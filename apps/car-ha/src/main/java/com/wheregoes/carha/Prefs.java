package com.wheregoes.carha;

import android.content.SharedPreferences;

/** Shared-preferences keys and defaults for the whole app. */
final class Prefs {
    static final String NAME = "car_ha_prefs";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_URL = "webhook_url";
    static final String KEY_WIFI_ONLY = "wifi_only";
    static final String KEY_CFG_VERSION = "cfg_version";

    static final String DEFAULT_URL = "http://100.125.8.124:8123/api/webhook/bydmate_car";
    /** The car reaches HA over Tailscale from mobile data, not only on home WiFi. */
    static final boolean DEFAULT_WIFI_ONLY = false;
    /** Bump to force the URL + wifi-only defaults onto existing installs once. */
    static final int CFG_VERSION = 2;

    /**
     * An in-place upgrade keeps the old SharedPreferences, so a changed default
     * would never reach a device that already has the app. This rewrites the URL
     * and wifi-only prefs to the current defaults exactly once per config
     * version, then stamps it so the user's later edits are not clobbered again.
     */
    static void migrate(SharedPreferences p) {
        if (p.getInt(KEY_CFG_VERSION, 0) < CFG_VERSION) {
            p.edit()
                    .putString(KEY_URL, DEFAULT_URL)
                    .putBoolean(KEY_WIFI_ONLY, DEFAULT_WIFI_ONLY)
                    .putInt(KEY_CFG_VERSION, CFG_VERSION)
                    .apply();
        }
    }

    private Prefs() {}
}
