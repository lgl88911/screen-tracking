#!/usr/bin/env bash
# 桌面单测：UsageAnalyzer / SyncPaths 都不依赖 android.*，JDK 直接跑
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME_DIR:-$HOME/jdk}"
export _JAVA_OPTIONS="-Xmx256m -Xss512k"

OUT1="$(mktemp -d /tmp/st-test.XXXXXX)"
"$JDK/bin/javac" -encoding UTF-8 -d "$OUT1" \
    "$APP_DIR/src/com/screentime/export/UsageAnalyzer.java" \
    "$APP_DIR/test/AnalyzerTest.java"
"$JDK/bin/java" -cp "$OUT1" AnalyzerTest

OUT2="$(mktemp -d /tmp/st-path.XXXXXX)"
"$JDK/bin/javac" -encoding UTF-8 -d "$OUT2" \
    "$APP_DIR/src/com/screentime/export/SyncPaths.java" \
    "$APP_DIR/test/DeviceIdTest.java"
"$JDK/bin/java" -cp "$OUT2" DeviceIdTest

# 快照导入：Json + SnapshotImporter 都不依赖 android.*
OUT3="$(mktemp -d /tmp/st-import.XXXXXX)"
"$JDK/bin/javac" -encoding UTF-8 -d "$OUT3" \
    "$APP_DIR/src/com/screentime/export/Json.java" \
    "$APP_DIR/src/com/screentime/export/SnapshotImporter.java" \
    "$APP_DIR/test/ImportTest.java"
"$JDK/bin/java" -cp "$OUT3" ImportTest
