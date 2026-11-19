# Локальная модель E5

Полная офлайн-сборка 0.3.0 содержит в APK `model_int8.onnx` и `tokenizer.json`. Телефон ничего не скачивает. Файлы весов исключены из Git: для воспроизводимой сборки на компьютере выполните:

```sh
bash scripts/fetch_model.sh
./gradlew assembleDebug testDebugUnitTest -PrequireModel=true -PverifyOfflineModel=true
```

Скрипт проверяет закреплённые SHA-256 и пропускает уже проверенные файлы. `-PrequireModel=true` останавливает сборку при отсутствии или повреждении assets; `-PverifyOfflineModel=true` запускает реальный ONNX CPU-тест на компьютере разработчика (native desktop-библиотека входит только в тесты). Обычная сборка без assets сохраняет FTS fallback.

Модель: intfloat/multilingual-e5-small, переносимая ONNX int8-конвертация Xenova, revision `761b726dd34fb83930e26aab4e9ac3899aa1fa78`, лицензия MIT. Веса 118054593 байта, словарь 17082730 байт. При первом использовании создаётся приватная копия весов в `noBackupFilesDir` для ONNX Runtime.
