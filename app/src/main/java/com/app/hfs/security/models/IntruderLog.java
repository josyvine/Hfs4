package com.hfs.security.models;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Data model representing a captured intrusion event.
 * This class wraps the photo file saved during a face mismatch 
 * and extracts metadata for display in the History screen.
 */
public class IntruderLog {

    private final String fileName;
    private final String filePath;
    private final String appName;
    private final long timestamp;
    private final long fileSize;

    /**
     * Constructor that initializes log data from a physical file.
     * Expected filename format: AppName-PackageName-Timestamp.jpg
     * 
     * @param file The image file captured by the background camera service.
     */
    public IntruderLog(File file) {
        this.fileName = file.getName();
        this.filePath = file.getAbsolutePath();
        this.fileSize = file.length();
        this.timestamp = file.lastModified();
        
        // Extract the app name from the structured filename
        this.appName = parseAppNameFromFileName(fileName);
    }

    /**
     * Helper to extract the readable app name from the saved filename.
     */
    private String parseAppNameFromFileName(String name) {
        try {
            if (name.contains("-")) {
                // Returns the first part of the name before the first hyphen
                return name.split("-")[0];
            }
        } catch (Exception e) {
            return "Unknown App";
        }
        return "Unknown";
    }

    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getAppName() {
        return appName;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getFileSize() {
        return fileSize;
    }

    /**
     * Converts the raw file timestamp into a human-readable date and time.
     * Example: Feb 09, 2026 05:18 AM
     */
    public String getFormattedDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm a", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    /**
     * Converts the file size into a readable format (KB/MB).
     */
    public String getReadableFileSize() {
        if (fileSize <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB"};
        int digitGroups = (int) (Math.log10(fileSize) / Math.log10(1024));
        return new java.text.DecimalFormat("#,##0.#")
                .format(fileSize / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}