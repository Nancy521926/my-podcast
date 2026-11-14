#!/usr/bin/env bash
# My Podcast APK 构建脚本（不依赖 Gradle）
#
# 依赖：JDK 17、Android SDK（platform android-34 + build-tools 34.0.0）
# 用法：
#   export JAVA_HOME=/path/to/jdk-17
#   export ANDROID_SDK=/path/to/Android/sdk
#   bash build.sh
# 产物：../dist/MyPodcast-v1.2.apk
set -e

: "${JAVA_HOME:?请先设置 JAVA_HOME（JDK 17）}"
SDK="${ANDROID_SDK:-${ANDROID_HOME:-}}"
: "${SDK:?请先设置 ANDROID_SDK（Android SDK 根目录）}"

BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "找不到 $ANDROID_JAR，请先安装 platform android-34"; exit 1; }

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) AAPT2="$BT/aapt2.exe"; D8="$BT/d8.bat"; APKSIGNER="$BT/apksigner.bat"; ZIPALIGN="$BT/zipalign.exe" ;;
  *)                    AAPT2="$BT/aapt2";     D8="$BT/d8";     APKSIGNER="$BT/apksigner";     ZIPALIGN="$BT/zipalign" ;;
esac

if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v py >/dev/null 2>&1; then PY="py -3"
else PY=python; fi

export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"

OUT="../dist/MyPodcast-v1.2.apk"
mkdir -p ../dist
rm -rf build
mkdir -p build/classes build/gen build/dex

echo "[1/6] 编译资源"
"$AAPT2" compile --dir res -o build/res.zip

echo "[2/6] 链接资源 + manifest + assets"
"$AAPT2" link \
  -o build/app-unsigned.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  -A assets \
  --java build/gen \
  --auto-add-overlay \
  build/res.zip

echo "[3/6] javac 编译 Java"
find src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR" \
  -d build/classes \
  @build/sources.txt 2>&1 | grep -v 'bootstrap class path' || true

echo "[4/6] d8 生成 classes.dex"
find build/classes -name '*.class' > build/classes.txt
"$D8" --lib "$ANDROID_JAR" --min-api 23 --release \
  --output build/dex \
  @build/classes.txt

echo "[5/6] 写入 dex + zipalign"
$PY - <<'PY'
import zipfile
z = zipfile.ZipFile('build/app-unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('build/dex/classes.dex', 'classes.dex')
z.close()
print('  classes.dex 已写入')
PY
"$ZIPALIGN" -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "[6/6] 签名"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -v -keystore debug.keystore \
    -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
  echo "  已生成 debug.keystore（仅本地测试用，不要提交）"
fi
"$APKSIGNER" sign \
  --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$OUT" \
  build/app-aligned.apk

"$APKSIGNER" verify --print-certs "$OUT" | head -5
ls -lh "$OUT"
echo "构建完成：$(cd ../dist && pwd)/MyPodcast-v1.2.apk"
