package com.hfs.security.ui.fragments;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.hfs.security.adapters.AppSelectionAdapter;
import com.hfs.security.databinding.FragmentProtectedAppsBinding;
import com.hfs.security.models.AppInfo;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen for Protected App Selection (Phase 1, Step 2).
 * Allows users to choose which installed apps HFS should monitor.
 * Logic: Fetches installed apps -> Cross-references with DB -> Displays with Toggle.
 */
public class ProtectedAppsFragment extends Fragment implements AppSelectionAdapter.OnAppSelectionListener {

    private FragmentProtectedAppsBinding binding;
    private AppSelectionAdapter adapter;
    private List<AppInfo> fullAppList;
    private HFSDatabaseHelper db;
    
    // Executor for background processing to prevent UI freezing during app list fetch
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the protected apps selection layout
        binding = FragmentProtectedAppsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        db = HFSDatabaseHelper.getInstance(requireContext());
        fullAppList = new ArrayList<>();
        
        setupRecyclerView();
        setupSearch();
        
        // Load apps in a background thread
        loadInstalledApps();
    }

    private void setupRecyclerView() {
        binding.rvApps.setLayoutManager(new LinearLayoutManager(requireContext()));
        // Initialize adapter with empty list and the click listener
        adapter = new AppSelectionAdapter(new ArrayList<>(), this);
        binding.rvApps.setAdapter(adapter);
    }

    /**
     * Logic for searching/filtering the app list.
     */
    private void setupSearch() {
        binding.etSearchApps.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    /**
     * Scans the device for installed applications.
     * Filters out system components that have no launcher icon.
     */
    private void loadInstalledApps() {
        binding.progressBar.setVisibility(View.VISIBLE);
        
        executor.execute(() -> {
            PackageManager pm = requireContext().getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppInfo> tempInfoList = new ArrayList<>();
            
            // Get currently protected packages from local database
            Set<String> savedProtectedPackages = db.getProtectedPackages();

            for (ApplicationInfo app : packages) {
                // Filter: Only show apps that the user can actually launch (Gallery, WhatsApp, etc.)
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    // Skip our own app from the list
                    if (app.packageName.equals(requireContext().getPackageName())) continue;

                    String name = app.loadLabel(pm).toString();
                    Drawable icon = app.loadIcon(pm);
                    boolean isAlreadyProtected = savedProtectedPackages.contains(app.packageName);
                    
                    tempInfoList.add(new AppInfo(name, app.packageName, icon, isAlreadyProtected));
                }
            }

            // Sort apps alphabetically for better User Experience
            Collections.sort(tempInfoList, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));

            // Return results to the main UI thread
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    fullAppList = tempInfoList;
                    adapter.updateList(fullAppList);
                    binding.progressBar.setVisibility(View.GONE);
                    
                    if (fullAppList.isEmpty()) {
                        binding.tvNoAppsFound.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    /**
     * Simple filtering logic based on the app name or package string.
     */
    private void filterApps(String query) {
        List<AppInfo> filtered = new ArrayList<>();
        for (AppInfo info : fullAppList) {
            if (info.getAppName().toLowerCase().contains(query.toLowerCase()) ||
                info.getPackageName().toLowerCase().contains(query.toLowerCase())) {
                filtered.add(info);
            }
        }
        adapter.updateList(filtered);
    }

    /**
     * Interface callback: Triggered whenever a checkbox in the list is toggled.
     * Updates the local database set immediately to ensure the Monitor Service stays updated.
     */
    @Override
    public void onAppToggle(String packageName, boolean isSelected) {
        Set<String> currentProtectedSet = new HashSet<>(db.getProtectedPackages());
        
        if (isSelected) {
            currentProtectedSet.add(packageName);
        } else {
            currentProtectedSet.remove(packageName);
        }
        
        // Save the updated list to SharedPreferences/DB
        db.saveProtectedPackages(currentProtectedSet);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}