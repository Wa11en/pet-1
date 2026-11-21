#!/usr/bin/env python3
"""Separate technical preparation from the owner's store-submission decisions."""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def check(root, apk_report=None, aab_report=None):
    config = json.loads((root / 'release/release-config.json').read_text())
    if config.get('schemaVersion') != 1:
        raise ValueError('Unsupported release configuration')
    blockers = []
    publisher, pricing, privacy = (config[key] for key in ('publisher', 'pricing', 'privacy'))
    if not publisher.get('legalName'):
        blockers.append('Указать юридическое имя продавца.')
    if not publisher.get('merchantEligible') or not publisher.get('sellerAccountVerified'):
        blockers.append('Подтвердить аккаунт продавца, которому RuStore разрешает продажу приложений (ИП/юрлицо).')
    if not pricing.get('confirmed') or not isinstance(pricing.get('price'), (int, float)) or pricing['price'] <= 0:
        blockers.append('Подтвердить цену; 199 ₽ пока только предложение.')
    if not privacy.get('url', '').startswith('https://') or not privacy.get('publicUrlVerified'):
        blockers.append('Проверить публичный HTTPS-адрес политики конфиденциальности.')
    if not config['signing'].get('ownerHasKeystoreBackup') or not config['signing'].get('certificateSha256'):
        blockers.append('Владелец должен создать ключ подписи, сохранить резервную копию и указать отпечаток сертификата.')
    for field, label in [('ageRatingConfirmed', 'возрастной рейтинг'), ('ownerApprovedStoreCopy', 'описания для магазина'),
                         ('storeScreenshotsReviewed', 'снимки интерфейса')]:
        if not config['review'].get(field):
            blockers.append('Владелец должен подтвердить: ' + label + '.')
    policy = json.loads((root / 'app/src/main/assets/privacy/policy.json').read_text())
    if policy['identity']['support_email'] != publisher['supportEmail']:
        raise ValueError('Support email differs between policy and release configuration')
    if config['runtime'] != {'ads': False, 'analytics': False, 'internetPermission': False, 'modelBundled': True}:
        raise ValueError('Offline/no-ad release constraints changed')
    for language in ('ru', 'en'):
        metadata = json.loads((root / f'release/store/metadata-{language}.json').read_text())
        if metadata['applicationId'] != config['applicationId'] or len(metadata['displayName']) > 30 or len(metadata['shortDescription']) > 80 or len(metadata['fullDescription']) > 4000:
            raise ValueError('Invalid store metadata or description length')
        if metadata['publisher']['supportEmail'] != publisher['supportEmail'] or metadata['publisher']['privacyPolicyUrl'] != privacy['url']:
            raise ValueError('Store contacts differ from the release configuration')
        if metadata['publisher']['legalName'] != publisher['legalName'] or metadata['monetization']['price'] != pricing['price'] or metadata['monetization']['priceConfirmed'] != pricing['confirmed'] or metadata['monetization']['merchantEligible'] != publisher['merchantEligible']:
            raise ValueError('Store seller or price differs from the release configuration')
    artifacts = []
    for path in (apk_report, aab_report):
        if path is None or not Path(path).is_file():
            blockers.append('Предоставить проверенный подписанный APK/AAB и отчёт его проверки.')
            continue
        data = json.loads(Path(path).read_text())
        artifacts.append({'artifact': data.get('artifact'), 'sha256': data.get('sha256'),
                          'unsignedCandidate': data.get('candidateOnly', True)})
        if not data.get('technicalChecksPassed'):
            blockers.append('Техническая проверка артефакта не пройдена.')
        manifest = data.get('manifest', {})
        if (manifest.get('applicationId'), manifest.get('versionName'), manifest.get('versionCode')) != (
                config['applicationId'], config['versionName'], config['versionCode']):
            blockers.append('Версия или идентификатор артефакта отличается от release-config.json.')
        signed = data.get('signature', {})
        if not signed.get('verified') or data.get('candidateOnly', True):
            blockers.append('Кандидат не подписан производственным ключом владельца.')
        elif signed.get('certificateSha256') != config['signing'].get('certificateSha256'):
            blockers.append('Сертификат артефакта не совпадает с подтверждённым ключом владельца.')
    blockers = list(dict.fromkeys(blockers))
    return {'applicationId': config['applicationId'], 'versionName': config['versionName'],
            'submissionReady': not blockers, 'proposedPriceRub': pricing['proposedPrice'],
            'supportEmail': publisher['supportEmail'], 'blockers': blockers, 'artifacts': artifacts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report-only', action='store_true', help='Write a truthful preparation report without authorizing submission')
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
