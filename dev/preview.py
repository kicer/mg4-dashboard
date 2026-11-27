#!/usr/bin/env python3
"""ov.dash 页面本地预览。

    python3 dev/preview.py                  # 打开 http://127.0.0.1:8765
    python3 dev/preview.py --port 9000

只模拟 HTML 这一层：静态文件 + 每秒推一次 window.onData。
不涉及浏览器之外的任何东西——数据是假的，WebView 版本差异也测不出来（本地是
新版 Chrome，车机是 83）。

页面来源：assets/*.html（自带默认页）和 dev/pages/*.html（自己放测试页）。
"""

import argparse
import hashlib
import http.server
import json
import math
import os
import signal
import socketserver
import subprocess
import sys
import time
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "assets")
EXTRA = os.path.join(ROOT, "dev", "pages")
KEYS_FILE = os.path.join(ASSETS, "keys.txt")

# 页面相对资源的 Content-Type
MIME = {
    ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
    ".gif": "image/gif", ".webp": "image/webp", ".svg": "image/svg+xml",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".woff2": "font/woff2", ".ttf": "font/ttf",
}

# 预览背景：实车截图，已拉进 dev/bg/，仓库自包含
#   main.png    主屏桌面（1920×1080）
#   cluster.png 副屏（1280×768）
#   app.png     应用内（主屏 1920×1080）
_BG_DIR = os.path.join(ROOT, "dev", "bg")
BG = {
    "0": os.path.join(_BG_DIR, "main.png"),
    "1": os.path.join(_BG_DIR, "cluster.png"),
    "2": os.path.join(_BG_DIR, "app.png"),
}

# 车机上的两块屏，单位是 CSS px（已按 dpr 1.125 换算过）
# slot 是 /data 用的数据通道，场景 2（应用内）同样是主屏数据
SCREENS = {
    "0": {"w": 1708, "h": 880, "name": "主屏 display 0", "slot": "0"},
    "1": {"w": 1139, "h": 684, "name": "副屏 display 1", "slot": "1"},
    "2": {"w": 1708, "h": 880, "name": "应用内（主屏）", "slot": "0"},
}


def load_keys():
    keys = []
    try:
        with open(KEYS_FILE, encoding="utf-8") as f:
            for ln in f:
                ln = ln.strip()
                if ln and not ln.startswith("#"):
                    k = ln.split("|")[0].strip()
                    if k:
                        keys.append(k)
    except OSError:
        pass
    return keys


KEYS = load_keys()


def stable(key):
    return int.from_bytes(hashlib.md5(key.encode()).digest()[:4], "big")


# GPS 模拟：真实道路那种「直道 → 缓弯 → 直道 → 急弯」循环
_GPS = {"lat": 35.39629, "lon": 116.99190, "bearing": 0.0, "last_t": 0.0}

# 每 7 秒换一段路，角度是相对初始朝向的目标偏角
_ROAD_PLAN = [0.0, 38.0, 0.0, -62.0, 12.0, -25.0, 0.0]


def gps_advance(speed_kmh):
    """推进模拟车辆位置，返回 (lat, lon, bearing, gpsSpeed)。"""
    g = _GPS
    now = time.time()
    dt = 1.0 if not g["last_t"] else max(0.0, min(2.0, now - g["last_t"]))
    if dt < 0.5:
        return g["lat"], g["lon"], g["bearing"], speed_kmh
    g["last_t"] = now

    # 目标朝向平滑过渡，不像直角那么生硬
    target = _ROAD_PLAN[int(now / 7) % len(_ROAD_PLAN)]
    g["bearing"] += (target - g["bearing"]) * min(1.0, 0.55 * dt)
    while g["bearing"] > 180.0:
        g["bearing"] -= 360.0
    while g["bearing"] < -180.0:
        g["bearing"] += 360.0

    # 60km/h ≈ 0.00015 度/秒；预览里再放大 5 倍，让轨迹快点铺开
    step = max(0.0, speed_kmh) * 0.000012 * dt
    rad = math.radians(g["bearing"])
    g["lat"] += step * math.cos(rad)
    g["lon"] += step * math.sin(rad)
    return g["lat"], g["lon"], g["bearing"], speed_kmh


_SIM = {"t": 0}


def trip_curve(t):
    """360 秒一轮的行程：起步 → 巡航 → 减速回收 → 刹停。返回 (车速, 电流)。"""
    t = t % 360
    if t < 12:
        return t * 7.5, 190.0
    if t < 70:
        return 90 - (70 - t) * 0.3, 90 + math.sin(t / 6) * 50
    if t < 200:
        return 88 + math.sin(t / 14) * 12, 35 + math.sin(t / 9) * 35
    if t < 260:
        return max(0.0, 88 - (t - 200) * 1.45), -35 - math.sin(t / 8) * 10
    if t < 300:
        return max(0.0, 4 - (t - 260) * 0.4), -12.0
    return 0.0, 0.0


def mock(slot, tick, speed=None, soc=None, desktop=True, aec=None):
    """造一份和车机同形状的数据：key 就是 keys.txt 里的原始 key。

    speed / soc / aec 传了就固定用，不传就走自动波动。
    aec 是目标瞬时电耗（kWh/100km），这里按 380V 反算电流，
    页面那边的 instantAec() 再换算回去，保证闭环一致。
    """
    d = {}
    for k in KEYS:
        v = stable(k)
        if "TIRE" in k and "PRESSURE" in k:
            d[k] = round(2.2 + (v % 40) / 100.0, 2)
        elif "ODO" in k:
            d[k] = round(12000 + (v % 5000) / 10.0, 1)
        elif "TEMP" in k:
            d[k] = round(22 + (v % 60) / 10.0, 1)
        else:
            d[k] = round((v % 1000) / 10.0, 1)

    # 行程曲线驱动：每请求一次推进 1 秒
    _SIM["t"] += 1
    sim_t = _SIM["t"]
    spd_sim, cur_sim = trip_curve(sim_t)

    d["SPEED"] = float(speed) if speed is not None else round(spd_sim, 1)
    d["BMS_SOC_PERCENT"] = (float(soc) if soc is not None
                            else round(80 - (sim_t % 360) * 0.012, 1))

    # 门锁给正常值，否则随机数会让车图一直报警
    d["DOOR_OPEN_COND"] = 0
    d["DOOR_LOCK_STATUS"] = 1

    # 电压随负载轻微浮动；电流默认来自行程曲线
    volt = round(398 + math.sin(sim_t / 40) * 3, 1)
    d["BMS_DISP_PACK_VOL_ORG"] = volt

    if aec is not None:
        # 电耗滑杆优先：P(kW) = AEC × v / 100，I = P × 1000 / V
        d["IPK_AVERAGE_PC_AEC1"] = round(aec, 1)
        if cur_sim < 0:
            d["BMS_DISP_PACK_CURRENT_ORG"] = round(cur_sim, 1)   # 回收段保留负值
        else:
            d["BMS_DISP_PACK_CURRENT_ORG"] = round(aec * d["SPEED"] * 10.0 / volt, 1)
    else:
        d["BMS_DISP_PACK_CURRENT_ORG"] = round(cur_sim, 1)
        d["IPK_AVERAGE_PC_AEC1"] = 15.2
    d["IPK_TRIPMETER_TRIP1"] = round(42.3 + sim_t * 0.02, 1)
    d["IACINCARTEM"] = 22.5
    d["OUTSIDE_TEP"] = 18.3

    # GPS 按当前车速推进，模拟真实行驶
    g_lat, g_lon, g_brg, g_spd = gps_advance(d["SPEED"])

    d.update({
        "_slot": "main" if slot == "0" else "cluster",
        "_tick": tick,
        "_ts": int(time.time() * 1000),
        "onDesktop": desktop,
        "fgApp": "com.zone.hmi.launcher" if desktop else "com.zone.hmi.navi",
        "mediaTitle": "成吉思汗的传说",
        "mediaArtist": "格格",
        "mediaAlbum": "成吉思汗的传说",
        "mediaSource": "bt_music",
        "mediaPlaying": True,
        "lat": round(g_lat, 6),
        "lon": round(g_lon, 6),
        "gpsSpeed": round(g_spd, 1),
        # 每秒转 0.55°，罗盘会画出一圈螺旋
        "bearing": round((sim_t * 0.55) % 360, 1),
        "hAcc": 5,
        "vAcc": 8,
        "gpsAge": 1200,
        "alt": round(82 + math.sin(sim_t / 45) * 18),
    })
    return d


def load_cfg():
    """读 dev/config.json，用来把页面绑定到正确的屏。"""
    try:
        with open(os.path.join(ROOT, "dev", "config.json"), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def page_screen(name):
    """这个 html 挂在哪块屏。查不到就当主屏。"""
    for s in load_cfg().get("screens", []):
        if s.get("html") == name:
            return str(s.get("display", 0))
    return "0"


def find_page(name):
    """按文件名找页面。dev/pages 优先，方便边改边预览。"""
    for d in (EXTRA, ASSETS):
        p = os.path.join(d, name)
        if os.path.isfile(p) and p.endswith(".html"):
            return p
    return None


def list_pages():
    """返回 [(文件名, 来源)]。dev/pages 优先，同名时只列开发版。"""
    out, seen = [], set()
    for d, tag in ((EXTRA, "dev/pages"), (ASSETS, "assets")):
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            # 设置页依赖 dash 接口，预览环境里拿不到，不列出来
            if not name.endswith(".html") or name == "ui-settings.html":
                continue
            if name in seen:
                continue
            seen.add(name)
            out.append((name, tag))
    return out


SHIM = """
<script>
/* ---- ov.dash 本地预览注入：只在这里存在，车机上没有 ---- */
(function () {
  var SLOT = %s;
  var OV = {};
  var lastPull = 0;
  var TIMER = null;

  function startTimer(ms) {
    if (TIMER) clearInterval(TIMER);
    TIMER = setInterval(pull, ms);
  }

  window.addEventListener('message', function (e) {
    if (!e.data) return;
    /* 容器页顶部的快进滑块改采样间隔 */
    if (e.data.interval) startTimer(e.data.interval);
    if (!e.data.__ovdash) return;
    OV = e.data.__ovdash;
    /* 拖滑块时 input 每像素触发一次，节流到 250ms，别把采样窗口刷掉 */
    var now = Date.now();
    if (now - lastPull < 250) return;
    lastPull = now;
    pull();
  });

  function pull() {
    var q = '/data?slot=' + SLOT + '&t=' + Date.now();
    for (var k in OV) {
      if (OV[k] !== null && OV[k] !== undefined) {
        q += '&' + k + '=' + encodeURIComponent(OV[k]);
      }
    }
    var x = new XMLHttpRequest();
    x.open('GET', q, true);
    x.onload = function () {
      if (x.status !== 200) return;
      var d;
      try { d = JSON.parse(x.responseText); } catch (e) { return; }
      if (typeof window.onData === 'function') {
        try { window.onData(d); } catch (e) { console.error('[preview] onData 抛错', e); }
      }
    };
    x.send();
  }
  pull();
  startTimer(1000);
})();
</script>
"""

CONTAINER = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>%(title)s</title>
<style>
  html,body{margin:0;background:#15181c;color:#9fb0bd;
    font-family:-apple-system,'Noto Sans CJK SC',sans-serif}
  .bar{padding:9px 16px;font-size:13px;background:#1e2328;border-bottom:1px solid #2a3038;
    display:flex;align-items:center;flex-wrap:wrap}
  .bar b{color:#cfdae2;font-weight:600;margin-right:12px}
  .bar span{margin-right:14px}
  .bar>a{color:#4fc3f7;text-decoration:none;margin-left:auto}
  .ctl{display:inline-flex;align-items:center;font-size:12px;color:#9fb0bd;margin-right:14px}
  .ctl input[type=range]{width:96px;margin:0 6px;accent-color:#00b8d4}
  .ctl b{color:#cfdae2;font-weight:600;min-width:28px;display:inline-block}
  .seg{display:inline-flex;border:1px solid #2e4150;border-radius:7px;overflow:hidden;margin-right:14px}
  .seg a{padding:4px 11px;font-size:12px;color:#8fa9ba;text-decoration:none;cursor:pointer}
  .seg a.on{background:#00b8d4;color:#04222b;font-weight:600}
  .stage{position:relative;margin:20px auto;box-shadow:0 0 0 1px #3a444d;
    width:%(w)dpx;height:%(h)dpx;background:#0b1116;
    background-size:100%% auto;background-position:top left;background-repeat:no-repeat}
  iframe{border:0;display:block;width:%(w)dpx;height:%(h)dpx;background:transparent}
</style></head>
<body>
<div class="bar">
  <b>%(title)s</b>
  <span>%(w)d × %(h)d</span>
  <span id="zoom" style="color:#4fc3f7;margin-right:14px">100%%</span>
  %(scene)s
  <span class="seg" id="disp">
    <a data-v="1" class="on">一直显示</a><a data-v="0">只桌面显示</a>
  </span>
  <label class="ctl"><input type="checkbox" id="autospd" checked>自动速度</label>
  <label class="ctl">车速<input type="range" id="spd" min="0" max="180" step="1" value="90"><b id="spdv">90</b></label>
  <label class="ctl">快进<input type="range" id="fwd" min="1" max="20" step="1" value="1"><b id="fwdv">1&times;</b></label>
  <label class="ctl">电量<input type="range" id="soc" min="0" max="100" step="1" value="78"><b id="socv">78</b></label>
  <label class="ctl">电耗<input type="range" id="aec" min="0" max="40" step="1" value="15"><b id="aecv2">15</b></label>
  <span id="st">热重载中…</span>
  <a href="/">← 换页面</a>
</div>
<div class="stage" id="stage">
  <iframe id="f" src="/raw?f=%(file)s&slot=%(slot)s"></iframe>
</div>
<script>
  var D = %(d)s;
  var W = %(w)d, H = %(h)d;
  var SCENE = 'desk';      /* desk | app */
  var ONLY = false;        /* true = 只桌面显示 */
  var stage = document.getElementById('stage');
  var f = document.getElementById('f');

  /* 窗口放不下就等比缩小（zoom 会把布局尺寸一起缩，不会多出滚动条） */
  function fit() {
    var s = Math.min(1, (document.body.clientWidth - 40) / W);
    stage.style.zoom = s;
    document.getElementById('zoom').textContent = Math.round(s * 100) + '%%';
  }
  window.addEventListener('resize', fit);
  fit();

  function push() {
    var spd = document.getElementById('spd').value;
    var soc = document.getElementById('soc').value;
    var aec = document.getElementById('aec').value;
    var auto = document.getElementById('autospd').checked;
    var mul = +document.getElementById('fwd').value;
    document.getElementById('spdv').textContent = spd;
    document.getElementById('socv').textContent = soc;
    document.getElementById('aecv2').textContent = aec;
    document.getElementById('fwdv').innerHTML = mul + '&times;';
    if (!f.contentWindow) return;
    f.contentWindow.postMessage({
      __ovdash: {
        speed: auto ? null : spd,   /* 勾了自动就不传，让服务端走行程曲线 */
        soc: soc, aec: aec,
        desk: SCENE === 'desk' ? 1 : 0
      },
      interval: Math.round(1000 / mul)
    }, '*');
  }

  function render() {
    stage.style.backgroundImage = 'url(' +
      (SCENE === 'app' ? '/bg?d=2' : '/bg?d=' + D) + ')';
    f.style.visibility = (SCENE === 'desk' || !ONLY) ? 'visible' : 'hidden';

    document.querySelectorAll('#disp a').forEach(function (a) {
      a.className = (a.dataset.v === '0') === ONLY ? 'on' : '';
    });
    document.querySelectorAll('#scene a').forEach(function (a) {
      a.className = (a.dataset.v === SCENE) ? 'on' : '';
    });
    push();
  }

  document.querySelectorAll('#disp a').forEach(function (a) {
    a.onclick = function () { ONLY = a.dataset.v === '0'; render(); };
  });
  document.querySelectorAll('#scene a').forEach(function (a) {
    a.onclick = function () { SCENE = a.dataset.v; render(); };
  });

  f.onload = function () { render(); };
  ['spd', 'soc', 'aec', 'fwd', 'autospd'].forEach(function (id) {
    document.getElementById(id).addEventListener('input', push);
  });
  render();

  var last = null;
  setInterval(function () {
    var x = new XMLHttpRequest();
    x.open('GET', '/mtime?f=%(file)s&t=' + Date.now(), true);
    x.onload = function () {
      if (x.status !== 200) return;
      if (last === null) { last = x.responseText; document.getElementById('st').textContent = '已连接'; return; }
      if (last !== x.responseText) { location.reload(); }
    };
    x.send();
  }, 800);
</script>
</body></html>
"""

INDEX = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>ov.dash 预览</title>
<style>
  html,body{margin:0;background:#15181c;color:#b9cbd8;
    font-family:-apple-system,'Noto Sans CJK SC',sans-serif}
  .wrap{max-width:820px;margin:0 auto;padding:36px 24px}
  h1{font-size:24px;color:#e3edf3;margin:0 0 6px}
  .sub{font-size:13px;color:#6f8b9c;margin-bottom:26px;line-height:1.7}
  .row{display:flex;align-items:center;padding:14px 18px;margin-bottom:10px;
    background:#1b2228;border:1px solid #29333b;border-radius:10px}
  .nm{flex:1;font-size:16px;color:#cfdae2}
  .tag{font-size:12px;color:#6f8b9c;margin-right:14px}
  .btn{font-size:13px;padding:7px 16px;border-radius:7px;margin-left:8px;
    background:#00b8d4;color:#04222b;text-decoration:none;font-weight:600}
  .btn.alt{background:#31505f;color:#c6dced}
  .empty{color:#7a8f9c;font-size:14px}
</style></head>
<body><div class="wrap">
<h1>ov.dash 页面预览</h1>
<div class="sub">
  假数据，每秒推一次 <code>window.onData</code>。改文件后容器会自动刷新。<br>
  本地是新版 Chrome，测不出车机 WebView 83 的兼容问题（见 docs/HTML设计指南.md 4.1）。
</div>
%(rows)s
</div></body></html>
"""


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # 安静点，只在出错时关心

    def send_text(self, body, code=200, ctype="text/html; charset=utf-8"):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        path = u.path

        if path == "/":
            pages = list_pages()
            if pages:
                rows = []
                for name, src in pages:
                    q = urllib.parse.quote(name)
                    sc = SCREENS.get(page_screen(name), SCREENS["0"])
                    rows.append(
                        '<div class="row"><div class="nm">%s</div>'
                        '<div class="tag">%s · %d×%d</div>'
                        '<div class="tag" style="color:#4fc3f7">%s</div>'
                        '<a class="btn" href="/p?f=%s">预览</a></div>'
                        % (name, sc["name"], sc["w"], sc["h"], src, q)
                    )
                body = INDEX % {"rows": "\n".join(rows)}
            else:
                body = INDEX % {"rows": '<div class="empty">assets/ 和 dev/pages/ 下都没有 .html</div>'}
            self.send_text(body)
            return

        if path == "/data":
            slot = (q.get("slot") or ["0"])[0]
            spd = q.get("speed")
            soc = q.get("soc")
            aec = q.get("aec")
            desk = (q.get("desk") or ["1"])[0] != "0"
            try:
                spd_v = float(spd[0]) if spd else None
                soc_v = float(soc[0]) if soc else None
                aec_v = float(aec[0]) if aec else None
            except ValueError:
                spd_v = soc_v = aec_v = None
            body = json.dumps(mock(slot, int(time.time()), spd_v, soc_v, desk, aec_v),
                              ensure_ascii=False)
            self.send_text(body, ctype="application/json; charset=utf-8")
            return

        if path == "/bg":
            # 实车截图做背景，找不到就 404，容器页退回深色底
            d = (q.get("d") or ["0"])[0]
            f = BG.get(d)
            if f and os.path.isfile(f):
                try:
                    with open(f, "rb") as fh:
                        data = fh.read()
                except OSError:
                    data = b""
                if data:
                    self.send_response(200)
                    self.send_header("Content-Type", "image/png")
                    self.send_header("Content-Length", str(len(data)))
                    self.send_header("Cache-Control", "max-age=3600")
                    self.end_headers()
                    self.wfile.write(data)
                    return
            self.send_text("没有背景图", code=404, ctype="text/plain; charset=utf-8")
            return

        if path == "/mtime":
            f = find_page((q.get("f") or [""])[0])
            if not f:
                self.send_text("0", ctype="text/plain")
                return
            self.send_text(str(os.path.getmtime(f)), ctype="text/plain")
            return

        if path in ("/raw", "/p"):
            name = (q.get("f") or [""])[0]
            f = find_page(name)
            if not f:
                self.send_text("404 没有这个页面：" + name, code=404)
                return
            if path == "/p":
                slot = (q.get("d") or [page_screen(name)])[0]
                sc = SCREENS.get(slot, SCREENS["0"])
                # 设置页只可能出现在主屏，副屏不给场景切换
                scene = ""
                if slot == "0":
                    scene = ('<span class="seg" id="scene">'
                             '<a data-v="desk" class="on">桌面</a>'
                             '<a data-v="app">设置页</a></span>')
                body = CONTAINER % {
                    "title": sc["name"] + " — " + name,
                    "w": sc["w"], "h": sc["h"],
                    "file": urllib.parse.quote(name), "slot": sc["slot"],
                    "d": int(slot), "scene": scene,
                }
                self.send_text(body)
                return
            # /raw：原样返回，但在 </body> 前注入 shim
            slot = (q.get("slot") or ["0"])[0]
            try:
                with open(f, encoding="utf-8") as fh:
                    html = fh.read()
            except OSError as e:
                self.send_text("读取失败：%s" % e, code=500)
                return
            shim = SHIM % json.dumps(slot)
            if "</body>" in html:
                html = html.replace("</body>", shim + "</body>", 1)
            else:
                html += shim
            self.send_text(html)
            return

        # 兑底：页面里的相对资源（car.png、style.css 之类）
        # /raw 的 URL 路径是 /raw，所以 car.png 会解析成 /car.png，这里补上
        base = os.path.basename(path)
        if base and "." in base and "/" not in path.strip("/"):
            for d in (EXTRA, ASSETS):
                f = os.path.join(d, base)
                if os.path.isfile(f):
                    try:
                        with open(f, "rb") as fh:
                            data = fh.read()
                    except OSError:
                        break
                    ctype = MIME.get(os.path.splitext(base)[1].lower(), "application/octet-stream")
                    self.send_response(200)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Content-Length", str(len(data)))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(data)
                    return

        self.send_text("404", code=404)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


def port_pids(port):
    """列出正在监听该端口的 pid（lsof 在 macOS/Linux 都有）"""
    try:
        out = subprocess.run(["lsof", "-nP", "-ti", "tcp:%d" % port, "-sTCP:LISTEN"],
                             capture_output=True, text=True).stdout
    except OSError:
        return []
    return [int(x) for x in out.split() if x.strip().isdigit()]


def cmdline(pid):
    try:
        return subprocess.run(["ps", "-o", "command=", "-p", str(pid)],
                              capture_output=True, text=True).stdout.strip()
    except OSError:
        return ""


def free_port(port):
    """端口被上一次的 preview.py 占着就先杀掉，省得每次重启都报 Address already in use。

    只杀命令行里带 preview.py 的进程；别的程序占着就报错退出，不误伤。
    """
    pids = [p for p in port_pids(port) if p != os.getpid()]
    if not pids:
        return True

    mine = [p for p in pids if "preview.py" in cmdline(p)]
    other = [p for p in pids if p not in mine]
    for p in other:
        print("端口 %d 被别的进程占用：pid %d  %s" % (port, p, cmdline(p)))
    if other:
        print("不是 preview.py，不动它。换个端口：python3 dev/preview.py --port 9000")
        return False

    print("端口 %d 上有上一次的预览服务（pid %s），先关掉"
          % (port, ", ".join(str(p) for p in mine)))
    for p in mine:
        try:
            os.kill(p, signal.SIGTERM)
        except OSError:
            pass
    # 等它把端口放掉，2 秒还不走就 SIGKILL
    for _ in range(20):
        time.sleep(0.1)
        if not port_pids(port):
            return True
    for p in mine:
        try:
            os.kill(p, signal.SIGKILL)
        except OSError:
            pass
    time.sleep(0.3)
    return not port_pids(port)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    a = ap.parse_args()
    if not free_port(a.port):
        sys.exit(1)
    print("ov.dash 预览：http://127.0.0.1:%d" % a.port)
    print("数据 key：%d 个（来自 assets/keys.txt）" % len(KEYS))
    print("页面目录：assets/ 和 dev/pages/")
    try:
        srv = Server(("127.0.0.1", a.port), Handler)
    except OSError as e:
        print("端口 %d 绑不上：%s" % (a.port, e))
        print("换个端口：python3 dev/preview.py --port 9000")
        sys.exit(1)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n停止")
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
