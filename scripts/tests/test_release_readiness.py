import base64
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
        for name in ['release/release-config.json', 'release/store/metadata-ru.json', 'release/store/metadata-en.json',
                     'app/src/store/assets/privacy/policy.json']:
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
            path.write_text(json.dumps(data))

    def reports(self, signed=True, version=None, variant='store'):
        paths = []
        for ext in ('apk', 'aab'):
            data = {'artifact': f'candidate.{ext}', 'sha256': 'a' * 64, 'technicalChecksPassed': True,
                    'manifest': {'applicationId': self.config['applicationId'],
                                 'versionName': version or self.config['versionName'],
                                 'versionCode': self.config['versionCode'], 'variant': variant},
                    'candidateOnly': not signed,
                    'signature': {'verified': signed, 'certificateSha256': 'b' * 64}}
            path = self.root / f'{ext}.json'
            path.write_text(json.dumps(data))
            paths.append(path)
        return paths

    def approve(self):
        self.config['publisher'].update(legalName='Example self-employed publisher', developerAccountVerified=True)
        self.config['pro'].update(providerAccountVerified=True, sbpEnabled=True, receiptsConfigured=True,
                                  productionConfigured=True, backendUrl='https://payments.publisher.test',
                                  licensePublicKey=base64.b64encode(b'k' * 91).decode())
        self.config['ads'].update(productionConfigured=True, adUnitId='R-M-12345678-1')
        self.config['privacy']['publicUrlVerified'] = True
        self.config['signing'].update(ownerHasKeystoreBackup=True, certificateSha256='b' * 64)
        self.config['review'] = {key: True for key in self.config['review']}
        self.save()

    def test_default_preparation_is_blocked_without_configured_provider_and_owner(self):
        result = module.check(self.root)
        self.assertFalse(result['submissionReady'])
        self.assertEqual(0, result['installationPriceRub'])
        self.assertEqual(99, result['proPriceRub'])
        self.assertFalse(result['automaticDebits'])
        self.assertGreaterEqual(len(result['blockers']), 10)

    def test_owner_flags_cannot_make_unsigned_candidates_ready(self):
        self.approve()
        result = module.check(self.root, *self.reports(signed=False))
        self.assertFalse(result['submissionReady'])
        self.assertTrue(any('подписан' in message for message in result['blockers']))

    def test_verified_store_artifacts_and_confirmed_connections_can_be_ready(self):
        self.approve()
        self.assertTrue(module.check(self.root, *self.reports())['submissionReady'])

    def test_offline_reports_cannot_be_treated_as_commercial_store_release(self):
        self.approve()
        result = module.check(self.root, *self.reports(variant='offline'))
        self.assertFalse(result['submissionReady'])
        self.assertTrue(any('store-артефакты' in message for message in result['blockers']))

    def test_a_different_artifact_version_is_rejected(self):
        self.approve()
        self.assertFalse(module.check(self.root, *self.reports(version='0.6.0'))['submissionReady'])

    def test_store_price_cannot_silently_differ_from_approved_configuration(self):
        self.approve()
        path = self.root / 'release/store/metadata-ru.json'
        data = json.loads(path.read_text())
        data['monetization']['proPrice'] = 299
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'price'):
            module.check(self.root)

    def test_recurring_debits_cannot_replace_manual_sbp_plan(self):
        self.approve()
        self.config['pro']['automaticDebits'] = True
        self.save()
        with self.assertRaisesRegex(ValueError, 'automatic debits'):
            module.check(self.root)

    def test_installation_must_remain_free(self):
        self.approve()
        self.config['pricing']['price'] = 199
        self.save()
        with self.assertRaisesRegex(ValueError, 'free installation'):
            module.check(self.root)

    def test_network_permission_cannot_be_added_to_offline_flavor(self):
        self.approve()
        self.config['runtime']['offline']['internetPermission'] = True
        self.save()
        with self.assertRaisesRegex(ValueError, 'constraints'):
            module.check(self.root)

    def test_ad_sdk_technical_telemetry_cannot_be_hidden_from_store_description(self):
        self.approve()
        self.config['runtime']['store']['analytics'] = False
        self.save()
        with self.assertRaisesRegex(ValueError, 'constraints'):
            module.check(self.root)

    def test_blank_backend_and_license_key_remain_blocked_even_with_production_flag(self):
        self.approve()
        self.config['pro'].update(backendUrl='', licensePublicKey='')
        self.save()
        self.assertFalse(module.check(self.root, *self.reports())['submissionReady'])

    def test_demo_ad_unit_and_example_backend_remain_blocked(self):
        self.approve()
        self.config['ads']['adUnitId'] = 'demo-interstitial-yandex'
        self.config['pro']['backendUrl'] = 'https://payments.example.org'
        self.save()
        self.assertFalse(module.check(self.root, *self.reports())['submissionReady'])

    def test_receipts_and_third_party_payment_declaration_are_required(self):
        self.approve()
        self.config['pro']['receiptsConfigured'] = False
        self.config['review']['thirdPartyPaymentDeclared'] = False
        self.save()
        result = module.check(self.root, *self.reports())
        self.assertFalse(result['submissionReady'])
        self.assertTrue(any('чеков' in value for value in result['blockers']))
        self.assertTrue(any('сторонней оплаты' in value for value in result['blockers']))

    def test_a_foreign_signing_certificate_is_blocked(self):
        self.approve()
        self.config['signing']['certificateSha256'] = 'c' * 64
        self.save()
        self.assertFalse(module.check(self.root, *self.reports())['submissionReady'])

    def test_https_url_validation_rejects_credentials_and_insecure_addresses(self):
        for url in ['http://payments.publisher.test', 'https://secret@payments.publisher.test',
                    'https://payments.publisher.test:8080', 'https://localhost', 'https://payments.example.org',
                    'https://payments.publisher.test?secret=value', 'https://payments.publisher.test#fragment']:
            with self.subTest(url=url):
                self.assertFalse(module.public_https(url))
        self.assertTrue(module.public_https('https://payments.publisher.test/api'))


if __name__ == '__main__':
    unittest.main()
