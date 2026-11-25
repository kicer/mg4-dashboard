# ov.dash — MG4 车机自定义 HUD

在上汽 MG4（德赛西威 8155 / Android 11 AAOS）的主屏和副屏上叠加自定义数据浮层。
**所有视觉由 HTML 决定**，改页面不用重新编译 APK。

## 能力

| 屏 | 显示内容 | 触发条件 |
|---|---|---|
| **主屏** display 0 | `ui-main.html`（四卡片） | 只在桌面显示，进 app 自动隐藏 |
| **副屏** display 1 | `ui-cluster.html`（四卡片） | 常显，避开上下各 75px 的 QNX 边栏 |

**数据**：516 个车辆信号（binder 订阅推送）+ 12 个 GPS 字段 + 5 个媒体字段 + 2 个前台状态字段。

详见 `docs/数据字典.md`。

## 硬限制（先看这条）

**副屏有三种模式**：P 档、D 档信息流、D 档地图。

- **P 档 / D 档信息流**：画面由 **QNX** 渲染，不经过 Android。任何 Android 浮层都盖不上去。
- **D 档地图**：显示源切到 Android 的 HDMI(display 1)，浮层才可见。

另外副屏还有两块 QNX 区域盖不住：**上下各 75 物理像素的边栏**、**右侧导航信息栏**。
详见 `docs/显示规格.md`。

## 安装

```bash
# 一键：装包 + 全部授权
./dev/setup.sh

# 或者手动
adb push build/aligned.apk /sdcard/Download/dash.apk
adb shell pm install -r -i com.zone.hmi.appstore /sdcard/Download/dash.apk
adb shell appops set ov.dash SYSTEM_ALERT_WINDOW allow
adb shell appops set ov.dash GET_USAGE_STATS allow
adb shell pm grant ov.dash android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ov.dash android.permission.ACCESS_COARSE_LOCATION
adb shell cmd notification allow_listener ov.dash/ov.dash.MediaListener

# 无障碍（可选，提供零延迟的桌面切换检测）
./dev/enable-a11y.sh
```

**关键点：**

- `-i com.zone.hmi.appstore` 不能省，否则收不到开机广播
- `appops set SYSTEM_ALERT_WINDOW` 不能省，否则浮层不显示（车机没有授权界面）
- 无障碍在 `pm install -r` / `am force-stop` 后会被系统移除，需要重新跑 `enable-a11y.sh`

## 改页面（不编译 APK）

```bash
./dev/push-page.sh 我的页面.html ui-main.html
```

推文件 → `chmod 666` → 热重载。新建文件必须 chmod，否则属主是 shell，app 读不到。

## 配置

`/sdcard/Android/data/ov.dash/files/config.json`

```json
{
  "refresh": 1000,
  "topCheckMs": 500,
  "screens": [
    { "display": 0, "html": "ui-main.html", "name": "main",
      "enabled": true, "onlyOnDesktop": true },
    { "display": 1, "html": "ui-cluster.html", "name": "cluster", "enabled": true }
  ]
}
```

保存后自动热重载。字段说明见 `docs/HTML设计指南.md` 第 6.3 节。

## 广播

```bash
adb shell am broadcast -a ov.dash.reload   # 强制重载配置和页面
adb shell am broadcast -a ov.dash.stop     # 停止服务
adb shell am broadcast -a ov.dash.json     # 打印当前 JSON（调试）
adb shell am broadcast -a ov.dash.media    # 打印媒体/前台状态（调试）
```

## 目录

```
assets/          打进 APK 的默认页面和 keys.txt
dev/             工具脚本、当前配置、调试页
res/             无障碍服务的 XML 配置
src/ov/dash/     源码
```

## 文档

| 文件 | 面向 | 内容 |
|---|---|---|
| **`docs/HTML设计指南.md`** | 写页面的人 | 骨架、接口、config、硬性规则、部署 |
| **`docs/数据字典.md`** | 写页面的人 | 539 个字段的完整清单 |
| **`docs/显示规格.md`** | 写页面的人 | 画布尺寸、QNX 三块禁区 |
| **`docs/数据源.md`** | 维护者 | 4 个数据源的协议、权限、触发方式 |
| **`docs/技术笔记.md`** | 维护者 | 逆向全过程、实测数据、踩过的坑 |
