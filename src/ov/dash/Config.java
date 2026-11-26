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

    final List<Screen> screens = new ArrayList<>();

    static final class Screen {
        int display = 1;
        String html = "ui-cluster.html";
        /** 给 HTML 用的槽位标识，不写就从 html 文件名去后缀。 */
        String name;
        /** 窗口高度（物理像素），0 = 全屏。用于顶部条这类局部 overlay。 */
        int height = 0;
        boolean enabled = true;
        /** true = 只在 launcherPkg 位于前台时显示，切到别的 app 就隐藏。 */
        boolean onlyOnDesktop = false;
        String launcherPkg = "com.zone.hmi.launcher";
    }

    static Config defaults() {
        Config c = new Config();
        // 双屏默认都开：主屏只在桌面显示，副屏常显
        Screen main = screen(0, "ui-main.html", true);
        main.onlyOnDesktop = true;
        main.launcherPkg = "com.zone.hmi.launcher";
        c.screens.add(main);
        c.screens.add(screen(1, "ui-cluster.html", true));
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
                s.name = so.has("name") ? so.optString("name", null) : null;
                s.height = so.optInt("height", 0);
                s.enabled = so.optBoolean("enabled", true);
                s.onlyOnDesktop = so.optBoolean("onlyOnDesktop", false);
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
            JSONArray arr = new JSONArray();
            for (Screen s : screens) {
                JSONObject so = new JSONObject();
                so.put("display", s.display);
                so.put("html", s.html);
                if (s.name != null && !s.name.isEmpty()) {
                    so.put("name", s.name);
                }
                if (s.height > 0) {
                    so.put("height", s.height);
                }
                so.put("enabled", s.enabled);
                if (s.onlyOnDesktop) {
                    so.put("onlyOnDesktop", true);
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
