"""Public-data boundaries and honest readiness checks for the release material ZIP."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release_kit', ROOT / 'scripts/package_release_kit.py')
kit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kit)


def png(width, height):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    pixels = (b'\x00' + b'\xff\xff\xff\xff' * width) * height
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b''))


class ReleaseKitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        self.output = Path(self.temp.name) / 'kit.zip'
        for name in kit.PUBLIC_PATHS:
            self.write(name, b'Public release material.\n')
        for name in ('release/release-config.json', 'app/src/store/assets/privacy/policy.json'):
            self.write(name, (ROOT / name).read_bytes())
        for language in ('ru', 'en'):
            metadata_path = f'release/store/metadata-{language}.json'
            metadata = json.loads((ROOT / metadata_path).read_text(encoding='utf-8'))
            self.write(metadata_path, json.dumps(metadata, ensure_ascii=False).encode('utf-8'))
            for prefix in ('', 'current-preview/'):
                for field, key in [('name', 'displayName'), ('short-description', 'shortDescription'),
                                   ('full-description', 'fullDescription')]:
                    self.write(f'release/store/{prefix}{language}/{field}.txt', (metadata[key] + '\n').encode('utf-8'))
        checksums = []
        for name in kit.ARTWORK_NAMES:
            dimensions = (512, 512) if name == 'icon-512' else (1024, 500)
            data = png(*dimensions)
            self.write(f'release/store/assets/{name}.png', data)
            self.write(f'release/store/assets/{name}.svg', b'<svg xmlns="http://www.w3.org/2000/svg"/>\n')
            checksums.append(f'{hashlib.sha256(data).hexdigest()}  {name}.png\n')
        self.write('release/store/assets/SHA256SUMS-artwork.txt', ''.join(checksums).encode())
        image = png(1080, 1920)
        files = {}
        for name in kit.SCREENSHOT_NAMES:
            self.write(kit.SCREENSHOT_PREFIX + name, image)
            files[name] = {'sha256': hashlib.sha256(image).hexdigest(), 'width': 1080, 'height': 1920}
        provenance = {'schemaVersion': 1, 'sourceRunUrl': 'https://github.com/Wa11en/pet-1/actions/runs/123',
                      'sourceCommit': 'a' * 40, 'files': files}
        self.write(kit.PROVENANCE_PATH, json.dumps(provenance).encode())

    def write(self, relative, data):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def build(self, **kwargs):
        return kit.build_kit(self.root, self.output, **kwargs)

    def test_complete_pack_keeps_links_hashes_and_false_submission_status(self):
        result = self.build()
        self.assertTrue(result['materialsComplete'])
        self.assertFalse(result['submissionReady'])
        with zipfile.ZipFile(self.output) as archive:
            self.assertIn('release/START_HERE.md', archive.namelist())
            self.assertIn('release/START_HERE.md', archive.read('README.txt').decode())
            manifest = json.loads(archive.read('MANIFEST.json'))
            expected = json.loads((self.root / 'release/release-config.json').read_text(encoding='utf-8'))
            self.assertEqual(expected['versionCode'], manifest['versionCode'])
            self.assertFalse(manifest['publishedToStore'])
            self.assertFalse(json.loads(archive.read('READINESS.json'))['submissionReady'])
            self.assertEqual(set(archive.namelist()) - {'MANIFEST.json'}, {item['path'] for item in manifest['files']})
            for item in manifest['files']:
                data = archive.read(item['path'])
                self.assertEqual(len(data), item['bytes'])
                self.assertEqual(hashlib.sha256(data).hexdigest(), item['sha256'])
        self.assertEqual(f'{result["sha256"]}  kit.zip\n', self.output.with_suffix('.zip.sha256').read_text())

    def test_unlisted_keys_diaries_artifacts_and_secret_symlinks_are_excluded(self):
        for name in ('.env', 'release/owner.jks', 'release/artifacts/private.apk',
                     'release/artifacts/private.aab', 'diary.db', 'release/store/screenshots/private.png'):
            self.write(name, b'PRIVATE_SECRET_MUST_NEVER_BE_PACKAGED')
        (self.root / 'release/unlisted-key.pem').symlink_to(self.root / '.env')
        self.build()
        with zipfile.ZipFile(self.output) as archive:
            self.assertNotIn('diary.db', archive.namelist())
            self.assertFalse(any(b'PRIVATE_SECRET_MUST_NEVER_BE_PACKAGED' in archive.read(name) for name in archive.namelist()))

    def test_input_symlink_and_output_input_collision_fail(self):
        guide = self.root / 'release/START_HERE.md'
        guide.unlink()
        guide.symlink_to(self.root / 'release/RUSTORE.md')
        with self.assertRaisesRegex(ValueError, 'Symlink'):
            self.build()
        guide.unlink()
        guide.write_text('Public guide\n')
        with self.assertRaisesRegex(ValueError, 'replace a public input'):
            kit.build_kit(self.root, guide)
        alias = self.root / 'release/unused/../START_HERE.md'
        with self.assertRaisesRegex(ValueError, 'replace a public input'):
            kit.build_kit(self.root, alias)
        self.assertEqual('Public guide\n', guide.read_text())
        canonical = self.root / 'release/store/ru'
        moved = self.root / 'ru-real'
        canonical.rename(moved)
        canonical.symlink_to(moved, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'Symlink'):
            self.build()

    def test_metadata_mismatch_and_overlong_preview_fail(self):
        name = 'release/store/ru/name.txt'
        self.write(name, 'Другое название\n'.encode())
        with self.assertRaisesRegex(ValueError, 'Metadata/TXT mismatch'):
            self.build()
        metadata = json.loads((self.root / 'release/store/metadata-ru.json').read_text())
        self.write(name, (metadata['displayName'] + '\n').encode())
        self.write('release/store/current-preview/en/release-notes.txt', b'x' * 501)
        with self.assertRaisesRegex(ValueError, '1..500'):
            self.build()

    def test_missing_screens_require_explicit_incomplete_draft(self):
        for name in kit.SCREENSHOT_NAMES:
            (self.root / (kit.SCREENSHOT_PREFIX + name)).unlink()
        (self.root / kit.PROVENANCE_PATH).unlink()
        with self.assertRaisesRegex(ValueError, 'Missing required'):
            self.build()
        result = self.build(allow_missing_screenshots=True)
        self.assertFalse(result['materialsComplete'])
        with zipfile.ZipFile(self.output) as archive:
            self.assertFalse(json.loads(archive.read('MANIFEST.json'))['materialsComplete'])
            self.assertEqual(9, len(json.loads(archive.read('READINESS.json'))['missingMaterials']))
        with self.assertRaisesRegex(ValueError, 'replace a public input'):
            kit.build_kit(self.root, self.root / (kit.SCREENSHOT_PREFIX + 'ru-journal.png'),
                          allow_missing_screenshots=True)

    def test_modified_screenshot_never_passes_even_in_draft_mode(self):
        path = self.root / (kit.SCREENSHOT_PREFIX + 'ru-journal.png')
        path.write_bytes(path.read_bytes() + b'changed bytes')
        for draft in (False, True):
            with self.subTest(draft=draft), self.assertRaisesRegex(ValueError, 'provenance hash mismatch'):
                self.build(allow_missing_screenshots=draft)

    def test_zip_is_deterministic_and_permissions_do_not_follow_input(self):
        self.build()
        original = self.output.read_bytes()
        guide = self.root / 'release/START_HERE.md'
        os.utime(guide, (1, 1))
        guide.chmod(0o777)
        self.build()
        self.assertEqual(original, self.output.read_bytes())
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(sorted(archive.namelist()), archive.namelist())
            for entry in archive.infolist():
                self.assertEqual(kit.ZIP_TIMESTAMP, entry.date_time)
                self.assertEqual(0o644, (entry.external_attr >> 16) & 0o777)


if __name__ == '__main__':
    unittest.main()
