#!/usr/bin/env python3
"""Privacy publication checks: identities cannot introduce HTML or unresolved placeholders."""

from copy import deepcopy
from html.parser import HTMLParser
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

import render_privacy


class PageInspector(HTMLParser):
    def __init__(self):
        super().__init__()
        self.tags = []
        self.links = []

    def handle_starttag(self, tag, attrs):
        self.tags.append(tag)
        if tag == "a":
            self.links.append(dict(attrs).get("href", ""))


class PrivacyPublicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = json.loads(render_privacy.DEFAULT_SOURCE.read_text(encoding="utf-8"))

    def testBothLanguagesRenderSameContactVersionAndNoUnresolvedTokens(self):
        for language in ("ru", "en"):
            with self.subTest(language=language):
                rendered = render_privacy.render_page(self.policy, language)
                self.assertIn(self.policy["identity"]["support_email"], rendered)
                self.assertIn(self.policy["updated_at"], rendered)
                self.assertNotIn("{{", rendered)
                inspector = PageInspector()
                inspector.feed(rendered)
                self.assertNotIn("script", inspector.tags)
                self.assertNotIn("img", inspector.tags)
                self.assertTrue(all(not link.startswith(("https:", "http:")) for link in inspector.links))

    def testDeveloperNameIsPlainTextEvenIfItContainsMarkup(self):
        rendered = render_privacy.render_page(self.policy, "en", '<script>alert("name")</script> & <b>Diary</b>')
        self.assertIn("&lt;script&gt;", rendered)
        self.assertIn("&amp; &lt;b&gt;Diary&lt;/b&gt;", rendered)
        inspector = PageInspector()
        inspector.feed(rendered)
        self.assertNotIn("script", inspector.tags)
        self.assertNotIn("b", inspector.tags)

    def testSupportEmailCannotInjectMailtoHeadersOrHtml(self):
        email = "contact?subject=private@example.com"
        rendered = render_privacy.render_page(self.policy, "en", support_email=email)
        inspector = PageInspector()
        inspector.feed(rendered)
        self.assertIn("mailto:contact%3Fsubject%3Dprivate@example.com", inspector.links)
        for invalid in ("", "person", "a@example.com\r\nBcc:other@example.com", 'a\"onclick=\"evil@example.com'):
            with self.subTest(email=invalid), self.assertRaises(ValueError):
                render_privacy.render_page(self.policy, "en", support_email=invalid)

    def testMissingIdentityOrUnknownTemplateTokenFailsBeforePublication(self):
        with self.assertRaises(ValueError):
            render_privacy.render_page(self.policy, "ru", developer_name=" ")
        with self.assertRaises(ValueError):
            render_privacy.render_page(self.policy, "ru", developer_name="{{DEVELOPER_NAME}}")
        changed = deepcopy(self.policy)
        changed["locales"]["en"]["summary"] += " {{UNRESOLVED_OWNER}}"
        with self.assertRaises(ValueError):
            render_privacy.render_page(changed, "en")

    def testCheckModeDetectsStalePagesAndNeverRewritesThem(self):
        script = Path(render_privacy.__file__).resolve()
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, str(script), "--output-dir", directory]
            subprocess.run(command, check=True, capture_output=True)
            subprocess.run(command + ["--check"], check=True, capture_output=True)
            output = Path(directory) / "en.html"
            output.write_text("Stale policy", encoding="utf-8")
            result = subprocess.run(command + ["--check"], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b"missing or stale", result.stderr)
            self.assertEqual("Stale policy", output.read_text(encoding="utf-8"))

    def testStoreVariantRendersItsOwnPolicyWithoutChangingOfflinePublication(self):
        script = Path(render_privacy.__file__).resolve()
        offline = {name: (render_privacy.DEFAULT_OUTPUT / name).read_bytes() for name in ("index.html", "en.html")}
        policy = json.loads(render_privacy.STORE_SOURCE.read_text(encoding="utf-8"))
        self.assertEqual(1, policy["schema_version"])
        self.assertEqual("2026-11-22", policy["updated_at"])
        self.assertNotEqual(self.policy["policy_version"], policy["policy_version"])
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, str(script), "--variant", "store", "--output-dir", directory]
            subprocess.run(command, check=True, capture_output=True)
            subprocess.run(command + ["--check"], check=True, capture_output=True)
            for language, name in (("ru", "index.html"), ("en", "en.html")):
                rendered = (Path(directory) / name).read_text(encoding="utf-8")
                self.assertEqual(render_privacy.render_page(policy, language), rendered)
                self.assertIn("Yandex Mobile Ads SDK 8.6.0", rendered)
                self.assertIn("99", rendered)
                self.assertIn("YooKassa", rendered)
                self.assertNotIn("{{", rendered)
                inspector = PageInspector()
                inspector.feed(rendered)
                self.assertNotIn("script", inspector.tags)
                self.assertNotIn("img", inspector.tags)
                self.assertTrue(all(not link.startswith(("https:", "http:")) for link in inspector.links))
                section_ids = {section["id"] for section in policy["locales"][language]["sections"]}
                self.assertTrue({"advertising", "payments"}.issubset(section_ids))
        for name, original in offline.items():
            self.assertEqual(original, (render_privacy.DEFAULT_OUTPUT / name).read_bytes())

    def testStorePublicationIsCurrentWithoutRegeneratingIt(self):
        script = Path(render_privacy.__file__).resolve()
        subprocess.run([sys.executable, str(script), "--variant", "store", "--check"],
                       check=True, capture_output=True)

    def testStoreOverridesEveryScreenStringThatPromisesNoInternetOrAnalytics(self):
        for qualifier in ("values", "values-ru"):
            source = render_privacy.REPO_ROOT / "app/src/main/res" / qualifier
            misleading = set()
            for file in source.glob("*.xml"):
                for item in ET.parse(file).getroot().findall("string"):
                    text = "".join(item.itertext()).lower()
                    if any(claim in text for claim in ("no internet", "no analytics", "no accounts, cloud sync or internet",
                        "fully offline", "нет разрешения на интернет", "без доступа в интернет", "без аналитики", "полностью офлайн", "и доступа в интернет")):
                        misleading.add(item.attrib["name"])
            target = render_privacy.REPO_ROOT / "app/src/store/res" / qualifier / "privacy_monetization.xml"
            overrides = {item.attrib["name"]: "".join(item.itertext()).lower()
                         for item in ET.parse(target).getroot().findall("string")}
            self.assertTrue(misleading, "Expected to audit offline privacy claims")
            self.assertTrue(misleading.issubset(overrides), f"Missing {qualifier} store overrides: {misleading - overrides.keys()}")
            for name in misleading:
                self.assertNotIn("no internet", overrides[name])
                self.assertNotIn("нет разрешения на интернет", overrides[name])


if __name__ == "__main__":
    unittest.main()
