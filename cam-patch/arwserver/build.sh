#!/usr/bin/env bash
# 发送到手机 RAW  (com.local.arwserver)  构建脚本
# 目标平台：Sony A6000 / ILCE-6000 / Android 2.3.7 / API 10
#
# 流程（与 rawswitch/build.sh 同源）：
#   [1] javac --release 8  （用 platform10 的 android.jar 当 classpath）
#   [2] d8 --min-api 10 --release  （build-tools 36.0.0，产出单 dex）
#       ★ --release 不能省：它会剥掉调试信息。实测同一个 classes2 目录
#         带 --release → 70684 B / sha256 9d94a0d1…（= 相机上 v19 的 dex）
#         不带       → 78416 B / sha256 fbc169eb…（差 +9.9%）
#         要"重编出来和装机那份一致"，必须带。
#   [3] aapt package + aapt add classes.dex  → unsigned.apk
#   [4] apksigner 用 fw/keys/testkey.pk8（AOSP testkey，与相机系统 APK 同钥匙）
#
# 用法：
#   bash build.sh                → 产出 arwserver.apk
#   bash build.sh arwserver_v20  → 产出 arwserver_v20.apk
#
# ⚠ 必须用 testkey 签名。相机上已装的 com.local.arwserver 就是这个签名
#   （packages.xml 里 cert index 3 = rawswitch 同款 testkey）；
#   换钥匙会导致签名冲突，必须先 adb uninstall 才能装。
set -e

ROOT=/c/Users/22206/Desktop/sony
SDK=/c/Users/22206/AppData/Local/Android/Sdk
BT="$SDK/build-tools/36.0.0"
AJAR="$ROOT/cam-patch/platform10/android-2.3.3_r02-linux/android.jar"
OUT="$ROOT/cam-patch/arwserver"
VER="${1:-arwserver}"

cd "$OUT"

for f in "$AJAR" "$ROOT/fw/keys/testkey.pk8" "$ROOT/fw/keys/testkey.x509.pem"; do
    [ -f "$f" ] || { echo "缺少依赖: $f"; exit 1; }
done

rm -rf classes2 classes.dex unsigned.apk

echo "[1/4] javac --release 8"
javac --release 8 -g -encoding UTF-8 -cp "$AJAR" -d classes2 \
    $(find src -name '*.java' | sort)

echo "[2/4] d8 --min-api 10"
"$BT/d8.bat" --min-api 10 --release --lib "$AJAR" --output . \
    $(find classes2 -name '*.class' | sort) >/dev/null
test -f classes.dex

echo "[3/4] aapt package + add classes.dex"
"$BT/aapt.exe" package -f -M AndroidManifest.xml -S res -I "$AJAR" -F unsigned.apk
"$BT/aapt.exe" add -f unsigned.apk classes.dex

echo "[4/4] apksigner (AOSP testkey / v1 only)"
"$BT/apksigner.bat" sign \
    --key "$ROOT/fw/keys/testkey.pk8" \
    --cert "$ROOT/fw/keys/testkey.x509.pem" \
    --min-sdk-version 10 \
    --v1-signing-enabled true --v2-signing-enabled false \
    --out "$VER.apk" unsigned.apk

"$BT/apksigner.bat" verify --print-certs "$VER.apk" | head -4
echo "--- badging ---"
"$BT/aapt.exe" dump badging "$VER.apk" | grep -E "^(package|application-label|launchable-activity|sdkVersion|targetSdkVersion)" || true
echo "--- sha256 ---"
sha256sum classes.dex "$VER.apk"
ls -l "$VER.apk"
echo "BUILD_OK $VER.apk"
