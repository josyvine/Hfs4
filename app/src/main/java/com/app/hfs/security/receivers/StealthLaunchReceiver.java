package com.hfs.security.receivers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.hfs.security.R;
import com.hfs.security.ui.StealthUnlockActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Advanced Stealth Mode Receiver.
 * FIXED & UPDATED: 
 * 1. Supports the 'Toggle' plan: Triggers whether the app is hidden or visible.
 * 2. Recognizes raw PIN, *#PIN#, and #PIN# USSD formats.
 * 3. Uses High-Priority Sticky Notifications to bypass Oppo background restrictions.
 * 4. Bridges to the StealthUnlockActivity for secure Hide/Unhide toggling.
 */
public class StealthLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_StealthTrigger";
    private static final String STEALTH_CHANNEL_ID = "hfs_stealth_verified_channel";
    private static final int STEALTH_NOTIF_ID = 3003;

    @Override
    public void onReceive(Context context, Intent intent) {
        // We listen specifically for the moment the 'CALL' button is pressed
        String action = intent.getAction();
        
        if (action != null && action.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
            
            // 1. Extract the number dialed from the system intent
            String dialedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            if (dialedNumber == null) return;

            // 2. Fetch the user's CUSTOM SECRET PIN from the database
            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
            String savedPin = db.getMasterPin(); 

            if (savedPin == null || savedPin.isEmpty()) return;

            // 3. Normalize strings for accurate comparison
            String cleanDialed = dialedNumber.trim();
            String cleanSaved = savedPin.trim();

            // 4. USSD & PIN MATCH LOGIC
            // Verifies if input matches PIN, *#PIN#, or #PIN#
            boolean isMatch = cleanDialed.equals(cleanSaved) || 
                              cleanDialed.equals("*#" + cleanSaved + "#") || 
                              cleanDialed.equals("#" + cleanSaved + "#");

            if (isMatch) {
                Log.i(TAG, "Stealth Authentication Success for PIN: " + cleanSaved);

                // 5. ABORT CALL IMMEDIATELY
                // This stops the cellular network from placing the call and 
                // prevents the secret PIN from appearing in the system call logs.
                setResultData(null);
                abortBroadcast();

                // 6. FEEDBACK: Immediate Toast confirmation as requested
                Toast.makeText(context, "HFS: Identity Verified. Open Notification to proceed.", Toast.LENGTH_LONG).show();

                // 7. TRIGGER THE STICKY NOTIFICATION
                // This notification serves as the portal to the Hide/Unhide popup.
                showStickyVerifiedNotification(context);
            }
        }
    }

    /**
     * Constructs a high-priority system notification.
     * Clicks will launch the StealthUnlockActivity popup.
     */
    private void showStickyVerifiedNotification(Context context) {
        // Prepare the intent for the Stealth Popup Activity
        Intent popupIntent = new Intent(context, StealthUnlockActivity.class);
        popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // Standard PendingIntent flags for modern Android compatibility
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 
                0, 
                popupIntent, 
                flags
        );

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Create the Security Notification Channel for Android O (API 26) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    STEALTH_CHANNEL_ID, 
                    "HFS Identity Verification", 
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setDescription("Portal for HFS Stealth Mode access");
            
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        // Build the notification with Maximum Priority for Oppo/Realme visibility
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, STEALTH_CHANNEL_ID)
                .setSmallIcon(R.drawable.hfs) // Your hfs.png icon
                .setContentTitle("HFS: Identity Verified")
                .setContentText("Tap here to HIDE or UNHIDE the app icon")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOngoing(false); 

        if (notificationManager != null) {
            notificationManager.notify(STEALTH_NOTIF_ID, builder.build());
        }
    }
}