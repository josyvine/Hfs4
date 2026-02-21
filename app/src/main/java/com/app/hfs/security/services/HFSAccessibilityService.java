package com.hfs.security.services;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.hfs.security.ui.LockScreenActivity;
import com.hfs.security.ui.SystemCaptureActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.Set;

/**
 * HFS Real-time Detection Service.
 * Replaces polling with event-driven detection for Zero-Flash locking.
 * 
 * NEW CAPABILITY: Watches the System Lock Screen (System UI) for biometric 
 * failure text or 2 failed PIN clicks, and launches the Invisible Camera.
 */
public class HFSAccessibilityService extends AccessibilityService {

    private static final String TAG = "HFS_Accessibility";
    private HFSDatabaseHelper db;

    // --- SESSION CONTROL FLAGS (For Protected Apps) ---
    public static boolean isLockActive = false;
    private static String unlockedPackage = "";
    private static long lastUnlockTimestamp = 0;
    private static final long SESSION_GRACE_MS = 10000; // 10 Seconds

    // --- SYSTEM LOCK TRACKERS (For Phone Unlock Failures) ---
    private int systemPinAttemptCount = 0;
    private long lastSystemAlertTime = 0;
    private static final long SYSTEM_COOLDOWN_MS = 5000; // 5 seconds cooldown to prevent spamming

    /**
     * Signals that the owner has successfully bypassed the lock (Biometric/PIN).
     * This method is called from LockScreenActivity.
     */
    public static void unlockSession(String packageName) {
        unlockedPackage = packageName;
        lastUnlockTimestamp = System.currentTimeMillis();
        Log.d(TAG, "Owner Verified. Grace Period active for: " + packageName);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        db = HFSDatabaseHelper.getInstance(this);
        Log.d(TAG, "HFS Accessibility Service Connected and Monitoring...");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String currentPkg = event.getPackageName().toString();

        int eventType = event.getEventType();

        // ==========================================================
        // PART 1: NORMAL HFS LOCK LOGIC (For Protected Apps)
        // THIS IS UNTOUCHED AND WORKS EXACTLY AS BEFORE
        // ==========================================================
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            // If the phone successfully unlocks and leaves the System UI, reset the PIN failure counter
            if (!currentPkg.equals("com.android.systemui")) {
                systemPinAttemptCount = 0;
            }

            // 1. SELF-PROTECTION: Ignore HFS itself to prevent lock loops.
            if (currentPkg.equals(getPackageName())) {
                isLockActive = true;
                return;
            }

            // 2. TASK MANAGER BYPASS FIX
            if (isLockActive && !currentPkg.equals(unlockedPackage)) {
                // Proceed to protection check
            } else if (isLockActive) {
                return;
            }

            // 3. RE-ARM LOGIC
            if (!currentPkg.equals(unlockedPackage)) {
                if (!unlockedPackage.isEmpty()) {
                    Log.d(TAG, "User switched apps. Security Re-armed.");
                    unlockedPackage = "";
                }
            }

            // 4. PROTECTION LOGIC
            Set<String> protectedApps = db.getProtectedPackages();
            if (protectedApps.contains(currentPkg)) {
                boolean isSessionValid = currentPkg.equals(unlockedPackage) && 
                        (System.currentTimeMillis() - lastUnlockTimestamp < SESSION_GRACE_MS);

                if (!isSessionValid) {
                    Log.i(TAG, "Security Breach Detected: Immediate Lock for " + currentPkg);
                    triggerLockOverlay(currentPkg);
                }
            }
        }

        // ==========================================================
        // PART 2: THE NEW SYSTEM LOCK WATCHER (For Phone Screen)
        // Watches the lock screen for Finger/Face/PIN failures
        // ==========================================================
        if (currentPkg.equals("com.android.systemui")) {
            
            long currentTime = System.currentTimeMillis();

            // A. WATCH FOR FINGERPRINT/FACE TEXT ERRORS
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                for (CharSequence text : event.getText()) {
                    String screenText = text.toString().toLowerCase();
                    
                    // Look for standard Oppo/Realme/Android failure messages
                    if (screenText.contains("not recognized") || 
                        screenText.contains("mismatch") || 
                        screenText.contains("incorrect") ||
                        screenText.contains("try again")) {
                        
                        if (currentTime - lastSystemAlertTime > SYSTEM_COOLDOWN_MS) {
                            Log.i(TAG, "System Biometric Failure Detected from text: " + screenText);
                            triggerInvisibleSystemCamera();
                            lastSystemAlertTime = currentTime;
                            systemPinAttemptCount = 0; // Reset counter after alert
                        }
                    }
                }
            }

            // B. WATCH FOR PIN/PATTERN CLICKS (Force 2-Attempt Rule)
            if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                systemPinAttemptCount++;
                Log.d(TAG, "System UI Click Detected. Count: " + systemPinAttemptCount);

                if (systemPinAttemptCount >= 2) {
                    if (currentTime - lastSystemAlertTime > SYSTEM_COOLDOWN_MS) {
                        Log.i(TAG, "System PIN Failure Detected (2 attempts).");
                        triggerInvisibleSystemCamera();
                        lastSystemAlertTime = currentTime;
                    }
                    // Reset to 0 so it counts 2 more attempts if they keep trying
                    systemPinAttemptCount = 0; 
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "HFS Accessibility Service Interrupted.");
    }

    /**
     * NORMAL HFS LOCK: Launches the visible Lock Screen Overlay.
     */
    private void triggerLockOverlay(String packageName) {
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
            isLockActive = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch lock overlay: " + e.getMessage());
        }
    }

    /**
     * SYSTEM LOCK INTRUDER: Launches the NEW Invisible Camera Activity.
     * This takes the photo and sends the SMS without the intruder knowing.
     */
    private void triggerInvisibleSystemCamera() {
        Intent captureIntent = new Intent(this, SystemCaptureActivity.class);
        captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                             | Intent.FLAG_ACTIVITY_MULTIPLE_TASK 
                             | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(captureIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch invisible system capture: " + e.getMessage());
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
    public boolean onUnbind(Intent intent) {
        Log.w(TAG, "HFS Accessibility Service Unbound.");
        return super.onUnbind(intent);
    }
}