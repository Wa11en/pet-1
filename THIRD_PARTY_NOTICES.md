# Сторонние компоненты

- AndroidX, Jetpack Compose, Room — Apache License 2.0, Google / Android Open Source Project.
- Kotlin и kotlinx.coroutines — Apache License 2.0, JetBrains.
- SQLCipher Community Edition (`net.zetetic:sqlcipher-android`) — BSD-style license, Zetetic LLC; смотрите [LICENSE](https://github.com/sqlcipher/sqlcipher-android/blob/master/LICENSE).
- Snowball Stemmer (`com.github.rholder:snowball-stemmer:1.3.0.581.1`) — BSD License; Copyright (c) 2001 Dr Martin Porter, (c) 2002 Richard Boulton. [Исходники и уведомление](https://github.com/rholder/snowball-stemmer#license). Только локальная обработка RU/EN слов, сетевого кода нет.
- ONNX Runtime — MIT, Microsoft; [LICENSE](https://github.com/microsoft/onnxruntime/blob/main/LICENSE).
- multilingual-e5-small — MIT согласно model card intfloat; [оригинальная карточка](https://huggingface.co/intfloat/multilingual-e5-small). Переносимый int8 ONNX — конвертация Xenova, зафиксированный revision приведён в README. Полная офлайн-сборка 0.3.0 включает веса и токенизатор. Текст MIT-лицензии включён в assets/model/LICENSE-E5.txt; отдельные файлы для собственной сборки добавляются скриптом fetch_model.sh.
- Алгоритмические источники Kotlin-порта SentencePiece: Google SentencePiece ([Apache 2.0](https://github.com/google/sentencepiece/blob/master/LICENSE), [normalizer.cc](https://github.com/google/sentencepiece/blob/master/src/normalizer.cc), [unigram_model.cc](https://github.com/google/sentencepiece/blob/master/src/unigram_model.cc)); Hugging Face [spm_precompiled](https://github.com/huggingface/spm_precompiled) (Apache 2.0). Реализация использует формат предкомпилированной Darts-нормализации и Unigram Viterbi.
- org.json JVM и sqlite-jdbc входят только в тесты, не в APK. Лицензии: [JSON-java](https://github.com/stleary/JSON-java/blob/master/LICENSE), [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc/blob/master/LICENSE).

Полные тексты лицензий артефактов доступны в соответствующих исходных репозиториях и Maven-пакетах. Перед публичным распространением проверьте модель и включите обязательные license notices для конкретного состава релиза.
