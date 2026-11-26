# ov.dash — MG4 车机数据 HUD

在上汽 MG4（德赛西威 8155 / Android 11 AAOS）的主屏和副屏上叠一层浮层。

app 只做两件事：**采集车辆数据** → **推给你自己写的网页**。
界面长什么样完全由 HTML 决定，**改页面不用重编 APK、不用重装**。

## 一键安装（推荐）

不用敲命令，手机上装个 app 就能装完。

1. 手机装 [**mg4shell**](https://github.com/kicer/mg4-adb-shell/releases) —— 一个手机端的 adb 客户端
2. 打开它，顶部填车机 IP `xxx.xxx.xxx.xxx:5555`，点「连接」
3. 切到 **批量** 页，长按 `ovdash hud` 那条 → 选「执行」

剩下的它会自己跑完：下载 APK → 推车机 → 安装 → 等装好 → 六项授权 → 开无障碍 → 启动。
中途想停，点红色「停止」就行。

> **前提是车机已经能用 adb 连**。无线 ADB 怎么开见 [esp32-mg4](https://github.com/kicer/esp32-mg4)，
> 烧好开发板插上去就有，不需要 USB 线。

装完回到桌面，浮层就在上面了。

---

## 手动装（不想装 mg4shell 的话）

**第 1 步，装包**

```bash
adb push ovdash.apk /sdcard/Download/ovdash.apk
adb shell pm install -r -i com.zone.hmi.appstore /sdcard/Download/ovdash.apk
```

`-i com.zone.hmi.appstore` 不能省，伪装成商店来源，否则收不到开机广播。

> **必须确认装成功再往下走。** `pm install` 走增量安装，输出 `Success` 只代表命令下发成功，
> 包在系统里还在后台注册。这时候跑授权会**静默失败**（日志里只有 `Unknown package: ov.dash`，
> 命令本身不报错），最后表现为浮层加不上、日志报 `permission denied for window type 2038`。
>
> 等到有输出再继续：
>
> ```bash
> adb shell pm list packages | grep ov.dash
> ```

**第 2 步，授权**

```bash
adb shell appops set ov.dash SYSTEM_ALERT_WINDOW allow
adb shell appops set ov.dash GET_USAGE_STATS allow
adb shell appops set ov.dash MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant ov.dash android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ov.dash android.permission.ACCESS_COARSE_LOCATION
adb shell cmd notification allow_listener ov.dash/ov.dash.MediaListener

# 无障碍：顺序敏感，先关、设列表、再开
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure enabled_accessibility_services 'com.zone.service.speech.viewcmd/.ViewCmdAccessibilityService:ov.dash/.TopWatcher'
adb shell settings put secure accessibility_enabled 1
```

关于无障碍那三行，两个坑：

- **值里的单引号必须保留**（里面带冒号），但整条命令不用再包一层双引号
- 这条是**追加**到现有列表，不能直接覆盖 —— 车机自带的「所见即可说」语音服务也在里面

**第 3 步，启动**

```bash
adb shell am start -n ov.dash/.Main
```

启动放最后：先让无障碍上线，app 一启动就能拿到已连接的监听器，否则开头几秒会退回轮询。

> **重装 APK 后授权会全部丢。** `appops` 和 `pm grant` 都挂在包上，`pm install -r`
> 或卸载重装都会清掉，需要把第 2 步整段重新跑一遍。只重启车机或重启 app 不影响。

## 换个页面

```bash
adb push 自定义.html /sdcard/Android/media/ov.dash/ui-main.html
```

一条命令，push 完自动热重载（`FileObserver` 监听目录）。

也可以推到任意位置，在设置页里选中它，或把 `config.json` 的 `html` 写成绝对路径
（需 `MANAGE_EXTERNAL_STORAGE`）。

**共享目录**指 `/sdcard/Android/media/ov.dash/`，放 `config.json`、`keys.txt` 和各个页面。
下面提到「共享目录」都是指这里。

> 仓库里的 `dev/push-page.sh` 是自用脚本，内部多做了 `chmod 666` 和广播。
> `chmod` 不能省：adb 新建的文件属主是 `shell`、权限 `600`，app 读不到会白屏。

---

# 技术细节

## 写你的页面

页面就是一个普通 HTML，靠 `window.onData(d)` 拿数据：

- `window.onData(d)` 每个 `refresh` 周期调用一次
- 绝大部分字段是 number，少数是字符串（`mediaTitle` `fgApp` 等）和布尔（`onDesktop`）
- 字段清单 → `docs/数据字典.md`（500+ 个）
- 画布尺寸、副屏禁区 → `docs/显示规格.md`
- 最小骨架、性能、config 全解 → `docs/HTML设计指南.md`

## 默认页面

| 文件 | 挂在哪 |
|---|---|
| `ui-main.html` | 主屏 display 0 |
| `ui-cluster.html` | 副屏 display 1 |

这两个是**自定义的起点**，内容还在持续完善。照着改、整个换掉、或者从空文件重写都行。

装好后**两个屏默认都显示**：主屏只在桌面出现（进 app 自动隐藏），副屏常显。
如果只要一个屏，把 `config.json` 里对应那条的 `enabled` 改成 `false`。

**改坏了怎么恢复**：删掉共享目录下的那个文件，发 `am broadcast -a ov.dash.reload`，
app 会自动从 APK 里重新复制一份原版。

**可以带附件**：共享目录下放 css / js / 图片 / 字体，页面里用相对路径引用即可。

## 设置界面

点开 app 图标就是设置页，不用 adb 也能改配置：

| 区域 | 内容 |
|---|---|
| 顶部 | 共享目录、本机 IP、无障碍开关状态 |
| 采集 | 刷新周期 500 / 1000 / 2000 / 5000 ms |
| 每屏一张卡 | 选页面（扫共享目录 / Download / U 盘）、启用、仅桌面显示 |
| 底部 | 「保存并应用」→ 写 `config.json` → 服务热重载 |
| 右上角 | `×` 关闭，Activity 和 WebView 一起销毁 |

**页面扫描范围**：共享目录、`/sdcard/Download/`、U 盘 / SD 卡（动态枚举，插拔后重开设置页可见）。
每个目录最多收 300 个 `.html`，递归 2 层，所以 `my-hud/index.html` 这种带引用的整包也能选。

设置页打开时主屏浮层会主动让开（`Main.visible` + `ov.dash.check` 广播），关掉就恢复。

> 首屏不扫描：`info()` 只回环境信息，页面列表走单独的 `pages()` 接口，
> 因为扫 U 盘可能很慢。所以下拉会比界面晚一点出现。

## 本地预览（不上车机）

```bash
python3 dev/preview.py           # 打开 http://127.0.0.1:8765
```

- 首页按 `dev/config.json` 自动绑屏，点「预览」直接进对应屏幕（主屏 1708×880 / 副屏 1139×684）
- **背景是实车截图**（在 `dev/bg/`），页面真的叠在车机画面上：

  | 文件 | 用于 |
  |---|---|
  | `main.png` | 主屏桌面 |
  | `cluster.png` | 副屏 |
  | `app.png` | 主屏的「设置页」场景 |

  截图就是实车画面，不再额外画 QNX 边栏/导航栏遮罩。
- 顶部按钮：`桌面 / 设置页`（切背景 + `onDesktop`，只主屏有）；
  `一直显示 / 只桌面显示`（模拟 `onlyOnDesktop`：选“只桌面”时切到设置页，页面直接隐藏）
- 滑块：`车速` `0~180`、`电量` `0~100`，拖动即时生效
- 假数据每秒推一次 `window.onData`，**500+ 个字段，key 就是真实 key**（从 `assets/keys.txt` 生成）
- 改完 HTML 自动刷新，不用手动重启服务
- 页面放 `assets/` 或 `dev/pages/`（后者不存在就跳过），`ui-settings.html` 不列入

> 本地是新版 Chrome，**测不出车机 WebView 83 的兼容问题**。
> 上线前对照一下 `docs/HTML设计指南.md` 4.1 的不能用清单。

### 页面改哪一份？

| 位置 | 作用 |
|---|---|
| **`dev/pages/`** | 开发版，**预览优先读这里**，同名时盖住 assets 的，随便改 |
| `assets/` | 出厂默认页，会被打进 APK |

上车流程：

```bash
# 1. 改 dev/pages/ui-main.html，浏览器里看效果
# 2. 推到车机（共享目录）
adb push dev/pages/ui-main.html /sdcard/Android/media/ov.dash/ui-main.html
# 3. 定稿后同步回 assets/，下次编译进 APK
cp dev/pages/ui-main.html assets/ui-main.html
```

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
      "enabled": true, "onlyOnDesktop": true,
      "launcherPkg": "com.zone.hmi.launcher" },
    { "display": 1, "html": "ui-cluster.html", "name": "cluster", "enabled": true }
  ]
}
```

保存后自动热重载。字段说明见 `docs/HTML设计指南.md` 第 6.3 节。

## 广播

```bash
adb shell am broadcast -a ov.dash.reload   # 重载配置和页面
adb shell am broadcast -a ov.dash.stop     # 停止服务
adb shell am broadcast -a ov.dash.check    # 重新判定浮层显隐（设置页自己用）
adb shell am broadcast -a ov.dash.json     # 打印当前 JSON
adb shell am broadcast -a ov.dash.media    # 打印媒体/前台状态
```

还有几个开发用的：`ov.dash.probe`（枚举 binder pool）、`ov.dash.scan`（扫全部 key）、
`ov.dash.top`（打印前台包名）、`ov.dash.mc`（强制刷媒体）。

## 目录

```
assets/          打进 APK 的默认页面和 keys.txt
dev/             开发期脚本和本地预览
  setup.sh         一键装包 + 全部授权
  push-page.sh     推页面到共享目录并热重载
  enable-a11y.sh   启用无障碍（顺序敏感）
  preview.py       本地预览服务，见「本地预览」
  pages/           开发版页面，预览优先读这里
  bg/              预览用实车背景图
  config.json      预览用的配置
build/           构建中间产物
ovdash.apk       构建产物，每次 ./build.sh 自动刷新，直接 adb push 这个
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

## 相关项目

- [**mg4shell**](https://github.com/kicer/mg4-adb-shell) — 手机端 adb 客户端，本项目的安装就是用它一键跑的
- [**esp32-mg4**](https://github.com/kicer/esp32-mg4) — 无线 ADB 硬件方案
