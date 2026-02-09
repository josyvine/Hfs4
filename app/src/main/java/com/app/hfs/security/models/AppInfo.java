package com.hfs.security.models;

import android.graphics.drawable.Drawable;

/**
 * Data model representing an installed application on the user's device.
 * Used in the Protected Apps Selection screen to manage which apps are locked.
 */
public class AppInfo implements Comparable<AppInfo> {

    private String appName;
    private String packageName;
    private Drawable icon;
    private boolean isSelected;

    /**
     * Constructor for AppInfo.
     * 
     * @param appName User-friendly name of the app (e.g., "WhatsApp")
     * @param packageName System ID of the app (e.g., "com.whatsapp")
     * @param icon The launcher icon of the app
     * @param isSelected Whether this app is currently marked for protection
     */
    public AppInfo(String appName, String packageName, Drawable icon, boolean isSelected) {
        this.appName = appName;
        this.packageName = packageName;
        this.icon = icon;
        this.isSelected = isSelected;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public Drawable getIcon() {
        return icon;
    }

    public void setIcon(Drawable icon) {
        this.icon = icon;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    /**
     * Implementation of the Comparable interface.
     * Allows the list of apps to be sorted alphabetically by name automatically
     * using Collections.sort().
     */
    @Override
    public int compareTo(AppInfo other) {
        if (this.appName == null || other.appName == null) {
            return 0;
        }
        return this.appName.compareToIgnoreCase(other.appName);
    } 
}