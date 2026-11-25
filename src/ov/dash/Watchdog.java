package ov.dash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 心跳看门狗：服务每 tick 写时间戳，超过阈值没更新就重新拉起。 */
public class Watchdog extends BroadcastReceiver {

    static final String PREF = "cfg";
    static final String KEY_HB = "hb";
    static final long STALE_MS = 180_000L;

    @Override
    public void onReceive(Context c, Intent i) {
        long hb = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_HB, 0L);
        long age = System.currentTimeMillis() - hb;
        Log.e("OVDASH", "watchdog age=" + age);
        if (age > STALE_MS) {
            try {
                c.startForegroundService(new Intent(c, DashService.class));
                Log.e("OVDASH", "watchdog restart service");
            } catch (Throwable t) {
                Log.e("OVDASH", "watchdog start fail", t);
            }
        }
    }
}
