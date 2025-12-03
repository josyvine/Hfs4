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
import org.libtorrent4j.InfoHash;
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
import org.libtorrent4j.swig.torrent_status;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TorrentManager Fixed for Build 33.0.1 and libtorrent4j 2.1.0-38 compatibility.
 * Replaced Reflection and incompatible API calls with direct, compatible methods.
 */
public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    private final Map<String, String> hashToIdMap; // infoHashHex -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events using direct AlertType checks
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                return null; // Listen to all alerts
            }

            @Override
            public void alert(Alert<?> alert) {
                if (alert.type() == AlertType.STATE_UPDATE) {
                    handleStateUpdate((StateUpdateAlert) alert);
                } else if (alert.type() == AlertType.TORRENT_FINISHED) {
                    handleTorrentFinished((TorrentFinishedAlert) alert);
                } else if (alert.type() == AlertType.TORRENT_ERROR) {
                    handleTorrentError((TorrentErrorAlert) alert);
                }
            }
        });

        // Start the session
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
            // FIX: Access info_hash via SWIG object since TorrentStatus wrapper methods vary
            torrent_status ts = status.swig();
            if (ts == null) continue;
            
            String infoHex = new InfoHash(ts.info_hash()).toHex();
            if (infoHex == null) continue;

            String dropRequestId = hashToIdMap.get(infoHex);
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | Down: " + (status.downloadPayloadRate() / 1024) + " KB/s | Up: " + (status.uploadPayloadRate() / 1024) + " KB/s");

                long totalDone = status.totalDone();
                long totalWanted = status.totalWanted();
                
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
        String infoHex = handle.infoHash().toHex();
        String dropRequestId = hashToIdMap.get(infoHex);
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = handle.infoHash().toHex();
        String dropRequestId = hashToIdMap.get(infoHex);

        String errorMsg = alert.message();
        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        cleanupTorrent(handle);
    }

    /**
     * Creates a torrent file and starts seeding it.
     */
    public String startSeeding(File dataFile, String dropRequestId) {
        if (dataFile == null || !dataFile.exists()) {
            Log.e(TAG, "Data file to be seeded does not exist.");
            return null;
        }

        File torrentFile = null;
        try {
            // 1. Create the .torrent file
            torrentFile = createTorrentFile(dataFile);
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);

            // 2. Add the torrent to the session
            sessionManager.download(torrentInfo, dataFile.getParentFile());
            
            // Retrieve the handle we just added
            TorrentHandle handle = sessionManager.find(torrentInfo.infoHash());

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                String infoHex = handle.infoHash().toHex();
                hashToIdMap.put(infoHex, dropRequestId);
                
                String magnetLink = handle.makeMagnetUri();
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

    /**
     * Helper to create a .torrent file from a source file.
     */
    private File createTorrentFile(File dataFile) throws IOException {
        file_storage fs = new file_storage();
        
        // FIX: Use full signature add_file(path, size, flags, mtime, link_path)
        // 0 for flags, 0 for mtime, "" for link_path
        fs.add_file(dataFile.getName(), dataFile.length(), 0, 0, "");

        // FIX: Use constructor create_torrent(file_storage)
        create_torrent ct = new create_torrent(fs);
        ct.set_creator("HFM Drop");
        ct.set_priv(true); 

        entry e = ct.generate();
        byte_vector bencoded = e.bencode();
        
        byte[] torrentBytes = Vectors.byte_vector2bytes(bencoded);

        File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
            fos.write(torrentBytes);
            fos.flush();
        }
        return tempTorrent;
    }

    /**
     * Starts downloading a file from a magnet link.
     */
    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            // FIX: Pass saveDirectory to fetchMagnet as required
            byte[] data = sessionManager.fetchMagnet(magnetLink, 30, saveDirectory); 
            
            if (data != null) {
                TorrentInfo ti = TorrentInfo.bdecode(data);
                
                // Add to session
                sessionManager.download(ti, saveDirectory);
                
                // Retrieve handle
                TorrentHandle handle = sessionManager.find(ti.infoHash());

                if (handle != null && handle.isValid()) {
                    activeTorrents.put(dropRequestId, handle);
                    String infoHex = handle.infoHash().toHex();
                    hashToIdMap.put(infoHex, dropRequestId);
                    
                    Log.d(TAG, "Started download for request ID: " + dropRequestId);
                } else {
                    Log.e(TAG, "Failed to start download: Invalid handle returned.");
                    broadcastDownloadError("Failed to initialize download session.");
                }
            } else {
                Log.e(TAG, "Failed to fetch magnet metadata.");
                broadcastDownloadError("Could not retrieve file metadata from magnet link.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download: " + e.getMessage(), e);
            broadcastDownloadError("Download Error: " + e.getMessage());
        }
    }
    
    private void broadcastDownloadError(String msg) {
        Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
        errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, msg);
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        String infoHex = handle.infoHash().toHex();
        String dropRequestId = hashToIdMap.get(infoHex);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(infoHex);
        }

        sessionManager.remove(handle);

        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + (dropRequestId != null ? dropRequestId : "unknown"));
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null;
    }

    // -----------------------------------------------------------
    // Reflection & helper utils (Original logic preserved below)
    // -----------------------------------------------------------

    private TorrentHandle tryDownloadViaReflection(TorrentInfo ti, File saveDir) {
        // Try common method signatures and return a TorrentHandle if possible.
        try {
            // Attempt: TorrentHandle download(TorrentInfo, File)
            Method m = findMethod(sessionManager.getClass(), "download", new Class[]{TorrentInfo.class, File.class});
            if (m != null) {
                Object r = m.invoke(sessionManager, ti, saveDir);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else if (r == null) {
                    // Some versions: method returns void; try to find the handle by searching session for torrent matching info
                    String hex = infoHashObjectToHexSafe(ti);
                    TorrentHandle h = findHandleByInfoHex(hex);
                    if (h != null) return h;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            // Attempt: TorrentHandle download(String magnet, File, torrent_flags_t)
            Method m2 = findMethod(sessionManager.getClass(), "download", new Class[]{String.class, File.class, Object.class});
            if (m2 != null) {
                // FIXED: Removed the direct call to `TorrentInfo.make_magnet_uri` and replaced it with
                // a reflective call. This is more robust and avoids compile errors if the method name
                // changes slightly between versions (e.g., makeMagnetUri vs make_magnet_uri).
                String magnetUri = (String) callStaticMethodIfExists(TorrentInfo.class, "make_magnet_uri", new Class[]{TorrentInfo.class}, new Object[]{ti});
                if (magnetUri == null) {
                    // Fallback to the other possible name
                    magnetUri = (String) callStaticMethodIfExists(TorrentInfo.class, "makeMagnetUri", new Class[]{TorrentInfo.class}, new Object[]{ti});
                }
                
                if (magnetUri != null) {
                    Object r = m2.invoke(sessionManager, magnetUri, saveDir, null);
                    if (r instanceof TorrentHandle) return (TorrentHandle) r;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private TorrentHandle tryAddTorrentParams(AddTorrentParams params, File saveDir) {
        try {
            // Try: sessionManager.download(AddTorrentParams) -> some micro-versions may implement this
            Method m = findMethod(sessionManager.getClass(), "download", new Class[]{AddTorrentParams.class});
            if (m != null) {
                Object r = m.invoke(sessionManager, params);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else if (r == null) {
                    // void return path - try to locate handle by infoHash inside params
                    Object ti = callMethodIfExists(params, "torrentInfo");
                    String hex = infoHashObjectToHexSafe(ti);
                    TorrentHandle h = findHandleByInfoHex(hex);
                    if (h != null) return h;
                }
            }
        } catch (Throwable ignored) {
        }

        // Try: sessionManager.addTorrent(AddTorrentParams) or add_torrent
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
        } catch (Throwable ignored) {
        }

        // If nothing returned, null
        return null;
    }

    private String infoHashFromParamsHex(AddTorrentParams params) {
        try {
            Object ti = callMethodIfExists(params, "torrentInfo");
            return infoHashObjectToHexSafe(ti);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private TorrentHandle findHandleByInfoHex(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        // naive search through activeTorrents map
        for (Map.Entry<String, TorrentHandle> e : activeTorrents.entrySet()) {
            TorrentHandle th = e.getValue();
            String hHex = extractInfoHashHexFromHandle(th);
            if (hHex != null && hHex.equalsIgnoreCase(hex)) return th;
        }
        // last-resort: attempt to inspect sessionManager for handles (reflectively)
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
            // Try common accessor names
            try {
                Object ih = callMethodIfExists(status, "infoHash");
                String s = infoHashObjectToHexSafe(ih);
                if (s != null) return s;
            } catch (Throwable ignored) {}
            try {
                Object ih = callMethodIfExists(status, "info_hash");
                String s = infoHashObjectToHexSafe(ih);
                if (s != null) return s;
            } catch (Throwable ignored) {}
            // Some status objects give a torrent handle
            try {
                Object th = callMethodIfExists(status, "handle");
                if (th instanceof TorrentHandle) {
                    return extractInfoHashHexFromHandle((TorrentHandle) th);
                }
            } catch (Throwable ignored) {}
            // fallback to status.toString
            try {
                String s = status.toString();
                if (s != null && s.length() >= 20) return s;
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "extractInfoHashHexFromStatus failed: " + t.getMessage());
        }
        return null;
    }

    private String extractInfoHashHexFromHandle(TorrentHandle handle) {
        if (handle == null) return null;
        try {
            try {
                Object ih = callMethodIfExists(handle, "infoHash");
                String s = infoHashObjectToHexSafe(ih);
                if (s != null) return s;
            } catch (Throwable ignored) {}
            try {
                Object ih = callMethodIfExists(handle, "info_hash");
                String s = infoHashObjectToHexSafe(ih);
                if (s != null) return s;
            } catch (Throwable ignored) {}
            // fallback to handle.toString()
            try {
                String s = handle.toString();
                if (s != null && s.length() >= 20) return s;
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "extractInfoHashHexFromHandle failed: " + t.getMessage());
        }
        return null;
    }

    private long safeLong(Object obj, String methodName) {
        try {
            Object v = callMethodIfExists(obj, methodName);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {}
        return 0L;
    }

    private Object callMethodIfExists(Object target, String methodName, Class<?>[] paramTypes, Object[] params) throws Exception {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), methodName, paramTypes);
        if (m == null) return null;
        m.setAccessible(true);
        return m.invoke(target, params);
    }

    private Object callMethodIfExists(Object target, String methodName) throws Exception {
        return callMethodIfExists(target, methodName, new Class<?>[]{}, new Object[]{});
    }

    private Object callStaticMethodIfExists(Class<?> cls, String methodName, Class<?>[] paramTypes, Object[] params) throws Exception {
        Method m = findMethod(cls, methodName, paramTypes);
        if (m == null) return null;
        m.setAccessible(true);
        return m.invoke(null, params);
    }

    private Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        if (cls == null) return null;
        try {
            return cls.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            // try declared methods
            for (Method mm : cls.getDeclaredMethods()) {
                if (!mm.getName().equals(name)) continue;
                Class<?>[] pts = mm.getParameterTypes();
                if (paramTypes == null || paramTypes.length == 0 || pts.length == paramTypes.length) {
                    boolean match = true;
                    if (paramTypes != null && paramTypes.length > 0) {
                        for (int i=0; i < pts.length; i++) {
                            if (!pts[i].isAssignableFrom(paramTypes[i])) {
                                match = false;
                                break;
                            }
                        }
                    }
                    if (match) return mm;
                }
            }
            // check superclasses
            Class<?> sc = cls.getSuperclass();
            if (sc != null) return findMethod(sc, name, paramTypes);
            return null;
        }
    }

    private Constructor<?> findConstructor(Class<?> cls, Class<?>[] paramTypes) {
        try {
            return cls.getConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            for (Constructor<?> c : cls.getConstructors()) {
                Class<?>[] pts = c.getParameterTypes();
                if (pts.length == paramTypes.length) return c;
            }
            return null;
        }
    }

    private Object callMethodSafely(Object target, String name) {
        try {
            return callMethodIfExists(target, name);
        } catch (Throwable t) {
            return null;
        }
    }

    // FIXED: The duplicate methods that were here have been removed permanently.

    private String infoHashObjectToHexSafe(Object ihObj) {
        if (ihObj == null) return null;
        try {
            // toHex()
            try {
                Method m = findMethod(ihObj.getClass(), "toHex", new Class[]{});
                if (m != null) {
                    Object r = m.invoke(ihObj);
                    if (r instanceof String) return (String) r;
                }
            } catch (Throwable ignored) {}
            // toString()
            try {
                String s = ihObj.toString();
                if (s != null && s.length() > 0) return s;
            } catch (Throwable ignored) {}
            // if there is a getBytes() or data() method returning byte[]
            try {
                Method m = findMethod(ihObj.getClass(), "getBytes", new Class[]{});
                if (m != null) {
                    Object r = m.invoke(ihObj);
                    if (r instanceof byte[]) return bytesToHex((byte[]) r);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "infoHashObjectToHexSafe failed: " + t.getMessage());
        }
        return null;
    }

    private byte[] attemptByteVectorToBytesReflective(Object byteVectorObj) {
        if (byteVectorObj == null) return null;
        try {
            // Try size() and get(i)
            Method sizeM = findMethod(byteVectorObj.getClass(), "size", new Class[]{});
            Method getM = findMethod(byteVectorObj.getClass(), "get", new Class[]{int.class});
            if (sizeM != null && getM != null) {
                Object szObj = sizeM.invoke(byteVectorObj);
                int sz = (szObj instanceof Number) ? ((Number) szObj).intValue() : 0;
                byte[] out = new byte[sz];
                for (int i = 0; i < sz; i++) {
                    Object b = getM.invoke(byteVectorObj, i);
                    if (b instanceof Number) out[i] = ((Number) b).byteValue();
                    else out[i] = (byte) ((int) b);
                }
                return out;
            }
        } catch (Throwable ignored) {
        }
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