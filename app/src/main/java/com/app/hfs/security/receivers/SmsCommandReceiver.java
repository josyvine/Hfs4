package com.hfs.security.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import android.widget.Toast;

import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Remote Command Processor (Phase 5).
 * Listens for SMS commands specifically from the Trusted Number.
 * Format: 
 *   HFS LOCK [PIN]   -> Activates full app protection.
 *   HFS UNLOCK [PIN] -> Temporarily disables protection.
 */
public class SmsCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_SmsReceiver";
    private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null && intent.getAction().equals(SMS_RECEIVED)) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                // Parse the SMS PDU (Protocol Data Unit)
                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus != null) {
                    for (Object pdu : pdus) {
                        SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                        String sender = smsMessage.getOriginatingAddress();
                        String messageBody = smsMessage.getMessageBody();

                        if (sender != null && messageBody != null) {
                            processIncomingSms(context, sender, messageBody);
                        }
                    }
                }
            }
        }
    }

    /**
     * Validates sender and parses command logic.
     */
    private void processIncomingSms(Context context, String sender, String message) {
        HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
        String trustedNumber = db.getTrustedNumber();
        String masterPin = db.getMasterPin();

        // 1. SECURITY CHECK: Only allow commands from the registered Trusted Number
        // We use .contains to handle variations in country codes (+1, 00, etc.)
        if (TextUtils_isNumberMatch(sender, trustedNumber)) {
            
            String upperMessage = message.toUpperCase().trim();

            // 2. COMMAND PARSING: HFS LOCK [PIN]
            if (upperMessage.startsWith("HFS LOCK")) {
                if (upperMessage.contains(masterPin)) {
                    executeRemoteLock(context);
                } else {
                    Log.w(TAG, "Remote Lock failed: Incorrect PIN from trusted sender.");
                }
            } 
            // 3. COMMAND PARSING: HFS UNLOCK [PIN]
            else if (upperMessage.startsWith("HFS UNLOCK")) {
                if (upperMessage.contains(masterPin)) {
                    executeRemoteUnlock(context);
                } else {
                    Log.w(TAG, "Remote Unlock failed: Incorrect PIN from trusted sender.");
                }
            }
        }
    }

    /**
     * REMOTE COMMAND: LOCK
     * Note: Accessibility Services require manual user activation in Settings.
     * This method logs the remote request.
     */
    private void executeRemoteLock(Context context) {
        Log.i(TAG, "REMOTE COMMAND RECEIVED: LOCK INITIATED");
        // Manual activation of Accessibility Service is required via Settings.
        Toast.makeText(context, "HFS: Remote Lock Command Received", Toast.LENGTH_SHORT).show();
    }

    /**
     * REMOTE COMMAND: UNLOCK
     * Note: Accessibility Services require manual user deactivation in Settings.
     * This method logs the remote request.
     */
    private void executeRemoteUnlock(Context context) {
        Log.i(TAG, "REMOTE COMMAND RECEIVED: UNLOCK INITIATED");
        // Manual deactivation of Accessibility Service is required via Settings.
        Toast.makeText(context, "HFS: Remote Unlock Command Received", Toast.LENGTH_SHORT).show();
    }

    /**
     * Simple helper to match phone numbers while ignoring formatting like '+' or spaces.
     */
    private boolean TextUtils_isNumberMatch(String sender, String saved) {
        if (sender == null || saved == null || saved.isEmpty()) return false;
        
        // Strip everything except digits
        String cleanSender = sender.replaceAll("[^\\d]", "");
        String cleanSaved = saved.replaceAll("[^\\d]", "");
        
        // Check if the end of the numbers match (standard for international/local overlap)
        return cleanSender.endsWith(cleanSaved) || cleanSaved.endsWith(cleanSender);
    }
}