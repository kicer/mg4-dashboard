#!/bin/bash
# 启用无障碍前台监听（TopWatcher22）
#
# 前提：车机刚重启过，且之后没有 force-stop 或重装过 ov.dash。
# 因为 force-stop / pm install 会杀掉服务，系统会把 TopWatcher22 标记为
# crashed 并永久停止重试，那个标记只有重启系统才能清掉。
#
# 顺序很重要：先关 -> 设列表 -> 再开，否则设置不生效。
set -e

ORG=com.zone.service.speech.viewcmd/.ViewCmdAccessibilityService
OURS=ov.dash/.TopWatcher22

echo "==> 关闭无障碍"
adb shell "settings put secure accessibility_enabled 0"
sleep 1

echo "==> 写入服务列表（保留原有语音服务）"
adb shell "settings put secure enabled_accessibility_services '$ORG:$OURS'"
sleep 1

echo "==> 重新开启"
adb shell "settings put secure accessibility_enabled 1"
sleep 3

echo "==> 结果"
adb shell "dumpsys accessibility | grep -E 'Enabled services|Bound services|Crashed'" 2>&1 | head -5
