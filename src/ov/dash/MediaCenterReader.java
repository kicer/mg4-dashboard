package ov.dash;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 车机媒体中心数据源。
 *
 * 走车厂私有 AIDL：bind com.zone.service.mediacenter.MediaCenterService，
 * transact(21) 一次返回 播放源 + 歌名 + 歌手 + 专辑 + 时长。
 *
 * 只被动调用（由 MediaListener 的事件触发），自身不轮询。
 */
final class MediaCenterReader {

    private static final String TAG = "OVDASH";
    private static final String PKG = "com.zone.service.mediacenter";
    private static final String CLS = "com.zone.service.mediacenter.MediaCenterService";
    private static final String DESC = "com.zone.api.mediacenter.IMediaService";

    static volatile String title = "";
    static volatile String artist = "";
    static volatile String album = "";
    /** 播放源：bt / radio / local ... */
    static volatile String kind = "";
    static volatile long durationMs;

    private static Context ctx;
    private static volatile IBinder svc;
    private static volatile boolean binding;
    private static long lastBind;

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName cn, IBinder b) {
            svc = b;
            binding = false;
            Log.e(TAG, "mediacenter connected");
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName cn) {
            svc = null;
            binding = false;
        }
    };

    private MediaCenterReader() {
    }

    static void init(Context c) {
        ctx = c.getApplicationContext();
        ensureBound();
    }

    private static void ensureBound() {
        if (ctx == null || svc != null || binding) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBind < 5000) {
            return;
        }
        lastBind = now;
        binding = true;
        try {
            Intent i = new Intent(DESC);
            i.setClassName(PKG, CLS);
            boolean ok = ctx.bindService(i, CONN, Context.BIND_AUTO_CREATE);
            Log.e(TAG, "mediacenter bind ret=" + ok);
            if (!ok) {
                binding = false;
            }
        } catch (Throwable t) {
            binding = false;
            Log.e(TAG, "mediacenter bind fail: " + t);
        }
    }

    /** 拉一次当前媒体信息。只在 MediaListener 的事件回调里调。 */
    static void refresh() {
        ensureBound();
        IBinder b = svc;
        if (b == null) {
            return;
        }
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC);
            b.transact(21, d, r, 0);
            r.readException();
            int count = r.readInt();
            String k = r.readString();
            kind = k == null ? "" : k;
            if (count == 0) {
                title = "";
                artist = "";
                album = "";
                durationMs = 0;
                return;
            }
            List<String> strs = new ArrayList<>();
            long num = 0;
            // 服务端按顺序塞字符串，纯数字的是时长/频率
            while (r.dataPosition() < r.dataSize() && strs.size() < 200) {
                int pos = r.dataPosition();
                try {
                    String s = r.readString();
                    if (s == null || s.isEmpty()) {
                        continue;
                    }
                    if (s.matches("\\d{1,12}")) {
                        long v = Long.parseLong(s);
                        if (v > 1000) {
                            num = v;
                        }
                    } else if (s.matches("[0-9a-fA-F-]{30,}")) {
                        // UUID / 内部 mediaId，不是显示内容
                    } else {
                        strs.add(s);
                    }
                } catch (Throwable t) {
                    // 遇到非字符串字段，跳过 4 字节继续
                    r.setDataPosition(pos);
                    try {
                        int raw = r.readInt();
                        if (raw > 1000) {
                            num = raw;
                        }
                    } catch (Throwable ignore) {
                        break;
                    }
                }
            }
            if (strs.size() > 0) {
                title = strs.get(0);
            }
            if (strs.size() > 1) {
                artist = strs.get(1);
            }
            if (strs.size() > 2) {
                album = strs.get(2);
            }
            durationMs = num;
            Log.e(TAG, "mc -> " + title + " / " + artist + " / " + album
                    + " kind=" + kind + " n=" + strs.size());
        } catch (Throwable t) {
            Log.e(TAG, "mc refresh fail: " + t);
        } finally {
            d.recycle();
            r.recycle();
        }
    }
}
