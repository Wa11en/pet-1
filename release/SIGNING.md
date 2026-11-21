# Подпись релиза владельцем приложения

В репозитории нет production-ключа и паролей. Подготовленный unsigned-кандидат позволяет проверить код, модель, манифест и оформление, но **не является готовым файлом для отправки в магазин**. Платный релиз также требует подходящего аккаунта продавца и завершённых реквизитов: см. [RUSTORE.md](RUSTORE.md).

## 1. Создать ключ на своём компьютере

Нужны JDK 21, Python 3.11+ и Android SDK, указанные в README. На личном компьютере владельца:

```sh
bash scripts/create_release_key.sh
```

Либо передайте абсолютный путь **вне репозитория**:

```sh
bash scripts/create_release_key.sh "$HOME/.private-diary-signing/private-diary-release.jks"
```

`keytool` сам запросит пароли и данные сертификата в терминале. Скрипт не передаёт пароли в аргументах процесса, не загружает ключ и не заменяет существующие файлы. Alias — `private-diary-release`. Храните JKS и оба пароля в собственной защищённой резервной копии. Для обновлений APK в RuStore нужен тот же ключ подписи.

Если у приложения уже есть production-ключ, используйте его. Не создавайте новый ключ для каждого выпуска. Тестовый Android Debug certificate не подходит для магазинного релиза.

## 2. Передать секреты только локальному процессу сборки

В Bash на своём компьютере:

```sh
export DIARY_RELEASE_STORE_FILE="$HOME/.private-diary-signing/private-diary-release.jks"
export DIARY_RELEASE_KEY_ALIAS="private-diary-release"
read -r -s -p 'Пароль хранилища: ' DIARY_RELEASE_STORE_PASSWORD
printf '\n'
read -r -s -p 'Пароль ключа: ' DIARY_RELEASE_KEY_PASSWORD
printf '\n'
export DIARY_RELEASE_STORE_PASSWORD DIARY_RELEASE_KEY_PASSWORD
bash scripts/build_release.sh
unset DIARY_RELEASE_STORE_PASSWORD DIARY_RELEASE_KEY_PASSWORD
```

Пароли не нужно присылать в чат, добавлять в `gradle.properties`, исходники, командную строку Gradle или GitHub. Не включайте shell tracing при работе с секретами. Скрипт читает переменные окружения и сообщает только имена отсутствующих настроек.

Выходные файлы:

- `app/build/outputs/apk/release/app-release.apk` — подписанный APK для магазина, поддерживающего этот формат.
- `app/build/outputs/bundle/release/app-release.aab` — подписанный Android App Bundle для Google Play и магазинов, принимающих AAB.
- `verification-release.json` в обеих папках — отчёт о технических проверках и SHA-256.

Ключ загрузки AAB и ключ, которым Google подписывает установочные APK при Play App Signing, могут различаться. Это отдельно настраивается владельцем в Play Console. Используйте реальный app-signing certificate магазина, когда планируете обновление между каналами.

## 3. Проверить подготовленный кандидат без секретов

```sh
bash scripts/build_release.sh --unsigned-candidate
```

Эта команда явно убирает signing-переменные из своего окружения и собирает unsigned APK/AAB. В отчёте будут `candidateOnly: true` и `signature.verified: false`; это ожидаемо. Загрузить такой файл как завершённый релиз нельзя. Обычная `assembleRelease`/`bundleRelease` без четырёх настроек подписи останавливается.

## Проверки артефакта

```sh
python3 scripts/verify_release.py app/build/outputs/apk/release/app-release.apk
python3 scripts/verify_release.py app/build/outputs/bundle/release/app-release.aab
python3 -m unittest discover -s scripts/tests -p 'test_verify_release.py'
```

Проверяется точный файл: подпись, запрет Android Debug certificate, отсутствие `debuggable`, `testOnly`, instrumentation/preview activity и INTERNET; SHA-256 модели и словаря; известные сигнатуры рекламных, аналитических и сетевых SDK; выравнивание ELF64 и ZIP для 16-КБ страниц. У AAB проверяется запрос `PAGE_ALIGNMENT_16K` или более сильного выравнивания в `BundleConfig.pb`; protobuf-манифест читается локально, без сторонних Python-пакетов.

Это техническая проверка. Она не проверяет статус продавца, согласование цены магазином, право на бренд или поведение всех устройств. Перед отправкой установите полученные APK/splits на реальные устройства, включая устройство с 16-КБ страницами. Проверка известных имён SDK не заменяет независимый аудит стороннего кода.

## Переход с тестового APK

У GitHub APK 0.6.x тестовая подпись. Релиз с новым сертификатом при прежнем package `ru.privatediary` не устанавливается поверх него. Сначала сохраните нужные черновики как записи, восстановите нужные записи из корзины и создайте зашифрованную `.pdiary`-копию с паролем в выбранной папке. Проверьте, что файл доступен отдельно от данных приложения. После переустановки создайте новый PIN и восстановите записи из копии. PIN, черновики и корзина в переносимую копию не входят.

Закрытый ключ, пароли и `.pdiary` с личными записями не должны публиковаться вместе с релизными материалами.
