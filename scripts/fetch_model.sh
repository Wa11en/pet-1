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

verify_sha256() {
    local expected="$1" file="$2" actual
    if command -v sha256sum >/dev/null 2>&1; then
        actual="$(sha256sum "$file")"
    else
        actual="$(shasum -a 256 "$file")"
    fi
    actual="${actual%% *}"
    if [[ "$actual" != "$expected" ]]; then
        echo "Ошибка SHA-256: $file" >&2
        exit 1
    fi
}

echo 'Загрузка multilingual-e5-small int8 (~135 МБ вместе со словарём)…'
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    "$BASE_URL/onnx/model_int8.onnx" -o "$TEMP_DIR/model_int8.onnx"
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    "$BASE_URL/tokenizer.json" -o "$TEMP_DIR/tokenizer.json"
verify_sha256 '4d24e2bc01a447951524466ef533e52944bf48509e6552810bcee1a2711cb02c' "$TEMP_DIR/model_int8.onnx"
verify_sha256 '0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39' "$TEMP_DIR/tokenizer.json"
mv -- "$TEMP_DIR/model_int8.onnx" "$DESTINATION/model_int8.onnx"
mv -- "$TEMP_DIR/tokenizer.json" "$DESTINATION/tokenizer.json"
echo 'Готово. Пересоберите APK, чтобы включить модель в приложение.'
