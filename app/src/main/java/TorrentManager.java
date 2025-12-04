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
 * TorrentManager with Brute-Force Reflection + Full Reflection for params.
 * Fixes compile errors (AddTorrentParams) and runtime errors (constructor not found).
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
                    } else if (t instanceof Integer) {
                        int code = (Integer) t;
                        if (code == 7) handleStateUpdate((StateUpdateAlert) alert);
                        else if (code == 15) handleTorrentFinished((TorrentFinishedAlert) alert);
                        else if (code == 13) handleTorrentError((TorrentErrorAlert) alert);
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
            broadcastError("Library Error: " + errorMsg);
        }
        cleanupTorrent(handle);
    }

    public String startSeeding(File dataFile, String dropRequestId) {
        if (dataFile == null || !dataFile.exists()) return null;

        File torrentFile = null;
        try {
            torrentFile = createTorrentFile(dataFile);
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);

            TorrentHandle handle = tryDownloadViaReflection(torrentInfo, dataFile.getParentFile());

            // If direct download returned null, try fallback via AddTorrentParams (Reflective)
            if (handle == null) {
                Object params = null;
                try {
                    // Fully reflective instantiation to avoid "cannot find symbol"
                    Class<?> atpClass = Class.forName("org.libtorrent4j.AddTorrentParams");
                    params = atpClass.newInstance();
                    
                    callMethodIfExists(params, "setTorrentInfo", new Class[]{TorrentInfo.class}, new Object[]{torrentInfo});
                    callMethodIfExists(params, "setSavePath", new Class[]{String.class}, new Object[]{dataFile.getParentFile().getAbsolutePath()});
                } catch (Throwable t) {
                    broadcastError("Reflection Init Params Error: " + t.toString());
                }

                if (params != null) {
                    handle = tryAddTorrentParams(params, dataFile.getParentFile());
                }
            }

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                String infoHex = extractInfoHashHexFromHandle(handle);
                if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                
                String magnetLink = makeMagnetUriSafe(handle);
                Log.d(TAG, "Seeding started. Magnet: " + magnetLink);
                return magnetLink;
            } else {
                broadcastError("Failed to get valid TorrentHandle. Reflection failed to add torrent.");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Seeding failed", e);
            broadcastError("Exception in startSeeding: " + e.toString());
            return null;
        } finally {
            if (torrentFile != null) torrentFile.delete();
        }
    }

    private File createTorrentFile(File dataFile) throws IOException {
        file_storage fs = new file_storage();
        boolean added = false;

        try {
            callStaticMethodIfExists(libtorrent.class, "add_files", new Class[]{file_storage.class, String.class}, new Object[]{fs, dataFile.getAbsolutePath()});
            added = true;
        } catch (Throwable t) { }

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
            } catch (Throwable t) { }
        }

        if (!added) {
            String msg = "libtorrent.add_files(...) not available via Reflection.";
            broadcastError(msg);
            throw new IOException(msg);
        }

        create_torrent ct = null;
        String constructorError = "No constructor error yet";

        // BRUTE FORCE CONSTRUCTOR FINDER
        try {
            Constructor<?>[] constructors = create_torrent.class.getConstructors();
            for (Constructor<?> cons : constructors) {
                Class<?>[] params = cons.getParameterTypes();
                
                // Case 1: (file_storage)
                if (params.length == 1 && params[0].isAssignableFrom(file_storage.class)) {
                    ct = (create_torrent) cons.newInstance(fs);
                    break;
                }
                
                // Case 2: (file_storage, int)
                if (params.length == 2 && params[0].isAssignableFrom(file_storage.class) && (params[1] == int.class || params[1] == Integer.class)) {
                    ct = (create_torrent) cons.newInstance(fs, 0); 
                    break;
                }

                // Case 3: (file_storage, int, int)
                if (params.length == 3 && params[0].isAssignableFrom(file_storage.class) 
                    && (params[1] == int.class || params[1] == Integer.class)
                    && (params[2] == int.class || params[2] == Integer.class)) {
                    ct = (create_torrent) cons.newInstance(fs, 0, 0); 
                    break;
                }
            }
        } catch (Throwable t) {
            constructorError = t.toString();
        }

        if (ct == null) {
            String msg = "create_torrent constructor not found. Error: " + constructorError;
            broadcastError(msg);
            throw new IOException(msg);
        }

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
                torrentBytes = attemptByteVectorToBytesReflective(bencoded);
            }

            if (torrentBytes == null) throw new IOException("Failed to convert bencoded data to bytes.");

            File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
                fos.write(torrentBytes);
            }
            return tempTorrent;

        } catch (Throwable t) {
            broadcastError("Bencode Failed: " + t.toString());
            throw new IOException("Failed to bencode generated torrent: " + t.getMessage(), t);
        }
    }

    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            byte[] torrentData = null;
            try {
                Object res = callMethodIfExists(sessionManager, "fetchMagnet", new Class[]{String.class, int.class}, new Object[]{magnetLink, 30});
                if (res == null) {
                     res = callMethodIfExists(sessionManager, "fetchMagnet", new Class[]{String.class, int.class, File.class}, new Object[]{magnetLink, 30, saveDirectory});
                }
                if (res instanceof byte[]) torrentData = (byte[]) res;
            } catch (Throwable t) { }

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
            
            try {
                Class<?> atpClass = Class.forName("org.libtorrent4j.AddTorrentParams");
                Object params = callStaticMethodIfExists(atpClass, "parseMagnetUri", new Class[]{String.class}, new Object[]{magnetLink});
                if (params != null) {
                     TorrentHandle handle = tryAddTorrentParams(params, saveDirectory);
                     if (handle != null && handle.isValid()) {
                        activeTorrents.put(dropRequestId, handle);
                        String infoHex = extractInfoHashHexFromHandle(handle);
                        if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                     }
                }
            } catch (ClassNotFoundException ignored) { }

        } catch (Exception e) {
            Log.e(TAG, "Download start failed", e);
            broadcastError("Download Exception: " + e.toString());
        }
    }

    private void broadcastError(String errorMsg) {
        Log.e(TAG, "Broadcasting Critical Error: " + errorMsg);
        Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
        errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "INTERNAL JAVA ERROR: " + errorMsg);
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(infoHex);
        }

        try {
            callMethodIfExists(sessionManager, "remove", new Class[]{TorrentHandle.class}, new Object[]{handle});
        } catch (Throwable t) {
            try {
                callMethodIfExists(sessionManager, "removeTorrent", new Class[]{TorrentHandle.class}, new Object[]{handle});
            } catch (Throwable t2) { }
        }
    }

    public void stopSession() {
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null;
    }

    // --- Reflection Helpers ---

    private TorrentHandle tryDownloadViaReflection(TorrentInfo ti, File saveDir) {
        try {
            Method m = findMethod(SessionManager.class, "download", new Class[]{TorrentInfo.class, File.class});
            if (m != null) {
                Object r = m.invoke(sessionManager, ti, saveDir);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                if (r == null) { 
                    Object ih = callMethodIfExists(ti, "infoHash");
                    if (ih != null) return (TorrentHandle) callMethodIfExists(sessionManager, "find", new Class[]{ih.getClass()}, new Object[]{ih});
                }
            }
        } catch (Throwable t) { }

        try {
            Method m2 = findMethod(sessionManager.getClass(), "download", new Class[]{String.class, File.class, Object.class});
            if (m2 != null) {
                String magnetUri = makeMagnetUriSafe(null); // Fallback string gen
                // Actually invoke magnet uri logic if possible
                Object ih = callMethodIfExists(ti, "infoHash");
                if (ih != null) {
                    magnetUri = "magnet:?xt=urn:btih:" + infoHashToHexSafe(ih);
                }
                
                if (magnetUri != null) {
                    Object r = m2.invoke(sessionManager, magnetUri, saveDir, null);
                    if (r instanceof TorrentHandle) return (TorrentHandle) r;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // Helper using Object to avoid import errors
    private TorrentHandle tryAddTorrentParams(Object params, File saveDir) {
        try {
            Method m = findMethod(sessionManager.getClass(), "download", new Class[]{params.getClass()});
            if (m != null) {
                Object r = m.invoke(sessionManager, params);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else if (r == null) {
                    Object ti = callMethodIfExists(params, "torrentInfo");
                    String hex = infoHashObjectToHexSafe(ti);
                    TorrentHandle h = findHandleByInfoHex(hex);
                    if (h != null) return h;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Method mAdd = findMethod(sessionManager.getClass(), "addTorrent", new Class[]{params.getClass()});
            if (mAdd != null) {
                Object r = mAdd.invoke(sessionManager, params);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else {
                    String hex = infoHashFromParamsHex(params);
                    return findHandleByInfoHex(hex);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String infoHashFromParamsHex(Object params) {
        try {
            Object ti = callMethodIfExists(params, "torrentInfo");
            return infoHashObjectToHexSafe(ti);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private TorrentHandle findHandleByInfoHex(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        for (Map.Entry<String, TorrentHandle> e : activeTorrents.entrySet()) {
            TorrentHandle th = e.getValue();
            String hHex = extractInfoHashHexFromHandle(th);
            if (hHex != null && hHex.equalsIgnoreCase(hex)) return th;
        }
        try {
            Object session = sessionManager;
            Method getTorrents = findMethod(session.getClass(), "getTorrents", new Class[]{});
            if (getTorrents != null) {
                Object list = getTorrents.invoke(session);
                if (list instanceof java.util.Collection) {
                    for (Object o : ((java.util.Collection) list)) {
                        try {
                            String hx = infoHashObjectToHexSafe(callMethodIfExists(o, "infoHash"));
                            if (hx != null && hx.equalsIgnoreCase(hex)) {
                                if (o instanceof TorrentHandle) return (TorrentHandle) o;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractInfoHashHexFromStatus(TorrentStatus status) {
        if (status == null) return null;
        try {
            Object ih = callMethodIfExists(status, "infoHash");
            String hex = infoHashToHexSafe(ih);
            if (hex != null) return hex;
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

    private long safeLong(Object obj, String methodName) {
        try {
            Object res = callMethodIfExists(obj, methodName);
            if (res instanceof Number) return ((Number) res).longValue();
        } catch (Throwable ignored) { }
        return 0L;
    }

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
    
    private Object callMethodSafely(Object target, String name) {
        try { return callMethodIfExists(target, name); } catch (Throwable t) { return null; }
    }

    private String infoHashObjectToHexSafe(Object ihObj) {
        if (ihObj == null) return null;
        try {
            Method m = findMethod(ihObj.getClass(), "toHex", new Class[]{});
            if (m != null) {
                Object r = m.invoke(ihObj);
                if (r instanceof String) return (String) r;
            }
        } catch (Throwable ignored) {}
        try {
            String s = ihObj.toString();
            if (s != null && s.length() > 0) return s;
        } catch (Throwable ignored) {}
        try {
            Method m = findMethod(ihObj.getClass(), "getBytes", new Class[]{});
            if (m != null) {
                Object r = m.invoke(ihObj);
                if (r instanceof byte[]) return bytesToHex((byte[]) r);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String infoHashToHexSafe(Object ih) {
        return infoHashObjectToHexSafe(ih);
    }

    // MISSING METHOD RESTORED
    private String makeMagnetUriSafe(TorrentHandle handle) {
        if (handle == null) return null;
        try {
            // Try standard method
            return handle.makeMagnetUri();
        } catch (Throwable t) {
            // Fallback: construct manually if possible
            String hex = extractInfoHashHexFromHandle(handle);
            if (hex != null) return "magnet:?xt=urn:btih:" + hex;
        }
        return null;
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

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}