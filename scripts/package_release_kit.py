#!/usr/bin/env python3
"""Package only public RuStore preparation materials; never publish or sign an app."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GUIDES = ('START_HERE', 'RUSTORE', 'REVIEWER', 'SIGNING', 'VALIDATION',
          'RELEASE_CHECKLIST', 'DATA_DISCLOSURE')
TEXT_FIELDS = ('name', 'short-description', 'full-description', 'release-notes',
               'screenshot-captions', 'help-faq', 'support-template')
LEGAL_FILES = ('README.md', 'OWNER_FIELDS.md', 'PRIVACY_ADDENDUM.template.ru.md',
               'PRO_TERMS.template.ru.md', 'REFUNDS.template.ru.md',
               'CONSENT_DISCLOSURES.ru.md', 'SUPPORT_TEMPLATES.ru.md')
ARTWORK_NAMES = ('icon-512', 'feature-ru', 'feature-en')
SCREENSHOT_NAMES = tuple(f'{language}-{scene}.png' for language in ('ru', 'en')
                         for scene in ('journal', 'editor', 'search', 'calendar'))
SCREENSHOT_PREFIX = 'release/store/screenshots/'
PROVENANCE_PATH = SCREENSHOT_PREFIX + 'provenance.json'
PUBLIC_PATHS = tuple(sorted(
    [f'release/{name}.md' for name in GUIDES] +
    ['release/release-config.json', 'release/store/README.md'] +
    [f'release/store/metadata-{language}.json' for language in ('ru', 'en')] +
    [f'release/store/{prefix}{language}/{field}.txt'
     for prefix in ('', 'current-preview/') for language in ('ru', 'en') for field in TEXT_FIELDS] +
    [f'release/store/assets/{name}.{extension}' for name in ARTWORK_NAMES for extension in ('png', 'svg')] +
    ['release/store/assets/README.md', 'release/store/assets/SHA256SUMS-artwork.txt'] +
    [f'release/legal/{name}' for name in LEGAL_FILES] +
    ['docs/privacy/store/index.html', 'docs/privacy/store/en.html',
     'app/src/store/assets/privacy/policy.json', 'app/src/main/assets/model/LICENSE-E5.txt',
     'backend/README.md']))
ZIP_TIMESTAMP = (2026, 11, 22, 0, 0, 0)


def _read(root, relative, optional=False):
    """Reject symlinks, including intermediate directories, before reading an input."""
    path = root
    for part in Path(relative).parts:
        path = path / part
        if path.is_symlink():
            raise ValueError(f'Symlink is not a public release input: {relative}')
    if not path.exists():
        if optional:
            return None
        raise ValueError(f'Missing required public material: {relative}')
    if not path.resolve().is_relative_to(root) or not stat.S_ISREG(path.stat().st_mode):
        raise ValueError(f'Input must be a regular file inside the root: {relative}')
    return path.read_bytes()


def _text(data, name):
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError as error:
        raise ValueError(f'Public text must be UTF-8: {name}') from error


def _json(data, name):
    try:
        result = json.loads(_text(data, name))
    except json.JSONDecodeError as error:
        raise ValueError(f'Invalid JSON: {name}') from error
    if not isinstance(result, dict):
        raise ValueError(f'JSON must be an object: {name}')
    return result


def _png_size(data, name):
    if (len(data) < 33 or data[:8] != b'\x89PNG\r\n\x1a\n' or
            data[8:12] != b'\x00\x00\x00\r' or data[12:16] != b'IHDR'):
        raise ValueError(f'Invalid PNG header: {name}')
    return struct.unpack('>II', data[16:24])


def _check_metadata(payload, config):
    limits = {'name': 30, 'short-description': 80, 'full-description': 4000, 'release-notes': 500}
    mapping = {'name': 'displayName', 'short-description': 'shortDescription', 'full-description': 'fullDescription'}
    for language in ('ru', 'en'):
        name = f'release/store/metadata-{language}.json'
        metadata = _json(payload[name], name)
        if metadata.get('applicationId') != config.get('applicationId'):
            raise ValueError(f'Metadata application ID differs from release configuration: {name}')
        for prefix in ('', 'current-preview/'):
            for field, limit in limits.items():
                path = f'release/store/{prefix}{language}/{field}.txt'
                value = _text(payload[path], path).removesuffix('\n')
                if not value.strip() or len(value) > limit:
                    raise ValueError(f'{path} must contain 1..{limit} Unicode characters')
                if not prefix and field in mapping and value != metadata.get(mapping[field]):
                    raise ValueError(f'Metadata/TXT mismatch: {path}')


def _check_artwork(payload):
    for name in ARTWORK_NAMES:
        path = f'release/store/assets/{name}.png'
        expected = (512, 512) if name == 'icon-512' else (1024, 500)
        if _png_size(payload[path], path) != expected:
            raise ValueError(f'Incorrect artwork dimensions: {path}; expected {expected}')
    checksum_path = 'release/store/assets/SHA256SUMS-artwork.txt'
    checksums = {}
    for line in _text(payload[checksum_path], checksum_path).splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  (icon-512|feature-ru|feature-en)\.png', line)
        if not match or match[2] in checksums:
            raise ValueError(f'Invalid or duplicate artwork checksum: {checksum_path}')
        checksums[match[2]] = match[1]
    if set(checksums) != set(ARTWORK_NAMES):
        raise ValueError('Artwork checksums must cover exactly the three public PNGs')
    for name, digest in checksums.items():
        if hashlib.sha256(payload[f'release/store/assets/{name}.png']).hexdigest() != digest:
            raise ValueError(f'Artwork checksum mismatch: {name}.png')


def _check_screenshots(root, payload, allow_missing):
    missing = []
    for name in SCREENSHOT_NAMES:
        path = SCREENSHOT_PREFIX + name
        data = _read(root, path, optional=allow_missing)
        if data is None:
            missing.append(path)
        else:
            payload[path] = data
    data = _read(root, PROVENANCE_PATH, optional=allow_missing)
    if data is None:
        missing.append(PROVENANCE_PATH)
        if any(SCREENSHOT_PREFIX + name in payload for name in SCREENSHOT_NAMES):
            raise ValueError('Existing screenshots require provenance, including in a draft kit')
        return missing
    payload[PROVENANCE_PATH] = data
    provenance = _json(data, PROVENANCE_PATH)
    if (provenance.get('schemaVersion') != 1 or
            not re.fullmatch(r'https://github\.com/[^/?#]+/[^/?#]+/actions/runs/[0-9]+',
                             str(provenance.get('sourceRunUrl', ''))) or
            not re.fullmatch(r'[0-9a-f]{40}', str(provenance.get('sourceCommit', '')))):
        raise ValueError('Screenshot provenance needs schemaVersion 1, a source run URL and full commit SHA')
    files = provenance.get('files')
    if not isinstance(files, dict) or set(files) != set(SCREENSHOT_NAMES):
        raise ValueError('Screenshot provenance must describe exactly eight named UI screenshots')
    for name in SCREENSHOT_NAMES:
        path = SCREENSHOT_PREFIX + name
        if path not in payload:
            continue
        details = files[name]
        dimensions = _png_size(payload[path], path)
        if (not isinstance(details, dict) or dimensions != (1080, 1920) or
                (details.get('width'), details.get('height')) != dimensions or
                details.get('sha256') != hashlib.sha256(payload[path]).hexdigest()):
            raise ValueError(f'Screenshot dimensions or provenance hash mismatch: {name}')
    return missing


def _readiness(root):
    # Use this script's trusted checker, rather than execute code from --root.
    spec = importlib.util.spec_from_file_location('kit_release_readiness', Path(__file__).with_name('check_release_readiness.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    result = module.check(root)
    result['submissionReady'] = False
    result['publishedToStore'] = False
    return result


def _json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n').encode('utf-8')


def build_kit(root=ROOT, output=None, allow_missing_screenshots=False):
    """Return output/hash/status after writing the deterministic ZIP and SHA256 sibling.

    MANIFEST.json indexes every other archive entry, including generated README/READINESS.
    Missing screenshots are allowed only by explicit draft mode; invalid inputs always fail.
    """
    root = Path(root).absolute()
    if root.is_symlink() or root.resolve() != root or not root.is_dir():
        raise ValueError('Root must be an existing directory without symlinks')
    payload = {name: _read(root, name) for name in PUBLIC_PATHS}
    for name, data in payload.items():
        if not name.endswith('.png'):
            _text(data, name)
        if name.endswith('.json'):
            _json(data, name)
    config = _json(payload['release/release-config.json'], 'release/release-config.json')
    version = config.get('versionName')
    if (not isinstance(version, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]{0,79}', version) or
            type(config.get('versionCode')) is not int or config['versionCode'] < 1):
        raise ValueError('Invalid release version name or version code')
    _check_metadata(payload, config)
    _check_artwork(payload)
    missing = _check_screenshots(root, payload, allow_missing_screenshots)
    readiness = _readiness(root)
    readiness['materialsComplete'] = not missing
    readiness['missingMaterials'] = missing
    payload['READINESS.json'] = _json_bytes(readiness)
    payload['README.txt'] = (
        'Личный дневник / Private Diary — материалы для RuStore\n\n'
        'Начните с release/START_HERE.md. Пути и ссылки сохраняют структуру репозитория.\n'
        f'Версия: {version} ({config["versionCode"]}).\n'
        f'Комплект материалов полный: {"да" if not missing else "нет"}.\n'
        'В архиве нет установочного APK/AAB, ключей и данных дневника.\n'
        'Приложение не опубликовано в RuStore. Готовность к отправке: нет.\n'
        'Оставшиеся реквизиты, подключения и проверки перечислены в READINESS.json.\n'
    ).encode('utf-8')
    manifest = {'schemaVersion': 1, 'versionName': version, 'versionCode': config['versionCode'],
                'materialsComplete': not missing, 'submissionReady': False, 'publishedToStore': False,
                'files': [{'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                          for name, data in sorted(payload.items())]}
    payload['MANIFEST.json'] = _json_bytes(manifest)
    output = Path(output).absolute() if output else root / f'release/artifacts/Private-Diary-RuStore-Release-Kit-{version}.zip'
    # Check the original spelling before removing '..', which could otherwise hide a symlink.
    if output.is_symlink() or any(parent.is_symlink() for parent in output.parents):
        raise ValueError('Output and its directories must not be symlinks')
    output = Path(os.path.abspath(output))
    checksum_output = output.with_suffix(output.suffix + '.sha256')
    reserved_paths = set(payload) | set(PUBLIC_PATHS) | {PROVENANCE_PATH} | {
        SCREENSHOT_PREFIX + name for name in SCREENSHOT_NAMES}
    for path in (output, checksum_output):
        if path.is_symlink() or any(parent.is_symlink() for parent in path.parents):
            raise ValueError('Output and its directories must not be symlinks')
        if path.is_relative_to(root) and path.relative_to(root).as_posix() in reserved_paths:
            raise ValueError('Output must not replace a public input')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix='.release-kit-', suffix='.zip', delete=False) as stream:
            temporary = Path(stream.name)
        with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, data in sorted(payload.items()):
                info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data, compresslevel=9)
        digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
        os.replace(temporary, output)
        checksum_output.write_text(f'{digest}  {output.name}\n', encoding='utf-8')
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return {'output': str(output), 'sha256': digest, 'materialsComplete': not missing,
            'submissionReady': False, 'publishedToStore': False,
            'fileCount': len(payload), 'readiness': readiness}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--allow-missing-screenshots', action='store_true', help='Create an explicitly incomplete draft kit')
    args = parser.parse_args()
    try:
        result = build_kit(args.root, args.output, args.allow_missing_screenshots)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f'Release kit packaging failed: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
