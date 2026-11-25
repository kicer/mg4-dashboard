package ov.dash;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** 启动入口。浮层由 DashService 管理。 */
public class Main extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startForegroundService(new Intent(this, DashService.class));

        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setPadding(48, 64, 48, 48);
        tv.setText("ov.dash 已启动\n\n"
                + "浮层在 display 1（副屏），仅 D 档地图模式可见\n"
                + "（P 档 / D 档信息流由 QNX 渲染，Android 无法覆盖）\n\n"
                + "调整位置：\n"
                + "adb shell am broadcast -a ov.dash.config \\\n"
                + "  --ei x 60 --ei y 60 --ei w 560 --ei h 130\n\n"
                + "切换内容：\n"
                + "  --es mode soc|speed|range|odo|temp|volt|socv\n\n"
                + "停止：\n"
                + "adb shell am broadcast -a ov.dash.stop");
        setContentView(tv);
    }
}
