package com.hfs.security.services;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.hfs.security.utils.DriveHelper;

import java.io.File;
import java.util.Collections;

/**
 * Background Cloud Sync Worker.
 * This class is managed by WorkManager to handle intruder photo uploads
 * when the device is offline or the app is in the background.
 * Logic:
 * 1. Retrieves the local file path from the task data.
 * 2. Authenticates with the saved Google Account.
 * 3. Uses DriveHelper to upload the file and generate a public link.
 * 4. Retries automatically if the network is unstable.
 */
public class DriveUploadWorker extends Worker {

    private static final String TAG = "HFS_DriveWorker";

    public DriveUploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        // 1. Retrieve the file path passed from LockScreenActivity
        String filePath = getInputData().getString("file_path");
        if (filePath == null) {
            return Result.failure();
        }

        File photoFile = new File(filePath);
        if (!photoFile.exists()) {
            Log.e(TAG, "Upload failed: Local file no longer exists.");
            return Result.failure();
        }

        try {
            // 2. Obtain the last signed-in Google Account
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(getApplicationContext());
            if (account == null) {
                Log.e(TAG, "Upload failed: No Google account connected.");
                return Result.failure();
            }

            // 3. Initialize Google Drive Service with the user's credentials
            GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                    getApplicationContext(), 
                    Collections.singleton(DriveScopes.DRIVE_FILE)
            );
            credential.setSelectedAccount(account.getAccount());

            Drive driveService = new Drive.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    new GsonFactory(),
                    credential)
                    .setApplicationName("HFS Security")
                    .build();

            // 4. Perform the Upload via DriveHelper
            DriveHelper driveHelper = new DriveHelper(getApplicationContext(), driveService);
            
            Log.i(TAG, "Starting background upload for: " + photoFile.getName());
            String shareableLink = driveHelper.uploadFileAndGetLink(photoFile);

            if (shareableLink != null) {
                Log.i(TAG, "Background upload successful! Link: " + shareableLink);
                
                /*
                 * Optional: Since this happens after the initial SMS (which likely said 'Pending'),
                 * we could trigger a second SMS here with the actual link if required.
                 * For now, we ensure the file is safe in the cloud.
                 */
                
                return Result.success();
            } else {
                return Result.retry();
            }

        } catch (Exception e) {
            Log.e(TAG, "Critical error during background upload: " + e.getMessage());
            
            // If the error is network-related, we tell WorkManager to try again later
            if (e instanceof java.io.IOException) {
                return Result.retry();
            }
            
            return Result.failure();
        }
    }
}