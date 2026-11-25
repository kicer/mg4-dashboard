package ov.dash;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * 副屏 HUD 服务（多屏 WebView 版）。
 *
 * 每个 enabled 的 screen 对应一个独立 WebView（View 不能跨窗口，必须一屏一实例）。
 * HTML 用 file:// 加载，同目录下的图片/CSS/JS 走相对路径即可。
 *
 * 文件布局（根目录 = Android/media/ov.dash/，公共可见，adb push 无需 chmod）:
 *   config.json       全局配置
 *   ui-cluster.html   仪表屏 UI
 *   ui-main.html      中控 UI
 *   ...               任意 png/css/js，相对路径引用
 * 页面也可以写绝对路径（以 / 开头），但 app 读不到时会白屏。
 */
public class DashService extends Service {

    private static final String TAG = "OVDASH";
    static final String ACTION_STOP = "ov.dash.stop";
    static final String ACTION_RELOAD = "ov.dash.reload";
    static final String ACTION_PROBE = "ov.dash.probe";
    static final String ACTION_SCAN = "ov.dash.scan";
    static final String ACTION_TOP = "ov.dash.top";
    static final String ACTION_MC = "ov.dash.mc";
    static final String ACTION_JSON = "ov.dash.json";
    static final String ACTION_MEDIA = "ov.dash.media";
    private static final long WATCHDOG_MS = 120_000L;
    private static final int TYPE_OVERLAY = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    /** 主线程：只负责 WebView 操作。 */
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 后台线程：负责 binder 采集，避免阻塞 UI。 */
    private HandlerThread bgThread;
    private Handler bgHandler;
    private final Signals signals = Signals.create();
    private final List<ScreenCtx> screens = new ArrayList<>();

    private Config cfg = Config.defaults();
    private File baseDir;
    private File cfgFile;
    private FileObserver observer;
    private boolean pumping;
    private int tick;
    /** 跨线程可见：主线程写，采集线程读，避免直接读 screens 列表。 */
    private volatile boolean active;
    /** 已注册「等 display 出现」的屏，避免重复注册 DisplayListener。 */
    private final Set<Integer> waiting = new HashSet<>();

    /** 一块屏一个 WebView。 */
    private static final class ScreenCtx {
        Config.Screen cfg;
        String slotName;
        Display disp;
        WebView web;
        WindowManager wm;
        WindowManager.LayoutParams lp;
        boolean attached;
    }

    /** 后台线程执行：采集完把 JSON 丢回主线程分发。 */
    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            final String json = collectJson();
            if (json != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        distribute(json);
                    }
                });
            }
            getSharedPreferences(Watchdog.PREF, MODE_PRIVATE).edit()
                    .putLong(Watchdog.KEY_HB, System.currentTimeMillis()).apply();
            if (bgHandler != null) {
                bgHandler.postDelayed(this, Math.max(500, cfg.refresh));
            }
        }
    };

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_STOP.equals(a)) {
                stopSelf();
            } else if (ACTION_RELOAD.equals(a)) {
                reloadAll();
            } else if (ACTION_PROBE.equals(a)) {
                // binder 扫描不能跑主线程
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        signals.probePool(getPackageName());
                    }
                }).start();
            } else if (ACTION_SCAN.equals(a)) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        doScan();
                    }
                }).start();
            } else if (ACTION_TOP.equals(a)) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        probeTop();
                    }
                }).start();
            } else if (ACTION_MC.equals(a)) {
                MediaCenterReader.init(DashService.this);
                MediaCenterReader.refresh();
            } else if (ACTION_JSON.equals(a)) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.location.LocationManager lm =
                                    (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
                            Log.e(TAG, "json: " + signals.collect(lm));
                        } catch (Throwable t) {
                            Log.e(TAG, "json fail: " + t);
                        }
                    }
                }).start();
            } else if (ACTION_MEDIA.equals(a)) {
                Log.e(TAG, "media: title=" + MediaCenterReader.title
                        + " | artist=" + MediaCenterReader.artist
                        + " | album=" + MediaCenterReader.album
                        + " | src=" + MediaCenterReader.kind
                        + " | playing=" + MediaListener.playing
                        + " | appWindow=" + TopWatcher20.hasAppWindow
                        + " | appPkg=" + TopWatcher20.topAppPkg
                        + " || onDesktop=" + isOnDesktop()
                        + " | fgApp=" + topPkg
                        + " | pushCount=" + Signals.pushCount.get());
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, notif());

        baseDir = pickBaseDir();
        cfgFile = new File(baseDir, "config.json");
        cfg = Config.load(cfgFile);
        Log.e(TAG, "config loaded: refresh=" + cfg.refresh + " screens=" + cfg.screens.size());

        copyMissingAssets();
        boolean ok = signals.bind(getPackageName());
        Log.e(TAG, "signals bind=" + ok);
        signals.loadExtra(baseDir);
        if (ok) {
            // 订阅 + 首次填充都放后台：几百个 key 全量读一遍要几秒
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG, "subscribe=" + signals.subscribeAll());
                    signals.primeCache();
                }
            }, "ovdash-subscribe").start();
        }
        // 媒体中心 bind 放在这里发起，不依赖无障碍/通知监听是否连上
        MediaCenterReader.init(this);
        // 无障碍事件到达时直接触发一次检查，不再靠定时轮询
        TopWatcher20.onChange = new Runnable() {
            @Override
            public void run() {
                handler.post(topChecker);
            }
        };

        startWatchdog();
        startObserver();
        buildScreens();
        registerReceiver(rx, filter());

        bgThread = new HandlerThread("ovdash-collect");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        bgHandler.post(loop);
        Log.e(TAG, "service ready dir=" + baseDir);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(rx);
        } catch (Throwable ignore) {
            // 未注册
        }
        if (observer != null) {
            observer.stopWatching();
        }
        destroyAll();
        if (bgHandler != null) {
            bgHandler.removeCallbacksAndMessages(null);
        }
        if (bgThread != null) {
            bgThread.quitSafely();
        }
        super.onDestroy();
        Log.e(TAG, "destroyed");
    }

    private IntentFilter filter() {
        IntentFilter f = new IntentFilter(ACTION_STOP);
        f.addAction(ACTION_RELOAD);
        f.addAction(ACTION_PROBE);
        f.addAction(ACTION_SCAN);
        f.addAction(ACTION_TOP);
        f.addAction(ACTION_MC);
        f.addAction(ACTION_JSON);
        f.addAction(ACTION_MEDIA);
        return f;
    }

    // ---------------- 多屏管理 ----------------

    private void buildScreens() {
        destroyAll();
        for (Config.Screen s : cfg.screens) {
            if (!s.enabled) {
                Log.e(TAG, "screen disabled: display=" + s.display);
                continue;
            }
            createScreen(s);
        }
        active = !screens.isEmpty();
        Log.e(TAG, "active screens=" + screens.size() + " active=" + active);
        handler.removeCallbacks(topChecker);
        if (active && needTopCheck()) {
            handler.post(topChecker);
        }
    }

    private void createScreen(final Config.Screen s) {
        final DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display d = dm.getDisplay(s.display);
        if (d == null) {
            // 同一块屏只注册一次，否则热重载会堆积多个 listener，副屏一出现就叠出多个 WebView
            if (!waiting.add(s.display)) {
                Log.e(TAG, "display " + s.display + " not ready, already waiting");
                return;
            }
            Log.e(TAG, "display " + s.display + " not ready, waiting");
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int id) {
                    if (id == s.display) {
                        waiting.remove(s.display);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                createScreen(s);
                            }
                        });
                    }
                }

                @Override
                public void onDisplayRemoved(int id) {
                    Log.e(TAG, "display removed " + id);
                }

                @Override
                public void onDisplayChanged(int id) {
                    // no-op
                }
            }, handler);
            return;
        }

        ScreenCtx c = new ScreenCtx();
        c.cfg = s;
        c.slotName = slotNameOf(s);
        c.disp = d;
        c.lp = makeLp(s.height);
        buildWeb(c);
        // onlyOnDesktop 的屏由 topChecker 决定何时挂上去，先加载不显示
        if (!s.onlyOnDesktop || s.launcherPkg.equals(currentTop())) {
            try {
                c.wm.addView(c.web, c.lp);
                c.attached = true;
            } catch (Throwable t) {
                Log.e(TAG, "addView fail display=" + s.display + ": " + t);
                c.web.destroy();
                c.web = null;
                return;
            }
        }
        screens.add(c);
        // 副屏可能晚于 buildScreens 就绪，这里必须同步一次，否则采集被 active 短路
        active = !screens.isEmpty();
        Log.e(TAG, "screen created display=" + s.display + " html=" + s.html + " attached=" + c.attached);
    }

    /** config 里的 name，没写就用 html 文件名去后缀。 */
    private static String slotNameOf(Config.Screen s) {
        if (s.name != null && !s.name.isEmpty()) {
            return s.name;
        }
        String h = s.html == null ? "" : s.html;
        int p = h.lastIndexOf('.');
        return p > 0 ? h.substring(0, p) : h;
    }

    private WindowManager.LayoutParams makeLp(int height) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height > 0 ? height : ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    /** 建一套 WebView（含该 display 的 WindowManager），并开始加载 html。 */
    private void buildWeb(ScreenCtx c) {
        Context ctx = createDisplayContext(c.disp);
        c.wm = (WindowManager) ctx.getSystemService(WINDOW_SERVICE);
        c.web = new WebView(ctx);
        Log.e(TAG, "display=" + c.cfg.display + " density="
                + ctx.getResources().getDisplayMetrics().density
                + " dpi=" + ctx.getResources().getDisplayMetrics().densityDpi);
        c.web.setBackgroundColor(Color.TRANSPARENT);
        c.web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings ws = c.web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        c.web.setWebViewClient(new WebViewClient());
        c.web.setVerticalScrollBarEnabled(false);
        c.web.setHorizontalScrollBarEnabled(false);
        loadScreen(c);
    }

    /** file:// 加载，同目录相对路径的图片/CSS/JS 自动可用。 */
    private void loadScreen(ScreenCtx c) {
        File f = resolve(c.cfg.html);
        if (!f.exists() && !c.cfg.html.startsWith("/")) {
            copyAsset(c.cfg.html, f);
        }
        String url = Uri.fromFile(f).toString();
        Log.e(TAG, "load screen display=" + c.cfg.display + " url=" + url + " exists=" + f.exists());
        c.web.loadUrl(url);
    }

    private void destroyAll() {
        handler.removeCallbacks(topChecker);
        for (ScreenCtx c : screens) {
            try {
                if (c.attached && c.wm != null && c.web != null) {
                    c.wm.removeViewImmediate(c.web);
                    c.attached = false;
                }
            } catch (Throwable ignore) {
                // 已移除
            }
            try {
                if (c.web != null) {
                    c.web.destroy();
                }
            } catch (Throwable ignore) {
                // 已销毁
            }
        }
        screens.clear();
        active = false;
    }

    private void reloadAll() {
        cfg = Config.load(cfgFile);
        Log.e(TAG, "reload config: refresh=" + cfg.refresh + " screens=" + cfg.screens.size());
        copyMissingAssets();
        buildScreens();
    }

    // ---------------- 数据推送 ----------------

    /** 后台线程：采集全部信号，返回 JSON 字符串。 */
    private String collectJson() {
        if (pumping || !active) {
            return null;
        }
        pumping = true;
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            JSONObject o = signals.collect(lm);
            tick++;
            o.put("_tick", tick);
            // 当前是否在桌面，以及前台包名，作为数据源交给 HTML 自行处理
            try {
                boolean desktop = isOnDesktop();
                o.put("onDesktop", desktop);
                if (topPkg != null) {
                    o.put("fgApp", topPkg);
                }
            } catch (Throwable ignore) {
                // 前台状态取不到不影响其他字段
            }
            return o.toString();
        } catch (Throwable t) {
            Log.e(TAG, "collect fail: " + t);
            return null;
        } finally {
            pumping = false;
        }
    }

    /** 主线程：把 JSON 推给各屏 WebView，值未变化时按策略跳过。 */
    private void distribute(String json) {
        for (ScreenCtx c : screens) {
            if (c.web == null) {
                continue;
            }
            try {
                String per = json.substring(0, json.length() - 1)
                        + ",\"_slot\":\"" + c.slotName + "\",\"_h\":" + c.cfg.height + "}";
                c.web.evaluateJavascript("window.onData&&window.onData(" + per + ")", null);
            } catch (Throwable t) {
                Log.e(TAG, "distribute fail display=" + c.cfg.display + ": " + t);
            }
        }
    }

    // ---------------- 资源 ----------------

    /** 相对路径按根目录展开；以 / 开头当绝对路径（外部目录里的页面）。 */
    private File resolve(String path) {
        if (path == null) {
            return new File(baseDir, "ui-main.html");
        }
        return path.startsWith("/") ? new File(path) : new File(baseDir, path);
    }

    /**
     * 页面根目录。优先 Android/media/<pkg>：文件管理器可见，adb push 不需要
     * 中转和 chmod（Android/data 下 push 会 fchown 失败）。
     * 没有外部存储时退回私有目录。
     */
    private File pickBaseDir() {
        File legacy = getExternalFilesDir(null);
        File picked = null;
        File[] all = getExternalMediaDirs();
        if (all != null) {
            for (File d : all) {
                if (d != null) {
                    picked = d;
                    break;
                }
            }
        }
        if (picked == null) {
            return legacy;
        }
        migrateLegacy(legacy, picked);
        return picked;
    }

    /** 老版本的页面在 Android/data 下，首次切目录时搬一次，不覆盖已有文件。 */
    private void migrateLegacy(File from, File to) {
        if (from == null || to == null) {
            return;
        }
        if (new File(to, "config.json").exists()) {
            return;                       // 已经迁移过
        }
        File[] fs = from.listFiles();
        if (fs == null) {
            return;
        }
        int n = 0;
        for (File f : fs) {
            if (!f.isFile()) {
                continue;
            }
            File dst = new File(to, f.getName());
            if (dst.exists()) {
                continue;
            }
            try {
                Files.copy(f.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                n++;
            } catch (Throwable t) {
                Log.e(TAG, "migrate fail " + f.getName() + ": " + t);
            }
        }
        Log.e(TAG, "migrated " + n + " files -> " + to);
    }

    private void copyMissingAssets() {
        for (Config.Screen s : cfg.screens) {
            if (s.html != null && s.html.startsWith("/")) {
                continue;                 // 外部绝对路径，assets 里没有
            }
            File f = new File(baseDir, s.html);
            if (!f.exists()) {
                copyAsset(s.html, f);
            }
        }
        File kf = new File(baseDir, "keys.txt");
        if (!kf.exists()) {
            copyAsset("keys.txt", kf);
        }
    }

    /** 缓存的前台包名，由 topChecker 维护。 */
    private String topPkg;
    private long topCheckedAt;

    /** 当前是否在桌面。取配置里第一个 onlyOnDesktop 屏的 launcherPkg 作为基准。 */
    private boolean isOnDesktop() {
        String top = topPkg;
        if (top == null) {
            return false;
        }
        for (Config.Screen s : cfg.screens) {
            if (s.onlyOnDesktop && s.launcherPkg != null) {
                return s.launcherPkg.equals(top);
            }
        }
        return "com.zone.hmi.launcher".equals(top);
    }

    /** 取当前前台包名：无障碍事件只当触发器，实际值仍用 queryEvents 确认。
     *  因为 launcher 的常驻浮层也会发 TYPE_WINDOW_STATE_CHANGED，包名是 launcher，
     *  光信事件会被误判成「回到桌面」。 */
    private String currentTop() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return TopWatcher20.connected ? TopWatcher20.topPkg : topPkg;
        }
        long now = System.currentTimeMillis();
        long from = topCheckedAt > 0 ? topCheckedAt - 1000 : now - 3600000L;
        try {
            UsageEvents ev = usm.queryEvents(from, now);
            if (ev != null) {
                UsageEvents.Event e = new UsageEvents.Event();
                String p = null;
                long t = 0;
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && e.getTimeStamp() >= t) {
                        t = e.getTimeStamp();
                        p = e.getPackageName();
                    }
                }
                // 过滤自己，否则打开 Main 会被误判成“离开桌面”
                if (p != null && !getPackageName().equals(p)) {
                    if (!p.equals(topPkg)) {
                        Log.e(TAG, "top -> " + p);
                    }
                    topPkg = p;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "currentTop fail: " + t);
        }
        topCheckedAt = now;
        return topPkg;
    }

    /** 有主屏就必须知道前台状态：onlyOnDesktop 要用，onDesktop 字段也要用。 */
    private boolean needTopCheck() {
        for (Config.Screen s : cfg.screens) {
            if (s.enabled && s.display == 0) {
                return true;
            }
        }
        return false;
    }

    /** 按 topCheckMs 对 onlyOnDesktop 的屏做显示/隐藏判断。 */
    private final Runnable topChecker = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);   // 防止重复排队
            try {
                String top = currentTop();
                for (ScreenCtx c : screens) {
                    if (!c.cfg.onlyOnDesktop) {
                        continue;
                    }
                    setScreenVisible(c, c.cfg.launcherPkg.equals(top));
                }
            } catch (Throwable t) {
                Log.e(TAG, "topChecker fail: " + t);
            }
            // 无障碍在线时由事件触发，不再定时轮询；没连上才退回定时查询
            if (!TopWatcher20.connected && active && needTopCheck()) {
                handler.postDelayed(this, Math.max(100, cfg.topCheckMs));
            }
        }
    };

    private void setScreenVisible(ScreenCtx c, boolean show) {
        if (c.attached == show) {
            return;
        }
        if (show) {
            try {
                buildWeb(c);
                c.wm.addView(c.web, c.lp);
                c.attached = true;
                Log.e(TAG, "display=" + c.cfg.display + " attached top=" + topPkg);
            } catch (Throwable t) {
                Log.e(TAG, "show display=" + c.cfg.display + " fail: " + t);
            }
        } else {
            try {
                c.wm.removeViewImmediate(c.web);
            } catch (Throwable ignore) {
                // 已移除
            }
            try {
                c.web.destroy();
            } catch (Throwable ignore) {
                // 已销毁
            }
            c.web = null;
            c.wm = null;
            c.attached = false;
            Log.e(TAG, "display=" + c.cfg.display + " destroyed top=" + topPkg);
        }
    }

    /** 调试：查当前前台包名（需要 GET_USAGE_STATS appop）。 */
    private void probeTop() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                Log.e(TAG, "top: usm null");
                return;
            }
            long now = System.currentTimeMillis();
            // 方案 A：事件流，窗口拉大到 60 秒
            UsageEvents ev = usm.queryEvents(now - 3600000L, now);
            if (ev == null) {
                Log.e(TAG, "top(events) null, 权限没生效?");
            } else {
                UsageEvents.Event e = new UsageEvents.Event();
                String pkg = null;
                long t = 0;
                int n = 0;
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    n++;
                    if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && e.getTimeStamp() >= t) {
                        t = e.getTimeStamp();
                        pkg = e.getPackageName();
                    }
                }
                Log.e(TAG, "top(events) pkg=" + pkg + " n=" + n);
            }
            // 方案 B：使用统计，取 lastTimeUsed 最大那个
            java.util.List<android.app.usage.UsageStats> list =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now);
            if (list == null) {
                Log.e(TAG, "top(stats) null");
                return;
            }
            android.app.usage.UsageStats best = null;
            for (android.app.usage.UsageStats u : list) {
                if (u.getLastTimeUsed() > 0 && (best == null || u.getLastTimeUsed() > best.getLastTimeUsed())) {
                    best = u;
                }
            }
            Log.e(TAG, "top(stats) size=" + list.size() + " best="
                    + (best == null ? "null" : best.getPackageName() + " ago=" + (now - best.getLastTimeUsed()) + "ms"));
        } catch (Throwable t) {
            Log.e(TAG, "top fail: " + t);
        }
    }

    /** 调试：读取 files/keys.txt 里的候选 key，遍历全部子服务扫描一遍。 */
    private void doScan() {
        try {
            File kf = new File(baseDir, "keys.txt");
            if (!kf.exists()) {
                copyAsset("keys.txt", kf);
            }
            java.util.List<String> ks = new java.util.ArrayList<>();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(kf));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.length() > 0 && !line.startsWith("#")) {
                        int p = line.indexOf('|');
                        ks.add(p > 0 ? line.substring(0, p).trim() : line);
                    }
                }
            } finally {
                br.close();
            }
            File out = new File(baseDir, "scan.txt");
            signals.scanKeys(getPackageName(), ks.toArray(new String[0]), out);
            Log.e(TAG, "scan finished -> " + out);
        } catch (Throwable t) {
            Log.e(TAG, "doScan fail: " + t);
        }
    }

    private void copyAsset(String name, File dst) {
        try {
            File dir = dst.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            AssetManager am = getAssets();
            InputStream in = am.open(name);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            out.close();
            Log.e(TAG, "asset copied: " + name);
        } catch (Throwable t) {
            Log.e(TAG, "copy asset fail " + name + ": " + t);
        }
    }

    private void startObserver() {
        observer = new FileObserver(baseDir.getAbsolutePath(), FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, final String path) {
                if (path == null) {
                    return;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if ("config.json".equals(path)) {
                            Log.e(TAG, "config.json changed");
                            reloadAll();
                        } else {
                            for (ScreenCtx c : screens) {
                                if (path.equals(c.cfg.html)) {
                                    Log.e(TAG, "html changed: " + path);
                                    loadScreen(c);
                                    break;
                                }
                            }
                        }
                    }
                });
            }
        };
        try {
            observer.startWatching();
        } catch (Throwable t) {
            Log.e(TAG, "observer fail: " + t);
        }
    }

    private void startWatchdog() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, Watchdog.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, WATCHDOG_MS, WATCHDOG_MS, pi);
            Log.e(TAG, "watchdog armed");
        } catch (Throwable t) {
            Log.e(TAG, "watchdog fail: " + t);
        }
    }

    private Notification notif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("d", "ov.dash",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(this, "d")
                .setContentTitle("ov.dash")
                .setContentText("HUD running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();
    }
}
