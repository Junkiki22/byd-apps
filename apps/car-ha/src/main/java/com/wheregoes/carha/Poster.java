package com.wheregoes.carha;

import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One job: POST a JSON body to the Home Assistant webhook. Call off the main thread. */
final class Poster {

    private static final String TAG = "CarHaPoster";
    private static final int TIMEOUT_MS = 10_000;

    private Poster() {}

    /** @return a short result string: HTTP status code, or "ERR: ...". */
    static String post(String urlStr, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            Log.i(TAG, "POST " + urlStr + " -> " + code);
            return String.valueOf(code);
        } catch (Exception e) {
            Log.w(TAG, "POST failed: " + e.getMessage());
            return "ERR: " + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
