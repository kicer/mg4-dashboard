#!/bin/bash
# ov.dash 构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 所有中间产物和最终 APK 都在 build/ 下，根目录只留源码和文档
set -e
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/android/sdk"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/gen build/classes build/dexout build/compiled

# 无障碍服务需要 res/（xml 配置 + strings），aapt2 必须先 compile
"$BT/aapt2" compile --dir res -o build/compiled/

"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets build/compiled/*.flat \
    --java build/gen --min-sdk-version 30 --target-sdk-version 30 -o build/unsigned.apk

javac -g:none -source 8 -target 8 -Xlint:-options -classpath "$JAR" -d build/classes \
    src/ov/dash/*.java build/gen/ov/dash/R.java

"$BT/d8" --lib "$JAR" --min-api 30 --output build/dexout build/classes/ov/dash/*.class

( cd build/dexout && zip -q -u ../unsigned.apk classes.dex )

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey build/aligned.apk
ls -la build/aligned.apk
