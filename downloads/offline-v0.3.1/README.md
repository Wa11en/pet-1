# Офлайн-сборка 0.3.1

[Скачать APK](https://github.com/Wa11en/pet-1/releases/download/v0.3.1/Private-Diary-0.3.1-offline.apk).

Подписанный APK со встроенной моделью разделён на части для хранения в GitHub. На телефоне используйте готовый APK по ссылке выше. После клонирования репозитория восстановить его на компьютере можно так:

```sh
python3 scripts/restore_offline_apk.py downloads/offline-v0.3.1 /tmp/private-diary-031 --version 0.3.1
```

Папка назначения должна быть новой пустой папкой. Скрипт проверяет SHA-256 частей, архива, APK и файлов модели; ключ подписи и Android SDK не нужны.
