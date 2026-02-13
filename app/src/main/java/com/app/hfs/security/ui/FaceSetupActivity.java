package com.hfs.security.ui;

import android.graphics.PointF;
import android.graphics.Rect;
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
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import com.hfs.security.R;
import com.hfs.security.databinding.ActivityFaceSetupBinding;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owner Face Registration Screen.
 * UPDATED for "Zero-Fail" Plan:
 * 1. Step 2: Normalizes landmarks based on Face Bounding Box width (Fixes Distance).
 * 2. Step 3: Implements 5-Point Triangulation (Eyes, Nose, Mouth corners).
 * 3. Multi-Frame Averaging: Captures 5 samples for a rock-solid identity map.
 */
public class FaceSetupActivity extends AppCompatActivity {

    private static final String TAG = "HFS_FaceSetup";
    private ActivityFaceSetupBinding binding;
    private ExecutorService cameraExecutor;
    private FaceDetector detector;
    private HFSDatabaseHelper db;

    // Calibration state
    private boolean isCalibrationDone = false;
    private final int SAMPLES_REQUIRED = 5;
    
    // Lists to store normalized ratios from 5 points
    private final List<Float> ratioEyeToEye = new ArrayList<>();
    private final List<Float> ratioEyeToNose = new ArrayList<>();
    private final List<Float> ratioMouthWidth = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        binding = ActivityFaceSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Configure ML Kit for high-accuracy landmark detection
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.35f) // User must be reasonably close
                .build();
        
        detector = FaceDetection.getClient(options);

        binding.btnBack.setOnClickListener(v -> finish());
        
        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (isCalibrationDone) {
                        image.close();
                        return;
                    }
                    processCalibrationFrame(image);
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraX bind failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    private void processCalibrationFrame(androidx.camera.core.ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) return;

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), 
                imageProxy.getImageInfo().getRotationDegrees()
        );

        detector.process(image)
                .addOnSuccessListener(faces -> {
                    if (!faces.isEmpty() && !isCalibrationDone) {
                        Face face = faces.get(0);
                        
                        // Extract all 5 points required for triangulation
                        FaceLandmark leftEye = face.getLandmark(FaceLandmark.LEFT_EYE);
                        FaceLandmark rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE);
                        FaceLandmark nose = face.getLandmark(FaceLandmark.NOSE_BASE);
                        FaceLandmark mouthL = face.getLandmark(FaceLandmark.MOUTH_LEFT);
                        FaceLandmark mouthR = face.getLandmark(FaceLandmark.MOUTH_RIGHT);

                        if (leftEye != null && rightEye != null && nose != null && mouthL != null && mouthR != null) {
                            collectNormalizedSample(face, leftEye, rightEye, nose, mouthL, mouthR);
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    /**
     * Logic for Step 2 & 3: Normalizes distances by face width and stores samples.
     */
    private void collectNormalizedSample(Face face, FaceLandmark L, FaceLandmark R, 
                                         FaceLandmark N, FaceLandmark ML, FaceLandmark MR) {
        
        // Step 2: Normalization Factor (The width of the face on screen)
        Rect bounds = face.getBoundingBox();
        float faceWidth = (float) bounds.width();
        if (faceWidth <= 0) return;

        // Step 3: Triangulation - Calculate distances
        float distEyeToEye = calculateDistance(L.getPosition(), R.getPosition());
        float distEyeToNose = calculateDistance(L.getPosition(), N.getPosition());
        float distMouthWidth = calculateDistance(ML.getPosition(), MR.getPosition());

        // Save distances as a percentage of the total face width
        // This ensures math stays the same regardless of phone distance
        ratioEyeToEye.add(distEyeToEye / faceWidth);
        ratioEyeToNose.add(distEyeToNose / faceWidth);
        ratioMouthWidth.add(distMouthWidth / faceWidth);

        final int progress = ratioEyeToEye.size();

        runOnUiThread(() -> {
            binding.tvStatus.setText("CALIBRATING IDENTITY: " + progress + "/" + SAMPLES_REQUIRED);
            if (progress >= SAMPLES_REQUIRED) {
                saveAveragedIdentity();
            }
        });
    }

    /**
     * Finalizes the Zero-Fail Identity Map by averaging the 5 snapshots.
     */
    private void saveAveragedIdentity() {
        isCalibrationDone = true;

        float avgEE = 0, avgEN = 0, avgMW = 0;
        for (int i = 0; i < SAMPLES_REQUIRED; i++) {
            avgEE += ratioEyeToEye.get(i);
            avgEN += ratioEyeToNose.get(i);
            avgMW += ratioMouthWidth.get(i);
        }

        avgEE /= SAMPLES_REQUIRED;
        avgEN /= SAMPLES_REQUIRED;
        avgMW /= SAMPLES_REQUIRED;

        // Construct the complex signature string
        final String signatureMap = avgEE + "|" + avgEN + "|" + avgMW;

        runOnUiThread(() -> {
            binding.captureAnimation.setVisibility(View.VISIBLE);
            binding.tvStatus.setText("BIOMETRIC SIGNATURE SAVED");
            
            // Save the normalized 5-point map to the database
            db.saveOwnerFaceData(signatureMap);
            db.setSetupComplete(true);

            Toast.makeText(this, "Calibration Successful", Toast.LENGTH_LONG).show();

            // Return to settings after 2 seconds
            binding.rootLayout.postDelayed(this::finish, 2000);
        });
    }

    private float calculateDistance(PointF p1, PointF p2) {
        return (float) Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (detector != null) detector.close();
    }
}