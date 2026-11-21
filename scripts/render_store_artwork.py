#!/usr/bin/env python3
"""Render store artwork from the actual Android icon and the diary's paper palette.

Requires Python 3, Pillow, Inkscape, and Noto Sans / Noto Serif fonts.
No network calls, user diary data, or generated UI screenshots are involved.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
PALETTE = {
    "paper": "#F7F4EC",
    "ink": "#253B2F",
    "cover": "#234C3F",
    "sage": "#DFE9D8",
    "muted": "#65705F",
    "rule": "#DDDCD1",
    "terracotta": "#9A5E47",
    "glow": "#DCC69A",
}


def svg_color(value: str) -> str:
    """Android's eight-digit color uses ARGB, whereas SVG uses RGBA."""
    if value == "#00000000":
        return "none"
    if len(value) == 9:
        return "#" + value[3:] + value[1:3]
    return value


def icon_paths(icon_file: Path) -> list[str]:
    paths: list[str] = []
    for path in ET.parse(icon_file).getroot().findall("path"):
        attrs = {"d": path.attrib[ANDROID_NS + "pathData"]}
        mapping = {
            "fillColor": "fill",
            "strokeColor": "stroke",
            "strokeWidth": "stroke-width",
            "strokeLineCap": "stroke-linecap",
        }
        for android_name, svg_name in mapping.items():
            value = path.attrib.get(ANDROID_NS + android_name)
            if value is not None:
                attrs[svg_name] = svg_color(value) if android_name.endswith("Color") else value
        paths.append("<path " + " ".join(f'{name}="{html.escape(value, quote=True)}"' for name, value in attrs.items()) + "/>")
    if len(paths) != 10:
        raise ValueError("Review artwork conversion: the source icon has changed")
    return paths


def svg_document(width: int, height: int, body: str, title: str, description: str) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img">\n'
        f"<title>{html.escape(title)}</title>\n<desc>{html.escape(description)}</desc>\n"
        + body + "\n</svg>\n"
    )


def store_icon(paths: list[str]) -> str:
    # A store applies its own mask. Keep the Android artwork while filling its
    # rounded transparent corners with the same green, rather than baking a mask.
    body = f'<rect width="512" height="512" fill="{PALETTE["cover"]}"/>\n'
    body += '<g transform="scale(8)">' + "\n".join(paths) + "</g>"
    return svg_document(512, 512, body, "Private Diary", "The app's paper diary, bookmark, and warm gold sun on a forest-green background.")


def feature(paths: list[str], language: str) -> str:
    copy = {
        "ru": {
            "label": "ЛИЧНЫЙ ДНЕВНИК",
            "lines": ("Сохраните", "свой день."),
            "description": ("Поиск по смыслу.", "На вашем устройстве."),
            "footer": "Без рекламы  ·  Без аккаунта",
            "title": "Личный дневник — поиск по смыслу на устройстве",
            "alt": "Тёплая бумажная обложка с иллюстрацией дневника. Поиск по смыслу на устройстве, без рекламы и аккаунта.",
        },
        "en": {
            "label": "PRIVATE DIARY",
            "lines": ("Keep a little", "of every day."),
            "description": ("Search by meaning.", "On your device."),
            "footer": "No ads  ·  No account",
            "title": "Private Diary — semantic search on your device",
            "alt": "Warm paper artwork with a diary illustration. Search by meaning on your device, without ads or an account.",
        },
    }[language]
    esc = html.escape
    body = f'''<defs>
  <linearGradient id="paper" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#FBF8F1"/><stop offset="1" stop-color="{PALETTE['paper']}"/>
  </linearGradient>
  <clipPath id="canvas"><rect width="1024" height="500"/></clipPath>
</defs>
<g clip-path="url(#canvas)">
  <rect width="1024" height="500" fill="url(#paper)"/>
  <circle cx="868" cy="262" r="230" fill="{PALETTE['sage']}"/>
  <circle cx="876" cy="253" r="190" fill="none" stroke="#C7D6C0" stroke-width="1"/>
  <path d="M654 449C742 443 865 445 951 450" fill="none" stroke="#C2CFB9" stroke-width="1"/>
  <path d="M64 116H102" stroke="{PALETTE['terracotta']}" stroke-width="3" stroke-linecap="round"/>
  <text x="64" y="83" font-family="Noto Sans" font-weight="500" font-size="14" letter-spacing="2.6" fill="{PALETTE['cover']}">{esc(copy['label'])}</text>
  <text x="61" y="196" font-family="Noto Serif" font-size="54" fill="{PALETTE['ink']}">{esc(copy['lines'][0])}</text>
  <text x="61" y="268" font-family="Noto Serif" font-size="54" fill="{PALETTE['ink']}">{esc(copy['lines'][1])}</text>
  <text x="64" y="325" font-family="Noto Sans" font-size="21" fill="{PALETTE['muted']}">{esc(copy['description'][0])}</text>
  <text x="64" y="357" font-family="Noto Sans" font-size="21" fill="{PALETTE['muted']}">{esc(copy['description'][1])}</text>
  <path d="M64 403H528" fill="none" stroke="{PALETTE['rule']}" stroke-width="1"/>
  <g transform="translate(64 424)" fill="none" stroke="{PALETTE['cover']}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
    <rect x="1" y="-1" width="11" height="9" rx="2"/><path d="M3-1V-4A3.5 3.5 0 0 1 10-4V-1"/>
  </g>
  <text x="88" y="432" font-family="Noto Sans" font-size="14" fill="{PALETTE['muted']}">{esc(copy['footer'])}</text>
  <g transform="translate(612 51) scale(6.3) rotate(-8 33 35)">
    {''.join(paths[2:])}
  </g>
</g>'''
    return svg_document(1024, 500, body, copy["title"], copy["alt"])


def rasterize(source: Path, target: Path, size: tuple[int, int], mode: str, inkscape: str) -> None:
    with tempfile.TemporaryDirectory(prefix="private-diary-artwork-") as directory:
        environment = dict(os.environ)
        environment["INKSCAPE_PROFILE_DIR"] = str(Path(directory) / "inkscape")
        environment["XDG_CACHE_HOME"] = str(Path(directory) / "cache")
        result = subprocess.run([
            inkscape, str(source), "--export-type=png", f"--export-filename={target}",
            f"--export-width={size[0]}", f"--export-height={size[1]}",
        ], env=environment, capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError(f"Inkscape could not render {source.name}: {result.stderr.strip()}")
    with Image.open(target) as rendered:
        image = rendered.convert(mode)
        if image.size != size:
            raise ValueError(f"Unexpected dimensions for {target.name}: {image.size}")
        if mode == "RGBA" and image.getchannel("A").getextrema() != (255, 255):
            raise ValueError("Store icon must have an opaque full-square background")
        image.save(target, format="PNG", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "release/store/assets")
    args = parser.parse_args()
    inkscape = shutil.which("inkscape")
    if inkscape is None:
        raise SystemExit("Install Inkscape to render the SVG artwork; no downloads are performed by this script.")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    paths = icon_paths(ROOT / "app/src/main/res/drawable/ic_diary.xml")
    specs = [
        ("icon-512", store_icon(paths), (512, 512), "RGBA"),
        ("feature-ru", feature(paths, "ru"), (1024, 500), "RGB"),
        ("feature-en", feature(paths, "en"), (1024, 500), "RGB"),
    ]
    checksums: list[str] = []
    for name, svg, size, mode in specs:
        source = output / (name + ".svg")
        target = output / (name + ".png")
        source.write_text(svg, encoding="utf-8")
        rasterize(source, target, size, mode, inkscape)
        if target.stat().st_size > 1024 * 1024:
            raise ValueError(f"{target.name} exceeds 1 MiB")
        checksums.append(f"{hashlib.sha256(target.read_bytes()).hexdigest()}  {target.name}\n")
        print(f"{target.name}: {size[0]}×{size[1]}, {mode}, {target.stat().st_size} bytes")
    (output / "SHA256SUMS-artwork.txt").write_text("".join(checksums), encoding="ascii")


if __name__ == "__main__":
    main()
