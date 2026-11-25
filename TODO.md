# 待硬件验证

两个问题都卡在"现场现象无法复现/探测"，代码侧暂时不动，等车机在手边再继续。

---

## 1. 副屏导航模式下，路标盖住 overlay

### 现象
D 档地图模式下，导航的转向路标渲染在 `ov.dash` 的 overlay **之上**。

### 三种可能

| # | 可能 | 原理 | 能否修 |
|---|---|---|---|
| 1 | 导航也用 `TYPE_APPLICATION_OVERLAY`，但比我们晚 `addView` | 同一 layer 内按 `mSeq` 排，后加的在上 | 周期性 `removeView`+`addView` 重置顺序 |
| 2 | 导航是系统 app，用了 `APPLICATION_OVERLAY` 之上的系统窗口类型 | 需要系统签名 | 第三方拿不到 |
| 3 | **路标由 QNX 层渲染** | 物理 plane，Android 合成结果整体在其下 | **无解** |

可能 3 已间接验证过：副屏 P 档 / D 档信息流是 QNX 直出，Android 浮层完全盖不上去。

### 诊断命令
```bash
# 看 display 1 上的窗口，输出越靠后越在上层
adb shell dumpsys window windows | grep -E "^  Window #|mDisplayId=|ty="

# 看 SurfaceFlinger 有没有导航/我们的独立 layer
adb shell dumpsys SurfaceFlinger | grep -iE "navi|cluster|ov.dash"
```

判定：display 1 上找不到任何画路标的 Android 窗口、但屏幕上却有路标 → 可能 3。

### 若为可能 1 的修法
`config.json` 增加 `repinSec`，到点 `removeView` + `addView` 重置 layer 顺序。
同一帧内完成，WebView 不销毁、数据不重载，但有极短闪烁。该方案未经验证，先不做。

---

## 2. 主屏是否「只在桌面显示」

### 需求
`config.json` 增加开关，让 display 0 的 HUD 要么一直显示，要么只在 launcher 前台时显示。

### 技术障碍
Android 5.0 起普通 app 拿不到前台包名：

| 接口 | 权限 | 可用 |
|---|---|---|
| `ActivityManager.getRunningTasks()` | `REAL_GET_TASKS`（signature） | 只返回自己的任务 |
| `ActivityTaskManager.getTasks()` | 同上 | 反射也过不了权限检查 |
| `/proc` 遍历 | hidepid | 看不到 |
| `UsageStatsManager.queryEvents()` | `GET_USAGE_STATS`（**appop**） | 唯一可行，未授权静默返回空 |

### 待试的授权路径
```bash
adb shell appops set ov.dash GET_USAGE_STATS allow
```
或车机内：设置 → 应用 → 特殊权限 → 使用情况访问 → ov.dash。
`com.zone.hmi.settings` 是否保留该入口**未验证**。

### 已排查：zone SDK 里没有现成接口
`04-车机SDK/sdk` 下三个 jar 的 dex 里，`foreground` / `topActivity` / `topApp` / `runningTask` 等字符串**一个都没有**。

### 唯一线索：MultiDisplay 消息通道
`com.zone.api.middleframework.jar` → `com.zone.api.multidisplay.MultiDisplay`（1,741,456 字节 dex）：

```java
subscribeMessage(MultiDisplayListener listener, ArrayList<Integer> messageIds)
sendMessage(int messageId, String message, int timeout)
```
- 权限：`zone.permission.multidisplay.MULTI_DISPLAY_NORMAL`（名字带 NORMAL，疑似 normal 级，装包即授予）
- 底层：HIDL `com.zone.service.multidisplay@V1_0`
- 缓存路径常量：`/dev/tocluster/`

**潜力**：若副屏会通过 message 上报当前模式（P 档 / 地图 / 信息流），订阅即可精确判断，绕开 `GET_USAGE_STATS`。

**未知**：`messageId` 是双方约定的业务协议，SDK 里没有任何常量定义，需逆向 `com.zone.service.multidisplay` 或抓消息确认。

### 若拿到权限后的配置设计
```json
{ "display": 0, "html": "ui-main.html", "enabled": true,
  "alwaysShow": false, "launcherPkg": "com.zone.hmi.launcher" }
```
未授权时退化为 `alwaysShow=true`，不塌。

---

## 当前代码状态

```
fa597a9  fix: 副屏延迟枚举时 active 恒为 false，采集被短路
26f0c41  perf: 采集挪到后台线程，UI 线程不再碰 binder
f61720b  初始版本：采集与分发都在主线程
```

- `ov.dash-主线程版.apk` — `c596f188…`，**已实测正常**
- `ov.dash-后台线程版.apk` — `dbf78208…`，含 `fa597a9` 修复，**未实测**
