package ov.dash;

import android.content.Context;

import java.io.File;

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
        return new File[]{new File("/sdcard/Download"), root(c)};
    }
}
