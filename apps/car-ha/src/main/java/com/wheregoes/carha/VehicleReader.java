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

    /**
     * Every autoservice address that answered on the Japanese Dolphin (DiLink3,
     * probe 2026-11-08 against BYDMate's FidMap; the ones that returned -10011
     * are left out). {json key, tx (5=int, 7=float), device, fid, scale, offset}.
     * Values go out under "as"; a few are also mirrored to the legacy top-level
     * keys the HA automations already use, see {@link #LEGACY}.
     */
    private static final Object[][] AUTOSERVICE = {
            {"soc", 7, 1014, 1246777400, 1, 0},
            {"speed", 7, 1013, -1807745016, 1, 0},
            {"mileage", 5, 1014, 1246765072, 0.1, 0},
            {"power", 5, 1012, 339738656, 1, 0},
            {"total_elec_consumption", 7, 1014, 1032871984, 1, 0},
            {"battery_remain_kwh", 7, 1005, 882901008, 1, 0},
            {"voltage12v", 7, 1001, 1128267816, 1, 0},
            {"max_cell_voltage", 5, 1014, 1147142192, 0.001, 0},
            {"min_cell_voltage", 5, 1014, 1147142160, 0.001, 0},
            {"gear", 5, 1011, 555745336, 1, 0},
            {"charge_gun_state", 5, 1009, 876609586, 1, 0},
            {"bms_state", 5, 1009, 876609560, 1, 0},
            {"ac_status", 5, 1000, 1077936144, 1, 0},
            {"ac_temp", 5, 1000, 1077936168, 1, 0},
            {"fan_level", 5, 1000, 1077936156, 1, 0},
            {"ac_circ", 5, 1000, 1077936148, 1, 0},
            {"exterior_temp", 5, 1000, 1077936184, 1, 0},
            {"hood", 5, 1001, 692060188, 1, 0},
            {"seatbelt_fl", 5, 1007, 692060184, 1, 0},
            {"tire_press_fl", 5, 1016, -1728052956, 1, 0},
            {"tire_press_fr", 5, 1016, -1728052952, 1, 0},
            {"tire_press_rl", 5, 1016, -1728052948, 1, 0},
            {"tire_press_rr", 5, 1016, -1728052944, 1, 0},
            {"light_low", 5, 1004, 950009866, 1, 0},
            {"drl", 5, 1004, 1231040528, 1, 0},
            {"turn_signal", 5, 1004, 950009900, 1, 0},
            {"max_bat_temp", 5, 1014, 1148190752, 1, -40},
            {"min_bat_temp", 5, 1014, 1148190736, 1, -40},
            {"power_state", 5, 1023, 315621408, 1, 0},
            {"door_fl", 5, 1001, 692060168, 1, 0},
            {"door_fr", 5, 1001, 692060170, 1, 0},
            {"door_rl", 5, 1001, 692060172, 1, 0},
            {"door_rr", 5, 1001, 692060174, 1, 0},
            {"window_fl", 5, 1001, 947912728, 1, 0},
            {"window_fr", 5, 1001, 1267728400, 1, 0},
            {"window_rl", 5, 1001, 947912736, 1, 0},
            {"window_rr", 5, 1001, 947912752, 1, 0},
            {"window_rrgen3", 5, 1001, 1267728408, 1, 0},
            {"sunroof", 5, 1001, 1101004808, 1, 0},
            {"lock_fl", 5, 1032, 1081081864, 1, 0},
            {"drive_mode", 5, 1006, 555745294, 1, 0},
            {"work_mode", 5, 1006, 874512420, 1, 0},
            {"ac_defrost_front", 5, 1000, 1077936150, 1, 0},
            {"ac_wind_mode", 5, 1000, 1077936152, 1, 0},
            {"ac_ctrl_mode", 5, 1000, 1077936146, 1, 0},
            {"light_side", 5, 1004, 950009864, 1, 0},
            {"light_high", 5, 1004, 950009868, 1, 0},
            {"seatbelt_fr", 5, 1007, 638582811, 1, 0},
            {"seatbelt_rl", 5, 1007, 638582812, 1, 0},
            {"seatbelt_rm", 5, 1007, 638582813, 1, 0},
            {"seatbelt_rr", 5, 1007, 638582814, 1, 0},
            {"light_level", 5, 1043, 315621396, 1, 0},
            {"key_battery_status", 5, 1014, 402653200, 1, 0},
            {"wiper_relay", 5, 1046, 1336934438, 1, 0},
            {"auto_wipers", 5, 1046, 321912862, 1, 0},
            {"insulation_kohm", 5, 1039, 1134559256, 1, 0},
            {"motor_temp_front", 5, 1039, 1154482192, 1, 0},
            {"motor_temp_rear", 5, 1039, 1155530768, 1, 0},
            {"inverter_temp_front", 5, 1039, 1154482184, 1, 0},
            {"inverter_temp_rear", 5, 1039, 1155530760, 1, 0},
            {"hv_voltage", 5, 1009, 1145045000, 1, 0},
            {"hv_current", 7, 1009, 1145045016, 1, 0},
            {"motor_current_front", 7, 1009, 1186988040, 1, 0},
            {"motor_current_rear", 7, 1009, 1186988056, 1, 0},
            {"bms_max_charge_kw", 5, 1014, 877658136, 0.1, 0},
            {"bms_max_discharge_allow_kw", 5, 1014, 877658120, 0.1, 0},
            {"bms_max_discharge_kw", 5, 1014, 1145045048, 1, 0},
            {"motor_rpm_front", 5, 1012, 1141899272, 1, 0},
            {"motor_rpm_rear", 5, 1012, 621805576, 1, 0},
            {"tyre_temp_fl", 5, 1007, 1246797848, 1, 0},
            {"tyre_temp_fr", 5, 1007, 1246797860, 1, 0},
            {"tyre_temp_rl", 5, 1007, 1246797872, 1, 0},
            {"tyre_temp_rr", 5, 1007, 1246797884, 1, 0},
            {"pedal_accel", 5, 1013, 874512392, 1, 0},
            {"pedal_brake", 5, 1013, 874512400, 1, 0},
            {"charger_connect_state", 5, 1009, 89128973, 1, 0},
            {"charge_connect_indicator", 5, 1007, 710934572, 1, 0},
            {"soh", 5, 1014, 1145045032, 1, 0},
            {"lifetime_avg_phm", 7, 1014, 1246761008, 1, 0},
            {"charging_type", 5, 1009, 876609592, 1, 0},
            {"battery_type", 5, 1009, -1728053169, 1, 0},
            {"charging_capacity", 7, 1009, 666894360, 1, 0},
            {"seat_config_vent_lf", 5, 1000, 715132952, 1, 0},
            {"seat_config_heat_lf", 5, 1000, 715132955, 1, 0},
            {"seat_has_driver_vent", 5, 1023, -811597816, 1, 0},
            {"seat_has_driver_heat", 5, 1023, -811597813, 1, 0},
    };
    /** "as" key -> legacy top-level key (kept for the existing HA helpers). */
    private static final String[][] LEGACY = {
            {"mileage", "odometer_km"},
            {"soh", "soh_pct"},
            {"battery_remain_kwh", "bms_kwh"},
            {"total_elec_consumption", "total_elec_kwh"},
            {"lifetime_avg_phm", "avg_kwh_100km"},
            {"charging_capacity", "charge_kwh"},
    };

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
        readAutoservice(o, sum);
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

    /** One self-ADB round trip per poll (~1-4 s for the whole table). */
    private void readAutoservice(JSONObject o, StringBuilder sum) {
        try {
            JSONObject as = readAutoserviceNow();
            if (as == null) {
                return;
            }
            o.put("as", as);
            for (String[] l : LEGACY) {
                if (as.has(l[0])) {
                    o.put(l[1], as.get(l[0]));
                }
            }
            if (as.has("mileage")) {
                sum.append("オド ").append(Math.round(as.getDouble("mileage"))).append("km  ");
            }
            if (as.has("soh")) {
                sum.append("SOH ").append(as.getInt("soh")).append("%  ");
            }
            sum.append("AS ").append(as.length()).append("項目  ");
        } catch (Throwable t) {
            Log.d(TAG, "autoservice read failed: " + t.getMessage());
        }
    }

    /** adbd caps an OPEN payload at 4 KB, so the table goes out in chunks. */
    private static final int CHUNK = 20;

    private JSONObject readAutoserviceNow() throws Exception {
        JSONObject j = new JSONObject();
        for (int start = 0; start < AUTOSERVICE.length; start += CHUNK) {
            int end = Math.min(start + CHUNK, AUTOSERVICE.length);
            StringBuilder cmd = new StringBuilder();
            for (int k = start; k < end; k++) {
                Object[] r = AUTOSERVICE[k];
                cmd.append("service call autoservice ").append(r[1])
                        .append(" i32 ").append(r[2]).append(" i32 ").append(r[3]).append(";");
            }
            String out = adb.run(cmd.toString());
            if (out == null) {
                break;
            }
            parseChunk(out, start, end, j);
        }
        return j.length() == 0 ? null : j;
    }

    private static void parseChunk(String out, int start, int end, JSONObject j) throws Exception {
        // One "Result: Parcel(00000000 xxxxxxxx ...)" line per call, in order.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Parcel\\(([0-9a-f]{8}) ([0-9a-f]{8})").matcher(out);
        int i = start;
        while (m.find() && i < end) {
            Object[] r = AUTOSERVICE[i++];
            if (!"00000000".equals(m.group(1))) {
                continue;
            }
            int raw = (int) Long.parseLong(m.group(2), 16);
            // -10011/-10013/... = no such signal on this car / link error.
            if (raw <= -9900 && raw >= -10100) {
                continue;
            }
            double scale = ((Number) r[4]).doubleValue();
            int offset = ((Number) r[5]).intValue();
            double v;
            if ((int) r[1] == 7) {
                float f = Float.intBitsToFloat(raw);
                if (Float.isNaN(f) || Float.isInfinite(f)) {
                    continue;
                }
                v = f;
            } else {
                v = raw * scale + offset;
            }
            v = Math.round(v * 1000) / 1000.0;
            if (v == Math.rint(v) && Math.abs(v) < 1e9) {
                j.put((String) r[0], (long) v);
            } else {
                j.put((String) r[0], v);
            }
        }
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
