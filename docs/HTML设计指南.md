# ov.dash HTML 设计指南

车机上跑的这个 app 只做两件事：**采集车辆信号** + **把 JSON 推给你写的网页**。
界面长什么样完全由你写的那张页面决定，改网页**不需要重装 APK**。

---

## 1. 最小骨架

```html
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
  html, body {
    margin: 0; padding: 0;
    width: 100%; height: 100%;
    background: transparent;      /* 必须透明，否则会盖黑原厂界面 */
    overflow: hidden;
    font-family: monospace;
    color: #fff;
    -webkit-user-select: none;
  }
</style>
</head>
<body>
  <div id="v">--</div>
  <script>
    window.onData = function (d) {
      document.getElementById('v').textContent = d.SPEED == null ? '--' : d.SPEED;
    };
  </script>
</body>
</html>
```

就这么点东西。文件名随意，在 `config.json` 里指名它，丢进共享目录即可。

---

## 2. 数据接口

app 每次采集完就调用一次：

```js
window.onData = function (d) {
  // d 是一个普通对象，字段见下表
};
```

- 周期：`config.json` 里的 `refresh`（默认 1000ms）
- 每个 `refresh` 周期推一次。516 个字段里约 10 个每秒在变（电流抖动、gpsAge 递增等），做不到「值没变就不推」
- **所有数值都是 number，不是字符串**

### 2.1 字段表

JSON 里的键名是**「原始 key」列**。前面那列只是本文档里的简称，写页面时不能用。

**电池 / BMS**

| 简称 | 原始 key | 小数位 | 说明 |
|---|---|---|---|
| `soc` | BMS_SOC_PERCENT | 1 | 动力电池电量 % |
| `soh` | BMS_BATTERY_SOC_ORG | 1 | 电池健康度 % |
| `volt` | BMS_DISP_PACK_VOL_ORG | 1 | 电池包电压 V |
| `curr` | BMS_DISP_PACK_CURRENT_ORG | 1 | 电池包电流 A |
| `range` | RANGE | 0 | 剩余续航 km |
| `power` | CHARGE_POWER | 1 | 充电功率 kW |
| `heat` | BMS_HEAT | 0 | 电池加热状态 |
| `warn` | BMS_WRNNG_INFO | 0 | 电池告警码 |
| `chargeDoor` | CHARGE_DOOR | 0 | 充电口状态 |
| `acVolt` | AC_VOLTAGE | 1 | 交流充电电压 V |
| `acCurr` | AC_CURRENT | 1 | 交流充电电流 A |
| `energyTotal` | BMS_TOTAL_CONSMP_AFT_START | 2 | 本次启动后总耗电 kWh |
| `energyDrive` | BMS_ACC_CONSMP_AFT_START | 2 | 驱动耗电 kWh |
| `energyHvac` | BMS_HVAC_CONSMP_AFT_START | 2 | 空调耗电 kWh |
| `energyRegen` | BMS_REGEN_AFT_START | 2 | 能量回收 kWh |

**里程 / 行驶 / 胎压**

| 简称 | 原始 key | 小数位 | 说明 |
|---|---|---|---|
| `odo` | ODO_DISP | 0 | 总里程 km |
| `trip1` | IPK_TRIPMETER_TRIP1 | 1 | 小计里程 1 km |
| `trip2` | IPK_TRIPMETER_TRIP2 | 1 | 小计里程 2 km |
| `aec1` | IPK_AVERAGE_PC_AEC1 | 1 | 平均电耗 1（kWh/100km） |
| `aec2` | IPK_AVERAGE_PC_AEC2 | 1 | 平均电耗 2 |
| `aspd1` | IPK_AVERAGE_SPEED1 | 0 | 平均车速 1 km/h |
| `aspd2` | IPK_AVERAGE_SPEED2 | 0 | 平均车速 2 |
| `dtime1` | IPK_DRIVING_TIME1 | 0 | 行驶时长 1 |
| `dtime2` | IPK_DRIVING_TIME2 | 0 | 行驶时长 2 |
| `gear` | ORG_GEAR | 0 | 档位 |
| `speed` | SPEED | 0 | 车速 km/h |
| `pwrStatus` | POWER_STATUS | 0 | 电源状态 |
| `tire_fl` | FL_TIRE_PRESSURE | 0 | 左前胎压 kPa |
| `tire_fr` | FR_TIRE_PRESSURE | 0 | 右前胎压 |
| `tire_rl` | RL_TIRE_PRESSURE | 0 | 左后胎压 |
| `tire_rr` | RR_TIRE_PRESSURE | 0 | 右后胎压 |

**空调 / 灯光 / 其他**

| 简称 | 原始 key | 小数位 | 说明 |
|---|---|---|---|
| `intemp` | IACINCARTEM | 1 | 车内温度 ℃ |
| `outtemp` | OUTSIDE_TEP | 1 | 车外温度 ℃ |
| `dayLight` | DAY_LIGHT | 0 | 日行灯状态 |
| `dipped` | DIPPED_BEAM_LIGHT_ON | 0 | 近光灯 |
| `mainBeam` | MAIN_BEAM_LIGHT_ON | 0 | 远光灯 |
| `fog` | FOG_LIGHT_STATUS | 0 | 雾灯 |
| `turnLamp` | DIRECTION_INDICATOR_LAMP | 0 | 转向指示灯 |
| `sideLight` | VEHICLE_SIDE_LIGHT_STS | 0 | 示宽灯 |
| `turnLeft` | LIGHT_EL_LDIR_CONTRL_ON | 0 | 左转向 |
| `turnRight` | LIGHT_EL_RDIR_CONTRL_ON | 0 | 右转向 |
| `alt` | LocationManager | 0 | 海拔 m（走 GPS） |
| `_ts` | — | — | 采集时间戳（毫秒） |
| `_tick` | — | — | 帧序号，自增 |

### 2.2 三个必须注意的点

**① 字段可能不存在**

取不到的信号**不会出现在 JSON 里**，不是 `0` 也不是 `null`：

```js
// 错
if (d.BMS_SOC_PERCENT > 50) ...

// 对
if (d.BMS_SOC_PERCENT != null && d.BMS_SOC_PERCENT > 50) ...
```

**② 灯光类不一定是 0/1**

`DAY_LIGHT` 实测返回过 `3`，是状态枚举值，不要当成布尔用。要显示图标就按区间判断。

**③ 单位**

| 量 | 单位 |
|---|---|
| 电压 | V |
| 电流 | A |
| 功率 | kW |
| 温度 | ℃ |
| 胎压 | kPa（实测 248 ≈ 2.48 bar） |
| 里程 | km |
| 电耗 | kWh/100km |
| 海拔 | m |

---

## 3. 画布尺寸（重要）

**CSS 视口不等于物理分辨率**。按下面的数字设计：

| 屏 | 物理 | **CSS 画布** | **内容可用区** |
|---|---|---|---|
| 主屏 display 0 | 1920×1080 | **1708 × 880** | 1708 × 880 |
| 副屏 display 1 | 1280×768 | **1139 × 684** | **1139 × 550** |

### 3.1 副屏的 QNX 区域

副屏上有两块区域是 QNX 直接渲染的，**Android 盖不住、也探测不到**。

#### 上下边栏：各 75 物理像素（= 66.7 CSS）

网页内容画到那里会被挡：

```
0    ┌────────────────────┐
     │  QNX 边栏 75px      │  ← 别画东西
75   ├────────────────────┤
     │                    │
     │   安全区            │  ← 1139 × 550 CSS
     │   （1280×618 物理） │
     │                    │
693  ├────────────────────┤
     │  QNX 边栏 75px      │  ← 别画东西
768  └────────────────────┘
```

实用写法：

```css
.safe {
  position: absolute;
  left: 0; right: 0;
  top: 67px;              /* 75 / 1.125 ≈ 67 */
  bottom: 67px;
}
```

主屏没有边栏，整个 1708×880 都能用（但底部 90 物理像素已经被系统导航栏吃掉，不在画布里）。

#### 右侧导航信息栏：无解，设计时避开

副屏右侧有一条 QNX 直出的**导航信息栏**，**导航和 P 档都会显示**。

它不是完全不透明——而是盖了一层**遮罩**：

- 我们的画面还能看出「那儿有东西」，但**上面的数值完全读不出来**
- QNX 层限制，Android 侧无法覆盖、无法穿透、也无法探测它的宽度

**副屏右侧不要放任何需要读数或交互的内容。**

### 3.2 换算

```js
物理宽 = innerWidth  * devicePixelRatio   // 1.125
物理高 = innerHeight * devicePixelRatio
```

参考截图：`91-截图/规格-主屏-1920x1080.png`、`规格-副屏-1280x768.png`

---

## 4. 硬性规则

| # | 规则 | 原因 |
|---|---|---|
| 1 | `html,body` 必须 `background:transparent` | 否则盖黑原厂界面 |
| 2 | **不要写死 `width`/`height`**，用 `100%` | 实际视口 1139×684，写 1280×768 会溢出裁切 |
| 3 | **不要用 `screen.width/height`** | 副屏上它返回主屏的 1707×960 |
| 4 | **不要用 `inset`** | 车机 WebView 版本旧（Chrome <87），不认，元素会变 0 尺寸。用 `left/top/right/bottom` |
| 5 | ES2020 可用（`?.` `??`），但别用更新的语法 | `Array.at` / `structuredClone` 在 Chrome 83 上不存在 |
| 6 | 不要用 `alert` / `confirm` / `prompt` | 车机可能不弹，且会卡住线程 |
| 7 | 不要引用外网资源（CDN、字体、图片） | 车机不一定有网 |
| 8 | 同目录资源用相对路径（`./a.png`） | app 用 `file://` 加载，相对路径可用 |
| 9 | **副屏右侧不要放重要内容** | QNX 导航信息栏会盖遮罩，数值完全读不出来 |
| 10 | **不要拿 screencap 截图当视觉依据** | 截图只有 Android 层，QNX 边栏和信息栏在截图里是透明的 |

### 4.1 车机 WebView 是 Chrome 83（2020 年）

`com.android.webview 83.0.4103.120`。在电脑上新版 Chrome 调好的页面，到车上会**静默失效**——
不报错，就是不生效。

| 不能用 | 最低版本 | 改成 |
|---|---|---|
| flex 的 `gap` | 84 | 用 `margin` |
| `inset` | 87 | `left/top/right/bottom` |
| `aspect-ratio` | 88 | `padding-bottom` 撑高 |
| `Array.at()` | 92 | `arr[i]` |
| `structuredClone` | 98 | `JSON.parse(JSON.stringify(x))` |
| `@layer` | 99 | 不要用 |
| `:has()` | 105 | 加一个 class |
| `@container` | 105 | `@media` |
| `color-mix()` | 111 | 写死颜色 |

确认版本的办法：页面里打印 `navigator.userAgent`。

> 自带的 `ui-main.html` 里 `.card::before{inset:0}` 就是这个坑——那层高光从来没渲染出来过，
> 但因为是额外装饰，不去看代码发现不了。

---

## 5. 性能

车机 CPU 不宽裕，主屏副屏各一个 WebView，每秒推一次数据。

**该做**

```js
// 只改文本，不重建节点
el.textContent = v;
```

**别做**

```js
// 每帧 innerHTML 重建整个 DOM —— 会疯狂重排
box.innerHTML = '<div>...</div>';
```

- 动画用 CSS `transform` / `opacity`，不要用 `top` / `left` / `width`
- 大范围 `box-shadow`、`filter: blur()` 慎用，车机 GPU 弱
- 别在 `onData` 里做 `querySelector`（先用变量缓存节点）

实测参考：一个只有边框的页面 6 秒渲染 362 帧（≈60fps），说明 WebView 是满帧跑的，写重一点很容易掉帧。

---

## 6. 部署与调试（不重装 APK）

### 6.1 目录

```
/sdcard/Android/media/ov.dash/
  config.json        配置
  ui-main.html       主屏页面
  ui-cluster.html    副屏页面
  my-hud/index.html  带 css/js/图片的整包页面
```

这个目录是公共的：文件管理器、U 盘工具、adb 都能直接访问，**不需要 chmod**。

### 6.2 改页面的正确姿势

```bash
adb push 新页面.html /sdcard/Android/media/ov.dash/ui-cluster.html
```

一条命令就完事，`FileObserver` 监听到文件变动会自动热重载。
页面也可以放在别处，把 `config.json` 的 `html` 写成绝对路径
（如 `/sdcard/Download/x.html`，需要 `MANAGE_EXTERNAL_STORAGE`，已默认授权）。

**全程不需要编译 APK。** 保险起见也可以手动触发：

```bash
adb shell am broadcast -a ov.dash.reload
```

### 6.3 config.json

```json
{
  "refresh": 1000,
  "topCheckMs": 500,
  "screens": [
    { "display": 0, "html": "ui-main.html",    "name": "main",    "enabled": true },
    { "display": 1, "html": "ui-cluster.html", "name": "cluster", "enabled": true }
  ]
}
```

**全局字段**

| 字段 | 默认 | 说明 |
|---|---|---|
| `refresh` | 1000 | 采集周期 ms |
| `topCheckMs` | 500 | 前台应用检查间隔 ms（无障碍在线时靠事件，不跑定时） |

**每个屏**

| 字段 | 默认 | 说明 |
|---|---|---|
| `display` | 1 | 0 = 主屏，1 = 副屏 |
| `html` | — | 页面文件名，相对共享目录；以 `/` 开头则当绝对路径 |
| `name` | html 去后缀 | 注入给 HTML 的 `_slot` 值 |
| `enabled` | true | `false` 时不创建 WebView，零内存 |
| `onlyOnDesktop` | false | `true` = 只在桌面显示（整个窗口销毁重建） |
| `launcherPkg` | `com.zone.hmi.launcher` | 判定「桌面」的包名 |
| `height` | 0 | 窗口高度（物理像素），`0` = 全屏。**当前方案不用** |

**`html` 可以是绝对路径**

| 写法 | 例子 | 适用 |
|---|---|---|
| 相对路径 | `ui-main.html` | 放共享目录，最稳 |
| 绝对路径 | `/storage/01C5-7ADB/hud/index.html` | U 盘、SD 卡、Download |

绝对路径**不做复制**，U 盘上改完刷新即生效。代价是设备拔掉就找不到文件，
所以加载时有两层兜底：

1. 绝对路径读不到 → 试共享目录下的**同名文件**
2. 还没有 → 用 APK 自带的 `ui-main.html`（不存在则从 assets 铺一份）

config 里的路径不会被改写，U 盘插回来继续用原页面。

> 想彻底不受 U 盘影响，就把页面放进共享目录用相对路径；
> 绝对路径适合临时试页。

**两种隐藏方式，效果完全不同：**

| 方式 | 配置 | 进 app 后 | `onDesktop` 有用吗 |
|---|---|---|---|
| **窗口级**（当前使用） | `onlyOnDesktop: true` | WebView 被 `destroy()`，**页面完全不存在** | ❌ 没用。页面在 app 里不渲染，`onDesktop` 永远是 `true` |
| **页面级**（备选） | `onlyOnDesktop: false` | WebView 常驻，页面继续运行 | ✅ 有用。HTML 自己根据它切换 `visibility` |

**关键分岐**：

- 窗口级 = 把“显不显示”交给 app 层决定。开销最小（进 app 零内存），
  但 HTML 无法参与——在 app 里它就是一具尸体，写什么逻辑都不执行。
- 页面级 = 把“显不显示”交给 HTML。窗口不销毁，进 app 时靠
  `body.visibility:hidden` 整页隐藏，窗口透明，app 内容自然透出。

**当前用的是窗口级**（进 app 后 WebView 被销毁，内存归零）。
如果你希望进 app 后页面还能继续跑（比如在顶部条显示东西），
才改成页面级。

### 6.4 看日志

```bash
adb shell logcat -d -s OVDASH:* | tail -40
```

---

## 7. 完整示例

```html
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
  html, body {
    margin: 0; padding: 0; width: 100%; height: 100%;
    background: transparent; overflow: hidden;
    font-family: 'Courier New', monospace; color: #00ff88;
    -webkit-user-select: none;
  }
  .safe {
    position: absolute; left: 0; right: 0;
    top: 67px; bottom: 67px;        /* 让开副屏 QNX 边栏 */
    display: flex; flex-direction: column;
    justify-content: center; align-items: center;
  }
  .big { font-size: 90px; font-weight: 700; line-height: 1;
         text-shadow: 0 0 20px rgba(0,255,136,.6); }
  .row { display: flex; gap: 40px; margin-top: 18px; font-size: 24px; }
  .k { color: rgba(0,255,136,.55); font-size: 14px; letter-spacing: 2px; }
</style>
</head>
<body>
  <div class="safe">
    <div class="big" id="spd">--</div>
    <div class="k">KM/H</div>
    <div class="row">
      <div><span class="k">SOC</span> <b id="soc">--</b>%</div>
      <div><span class="k">RANGE</span> <b id="rng">--</b>km</div>
      <div><span class="k">ODO</span> <b id="odo">--</b>km</div>
    </div>
  </div>
  <script>
    var $spd = document.getElementById('spd'),
        $soc = document.getElementById('soc'),
        $rng = document.getElementById('rng'),
        $odo = document.getElementById('odo');

    function n(v, d) { return v == null ? '--' : Number(v).toFixed(d || 0); }

    window.onData = function (d) {
      $spd.textContent = n(d.SPEED, 0);
      $soc.textContent = n(d.BMS_SOC_PERCENT, 0);
      $rng.textContent = n(d.RANGE, 0);
      $odo.textContent = n(d.ODO_DISP, 0);
    };
  </script>
</body>
</html>
```

放在副屏（display 1），会显示车速、电量、续航、里程，自动避开上下边栏。
