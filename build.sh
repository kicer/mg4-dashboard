#!/bin/bash
# 用 Android Studio 的 JBR + 现有 SDK 构建，不安装任何 SDK 包
set -e
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/android/sdk"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"

cd "$(dirname "$0")"
rm -rf gen classes dexout compiled unsigned.apk aligned.apk
mkdir -p gen classes dexout compiled

# 无障碍服务需要 res/（xml 配置 + strings），aapt2 必须先 compile
"$BT/aapt2" compile --dir res -o compiled/

"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets compiled/*.flat \
    --java gen --min-sdk-version 30 --target-sdk-version 30 -o unsigned.apk

javac -g:none -source 8 -target 8 -Xlint:-options -classpath "$JAR" -d classes \
    src/ov/dash/*.java gen/ov/dash/R.java

"$BT/d8" --lib "$JAR" --min-api 30 --output dexout classes/ov/dash/*.class

( cd dexout && zip -q -u ../unsigned.apk classes.dex )

"$BT/zipalign" -f 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey aligned.apk
ls -la aligned.apk
