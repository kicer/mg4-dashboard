# ov.dash — MG4 车机数据 HUD

在上汽 MG4（德赛西威 8155 / Android 11 AAOS）的主屏和副屏上叠加浮层。

app 只做两件事：**采集车辆数据** → **推给你自己写的网页**。
界面长什么样完全由 HTML 决定，改页面不用重编 APK、不用重装。

## 快速开始

**装包 + 授权**（一次性）：

```bash
adb push build/aligned.apk /sdcard/Download/dash.apk
adb shell pm install -r -i com.zone.hmi.appstore /sdcard/Download/dash.apk
adb shell appops set ov.dash SYSTEM_ALERT_WINDOW allow
adb shell appops set ov.dash GET_USAGE_STATS allow
adb shell pm grant ov.dash android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ov.dash android.permission.ACCESS_COARSE_LOCATION
adb shell appops set ov.dash MANAGE_EXTERNAL_STORAGE allow
adb shell cmd notification allow_listener ov.dash/ov.dash.MediaListener
adb shell am start -n ov.dash/.Main
```

**换页面**（随时，不用重装）：

```bash
adb push 我的页面.html /sdcard/Android/media/ov.dash/ui-main.html
```

一条命令，push 完自动热重载。也可以推到任意位置，在设置页里选中它，
或把 `config.json` 的 `html` 写成绝对路径（需 `MANAGE_EXTERNAL_STORAGE`）。

装包那几条里，`-i com.zone.hmi.appstore` 不能省（否则收不到开机广播），
`SYSTEM_ALERT_WINDOW` 不能省（否则浮层不显示，车机没有授权界面），
`MANAGE_EXTERNAL_STORAGE` 不能省（否则只能读共享目录，读不到 `Download`）。

**可选：无障碍**（桌面切换检测延迟更低）。顺序敏感，先关、设列表、再开：

```bash
adb shell "settings put secure accessibility_enabled 0"
adb shell "settings put secure enabled_accessibility_services 'com.zone.service.speech.viewcmd/.ViewCmdAccessibilityService:ov.dash/.TopWatcher30'"
adb shell "settings put secure accessibility_enabled 1"
```

`pm install -r` / `am force-stop` 会让系统移除这一项，需重跑。

## 写你的页面

页面就是一个普通 HTML，靠 `window.onData(d)` 拿数据：

- `window.onData(d)` 每个 `refresh` 周期调用一次，`d` 里的值全是 number
- 字段清单 → `docs/数据字典.md`（500+ 个）
- 画布尺寸、副屏禁区 → `docs/显示规格.md`
- 最小骨架、性能、config 全解 → `docs/HTML设计指南.md`

## 默认页面

| 文件 | 挂在哪 |
|---|---|
| `ui-main.html` | 主屏 display 0 |
| `ui-cluster.html` | 副屏 display 1 |

这两个是**自定义的起点**，内容还在持续完善。照着改、整个换掉、或者从空文件重写都行。

**改坏了怎么恢复**：删掉共享目录下的那个文件，发 `am broadcast -a ov.dash.reload`，
app 会自动从 APK 里重新复制一份原版。

**可以带附件**：共享目录下放 css / js / 图片 / 字体，页面里用相对路径引用即可。

## 硬限制（先看这条）

**副屏有三种模式**：P 档、D 档信息流、D 档地图。

- **P 档 / D 档信息流**：画面由 **QNX** 渲染，不经过 Android，任何浮层都盖不上去
- **D 档地图**：显示源切到 Android 的 HDMI(display 1)，浮层才可见

副屏还有两块 QNX 区域盖不住：**上下各 75 物理像素的边栏**、**右侧导航信息栏**。
详见 `docs/显示规格.md`。

## 配置

`/sdcard/Android/media/ov.dash/config.json`

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
adb shell am broadcast -a ov.dash.reload   # 重载配置和页面
adb shell am broadcast -a ov.dash.stop     # 停止服务
adb shell am broadcast -a ov.dash.json     # 打印当前 JSON
adb shell am broadcast -a ov.dash.media    # 打印媒体/前台状态
```

## 目录

```
assets/          打进 APK 的默认页面和 keys.txt
build/           构建产物（aligned.apk）
dev/             开发期脚本（setup.sh / push-page.sh 等），自用
docs/            文档
res/             无障碍服务配置
src/ov/dash/     源码
```

## 文档

| 文件 | 面向 | 内容 |
|---|---|---|
| **`docs/HTML设计指南.md`** | 写页面的人 | 骨架、接口、config、硬性规则、部署 |
| **`docs/数据字典.md`** | 写页面的人 | 全部字段清单 |
| **`docs/显示规格.md`** | 写页面的人 | 画布尺寸、QNX 禁区 |
| **`docs/数据源.md`** | 维护者 | 4 个数据源的协议、权限 |
| **`docs/技术笔记.md`** | 维护者 | 逆向过程、实测数据、踩过的坑 |
