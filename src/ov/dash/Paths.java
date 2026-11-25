package ov.dash;

import android.content.Context;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 页面根目录和外部扫描目录的统一定义。 */
final class Paths {

    /** 页面根目录：优先 Android/media/<pkg>，公共可见，adb push 不需 chmod。 */
    static File root(Context c) {
        File[] all = c.getExternalMediaDirs();
        if (all != null) {
            for (File d : all) {
                if (d != null) {
                    return d;
                }
            }
        }
        File f = c.getExternalFilesDir(null);
        return f != null ? f : c.getFilesDir();
    }

    /** 允许用户挑 html 的目录，排序即界面里的分组顺序。 */
    static File[] scanDirs(Context c) {
        List<File> out = new ArrayList<>();
        out.add(new File("/sdcard/Download"));
        File root = root(c);
        out.add(root);

        // U 盘 / SD 卡：卷会随时插拔，每次重新枚举
        try {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                for (StorageVolume v : sm.getStorageVolumes()) {
                    File dir = v.getDirectory();
                    if (dir == null || dir.getAbsolutePath().startsWith("/storage/emulated")) {
                        continue;                 // 内置存储，Download 已覆盖
                    }
                    if (dir.equals(root) || out.contains(dir)) {
                        continue;
                    }
                    out.add(dir);
                }
            }
        } catch (Throwable ignore) {
            // 拿不到卷列表不影响主流程
        }
        return out.toArray(new File[0]);
    }

    /** 目录在界面里的显示名。 */
    static String label(Context c, File dir) {
        String p = dir.getAbsolutePath();
        if (dir.equals(root(c))) {
            return "共享目录";
        }
        if (p.endsWith("/Download")) {
            return "Download";
        }
        if (p.startsWith("/storage/")) {
            return "U 盘（" + dir.getName() + "）";
        }
        return dir.getName();
    }
}
