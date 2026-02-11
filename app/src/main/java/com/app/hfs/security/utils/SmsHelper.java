package com.hfs.security.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Advanced Alert & SMS/MMS Utility.
 * FIXED: 
 * 1. Automatic Country Code (+91) Sanitization for external delivery.
 * 2. Strict Limit: 3 messages per 5 minutes per app requirements.
 * 3. Simplified formatting to bypass carrier spam filters.
 */
public class SmsHelper {

    private static final String TAG = "HFS_SmsHelper";
    private static final String PREF_SMS_COOLDOWN = "hfs_sms_tracker";
    private static final long COOLDOWN_WINDOW_MS = 5 * 60 * 1000; // 5 Minutes
    private static final int MAX_MESSAGES_PER_WINDOW = 3;

    /**
     * Sends a security alert SMS to the external trusted number.
     * Includes automatic phone number formatting and cooldown logic.
     */
    public static void sendAlertSms(Context context, String targetAppName, String mapLink) {
        
        // 1. CHECK COOLDOWN (3 messages / 5 minutes)
        if (!isSmsAllowed(context)) {
            Log.w(TAG, "SMS Suppression: Limit reached (3 msgs/5 mins).");
            return;
        }

        HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
        String rawNumber = db.getTrustedNumber();

        if (rawNumber == null || rawNumber.isEmpty()) {
            Log.e(TAG, "SMS Failure: No trusted number configured.");
            return;
        }

        // 2. SANITIZE PHONE NUMBER
        // Ensures the message is sent to an international-ready format
        String finalPhoneNumber = sanitizePhoneNumber(rawNumber);

        // 3. CONSTRUCT MESSAGE BODY
        String currentTime = new SimpleDateFormat("dd-MMM-yyyy hh:mm a", Locale.getDefault()).format(new Date());
        
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("⚠ ALERT: Someone accessed ").append(targetAppName).append("\n");
        messageBuilder.append("Time: ").append(currentTime).append("\n");
        messageBuilder.append("Action: App Locked + Photo Saved\n");

        if (mapLink != null && !mapLink.isEmpty()) {
            messageBuilder.append("Location: ").append(mapLink);
        }

        String finalMessage = messageBuilder.toString();

        // 4. EXECUTE TRANSMISSION
        try {
            SmsManager smsManager;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                smsManager = context.getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager != null) {
                // Split long messages to ensure delivery to external carriers
                java.util.ArrayList<String> parts = smsManager.divideMessage(finalMessage);
                smsManager.sendMultipartTextMessage(finalPhoneNumber, null, parts, null, null);
                
                Log.i(TAG, "External SMS Alert sent to: " + finalPhoneNumber);
                
                // 5. UPDATE COOLDOWN TRACKER
                incrementSmsCounter(context);
            }

        } catch (Exception e) {
            Log.e(TAG, "SMS Error: Transmission blocked by carrier or system: " + e.getMessage());
        }
    }

    /**
     * Fix for External Delivery: Ensures number starts with +91 (or custom code).
     */
    private static String sanitizePhoneNumber(String number) {
        String clean = number.replaceAll("[^\\d]", ""); // Keep only digits
        
        // If the user didn't type '+', assume India (+91) as default for your testing
        // You can change "91" to your specific country code if different.
        if (!number.startsWith("+")) {
            if (clean.length() == 10) {
                return "+91" + clean;
            }
        }
        
        // If it already has a '+' or is in a different format, return as is but with '+'
        return number.startsWith("+") ? number : "+" + number;
    }

    /**
     * Enforces the 3-msg/5-min rule.
     */
    private static boolean isSmsAllowed(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_SMS_COOLDOWN, Context.MODE_PRIVATE);
        long windowStart = prefs.getLong("window_start", 0);
        int count = prefs.getInt("count", 0);
        long now = System.currentTimeMillis();

        // Check if the 5-minute window has expired
        if (now - windowStart > COOLDOWN_WINDOW_MS) {
            // Reset the window
            prefs.edit().putLong("window_start", now).putInt("count", 0).apply();
            return true;
        }

        // Return true only if under the 3-message limit
        return count < MAX_MESSAGES_PER_WINDOW;
    }

    private static void incrementSmsCounter(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_SMS_COOLDOWN, Context.MODE_PRIVATE);
        int count = prefs.getInt("count", 0);
        prefs.edit().putInt("count", count + 1).apply();
    }

    /**
     * Placeholder for future MMS development. 
     * Requires Mobile Data and specific APN handling.
     */
    public static void sendMmsAlert(Context context, File photoFile) {
        if (photoFile == null || !photoFile.exists()) return;
        Log.d(TAG, "MMS: Image detected. Packaging PDU for transmission...");
    }
}