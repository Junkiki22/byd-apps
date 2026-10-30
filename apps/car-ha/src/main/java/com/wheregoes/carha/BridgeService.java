package com.wheregoes.carha;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

/**
 * Foreground service: every {@link #POLL_INTERVAL_MS} it reads the vehicle via
 * {@link VehicleReader} and POSTs the JSON to the configured webhook, optionally
 * only while on Wi-Fi. All work runs on a dedicated io thread.
 */
public class BridgeService extends Service {

    private static final String TAG = "CarHaService";
    private static final String CHANNEL_ID = "car_ha_service";
    private static final int NOTIFICATION_ID = 7;
    private static final long POLL_INTERVAL_MS = 60_000L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final String TAILSCALE_PKG = "com.tailscale.ipn";
    private static final String TAILSCALE_CONNECT = "com.tailscale.ipn.CONNECT_VPN";
    /** Re-kick Tailscale after start in case it was not ready for the first one. */
    private static final long[] TAILSCALE_RETRY_MS = {15_000L, 45_000L};

    private static volatile boolean sRunning = false;
    /** Last outcome, surfaced in the UI. */
    static volatile String sLastResult = "(未送信)";
    static volatile String sLastSummary = "";
    static volatile long sLastSendTs = 0L;
    /** Freshest fix from active location updates; VehicleReader prefers it. */
    static volatile Location sLastFix;

    private HandlerThread ioThread;
    private Handler io;
    private VehicleReader reader;
    private PowerManager.WakeLock wakeLock;
    private LocationManager locationManager;
    private LocationListener locationListener;

    private final Runnable tick = this::pollAndReschedule;

    static boolean isRunning() { return sRunning; }

    /**
     * DiLink force-stops apps on ACC_OFF and only resumes (no real reboot) on ACC_ON,
     * so Android's always-on VPN never reconnects Tailscale and the webhook (sent over
     * the tailnet) has no route. Ask Tailscale to connect explicitly; a no-op when
     * already connected.
     */
    static void kickTailscale(Context context) {
        try {
            Intent i = new Intent(TAILSCALE_CONNECT)
                    .setClassName(TAILSCALE_PKG, TAILSCALE_PKG + ".IPNReceiver")
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(i);
            Log.i(TAG, "Tailscale CONNECT_VPN sent");
        } catch (Exception e) {
            Log.w(TAG, "Tailscale kick failed", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.migrate(getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE));
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_text)));
        acquireWakeLock();

        reader = new VehicleReader(this);
        ioThread = new HandlerThread("car-ha-io");
        ioThread.start();
        io = new Handler(ioThread.getLooper());
        startLocationUpdates();
        kickTailscale(this);
        for (long delay : TAILSCALE_RETRY_MS) {
            io.postDelayed(() -> kickTailscale(this), delay);
        }
        io.post(tick);

        sRunning = true;
        Log.i(TAG, "BridgeService started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "SEND_NOW".equals(intent.getAction()) && io != null) {
            io.post(() -> doOnce(true));
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        if (io != null) {
            io.removeCallbacksAndMessages(null);
        }
        if (ioThread != null) {
            ioThread.quitSafely();
        }
        stopLocationUpdates();
        releaseWakeLock();
        super.onDestroy();
        Log.i(TAG, "BridgeService stopped");
    }

    // --------------------------------------------------------------- location
    // getLastKnownLocation alone returns a stale cache when nothing is driving
    // the GPS. Actively requesting updates keeps a fresh fix flowing so the
    // position tracks like the live SOC does.

    private void startLocationUpdates() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "location permission not granted; position will be last-known only");
                return;
            }
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                return;
            }
            locationListener = new FixListener();
            String[] providers = { LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER };
            for (String p : providers) {
                try {
                    locationManager.requestLocationUpdates(
                            p, 15_000L, 0f, locationListener, ioThread.getLooper());
                } catch (Exception e) {
                    Log.d(TAG, "requestLocationUpdates(" + p + ") failed: " + e.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "startLocationUpdates failed: " + t.getMessage());
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception ignored) {
            }
        }
    }

    /** Named (not anonymous -- d8) listener that stashes the freshest fix. */
    static final class FixListener implements LocationListener {
        @Override public void onLocationChanged(Location location) {
            if (location != null) {
                sLastFix = location;
            }
        }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    }

    // ------------------------------------------------------------------- poll

    private void pollAndReschedule() {
        try {
            doOnce(false);
        } finally {
            if (io != null) {
                io.postDelayed(tick, POLL_INTERVAL_MS);
            }
        }
    }

    /** io thread. Read, gate on Wi-Fi, POST. */
    private void doOnce(boolean forced) {
        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        String url = prefs.getString(Prefs.KEY_URL, Prefs.DEFAULT_URL);
        boolean wifiOnly = prefs.getBoolean(Prefs.KEY_WIFI_ONLY, Prefs.DEFAULT_WIFI_ONLY);

        JSONObject body = reader.read(this, System.currentTimeMillis());
        sLastSummary = reader.lastSummary;

        if (wifiOnly && !forced && !isOnWifi()) {
            sLastResult = "WiFi外のためスキップ";
            Log.i(TAG, "skip: not on wifi");
            updateNotification();
            return;
        }
        String result = Poster.post(url, body);
        sLastResult = result;
        sLastSendTs = System.currentTimeMillis();
        updateNotification();
    }

    private boolean isOnWifi() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network n = cm.getActiveNetwork();
            if (n == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------------------------- notification

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        try {
            String text = sLastResult + "  " + sLastSummary;
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carha:service");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "wake lock unavailable: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
