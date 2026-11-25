package ov.dash;

import android.location.Location;
import android.location.LocationManager;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 信号采集器：把 zone VehicleServer 的所有可读信号打成 JSON 交给前端。
 * 协议逆向自 com.yg.clusterwall.ZoneBinder / CardataClient。
 */
final class Signals {

    private static final String TAG = "OVDASH";

    static final String DESC_CORE = "com.zone.api.coreservice.ICoreServiceManager";
    static final String DESC_POOL = "com.zone.api.vehicle.IBinderPool";
    static final String DESC_BMS = "com.zone.api.vehicle.function.bms.IBmsService";
    static final String DESC_COMMON = "com.zone.api.vehicle.function.vehicleCommon.IVehicleCommonService";
    static final String DESC_HVAC = "com.zone.api.vehicle.function.hvac.IHvacService";
    static final String DESC_LIGHT = "com.zone.api.vehicle.function.light.ILightService";
    static final String DESC_WINDOW = "com.zone.api.vehicle.function.window.IWindowService";

    static final int IDX_COMMON = 3;
    static final int IDX_HVAC = 5;
    static final int IDX_LIGHT = 6;
    static final int IDX_WINDOW = 8;
    static final int IDX_BMS = 10;

    private static final int TX_GET_POOL = 16;
    private static final int TX_QUERY = 1;
    private static final int TX_READ = 4;

    private static final String[] ALT_KEYS = {
            "ALTITUDE", "GPS_ALTITUDE", "VEHICLE_ALTITUDE", "ORG_ALTITUDE",
            "IPK_ALTITUDE", "CAR_ALTITUDE", "ALT", "ELEVATION",
    };

    /** key -> {服务名, 原始信号名, 小数位}，输出给前端的字段名就是 key。 */
    static final String[][] MAP = {
            {"soc",       "bms",    "BMS_SOC_PERCENT", "1"},
            {"soh",       "bms",    "BMS_BATTERY_SOC_ORG", "1"},
            {"volt",      "bms",    "BMS_DISP_PACK_VOL_ORG", "1"},
            {"curr",      "bms",    "BMS_DISP_PACK_CURRENT_ORG", "1"},
            {"range",     "bms",    "RANGE", "0"},
            {"power",     "bms",    "CHARGE_POWER", "1"},
            {"heat",      "bms",    "BMS_HEAT", "0"},
            {"warn",      "bms",    "BMS_WRNNG_INFO", "0"},
            {"chargeDoor","bms",    "CHARGE_DOOR", "0"},
            {"acVolt",    "bms",    "AC_VOLTAGE", "1"},
            {"acCurr",    "bms",    "AC_CURRENT", "1"},
            {"energyTotal","bms",   "BMS_TOTAL_CONSMP_AFT_START", "2"},
            {"energyDrive","bms",   "BMS_ACC_CONSMP_AFT_START", "2"},
            {"energyHvac","bms",    "BMS_HVAC_CONSMP_AFT_START", "2"},
            {"energyRegen","bms",   "BMS_REGEN_AFT_START", "2"},

            {"odo",       "common", "ODO_DISP", "0"},
            {"trip1",     "common", "IPK_TRIPMETER_TRIP1", "1"},
            {"trip2",     "common", "IPK_TRIPMETER_TRIP2", "1"},
            {"aec1",      "common", "IPK_AVERAGE_PC_AEC1", "1"},
            {"aec2",      "common", "IPK_AVERAGE_PC_AEC2", "1"},
            {"aspd1",     "common", "IPK_AVERAGE_SPEED1", "0"},
            {"aspd2",     "common", "IPK_AVERAGE_SPEED2", "0"},
            {"dtime1",    "common", "IPK_DRIVING_TIME1", "0"},
            {"dtime2",    "common", "IPK_DRIVING_TIME2", "0"},
            {"gear",      "common", "ORG_GEAR", "0"},
            {"speed",     "common", "SPEED", "0"},
            {"pwrStatus", "common", "POWER_STATUS", "0"},
            {"tire_fl",   "common", "FL_TIRE_PRESSURE", "0"},
            {"tire_fr",   "common", "FR_TIRE_PRESSURE", "0"},
            {"tire_rl",   "common", "RL_TIRE_PRESSURE", "0"},
            {"tire_rr",   "common", "RR_TIRE_PRESSURE", "0"},

            {"intemp",    "hvac",   "IACINCARTEM", "1"},
            {"outtemp",   "hvac",   "OUTSIDE_TEP", "1"},

            {"dayLight",  "light",  "DAY_LIGHT", "0"},
            {"dipped",    "light",  "DIPPED_BEAM_LIGHT_ON", "0"},
            {"mainBeam",  "light",  "MAIN_BEAM_LIGHT_ON", "0"},
            {"fog",       "light",  "FOG_LIGHT_STATUS", "0"},
            {"turnLamp",  "light",  "DIRECTION_INDICATOR_LAMP", "0"},
            {"sideLight", "light",  "VEHICLE_SIDE_LIGHT_STS", "0"},
            {"turnLeft",  "light",  "LIGHT_EL_LDIR_CONTRL_ON", "0"},
            {"turnRight", "light",  "LIGHT_EL_RDIR_CONTRL_ON", "0"},
    };

    private IBinder bms;
    private IBinder common;
    private IBinder hvac;
    private IBinder light;
    private IBinder window;

    boolean bind(String pkg) {
        IBinder core = core();
        IBinder pool = pool(core, pkg);
        if (pool == null) {
            Log.e(TAG, "pool null");
            return false;
        }
        common = query(pool, IDX_COMMON, DESC_COMMON);
        hvac = query(pool, IDX_HVAC, DESC_HVAC);
        light = query(pool, IDX_LIGHT, DESC_LIGHT);
        window = query(pool, IDX_WINDOW, DESC_WINDOW);
        bms = query(pool, IDX_BMS, DESC_BMS);
        Log.e(TAG, "signals bound bms=" + (bms != null) + " common=" + (common != null)
                + " hvac=" + (hvac != null) + " light=" + (light != null));
        return bms != null;
    }

    /** 采集全部信号，返回 JSON。取不到的值不出现在 JSON 里。 */
    JSONObject collect(LocationManager lm) {
        JSONObject o = new JSONObject();
        for (String[] m : MAP) {
            try {
                Object v = read(svc(m[1]), desc(m[1]), m[2]);
                if (v == null) {
                    continue;
                }
                int dp = Integer.parseInt(m[3]);
                o.put(m[0], round(f(v), dp));
            } catch (Throwable ignore) {
                // 单个信号失败不影响整体
            }
        }
        try {
            float alt = altitude(lm);
            if (!Float.isNaN(alt)) {
                o.put("alt", round(alt, 0));
            }
        } catch (Throwable ignore) {
            // GPS 不可用
        }
        try {
            o.put("_ts", System.currentTimeMillis());
        } catch (Throwable ignore) {
            // 不可能失败
        }
        return o;
    }

    private double round(float f, int dp) {
        double p = Math.pow(10, dp);
        return Math.round(f * p) / p;
    }

    private IBinder svc(String name) {
        switch (name) {
            case "bms":
                return bms;
            case "common":
                return common;
            case "hvac":
                return hvac;
            case "light":
                return light;
            case "window":
                return window;
            default:
                return null;
        }
    }

    private String desc(String name) {
        switch (name) {
            case "bms":
                return DESC_BMS;
            case "common":
                return DESC_COMMON;
            case "hvac":
                return DESC_HVAC;
            case "light":
                return DESC_LIGHT;
            case "window":
                return DESC_WINDOW;
            default:
                return DESC_COMMON;
        }
    }

    /** 先试 VHAL 的 8 个 key，取不到再退回 GPS。 */
    float altitude(LocationManager lm) {
        for (String k : ALT_KEYS) {
            float f = f(read(common, DESC_COMMON, k));
            if (!Float.isNaN(f) && f != 0f) {
                return f;
            }
        }
        if (lm == null) {
            return Float.NaN;
        }
        try {
            Location best = null;
            for (String p : new String[]{"gps", "network"}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && l.hasAltitude() && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            return best != null ? (float) best.getAltitude() : Float.NaN;
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    // ---------------- binder ----------------

    private static IBinder core() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "service.coreservicemanagerservice");
        } catch (Throwable t) {
            return null;
        }
    }

    private static IBinder pool(IBinder core, String pkg) {
        if (core == null) {
            return null;
        }
        android.os.Parcel d = android.os.Parcel.obtain();
        android.os.Parcel r = android.os.Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC_CORE);
            d.writeString(pkg);
            d.writeString("VehicleServerService");
            d.writeString("");
            core.transact(TX_GET_POOL, d, r, 0);
            r.readException();
            return r.readStrongBinder();
        } catch (Throwable t) {
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static IBinder query(IBinder pool, int idx, String desc) {
        if (pool == null) {
            return null;
        }
        android.os.Parcel d = android.os.Parcel.obtain();
        android.os.Parcel r = android.os.Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC_POOL);
            d.writeInt(idx);
            pool.transact(TX_QUERY, d, r, 0);
            r.readException();
            return r.readStrongBinder();
        } catch (Throwable t) {
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    static Object read(IBinder svc, String desc, String key) {
        if (svc == null) {
            return null;
        }
        android.os.Parcel d = android.os.Parcel.obtain();
        android.os.Parcel r = android.os.Parcel.obtain();
        try {
            d.writeInterfaceToken(desc);
            d.writeString(key);
            d.writeInt(1);
            d.writeInt(2000);
            d.writeString(null);
            d.writeString("java.lang.Float");
            d.writeValue(Float.valueOf(0f));
            d.writeValue(null);
            svc.transact(TX_READ, d, r, 0);
            r.readException();
            int ret = r.readInt();
            int has = r.readInt();
            if (ret != 2000 || has == 0) {
                return null;
            }
            r.readInt();
            r.readString();
            r.readString();
            return r.readValue(Signals.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** Number -> float，非数值返回 NaN。 */
    static float f(Object o) {
        return o instanceof Number ? ((Number) o).floatValue() : Float.NaN;
    }

    private Signals() {
    }

    static Signals create() {
        return new Signals();
    }
}
