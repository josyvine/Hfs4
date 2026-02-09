package com.hfs.security.ui.fragments;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.hfs.security.databinding.FragmentSettingsBinding;
import com.hfs.security.receivers.AdminReceiver;
import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Advanced Settings Screen (Phase 7 & 8).
 * Manages Master PIN, Trusted Number, Anti-Uninstall, and Stealth Mode.
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private HFSDatabaseHelper db;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = HFSDatabaseHelper.getInstance(requireContext());
        
        // Initialize Device Admin components
        devicePolicyManager = (DevicePolicyManager) requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(requireContext(), AdminReceiver.class);

        loadCurrentSettings();
        setupListeners();
    }

    /**
     * Populates the UI with currently saved security configurations.
     */
    private void loadCurrentSettings() {
        binding.etTrustedNumber.setText(db.getTrustedNumber());
        binding.etSecretPin.setText(db.getMasterPin());
        
        // Check if Anti-Uninstall (Device Admin) is active
        boolean isAdminActive = devicePolicyManager.isAdminActive(adminComponent);
        binding.switchAntiUninstall.setChecked(isAdminActive);

        // Load feature toggles
        binding.switchStealthMode.setChecked(db.isStealthModeEnabled());
        binding.switchFakeGallery.setChecked(db.isFakeGalleryEnabled());
    }

    private void setupListeners() {
        // 1. Save Core Security Credentials
        binding.btnSaveSettings.setOnClickListener(v -> saveCoreCredentials());

        // 2. Anti-Uninstall Toggle (Device Admin)
        binding.switchAntiUninstall.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                activateDeviceAdmin();
            } else {
                deactivateDeviceAdmin();
            }
        });

        // 3. Stealth Mode Toggle (Hiding App Icon)
        binding.switchStealthMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setStealthMode(isChecked);
            if (isChecked) {
                showStealthWarning();
            } else {
                toggleAppIcon(true);
            }
        });

        // 4. Fake Gallery Mode Toggle
        binding.switchFakeGallery.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setFakeGalleryEnabled(isChecked);
            Toast.makeText(getContext(), "Decoy System " + (isChecked ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
        });
    }

    private void saveCoreCredentials() {
        String number = binding.etTrustedNumber.getText().toString().trim();
        String pin = binding.etSecretPin.getText().toString().trim();

        if (TextUtils.isEmpty(number) || pin.length() < 4) {
            Toast.makeText(getContext(), "Enter a valid Number and 4-digit PIN", Toast.LENGTH_SHORT).show();
            return;
        }

        db.saveTrustedNumber(number);
        db.saveMasterPin(pin);
        Toast.makeText(getContext(), "Security Credentials Updated", Toast.LENGTH_SHORT).show();
    }

    private void activateDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to prevent intruders from uninstalling HFS.");
        startActivity(intent);
    }

    private void deactivateDeviceAdmin() {
        devicePolicyManager.removeActiveAdmin(adminComponent);
        Toast.makeText(getContext(), "Anti-Uninstall Protection Disabled", Toast.LENGTH_SHORT).show();
    }

    /**
     * Stealth Mode Logic: Completely hides the app from the launcher.
     */
    private void toggleAppIcon(boolean show) {
        PackageManager pm = requireContext().getPackageManager();
        ComponentName componentName = new ComponentName(requireContext(), SplashActivity.class);
        
        int state = show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED 
                         : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
    }

    private void showStealthWarning() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Stealth Mode Active")
                .setMessage("The app icon will now be hidden. To open HFS again, dial *#*#7392#*#* in your phone's dialer.")
                .setPositiveButton("I Understand", (dialog, which) -> toggleAppIcon(false))
                .setNegativeButton("Cancel", (dialog, which) -> binding.switchStealthMode.setChecked(false))
                .setCancelable(false)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}