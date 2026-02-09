package com.hfs.security.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Stealth Mode Launch Logic (Phase 8 - Oppo/ColorOS Fix).
 * This receiver intercepts outgoing calls before they are placed.
 * 
 * Logic:
 * 1. User dials their CUSTOM PIN (saved in Settings) and presses the CALL button.
 * 2. This receiver catches the number.
 * 3. If the number matches the saved PIN, the call is ABORTED (canceled).
 * 4. The HFS App is launched immediately.
 */
public class StealthLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_StealthLaunch";

    @Override
    public void onReceive(Context context, Intent intent) {
        // We listen for the New Outgoing Call action
        String action = intent.getAction();
        
        if (action != null && action.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
            
            // 1. Get the number the user just typed in the dialer
            String dialedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            
            if (dialedNumber == null) return;

            // 2. Access the database to get the user's CUSTOM PIN
            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
            String savedSecretPin = db.getMasterPin(); // This is the PIN from your Settings screen

            // 3. Compare the dialed number with the saved PIN
            // We strip any extra characters like * or # just in case the user adds them
            String cleanDialed = dialedNumber.replaceAll("[^\\d]", "");
            String cleanSaved = savedSecretPin.replaceAll("[^\\d]", "");

            if (cleanDialed.equals(cleanSaved) && !cleanSaved.isEmpty()) {
                
                Log.i(TAG, "Stealth Trigger Detected: " + cleanDialed);

                // 4. CANCEL THE CALL
                // This prevents the phone from actually dialing the number and 
                // prevents it from showing up in the call log.
                setResultData(null);

                // 5. LAUNCH THE HFS APP
                Intent launchIntent = new Intent(context, SplashActivity.class);
                
                // FLAG_ACTIVITY_NEW_TASK: Required to start an activity from a receiver
                // FLAG_ACTIVITY_CLEAR_TOP: Ensures a clean launch of the app
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                
                try {
                    context.startActivity(launchIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch HFS from dialer: " + e.getMessage());
                }
            }
        }
    }
}