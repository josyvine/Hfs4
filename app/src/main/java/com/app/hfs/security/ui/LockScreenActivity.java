package com.hfs.security.ui;

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
import androidx.core.content.ContextCompat;

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
 * FIXED: Implemented a 2-second 'Strict Timeout'. If the owner's face 
 * is not identified immediately, the system automatically triggers 
 * the lock, captures the intruder's photo, and sends an alert.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String TAG = "HFS_LockScreen";
    private ActivityLockScreenBinding binding;
    private ExecutorService cameraExecutor;
    private FaceAuthHelper faceAuthHelper;
    private HFSDatabaseHelper db;
    
    private boolean isProcessing = false;
    private boolean isActionTaken = false;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    // Biometric Fallback
    private Executor biometricExecutor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ensure the activity appears over the system lock screen and other apps
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        faceAuthHelper = new FaceAuthHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // UI Initialization
        binding.lockContainer.setVisibility(View.GONE);
        binding.scanningIndicator.setVisibility(View.VISIBLE);

        setupFingerprintScanner();
        startInvisibleCamera();

        // 1. STRICT TIMEOUT LOGIC:
        // If no match occurs within 2000ms (2 seconds), force the intruder lock.
        timeoutHandler.postDelayed(() -> {
            if (!isActionTaken && !isFinishing()) {
                Log.w(TAG, "Detection Timeout: No face matched within 2 seconds. Locking.");
                handleIntrusionDetection(null); // Passing null because we use the current camera state
            }
        }, 2000);

        // Click Listeners
        binding.btnUnlockPin.setOnClickListener(v -> verifyBackupPin());
        binding.btnFingerprint.setOnClickListener(v -> biometricPrompt.authenticate(promptInfo));
    }

    private void setupFingerprintScanner() {
        biometricExecutor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, biometricExecutor, 
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                finish(); // Fingerprint verified owner
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Verification")
                .setSubtitle("Use fingerprint to access app")
                .setNegativeButtonText("Use PIN")
                .build();
    }

    private void startInvisibleCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 1x1 Pixel Invisible Preview
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.invisiblePreview.getSurfaceProvider());

                // Analysis for Face Matching
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFaceFrame);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera Provider Error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFaceFrame(@NonNull ImageProxy imageProxy) {
        if (isProcessing || isActionTaken) {
            imageProxy.close();
            return;
        }

        isProcessing = true;

        faceAuthHelper.authenticate(imageProxy, new FaceAuthHelper.AuthCallback() {
            @Override
            public void onMatchFound() {
                // SUCCESS: Cancel timeout and allow access
                timeoutHandler.removeCallbacksAndMessages(null);
                runOnUiThread(() -> finish());
            }

            @Override
            public void onMismatchFound() {
                // FAILURE: Unknown face detected. Lock immediately.
                handleIntrusionDetection(imageProxy);
            }

            @Override
            public void onError(String error) {
                isProcessing = false;
                imageProxy.close();
            }
        });
    }

    private void handleIntrusionDetection(ImageProxy imageProxy) {
        if (isActionTaken) return;
        isActionTaken = true;

        // Stop the timeout handler
        timeoutHandler.removeCallbacksAndMessages(null);

        runOnUiThread(() -> {
            // 1. Show Lock UI
            binding.scanningIndicator.setVisibility(View.GONE);
            binding.lockContainer.setVisibility(View.VISIBLE);
            
            // 2. Capture Evidence
            if (imageProxy != null) {
                FileSecureHelper.saveIntruderCapture(LockScreenActivity.this, imageProxy);
            }

            // 3. Send SMS Alert
            String appName = getIntent().getStringExtra("TARGET_APP_NAME");
            if (appName == null) appName = "a Protected App";
            SmsHelper.sendAlertSms(LockScreenActivity.this, appName);

            Toast.makeText(this, "⚠ Intruder Caught - Security Alert Sent", Toast.LENGTH_LONG).show();
            
            // 4. Trigger Fingerprint prompt automatically
            biometricPrompt.authenticate(promptInfo);
        });
    }

    private void verifyBackupPin() {
        String enteredPin = binding.etPinInput.getText().toString();
        if (enteredPin.equals(db.getMasterPin())) {
            finish();
        } else {
            binding.tvErrorMsg.setText("Incorrect Security PIN");
            binding.etPinInput.setText("");
        }
    }

    @Override
    protected void onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null);
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Disabled to prevent intruder from hitting back to bypass
    }
}