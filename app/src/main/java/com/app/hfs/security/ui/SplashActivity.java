package com.hfs.security.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.hfs.security.R;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Entry point of the HFS Application.
 * Displays the "Hybrid File Security" branding and initializes the 
 * redirection logic based on app setup status.
 */
@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    // Delay duration for the splash screen logo (2.5 seconds)
    private static final int SPLASH_DELAY_MS = 2500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Set the layout containing the hfs.png logo
        setContentView(R.layout.activity_splash);

        // Simple delay logic to show branding before checking security status
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                checkSetupAndNavigate();
            }
        }, SPLASH_DELAY_MS);
    }

    /**
     * Logic to determine where the user goes after the splash screen.
     * Uses HFSDatabaseHelper to check if the owner has already 
     * registered their face and PIN.
     */
    private void checkSetupAndNavigate() {
        HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(this);

        Intent intent;
        
        // Check if the Master PIN and Owner Face registration are complete
        if (db.isSetupComplete()) {
            // If setup is done, go to the Main Dashboard
            intent = new Intent(SplashActivity.this, MainActivity.class);
        } else {
            // If new user, go to the Setup Wizard (Starting with MainActivity 
            // which will host the setup fragments)
            intent = new Intent(SplashActivity.this, MainActivity.class);
            // We pass an extra to tell MainActivity to show the setup flow
            intent.putExtra("SHOW_SETUP", true);
        }

        startActivity(intent);
        
        // Finish SplashActivity so the user cannot return to it via the Back button
        finish();
    }
}