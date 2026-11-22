#!/usr/bin/env python3
"""Render public RU/EN privacy pages from the same local JSON used by the Android app."""

import argparse
from html import escape
import json
from pathlib import Path
import re
from urllib.parse import quote

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = REPO_ROOT / "app/src/main/assets/privacy/policy.json"
DEFAULT_OUTPUT = REPO_ROOT / "docs/privacy"
STORE_SOURCE = REPO_ROOT / "app/src/store/assets/privacy/policy.json"
STORE_OUTPUT = DEFAULT_OUTPUT / "store"
TOKENS = {"{{DEVELOPER_NAME}}", "{{SUPPORT_EMAIL}}"}


def identity(document, developer_name=None, support_email=None):
    name = (developer_name if developer_name is not None else document["identity"]["public_name"]).strip()
    email = (support_email if support_email is not None else document["identity"]["support_email"]).strip()
    if not name or len(name) > 160 or any(ord(char) < 32 for char in name):
        raise ValueError("The public developer name must contain 1–160 printable characters")
    if len(email) > 254 or not re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+", email):
        raise ValueError("A valid public support email is required")
    if any(token in name or token in email for token in TOKENS):
        raise ValueError("Unresolved identity placeholders are not allowed")
    return name, email


def resolved(text, name, email):
    text = text.replace("{{DEVELOPER_NAME}}", name).replace("{{SUPPORT_EMAIL}}", email)
    if "{{" in text or "}}" in text:
        raise ValueError("An unresolved policy placeholder remains")
    return text


def render_page(document, language, developer_name=None, support_email=None):
    name, email = identity(document, developer_name, support_email)
    if document.get("schema_version") != 1:
        raise ValueError("Unsupported policy schema")
    date = document["updated_at"]
    version = document["policy_version"]
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date) or not str(version).strip():
        raise ValueError("A version and ISO policy date are required")
    locale = document["locales"][language]
    title = resolved(locale["title"], name, email)
    summary = resolved(locale["summary"], name, email)
    metadata = f"Обновлено: {date} · Версия {version}" if language == "ru" else f"Updated: {date} · Version {version}"
    contact_label = "Связаться по вопросам конфиденциальности" if language == "ru" else "Contact about privacy"
    switch_file, switch_text = ("en.html", "English") if language == "ru" else ("index.html", "Русский")
    sections = []
    for section in locale["sections"]:
        title_html = escape(resolved(section["title"], name, email))
        paragraphs = "\n".join(f"      <p>{escape(resolved(paragraph, name, email))}</p>" for paragraph in section["paragraphs"])
        sections.append(f"    <section>\n      <h2>{title_html}</h2>\n{paragraphs}\n    </section>")
    content = "\n".join(sections)
    mailto = quote(email, safe="@.-_+")
    return f'''<!doctype html>
<html lang="{language}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="{escape(summary, quote=True)}">
  <meta name="color-scheme" content="light dark">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
  <title>{escape(title)} — {escape(document["identity"]["app_name"])}</title>
  <style>
    :root {{ color-scheme: light dark; font: 18px/1.65 system-ui, sans-serif; }}
    body {{ margin: 0; background: #f8f6ef; color: #25352b; }}
    main {{ max-width: 48rem; margin: auto; padding: 2rem 1.25rem 4rem; }}
    nav {{ display: flex; justify-content: space-between; gap: 1rem; flex-wrap: wrap; margin-bottom: 2.5rem; }}
    nav span, .meta {{ color: #5b6b60; }}
    h1 {{ font: 600 clamp(2rem, 6vw, 3rem)/1.2 Georgia, serif; margin-bottom: 1rem; }}
    h2 {{ font-size: 1.3rem; line-height: 1.35; margin-top: 2.25rem; }}
    p {{ overflow-wrap: anywhere; }}
    a {{ color: #255b40; text-underline-offset: .2em; }}
    a:focus-visible {{ outline: 3px solid #a76d42; outline-offset: 4px; }}
    .summary {{ font-size: 1.1rem; border-left: 3px solid #b68861; padding-left: 1rem; }}
    footer {{ margin-top: 3rem; border-top: 1px solid #cbd4cb; padding-top: 1rem; }}
    @media (prefers-color-scheme: dark) {{
      body {{ background: #15211a; color: #e7e8df; }}
      a {{ color: #a7d5b5; }}
      nav span, .meta {{ color: #b4c5b9; }}
      footer {{ border-color: #435348; }}
    }}
  </style>
</head>
<body>
  <main>
    <nav aria-label="Language"><span>{escape(document["identity"]["app_name"])}</span><a href="{switch_file}" lang="{'en' if language == 'ru' else 'ru'}">{switch_text}</a></nav>
    <h1>{escape(title)}</h1>
    <p class="meta">{escape(metadata)}</p>
    <p class="summary">{escape(summary)}</p>
{content}
    <footer><p>{contact_label}: <a href="mailto:{escape(mailto, quote=True)}">{escape(email)}</a></p></footer>
  </main>
</body>
</html>
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("offline", "store"), default="offline",
                        help="Select the bundled policy and default publication directory")
    parser.add_argument("--source", type=Path, help="Override the selected variant's policy source")
    parser.add_argument("--output-dir", type=Path, help="Override the selected variant's publication directory")
    parser.add_argument("--developer-name", help="Public developer/app brand; do not invent a legal entity")
    parser.add_argument("--support-email", help="Public support contact shown in the policy")
    parser.add_argument("--check", action="store_true", help="Fail if checked-in pages differ; never writes files")
    args = parser.parse_args()
    source = args.source or (STORE_SOURCE if args.variant == "store" else DEFAULT_SOURCE)
    output_dir = args.output_dir or (STORE_OUTPUT if args.variant == "store" else DEFAULT_OUTPUT)
    document = json.loads(source.read_text(encoding="utf-8"))
    outputs = {"index.html": render_page(document, "ru", args.developer_name, args.support_email),
               "en.html": render_page(document, "en", args.developer_name, args.support_email)}
    if args.check:
        stale = [name for name, value in outputs.items()
                 if not (output_dir / name).is_file() or (output_dir / name).read_text(encoding="utf-8") != value]
        if stale:
            parser.exit(1, "Privacy pages are missing or stale: " + ", ".join(stale) +
                        f"\nRun python3 scripts/render_privacy.py --variant {args.variant}\n")
        print("Privacy pages match the bundled RU/EN policy.")
    else:
        output_dir.mkdir(parents=True, exist_ok=True)
        for name, value in outputs.items():
            (output_dir / name).write_text(value, encoding="utf-8")
        print("Rendered both privacy pages from the bundled policy.")


if __name__ == "__main__":
    main()
