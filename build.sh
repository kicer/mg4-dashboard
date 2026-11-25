#!/bin/bash
# 构建 ov.dash。中间产物全在 build/ 下，根目录只留 aligned.apk。
set -e
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/android/sdk"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"

cd "$(dirname "$0")"
rm -rf build aligned.apk
mkdir -p build/gen build/classes build/dexout build/compiled

# 无障碍服务的 xml/strings 资源
"$BT/aapt2" compile --dir res -o build/compiled/

"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets build/compiled/*.flat \
    --java build/gen --min-sdk-version 30 --target-sdk-version 30 -o build/unsigned.apk

javac -g:none -source 8 -target 8 -Xlint:-options -classpath "$JAR" -d build/classes \
    src/ov/dash/*.java build/gen/ov/dash/R.java

"$BT/d8" --lib "$JAR" --min-api 30 --output build/dexout build/classes/ov/dash/*.class

( cd build/dexout && zip -q -u ../unsigned.apk classes.dex )

"$BT/zipalign" -f 4 build/unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey aligned.apk
ls -la aligned.apk
