package ov.dash;

import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
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
    static final String DESC_ADS = "com.zone.api.vehicle.function.ads.IAdsService";
    static final String DESC_AIR = "com.zone.api.vehicle.function.airoutlet.IAirOutletService";
    static final String DESC_BRAKE = "com.zone.api.vehicle.function.brake.IBrakeService";
    static final String DESC_DOOR = "com.zone.api.vehicle.function.doorlock.IDoorLockService";
    static final String DESC_MIRROR = "com.zone.api.vehicle.function.rearmirror.IRearMirrorService";
    static final String DESC_SEAT = "com.zone.api.vehicle.function.seat.ISeatService";
    static final String DESC_HUD = "com.zone.api.vehicle.function.hud.IHudService";
    static final String DESC_STEER = "com.zone.api.vehicle.function.steering.ISteeringService";

    static final int IDX_COMMON = 3;
    static final int IDX_HVAC = 5;
    static final int IDX_LIGHT = 6;
    static final int IDX_WINDOW = 8;
    static final int IDX_BMS = 10;
    static final int IDX_ADS = 0;
    static final int IDX_AIR = 1;
    static final int IDX_BRAKE = 2;
    static final int IDX_DOOR = 4;
    static final int IDX_MIRROR = 7;
    static final int IDX_SEAT = 9;
    static final int IDX_HUD = 11;
    static final int IDX_STEER = 12;

    private static final int TX_GET_POOL = 16;
    private static final int TX_QUERY = 1;
    private static final int TX_READ = 4;

    private static final String[] ALT_KEYS = {
            "ALTITUDE", "GPS_ALTITUDE", "VEHICLE_ALTITUDE", "ORG_ALTITUDE",
            "IPK_ALTITUDE", "CAR_ALTITUDE", "ALT", "ELEVATION",
    };

    /** key -> {服务名, 原始信号名, 小数位}，输出给前端的字段名就是 key。 */

    private IBinder bms;
    private IBinder common;
    private IBinder hvac;
    private IBinder light;
    private IBinder window;
    private IBinder ads, air, brake, door, mirror, seat, hud, steer;

    /** keys.txt 里的动态字段：原始 key 名与所属服务。 */
    private String[] extraKeys = new String[0];
    private String[] extraSvcs = new String[0];

    /** 服务端推送过来的最新值，回调线程写、采集线程读。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 保住监听器引用，否则会被 GC 回收导致回调断掉。 */
    private final java.util.List<Binder> listeners = new java.util.ArrayList<>();
    private volatile boolean subscribed;
    /** 调试用：服务端推送回调次数。 */
    static final java.util.concurrent.atomic.AtomicLong pushCount =
            new java.util.concurrent.atomic.AtomicLong();

    private static final String DESC_LISTENER = "com.zone.api.vehicle.common.listener.IDataChangeListener";
    private static final int TX_REGISTER = 1;
    private static final int TX_UNREGISTER = 2;
    private static final int TX_ON_DATA_CHANGED = 1;

    /** 服务端数据变化回调。oneway 调用，不阻塞服务端。 */
    private final class DataListener extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != TX_ON_DATA_CHANGED) {
                return false;
            }
            try {
                data.enforceInterface(DESC_LISTENER);
                String flag = data.readString();
                if (data.readInt() != 0) {
                    data.readInt();                 // mCode
                    data.readString();              // mMessage
                    data.readString();              // className
                    Object val = data.readValue(null);
                    data.readValue(null);           // mExtra
                    if (flag != null && val != null) {
                        cache.put(flag, val);
                        pushCount.incrementAndGet();
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "onDataChanged fail: " + t);
            }
            return true;
        }
    }

    /** keys.txt 的动态字段，按服务分组订阅。 */
    boolean subscribeAll() {
        java.util.Map<String, java.util.ArrayList<String>> bySvc = new java.util.HashMap<>();
        for (int i = 0; i < extraKeys.length; i++) {
            addFlag(bySvc, extraSvcs[i], extraKeys[i]);
        }
        boolean any = false;
        for (java.util.Map.Entry<String, java.util.ArrayList<String>> e : bySvc.entrySet()) {
            IBinder s = svc(e.getKey());
            if (s == null) {
                continue;
            }
            try {
                Binder l = new DataListener();
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(desc(e.getKey()));
                    d.writeStrongBinder(l);
                    d.writeStringList(e.getValue());
                    s.transact(TX_REGISTER, d, r, 0);
                    r.readException();
                } finally {
                    d.recycle();
                    r.recycle();
                }
                listeners.add(l);
                any = true;
                Log.e(TAG, "subscribed " + e.getKey() + " n=" + e.getValue().size());
            } catch (Throwable t) {
                Log.e(TAG, "subscribe " + e.getKey() + " fail: " + t);
            }
        }
        subscribed = any;
        return any;
    }

    private static void addFlag(java.util.Map<String, java.util.ArrayList<String>> map,
                                String svcName, String key) {
        if (svcName == null || key == null) {
            return;
        }
        java.util.ArrayList<String> l = map.get(svcName);
        if (l == null) {
            l = new java.util.ArrayList<>();
            map.put(svcName, l);
        }
        if (!l.contains(key)) {
            l.add(key);
        }
    }

    /** 订阅后先全量读一遍填缓存，否则服务端不推的 key 永远是空的。 */
    void primeCache() {
        int n = 0;
        for (int i = 0; i < extraKeys.length; i++) {
            IBinder b = svc(extraSvcs[i]);
            if (b == null) {
                continue;
            }
            Object v = read(b, desc(extraSvcs[i]), extraKeys[i]);
            if (v != null) {
                cache.put(extraKeys[i], v);
                n++;
            }
        }
        Log.e(TAG, "primeCache filled=" + n + " subscribed=" + subscribed);
    }

    /** 优先取推送缓存；已订阅且 primeCache 跑过，就不再回退到同步读。 */
    private Object valueOf(String svcName, String key) {
        if (subscribed) {
            return cache.get(key);
        }
        IBinder b = svc(svcName);
        return b == null ? null : read(b, desc(svcName), key);
    }

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
        ads = query(pool, IDX_ADS, DESC_ADS);
        air = query(pool, IDX_AIR, DESC_AIR);
        brake = query(pool, IDX_BRAKE, DESC_BRAKE);
        door = query(pool, IDX_DOOR, DESC_DOOR);
        mirror = query(pool, IDX_MIRROR, DESC_MIRROR);
        seat = query(pool, IDX_SEAT, DESC_SEAT);
        hud = query(pool, IDX_HUD, DESC_HUD);
        steer = query(pool, IDX_STEER, DESC_STEER);
        Log.e(TAG, "signals bound bms=" + (bms != null) + " common=" + (common != null)
                + " hvac=" + (hvac != null) + " light=" + (light != null));
        return bms != null;
    }

    /** 从 files/keys.txt 加载动态字段，每行 KEY|service。 */
    void loadExtra(java.io.File dir) {
        java.io.File f = new java.io.File(dir, "keys.txt");
        if (!f.exists()) {
            Log.e(TAG, "loadExtra: keys.txt not found");
            return;
        }
        java.util.List<String> ks = new java.util.ArrayList<>();
        java.util.List<String> ss = new java.util.ArrayList<>();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) {
                        continue;
                    }
                    int p = line.indexOf('|');
                    if (p <= 0) {
                        continue;
                    }
                    ks.add(line.substring(0, p).trim());
                    ss.add(line.substring(p + 1).trim());
                }
            } finally {
                br.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "loadExtra fail: " + t);
        }
        extraKeys = ks.toArray(new String[0]);
        extraSvcs = ss.toArray(new String[0]);
        Log.e(TAG, "extra keys loaded=" + extraKeys.length);
    }

    /** 采集全部信号，返回 JSON。取不到的值不出现在 JSON 里。 */
    JSONObject collect(LocationManager lm) {
        JSONObject o = new JSONObject();
        try {
            float alt = altitude(lm);
            if (!Float.isNaN(alt)) {
                o.put("alt", round(alt, 0));
            }
            putGps(o, lm);
        } catch (Throwable ignore) {
            // GPS 不可用
        }
        for (int i = 0; i < extraKeys.length; i++) {
            try {
                Object v = valueOf(extraSvcs[i], extraKeys[i]);
                if (v == null) {
                    continue;
                }
                o.put(extraKeys[i], round(f(v), 2));
            } catch (Throwable ignore) {
                // 单个字段失败不影响其他
            }
        }
        // 媒体信息：优先用车机媒体中心（蓝牙场景只有它有歌名），退化到 MediaSession
        try {
            String mt = MediaCenterReader.title;
            String ma = MediaCenterReader.artist;
            String mal = MediaCenterReader.album;
            if (mt == null || mt.isEmpty()) {
                mt = MediaListener.title;
            }
            if (ma == null || ma.isEmpty()) {
                ma = MediaListener.artist;
            }
            if (mal == null || mal.isEmpty()) {
                mal = MediaListener.album;
            }
            if (mt != null && !mt.isEmpty()) {
                o.put("mediaTitle", mt);
            }
            if (ma != null && !ma.isEmpty()) {
                o.put("mediaArtist", ma);
            }
            if (mal != null && !mal.isEmpty()) {
                o.put("mediaAlbum", mal);
            }
            if (MediaCenterReader.kind != null && !MediaCenterReader.kind.isEmpty()) {
                o.put("mediaSource", MediaCenterReader.kind);
            }
            o.put("mediaPlaying", MediaListener.playing);
        } catch (Throwable ignore) {
            // JSON 构造失败不影响其他字段
        }
        try {
            o.put("_ts", System.currentTimeMillis());
        } catch (Throwable ignore) {
            // 不可能失败
        }
        return o;
    }

    /** 调试：枚举 IBinderPool 全部子服务，打印 descriptor。 */
    void probePool(String pkg) {
        IBinder pool = pool(core(), pkg);
        if (pool == null) {
            Log.e(TAG, "probe: pool null");
            return;
        }
        Log.e(TAG, "probe: scanning idx 0..39");
        for (int i = 0; i < 40; i++) {
            IBinder b = query(pool, i, null);
            if (b == null) {
                continue;
            }
            String desc;
            try {
                desc = b.getInterfaceDescriptor();
            } catch (Throwable t) {
                desc = "err:" + t;
            }
            Log.e(TAG, "probe idx=" + i + " desc=" + desc);
        }
        Log.e(TAG, "probe: done");
    }

    /** 调试：对每个候选 key 遍历全部子服务，找出能读到值的那个。 */
    void scanKeys(String pkg, String[] keys, java.io.File out) {
        IBinder pool = pool(core(), pkg);
        if (pool == null) {
            Log.e(TAG, "scan: pool null");
            return;
        }
        final int N = 13;
        IBinder[] svcs = new IBinder[N];
        String[] descs = new String[N];
        for (int i = 0; i < N; i++) {
            IBinder b = query(pool, i, null);
            svcs[i] = b;
            try {
                descs[i] = b == null ? null : b.getInterfaceDescriptor();
            } catch (Throwable t) {
                descs[i] = null;
            }
        }
        StringBuilder sb = new StringBuilder();
        int hit = 0;
        for (String k : keys) {
            for (int i = 0; i < N; i++) {
                if (svcs[i] == null || descs[i] == null) {
                    continue;
                }
                Object v = read(svcs[i], descs[i], k);
                if (v == null) {
                    continue;
                }
                sb.append(k).append('|').append(i).append('|')
                        .append(shortName(descs[i])).append('|')
                        .append(f(v)).append('\n');
                hit++;
                break;
            }
        }
        Log.e(TAG, "scan done hits=" + hit + " total=" + keys.length);
        java.io.Writer w = null;
        try {
            w = new java.io.FileWriter(out);
            w.write(sb.toString());
        } catch (Throwable t) {
            Log.e(TAG, "scan write fail: " + t);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static String shortName(String desc) {
        if (desc == null) {
            return "?";
        }
        String[] p = desc.split("\\.");
        return p.length >= 2 ? p[p.length - 2] : desc;
    }

    private static void putNum(JSONObject o, String key, Object v) {
        if (!(v instanceof Number)) {
            return;
        }
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return;
        }
        try {
            o.put(key, d);
        } catch (Throwable ignore) {
            // 单个字段失败不影响其他
        }
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
            case "vehicleCommon":
                return common;
            case "hvac":
                return hvac;
            case "light":
                return light;
            case "window":
                return window;
            case "ads":
                return ads;
            case "doorlock":
                return door;
            case "seat":
                return seat;
            case "hud":
                return hud;
            case "steering":
                return steer;
            case "brake":
                return brake;
            case "airoutlet":
                return air;
            case "rearmirror":
                return mirror;
            default:
                return null;
        }
    }

    private String desc(String name) {
        switch (name) {
            case "bms":
                return DESC_BMS;
            case "common":
            case "vehicleCommon":
                return DESC_COMMON;
            case "hvac":
                return DESC_HVAC;
            case "light":
                return DESC_LIGHT;
            case "window":
                return DESC_WINDOW;
            case "ads":
                return DESC_ADS;
            case "doorlock":
                return DESC_DOOR;
            case "seat":
                return DESC_SEAT;
            case "hud":
                return DESC_HUD;
            case "steering":
                return DESC_STEER;
            case "brake":
                return DESC_BRAKE;
            case "airoutlet":
                return DESC_AIR;
            case "rearmirror":
                return DESC_MIRROR;
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

    /** GPS 全量信息。一次 getLastKnownLocation 快照，不持续定位。 */
    private void putGps(JSONObject o, LocationManager lm) {
        if (lm == null) {
            return;
        }
        Location best = null;
        try {
            for (String p : new String[]{"gps", "network"}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
        } catch (Throwable t) {
            return;
        }
        if (best == null) {
            return;
        }
        try {
            o.put("lat", round((float) best.getLatitude(), 6));
            o.put("lon", round((float) best.getLongitude(), 6));
            if (best.hasSpeed()) {
                o.put("gpsSpeed", round(best.getSpeed() * 3.6f, 1));   // m/s → km/h
            }
            if (best.hasBearing()) {
                o.put("bearing", round(best.getBearing(), 0));
            }
            if (best.hasAccuracy()) {
                o.put("hAcc", round(best.getAccuracy(), 0));
            }
            if (best.hasVerticalAccuracy()) {
                o.put("vAcc", round(best.getVerticalAccuracyMeters(), 0));
            }
            // GPS 时间来自卫星，和系统时钟有偏差，可能为负，取绝对值
            long age = (System.currentTimeMillis() - best.getTime()) / 1000;
            o.put("gpsAge", Math.abs(age));
            Bundle ex = best.getExtras();
            if (ex != null) {
                // extras 里的类型不确定，统一按 Number 处理，避免 ClassCastException 把整个 GPS 块拖死
                putNum(o, "sats", ex.get("satellites"));
                putNum(o, "cn0", ex.get("meanCn0"));
                putNum(o, "gpsStatus", ex.get("gpsStatus"));
                putNum(o, "hdop", ex.get("hdop"));
            }
        } catch (Throwable ignore) {
            // GPS 字段失败不影响其他
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
