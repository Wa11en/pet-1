#!/usr/bin/env bash
set -euo pipefail

# This script runs on the developer's computer, never inside the Android application.
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="$ROOT_DIR/app/src/main/assets/model"
REVISION="761b726dd34fb83930e26aab4e9ac3899aa1fa78"
BASE_URL="https://huggingface.co/Xenova/multilingual-e5-small/resolve/$REVISION"
mkdir -p "$DESTINATION"
TEMP_DIR="$(mktemp -d "$DESTINATION/.fetch.XXXXXXXX")"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

matches_sha256() {
    local expected="$1" file="$2" actual
    if command -v sha256sum >/dev/null 2>&1; then
        actual="$(sha256sum "$file")"
    else
        actual="$(shasum -a 256 "$file")"
    fi
    actual="${actual%% *}"
    [[ "$actual" == "$expected" ]]
}

fetch_verified() {
    local remote_path="$1" name="$2" expected="$3"
    if [[ -f "$DESTINATION/$name" ]]; then
        if matches_sha256 "$expected" "$DESTINATION/$name"; then
            echo "Уже проверен: $name (загрузка не нужна)."
            return
        fi
        echo "Контрольная сумма $name не совпадает. Загружаем проверенную версию заново." >&2
    fi

    echo "Загрузка $name…"
    curl --fail --location --retry 3 --connect-timeout 30 --max-time 900 \
        --proto '=https' --proto-redir '=https' --tlsv1.2 \
        "$BASE_URL/$remote_path" -o "$TEMP_DIR/$name"
    if ! matches_sha256 "$expected" "$TEMP_DIR/$name"; then
        echo "Ошибка SHA-256: $name. Загруженный файл не установлен." >&2
        exit 1
    fi
    mv -- "$TEMP_DIR/$name" "$DESTINATION/$name"
}

echo 'Подготовка multilingual-e5-small int8 (~135 МБ вместе со словарём)…'
fetch_verified 'onnx/model_int8.onnx' 'model_int8.onnx' \
    '4d24e2bc01a447951524466ef533e52944bf48509e6552810bcee1a2711cb02c'
fetch_verified 'tokenizer.json' 'tokenizer.json' \
    '0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39'
echo 'Готово. Пересоберите APK, чтобы включить модель в приложение.'
