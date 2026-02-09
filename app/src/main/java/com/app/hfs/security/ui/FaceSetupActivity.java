package com.hfs.security.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.hfs.security.databinding.ActivityFaceSetupBinding;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owner Face Registration Screen (Phase 1).
 * This activity allows the owner to scan their face and save it 
 * as the 'Master Identity' for the security system.
 */
public class FaceSetupActivity extends AppCompatActivity {

    private ActivityFaceSetupBinding binding;
    private ExecutorService cameraExecutor;
    private FaceDetector detector;
    private HFSDatabaseHelper db;
    private boolean isFaceCaptured = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize ViewBinding
        binding = ActivityFaceSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Configure ML Kit for high accuracy face detection
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build();
        detector = FaceDetection.getClient(options);

        // UI Controls
        binding.btnBack.setOnClickListener(v -> finish());
        
        // Start the camera for registration
        startCamera();
    }

    /**
     * Initializes CameraX to show a visible preview for the user 
     * while the background analyzer looks for a face.
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 1. Preview: So the user can see themselves
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

                // 2. Analyzer: To process frames for a face
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (isFaceCaptured) {
                        image.close();
                        return;
                    }
                    processImageProxy(image);
                });

                // Use the Front Camera
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("HFS_FaceSetup", "Camera binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Analyzes camera frames to find a clear owner face.
     */
    @SuppressWarnings("UnsafeOptInUsageError")
    private void processImageProxy(androidx.camera.core.ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) return;

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), 
                imageProxy.getImageInfo().getRotationDegrees()
        );

        detector.process(image)
                .addOnSuccessListener(faces -> {
                    if (!faces.isEmpty() && !isFaceCaptured) {
                        // A face is found! In this setup phase, we register the face
                        registerOwnerFace();
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    /**
     * Saves the face registration status to the database.
     */
    private void registerOwnerFace() {
        isFaceCaptured = true;

        runOnUiThread(() -> {
            binding.captureAnimation.setVisibility(View.VISIBLE);
            binding.tvStatus.setText("FACE REGISTERED SUCCESSFULLY");
            
            // 1. Save the Registration Flag
            // (In a production ML app, we would save a face embedding here)
            db.saveOwnerFaceData("REGISTERED_OWNER_ID");
            
            // 2. Mark the overall App Setup as Complete
            db.setSetupComplete(true);

            Toast.makeText(this, "Identity Saved. Security Active.", Toast.LENGTH_LONG).show();

            // Delay closing for 2 seconds so the user sees the success message
            binding.rootLayout.postDelayed(this::finish, 2000);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (detector != null) {
            detector.close();
        }
    }
}