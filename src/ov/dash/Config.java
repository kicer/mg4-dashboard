package ov.dash;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 全局配置：采集节奏 + 每块屏一个 WebView。 */
final class Config {

    private static final String TAG = "OVDASH";

    int refresh = 1000;
    /** 前台应用检查间隔，配多少用多少（不搞 0 特殊值）。 */
    int topCheckMs = 500;
    boolean pushOnChange = true;
    int forcePushMs = 30_000;

    final List<Screen> screens = new ArrayList<>();

    static final class Screen {
        int display = 1;
        String html = "ui-cluster.html";
        boolean enabled = true;
        /** true = 只在 launcherPkg 位于前台时显示，切到别的 app 就隐藏。 */
        boolean onlyOnLauncher = false;
        String launcherPkg = "com.zone.hmi.launcher";
    }

    static Config defaults() {
        Config c = new Config();
        c.screens.add(screen(1, "ui-cluster.html", true));
        c.screens.add(screen(0, "ui-main.html", false));
        return c;
    }

    private static Screen screen(int display, String html, boolean enabled) {
        Screen s = new Screen();
        s.display = display;
        s.html = html;
        s.enabled = enabled;
        return s;
    }

    static Config load(File f) {
        if (f == null || !f.exists()) {
            Config d = defaults();
            d.save(f);
            return d;
        }
        try {
            String text = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            return parse(new JSONObject(text));
        } catch (Throwable t) {
            // 解析失败只回落内存默认值，绝不回写，避免覆盖用户手改的文件
            Log.e(TAG, "config parse fail, file untouched: " + t);
            return defaults();
        }
    }

    static Config parse(JSONObject o) {
        Config c = defaults();
        c.screens.clear();
        c.refresh = o.optInt("refresh", c.refresh);
        c.topCheckMs = o.optInt("topCheckMs", c.topCheckMs);
        c.pushOnChange = o.optBoolean("pushOnChange", c.pushOnChange);
        c.forcePushMs = o.optInt("forcePushMs", c.forcePushMs);
        JSONArray arr = o.optJSONArray("screens");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject so = arr.optJSONObject(i);
                if (so == null) {
                    continue;
                }
                Screen s = new Screen();
                s.display = so.optInt("display", 1);
                s.html = so.optString("html", "ui-cluster.html");
                s.enabled = so.optBoolean("enabled", true);
                s.onlyOnLauncher = so.optBoolean("onlyOnLauncher", false);
                s.launcherPkg = so.optString("launcherPkg", "com.zone.hmi.launcher");
                c.screens.add(s);
            }
        }
        return c;
    }

    void save(File f) {
        if (f == null) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("refresh", refresh);
            o.put("topCheckMs", topCheckMs);
            o.put("pushOnChange", pushOnChange);
            o.put("forcePushMs", forcePushMs);
            JSONArray arr = new JSONArray();
            for (Screen s : screens) {
                JSONObject so = new JSONObject();
                so.put("display", s.display);
                so.put("html", s.html);
                so.put("enabled", s.enabled);
                if (s.onlyOnLauncher) {
                    so.put("onlyOnLauncher", true);
                    so.put("launcherPkg", s.launcherPkg);
                }
                arr.put(so);
            }
            o.put("screens", arr);
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(o.toString(2).getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) {
            Log.e(TAG, "config save fail: " + t);
        }
    }

    Screen forDisplay(int id) {
        for (Screen s : screens) {
            if (s.display == id && s.enabled) {
                return s;
            }
        }
        return null;
    }

    private Config() {
    }
}
