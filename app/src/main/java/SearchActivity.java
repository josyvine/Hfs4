package com.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.EditText;


import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchActivity extends Activity implements SearchAdapter.OnItemClickListener, SearchAdapter.OnHeaderCheckedChangeListener, SearchAdapter.OnHeaderClickListener {

    private static final String TAG = "SearchActivity";

    private ImageButton closeButton, filterButton, deleteButton;
    private AutoCompleteTextView searchInput;
    private RecyclerView searchResultsGrid;
    private SearchAdapter adapter;
    private GridLayoutManager gridLayoutManager;
    
    // CHANGED: Separate master list (all data) from display list (what user sees)
    private List<Object> masterList = new ArrayList<>();
    private List<Object> displayList = new ArrayList<>();
    
    private String currentFilterType = "all";
    private ScaleGestureDetector scaleGestureDetector;
    private int currentSpanCount = 3;
    private static final int MIN_SPAN_COUNT = 1;
    private static final int MAX_SPAN_COUNT = 8;

    private RelativeLayout deletionProgressLayout;
    private ProgressBar deletionProgressBar;
    private TextView deletionProgressText;
    private BroadcastReceiver deleteCompletionReceiver;
    private BroadcastReceiver compressionBroadcastReceiver;

    private static final int CATEGORY_IMAGES = 1;
    private static final int CATEGORY_VIDEOS = 2;
    private static final int CATEGORY_AUDIO = 3;
    private static final int CATEGORY_DOCS = 4;
    private static final int CATEGORY_OTHER = 5;

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();

    private static final Pattern FILE_BASE_NAME_PATTERN = Pattern.compile("^(IMG|VID|PANO|DSC)_\\d{8}_\\d{6}");

    private List<SearchResult> mResultsPendingPermission;
    private Runnable mPendingOperation;


    public static class DateHeader {
        private final String dateString;
        private boolean isChecked;
        private boolean isExpanded; // NEW: Track expansion state

        public DateHeader(String dateString) {
            this.dateString = dateString;
            this.isChecked = false;
            this.isExpanded = true; // Default to expanded
        }

        public String getDateString() {
            return dateString;
        }

        public boolean isChecked() {
            return isChecked;
        }

        public void setChecked(boolean checked) {
            isChecked = checked;
        }
        
        public boolean isExpanded() {
            return isExpanded;
        }

        public void setExpanded(boolean expanded) {
            isExpanded = expanded;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        initializeViews();
        setupListeners();
        setupRecyclerView();
        setupPinchToZoom();
        setupBroadcastReceivers();
    }

    private void initializeViews() {
        closeButton = findViewById(R.id.close_button);
        filterButton = findViewById(R.id.filter_button);
        deleteButton = findViewById(R.id.delete_button);
        searchInput = findViewById(R.id.search_input);
        searchResultsGrid = findViewById(R.id.search_results_grid);

        deletionProgressLayout = findViewById(R.id.deletion_progress_layout);
        deletionProgressBar = findViewById(R.id.deletion_progress_bar);
        deletionProgressText = findViewById(R.id.deletion_progress_text);
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        filterButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showFilterMenu(v);
				}
			});

        deleteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showFileOperationsDialog();
				}
			});


        searchInput.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override public void onTextChanged(CharSequence s, int start, int before, int count) {
					fetchFolderSuggestions(s.toString());
				}
				@Override public void afterTextChanged(Editable s) {}
			});

        searchInput.setOnItemClickListener(new AdapterView.OnItemClickListener() {
				@Override
				public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
					String suggestion = (String) parent.getItemAtPosition(position);
					String currentText = searchInput.getText().toString();
					int lastSpaceIndex = currentText.lastIndexOf(' ');
					String newText = (lastSpaceIndex != -1) ? currentText.substring(0, lastSpaceIndex + 1) + suggestion + " " : suggestion + " ";
					searchInput.setText(newText);
					searchInput.setSelection(newText.length());
				}
			});


        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
				@Override
				public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
					executeQuery(searchInput.getText().toString());
					InputMethodManager imm = (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
					if (imm != null) {
						imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
					}
					return true;
				}
			});
    }

    private void setupRecyclerView() {
        gridLayoutManager = new GridLayoutManager(this, currentSpanCount);

        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
				@Override
				public int getSpanSize(int position) {
					if (position >= 0 && position < displayList.size()) {
						if (displayList.get(position) instanceof DateHeader) {
							return currentSpanCount;
						}
					}
					return 1;
				}
			});

        searchResultsGrid.setLayoutManager(gridLayoutManager);
        // CHANGED: Pass 'this' as OnHeaderClickListener as well
        adapter = new SearchAdapter(this, displayList, this, this, this);
        searchResultsGrid.setAdapter(adapter);
    }

    private void setupPinchToZoom() {
        scaleGestureDetector = new ScaleGestureDetector(this, new PinchZoomListener());
        searchResultsGrid.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View v, MotionEvent event) {
					scaleGestureDetector.onTouchEvent(event);
					return false;
				}
			});
    }

    private void executeQuery(final String query) {
        searchExecutor.execute(new Runnable() {
				@Override
				public void run() {
					final QueryParameters params = parseQuery(query);
					List<SearchResult> mediaStoreResults = executeQueryWithMediaStore(params);

					if (!mediaStoreResults.isEmpty()) {
						updateUIWithResults(mediaStoreResults);
					} else {
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									Toast.makeText(SearchActivity.this, "MediaStore found nothing. Starting deep scan...", Toast.LENGTH_SHORT).show();
								}
							});
						List<SearchResult> fileSystemResults = performFallbackFileSearch(params);
						updateUIWithResults(fileSystemResults);
					}
				}
			});
    }

    private void updateUIWithResults(final List<SearchResult> results) {
        final List<Object> groupedList = processAndGroupResults(results);
        runOnUiThread(new Runnable() {
				@Override
				public void run() {
					masterList.clear();
					masterList.addAll(groupedList);
                    // Initially show everything (all headers expanded by default)
                    rebuildDisplayList();
					if (results.isEmpty()) {
						Toast.makeText(SearchActivity.this, "No files found.", Toast.LENGTH_SHORT).show();
					}
				}
			});
    }
    
    // NEW: Logic to rebuild the display list based on expanded/collapsed headers
    private void rebuildDisplayList() {
        displayList.clear();
        boolean isCurrentGroupExpanded = true;
        
        for (Object item : masterList) {
            if (item instanceof DateHeader) {
                DateHeader header = (DateHeader) item;
                displayList.add(header);
                isCurrentGroupExpanded = header.isExpanded();
            } else {
                // If it's a file item, only add it if the current group is expanded
                if (isCurrentGroupExpanded) {
                    displayList.add(item);
                }
            }
        }
        adapter.updateData(displayList);
    }

    private List<Object> processAndGroupResults(List<SearchResult> flatResults) {
        List<Object> groupedList = new ArrayList<>();
        if (flatResults.isEmpty()) {
            return groupedList;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());
        String currentHeaderDate = "";

        for (SearchResult result : flatResults) {
            String resultDate = sdf.format(new Date(result.getLastModifiedForGrouping()));
            if (!resultDate.equals(currentHeaderDate)) {
                currentHeaderDate = resultDate;
                groupedList.add(new DateHeader(currentHeaderDate));
            }
            groupedList.add(result);
        }

        return groupedList;
    }

    private List<SearchResult> executeQueryWithMediaStore(QueryParameters params) {
        StringBuilder selection = new StringBuilder();
        List<String> selectionArgs = new ArrayList<>();
        Uri queryUri = MediaStore.Files.getContentUri("external");
        addFilterClauses(selection, selectionArgs);

        if (params.startTimeSeconds != -1 && params.endTimeSeconds != -1) {
            if (selection.length() > 0) selection.append(" AND ");
            selection.append(MediaStore.Files.FileColumns.DATE_MODIFIED + " >= ? AND " + MediaStore.Files.FileColumns.DATE_MODIFIED + " <= ?");
            selectionArgs.add(String.valueOf(params.startTimeSeconds));
            selectionArgs.add(String.valueOf(params.endTimeSeconds));
        }

        if (params.folderPath != null && !params.folderPath.isEmpty()) {
            if (selection.length() > 0) selection.append(" AND ");
            selection.append(MediaStore.Files.FileColumns.DATA + " LIKE ?");
            selectionArgs.add("%" + params.folderPath + "%");
        }

        List<SearchResult> results = new ArrayList<>();
        String[] projection = {
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA
        };

        final int limit = 2000;
        int offset = 0;
        String baseSortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";

        while (true) {
            String sortOrder = baseSortOrder + " LIMIT " + limit + " OFFSET " + offset;
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(queryUri, projection, selection.toString(),
                                                   selectionArgs.toArray(new String[0]), sortOrder);

                if (cursor == null || cursor.getCount() == 0) {
                    break;
                }

                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                int mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
                int dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    int mediaType = cursor.getInt(mediaTypeColumn);
                    long dateModifiedSeconds = cursor.getLong(dateModifiedColumn);
                    String displayName = cursor.getString(displayNameColumn);
                    String path = cursor.getString(dataColumn);
                    Uri contentUri;
                    if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                        contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    } else if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                        contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    } else {
                        contentUri = ContentUris.withAppendedId(queryUri, id);
                    }
                    results.add(new SearchResult(contentUri, id, dateModifiedSeconds * 1000, displayName, path));
                }

                if (cursor.getCount() < limit) {
                    break;
                }

                offset += limit;

            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return results;
    }

    private List<SearchResult> performFallbackFileSearch(QueryParameters params) {
        List<SearchResult> results = new ArrayList<>();
        File externalStorage = Environment.getExternalStorageDirectory();

        List<File> rootsToScan = new ArrayList<>();
        
        // --- ADDED: Standard Primary Storage paths ---
        rootsToScan.add(new File(externalStorage, "WhatsApp"));
        rootsToScan.add(new File(externalStorage, "Android/media/com.whatsapp/WhatsApp"));
        rootsToScan.add(new File(externalStorage, "Download"));
        rootsToScan.add(new File(externalStorage, "Telegram"));
        rootsToScan.add(new File(externalStorage, "DCIM"));
        rootsToScan.add(new File(externalStorage, "Pictures"));
        rootsToScan.add(new File(externalStorage, "DCIM/Camera"));
        
        // --- NEW: CLONED APP SUPPORT (Dual Messenger / Parallel Space) ---
        // Scan for user 999 (common for dual apps)
        File dualAppStorage = new File("/storage/emulated/999");
        if (dualAppStorage.exists() && dualAppStorage.canRead()) {
             rootsToScan.add(new File(dualAppStorage, "WhatsApp"));
             rootsToScan.add(new File(dualAppStorage, "Android/media/com.whatsapp/WhatsApp"));
             rootsToScan.add(new File(dualAppStorage, "DCIM"));
             rootsToScan.add(new File(dualAppStorage, "Download"));
        }
        
        // Scan for user 10 (sometimes used)
        File parallelAppStorage = new File("/storage/emulated/10");
        if (parallelAppStorage.exists() && parallelAppStorage.canRead()) {
             rootsToScan.add(new File(parallelAppStorage, "WhatsApp"));
             rootsToScan.add(new File(parallelAppStorage, "DCIM"));
        }

        for (File root : rootsToScan) {
            if (root.exists() && root.isDirectory()) {
                scanDirectory(root, params, results);
            }
        }

        Collections.sort(results, new Comparator<SearchResult>() {
				@Override
				public int compare(SearchResult f1, SearchResult f2) {
					return Long.compare(f2.getLastModifiedForGrouping(), f1.getLastModifiedForGrouping());
				}
			});
        return results;
    }

    private void scanDirectory(File directory, QueryParameters params, List<SearchResult> results) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // If specific folder searched, only go deeper if it matches
                if (params.folderPath == null || file.getAbsolutePath().toLowerCase().contains(params.folderPath.toLowerCase())) {
                    scanDirectory(file, params, results);
                }
            } else {
                boolean dateMatch = (params.startTimeSeconds == -1) ||
					(file.lastModified() >= params.startTimeSeconds * 1000 && file.lastModified() <= params.endTimeSeconds * 1000);

                boolean folderMatch = (params.folderPath == null) ||
					(file.getAbsolutePath().toLowerCase().contains(params.folderPath.toLowerCase()));

                if (dateMatch && folderMatch) {
                    if (isFileTypeMatch(file.getName())) {
                        results.add(new SearchResult(Uri.fromFile(file), file.lastModified(), file.lastModified(), file.getName(), file.getAbsolutePath()));
                    }
                }
            }
        }
    }

    private void addFilterClauses(StringBuilder selection, List<String> selectionArgs) {
        if ("images".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE + " = ?");
            selectionArgs.add(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE));
        } else if ("videos".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE + " = ?");
            selectionArgs.add(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO));
        } else if ("documents".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?, ?, ?, ?)");
            selectionArgs.addAll(Arrays.asList("application/pdf", "application/msword",
											   "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel",
											   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-powerpoint",
											   "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        } else if ("archives".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?, ?)");
            selectionArgs.addAll(Arrays.asList("application/zip", "application/vnd.rar", "application/x-7z-compressed",
											   "application/x-tar", "application/gzip"));
        } else if ("other".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE + " NOT IN (?, ?, ?)");
            selectionArgs.addAll(Arrays.asList(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
											   String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
											   String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)));
        }
    }

    private boolean isFileTypeMatch(String fileName) {
        if (currentFilterType.equals("all")) return true;

        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase();
        }

        switch (currentFilterType) {
            case "images":
                return Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(extension);
            case "videos":
                return Arrays.asList("mp4", "3gp", "mkv", "webm", "avi").contains(extension);
            case "documents":
                return Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt").contains(extension);
            case "archives":
                return Arrays.asList("zip", "rar", "7z", "tar", "gz").contains(extension);
            case "other":
                return !isFileTypeMatch(fileName, "images") && !isFileTypeMatch(fileName, "videos") &&
					!isFileTypeMatch(fileName, "documents") && !isFileTypeMatch(fileName, "archives");
            default:
                return true;
        }
    }

    private boolean isFileTypeMatch(String fileName, String type) {
        String originalFilter = this.currentFilterType;
        this.currentFilterType = type;
        boolean match = isFileTypeMatch(fileName);
        this.currentFilterType = originalFilter;
        return match;
    }

    private QueryParameters parseQuery(String query) {
        QueryParameters params = new QueryParameters();
        String q_trimmed = query.trim();
        if (q_trimmed.isEmpty()) {
            return params;
        }
        String q_lower = q_trimmed.toLowerCase();


        switch (currentFilterType) {
            case "images":
                Pattern p1_img = Pattern.compile("^(.*?)\\s+(\\d+)\\s+days$");
                Matcher m1_img = p1_img.matcher(q_lower);
                if (m1_img.find()) {
                    int keywordEndIndex = m1_img.start(2);
                    params.folderPath = q_trimmed.substring(0, keywordEndIndex).trim();
                    try {
                        int days = Integer.parseInt(m1_img.group(2));
                        if (days > 0) {
                            params.setDateRange(getStartOfDaysAgo(days - 1), getEndOfToday());
                        }
                    } catch (NumberFormatException ignored) {}
                    return params;
                }

                Pattern p2_img = Pattern.compile("^(.*?)\\s+(today|yesterday)$");
                Matcher m2_img = p2_img.matcher(q_lower);
                if (m2_img.find()) {
                    int keywordEndIndex = m2_img.start(2);
                    params.folderPath = q_trimmed.substring(0, keywordEndIndex).trim();
                    String daySpecifier = m2_img.group(2);
                    if ("today".equals(daySpecifier)) {
                        params.setDateRange(getStartOfToday(), getEndOfToday());
                    } else { // yesterday
                        params.setDateRange(getStartOfYesterday(), getEndOfYesterday());
                    }
                    return params;
                }

                Pattern p3_img = Pattern.compile("^day\\s+(\\d+)$");
                Matcher m3_img = p3_img.matcher(q_lower);
                if (m3_img.find()) {
                    try {
                        int day = Integer.parseInt(m3_img.group(1));
                        if (day > 0) {
                            params.setDateRange(getStartOfDaysAgo(day - 1), getEndOfDaysAgo(day - 1));
                        }
                    } catch (NumberFormatException ignored) {}
                    return params;
                }

                params.folderPath = q_trimmed;
                break;

            case "videos":
            case "documents":
            case "archives":
            case "other":
                Pattern p1_gen = Pattern.compile("^(.*?)\\s+day\\s+(\\d+)$");
                Matcher m1_gen = p1_gen.matcher(q_lower);
                if (m1_gen.find()) {
                    int keywordEndIndex = q_lower.lastIndexOf(" day ");
                    params.folderPath = q_trimmed.substring(0, keywordEndIndex).trim();
                    try {
                        int day = Integer.parseInt(m1_gen.group(2));
                        if (day > 0) {
                            params.setDateRange(getStartOfDaysAgo(day - 1), getEndOfDaysAgo(day - 1));
                        }
                    } catch (NumberFormatException ignored) {}
                    return params;
                }

                Pattern p2_gen = Pattern.compile("^day\\s+(\\d+)$");
                Matcher m2_gen = p2_gen.matcher(q_lower);
                if (m2_gen.find()) {
                    try {
                        int day = Integer.parseInt(m2_gen.group(1));
                        if (day > 0) {
                            params.setDateRange(getStartOfDaysAgo(day - 1), getEndOfDaysAgo(day - 1));
                        }
                    } catch (NumberFormatException ignored) {}
                    return params;
                }

                Pattern p3_gen = Pattern.compile("^(\\d+)\\s+days$");
                Matcher m3_gen = p3_gen.matcher(q_lower);
                if (m3_gen.find()) {
                    try {
                        int days = Integer.parseInt(m3_gen.group(1));
                        if (days > 0) {
                            params.setDateRange(getStartOfDaysAgo(days - 1), getEndOfToday());
                        }
                    } catch (NumberFormatException ignored) {}
                    return params;
                }

                params.folderPath = q_trimmed;
                break;

            case "all":
            default:
                String[] originalParts = q_trimmed.split("\\s+");
                if (originalParts.length == 1 && originalParts[0].isEmpty()) {
                    return params;
                }
                boolean[] used = new boolean[originalParts.length];

                for (int i = 0; i <= originalParts.length - 3; i++) {
                    if (!used[i] && !used[i+1] && !used[i+2] && originalParts[i + 1].equalsIgnoreCase("days") && originalParts[i + 2].equalsIgnoreCase("ago")) {
                        try {
                            int days = Integer.parseInt(originalParts[i]);
                            params.setDateRange(getStartOfDaysAgo(days), getEndOfDaysAgo(days));
                            used[i] = used[i + 1] = used[i + 2] = true;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                for (int i = 0; i <= originalParts.length - 2; i++) {
                    if (!used[i] && !used[i+1] && originalParts[i + 1].equalsIgnoreCase("days")) {
                        try {
                            int days = Integer.parseInt(originalParts[i]);
                            params.setDateRange(getStartOfDaysAgo(days - 1), getEndOfToday());
                            used[i] = used[i + 1] = true;
                        } catch (NumberFormatException ignored) {}
                    }
                }

                for (int i = 0; i < originalParts.length; i++) {
                    if (used[i]) continue;
                    String partLower = originalParts[i].toLowerCase();
                    if (partLower.equals("today")) {
                        params.setDateRange(getStartOfToday(), getEndOfToday());
                        used[i] = true;
                    } else if (partLower.equals("yesterday")) {
                        params.setDateRange(getStartOfYesterday(), getEndOfYesterday());
                        used[i] = true;
                    } else if (partLower.equals("phone") || partLower.equals("sdcard")) {
                        used[i] = true;
                    }
                }

                StringBuilder folderBuilder = new StringBuilder();
                for (int i = 0; i < originalParts.length; i++) {
                    if (!used[i]) {
                        if (folderBuilder.length() > 0) folderBuilder.append(" ");
                        folderBuilder.append(originalParts[i]);
                    }
                }
                String finalFolderPath = folderBuilder.toString().trim();
                if (!finalFolderPath.isEmpty()) {
                    params.folderPath = finalFolderPath;
                }
                break;
        }
        return params;
    }

    private long getStartOfToday() { Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); return c.getTimeInMillis() / 1000; }
    private long getEndOfToday() { Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); return c.getTimeInMillis() / 1000; }
    private long getStartOfYesterday() { return getStartOfDaysAgo(1); }
    private long getEndOfYesterday() { return getEndOfDaysAgo(1); }
    private long getStartOfDaysAgo(int days) { Calendar c = Calendar.getInstance(); c.add(Calendar.DATE, -days); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); return c.getTimeInMillis() / 1000; }
    private long getEndOfDaysAgo(int days) { Calendar c = Calendar.getInstance(); c.add(Calendar.DATE, -days); c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); return c.getTimeInMillis() / 1000; }

    private void fetchFolderSuggestions(final String constraint) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String lastWord = constraint;
                int lastSpaceIndex = constraint.lastIndexOf(' ');
                if (lastSpaceIndex != -1) {
                    lastWord = constraint.substring(lastSpaceIndex + 1);
                }

                if (lastWord.isEmpty()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (searchInput != null) searchInput.dismissDropDown();
                        }
                    });
                    return;
                }

                final Set<String> folderSet = new HashSet<>();
                Uri uri = MediaStore.Files.getContentUri("external");
                String[] projection = {"DISTINCT " + MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME};
                String selection = MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME + " LIKE ?";
                String[] selectionArgs = {lastWord + "%"};
                String sortOrder = MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME + " ASC";

                Cursor cursor = null;
                try {
                    cursor = getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
                    if (cursor != null) {
                        int bucketColumnIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME);
                        while (cursor.moveToNext()) {
                            String folderName = cursor.getString(bucketColumnIndex);
                            if (folderName != null) {
                                folderSet.add(folderName);
                            }
                        }
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }

                final List<String> suggestions = new ArrayList<>(folderSet);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed() || searchInput == null) {
                            return;
                        }
                        ArrayAdapter<String> suggestionAdapter = new ArrayAdapter<>(SearchActivity.this,
                                android.R.layout.simple_dropdown_item_1line, suggestions);
                        searchInput.setAdapter(suggestionAdapter);
                        if (!suggestions.isEmpty() && searchInput.isFocused()) {
                            searchInput.showDropDown();
                        }
                    }
                });
            }
        }).start();
    }

    private void initiateDeletionProcess() {
        final List<SearchResult> selectedResults = new ArrayList<>();
        // CHANGED: Use masterList here to ensure we delete hidden items if they were selected previously
        for (Object item : masterList) {
            if (item instanceof SearchResult) {
                SearchResult result = (SearchResult) item;
                if (!result.isExcluded()) {
                    selectedResults.add(result);
                }
            }
        }

        if (selectedResults.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean requiresSdCardPermission = false;
        for (SearchResult result : selectedResults) {
            if (result.getPath() != null) {
                File file = new File(result.getPath());
                if (StorageUtils.isFileOnSdCard(this, file) && !StorageUtils.hasSdCardPermission(this)) {
                    requiresSdCardPermission = true;
                    break;
                }
            }
        }

        if (requiresSdCardPermission) {
            mResultsPendingPermission = selectedResults;
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    confirmAndDelete(selectedResults);
                }
            };
            promptForSdCardPermission();
        } else {
            confirmAndDelete(selectedResults);
        }
    }

    private void confirmAndDelete(final List<SearchResult> filesToConfirm) {
        final Set<SearchResult> masterDeleteSet = new HashSet<>();
        for (SearchResult selectedResult : filesToConfirm) {
            masterDeleteSet.addAll(findSiblingFiles(selectedResult));
        }

        final List<SearchResult> toDelete = new ArrayList<>(masterDeleteSet);
        String dialogMessage;

        if (toDelete.size() > filesToConfirm.size()) {
            int siblingCount = toDelete.size() - filesToConfirm.size();
            dialogMessage = "You selected <b>" + filesToConfirm.size() + "</b> file(s), but we found <b>" + siblingCount
                + "</b> other related version(s).<br/><br/>Choose an action for all <b>"
                + toDelete.size() + "</b> related files.";
        } else {
            dialogMessage = "Choose an action for the " + toDelete.size() + " selected file(s).";
        }

        new AlertDialog.Builder(this).setTitle("Confirm Action")
			.setMessage(Html.fromHtml(dialogMessage))
			.setPositiveButton("Delete All", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					performDelete(toDelete);
				}
			})
            .setNeutralButton("Move to Recycle", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    moveToRecycleBin(toDelete);
                }
            })
            .setNegativeButton("Hide Files", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    hideFiles(toDelete);
                }
            })
			.show();
    }

    private void hideFiles(List<SearchResult> resultsToHide) {
        ArrayList<File> filesToHide = new ArrayList<>();
        for (SearchResult result : resultsToHide) {
            if (result.getPath() != null) {
                filesToHide.add(new File(result.getPath()));
            }
        }

        if (filesToHide.isEmpty()) {
            Toast.makeText(this, "Could not resolve file paths to hide.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, FileHiderActivity.class);
        intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, (Serializable) filesToHide);
        startActivity(intent);
    }


    private void moveToRecycleBin(List<SearchResult> resultsToMove) {
        new MoveToRecycleTask(resultsToMove).execute();
    }

    private List<SearchResult> findSiblingFiles(SearchResult originalResult) {
        List<SearchResult> siblings = new ArrayList<>();
        siblings.add(originalResult);

        if (originalResult.getPath() == null) {
            return siblings;
        }

        File originalFile = new File(originalResult.getPath());
        String fileName = originalFile.getName();
        Matcher matcher = FILE_BASE_NAME_PATTERN.matcher(fileName);

        if (matcher.find()) {
            String baseName = matcher.group(0);
            File parentDir = originalFile.getParentFile();

            if (parentDir != null && parentDir.isDirectory()) {
                // Use master list for sibling check
                for (Object item : masterList) {
                    if (item instanceof SearchResult) {
                        SearchResult potentialSibling = (SearchResult) item;
                        if (potentialSibling.getPath() != null) {
                            File potentialFile = new File(potentialSibling.getPath());
                            if (potentialFile.getParent() != null && potentialFile.getParent().equals(parentDir.getAbsolutePath()) &&
                                potentialFile.getName().startsWith(baseName) &&
                                !potentialFile.getAbsolutePath().equals(originalFile.getAbsolutePath())) {
                                siblings.add(potentialSibling);
                            }
                        }
                    }
                }
            }
        }
        return siblings;
    }


    private void performDelete(final List<SearchResult> toDelete) {
        ArrayList<String> filePathsToDelete = new ArrayList<>();
        for (SearchResult result : toDelete) {
            if (result.getPath() != null) {
                filePathsToDelete.add(result.getPath());
            }
        }

        if (filePathsToDelete.isEmpty()) {
            Toast.makeText(this, "Could not resolve file paths for deletion.", Toast.LENGTH_SHORT).show();
            return;
        }

        deletionProgressLayout.setVisibility(View.VISIBLE);
        deletionProgressBar.setIndeterminate(true);
        deletionProgressText.setText("Starting deletion...");

        Intent intent = new Intent(this, DeleteService.class);
        intent.putStringArrayListExtra(DeleteService.EXTRA_FILES_TO_DELETE, filePathsToDelete);
        ContextCompat.startForegroundService(this, intent);
    }

    private void promptForSdCardPermission() {
        new AlertDialog.Builder(this)
            .setTitle("SD Card Permission Needed")
            .setMessage("To delete files on your external SD card, you must grant this app access. Please tap 'Grant', then select the root of your SD card (e.g., 'Galaxy SD Card') on the next screen and tap 'Allow'.")
            .setPositiveButton("Grant", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    StorageUtils.requestSdCardPermission(SearchActivity.this);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == StorageUtils.REQUEST_CODE_SDCARD_PERMISSION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri,
																	  Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                    StorageUtils.saveSdCardUri(this, treeUri);
                    Toast.makeText(this, "SD card access granted.", Toast.LENGTH_SHORT).show();

                    if (mPendingOperation != null) {
                        mPendingOperation.run();
                    } else if (mResultsPendingPermission != null && !mResultsPendingPermission.isEmpty()) {
                        confirmAndDelete(mResultsPendingPermission);
                    }
                }
            } else {
                Toast.makeText(this, "SD card permission was not granted.", Toast.LENGTH_SHORT).show();
            }
            mResultsPendingPermission = null;
            mPendingOperation = null;
        }
    }

    private void showFilterMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenuInflater().inflate(R.menu.filter_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
				@Override
				public boolean onMenuItemClick(MenuItem item) {
					int itemId = item.getItemId();
					if (itemId == R.id.filter_all) currentFilterType = "all";
					else if (itemId == R.id.filter_images) currentFilterType = "images";
					else if (itemId == R.id.filter_videos) currentFilterType = "videos";
					else if (itemId == R.id.filter_documents) currentFilterType = "documents";
					else if (itemId == R.id.filter_archives) currentFilterType = "archives";
					else if (itemId == R.id.filter_other) currentFilterType = "other";
					
                    // Clear both lists and refresh data
                    masterList.clear();
                    displayList.clear();
                    adapter.updateData(displayList);
                    
					executeQuery(searchInput.getText().toString());
					return true;
				}
			});
        popup.show();
    }

    @Override
    public void onItemClick(SearchResult item) {
        item.setExcluded(!item.isExcluded());
        updateHeaderStateForItem(item);
        // Find index in displayList to notify adapter
        int index = displayList.indexOf(item);
        if(index != -1) {
            adapter.notifyItemChanged(index);
        }
    }

    @Override
    public void onItemLongClick(final SearchResult item) {
        final CharSequence[] options = {"Open", "Details", "Compress"};
        new AlertDialog.Builder(this)
            .setItems(options, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        openFileViewer(item);
                    } else if (which == 1) {
                        List<File> files = getFilesFromResults(Collections.singletonList(item));
                        showDetailsDialog(files);
                    } else if (which == 2) {
                        List<File> files = getFilesFromResults(Collections.singletonList(item));
                        if (!files.isEmpty() && files.get(0).getParentFile() != null) {
                            ArchiveUtils.startCompression(SearchActivity.this, files, files.get(0).getParentFile());
                            Toast.makeText(SearchActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            })
            .show();
    }


    @Override
    public void onHeaderCheckedChanged(DateHeader header, boolean isChecked) {
        header.setChecked(isChecked);
        
        // Find the index of this header in the MASTER list to update subsequent items
        int masterIndex = masterList.indexOf(header);
        if (masterIndex == -1) return;

        // Update selection state for all items under this header in master list
        for (int i = masterIndex + 1; i < masterList.size(); i++) {
            Object currentItem = masterList.get(i);
            if (currentItem instanceof SearchResult) {
                ((SearchResult) currentItem).setExcluded(!isChecked);
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }
        
        // Refresh display list to show changes
        adapter.notifyDataSetChanged();
    }
    
    // NEW: Handle minimizing/expanding headers
    @Override
    public void onHeaderClick(DateHeader header) {
        // Toggle state
        header.setExpanded(!header.isExpanded());
        // Rebuild the visible list based on new expansion states
        rebuildDisplayList();
    }

    private void updateHeaderStateForItem(SearchResult item) {
        // We must check the MASTER list logic here
        int itemIndex = masterList.indexOf(item);
        if (itemIndex == -1) return;

        DateHeader parentHeader = null;
        for (int i = itemIndex - 1; i >= 0; i--) {
            if (masterList.get(i) instanceof DateHeader) {
                parentHeader = (DateHeader) masterList.get(i);
                break;
            }
        }
        if (parentHeader == null) return;

        boolean allIncluded = true;
        int headerIndex = masterList.indexOf(parentHeader);
        for (int i = headerIndex + 1; i < masterList.size(); i++) {
            Object currentItem = masterList.get(i);
            if (currentItem instanceof SearchResult) {
                if (((SearchResult) currentItem).isExcluded()) {
                    allIncluded = false;
                    break;
                }
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }
        parentHeader.setChecked(allIncluded);
        
        // Notify adapter if header is visible
        int displayIndex = displayList.indexOf(parentHeader);
        if (displayIndex != -1) {
            adapter.notifyItemChanged(displayIndex);
        }
    }

    public static class SearchResult {
        private final Uri uri;
        private final long mediaStoreId;
        private final long lastModifiedForGrouping;
        private final String displayName;
        private final String path;
        private boolean isExcluded;

        public SearchResult(Uri uri, long mediaStoreId, long lastModifiedMillis, String displayName, String path) {
            this.uri = uri;
            this.mediaStoreId = mediaStoreId;
            this.lastModifiedForGrouping = lastModifiedMillis;
            this.displayName = displayName;
            this.path = path;
            this.isExcluded = true;
        }
        public Uri getUri() { return uri; }
        public long getLastModified() { return mediaStoreId; }
        public long getLastModifiedForGrouping() { return lastModifiedForGrouping; }
        public String getDisplayName() { return displayName; }
        public String getPath() { return path; }
        public boolean isExcluded() { return isExcluded; }
        public void setExcluded(boolean excluded) { isExcluded = excluded; }
    }

    private static class QueryParameters {
        String folderPath;
        long startTimeSeconds = -1;
        long endTimeSeconds = -1;
        void setDateRange(long start, long end) { this.startTimeSeconds = start; this.endTimeSeconds = end; }
    }

    private class PinchZoomListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            int previousSpanCount = currentSpanCount;
            if (scaleFactor > 1.05f) currentSpanCount = Math.max(MIN_SPAN_COUNT, currentSpanCount - 1);
            else if (scaleFactor < 0.95f) currentSpanCount = Math.min(MAX_SPAN_COUNT, currentSpanCount + 1);

            if (previousSpanCount != currentSpanCount) {
                gridLayoutManager.setSpanCount(currentSpanCount);
                adapter.notifyDataSetChanged();
            }
            return true;
        }
    }

    private int getFileCategory(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase(Locale.ROOT);
        }

        List<String> imageExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");
        List<String> videoExtensions = Arrays.asList("mp4", "3gp", "mkv", "webm", "avi");
        List<String> audioExtensions = Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac");
        List<String> docExtensions = Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "json", "xml", "html", "js", "css", "java", "kt", "py", "c", "cpp", "h", "cs", "php", "rb", "go", "swift", "sh", "bat", "ps1", "ini", "cfg", "conf", "md", "prop", "gradle", "pro", "sql");

        if (imageExtensions.contains(extension)) return CATEGORY_IMAGES;
        if (videoExtensions.contains(extension)) return CATEGORY_VIDEOS;
        if (audioExtensions.contains(extension)) return CATEGORY_AUDIO;
        if (docExtensions.contains(extension)) return CATEGORY_DOCS;
        return CATEGORY_OTHER;
    }

    private void openFileViewer(final SearchResult item) {
        new AsyncTask<Void, Void, Intent>() {
            @Override
            protected Intent doInBackground(Void... voids) {
                File file = new File(item.getPath());
                String path = file.getAbsolutePath();
                String name = file.getName();
                int category = getFileCategory(name);
                Intent intent = null;

                if (category == CATEGORY_IMAGES || category == CATEGORY_VIDEOS || category == CATEGORY_AUDIO) {
                    ArrayList<String> fileList = getSiblingFilesForViewer(file, category);
                    int currentIndex = fileList.indexOf(path);
                    if (currentIndex == -1) {
						return null;
                    }

                    if (category == CATEGORY_IMAGES) {
                        intent = new Intent(SearchActivity.this, ImageViewerActivity.class);
                        intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(ImageViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_VIDEOS) {
                        intent = new Intent(SearchActivity.this, VideoViewerActivity.class);
                        intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_AUDIO) {
                        intent = new Intent(SearchActivity.this, AudioPlayerActivity.class);
                        intent.putStringArrayListExtra(AudioPlayerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(AudioPlayerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    }
                } else {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        intent = new Intent(SearchActivity.this, PdfViewerActivity.class);
                    } else {
                        intent = new Intent(SearchActivity.this, TextViewerActivity.class);
                    }
                    intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path);
                }
                return intent;
            }

            @Override
            protected void onPostExecute(Intent intent) {
                if (intent != null) {
                    startActivity(intent);
                } else {
                    Toast.makeText(SearchActivity.this, "Error opening file.", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    private ArrayList<String> getSiblingFilesForViewer(File currentFile, final int category) {
        ArrayList<String> siblingFiles = new ArrayList<>();
        File parentDir = currentFile.getParentFile();
        if (parentDir == null || !parentDir.isDirectory()) {
            siblingFiles.add(currentFile.getAbsolutePath());
            return siblingFiles;
        }

        // Use masterList here to ensure we navigate through all available files
        for (Object item : masterList) {
            if (item instanceof SearchResult) {
                SearchResult result = (SearchResult) item;
                if (result.getPath() != null) {
                    File file = new File(result.getPath());
                    if (file.getParentFile() != null && file.getParentFile().equals(parentDir)) {
                        if (getFileCategory(file.getName()) == category) {
                            siblingFiles.add(file.getAbsolutePath());
                        }
                    }
                }
            }
        }

        Collections.sort(siblingFiles);
        return siblingFiles;
    }

    private void setupBroadcastReceivers() {
        deleteCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int deletedCount = intent.getIntExtra(DeleteService.EXTRA_DELETED_COUNT, 0);
                Toast.makeText(SearchActivity.this, "Deletion complete. " + deletedCount + " files removed.", Toast.LENGTH_LONG).show();

                deletionProgressLayout.setVisibility(View.GONE);
                executeQuery(searchInput.getText().toString());
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(deleteCompletionReceiver, new IntentFilter(DeleteService.ACTION_DELETE_COMPLETE));

        compressionBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean success = intent.getBooleanExtra(CompressionService.EXTRA_SUCCESS, false);
                if (success) {
                    executeQuery(searchInput.getText().toString());
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(compressionBroadcastReceiver, new IntentFilter(CompressionService.ACTION_COMPRESSION_COMPLETE));
    }

    @Override
    protected void onDestroy() {
        if (deleteCompletionReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(deleteCompletionReceiver);
        }
        if (compressionBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(compressionBroadcastReceiver);
        }
        super.onDestroy();
    }

    private void showFileOperationsDialog() {
        final List<SearchResult> selectedResults = new ArrayList<>();
        // Use masterList to ensure selections in collapsed sections are counted
        for (Object item : masterList) {
            if (item instanceof SearchResult) {
                SearchResult result = (SearchResult) item;
                if (!result.isExcluded()) {
                    selectedResults.add(result);
                }
            }
        }

        if (selectedResults.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<File> selectedFiles = getFilesFromResults(selectedResults);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_file_operations, null);
        builder.setView(dialogView);
        final AlertDialog dialog = builder.create();

        Button detailsButton = dialogView.findViewById(R.id.button_details);
        Button sendToDropZoneButton = dialogView.findViewById(R.id.button_send_to_drop_zone);
        Button compressButton = dialogView.findViewById(R.id.button_compress);
        Button copyButton = dialogView.findViewById(R.id.button_copy);
        Button moveButton = dialogView.findViewById(R.id.button_move);
        Button hideButton = dialogView.findViewById(R.id.button_hide);
        Button deleteButton = dialogView.findViewById(R.id.button_delete_permanently);
        Button recycleButton = dialogView.findViewById(R.id.button_move_to_recycle);

        copyButton.setVisibility(View.GONE);
        moveButton.setVisibility(View.GONE);

        detailsButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showDetailsDialog(selectedFiles);
					dialog.dismiss();
				}
			});
        
        sendToDropZoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedFiles.size() == 1) {
                    showSendToDropDialog(selectedFiles.get(0));
                } else {
                    Toast.makeText(SearchActivity.this, "HFM Drop currently supports sending a single file at a time.", Toast.LENGTH_LONG).show();
                }
                dialog.dismiss();
            }
        });

        compressButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (!selectedFiles.isEmpty() && selectedFiles.get(0).getParentFile() != null) {
						ArchiveUtils.startCompression(SearchActivity.this, selectedFiles, selectedFiles.get(0).getParentFile());
						Toast.makeText(SearchActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
					} else {
						Toast.makeText(SearchActivity.this, "Cannot determine destination for archive.", Toast.LENGTH_SHORT).show();
					}
					dialog.dismiss();
				}
			});

        hideButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					hideFiles(selectedResults);
					dialog.dismiss();
				}
			});

        deleteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					initiateDeletionProcess();
					dialog.dismiss();
				}
			});

        recycleButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					moveToRecycleBin(selectedResults);
					dialog.dismiss();
				}
			});

        dialog.show();
    }

    private List<File> getFilesFromResults(List<SearchResult> results) {
        List<File> files = new ArrayList<>();
        for (SearchResult result : results) {
            if (result.getPath() != null) {
                files.add(new File(result.getPath()));
            }
        }
        return files;
    }

    private void showDetailsDialog(final List<File> files) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_details, null);
        builder.setView(dialogView);
        builder.setCancelable(false);

        final TextView basicDetailsText = dialogView.findViewById(R.id.details_text_basic);
        final TextView aiDetailsText = dialogView.findViewById(R.id.details_text_ai);
        final ProgressBar progressBar = dialogView.findViewById(R.id.details_progress_bar);
        final Button moreButton = dialogView.findViewById(R.id.details_button_more);
        final Button copyButton = dialogView.findViewById(R.id.details_button_copy);
        final Button closeButton = dialogView.findViewById(R.id.details_button_close);

        final AlertDialog dialog = builder.create();

        if (files.size() == 1) {
            File file = files.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("Name: ").append(file.getName()).append("\n");
            sb.append("Path: ").append(file.getAbsolutePath()).append("\n");
            sb.append("Size: ").append(Formatter.formatFileSize(this, file.length())).append("\n");
            sb.append("Last Modified: ").append(new Date(file.lastModified()).toString());
            basicDetailsText.setText(sb.toString());
        } else {
            long totalSize = 0;
            for (File file : files) {
                totalSize += file.length();
            }
            basicDetailsText.setText("Items selected: " + files.size() + "\nTotal size: " + Formatter.formatFileSize(this, totalSize));
        }

        final GeminiAnalyzer analyzer = new GeminiAnalyzer(this, aiDetailsText, progressBar, copyButton);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        moreButton.setEnabled(ApiKeyManager.getApiKey(this) != null && isConnected);

        moreButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					analyzer.analyze(files);
				}
			});

        copyButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					ClipData clip = ClipData.newPlainText("AI Summary", aiDetailsText.getText());
					clipboard.setPrimaryClip(clip);
					Toast.makeText(SearchActivity.this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show();
				}
			});

        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        dialog.show();
    }

    private File getFileFromResult(SearchResult result) {
        if ("file".equals(result.getUri().getScheme())) {
            return new File(result.getUri().getPath());
        }
        String path = result.getPath();
        if (path != null) {
            return new File(path);
        }
        return null;
    }
    
    private void showSendToDropDialog(final File fileToSend) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_send_drop, null);
        final EditText receiverUsernameInput = dialogView.findViewById(R.id.edit_text_receiver_username);

        builder.setView(dialogView)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String receiverUsername = receiverUsernameInput.getText().toString().trim();
                        if (receiverUsername.isEmpty()) {
                            Toast.makeText(SearchActivity.this, "Receiver username cannot be empty.", Toast.LENGTH_SHORT).show();
                        } else {
                            showSenderWarningDialog(receiverUsername, fileToSend);
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        builder.create().show();
    }
    
    private void showSenderWarningDialog(final String receiverUsername, final File fileToSend) {
        final String secretNumber = generateSecretNumber();

        new AlertDialog.Builder(this)
            .setTitle("Important: Connection Stability")
            .setMessage("You are about to act as a temporary server for this file transfer.\n\n"
                    + "Please keep the app open and maintain a stable internet connection until the transfer is complete.\n\n"
                    + "Your Secret Number for this transfer is:\n" + secretNumber + "\n\nShare this number with the receiver.")
            .setPositiveButton("I Understand, Start Sending", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startSenderService(receiverUsername, secretNumber, fileToSend);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void startSenderService(String receiverUsername, String secretNumber, File fileToSend) {
        if (fileToSend == null || !fileToSend.exists()) {
            Toast.makeText(this, "Error: File to send does not exist.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, SenderService.class);
        intent.setAction(SenderService.ACTION_START_SEND);
        intent.putExtra(SenderService.EXTRA_FILE_PATH, fileToSend.getAbsolutePath());
        intent.putExtra(SenderService.EXTRA_RECEIVER_USERNAME, receiverUsername);
        intent.putExtra(SenderService.EXTRA_SECRET_NUMBER, secretNumber);
        ContextCompat.startForegroundService(this, intent);
    }
    
    private String generateSecretNumber() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private class MoveToRecycleTask extends AsyncTask<Void, Void, List<SearchResult>> {
        private AlertDialog progressDialog;
        private List<SearchResult> resultsToMove;
        private Context context;

        public MoveToRecycleTask(List<SearchResult> resultsToMove) {
            this.resultsToMove = resultsToMove;
            this.context = SearchActivity.this;
        }

        @Override
        protected void onPreExecute() {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_progress_simple, null);
            builder.setView(dialogView);
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected List<SearchResult> doInBackground(Void... voids) {
            File recycleBinDir = new File(Environment.getExternalStorageDirectory(), "HFMRecycleBin");
            if (!recycleBinDir.exists()) {
                if (!recycleBinDir.mkdir()) {
                    return new ArrayList<>();
                }
            }

            List<SearchResult> movedResults = new ArrayList<>();
            for (SearchResult result : resultsToMove) {
                if (result.getPath() == null) continue;

                File sourceFile = new File(result.getPath());
                if (sourceFile.exists()) {
                    File destFile = new File(recycleBinDir, sourceFile.getName());

                    if (destFile.exists()) {
                        String name = sourceFile.getName();
                        String extension = "";
                        int dotIndex = name.lastIndexOf(".");
                        if (dotIndex > 0) {
                            extension = name.substring(dotIndex);
                            name = name.substring(0, dotIndex);
                        }
                        destFile = new File(recycleBinDir, name + "_" + System.currentTimeMillis() + extension);
                    }

                    boolean moveSuccess = false;

                    if (sourceFile.renameTo(destFile)) {
                        moveSuccess = true;
                    } else {
                        Log.w(TAG, "renameTo failed for " + sourceFile.getAbsolutePath() + ". Falling back to copy-delete.");
                        if (copyFile(sourceFile, destFile)) {
                            if (StorageUtils.deleteFile(context, sourceFile)) {
                                moveSuccess = true;
                            } else {
                                Log.e(TAG, "Failed to delete original file " + sourceFile.getAbsolutePath() + " after copy. Deleting copied file to prevent duplication.");
                                destFile.delete();
                                moveSuccess = false;
                            }
                        } else {
                            Log.e(TAG, "Copy-delete fallback failed to copy file: " + sourceFile.getAbsolutePath());
                            moveSuccess = false;
                        }
                    }

                    if (moveSuccess) {
                        movedResults.add(result);
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(sourceFile)));
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)));
                    } else {
                        Log.w(TAG, "Failed to move file to recycle bin: " + sourceFile.getAbsolutePath());
                    }
                }
            }
            return movedResults;
        }

        @Override
        protected void onPostExecute(List<SearchResult> movedResults) {
            progressDialog.dismiss();

            if (movedResults.isEmpty() && !resultsToMove.isEmpty()) {
                Toast.makeText(context, "Failed to move some or all files.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, movedResults.size() + " file(s) moved to Recycle Bin.", Toast.LENGTH_LONG).show();
            }

            if (!movedResults.isEmpty()) {
                // Update master list and refresh UI
                masterList.removeAll(movedResults);
                rebuildDisplayList();
            }
        }

        private boolean copyFile(File source, File destination) {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(source);
                out = new FileOutputStream(destination);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Standard file copy failed, attempting with StorageUtils", e);
                return StorageUtils.copyFile(context, source, destination);
            } finally {
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}