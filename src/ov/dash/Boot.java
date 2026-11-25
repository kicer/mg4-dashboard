package ov.dash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机 / 快速启动 / 重装后拉起浮层服务。 */
public class Boot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Log.e("OVDASH", "boot action=" + i.getAction());
        try {
            c.startForegroundService(new Intent(c, DashService.class));
        } catch (Throwable t) {
            Log.e("OVDASH", "boot start fail", t);
        }
    }
}
