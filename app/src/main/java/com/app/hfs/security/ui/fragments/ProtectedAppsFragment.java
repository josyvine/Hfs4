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
 * Screen for Protected App Selection.
 * UPDATED: Removed the system-app filter to ensure Gallery, Photos, and Files 
 * are visible and protectable. Added fragment attachment checks to prevent crashes.
 */
public class ProtectedAppsFragment extends Fragment implements AppSelectionAdapter.OnAppSelectionListener {

    private FragmentProtectedAppsBinding binding;
    private AppSelectionAdapter adapter;
    private List<AppInfo> fullAppList;
    private HFSDatabaseHelper db;
    
    // Executor for background processing to keep the UI responsive
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the layout
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
        
        // Load all apps (including system apps) in a background thread
        loadInstalledApps();
    }

    private void setupRecyclerView() {
        binding.rvApps.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new AppSelectionAdapter(new ArrayList<>(), this);
        binding.rvApps.setAdapter(adapter);
    }

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
     * Logic: Scans the device for apps.
     * UPDATED: Now includes System Apps (Gallery/Files) by checking for 
     * Launch Intents instead of filtering by System Flags.
     */
    private void loadInstalledApps() {
        if (binding != null) {
            binding.progressBar.setVisibility(View.VISIBLE);
        }
        
        executor.execute(() -> {
            // Safety check: Is the fragment still attached?
            if (!isAdded() || getContext() == null) return;

            PackageManager pm = getContext().getPackageManager();
            
            // Get ALL installed applications
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppInfo> tempInfoList = new ArrayList<>();
            
            // Get currently protected packages from local database
            Set<String> savedProtectedPackages = db.getProtectedPackages();

            for (ApplicationInfo app : packages) {
                /* 
                 * FIX: Instead of skipping apps with FLAG_SYSTEM, we check if 
                 * the app has a 'Launch Intent'. This correctly captures 
                 * Gallery, File Manager, and Settings while ignoring 
                 * hidden background system processes.
                 */
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    
                    // Do not show our own HFS app in the list to avoid locking yourself out
                    if (app.packageName.equals(getContext().getPackageName())) continue;

                    String name = app.loadLabel(pm).toString();
                    Drawable icon = app.loadIcon(pm);
                    boolean isAlreadyProtected = savedProtectedPackages.contains(app.packageName);
                    
                    tempInfoList.add(new AppInfo(name, app.packageName, icon, isAlreadyProtected));
                }
            }

            // Sort the final list alphabetically
            Collections.sort(tempInfoList, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));

            // Update the UI on the Main Thread
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (binding != null) {
                        fullAppList = tempInfoList;
                        adapter.updateList(fullAppList);
                        binding.progressBar.setVisibility(View.GONE);
                        
                        if (fullAppList.isEmpty()) {
                            binding.tvNoAppsFound.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    private void filterApps(String query) {
        if (fullAppList == null) return;
        
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
     * Interface callback: Triggered when a user toggles the lock on an app.
     */
    @Override
    public void onAppToggle(String packageName, boolean isSelected) {
        Set<String> currentProtectedSet = new HashSet<>(db.getProtectedPackages());
        
        if (isSelected) {
            currentProtectedSet.add(packageName);
        } else {
            currentProtectedSet.remove(packageName);
        }
        
        // Update the database immediately
        db.saveProtectedPackages(currentProtectedSet);
    }

    @Override
    public void onDestroyView() {
        // Shutdown the executor immediately to prevent background crashes
        executor.shutdownNow();
        super.onDestroyView();
        binding = null;
    }
}