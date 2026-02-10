package com.hfs.security.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Advanced Stealth Mode Trigger for Oppo/Realme.
 * FIXED: 
 * 1. Added a Toast message that appears immediately when dialing.
 * 2. Uses 'abortBroadcast' to force-stop the call dialer.
 * 3. Pulls the Custom PIN from the database to avoid hardcoding.
 */
public class StealthLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_StealthReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // We listen for the event where the user presses the 'CALL' button
        String action = intent.getAction();
        
        if (action != null && action.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
            
            // 1. Get the number currently being dialed
            String dialedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            
            if (dialedNumber == null) {
                return;
            }

            // 2. Fetch your CUSTOM PIN from the database settings
            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
            String savedSecretPin = db.getMasterPin(); 

            // 3. Normalize strings (Remove *, #, or spaces)
            String cleanDialed = dialedNumber.replaceAll("[^\\d]", "");
            String cleanSaved = savedSecretPin.replaceAll("[^\\d]", "");

            // 4. Verification Check
            if (!cleanSaved.isEmpty() && cleanDialed.equals(cleanSaved)) {
                
                Log.i(TAG, "Dialer Match: Intercepting call for PIN " + cleanDialed);

                // 5. USER REQUEST: Show Toast message immediately to confirm detection
                Toast.makeText(context, "HFS: Security PIN Detected. Opening App...", Toast.LENGTH_LONG).show();

                /* 
                 * 6. ABORT THE CALL 
                 * setResultData(null) prevents the phone from connecting the call.
                 * abortBroadcast() stops the system from recording this number in Call Logs.
                 */
                setResultData(null);
                abortBroadcast();

                // 7. LAUNCH THE APP WITH OVERLAY-LEVEL PRIORITY
                // We use SplashActivity as the clean entry point
                Intent launchIntent = new Intent(context, SplashActivity.class);
                
                /*
                 * FLAG_ACTIVITY_NEW_TASK: Required when starting from a receiver.
                 * FLAG_ACTIVITY_CLEAR_TOP: Ensures a fresh instance of the app.
                 * FLAG_ACTIVITY_SINGLE_TOP: Prevents duplicate screens.
                 */
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                
                try {
                    // Start the app in the foreground
                    context.startActivity(launchIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Oppo Restriction Error: Could not launch activity: " + e.getMessage());
                }
            }
        }
    }
}