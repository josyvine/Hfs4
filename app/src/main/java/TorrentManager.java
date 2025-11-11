package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.InfoHash;
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
import org.libtorrent4j.swig.create_torrent;
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
 * TorrentManager with reflection-based fallbacks to handle micro-version API differences
 * across libtorrent4j releases. All original logic preserved.
 */
public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    // Use hex string keys for info-hash to avoid class mismatch between Sha1Hash/InfoHash across versions.
    private final Map<String, String> hashToIdMap; // infoHashHex -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events
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
                        if (code == 7) {
                            handleStateUpdate((StateUpdateAlert) alert);
                        } else if (code == 15) {
                            handleTorrentFinished((TorrentFinishedAlert) alert);
                        } else if (code == 13) {
                            handleTorrentError((TorrentErrorAlert) alert);
                        }
                    } else {
                        String tn = t == null ? "" : t.getClass().getSimpleName().toLowerCase();
                        if (tn.contains("state")) handleStateUpdate((StateUpdateAlert) alert);
                        else if (tn.contains("finished")) handleTorrentFinished((TorrentFinishedAlert) alert);
                        else if (tn.contains("error")) handleTorrentError((TorrentErrorAlert) alert);
                    }
                } catch (Throwable t) {
                    try {
                        if (alert instanceof StateUpdateAlert) handleStateUpdate((StateUpdateAlert) alert);
                        else if (alert instanceof TorrentFinishedAlert) handleTorrentFinished((TorrentFinishedAlert) alert);
                        else if (alert instanceof TorrentErrorAlert) handleTorrentError((TorrentErrorAlert) alert);
                    } catch (Throwable ignored) {
                        Log.w(TAG, "Unknown alert type received: " + ignored.getMessage());
                    }
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
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, (int) Math.min(totalDone, Integer.MAX_VALUE));
                intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, (int) Math.min(totalWanted, Integer.MAX_VALUE));
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, totalDone);

                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

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

        String errorMsg;
        try {
            errorMsg = alert.message();
        } catch (Throwable t) {
            try {
                Object err = callMethodSafely(alert, "error");
                if (err != null) {
                    errorMsg = (String) callMethodSafely(err, "message");
                } else errorMsg = "Unknown torrent error";
            } catch (Throwable tt) {
                errorMsg = "Unknown torrent error";
            }
        }

        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        cleanupTorrent(handle);
    }

    public String startSeeding(File dataFile, String dropRequestId) {
        if (dataFile == null || !dataFile.exists()) {
            Log.e(TAG, "Data file to be seeded does not exist.");
            return null;
        }

        File torrentFile = null;
        try {
            torrentFile = createTorrentFile(dataFile);
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);

            TorrentHandle handle = tryDownloadViaReflection(torrentInfo, dataFile.getParentFile());
            if (handle == null) {
                AddTorrentParams params = new AddTorrentParams();
                try {
                    callMethodSafely(params, "setTorrentInfo", new Class[]{TorrentInfo.class}, new Object[]{torrentInfo});
                } catch (Throwable ignored) {}
                try {
                    callMethodSafely(params, "setSavePath", new Class[]{String.class}, new Object[]{dataFile.getParentFile().getAbsolutePath()});
                } catch (Throwable ignored) {}

                handle = tryAddTorrentParams(params, dataFile.getParentFile());
            }

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                String infoHex = extractInfoHashHexFromHandle(handle);
                if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                String magnetLink;
                try {
                    magnetLink = handle.makeMagnetUri();
                } catch (Throwable t) {
                    magnetLink = "magnet:?xt=urn:btih:" + (extractInfoHashHexFromHandle(handle) != null ? extractInfoHashHexFromHandle(handle) : "");
                }
                Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
                return magnetLink;
            } else {
                Log.e(TAG, "Failed to get valid TorrentHandle after adding seed.");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create torrent for seeding: " + e.getMessage(), e);
            return null;
        } finally {
            if (torrentFile != null && torrentFile.exists()) {
                torrentFile.delete();
            }
        }
    }

    private File createTorrentFile(File dataFile) throws IOException {
        file_storage fs = new file_storage();
        boolean added = false;
        try {
            callStaticSafely(libtorrent.class, "add_files", new Class[]{file_storage.class, String.class}, new Object[]{fs, dataFile.getAbsolutePath()});
            added = true;
        } catch (Throwable ignored) {}
        if (!added) {
            try {
                callStaticSafely(libtorrent.class, "add_files_ex", new Class[]{file_storage.class, String.class}, new Object[]{fs, dataFile.getAbsolutePath()});
                added = true;
            } catch (Throwable ignored) {}
        }
        if (!added) {
            throw new IOException("libtorrent.add_files(...) not available in this libtorrent4j binding.");
        }

        int pieceSize = 16 * 1024;
        try {
            Object val = callStaticSafely(libtorrent.class, "optimal_piece_size", new Class[]{file_storage.class}, new Object[]{fs});
            if (val instanceof Number) pieceSize = ((Number) val).intValue();
        } catch (Throwable ignored) {}

        create_torrent ct = null;
        try {
            Constructor<?> cons = findConstructor(create_torrent.class, new Class[]{file_storage.class, int.class});
            if (cons != null) {
                ct = (create_torrent) cons.newInstance(fs, pieceSize);
            }
        } catch (Throwable ignored) {}
        if (ct == null) {
            throw new IOException("create_torrent constructor not available for (file_storage,int) in this binding.");
        }

        byte[] torrentBytes;
        try {
            Object gen = callMethodSafely(ct, "generate");
            Object bencoded = callMethodSafely(gen, "bencode");
            torrentBytes = bencodeToBytes(bencoded);
            if (torrentBytes == null) {
                throw new IOException("Unable to convert bencoded data to byte[]");
            }
        } catch (Throwable t) {
            throw new IOException("Failed to bencode generated torrent: " + t.getMessage(), t);
        }

        File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
            fos.write(torrentBytes);
            fos.flush();
        }
        return tempTorrent;
    }

    // CRITICAL FIX: Safe bencode → byte[] conversion
    private byte[] bencodeToBytes(Object bencoded) {
        if (bencoded == null) return null;

        if (bencoded instanceof byte[]) {
            return (byte[]) bencoded;
        }

        String className = bencoded.getClass().getName();
        if (className.contains("byte_vector") || className.contains("ByteVector")) {
            try {
                return Vectors.byte_vector2bytes(bencoded);
            } catch (Throwable t) {
                return attemptByteVectorToBytesReflective(bencoded);
            }
        }

        return attemptByteVectorToBytesReflective(bencoded);
    }

    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            byte[] torrentData = null;
            try {
                Object fetched = callMethodSafely(sessionManager, "fetchMagnet", new Class[]{String.class, int.class, File.class}, new Object[]{magnetLink, 30, saveDirectory});
                if (fetched instanceof byte[]) torrentData = (byte[]) fetched;
            } catch (Throwable ignored) {}

            if (torrentData != null) {
                TorrentInfo ti = TorrentInfo.bdecode(torrentData);
                TorrentHandle handle = tryDownloadViaReflection(ti, saveDirectory);
                if (handle != null && handle.isValid()) {
                    activeTorrents.put(dropRequestId, handle);
                    String infoHex = extractInfoHashHexFromHandle(handle);
                    if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                    Log.d(TAG, "Started download for request ID: " + dropRequestId);
                    return;
                }
            }

            AddTorrentParams params = null;
            try {
                params = (AddTorrentParams) callStaticSafely(AddTorrentParams.class, "parseMagnetUri", new Class[]{String.class}, new Object[]{magnetLink});
            } catch (Throwable ignored) {}

            if (params != null) {
                TorrentHandle handle = tryAddTorrentParams(params, saveDirectory);
                if (handle != null && handle.isValid()) {
                    activeTorrents.put(dropRequestId, handle);
                    String infoHex = extractInfoHashHexFromHandle(handle);
                    if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                    Log.d(TAG, "Started download for request ID: " + dropRequestId);
                    return;
                }
            }

            Log.e(TAG, "Failed to start download: no metadata obtained from magnet link.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download: " + e.getMessage(), e);
        }
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
            callMethodSafely(sessionManager, "remove", new Class[]{TorrentHandle.class}, new Object[]{handle});
        } catch (Throwable t) {
            try {
                callMethodSafely(sessionManager, "removeTorrent", new Class[]{TorrentHandle.class}, new Object[]{handle});
            } catch (Throwable t2) {
                Log.w(TAG, "No remove method available on SessionManager: " + t2.getMessage());
            }
        }

        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + (dropRequestId != null ? dropRequestId : "unknown"));
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null;
    }

    private TorrentHandle tryDownloadViaReflection(TorrentInfo ti, File saveDir) {
        try {
            Method m = findMethod(sessionManager.getClass(), "download", new Class[]{TorrentInfo.class, File.class});
            if (m != null) {
                Object r = m.invoke(sessionManager, ti, saveDir);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else if (r == null) {
                    String hex = infoHashObjectToHexSafe(ti);
                    return findHandleByInfoHex(hex);
                }
            }
        } catch (Throwable ignored) {}

        try {
            String magnetUri = safeMakeMagnetUri(ti);
            Method m2 = findMethod(sessionManager.getClass(), "download", new Class[]{String.class, File.class, Object.class});
            if (m2 != null) {
                Object r = m2.invoke(sessionManager, magnetUri, saveDir, null);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private String safeMakeMagnetUri(TorrentInfo ti) {
        String uri = (String) callMethodSafely(ti, "makeMagnetUri");
        if (uri != null && !uri.isEmpty()) return uri;

        Object ih = callMethodSafely(ti, "infoHash");
        String hex = infoHashObjectToHexSafe(ih);
        return hex != null ? "magnet:?xt=urn:btih:" + hex : "";
    }

    private TorrentHandle tryAddTorrentParams(AddTorrentParams params, File saveDir) {
        try {
            Method m = findMethod(sessionManager.getClass(), "download", new Class[]{AddTorrentParams.class});
            if (m != null) {
                Object r = m.invoke(sessionManager, params);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else if (r == null) {
                    Object ti = callMethodSafely(params, "torrentInfo");
                    String hex = infoHashObjectToHexSafe(ti);
                    return findHandleByInfoHex(hex);
                }
            }
        } catch (Throwable ignored) {}

        try {
            Method mAdd = findMethod(sessionManager.getClass(), "addTorrent", new Class[]{AddTorrentParams.class});
            if (mAdd != null) {
                Object r = mAdd.invoke(sessionManager, params);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else {
                    String hex = infoHashFromParamsHex(params);
                    return findHandleByInfoHex(hex);
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private String infoHashFromParamsHex(AddTorrentParams params) {
        try {
            Object ti = callMethodSafely(params, "torrentInfo");
            return infoHashObjectToHexSafe(ti);
        } catch (Throwable ignored) {}
        return null;
    }

    private TorrentHandle findHandleByInfoHex(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        for (Map.Entry<String, TorrentHandle> e : activeTorrents.entrySet()) {
            String hHex = extractInfoHashHexFromHandle(e.getValue());
            if (hHex != null && hHex.equalsIgnoreCase(hex)) return e.getValue();
        }
        try {
            Method getTorrents = findMethod(sessionManager.getClass(), "getTorrents", new Class[]{});
            if (getTorrents != null) {
                Object list = getTorrents.invoke(sessionManager);
                if (list instanceof java.util.Collection) {
                    for (Object o : ((java.util.Collection) list)) {
                        String hx = infoHashObjectToHexSafe(callMethodSafely(o, "infoHash"));
                        if (hx != null && hx.equalsIgnoreCase(hex) && o instanceof TorrentHandle) {
                            return (TorrentHandle) o;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractInfoHashHexFromStatus(TorrentStatus status) {
        if (status == null) return null;
        try {
            Object ih = callMethodSafely(status, "infoHash");
            if (ih != null) return infoHashObjectToHexSafe(ih);
            ih = callMethodSafely(status, "info_hash");
            if (ih != null) return infoHashObjectToHexSafe(ih);
            Object th = callMethodSafely(status, "handle");
            if (th instanceof TorrentHandle) return extractInfoHashHexFromHandle((TorrentHandle) th);
            return status.toString();
        } catch (Throwable t) {
            Log.w(TAG, "extractInfoHashHexFromStatus failed: " + t.getMessage());
        }
        return null;
    }

    private String extractInfoHashHexFromHandle(TorrentHandle handle) {
        if (handle == null) return null;
        try {
            Object ih = callMethodSafely(handle, "infoHash");
            if (ih != null) return infoHashObjectToHexSafe(ih);
            ih = callMethodSafely(handle, "info_hash");
            if (ih != null) return infoHashObjectToHexSafe(ih);
            return handle.toString();
        } catch (Throwable t) {
            Log.w(TAG, "extractInfoHashHexFromHandle failed: " + t.getMessage());
        }
        return null;
    }

    private long safeLong(Object obj, String methodName) {
        try {
            Object v = callMethodSafely(obj, methodName);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {}
        return 0L;
    }

    private Object callMethodSafely(Object target, String methodName, Class[] paramTypes, Object[] params) {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), methodName, paramTypes);
        if (m == null) return null;
        try {
            m.setAccessible(true);
            return m.invoke(target, params);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object callMethodSafely(Object target, String methodName) {
        return callMethodSafely(target, methodName, new Class[]{}, new Object[]{});
    }

    private Object callStaticSafely(Class<?> cls, String methodName, Class[] paramTypes, Object[] params) {
        if (cls == null) return null;
        try {
            Method m = findMethod(cls, methodName, paramTypes);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(null, params);
        } catch (Throwable t) {
            return null;
        }
    }

    private Method findMethod(Class<?> cls, String name, Class[] paramTypes) {
        if (cls == null) return null;
        try {
            if (paramTypes == null) paramTypes = new Class[]{};
            return cls.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            for (Method mm : cls.getDeclaredMethods()) {
                if (!mm.getName().equals(name)) continue;
                Class<?>[] pts = mm.getParameterTypes();
                if (pts.length != paramTypes.length) continue;
                boolean match = true;
                for (int i = 0; i < pts.length; i++) {
                    if (!pts[i].isAssignableFrom(paramTypes[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return mm;
            }
            Class<?> sc = cls.getSuperclass();
            if (sc != null) return findMethod(sc, name, paramTypes);
            return null;
        }
    }

    private Constructor<?> findConstructor(Class<?> cls, Class[] paramTypes) {
        if (paramTypes == null) paramTypes = new Class[]{};
        try {
            return cls.getConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            for (Constructor<?> c : cls.getConstructors()) {
                Class<?>[] pts = c.getParameterTypes();
                if (pts.length == paramTypes.length) {
                    boolean match = true;
                    for (int i = 0; i < pts.length; i++) {
                        if (!pts[i].isAssignableFrom(paramTypes[i])) {
                            match = false;
                            break;
                        }
                    }
                    if (match) return c;
                }
            }
            return null;
        }
    }

    private String infoHashObjectToHexSafe(Object ihObj) {
        if (ihObj == null) return null;
        try {
            Method m = findMethod(ihObj.getClass(), "toHex", new Class[]{});
            if (m != null) {
                Object r = m.invoke(ihObj);
                if (r instanceof String) return (String) r;
            }
            String s = ihObj.toString();
            if (s != null && s.length() > 0) return s;
            m = findMethod(ihObj.getClass(), "getBytes", new Class[]{});
            if (m != null) {
                Object r = m.invoke(ihObj);
                if (r instanceof byte[]) return bytesToHex((byte[]) r);
            }
        } catch (Throwable t) {
            Log.w(TAG, "infoHashObjectToHexSafe failed: " + t.getMessage, t);
        }
        return null;
    }

    private byte[] attemptByteVectorToBytesReflective(Object byteVectorObj) {
        if (byteVectorObj == null) return null;
        try {
            Method sizeM = findMethod(byteVectorObj.getClass(), "size", new Class[]{});
            Method getM = findMethod(byteVectorObj.getClass(), "get", new Class[]{int.class});
            if (sizeM != null && getM != null) {
                Object szObj = sizeM.invoke(byteVectorObj);
                int sz = (szObj instanceof Number) ? ((Number) szObj).intValue() : 0;
                byte[] out = new byte[sz];
                for (int i = 0; i < sz; i++) {
                    Object b = getM.invoke(byteVectorObj, i);
                    out[i] = (b instanceof Number) ? ((Number) b).byteValue() : (byte) ((int) b);
                }
                return out;
            }
        } catch (Throwable ignored) {}
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