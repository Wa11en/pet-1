# Офлайн-сборка 0.4.0

[Скачать APK](https://github.com/Wa11en/pet-1/releases/download/v0.4.0/Private-Diary-0.4.0-offline.apk).

Подписанный APK со встроенной E5 разделён на части для GitHub. На телефоне используйте готовый APK выше. После клонирования репозитория восстановить его на компьютере можно так:

```sh
python3 scripts/restore_offline_apk.py downloads/offline-v0.4.0 /tmp/private-diary-040 --version 0.4.0
```

Укажите новую пустую папку назначения. Проверяются SHA-256 частей, ZIP, APK и обоих файлов модели. Скрипт читает ZIP прямо из частей, не создавая промежуточный полный архив. Android SDK и ключ подписи для восстановления не нужны.

APK: 240 739 488 байт. SHA-256: `055097c655cef2516a6aaa17a1285f04e38bdbb30da81e9feae172bc17181a85`.
