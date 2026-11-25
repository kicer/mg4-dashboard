# ov.dash — MG4 副屏数据浮层

在 MG4 副屏（display 1）的地图模式上叠加一行车辆数据，不干扰原厂仪表。

## 硬限制（先看这条）

副屏有三种模式：**P 档**、**D 档信息流**、**D 档地图**。

- **P 档 / D 档信息流**：画面由 **QNX** 渲染，不经过 Android。任何 Android 侧的浮层都盖不上去（原厂 App 也一样）。
- **D 档地图**：显示源切到 Android 的 HDMI(display 1)，此时浮层可见。

**所以它只在地图模式下出现，这是硬件行为，改不了。**

## 安装

```bash
# 1. 用商店来源安装（关键：不加 -i 会被标记为侧载，收不到开机广播）
adb push aligned.apk /sdcard/Download/ovdash.apk
adb shell pm install -r -i com.zone.hmi.appstore /sdcard/Download/ovdash.apk

# 2. 授权（车机没有悬浮窗设置界面，只能 adb）
adb shell appops set ov.dash SYSTEM_ALERT_WINDOW allow
adb shell pm grant ov.dash android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ov.dash android.permission.ACCESS_COARSE_LOCATION

# 3. 启动
adb shell am start -n ov.dash/.Main
```

`appops` 结果存在 `/data/system/appops.xml`，**重启不丢**，只有卸载重装才需要重做。

## 自启动

- `Boot` 接收 `BOOT_COMPLETED` / `QUICKBOOT_POWERON` / `MY_PACKAGE_REPLACED`
- 开机时 HDMI 副屏可能还没枚举，`DisplayListener` 会等它出现再建窗口
- `Watchdog` 每 2 分钟检查心跳，服务挂了自动拉起

## 配置（全部走广播，改完立即生效并持久化）

```bash
# 位置 / 尺寸
adb shell am broadcast -a ov.dash.config --ei x 20 --ei y 140 --ei w 1240 --ei h 520

# 字号(px) / 颜色(ARGB 十进制) / 背景不透明度(0-255)
adb shell am broadcast -a ov.dash.config --ei size 26
adb shell am broadcast -a ov.dash.config --ei color -1
adb shell am broadcast -a ov.dash.config --ei alpha 210

# 内容模式
adb shell am broadcast -a ov.dash.config --es mode panel
#   panel(默认全信息) soc speed range odo tire temp volt power trip multi all scan

# 刷新间隔(ms)
adb shell am broadcast -a ov.dash.config --ei refresh 1000

# 停止
adb shell am broadcast -a ov.dash.stop
```

## 数据来源

逆向自 `com.yg.clusterwall` 的 binder 协议：

```
ServiceManager.getService("service.coreservicemanagerservice")   ICoreServiceManager
  → transact(16, pkg + "VehicleServerService" + "")              IBinderPool
  → transact(1, index)                                           子服务
  → transact(4, key + 1 + 2000 + null + "java.lang.Float" + ..)  读信号
```

服务索引：

```
[0]  IAdsService      [7]  IRearMirrorService
[1]  IAirOutletService[8]  IWindowService
[2]  IBrakeService    [9]  ISeatService
[3]  IVehicleCommon   [10] IBmsService
[4]  IDoorLockService [11] IHudService
[5]  IHvacService     [12] ISteeringService
[6]  ILightService
```

权限：`zone.permission.vehicle.DEVICE_GET`（normal，装包自动授予）。写类权限（HVAC_SET/SEAT_SET/ADS_SET）是 signature，拿不到，也**不需要**。

## 构建

用 Android Studio 自带的 JBR + 本地 SDK，不安装任何 SDK 包：

```bash
./build.sh    # aapt2 → javac → d8 → zipalign → apksigner
```

注意：`build-tools/34.0.0` 的 d8 处理匿名内部类会 NPE，脚本用的是 `35.0.0`。

## 已知问题

- **车窗信号读不到**：`IWindowService` binder 正常，但 4 个 `WINDOW_OPENING_DEGREE_*` key 全返回 null，本车可能没配
- **海拔只能靠 GPS**：VHAL 的 8 个候选 key 全空，走 `LocationManager`，室内/地库会显示 `--`
- **`SYSTEM_ALERT_WINDOW` 无法界面授权**：车机删掉了 `ACTION_MANAGE_OVERLAY_PERMISSION` 界面，只能 adb
