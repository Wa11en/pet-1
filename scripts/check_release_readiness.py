#!/usr/bin/env python3
"""Check free RuStore distribution, explicit ads and manually renewed SBP Pro readiness."""
import argparse
import base64
import binascii
import json
import hashlib
import ipaddress
import re
from pathlib import Path
import sys
from urllib.parse import urlsplit, unquote

ROOT = Path(__file__).resolve().parents[1]


def public_https(value):
    try:
        if not isinstance(value, str) or not 1 <= len(value) <= 8192 or any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in value):
            return False
        url = urlsplit(value or '')
        host = (url.hostname or '').lower()
        labels = host.split('.')
        if len(labels) < 2 or any(not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', item) for item in labels):
            return False
        if any(host == suffix or host.endswith('.' + suffix) for suffix in (
                'localhost', 'local', 'internal', 'test', 'invalid', 'example', 'example.com', 'example.net', 'example.org')):
            return False
        try:
            ipaddress.ip_address(host)
            return False
        except ValueError:
            pass
        return (url.scheme == 'https' and url.username is None and url.password is None and url.port in (None, 443) and
                '?' not in value and '#' not in value and
                not any(ord(char) < 32 or ord(char) == 127 for char in unquote(url.path)))
    except ValueError:
        return False


def public_legal_url(value):
    return public_https(value) and urlsplit(value).port is None


def public_key_present(value):
    try:
        # Shape check only; the Android verifier and deployment tests must also validate the key.
        return isinstance(value, str) and len(base64.b64decode(value, validate=True)) >= 80
    except (ValueError, binascii.Error):
        return False


def check(root, apk_report=None, aab_report=None):
    config = json.loads((root / 'release/release-config.json').read_text())
    if config.get('schemaVersion') != 2:
        raise ValueError('Unsupported release configuration')
    publisher, pricing, privacy, pro, ads = (config[key] for key in ('publisher', 'pricing', 'privacy', 'pro', 'ads'))
    if config.get('distribution') != 'free_with_ads_and_pro' or pricing != {'currency': 'RUB', 'price': 0, 'confirmed': True}:
        raise ValueError('The approved store distribution requires a free installation')
    expected_pro = {'planId': 'pro_monthly', 'price': 99, 'currency': 'RUB', 'period': 'P1M',
                    'renewal': 'manual_sbp', 'automaticDebits': False, 'provider': 'YooKassa'}
    if any(pro.get(key) != value for key, value in expected_pro.items()) or set(pro.get('features', [])) != {'ad_free', 'local_insights'}:
        raise ValueError('Pro must be RUB 99 per calendar month, manually renewed by SBP without automatic debits')
    expected_runtime = {
        'offline': {'ads': False, 'analytics': False, 'internetPermission': False, 'modelBundled': True},
        'store': {'ads': True, 'analytics': 'ad_sdk_technical_telemetry', 'analyticsScope': 'ads_process_only',
                  'internetPermission': True, 'modelBundled': True},
    }
    if config.get('runtime') != expected_runtime or ads.get('provider') != 'YandexMobileAds' or ads.get('diaryDataShared') is not False or ads.get('userInitiatedOnly') is not True:
        raise ValueError('Offline/store network, model and ad privacy constraints changed')
    blockers = []
    if not publisher.get('legalName'):
        blockers.append('Указать юридическое имя владельца.')
    if not publisher.get('developerAccountVerified'):
        blockers.append('Подтвердить аккаунт разработчика RuStore для бесплатной публикации.')
    if not pro.get('providerAccountVerified') or not pro.get('sbpEnabled'):
        blockers.append('Подтвердить договор YooKassa для статуса владельца и подключение СБП.')
    if not pro.get('receiptsConfigured'):
        blockers.append('Подтвердить реальную выдачу чеков НПД/провайдера для статуса владельца.')
    if not pro.get('productionConfigured') or not public_https(pro.get('backendUrl')) or not public_key_present(pro.get('licensePublicKey')):
        blockers.append('Развернуть рабочий HTTPS-сервер оплаты и настроить публичный ключ проверки лицензии; пустые параметры не готовы.')
    if not public_legal_url(pro.get('termsUrl')) or not public_legal_url(pro.get('refundUrl')):
        blockers.append('Опубликовать заполненные условия Pro и возвратов и добавить их HTTPS-ссылки перед оплатой.')
    ad_unit = ads.get('adUnitId')
    if (not ads.get('productionConfigured') or not isinstance(ad_unit, str) or
            re.fullmatch(r'R-M-[0-9]+-[0-9]+', ad_unit) is None):
        blockers.append('Подключить собственный production-блок рекламы; демонстрационные/пустые параметры не готовы.')
    if not public_https(privacy.get('url')) or not privacy.get('publicUrlVerified'):
        blockers.append('Проверить публичный HTTPS-адрес политики store с раскрытием рекламы и оплаты.')
    if not config['signing'].get('ownerHasKeystoreBackup') or not config['signing'].get('certificateSha256'):
        blockers.append('Владелец должен сохранить резервную копию ключа подписи и указать отпечаток сертификата.')
    for field, label in [('ageRatingConfirmed', 'возрастной рейтинг'), ('ownerApprovedStoreCopy', 'описания для магазина'),
                         ('storeScreenshotsReviewed', 'снимки интерфейса'), ('adDisclosureConfirmed', 'раскрытие рекламы и данных SDK'),
                         ('thirdPartyPaymentDeclared', 'раскрытие прямой сторонней оплаты СБП в RuStore'),
                         ('paymentDisclosuresTested', 'сведения о продавце и доступность условий/возвратов перед оплатой'),
                         ('coreOfflineDeviceTested', 'проверку бесплатных функций в авиарежиме на телефоне')]:
        if not config['review'].get(field):
            blockers.append('Подтвердить: ' + label + '.')
    policy = json.loads((root / 'app/src/store/assets/privacy/policy.json').read_text())
    if publisher.get('legalName') and policy['identity'].get('legal_name') != publisher['legalName']:
        blockers.append('Указать то же юридическое имя владельца в store-политике, карточке и параметрах APK.')
    if policy['identity']['support_email'] != publisher['supportEmail'] or policy['policy_version'] != privacy['policyVersion']:
        raise ValueError('Store policy version or support email differs from the release configuration')
    for language in ('ru', 'en'):
        metadata = json.loads((root / f'release/store/metadata-{language}.json').read_text())
        if (metadata['applicationId'] != config['applicationId'] or len(metadata['displayName']) > 30 or
                len(metadata['shortDescription']) > 80 or len(metadata['fullDescription']) > 4000):
            raise ValueError('Invalid store metadata or description length')
        if (metadata['publisher']['supportEmail'] != publisher['supportEmail'] or
                metadata['publisher']['privacyPolicyUrl'] != privacy['url'] or
                metadata['publisher']['legalName'] != publisher['legalName']):
            raise ValueError('Store contacts differ from the release configuration')
        monetization = metadata['monetization']
        expected_monetization = {'type': config['distribution'], 'currency': 'RUB', 'price': 0, 'ads': True,
                                 'inAppPurchases': True, 'subscriptions': False, 'paymentProvider': 'YooKassa',
                                 'paymentMethod': 'sbp', 'proPrice': 99, 'proPeriod': 'P1M',
                                 'renewal': 'manual', 'automaticDebits': False}
        if monetization != expected_monetization:
            raise ValueError('Store price or monetization differs from the approved release configuration')
    artifacts = []
    for path, suffix in ((apk_report, '.apk'), (aab_report, '.aab')):
        if path is None or not Path(path).is_file():
            blockers.append('Предоставить проверенный подписанный store APK/AAB и отчёты проверки.')
            continue
        data = json.loads(Path(path).read_text())
        artifacts.append({'artifact': data.get('artifact'), 'sha256': data.get('sha256'),
                          'unsignedCandidate': data.get('candidateOnly', True)})
        if not data.get('technicalChecksPassed'):
            blockers.append('Техническая проверка артефакта не пройдена.')
        if not str(data.get('artifact', '')).endswith(suffix):
            blockers.append('Отчёт не соответствует ожидаемому типу APK/AAB.')
        manifest = data.get('manifest', {})
        if manifest.get('variant') != 'store':
            blockers.append('Для коммерческого выпуска нужны store-артефакты; offline не включает рекламу/оплату.')
        if (manifest.get('applicationId'), manifest.get('versionName'), manifest.get('versionCode')) != (
                config['applicationId'], config['versionName'], config['versionCode']):
            blockers.append('Версия или идентификатор артефакта отличается от release-config.json.')
        settings = data.get('buildSettings', {})
        key = pro.get('licensePublicKey')
        expected_settings = {
            'distribution': 'store', 'networkFeatures': True,
            'yanAdUnitId': ads.get('adUnitId') or '', 'paymentBackendUrl': pro.get('backendUrl') or '',
            'proLicensePublicKeySha256': hashlib.sha256(key.encode('utf-8')).hexdigest() if isinstance(key, str) and key.strip() else None,
            'sellerLegalName': publisher.get('legalName') or '', 'proTermsUrl': pro.get('termsUrl') or '',
            'proRefundUrl': pro.get('refundUrl') or '',
        }
        if settings.get('present') is not True:
            blockers.append('Отчёт должен проверять настройки BuildConfig из байткода самого APK/AAB.')
        elif any(settings.get(field) != expected for field, expected in expected_settings.items()):
            blockers.append('Настройки монетизации/продавца в APK/AAB отличаются от release-config.json; собрать заново с нужными Gradle properties.')
        signed = data.get('signature', {})
        if not signed.get('verified') or data.get('candidateOnly', True):
            blockers.append('Кандидат не подписан производственным ключом владельца.')
        elif signed.get('certificateSha256') != config['signing'].get('certificateSha256'):
            blockers.append('Сертификат артефакта не совпадает с подтверждённым ключом владельца.')
    blockers = list(dict.fromkeys(blockers))
    return {'applicationId': config['applicationId'], 'versionName': config['versionName'],
            'distribution': config['distribution'], 'submissionReady': not blockers,
            'installationPriceRub': 0, 'proPriceRub': 99, 'renewal': 'manual_sbp', 'automaticDebits': False,
            'supportEmail': publisher['supportEmail'], 'blockers': blockers, 'artifacts': artifacts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report-only', action='store_true', help='Report remaining work without authorizing submission')
    parser.add_argument('--report', type=Path)
    parser.add_argument('--apk-report', type=Path)
    parser.add_argument('--aab-report', type=Path)
    args = parser.parse_args()
    try:
        report = check(ROOT, args.apk_report, args.aab_report)
        output = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(output)
        print(output)
        return 0 if args.report_only or report['submissionReady'] else 1
    except (ValueError, OSError, KeyError) as error:
        print(f'Release readiness check failed: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
