#!/usr/bin/env bash
# 加密链路单测：编译 Crypto（无 android 依赖）+ CryptoTest，并在 /tmp/sce-test 留下样本
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME_DIR:-$HOME/jdk}"
OUT="$(mktemp -d /tmp/st-crypto.XXXXXX)"
export _JAVA_OPTIONS="-Xmx256m -Xss512k"

rm -rf /tmp/sce-test
mkdir -p /tmp/sce-test

"$JDK/bin/javac" -encoding UTF-8 -d "$OUT" \
    "$APP_DIR/src/com/screentime/export/Crypto.java" \
    "$APP_DIR/test/CryptoTest.java"

"$JDK/bin/java" -cp "$OUT" CryptoTest
