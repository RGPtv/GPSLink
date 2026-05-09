package com.gpslink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothGpsService extends Service {

    private static final String TAG             = "GPSlink-BT";
    public  static final String CHANNEL_ID      = "GPS_Link_BT";
    public  static final int    NOTIFICATION_ID = 2;
    public  static final String ACTION_STATUS   = "com.gpslink.STATUS";   // same as USB
    private static final String SPP_UUID        = "00001101-0000-1000-8000-00805F9B34FB";

    public  static final String EXTRA_BT_ADDRESS = "bt_address";
    public  static final String EXTRA_BT_NAME    = "bt_name";

    private static final int  SERIAL_LOG_MAX  = 12;
    private static final int  MAX_AUTO_RETRIES = 10;
    private static final long RETRY_DELAY_MS  = 3_000;
    private static final long STALE_FIX_MS    = 10_000;

    // -- Shared state (same lock object as UsbSerialService so MainActivity can guard both) --
    public static final Object STATE_LOCK = new Object();

    public static volatile boolean isRunning             = false;
    public static volatile String  lastConn              = "Not started";
    public static volatile String  lastSignal            = "\u2014";
    public static volatile String  lastPos               = "\u2014";
    public static volatile String  lastMovement          = "\u2014";
    public static volatile String  lastSerialLog         = "\u2014";
    public static volatile String  lastSatellites        = "\u2014";
    public static volatile String  lastSatsInView        = "\u2014";
    public static volatile String  lastSatsUsed          = "\u2014";
    public static volatile String  lastHeading           = "0\u00B0";
    public static volatile String  lastConstellationJson = "";
    public static volatile long    totalBytes            = 0;
    public static volatile int     totalSents            = 0;
    private static long            lastBroadcastTime     = 0;
    public static volatile long    lastFixTime           = 0;

    // -- Instance fields -------------------------------------------------------
    private LocationManager          locationManager;
    private PowerManager.WakeLock    wakeLock;
    private BluetoothSocket          btSocket;
    private InputStream              btInputStream;
    private Thread                   readerThread;
    private String                   connectedDevName = "";
    private String                   targetAddress    = "";
    private int                      retryCount       = 0;
    private final AtomicBoolean      active           = new AtomicBoolean(false);
    private final AtomicBoolean      connecting       = new AtomicBoolean(false);
    private ScheduledExecutorService retryExecutor;

    // NMEA parsing fields (accessed inside nmeaLock) --------------------------
    private final StringBuilder      nmeaBuffer  = new StringBuilder(512);
    private final ArrayDeque<String> serialLines = new ArrayDeque<>();
    private final Object             nmeaLock    = new Object();

    private double  latitude = 0, longitude = 0, altitude = 0;
    private float   speed = 0, bearing = 0, accuracy = 5.0f, hdop = 99.0f;
    private int     satellites = 0, fixQuality = 0, fixMode = 1;
    private int     noFixCount = 0; // consecutive GSA no-fix reports before clearing state
    private long    gpsTimeMs  = 0;
    private boolean hasGGA = false, hasRMC = false;

    private final Map<String, NmeaParser.SatInfo> seenSats = new LinkedHashMap<>();
    // Receiver-reported total satellites in view per GSV talker (GSV field 3).
    // Summed in updateSatelliteSummary() for the authoritative in-view count.
    private final Map<String, Integer> gsvReportedTotal    = new LinkedHashMap<>();
    // PRN keys seen in the current GSV cycle per talker, used to prune stale seenSats entries.
    private final Map<String, java.util.Set<String>> gsvCurrentCyclePrns = new LinkedHashMap<>();
    private int     satsTrackedCount = 0;
    private boolean hasGGASatCount   = false;

    // -- Hz receiver (no-op for BT, but keeps broadcasts compatible) ----------
    private final BroadcastReceiver setHzReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) { /* BT GPS ignores Hz commands */ }
    };

    // -- Lifecycle -------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        active.set(true);
        retryExecutor = Executors.newSingleThreadScheduledExecutor();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GPSLink:bt");
        }
        createNotificationChannel();

        IntentFilter setHzFilter = new IntentFilter(UsbSerialService.ACTION_SET_HZ);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(setHzReceiver, setHzFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(setHzReceiver, setHzFilter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean wasRunning = isRunning;
        isRunning = true;

        if (intent != null) {
            String addr = intent.getStringExtra(EXTRA_BT_ADDRESS);
            String name = intent.getStringExtra(EXTRA_BT_NAME);
            if (addr != null && !addr.isEmpty()) {
                targetAddress    = addr;
                connectedDevName = (name != null && !name.isEmpty()) ? name : addr;
            }
            if (!wasRunning) {
                synchronized (STATE_LOCK) {
                    totalBytes  = 0;
                    totalSents  = 0;
                    lastFixTime = 0;
                    retryCount  = 0;
                }
                synchronized (nmeaLock) {
                    serialLines.clear();
                    seenSats.clear();
                    gsvReportedTotal.clear();
                    gsvCurrentCyclePrns.clear();
                    satsTrackedCount = 0;
                    hasGGASatCount   = false;
                    lastSatellites   = "\u2014";
                    lastSatsInView   = "\u2014";
                    lastSatsUsed     = "\u2014";
                }
            }
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10L * 60 * 60 * 1000);
        }

        startForeground(NOTIFICATION_ID, buildNotification("Connecting to " + connectedDevName + "..."));
        startConnectThread();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        active.set(false);
        isRunning = false;
        connecting.set(false);
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
            try { retryExecutor.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            retryExecutor = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { unregisterReceiver(setHzReceiver); } catch (Exception ignored) {}
        stopIo();
        removeProviders();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -- Connection -----------------------------------------------------------

    private void startConnectThread() {
        if (!active.get()) return;
        if (btSocket != null) return;
        if (!connecting.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { connectBluetooth(); }
            finally { connecting.set(false); }
        }).start();
    }

    private void connectBluetooth() {
        if (!active.get()) return;

        if (targetAddress.isEmpty()) {
            broadcastConn("No Bluetooth device selected");
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            broadcastConn("Bluetooth not available on this device");
            return;
        }
        if (!adapter.isEnabled()) {
            broadcastConn("Bluetooth is disabled — please enable it");
            return;
        }

        // Check BLUETOOTH_CONNECT permission on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                broadcastConn("Bluetooth permission denied — grant in App Settings");
                return;
            }
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(targetAddress);
        } catch (IllegalArgumentException e) {
            broadcastConn("Invalid Bluetooth address: " + targetAddress);
            return;
        }

        broadcastConn("Connecting to " + connectedDevName + "...");

        BluetoothSocket socket = null;
        try {
            // Cancel discovery to speed up connection
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}

            socket = device.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID));
            socket.connect();

            btSocket      = socket;
            btInputStream = socket.getInputStream();

            setupMockProviders();
            retryCount = 0;

            broadcastConn("Connected \u00B7 " + connectedDevName);
            Log.d(TAG, "BT connected: " + connectedDevName + " [" + targetAddress + "]");

            startReaderThread();

        } catch (IOException e) {
            Log.e(TAG, "BT connect error", e);
            broadcastConn("Failed to connect to " + connectedDevName + ": " + e.getMessage());
            if (socket != null) { try { socket.close(); } catch (IOException ignored) {} }
            btSocket      = null;
            btInputStream = null;
            scheduleRetry();
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            byte[] buf = new byte[1024];
            try {
                InputStream is = btInputStream;
                while (active.get() && is != null) {
                    int n = is.read(buf);
                    if (n < 0) break; // stream closed
                    if (n > 0) onNewData(buf, n);
                }
            } catch (IOException e) {
                if (active.get()) {
                    Log.e(TAG, "BT read error", e);
                    handleDisconnect(e.getMessage());
                }
            }
        }, "BT-NMEA-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void handleDisconnect(String reason) {
        hasGGA     = false;
        hasRMC     = false;
        gpsTimeMs  = 0;
        noFixCount = 0;
        stopIo();
        if (!active.get()) return;

        retryCount++;
        if (retryCount > MAX_AUTO_RETRIES) {
            broadcastConn("BT disconnected — tap Start to reconnect");
            retryCount = 0;
            return;
        }
        broadcastConn("Disconnected — retrying in 3 s (" + retryCount + "/" + MAX_AUTO_RETRIES + ")");
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (retryExecutor != null && !retryExecutor.isShutdown()) {
            retryExecutor.schedule(
                    () -> { if (active.get()) startConnectThread(); },
                    RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void stopIo() {
        if (readerThread != null) { readerThread.interrupt(); readerThread = null; }
        if (btInputStream != null) { try { btInputStream.close(); } catch (IOException ignored) {} btInputStream = null; }
        if (btSocket != null) { try { btSocket.close(); } catch (IOException ignored) {} btSocket = null; }
    }

    // -- NMEA data reception --------------------------------------------------

    private void onNewData(byte[] data, int length) {
        totalBytes += length;

        synchronized (nmeaLock) {
            nmeaBuffer.append(new String(data, 0, length, StandardCharsets.ISO_8859_1));
            int idx;
            while ((idx = nmeaBuffer.indexOf("\n")) >= 0) {
                String sentence = nmeaBuffer.substring(0, idx).trim();
                nmeaBuffer.delete(0, idx + 1);
                if (!sentence.isEmpty()) processSentence(sentence);
            }
            if (nmeaBuffer.length() > 512) {
                Log.w(TAG, "NMEA buffer overflow — discarding partial line");
                int lastNL = nmeaBuffer.lastIndexOf("\n");
                if (lastNL > 0) nmeaBuffer.delete(0, lastNL + 1);
                else            nmeaBuffer.setLength(0);
            }
        }
    }

    // -- NMEA sentence processing (called inside nmeaLock) --------------------

    private void processSentence(String sentence) {
        if (sentence.startsWith("$GPTXT") || sentence.startsWith("$GNTXT")) {
            appendSerialLine(NmeaParser.verifyChecksum(sentence)
                    ? "[TXT] " + sentence : "[BAD CRC] " + sentence);
            broadcastSerial();
            return;
        }

        if (!NmeaParser.verifyChecksum(sentence)) {
            appendSerialLine("[BAD CRC] " + sentence);
            broadcastSerial();
            return;
        }

        if (sentence.contains("GSV")) {
            List<NmeaParser.SatInfo> sats = NmeaParser.parseGSV(sentence);
            String[] parts = sentence.split(",", -1);
            if (parts.length > 2) {
                String talker = parts[0];
                String talkerConstellation = NmeaParser.constellationFromTalker(talker);
                boolean isFirstMsg = "1".equals(parts[2].trim());
                boolean isLastMsg  = parts.length > 1 && parts[1].trim().equals(parts[2].trim());

                // Capture receiver's own total-in-view count from GSV field 3.
                if (isFirstMsg && parts.length > 3 && !parts[3].trim().isEmpty()) {
                    try {
                        gsvReportedTotal.put(talker, Integer.parseInt(parts[3].trim()));
                    } catch (NumberFormatException ignored) {}
                }

                if (!sats.isEmpty()) {
                    if (isFirstMsg) {
                        if ("GNSS".equals(talkerConstellation)) {
                            gsvCurrentCyclePrns.clear();
                        }
                        gsvCurrentCyclePrns.put(talker, new java.util.HashSet<>());
                    }

                    java.util.Set<String> cyclePrns = gsvCurrentCyclePrns.computeIfAbsent(
                            talker, k -> new java.util.HashSet<>());
                    for (NmeaParser.SatInfo s : sats) {
                        String key = s.constellation + ":" + s.prn;
                        seenSats.put(key, s);
                        cyclePrns.add(key);
                    }

                    // Prune seenSats entries for this talker that weren't in this cycle.
                    if (isLastMsg) {
                        java.util.Set<String> seen = gsvCurrentCyclePrns.getOrDefault(
                                talker, java.util.Collections.emptySet());
                        String prefix = talkerConstellation + ":";
                        seenSats.entrySet().removeIf(
                                e -> e.getKey().startsWith(prefix) && !seen.contains(e.getKey()));
                    }

                    updateSatelliteSummary();
                }
            }
            appendSerialLine(sentence);
            broadcastSerial();
            return;
        }

        appendSerialLine(sentence);
        broadcastSerial();

        NmeaParser.GpsData d = NmeaParser.parse(sentence);
        if (d == null || !d.valid) return;

        totalSents++;

        if ("GGA".equals(d.type)) {
            // FIX: reject implausible fixes — need at least 3 sats for a position,
            // 4 for a proper 3-D fix. 1–2 sat "fixes" are not trustworthy.
            if (d.satellites < 3) {
                Log.d(TAG, "Skipping GGA: only " + d.satellites + " satellite(s) in use");
                return;
            }
            latitude         = d.latitude;
            longitude        = d.longitude;
            altitude         = d.altitude;
            accuracy         = d.accuracy;
            hdop             = d.hdop;
            satellites       = d.satellites;
            fixQuality       = d.fixQuality;
            satsTrackedCount = d.satellites;
            hasGGASatCount   = true;
            hasGGA           = true;
            noFixCount       = 0; // a valid GGA immediately resets the no-fix debounce counter
            // Use GGA time-of-day as the fix timestamp when RMC hasn't arrived yet,
            // matching USB behaviour: lastFixTime and gpsTimeMs both come from the
            // same GPS-epoch source so they stay coherent when passed to pushLocation().
            // On BT cold-start gpsTimeMs may be 0 for the first few epochs, so fall
            // back to wall clock only in that case to avoid a stale-fix false positive.
            if (!hasRMC) {
                if (d.gpsTimeMs > 0) {
                    gpsTimeMs   = d.gpsTimeMs;
                }
                lastFixTime = System.currentTimeMillis();
            }
            if (fixMode == 1 && d.fixQuality >= 1) {
                fixMode = (d.altitude != 0) ? 3 : 2;
            }
            if (System.currentTimeMillis() - lastBroadcastTime > 200) {
                pushLocation();
                broadcastAll();
                lastBroadcastTime = System.currentTimeMillis();
            }
        } else if ("RMC".equals(d.type)) {
            speed   = d.speed;
            bearing = d.bearing;
            // Use GPS time from RMC as the authoritative fix timestamp, matching USB.
            // Both lastFixTime and gpsTimeMs must come from the same GPS-epoch source
            // so that Location.getTime() and the stale-fix guard stay coherent.
            // Fall back to wall clock only when the BT device hasn't locked time yet.
            if (d.gpsTimeMs > 0) {
                gpsTimeMs   = d.gpsTimeMs;
            }
            lastFixTime = System.currentTimeMillis();
            
            // Update lat/lon from RMC as well so that if RMC arrives before GGA
            // for a new epoch, we don't push old coordinates with new speed/time.
            if (d.latitude != 0 || d.longitude != 0) {
                latitude  = d.latitude;
                longitude = d.longitude;
            }
            hasRMC = true;
            if (System.currentTimeMillis() - lastBroadcastTime > 200) {
                pushLocation();
                broadcastAll();
                lastBroadcastTime = System.currentTimeMillis();
            }
        } else if ("GSA".equals(d.type)) {
            // FIX: store fix mode separately — do NOT mix it into fixQuality.
            fixMode = d.fixMode;

            if (d.fixMode == 1) {
                // FIX: debounce — require several consecutive no-fix GSA reports
                // before clearing hasGGA. A single GSA no-fix during acquisition
                // or a momentary signal dip was previously enough to wipe a valid
                // GGA fix, causing the visible flicker between "No fix" and "GPS".
                noFixCount++;
                if (noFixCount >= 3) {
                    fixQuality = 0;
                    fixMode    = 1;
                    hasGGA     = false;
                    hasRMC     = false;
                    Log.d(TAG, "GSA: " + noFixCount + " consecutive no-fix reports — clearing fix state");
                }
            } else {
                noFixCount = 0;
            }

            // Only adopt GSA DOP when it's more precise than current GGA value.
            if (d.hdop < hdop) {
                hdop     = d.hdop;
                accuracy = Math.max(1.0f, d.hdop * 4.0f);
            }
            // GSA PRN list (fields 3–14) is the ground truth for satellites used.
            // Cheap BT modules often under-report this count in GGA field 7, so
            // prefer the GSA count when it's higher.
            if (d.satellites > 0 && d.satellites > satsTrackedCount) {
                satsTrackedCount = d.satellites;
                satellites       = d.satellites;
                hasGGASatCount   = true;
            }
            // Do NOT push location here — GSA doesn't update lat/lon.
            broadcastAll();
        } else if ("VTG".equals(d.type)) {
            // VTG provides direct speed/course; use as supplement.
            speed   = d.speed;
            bearing = d.bearing;
            // FIX: do NOT push location here — VTG doesn't update lat/lon.
            broadcastAll();
        }
    }

    // -- Satellite summary (identical logic to UsbSerialService) --------------

    private void updateSatelliteSummary() {
        Map<String, Integer> byConstellation   = new LinkedHashMap<>();
        Map<String, int[]>   snrByConstellation = new LinkedHashMap<>();

        for (NmeaParser.SatInfo s : seenSats.values()) {
            String key = constellationAbbr(s.constellation);
            byConstellation.put(key, byConstellation.getOrDefault(key, 0) + 1);
            if (s.snr > 0) {
                int[] acc = snrByConstellation.get(key);
                if (acc == null) { acc = new int[]{0, 0}; snrByConstellation.put(key, acc); }
                acc[0] += s.snr; acc[1]++;
            } else {
                snrByConstellation.putIfAbsent(key, new int[]{0, 0});
            }
        }

        StringBuilder json = new StringBuilder("[");
        boolean firstEntry = true;
        for (Map.Entry<String, Integer> e : byConstellation.entrySet()) {
            String label = e.getKey();
            int totalSat = e.getValue();
            int[] snrAcc = snrByConstellation.get(label);
            int avgSnr   = (snrAcc != null && snrAcc[1] > 0) ? (snrAcc[0] / snrAcc[1]) : 0;
            if (!firstEntry) json.append(",");
            json.append("{\"label\":\"").append(label)
                .append("\",\"avgSnr\":").append(avgSnr)
                .append(",\"count\":").append(totalSat)
                .append("}");
            firstEntry = false;
        }
        json.append("]");
        lastConstellationJson = json.toString();

        int displayUsed = hasGGASatCount ? satsTrackedCount : 0;
        // Sum the receiver's own reported totals across all talkers for the true in-view count.
        // Falling back to seenSats.size() only when no GSV totals have been received yet.
        int reportedTotal = 0;
        for (int v : gsvReportedTotal.values()) reportedTotal += v;
        int seen = reportedTotal > 0 ? reportedTotal : seenSats.size();
        int displaySeen = Math.max(seen, displayUsed);

        StringBuilder sb = new StringBuilder();
        sb.append(displaySeen).append(" seen \u00B7 ").append(displayUsed).append(" in use");
        if (!byConstellation.isEmpty()) {
            sb.append("  ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : byConstellation.entrySet()) {
                if (!first) sb.append(" ");
                sb.append(e.getKey()).append(":").append(e.getValue());
                first = false;
            }
        }
        lastSatellites = sb.toString();
        lastSatsInView = String.valueOf(displaySeen);
        lastSatsUsed   = hasGGASatCount ? String.valueOf(displayUsed) : "\u2014";
    }

    private static String constellationAbbr(String name) {
        if (name == null) return "UNK";
        switch (name) {
            case "GPS":     return "GPS";
            case "GLONASS": return "GLO";
            case "Galileo": return "GAL";
            case "BeiDou":  return "BDU";
            case "QZSS":    return "QZS";
            case "SBAS":    return "SBS";
            case "GNSS":    return "GNS";
            default:        return name.substring(0, Math.min(3, name.length())).toUpperCase();
        }
    }

    // -- Mock location injection ----------------------------------------------

    private void setupMockProviders() {
        if (locationManager == null) return;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        tryAddProvider(LocationManager.GPS_PROVIDER,     Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
        tryAddProvider(LocationManager.NETWORK_PROVIDER, Criteria.POWER_LOW,  Criteria.ACCURACY_COARSE);
    }

    private void tryAddProvider(String p, int power, int acc) {
        if (locationManager == null) return;
        try { locationManager.removeTestProvider(p); } catch (Exception ignored) {}
        try {
            locationManager.addTestProvider(p, false, false, false, false,
                    true, true, true, power, acc);
            locationManager.setTestProviderEnabled(p, true);
        } catch (Exception e) {
            Log.w(TAG, "addTestProvider " + p + ": " + e.getMessage());
        }
    }

    private void removeProviders() {
        if (locationManager == null) return;
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);     } catch (Exception ignored) {}
        try { locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
    }

    private void pushLocation() {
        if (!hasGGA && !hasRMC) return;
        if (locationManager == null) return;
        // FIX: do not inject stale positions into the mock provider. Android's
        // location engine will reject them anyway once their elapsed-nanos age
        // exceeds its internal threshold, and doing so ourselves avoids feeding
        // apps a last-known position that is clearly outdated.
        long nowMs = System.currentTimeMillis();
        if (lastFixTime > 0 && nowMs - lastFixTime > STALE_FIX_MS) {
            Log.d(TAG, "Skipping pushLocation: fix is stale ("
                    + (nowMs - lastFixTime) + " ms old)");
            return;
        }
        long time  = gpsTimeMs > 0 ? gpsTimeMs : System.currentTimeMillis();
        long nanos = SystemClock.elapsedRealtimeNanos();
        pushToProvider(LocationManager.GPS_PROVIDER,     time, nanos);
        pushToProvider(LocationManager.NETWORK_PROVIDER, time, nanos);
    }

    private void pushToProvider(String provider, long time, long nanos) {
        try {
            Location loc = new Location(provider);
            loc.setLatitude(latitude);
            loc.setLongitude(longitude);
            loc.setAltitude(altitude);
            loc.setAccuracy(accuracy);
            loc.setSpeed(speed);
            loc.setBearing(bearing);
            loc.setTime(time);
            loc.setElapsedRealtimeNanos(nanos);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                loc.setVerticalAccuracyMeters(accuracy * 1.5f);
                loc.setSpeedAccuracyMetersPerSecond(accuracy * 0.1f);
                loc.setBearingAccuracyDegrees(accuracy * 2.0f);
            }
            
            locationManager.setTestProviderLocation(provider, loc);
        } catch (Exception e) {
            Log.w(TAG, "push " + provider + ": " + e.getMessage());
        }
    }

    // -- Broadcast helpers ----------------------------------------------------

    private void broadcastConn(String msg) {
        synchronized (STATE_LOCK) { lastConn = msg; }
        updateNotification(msg);
        sendStatusBroadcast(new Intent(ACTION_STATUS).putExtra("conn", msg));
        Log.d(TAG, msg);
    }

    private void broadcastAll() {
        long    nowMs  = System.currentTimeMillis();
        boolean stale  = (lastFixTime > 0) && (nowMs - lastFixTime > STALE_FIX_MS);
        // If we've gone stale, treat fix mode as no-fix for the UI.
        String  satStr = satellites == 1 ? "1 sat" : satellites + " sats";
        String  fixStr = stale ? "Stale" : fixLabel(fixQuality);
        int     displayFixMode = stale ? 1 : fixMode;
        String  latDir = latitude  >= 0 ? "N" : "S";
        String  lonDir = longitude >= 0 ? "E" : "W";

        synchronized (STATE_LOCK) {
            lastSignal   = satStr + "  Fix: " + fixStr
                         + (displayFixMode == 3 ? " 3D" : displayFixMode == 2 ? " 2D" : "")
                         + "  HDOP: " + String.format("%.1f", hdop)
                         + "  \u00B1" + String.format("%.0f", accuracy) + " m";
            if (lastFixTime > 0) {
                lastPos = String.format("%.6f\u00B0 %s\n%.6f\u00B0 %s\nAlt: %.1f m",
                        Math.abs(latitude),  latDir,
                        Math.abs(longitude), lonDir,
                        altitude);
            } else {
                lastPos = "\u2014";
            }
            lastMovement = String.format("Speed: %.1f km/h\nCourse: %.1f\u00B0\nHDOP: %.2f\nFix: %s",
                           speed * 3.6f, bearing, hdop, fixStr);
            lastHeading  = String.format("%.1f\u00B0", bearing);
        }

        updateNotification(String.format("%.5f, %.5f  %s  \u00B1%.0f m",
                latitude, longitude, fixStr, accuracy));

        sendStatusBroadcast(new Intent(ACTION_STATUS)
                .putExtra("signal",            lastSignal)
                .putExtra("position",          lastPos)
                .putExtra("movement",          lastMovement)
                .putExtra("serial",            lastSerialLog)
                .putExtra("satellites",        lastSatellites)
                .putExtra("bytes",             totalBytes)
                .putExtra("sents",             totalSents)
                .putExtra("fixtime",           lastFixTime)
                .putExtra("satsInView",        lastSatsInView)
                .putExtra("satsUsed",          lastSatsUsed)
                .putExtra("heading",           String.format("%.1f\u00B0", bearing))
                .putExtra("constellationJson", lastConstellationJson));
    }

    private void broadcastSerial() {
        sendStatusBroadcast(new Intent(ACTION_STATUS)
                .putExtra("serial",            lastSerialLog)
                .putExtra("bytes",             totalBytes)
                .putExtra("sents",             totalSents)
                .putExtra("satsInView",        lastSatsInView)
                .putExtra("satsUsed",          lastSatsUsed)
                .putExtra("satellites",        lastSatellites)
                .putExtra("constellationJson", lastConstellationJson));
    }

    private void appendSerialLine(String line) {
        serialLines.addLast(line);
        if (serialLines.size() > SERIAL_LOG_MAX) serialLines.removeFirst();
        StringBuilder sb = new StringBuilder();
        for (String l : serialLines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        lastSerialLog = sb.toString();
    }

    private void sendStatusBroadcast(Intent intent) {
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    // -- Fix label ------------------------------------------------------------

    private static String fixLabel(int q) {
        switch (q) {
            case 1:  return "GPS";
            case 2:  return "DGPS";
            case 3:  return "PPS";
            case 4:  return "RTK Fixed";
            case 5:  return "RTK Float";
            case 6:  return "Dead Reckoning";
            default: return "No fix";
        }
    }

    // -- Notification ---------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "GPSLink Bluetooth GPS", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Live GPS from Bluetooth receiver");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPSLink \u00B7 Bluetooth")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
