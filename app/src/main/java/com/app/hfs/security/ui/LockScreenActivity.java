package com.hfs.security.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.common.util.concurrent.ListenableFuture;
import com.hfs.security.databinding.ActivityLockScreenBinding;
import com.hfs.security.utils.FaceAuthHelper;
import com.hfs.security.utils.FileSecureHelper;
import com.hfs.security.utils.HFSDatabaseHelper;
import com.hfs.security.utils.SmsHelper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Security Overlay Activity.
 * FIXED: 
 * 1. Added GPS Location support (Google Maps link).
 * 2. Added Fingerprint Failure trigger (Immediate alert if scan fails).
 * 3. Resolved 'Verifying Identity' loop with a 2-second watchdog.
 * 4. Optimized for Oppo/Realme background activity flags.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String TAG = "HFS_LockScreen";
    private ActivityLockScreenBinding binding;
    private ExecutorService cameraExecutor;
    private FaceAuthHelper faceAuthHelper;
    private HFSDatabaseHelper db;
    private FusedLocationProviderClient fusedLocationClient;

    private boolean isActionTaken = false;
    private boolean isProcessing = false;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());

    // Biometric Logic
    private Executor biometricExecutor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Professional Flags: Ensure overlay is on top of system locks and notifications
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        faceAuthHelper = new FaceAuthHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Initial View State
        binding.lockContainer.setVisibility(View.GONE);
        binding.scanningIndicator.setVisibility(View.VISIBLE);

        setupBiometricAuth();
        startInvisibleCamera();

        // FIX: THE WATCHDOG TIMER (2 Seconds)
        // This ensures the "Verifying Identity" doesn't loop forever.
        watchdogHandler.postDelayed(() -> {
            if (!isActionTaken && !isFinishing()) {
                Log.w(TAG, "Watchdog Timeout: Verification failed. Locking.");
                triggerIntruderAlert(null);
            }
        }, 2000);

        // UI Listeners
        binding.btnUnlockPin.setOnClickListener(v -> checkPinAndUnlock());
        binding.btnFingerprint.setOnClickListener(v -> biometricPrompt.authenticate(promptInfo));
    }

    private void setupBiometricAuth() {
        biometricExecutor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, biometricExecutor, 
                new BiometricPrompt.AuthenticationCallback() {
            
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                // OWNER MATCH: Cancel all timers and close
                watchdogHandler.removeCallbacksAndMessages(null);
                finish();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                // ENHANCEMENT: SENSITIVE LOCKDOWN
                // If a fingerprint attempt is made and fails, trigger alert immediately.
                Log.e(TAG, "Biometric Attempt Failed. Triggering Intruder Alert.");
                triggerIntruderAlert(null);
                Toast.makeText(LockScreenActivity.this, "Security Breach: Identity Mismatch", Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Security Verification")
                .setSubtitle("Confirm identity to access app")
                .setNegativeButtonText("Use PIN")
                .build();
    }

    private void startInvisibleCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.invisiblePreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processCameraFrame);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera Setup Error: " + e.getMessage());
                triggerIntruderAlert(null); // Lock if camera is blocked
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processCameraFrame(@NonNull ImageProxy imageProxy) {
        if (isProcessing || isActionTaken) {
            imageProxy.close();
            return;
        }

        isProcessing = true;
        faceAuthHelper.authenticate(imageProxy, new FaceAuthHelper.AuthCallback() {
            @Override
            public void onMatchFound() {
                watchdogHandler.removeCallbacksAndMessages(null);
                runOnUiThread(() -> finish());
            }

            @Override
            public void onMismatchFound() {
                // Known intruder found - lock instantly
                triggerIntruderAlert(imageProxy);
            }

            @Override
            public void onError(String error) {
                isProcessing = false;
                imageProxy.close();
            }
        });
    }

    /**
     * Enhanced Lockdown Logic:
     * 1. Captures Face.
     * 2. Fetches GPS Location.
     * 3. Sends SMS with Google Maps Link.
     */
    private void triggerIntruderAlert(ImageProxy imageProxy) {
        if (isActionTaken) return;
        isActionTaken = true;

        watchdogHandler.removeCallbacksAndMessages(null);

        runOnUiThread(() -> {
            // Show the Denial UI
            binding.scanningIndicator.setVisibility(View.GONE);
            binding.lockContainer.setVisibility(View.VISIBLE);

            // Save the photo secretly
            if (imageProxy != null) {
                FileSecureHelper.saveIntruderCapture(LockScreenActivity.this, imageProxy);
            }

            // Fetch Location and Send SMS
            getGPSAndSendSms();
            
            // Re-trigger biometric prompt for the real owner
            biometricPrompt.authenticate(promptInfo);
        });
    }

    private void getGPSAndSendSms() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Permission missing? Send SMS without location as fallback
            SmsHelper.sendAlertSms(this, getIntent().getStringExtra("TARGET_APP_NAME"), null);
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            String mapLink = null;
            if (location != null) {
                mapLink = "https://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude();
            }
            
            String appName = getIntent().getStringExtra("TARGET_APP_NAME");
            if (appName == null) appName = "HFS Secure App";

            // Send standard alert with Map link
            SmsHelper.sendAlertSms(LockScreenActivity.this, appName, mapLink);
            
            // ENHANCEMENT: MMS Attempt (Future step)
        });
    }

    private void checkPinAndUnlock() {
        String input = binding.etPinInput.getText().toString();
        if (input.equals(db.getMasterPin())) {
            finish();
        } else {
            binding.tvErrorMsg.setText("Access Denied. Incorrect Master PIN.");
            binding.etPinInput.setText("");
        }
    }

    @Override
    protected void onDestroy() {
        watchdogHandler.removeCallbacksAndMessages(null);
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Prevent bypass via back key
    }
}