package com.hfs.security.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Data Storage Utility.
 * FIXED: Added saveIntruderCaptureAndGetFile to support Google Drive uploads.
 * This class handles the conversion of live camera frames into secure local JPEG files.
 */
public class FileSecureHelper {

    private static final String TAG = "HFS_FileSecure";
    private static final String INTRUDER_DIR = "intruders";

    /**
     * NEW: Saves the capture and returns the File object for Google Drive upload.
     * Required by LockScreenActivity to process cloud sync.
     */
    public static File saveIntruderCaptureAndGetFile(Context context, ImageProxy imageProxy) {
        Bitmap bitmap = imageProxyToBitmap(imageProxy);
        if (bitmap == null) return null;

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        bitmap = rotateBitmap(bitmap, rotation);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "HFS_INTRUDER_" + timestamp + ".jpg";

        File directory = new File(context.getExternalFilesDir(null), INTRUDER_DIR);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        File file = new File(directory, fileName);

        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            Log.i(TAG, "Local evidence stored for upload: " + file.getAbsolutePath());
            return file;
        } catch (IOException e) {
            Log.e(TAG, "File creation failed: " + e.getMessage());
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * Standard method to save capture without returning a file reference.
     */
    public static void saveIntruderCapture(Context context, ImageProxy imageProxy) {
        saveIntruderCaptureAndGetFile(context, imageProxy);
    }

    /**
     * Helper to convert CameraX YUV_420_888 format to Bitmap.
     */
    private static Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);

            byte[] imageBytes = out.toByteArray();
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        } catch (Exception e) {
            Log.e(TAG, "Bitmap conversion failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Rotates a bitmap to the correct orientation based on camera sensor data.
     */
    private static Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (degrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        // Flip horizontally for front camera mirror effect
        matrix.postScale(-1, 1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * Purges all locally stored intruder images.
     */
    public static void deleteAllLogs(Context context) {
        File directory = new File(context.getExternalFilesDir(null), INTRUDER_DIR);
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }
}