package ov.dash;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
 * 文件布局:
 *   {externalFilesDir}/config.json       全局配置
 *   {externalFilesDir}/ui-cluster.html   仪表屏 UI
 *   {externalFilesDir}/ui-main.html      中控 UI（默认关闭）
 *   {externalFilesDir}/...               任意 png/css/js，相对路径引用
 */
public class DashService extends Service {

    private static final String TAG = "OVDASH";
    static final String ACTION_STOP = "ov.dash.stop";
    static final String ACTION_RELOAD = "ov.dash.reload";
    static final String ACTION_PROBE = "ov.dash.probe";
    static final String ACTION_SCAN = "ov.dash.scan";
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
        WebView web;
        WindowManager wm;
        String lastJson = "";
        long lastPush;
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
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, notif());

        baseDir = getExternalFilesDir(null);
        cfgFile = new File(baseDir, "config.json");
        cfg = Config.load(cfgFile);
        Log.e(TAG, "config loaded: refresh=" + cfg.refresh + " screens=" + cfg.screens.size());

        copyMissingAssets();
        boolean ok = signals.bind(getPackageName());
        Log.e(TAG, "signals bind=" + ok);
        signals.loadExtra(baseDir);

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

        Context ctx = createDisplayContext(d);
        ScreenCtx c = new ScreenCtx();
        c.cfg = s;
        c.wm = (WindowManager) ctx.getSystemService(WINDOW_SERVICE);
        c.web = new WebView(ctx);
        // 排查用：确认这块屏的 WebView 到底拿到了什么 density，副屏应该是 1.0
        Log.e(TAG, "display=" + s.display + " density="
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

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            c.wm.addView(c.web, lp);
        } catch (Throwable t) {
            Log.e(TAG, "addView fail display=" + s.display + ": " + t);
            c.web.destroy();
            return;
        }
        loadScreen(c);
        screens.add(c);
        // 副屏可能晚于 buildScreens 就绪，这里必须同步一次，否则采集被 active 短路
        active = !screens.isEmpty();
        Log.e(TAG, "screen created display=" + s.display + " html=" + s.html + " active=" + active);
    }

    /** file:// 加载，同目录相对路径的图片/CSS/JS 自动可用。 */
    private void loadScreen(ScreenCtx c) {
        File f = new File(baseDir, c.cfg.html);
        if (!f.exists()) {
            copyAsset(c.cfg.html, f);
        }
        String url = Uri.fromFile(f).toString();
        Log.e(TAG, "load screen display=" + c.cfg.display + " url=" + url + " exists=" + f.exists());
        c.web.loadUrl(url);
    }

    private void destroyAll() {
        for (ScreenCtx c : screens) {
            try {
                if (c.wm != null && c.web != null) {
                    c.wm.removeView(c.web);
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
        long now = System.currentTimeMillis();
        for (ScreenCtx c : screens) {
            if (c.web == null) {
                continue;
            }
            if (cfg.pushOnChange && json.equals(c.lastJson)
                    && now - c.lastPush < cfg.forcePushMs) {
                continue;
            }
            try {
                c.web.evaluateJavascript("window.onData&&window.onData(" + json + ")", null);
                c.lastJson = json;
                c.lastPush = now;
            } catch (Throwable t) {
                Log.e(TAG, "distribute fail display=" + c.cfg.display + ": " + t);
            }
        }
    }

    // ---------------- 资源 ----------------

    private void copyMissingAssets() {
        for (Config.Screen s : cfg.screens) {
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
