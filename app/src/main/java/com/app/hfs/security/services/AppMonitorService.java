package com.hfs.security.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hfs.security.HFSApplication;
import com.hfs.security.R;
import com.hfs.security.ui.LockScreenActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.Set;

/**
 * The core Background Guard Service for HFS.
 * FIXED: 
 * 1. Added isLockActive flag to kill the infinite re-triggering loop.
 * 2. Optimized trigger logic to ensure Face Cam opens first in the Activity.
 * 3. Maintained Session Grace logic to allow owner usage after verification.
 */
public class AppMonitorService extends Service {

    private static final String TAG = "HFS_MonitorService";
    private static final int NOTIFICATION_ID = 2002;
    private static final long MONITOR_TICK_MS = 500; 

    private Handler monitorHandler;
    private Runnable monitorRunnable;
    private HFSDatabaseHelper db;

    // LOOP PREVENTION STATE
    // This flag is updated by LockScreenActivity to prevent multiple overlays
    public static boolean isLockActive = false;

    // SESSION MANAGEMENT
    private static String unlockedPackage = "";
    private static long lastUnlockTimestamp = 0;
    private static final long SESSION_TIMEOUT_MS = 30000; // 30 Seconds

    /**
     * Called by LockScreenActivity upon successful verify.
     */
    public static void unlockSession(String packageName) {
        unlockedPackage = packageName;
        lastUnlockTimestamp = System.currentTimeMillis();
        isLockActive = false; // Allow next check after user leaves
        Log.d(TAG, "Session Unlocked and Loop Flag Cleared for: " + packageName);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = HFSDatabaseHelper.getInstance(this);
        monitorHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Security Monitor Service Initialized");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createSecurityNotification());
        startMonitoringLoop();
        return START_STICKY; 
    }

    private Notification createSecurityNotification() {
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_NAME", "HFS Settings");
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, lockIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, HFSApplication.CHANNEL_ID)
                .setContentTitle("HFS: Silent Guard Active")
                .setContentText("Your privacy is currently protected")
                .setSmallIcon(R.drawable.hfs)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * Main aggressive loop.
     * FIXED: Now checks 'isLockActive' to prevent the infinite loading loop.
     */
    private void startMonitoringLoop() {
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                // 1. If a Lock Screen is already visible, do NOT trigger another one.
                // This kills the 'loader loop' behind the fingerprint scan.
                if (isLockActive) {
                    monitorHandler.postDelayed(this, MONITOR_TICK_MS);
                    return;
                }

                String currentApp = getForegroundPackageName();
                Set<String> protectedApps = db.getProtectedPackages();

                // 2. SESSION RE-ARM LOGIC
                if (!currentApp.equals(unlockedPackage) && !currentApp.equals(getPackageName())) {
                    if (!unlockedPackage.isEmpty()) {
                        unlockedPackage = "";
                    }
                }

                // 3. TRIGGER LOCK LOGIC
                if (protectedApps.contains(currentApp)) {
                    
                    long timeSinceUnlock = System.currentTimeMillis() - lastUnlockTimestamp;
                    boolean isAuthorized = currentApp.equals(unlockedPackage) && (timeSinceUnlock < SESSION_TIMEOUT_MS);

                    if (!isAuthorized) {
                        Log.i(TAG, "New Unauthorized Access: " + currentApp + ". Triggering UI.");
                        triggerLockOverlay(currentApp);
                    }
                }

                monitorHandler.postDelayed(this, MONITOR_TICK_MS);
            }
        };
        monitorHandler.post(monitorRunnable);
    }

    private String getForegroundPackageName() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long endTime = System.currentTimeMillis();
        long startTime = endTime - 10000;

        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();
        String currentPkg = "";

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentPkg = event.getPackageName();
            }
        }
        return currentPkg;
    }

    private void triggerLockOverlay(String packageName) {
        // Set the global flag immediately to block the service loop from re-triggering
        isLockActive = true;

        String appName = getAppNameFromPackage(packageName);
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_PACKAGE", packageName);
        lockIntent.putExtra("TARGET_APP_NAME", appName);
        
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                          | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                          | Intent.FLAG_ACTIVITY_CLEAR_TOP
                          | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        
        try {
            startActivity(lockIntent);
        } catch (Exception e) {
            isLockActive = false; // Reset on failure
            Log.e(TAG, "Failed to launch Lock overlay: " + e.getMessage());
        }
    }

    private String getAppNameFromPackage(String packageName) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (String) pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName; 
        }
    }

    @Override
    public void onDestroy() {
        if (monitorHandler != null && monitorRunnable != null) {
            monitorHandler.removeCallbacks(monitorRunnable);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}