package com.wheregoes.carha;

import android.content.Context;
import android.hardware.bydauto.ac.BYDAutoAcDevice;
import android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice;
import android.hardware.bydauto.statistic.BYDAutoStatisticDevice;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * Reads every vehicle signal reachable without ADB, via the BYD HAL
 * (BydPermissionContext bypass) plus the standard Android location provider,
 * and packs them into one JSON object for Home Assistant.
 *
 * HAL-first: traction SOC, EV range and odometer come from
 * {@link BYDAutoStatisticDevice}; power/lock/VIN from {@link BYDAutoBodyworkDevice};
 * cabin/outside temp and AC from {@link BYDAutoAcDevice}; position from GPS.
 * Tyre pressure and speed live in the instrument cluster, which not even ADB
 * shell can read, so they are intentionally absent.
 *
 * All reads run on a caller-supplied worker thread (never the main looper) and
 * each device is isolated in its own try/catch so one dead signal cannot blank
 * the rest.
 *
 * DiLink3 answers 0 for the HAL odometer, so odometer, SOH, BMS kWh and the
 * lifetime energy counters are read from autoservice through {@link AdbShell}
 * (self-ADB to localhost:5555). That path is optional: when adbd is closed or
 * not yet authorised the HAL values are posted alone.
 */
class VehicleReader {

    private static final String TAG = "CarHaReader";

    private final Context hal;
    private BYDAutoStatisticDevice statistic;
    private BYDAutoBodyworkDevice bodywork;
    private BYDAutoAcDevice ac;
    private Method getTemprature;
    private final AdbShell adb;
    private long lastAdbReadMs = 0L;
    private JSONObject lastAdb;

    /** autoservice reads: {json key, tx (5=int, 7=float), device, fid}. */
    private static final Object[][] AUTOSERVICE = {
            {"odometer_km", 5, 1014, 1246765072},       // 0.1 km
            {"soh_pct", 5, 1014, 1145045032},
            {"bms_kwh", 7, 1005, 882901008},
            {"total_elec_kwh", 7, 1014, 1032871984},
            {"avg_kwh_100km", 7, 1014, 1246761008},
            {"charge_kwh", 7, 1009, 666894360},
    };
    private static final long ADB_INTERVAL_MS = 5 * 60_000L;
    private static final long ADB_INTERVAL_CHARGING_MS = 60_000L;

    /** One-line human summary of the last read, for the UI. */
    volatile String lastSummary = "(まだ読み取っていません)";

    VehicleReader(Context context) {
        this.hal = new BydPermissionContext(context.getApplicationContext());
        this.adb = new AdbShell(context);
    }

    /**
     * Read everything and return it as a JSON object. Never throws; missing
     * signals are simply omitted.
     */
    JSONObject read(Context context, long ts) {
        JSONObject o = new JSONObject();
        StringBuilder sum = new StringBuilder();
        try {
            o.put("source", "car-ha");
            o.put("ts", ts);
        } catch (Exception ignored) {
        }

        readStatistic(o, sum);
        readAutoservice(o, sum, ts);
        readBodywork(o, sum);
        readClimate(o, sum);
        readLocation(context, o, sum);

        lastSummary = sum.length() == 0 ? "(読み取れた値なし)" : sum.toString().trim();
        return o;
    }

    // --------------------------------------------------------------- statistic

    private void readStatistic(JSONObject o, StringBuilder sum) {
        try {
            if (statistic == null) {
                statistic = BYDAutoStatisticDevice.getInstance(hal);
            }
            if (statistic == null) {
                return;
            }
            double soc = statistic.getElecPercentageValue();
            if (soc > 0 && soc <= 100) {
                int socInt = (int) Math.round(soc);
                o.put("soc", socInt);
                sum.append("SOC ").append(socInt).append("%  ");
            }
            int range = statistic.getEVMileageValue();
            if (range > 0 && range < 2000) {
                o.put("ev_range_km", range);
                sum.append("航続 ").append(range).append("km  ");
            }
            double odo = statistic.getTotalMileageValue();
            if (odo > 0 && !o.has("odometer_km")) {
                o.put("odometer_km", Math.round(odo));
                sum.append("オド ").append(Math.round(odo)).append("km  ");
            }
            double elec = statistic.getTotalElecConValue();
            if (elec > 0) {
                o.put("elec_consumption", elec);
            }
        } catch (Throwable t) {
            Log.d(TAG, "statistic read failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------- autoservice

    /**
     * Self-ADB is slower than the HAL, so it runs every {@link #ADB_INTERVAL_MS}
     * (every minute while charging) and the cached values ride along between.
     */
    private void readAutoservice(JSONObject o, StringBuilder sum, long ts) {
        try {
            boolean charging = lastAdb != null && lastAdb.optDouble("charge_kwh", 0) > 0;
            long interval = charging ? ADB_INTERVAL_CHARGING_MS : ADB_INTERVAL_MS;
            if (lastAdb == null || ts - lastAdbReadMs >= interval) {
                JSONObject fresh = readAutoserviceNow();
                if (fresh != null) {
                    lastAdb = fresh;
                    lastAdbReadMs = ts;
                }
            }
            if (lastAdb == null) {
                return;
            }
            java.util.Iterator<String> it = lastAdb.keys();
            while (it.hasNext()) {
                String k = it.next();
                o.put(k, lastAdb.get(k));
            }
            o.put("adb_ts", lastAdbReadMs);
            if (lastAdb.has("odometer_km")) {
                sum.append("オド ").append(Math.round(lastAdb.getDouble("odometer_km"))).append("km  ");
            }
            if (lastAdb.has("soh_pct")) {
                sum.append("SOH ").append(lastAdb.getInt("soh_pct")).append("%  ");
            }
        } catch (Throwable t) {
            Log.d(TAG, "autoservice read failed: " + t.getMessage());
        }
    }

    private JSONObject readAutoserviceNow() throws Exception {
        StringBuilder cmd = new StringBuilder();
        for (Object[] r : AUTOSERVICE) {
            cmd.append("service call autoservice ").append(r[1])
                    .append(" i32 ").append(r[2]).append(" i32 ").append(r[3]).append(";");
        }
        String out = adb.run(cmd.toString());
        if (out == null) {
            return null;
        }
        // One "Result: Parcel(00000000 xxxxxxxx ...)" line per call, in order.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Parcel\\(([0-9a-f]{8}) ([0-9a-f]{8})").matcher(out);
        JSONObject j = new JSONObject();
        int i = 0;
        while (m.find() && i < AUTOSERVICE.length) {
            Object[] r = AUTOSERVICE[i++];
            if (!"00000000".equals(m.group(1))) {
                continue;
            }
            int raw = (int) Long.parseLong(m.group(2), 16);
            String key = (String) r[0];
            if ((int) r[1] == 7) {
                float f = Float.intBitsToFloat(raw);
                if (f >= 0 && f < 1e7) {
                    j.put(key, Math.round(f * 10) / 10.0);
                }
            } else if (key.equals("odometer_km")) {
                if (raw > 0) {
                    j.put(key, raw / 10.0);
                }
            } else if (raw > 0 && raw <= 100) {
                j.put(key, raw);
            }
        }
        return j.length() == 0 ? null : j;
    }

    // ---------------------------------------------------------------- bodywork

    private void readBodywork(JSONObject o, StringBuilder sum) {
        try {
            if (bodywork == null) {
                bodywork = BYDAutoBodyworkDevice.getInstance(hal);
            }
            if (bodywork == null) {
                return;
            }
            int power = bodywork.getPowerLevel();
            boolean powerOn = power >= BYDAutoBodyworkDevice.BODYWORK_POWER_LEVEL_ON;
            o.put("power_level", power);
            o.put("power_on", powerOn);
            sum.append(powerOn ? "電源ON  " : "電源OFF  ");

            int sysState = bodywork.getAutoSystemState();
            boolean locked = sysState >= BYDAutoBodyworkDevice.BODYWORK_AUTO_SYSTEM_STATE_SET_SECURE
                    && sysState != BYDAutoBodyworkDevice.BODYWORK_AUTO_SYSTEM_STATE_UNDEFINED;
            o.put("locked", locked);
            sum.append(locked ? "施錠  " : "解錠  ");

            String vin = bodywork.getAutoVIN();
            if (vin != null && !vin.isEmpty()) {
                o.put("vin", vin);
            }
        } catch (Throwable t) {
            Log.d(TAG, "bodywork read failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ climate

    private void readClimate(JSONObject o, StringBuilder sum) {
        try {
            if (ac == null) {
                ac = BYDAutoAcDevice.getInstance(hal);
            }
            if (ac == null) {
                return;
            }
            if (getTemprature == null) {
                getTemprature = ac.getClass().getMethod("getTemprature", int.class);
                getTemprature.setAccessible(true);
            }
            int cabin = (int) getTemprature.invoke(ac, 1);
            int outside = (int) getTemprature.invoke(ac, 4);
            if (cabin >= 16 && cabin <= 32) {
                o.put("cabin_temp_c", cabin);
            }
            if (outside > -50 && outside < 80) {
                o.put("outside_temp_c", outside);
                sum.append("外 ").append(outside).append("C  ");
            }
            boolean acOn = ac.getAcStartState() == 1;
            o.put("ac_on", acOn);
            sum.append(acOn ? "AC ON  " : "AC OFF  ");
        } catch (Throwable t) {
            Log.d(TAG, "climate read failed: " + t.getMessage());
        }
    }

    // ----------------------------------------------------------------- location

    private void readLocation(Context context, JSONObject o, StringBuilder sum) {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return;
            }
            // The service's active updates give the freshest fix; last-known is
            // only a fallback for before the first live update arrives.
            Location best = BridgeService.sLastFix;
            String[] providers = {
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
            };
            for (String p : providers) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l == null) {
                        continue;
                    }
                    if (best == null || l.getTime() > best.getTime()) {
                        best = l;
                    }
                } catch (SecurityException se) {
                    Log.d(TAG, "location permission missing for " + p);
                    return;
                } catch (Exception ignored) {
                }
            }
            if (best != null) {
                o.put("lat", best.getLatitude());
                o.put("lon", best.getLongitude());
                o.put("gps_time", best.getTime());
                sum.append("GPS✓  ");
            }
        } catch (Throwable t) {
            Log.d(TAG, "location read failed: " + t.getMessage());
        }
    }
}
