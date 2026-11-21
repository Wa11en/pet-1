import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('readiness', ROOT / 'scripts/check_release_readiness.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReadinessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ['release/release-config.json', 'release/store/metadata-ru.json', 'release/store/metadata-en.json', 'app/src/main/assets/privacy/policy.json']:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())
        self.config_path = self.root / 'release/release-config.json'
        self.config = json.loads(self.config_path.read_text())

    def save(self):
        self.config_path.write_text(json.dumps(self.config))
        for lang in ('ru', 'en'):
            path = self.root / f'release/store/metadata-{lang}.json'
            data = json.loads(path.read_text())
            data['publisher']['legalName'] = self.config['publisher']['legalName']
            data['monetization']['price'] = self.config['pricing']['price']
            data['monetization']['priceConfirmed'] = self.config['pricing']['confirmed']
            data['monetization']['merchantEligible'] = self.config['publisher']['merchantEligible']
            path.write_text(json.dumps(data))

    def reports(self, signed=True, version='1.0.0-rc1'):
        paths = []
        for ext in ('apk', 'aab'):
            data = {'artifact': f'candidate.{ext}', 'sha256': 'a' * 64, 'technicalChecksPassed': True,
                    'manifest': {'applicationId': self.config['applicationId'], 'versionName': version, 'versionCode': 9},
                    'candidateOnly': not signed,
                    'signature': {'verified': signed, 'certificateSha256': 'b' * 64}}
            path = self.root / f'{ext}.json'
            path.write_text(json.dumps(data))
            paths.append(path)
        return paths

    def approve(self):
        self.config['publisher'].update(legalName='Example eligible publisher', merchantEligible=True, sellerAccountVerified=True)
        self.config['pricing'].update(price=199, confirmed=True)
        self.config['privacy']['publicUrlVerified'] = True
        self.config['signing'].update(ownerHasKeystoreBackup=True, certificateSha256='b' * 64)
        self.config['review'] = {key: True for key in self.config['review']}
        self.save()

    def test_default_preparation_is_blocked_without_owner_or_store_approval(self):
        result = module.check(self.root)
        self.assertFalse(result['submissionReady'])
        self.assertGreaterEqual(len(result['blockers']), 6)

    def test_owner_flags_cannot_make_unsigned_candidates_ready(self):
        self.approve()
        result = module.check(self.root, *self.reports(signed=False))
        self.assertFalse(result['submissionReady'])
        self.assertTrue(any('подписан' in message for message in result['blockers']))

    def test_verified_artifacts_and_owner_decisions_can_be_ready(self):
        self.approve()
        self.assertTrue(module.check(self.root, *self.reports())['submissionReady'])

    def test_a_different_artifact_version_is_rejected(self):
        self.approve()
        self.assertFalse(module.check(self.root, *self.reports(version='0.6.0'))['submissionReady'])

    def test_store_price_cannot_silently_differ_from_owner_configuration(self):
        self.approve()
        path = self.root / 'release/store/metadata-ru.json'
        data = json.loads(path.read_text()); data['monetization']['price'] = 299
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'price'):
            module.check(self.root)

    def test_network_or_advertising_constraints_cannot_be_waived_by_owner_flags(self):
        self.approve()
        self.config['runtime']['internetPermission'] = True
        self.save()
        with self.assertRaisesRegex(ValueError, 'constraints'):
            module.check(self.root)

    def test_a_foreign_signing_certificate_is_blocked(self):
        self.approve()
        self.config['signing']['certificateSha256'] = 'c' * 64
        self.save()
        self.assertFalse(module.check(self.root, *self.reports())['submissionReady'])


if __name__ == '__main__':
    unittest.main()
