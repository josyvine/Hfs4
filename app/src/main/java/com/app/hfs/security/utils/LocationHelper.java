package com.hfs.security.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

/**
 * GPS & Location Utility.
 * This class handles the retrieval of geographic coordinates to generate 
 * the Google Maps tracking link for the intruder alert SMS.
 * Uses Google Play Services FusedLocationProvider for maximum accuracy.
 */
public class LocationHelper {

    private static final String TAG = "HFS_LocationHelper";

    /**
     * Interface for location result callback.
     */
    public interface LocationResultCallback {
        void onLocationFound(String mapLink);
        void onLocationFailed(String error);
    }

    /**
     * Fetches the current device location and generates a Google Maps URL.
     * 
     * @param context App context.
     * @param callback The listener to return the formatted map link.
     */
    @SuppressLint("MissingPermission")
    public static void getDeviceLocation(Context context, LocationResultCallback callback) {
        
        // 1. Verify that Location permissions are granted
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            callback.onLocationFailed("Permission denied");
            return;
        }

        // 2. Initialize the Fused Location Client
        FusedLocationProviderClient fusedLocationClient = 
                LocationServices.getFusedLocationProviderClient(context);

        // 3. Request the last known location (Fastest method)
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(new OnSuccessListener<Location>() {
                    @Override
                    public void onSuccess(Location location) {
                        if (location != null) {
                            // Format: https://maps.google.com/maps?q=latitude,longitude
                            String mapUrl = "https://maps.google.com/maps?q=" 
                                    + location.getLatitude() + "," 
                                    + location.getLongitude();
                            
                            Log.i(TAG, "Location Captured: " + mapUrl);
                            callback.onLocationFound(mapUrl);
                        } else {
                            // If last location is null, we attempt a fresh request
                            fetchFreshLocation(fusedLocationClient, callback);
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.e(TAG, "GPS Error: " + e.getMessage());
                        callback.onLocationFailed(e.getMessage());
                    }
                });
    }

    /**
     * Attempts to force a fresh GPS refresh if the 'Last Known Location' is unavailable.
     */
    @SuppressLint("MissingPermission")
    private static void fetchFreshLocation(FusedLocationProviderClient client, LocationResultCallback callback) {
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        String mapUrl = "https://maps.google.com/maps?q=" 
                                + location.getLatitude() + "," 
                                + location.getLongitude();
                        callback.onLocationFound(mapUrl);
                    } else {
                        callback.onLocationFailed("GPS signal unavailable");
                    }
                })
                .addOnFailureListener(e -> callback.onLocationFailed(e.getMessage()));
    }
}