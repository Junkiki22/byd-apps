package com.wheregoes.carha;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

/**
 * Overrides the client-side permission checks so BYDAUTO_* signature
 * permissions pass without the app being signed by BYD. Copied verbatim (bar
 * the package) from pet-mode; the BYD HAL devices only check on the client
 * side (except Panorama, which this app does not touch).
 */
class BydPermissionContext extends ContextWrapper {

    BydPermissionContext(Context base) {
        super(base);
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        if (permission != null && permission.startsWith("android.permission.BYDAUTO_")) return;
        super.enforceCallingOrSelfPermission(permission, message);
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        if (permission != null && permission.startsWith("android.permission.BYDAUTO_"))
            return PackageManager.PERMISSION_GRANTED;
        return super.checkCallingOrSelfPermission(permission);
    }

    @Override
    public void enforcePermission(String permission, int pid, int uid, String message) {
        if (permission != null && permission.startsWith("android.permission.BYDAUTO_")) return;
        super.enforcePermission(permission, pid, uid, message);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (permission != null && permission.startsWith("android.permission.BYDAUTO_"))
            return PackageManager.PERMISSION_GRANTED;
        return super.checkPermission(permission, pid, uid);
    }
}
