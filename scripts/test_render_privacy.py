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


if __name__ == "__main__":
    unittest.main()
