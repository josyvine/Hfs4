package com.hfs.security.receivers;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

/**
 * Device Administration Receiver.
 * UPDATED: 
 * SMS and GPS logic removed from here to prevent duplicate messages.
 * Intruder detection (with Photo and Drive Link) on System Lock is now handled strictly 
 * by HFSAccessibilityService and SystemCaptureActivity for a faster response.
 */
public class AdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "HFS_AdminReceiver";

    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "HFS: System Protection Enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        super.onDisabled(context, intent);
        Toast.makeText(context, "HFS: Warning - System Protection Disabled", Toast.LENGTH_SHORT).show();
    }

    /**
     * Triggered by the Android OS when a screen unlock attempt fails (usually after 5 tries).
     */
    @Override
    public void onPasswordFailed(@NonNull Context context, @NonNull Intent intent) {
        super.onPasswordFailed(context, intent);
        
        /*
         * Note: We no longer send the SMS from here because it cannot include a photo.
         * The new HFSAccessibilityService already caught the intruder on attempt #1 or #2,
         * took their photo via SystemCaptureActivity, and sent the SMS. 
         * We simply log this event now to avoid double-texting.
         */
        Log.i(TAG, "OS reported System Unlock Failure. Alert already handled by HFS Watcher.");
    }

    @Override
    public void onPasswordSucceeded(@NonNull Context context, @NonNull Intent intent) {
        super.onPasswordSucceeded(context, intent);
        Log.d(TAG, "Device unlocked by owner.");
    }

    @Override
    public CharSequence onDisableRequested(@NonNull Context context, @NonNull Intent intent) {
        return "CRITICAL: Disabling HFS Admin will remove Anti-Uninstall protection.";
    }
}