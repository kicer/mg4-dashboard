#!/bin/bash
# 部署一个 html 到车机并热重载，不编译 APK
# 用法：
#   ./push-page.sh 本地.html              -> 用原文件名
#   ./push-page.sh 本地.html ui-top.html  -> 指定目标文件名
set -e

SRC="$1"
[ -z "$SRC" ] && { echo "用法: $0 <本地html> [目标文件名]"; exit 1; }
DST="${2:-$(basename "$SRC")}"
DIR=/sdcard/Android/data/ov.dash/files

adb push "$SRC" /sdcard/Download/_ovdash_tmp.html >/dev/null
# chmod 不能省：新建文件的属主是 shell，权限 600，app 读不到
adb shell "cp /sdcard/Download/_ovdash_tmp.html $DIR/$DST; chmod 666 $DIR/$DST" 2>/dev/null
adb shell "am broadcast -a ov.dash.reload" >/dev/null
echo "已部署 $DST"
adb shell "ls -la $DIR/$DST"
