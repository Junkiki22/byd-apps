package com.wheregoes.carha;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 11;

    private SharedPreferences prefs;
    private EditText urlInput;
    private CheckBox wifiOnlyBox;
    private Button toggleBtn;
    private TextView statusView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        Prefs.migrate(prefs);

        urlInput = findViewById(R.id.url_input);
        wifiOnlyBox = findViewById(R.id.wifi_only);
        toggleBtn = findViewById(R.id.toggle_btn);
        statusView = findViewById(R.id.status_view);

        urlInput.setText(prefs.getString(Prefs.KEY_URL, Prefs.DEFAULT_URL));
        wifiOnlyBox.setChecked(prefs.getBoolean(Prefs.KEY_WIFI_ONLY, Prefs.DEFAULT_WIFI_ONLY));
        wifiOnlyBox.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(Prefs.KEY_WIFI_ONLY, checked).apply());

        findViewById(R.id.save_btn).setOnClickListener(v -> saveUrl());
        toggleBtn.setOnClickListener(v -> toggleService());
        findViewById(R.id.send_btn).setOnClickListener(v -> sendNow());

        requestLocationIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }

    private void saveUrl() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            url = Prefs.DEFAULT_URL;
            urlInput.setText(url);
        }
        prefs.edit().putString(Prefs.KEY_URL, url).apply();
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
    }

    private void toggleService() {
        if (BridgeService.isRunning()) {
            prefs.edit().putBoolean(Prefs.KEY_ENABLED, false).apply();
            stopService(new Intent(this, BridgeService.class));
        } else {
            saveUrl();
            prefs.edit().putBoolean(Prefs.KEY_ENABLED, true).apply();
            startForegroundService(new Intent(this, BridgeService.class));
        }
        ui.postDelayed(refresh, 400);
    }

    private void sendNow() {
        saveUrl();
        Intent i = new Intent(this, BridgeService.class);
        i.setAction("SEND_NOW");
        startForegroundService(i);
        Toast.makeText(this, R.string.sending, Toast.LENGTH_SHORT).show();
        ui.postDelayed(refresh, 800);
    }

    private void refreshStatus() {
        boolean running = BridgeService.isRunning();
        toggleBtn.setText(running ? R.string.stop : R.string.start);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.state)).append(running ? "稼働中" : "停止中").append("\n");
        sb.append(getString(R.string.last_values)).append(
                BridgeService.sLastSummary == null || BridgeService.sLastSummary.isEmpty()
                        ? "-" : BridgeService.sLastSummary).append("\n");
        sb.append(getString(R.string.last_result)).append(BridgeService.sLastResult).append("\n");
        sb.append("ADB: ").append(AdbShell.sLastStatus).append("\n");
        if (BridgeService.sLastSendTs > 0) {
            CharSequence t = DateFormat.format("MM/dd HH:mm:ss", BridgeService.sLastSendTs);
            sb.append(getString(R.string.last_sent)).append(t);
        }
        statusView.setText(sb.toString());
        ui.postDelayed(refresh, 2000);
    }

    private void requestLocationIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }
    }
}
