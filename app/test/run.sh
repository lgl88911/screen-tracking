#!/usr/bin/env bash
# 桌面单测：只编译 UsageAnalyzer（无 android 依赖）+ 测试类
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME_DIR:-$HOME/jdk}"
OUT="$(mktemp -d /tmp/st-test.XXXXXX)"
export _JAVA_OPTIONS="-Xmx256m -Xss512k"

"$JDK/bin/javac" -encoding UTF-8 -d "$OUT" \
    "$APP_DIR/src/com/screentime/export/UsageAnalyzer.java" \
    "$APP_DIR/test/AnalyzerTest.java"

"$JDK/bin/java" -cp "$OUT" AnalyzerTest
