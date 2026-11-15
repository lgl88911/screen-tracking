#!/usr/bin/env bash
# 手工构建链：aapt2 link -> javac -> d8 -> 追加 classes.dex -> zipalign -> apksigner
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="$SDK/android-14"          # build-tools r34
PLATFORM="$SDK/android-13"    # platform-33 (android.jar)
JAVA_HOME_D="${JAVA_HOME_DIR:-$HOME/jdk}"
JAVAC="$JAVA_HOME_D/bin/javac"
KEYSTORE="$APP_DIR/keystore/debug.keystore"

# 签名口令：优先环境变量 KEYSTORE_PASS，其次 keystore/pass.txt（本地文件，已 gitignore）
# 两者都没有时生成随机口令，避免把固定口令写进版本库
KS_PASS_FILE="$APP_DIR/keystore/pass.txt"
if [ -n "${KEYSTORE_PASS:-}" ]; then
    KS_PASS="$KEYSTORE_PASS"
elif [ -f "$KS_PASS_FILE" ]; then
    KS_PASS="$(cat "$KS_PASS_FILE")"
else
    mkdir -p "$(dirname "$KS_PASS_FILE")"
    KS_PASS="$(openssl rand -hex 24)"
    printf '%s' "$KS_PASS" > "$KS_PASS_FILE"
    chmod 600 "$KS_PASS_FILE"
    echo "==> 已生成随机签名口令，写入 $KS_PASS_FILE"
fi

BUILD="$APP_DIR/build"
APK_NAME="screentime.apk"

export _JAVA_OPTIONS="-Xmx256m -Xss512k"
export JAVA_HOME="$JAVA_HOME_D"
export PATH="$JAVA_HOME_D/bin:$PATH"

echo "==> 清理"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "==> aapt2 link"
"$BT/aapt2" link \
    -o "$BUILD/base.apk" \
    -I "$PLATFORM/android.jar" \
    --manifest "$APP_DIR/AndroidManifest.xml" \
    --min-sdk-version 23 \
    --target-sdk-version 33 \
    --version-code 2 \
    --version-name 2.0

echo "==> javac"
find "$APP_DIR/src" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -bootclasspath "$PLATFORM/android.jar" \
    -source 8 -target 8 \
    -encoding UTF-8 \
    -d "$BUILD/classes" \
    @"$BUILD/sources.txt" 2>&1 | grep -v "^警告" || true

echo "==> d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$BT/d8" --lib "$PLATFORM/android.jar" --min-api 23 \
    --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "==> 追加 classes.dex（aapt2 产出的 APK 不含 dex）"
cd "$BUILD/dex"
zip -q "$BUILD/base.apk" classes.dex
cd "$APP_DIR"

echo "==> zipalign + 签名（在 /tmp 暂存：本仓库所在文件系统不支持原地 rename）"
STAGE="$(mktemp -d /tmp/apkstage.XXXXXX)"
"$BT/zipalign" -f 4 "$BUILD/base.apk" "$STAGE/u.apk"

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    "$JAVA_HOME_D/bin/keytool" -genkeypair -v \
        -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias screentime -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=Screentime Export, OU=Personal, O=Personal, L=CN, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:"$KS_PASS" \
    --key-pass pass:"$KS_PASS" --ks-key-alias screentime \
    --out "$STAGE/signed.apk" "$STAGE/u.apk"

"$BT/apksigner" verify "$STAGE/signed.apk"
cp "$STAGE/signed.apk" "$BUILD/$APK_NAME"
echo "==> 完成: $BUILD/$APK_NAME"
ls -lh "$BUILD/$APK_NAME"
