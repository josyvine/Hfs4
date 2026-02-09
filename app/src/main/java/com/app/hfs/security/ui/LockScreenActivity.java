package com.hfs.security.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Security Overlay Activity.
 * This activity launches whenever a protected app is opened.
 * 1. It runs a 1x1 pixel invisible camera preview.
 * 2. It analyzes the front camera frames for face recognition.
 * 3. It either closes (Success) or blocks the user (Mismatch).
 */
public class LockScreenActivity extends AppCompatActivity {

    private ActivityLockScreenBinding binding;
    private ExecutorService cameraExecutor;
    private FaceAuthHelper faceAuthHelper;
    private HFSDatabaseHelper db;
    private boolean isProcessing = false;
    private boolean isLocked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set window flags to appear over other apps and the system lock screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        faceAuthHelper = new FaceAuthHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initially hide the Lock UI (PIN/Warning) to perform silent check first
        binding.lockContainer.setVisibility(View.GONE);
        binding.scanningIndicator.setVisibility(View.VISIBLE);

        // Start the invisible camera preview
        startInvisibleCamera();

        // Setup PIN Button for manual bypass (Phase 9 backup)
        binding.btnUnlockPin.setOnClickListener(v -> verifyBackupPin());
    }

    /**
     * Initializes CameraX to capture the intruder's face silently.
     */
    private void startInvisibleCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 1. Preview (1x1 pixel - invisible to user)
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.invisiblePreview.getSurfaceProvider());

                // 2. Image Analysis (For Face Recognition)
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFaceFrame);

                // Use Front Camera for intruder detection
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Processes each frame from the front camera to identify the user.
     */
    private void analyzeFaceFrame(@NonNull ImageProxy imageProxy) {
        if (isProcessing || isLocked) {
            imageProxy.close();
            return;
        }

        isProcessing = true;

        faceAuthHelper.authenticate(imageProxy, new FaceAuthHelper.AuthCallback() {
            @Override
            public void onMatchFound() {
                // SUCCESS: Owner identified. Close overlay and allow app access.
                runOnUiThread(() -> finish());
            }

            @Override
            public void onMismatchFound() {
                // FAILURE: Unknown person detected. Trigger security actions.
                handleIntrusionDetection(imageProxy);
            }

            @Override
            public void onError(String error) {
                isProcessing = false;
                imageProxy.close();
            }
        });
    }

    /**
     * Executes Phase 3/4: Lock UI, Save Evidence, and Send Alert SMS.
     */
    private void handleIntrusionDetection(ImageProxy imageProxy) {
        if (isLocked) return;
        isLocked = true;

        runOnUiThread(() -> {
            // Show the Warning and PIN prompt
            binding.scanningIndicator.setVisibility(View.GONE);
            binding.lockContainer.setVisibility(View.VISIBLE);
            
            // Save Intruder Photo secretly
            FileSecureHelper.saveIntruderCapture(LockScreenActivity.this, imageProxy);

            // Send Automatic SMS Alert to Trusted Number
            String appName = getIntent().getStringExtra("TARGET_APP_NAME");
            SmsHelper.sendAlertSms(LockScreenActivity.this, appName);

            Toast.makeText(this, "⚠ Intruder Detected - App Locked", Toast.LENGTH_LONG).show();
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
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    @Override
    public void onBackPressed() {
        // Prevent closing the lock screen via back button
        // Intruder must either match face or enter PIN
    }
}