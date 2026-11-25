package ov.dash;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 前台应用监听。只关心 TYPE_WINDOW_STATE_CHANGED 的包名，
 * 不读窗口内容（canRetrieveWindowContent=false）。
 *
 * 作用：替代 UsageStatsManager 轮询，切应用瞬间就能知道。
 */
public class TopWatcher extends AccessibilityService {

    private static final String TAG = "OVDASH";

    /** 当前前台包名，DashService 直接读这个字段。 */
    static volatile String topPkg;
    /** 无障碍服务是否已连上。false 时 DashService 退回轮询。 */
    static volatile boolean connected;
    /** 事件到达时的回调，由 DashService 注册。 */
    static volatile Runnable onChange;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        Log.e(TAG, "a11y connected");
        fire();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        Log.e(TAG, "a11y unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent ev) {
        if (ev == null || ev.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence cs = ev.getPackageName();
        if (cs == null) {
            return;
        }
        String p = cs.toString();
        // 过滤自己，否则打开 Main 会被误判成离开桌面
        if (getPackageName().equals(p)) {
            return;
        }
        if (!p.equals(topPkg)) {
            topPkg = p;
            Log.e(TAG, "a11y top -> " + p + " / " + ev.getClassName());
        }
        fire();
    }

    private void fire() {
        Runnable r = onChange;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable t) {
                Log.e(TAG, "a11y fire fail: " + t);
            }
        }
    }

    @Override
    public void onInterrupt() {
        // 不需要打断处理
    }
}
