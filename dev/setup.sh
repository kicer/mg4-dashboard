#!/bin/bash
# ov.dash 一键部署：推送 + 安装 + 全部授权
# 用法：./setup.sh [apk路径]      默认 build/aligned.apk
set -e

APK="${1:-build/aligned.apk}"
PKG=ov.dash
D=/sdcard/Android/data/ov.dash/files

echo "==> 推送 $APK"
adb push "$APK" /sdcard/Download/dash.apk >/dev/null

echo "==> 安装（-i 伪装商店来源，否则收不到开机广播、可能不入 launcher）"
adb shell pm install -r -i com.zone.hmi.appstore /sdcard/Download/dash.apk

echo "==> 悬浮窗权限（车机删了授权界面，只能 appops）"
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow

echo "==> 使用情况访问（onlyOnDesktop 检测前台应用用）"
adb shell appops set $PKG GET_USAGE_STATS allow

echo "==> 定位权限（海拔数据用）"
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION

echo "==> 启动"
adb shell am start -n $PKG/.Main >/dev/null 2>&1 || true

echo ""
echo "==> 当前状态"
adb shell "appops get $PKG SYSTEM_ALERT_WINDOW; appops get $PKG GET_USAGE_STATS" 2>&1
adb shell "dumpsys package $PKG | grep -E 'ACCESS_(FINE|COARSE)_LOCATION: granted|installerPackageName'" 2>&1
adb shell "ls -la $D" 2>&1 | tail -8
