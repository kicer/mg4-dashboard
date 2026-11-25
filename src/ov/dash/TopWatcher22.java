package ov.dash;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * 前台应用监听。只关心 TYPE_WINDOW_STATE_CHANGED 的包名，
 * 不读窗口内容（canRetrieveWindowContent=false）。
 *
 * 作用：替代 UsageStatsManager 轮询，切应用瞬间就能知道。
 */
public class TopWatcher22 extends AccessibilityService {

    private static final String TAG = "OVDASH";

    /** 当前前台包名，DashService 直接读这个字段。 */
    static volatile String topPkg;
    /** 最顶层的应用窗口包名（含分屏/透明 Activity 这类 ACTIVITY_RESUMED 看不出来的）。 */
    static volatile String topAppPkg;
    /** 是否有非桌面的应用窗口可见。仅作为数据源交给 HTML 自己判断。 */
    static volatile boolean hasAppWindow;
    /** 无障碍服务是否已连上。false 时 DashService 退回轮询。 */
    static volatile boolean connected;
    /** 事件到达时的回调，由 DashService 注册。 */
    static volatile Runnable onChange;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        Log.e(TAG, "a11y connected");
        scanWindows();
        fire();
    }

    /**
     * 看最顶层的 TYPE_APPLICATION 窗口是哪个 app。
     * 多媒体小窗、分屏、透明 Activity 这些场景里 ACTIVITY_RESUMED 会停在桌面，
     * 只能靠窗口层级判断。
     */
    private void scanWindows() {
        try {
            java.util.List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) {
                return;
            }
            StringBuilder sb = new StringBuilder("wins(" + wins.size() + ")：");
            String top = null;
            int bestLayer = -1;
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo root = w.getRoot();
                String pkg = root == null ? "null-root" : String.valueOf(root.getPackageName());
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    sb.append(" APP[").append(pkg).append(" L=").append(w.getLayer()).append(']');
                    if (root != null && w.getLayer() > bestLayer) {
                        bestLayer = w.getLayer();
                        top = pkg;
                    }
                }
            }
            Log.e(TAG, sb.toString());
            topAppPkg = top;
            hasAppWindow = top != null && !top.equals("com.zone.hmi.launcher");
            Log.e(TAG, "wins -> top=" + top + " hasAppWindow=" + hasAppWindow);
        } catch (Throwable t) {
            Log.e(TAG, "scanWindows fail: " + t);
        }
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
        scanWindows();
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
