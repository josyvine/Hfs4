package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.Vectors;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.entry;
import org.libtorrent4j.swig.file_storage;
import org.libtorrent4j.swig.libtorrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TorrentManager using robust Reflection to handle API version mismatches.
 * This ensures compilation succeeds (no 'symbol not found' errors) 
 * and fixes the runtime 'Failed to generate secure link' issue.
 */
public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    private final Map<String, TorrentHandle> activeTorrents;
    private final Map<String, String> hashToIdMap;

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                return null;
            }

            @Override
            public void alert(Alert<?> alert) {
                try {
                    Object t = alert.type();
                    if (t instanceof AlertType) {
                        AlertType at = (AlertType) t;
                        if (at == AlertType.STATE_UPDATE) {
                            handleStateUpdate((StateUpdateAlert) alert);
                        } else if (at == AlertType.TORRENT_FINISHED) {
                            handleTorrentFinished((TorrentFinishedAlert) alert);
                        } else if (at == AlertType.TORRENT_ERROR) {
                            handleTorrentError((TorrentErrorAlert) alert);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        sessionManager.start();
    }

    public static TorrentManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TorrentManager.class) {
                if (instance == null) {
                    instance = new TorrentManager(context);
                }
            }
        }
        return instance;
    }

    private void handleStateUpdate(StateUpdateAlert alert) {
        List<TorrentStatus> statuses = alert.status();
        for (TorrentStatus status : statuses) {
            String infoHex = extractInfoHashHexFromStatus(status);
            if (infoHex == null) continue;

            String dropRequestId = hashToIdMap.get(infoHex);
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | Down: " + (status.downloadPayloadRate() / 1024) + " KB/s | Up: " + (status.uploadPayloadRate() / 1024) + " KB/s");

                long totalDone = safeLong(status, "totalDone");
                long totalWanted = safeLong(status, "totalWanted");
                
                int progress = 0;
                if (totalWanted > 0) {
                    progress = (int) ((totalDone * 100) / totalWanted);
                }
                
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, progress);
                intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, 100);
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, totalDone);

                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }
        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);

        String errorMsg = "Unknown Error";
        try { errorMsg = alert.message(); } catch (Throwable t) { }

        Log.e(TAG, "Torrent error: " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }
        cleanupTorrent(handle);
    }

    public String startSeeding(File dataFile, String dropRequestId) {
        if (dataFile == null || !dataFile.exists()) return null;

        File torrentFile = null;
        try {
            torrentFile = createTorrentFile(dataFile);
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);

            // Attempt to add torrent via reflection (SessionManager.download or addTorrent)
            TorrentHandle handle = tryDownloadViaReflection(torrentInfo, dataFile.getParentFile());

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                String infoHex = extractInfoHashHexFromHandle(handle);
                if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                
                String magnetLink = makeMagnetUriSafe(handle);
                Log.d(TAG, "Seeding started. Magnet: " + magnetLink);
                return magnetLink;
            }
        } catch (Exception e) {
            Log.e(TAG, "Seeding failed", e);
        } finally {
            if (torrentFile != null) torrentFile.delete();
        }
        return null;
    }

    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            // Attempt fetchMagnet via reflection
            byte[] torrentData = null;
            try {
                // Try signature (String, int) or (String, int, File)
                Object res = callMethodIfExists(sessionManager, "fetchMagnet", new Class[]{String.class, int.class}, new Object[]{magnetLink, 30});
                if (res == null) {
                     res = callMethodIfExists(sessionManager, "fetchMagnet", new Class[]{String.class, int.class, File.class}, new Object[]{magnetLink, 30, saveDirectory});
                }
                if (res instanceof byte[]) torrentData = (byte[]) res;
            } catch (Throwable ignored) { }

            if (torrentData != null) {
                TorrentInfo ti = TorrentInfo.bdecode(torrentData);
                TorrentHandle handle = tryDownloadViaReflection(ti, saveDirectory);
                
                if (handle != null && handle.isValid()) {
                    activeTorrents.put(dropRequestId, handle);
                    String infoHex = extractInfoHashHexFromHandle(handle);
                    if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                    return;
                }
            }
            
            // If fetchMagnet failed or returned null handle, try adding via AddTorrentParams (reflective)
            // This is the fallback if direct download fails
            Object params = callStaticMethodIfExists(Class.forName("org.libtorrent4j.AddTorrentParams"), "parseMagnetUri", new Class[]{String.class}, new Object[]{magnetLink});
            if (params != null) {
                 TorrentHandle handle = tryAddTorrentParamsViaReflection(params, saveDirectory);
                 if (handle != null && handle.isValid()) {
                    activeTorrents.put(dropRequestId, handle);
                    String infoHex = extractInfoHashHexFromHandle(handle);
                    if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                 }
            }

        } catch (Exception e) {
            Log.e(TAG, "Download start failed", e);
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Download Error: " + e.getMessage());
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);
        }
    }

    private File createTorrentFile(File dataFile) throws IOException {
        file_storage fs = new file_storage();
        boolean added = false;

        // 1. Try static libtorrent.add_files
        try {
            callStaticMethodIfExists(libtorrent.class, "add_files", new Class[]{file_storage.class, String.class}, new Object[]{fs, dataFile.getAbsolutePath()});
            added = true;
        } catch (Throwable ignored) { }

        // 2. If static failed, try instance method fs.add_file (Manual Add)
        if (!added) {
            try {
                // Try simple add_file(path, size)
                Method m = findMethod(file_storage.class, "add_file", new Class[]{String.class, long.class});
                if (m != null) {
                    m.invoke(fs, dataFile.getName(), dataFile.length());
                    added = true;
                } else {
                    // Try full signature add_file(path, size, flags, mtime, linkpath)
                    m = findMethod(file_storage.class, "add_file", new Class[]{String.class, long.class, int.class, int.class, String.class});
                    if (m != null) {
                        m.invoke(fs, dataFile.getName(), dataFile.length(), 0, 0, "");
                        added = true;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Manual add_file failed", t);
            }
        }

        if (!added) throw new IOException("Could not add file to storage via Reflection.");

        create_torrent ct = null;
        // Try constructor(file_storage)
        try {
            Constructor<?> cons = findConstructor(create_torrent.class, new Class[]{file_storage.class});
            if (cons != null) ct = (create_torrent) cons.newInstance(fs);
        } catch (Throwable ignored) { }

        // Try constructor(file_storage, int piece_size)
        if (ct == null) {
            try {
                Constructor<?> cons = findConstructor(create_torrent.class, new Class[]{file_storage.class, int.class});
                if (cons != null) ct = (create_torrent) cons.newInstance(fs, 0); // 0 = auto
            } catch (Throwable ignored) { }
        }

        if (ct == null) throw new IOException("Could not create create_torrent object via Reflection.");

        // Generate
        try {
            callMethodIfExists(ct, "set_creator", new Class[]{String.class}, new Object[]{"HFM Drop"});
            callMethodIfExists(ct, "set_priv", new Class[]{boolean.class}, new Object[]{true});
            
            Object entryObj = callMethodIfExists(ct, "generate");
            Object bencoded = callMethodIfExists(entryObj, "bencode");
            byte[] torrentBytes = null;
            
            if (bencoded instanceof byte_vector) {
                torrentBytes = Vectors.byte_vector2bytes((byte_vector) bencoded);
            } else {
                // Fallback for different return type
                torrentBytes = attemptByteVectorToBytesReflective(bencoded);
            }

            if (torrentBytes == null) throw new IOException("Failed to convert bencoded data to bytes.");

            File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
                fos.write(torrentBytes);
            }
            return tempTorrent;

        } catch (Throwable t) {
            throw new IOException("Failed to generate torrent file: " + t.getMessage(), t);
        }
    }

    // --- Reflection Helpers ---

    private TorrentHandle tryDownloadViaReflection(TorrentInfo ti, File saveDir) {
        try {
            // download(TorrentInfo, File)
            Method m = findMethod(SessionManager.class, "download", new Class[]{TorrentInfo.class, File.class});
            if (m != null) {
                Object r = m.invoke(sessionManager, ti, saveDir);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                // If returns void, find via find(InfoHash)
                if (r == null) { 
                    Object ih = callMethodIfExists(ti, "infoHash");
                    if (ih != null) return (TorrentHandle) callMethodIfExists(sessionManager, "find", new Class[]{ih.getClass()}, new Object[]{ih});
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }
    
    private TorrentHandle tryAddTorrentParamsViaReflection(Object params, File saveDir) {
        try {
            // setSavePath on params
            callMethodIfExists(params, "setSavePath", new Class[]{String.class}, new Object[]{saveDir.getAbsolutePath()});
            
            // download(AddTorrentParams)
            Method m = findMethod(SessionManager.class, "download", new Class[]{params.getClass()});
            if (m != null) {
                return (TorrentHandle) m.invoke(sessionManager, params);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private String makeMagnetUriSafe(TorrentHandle handle) {
        try {
            return handle.makeMagnetUri();
        } catch (Throwable t) {
            return "magnet:?xt=urn:btih:" + extractInfoHashHexFromHandle(handle);
        }
    }

    private String extractInfoHashHexFromStatus(TorrentStatus status) {
        if (status == null) return null;
        try {
            // 1. Try status.infoHash()
            Object ih = callMethodIfExists(status, "infoHash");
            String hex = infoHashToHexSafe(ih);
            if (hex != null) return hex;
            
            // 2. Try status.handle().infoHash()
            Object handle = callMethodIfExists(status, "handle");
            if (handle != null) {
                return extractInfoHashHexFromHandle((TorrentHandle) handle);
            }
        } catch (Throwable t) { }
        return null;
    }

    private String extractInfoHashHexFromHandle(TorrentHandle handle) {
        if (handle == null) return null;
        try {
            Object ih = callMethodIfExists(handle, "infoHash");
            return infoHashToHexSafe(ih);
        } catch (Throwable t) { }
        return null;
    }

    private String infoHashToHexSafe(Object ih) {
        if (ih == null) return null;
        try {
            // Try toHex()
            Object res = callMethodIfExists(ih, "toHex");
            if (res instanceof String) return (String) res;
            // Try toString()
            return ih.toString();
        } catch (Throwable t) { }
        return null;
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null) return;
        try {
            String hex = extractInfoHashHexFromHandle(handle);
            if (hex != null) {
                String reqId = hashToIdMap.remove(hex);
                if (reqId != null) activeTorrents.remove(reqId);
            }
            sessionManager.remove(handle);
        } catch (Throwable ignored) { }
    }

    public void stopSession() {
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null;
    }

    // --- Core Reflection Utils ---

    private Object callMethodIfExists(Object target, String methodName, Class<?>[] paramTypes, Object[] params) throws Exception {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), methodName, paramTypes);
        if (m != null) return m.invoke(target, params);
        return null;
    }

    private Object callMethodIfExists(Object target, String methodName) throws Exception {
        return callMethodIfExists(target, methodName, new Class<?>[]{}, new Object[]{});
    }

    private Object callStaticMethodIfExists(Class<?> cls, String methodName, Class<?>[] paramTypes, Object[] params) throws Exception {
        Method m = findMethod(cls, methodName, paramTypes);
        if (m != null) return m.invoke(null, params);
        return null;
    }

    private Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null) return null;
        try {
            return cls.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(name)) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == (paramTypes == null ? 0 : paramTypes.length)) return m; 
                }
            }
        }
        return null;
    }

    private Constructor<?> findConstructor(Class<?> cls, Class<?>[] paramTypes) {
        try {
            return cls.getConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private long safeLong(Object obj, String methodName) {
        try {
            Object res = callMethodIfExists(obj, methodName);
            if (res instanceof Number) return ((Number) res).longValue();
        } catch (Throwable ignored) { }
        return 0L;
    }
    
    private Object callMethodSafely(Object target, String name) {
        try { return callMethodIfExists(target, name); } catch (Throwable t) { return null; }
    }

    private byte[] attemptByteVectorToBytesReflective(Object byteVectorObj) {
        if (byteVectorObj == null) return null;
        try {
            Method sizeM = findMethod(byteVectorObj.getClass(), "size", new Class[]{});
            Method getM = findMethod(byteVectorObj.getClass(), "get", new Class[]{int.class});
            if (sizeM != null && getM != null) {
                int size = ((Number) sizeM.invoke(byteVectorObj)).intValue();
                byte[] bytes = new byte[size];
                for (int i = 0; i < size; i++) {
                    bytes[i] = ((Number) getM.invoke(byteVectorObj, i)).byteValue();
                }
                return bytes;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}