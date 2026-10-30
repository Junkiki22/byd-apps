package com.wheregoes.carha;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("CarHaBoot", "Boot received: " + intent.getAction());
        SharedPreferences prefs =
                context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(Prefs.KEY_ENABLED, false)) {
            // Service may have survived the suspend (onCreate won't rerun), so kick here too.
            BridgeService.kickTailscale(context);
            context.startForegroundService(new Intent(context, BridgeService.class));
            Log.i("CarHaBoot", "BridgeService started on boot");
        }
    }
}
