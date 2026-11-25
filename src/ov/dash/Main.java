package ov.dash;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/**
 * 设置界面。就是一个 WebView 跑 assets/ui-settings.html，
 * 页面通过 window.dash 读写 config.json，不需要 adb。
 */
public class Main extends Activity {

    private static final String TAG = "OVDASH";
    /** 设置页是否在前台。Service 靠它决定主屏浮层让不让开。 */
    static volatile boolean visible;
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startForegroundService(new Intent(this, DashService.class));

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "dash");
        web.loadUrl("file:///android_asset/ui-settings.html");
        setContentView(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        ping();
    }

    @Override
    protected void onPause() {
        visible = false;
        ping();
        super.onPause();
    }

    /** 告诉服务重新判定浮层显隐，不依赖无障碍事件是否及时。 */
    private void ping() {
        sendBroadcast(new Intent(DashService.ACTION_CHECK).setPackage(getPackageName()));
    }

    @Override
    protected void onDestroy() {
        // 设置页是独立 WebView，Activity 退出就销毁，不拖内存
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** JS 接口。被 WebView 的 JS 线程调用，同步 IO 都是小文件。 */
    private class Bridge {

        /** 环境 + 当前配置 + 可选页面，一次给全。 */
        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("root", Paths.root(Main.this).getAbsolutePath());
                o.put("ip", localIp());
                o.put("a11y", a11yOn());
                o.put("config", readConfigText());
                o.put("pages", pagesJson());
                return o.toString();
            } catch (Throwable t) {
                Log.e(TAG, "info fail: " + t);
                return "{\"error\":\"" + t + "\"}";
            }
        }

        /** 关闭设置页：finish 掉 Activity，浮层由 onPause 的 ping 叫回来。 */
        @JavascriptInterface
        public void close() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }

        /** 存盘并让服务重载。返回空串表示成功，否则是错误信息。 */
        @JavascriptInterface
        public String save(String json) {
            try {
                File f = new File(Paths.root(Main.this), "config.json");
                Config.parse(new JSONObject(json)).save(f);
                sendBroadcast(new Intent(DashService.ACTION_RELOAD).setPackage(getPackageName()));
                Log.e(TAG, "settings saved -> " + f);
                return "";
            } catch (Throwable t) {
                Log.e(TAG, "settings save fail: " + t);
                return String.valueOf(t);
            }
        }
    }

    private String readConfigText() {
        File f = new File(Paths.root(this), "config.json");
        if (!f.exists()) {
            Config.defaults().save(f);
        }
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            return new String(buf, 0, Math.max(n, 0), "UTF-8");
        } catch (Throwable t) {
            return "{}";
        }
    }

    /** 两个目录下所有 html，按目录分组靠 dir 字段区分。 */
    private String pagesJson() {
        JSONArray arr = new JSONArray();
        for (File dir : Paths.scanDirs(this)) {
            List<File> found = new ArrayList<>();
            collect(dir, found, 2);
            Collections.sort(found, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getAbsolutePath().compareTo(b.getAbsolutePath());
                }
            });
            String base = dir.getAbsolutePath();
            for (File f : found) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("path", f.getAbsolutePath());
                    o.put("name", f.getAbsolutePath().substring(base.length() + 1));
                    o.put("dir", base.contains("/Download") ? "Download" : "共享目录");
                    o.put("size", f.length());
                    arr.put(o);
                } catch (Throwable ignore) {
                    // 单个文件出错不影响整个列表
                }
            }
        }
        return arr.toString();
    }

    /** 递归最多 maxDepth 层，跳过隐藏目录。 */
    private void collect(File dir, List<File> out, int maxDepth) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] fs;
        try {
            fs = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            String name = f.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                if (maxDepth > 0) {
                    collect(f, out, maxDepth - 1);
                }
            } else {
                String n = name.toLowerCase();
                if (n.endsWith(".html") || n.endsWith(".htm")) {
                    out.add(f);
                }
            }
        }
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignore) {
            // 没网时返回空串
        }
        return "";
    }

    private boolean a11yOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) {
                return false;
            }
            // 系统里存的是短名 ov.dash/.TopWatcher25，dumpsys 才展开成全名
            String full = getPackageName() + "/" + TopWatcher25.class.getName();
            String brief = getPackageName() + "/." + TopWatcher25.class.getSimpleName();
            return s.contains(full) || s.contains(brief);
        } catch (Throwable t) {
            return false;
        }
    }
}
