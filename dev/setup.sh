#!/bin/bash
# ov.dash 一键部署：推送 + 安装 + 全部授权 + 无障碍 + 启动
# 用法：./setup.sh [apk路径]      默认 ovdash.apk
set -e

APK="${1:-ovdash.apk}"
PKG=ov.dash
D=/sdcard/Android/media/ov.dash

echo "==> 推送 $APK"
adb push "$APK" /sdcard/Download/ovdash.apk >/dev/null

echo "==> 安装（-i 伪装商店来源，否则收不到开机广播、可能不入 launcher）"
adb shell pm install -r -i com.zone.hmi.appstore /sdcard/Download/ovdash.apk

echo "==> 悬浮窗权限（车机删了授权界面，只能 appops）"
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow

echo "==> 使用情况访问（onlyOnDesktop 检测前台应用用）"
adb shell appops set $PKG GET_USAGE_STATS allow

echo "==> 全盘文件访问（读 Download / U 盘里的页面）"
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow

echo "==> 定位权限（海拔数据用）"
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION

echo "==> 通知监听（媒体信息）"
adb shell cmd notification allow_listener $PKG/$PKG.MediaListener

# 装包会让系统移除无障碍，每次重装都必须重设
echo "==> 无障碍"
"$(dirname "$0")/enable-a11y.sh"

# 启动放最后：先让无障碍上线，app 一启动就能拿到已连接的监听器
echo "==> 启动"
adb shell am start -n $PKG/.Main >/dev/null 2>&1 || true

echo ""
echo "==> 当前状态"
adb shell "appops get $PKG SYSTEM_ALERT_WINDOW; appops get $PKG GET_USAGE_STATS; appops get $PKG MANAGE_EXTERNAL_STORAGE" 2>&1
adb shell "dumpsys package $PKG | grep -E 'ACCESS_(FINE|COARSE)_LOCATION: granted|installerPackageName'" 2>&1
adb shell "dumpsys accessibility | grep -E 'Enabled services|Crashed'" 2>&1 | head -3 || true
adb shell "ls -la $D" 2>&1 | tail -8
