package com.gpslink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class UsbSerialService extends Service implements SerialInputOutputManager.Listener {

    private static final String TAG              = "GPSlink";
    public  static final String CHANNEL_ID       = "GPS_Link";
    public  static final int    NOTIFICATION_ID  = 1;
    public  static final String ACTION_STATUS    = "com.gpslink.STATUS";
    public  static final String ACTION_SET_HZ    = "com.gpslink.SET_HZ";
    private static final String ACTION_USB_PERM  = "com.gpslink.USB_PERMISSION";
    public  static final String EXTRA_HZ         = "hz";

    private static final int[]  SUPPORTED_HZ     = {1, 5, 10};
    private static final int    SERIAL_LOG_MAX   = 12;
    private static final int    UBLOX_VID        = 0x1546;
    private static final int[]  UBLOX_PIDS       = {0x01A7, 0x01A8, 0x01A9, 0x01AA};
    private static final int    BAUD_RATE        = 9600;
    private static final int    MAX_AUTO_RETRIES = 10;
    private static final long   RETRY_DELAY_MS   = 3_000;
    private static final long   STALE_FIX_MS     = 10_000;

    // ── Shared state (guarded by STATE_LOCK where noted) ──────────────────────
    public static final Object STATE_LOCK = new Object();

    public static volatile boolean isRunning            = false;
    public static volatile int     currentHz            = 1;
    public static volatile String  lastConn             = "Not started";
    public static volatile String  lastSignal           = "\u2014";
    public static volatile String  lastPos              = "\u2014";
    public static volatile String  lastMovement         = "\u2014";
    public static volatile String  lastSerialLog        = "\u2014";
    public static volatile String  lastSatellites       = "\u2014";
    public static volatile String  lastSatsInView       = "\u2014";
    public static volatile String  lastSatsUsed         = "\u2014";
    public static volatile String  lastHeading          = "0°";
    public static volatile String  lastConstellationJson = "";
    public static volatile long    totalBytes           = 0;
    public static volatile int     totalSents           = 0;
    public static volatile long    lastFixTime          = 0;

    // ── Instance fields ───────────────────────────────────────────────────────
    private LocationManager           locationManager;
    private PowerManager.WakeLock     wakeLock;
    private SerialInputOutputManager  ioManager;
    private UsbSerialPort             serialPort;
    private String                    connectedDevName = "";
    private int                       retryCount       = 0;
    private final AtomicBoolean       active           = new AtomicBoolean(false);
    private ScheduledExecutorService  retryExecutor;

    // NMEA parsing — ALL fields below are only accessed inside nmeaLock
    private final StringBuilder      nmeaBuffer  = new StringBuilder(512);
    private final ArrayDeque<String> serialLines = new ArrayDeque<>();
    private final Object             nmeaLock    = new Object();

    private double  latitude = 0, longitude = 0, altitude = 0;
    private float   speed = 0, bearing = 0, accuracy = 5.0f, hdop = 99.0f;
    private int     satellites = 0, fixQuality = 0;
    private long    gpsTimeMs  = 0;
    private boolean hasGGA = false, hasRMC = false;

    private final Map<String, NmeaParser.SatInfo> seenSats = new LinkedHashMap<>();
    private int     satsTrackedCount = 0;
    private boolean hasGGASatCount   = false;

    // ── UBX packet builders ───────────────────────────────────────────────────

    private static byte[] ubx(int cls, int id, byte[] payload) {
        byte[] msg = new byte[6 + payload.length + 2];
        msg[0] = (byte) 0xB5;
        msg[1] = (byte) 0x62;
        msg[2] = (byte) cls;
        msg[3] = (byte) id;
        msg[4] = (byte) (payload.length & 0xFF);
        msg[5] = (byte) ((payload.length >> 8) & 0xFF);
        System.arraycopy(payload, 0, msg, 6, payload.length);
        int a = 0, b = 0;
        for (int i = 2; i < 6 + payload.length; i++) {
            a = (a + (msg[i] & 0xFF)) & 0xFF;
            b = (b + a) & 0xFF;
        }
        msg[6 + payload.length]     = (byte) a;
        msg[6 + payload.length + 1] = (byte) b;
        return msg;
    }

    private static byte[] ubxCfgRate(int hz) {
        // BUG FIX: guard against hz==0 before division (already present, kept)
        int ms = (hz > 0) ? (1000 / hz) : 1000;
        return ubx(0x06, 0x08, new byte[]{
            (byte) (ms & 0xFF), (byte) (ms >> 8),
            0x01, 0x00,   // navRate = 1 (every measurement cycle)
            0x01, 0x00    // timeRef = 1 (GPS time)
        });
    }

    private static byte[] ubxCfgMsg(int msgCls, int msgId, int rate) {
        return ubx(0x06, 0x01, new byte[]{
            (byte) msgCls, (byte) msgId,
            0, (byte) rate, 0, (byte) rate, 0, 0
        });
    }

    /**
     * UBX-CFG-GNSS — enables GPS, GLONASS, Galileo, BeiDou.
     * M7 silently ignores Galileo/BeiDou blocks and applies GPS+GLONASS only.
     */
    private static byte[] ubxCfgGnss() {
        return ubx(0x06, 0x3E, new byte[]{
            0x00,              // msgVer
            0x00,              // numTrkChHw (0 = read from module)
            (byte) 0xFF,       // numTrkChUse (0xFF = use all available)
            0x04,              // numConfigBlocks = 4
            // GPS (gnssId=0): channels 8–16, enable, L1C/A
            0x00, 0x08, 0x10, 0x00,  (byte)0x01,0x00,0x01,0x01,
            // GLONASS (gnssId=6): channels 4–8, enable, L1OF
            0x06, 0x04, 0x08, 0x00,  (byte)0x01,0x00,0x01,0x01,
            // Galileo (gnssId=2): channels 4–8, enable, E1OS
            0x02, 0x04, 0x08, 0x00,  (byte)0x01,0x00,0x01,0x01,
            // BeiDou (gnssId=3): channels 2–4, enable, B1I
            0x03, 0x02, 0x04, 0x00,  (byte)0x01,0x00,0x01,0x01,
        });
    }

    /**
     * UBX-CFG-SBAS — enables SBAS ranging and correction.
     * scanmode=0 means auto-scan all PRNs (WAAS, EGNOS, MSAS, GAGAN).
     */
    private static byte[] ubxCfgSbas() {
        return ubx(0x06, 0x16, new byte[]{
            0x01,                                           // mode: enable
            0x03,                                           // usage: ranging + correction
            0x03,                                           // maxSBAS channels
            0x00,                                           // scanmode2
            (byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00    // scanmode1: auto
        });
    }

    /**
     * UBX-CFG-CFG — save current config to flash so it survives power cycles.
     */
    private static byte[] ubxCfgCfg() {
        return ubx(0x06, 0x09, new byte[]{
            0x00, 0x00, 0x00, 0x00,                         // clearMask
            (byte)0xFF,(byte)0xFF, 0x00, 0x00,              // saveMask (all)
            0x00, 0x00, 0x00, 0x00                          // loadMask
        });
    }

    // ── Broadcast receivers ───────────────────────────────────────────────────

    private final BroadcastReceiver usbPermReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERM.equals(intent.getAction())) return;
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                broadcastConn("USB permission granted – connecting…");
                startConnectThread();
            } else {
                broadcastConn("USB permission denied – tap Allow when prompted");
            }
        }
    };

    private final BroadcastReceiver setHzReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_SET_HZ.equals(intent.getAction())) return;
            int hz = intent.getIntExtra(EXTRA_HZ, 1);
            if (isValidHz(hz) && hz != currentHz) {
                synchronized (STATE_LOCK) { currentHz = hz; }
                // Run on a background thread – never block a BroadcastReceiver
                new Thread(UsbSerialService.this::applyHzConfig).start();
            }
        }
    };

    private static boolean isValidHz(int hz) {
        if (hz <= 0) return false;
        for (int h : SUPPORTED_HZ) if (h == hz) return true;
        return false;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        active.set(true);
        retryExecutor = Executors.newSingleThreadScheduledExecutor();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        // BUG FIX: pm could theoretically be null; guard it
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GPSLink:serial");
        }
        createNotificationChannel();

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Context.RECEIVER_NOT_EXPORTED : 0;
        registerReceiver(usbPermReceiver, new IntentFilter(ACTION_USB_PERM), flags);
        registerReceiver(setHzReceiver,   new IntentFilter(ACTION_SET_HZ),   flags);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isRunning = true;
        if (intent != null) {
            synchronized (STATE_LOCK) {
                totalBytes  = 0;
                totalSents  = 0;
                lastFixTime = 0;
                retryCount  = 0;
            }
            synchronized (nmeaLock) {
                serialLines.clear();
                seenSats.clear();
                satsTrackedCount  = 0;
                hasGGASatCount    = false;
                lastSatellites    = "\u2014";
                lastSatsInView    = "\u2014";
                lastSatsUsed      = "\u2014";
            }
            int hz = intent.getIntExtra(EXTRA_HZ, 1);
            synchronized (STATE_LOCK) { currentHz = isValidHz(hz) ? hz : 1; }
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10L * 60 * 60 * 1000); // 10-hour cap
        }
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"));
        startConnectThread();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        active.set(false);
        isRunning = false;
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
            try {
                retryExecutor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            retryExecutor = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { unregisterReceiver(usbPermReceiver); } catch (Exception e) { Log.w(TAG, "Unregister usbPerm", e); }
        try { unregisterReceiver(setHzReceiver);   } catch (Exception e) { Log.w(TAG, "Unregister setHz", e);   }
        stopIo();
        removeProviders();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── USB connection ────────────────────────────────────────────────────────

    private void startConnectThread() {
        if (!active.get()) return;
        new Thread(this::connectUsb).start();
    }

    private UsbSerialProber buildProber() {
        ProbeTable t = UsbSerialProber.getDefaultProbeTable();
        for (int pid : UBLOX_PIDS) t.addProduct(UBLOX_VID, pid, CdcAcmSerialDriver.class);
        return new UsbSerialProber(t);
    }

    private void connectUsb() {
        if (!active.get()) return;

        UsbManager usbManager = (UsbManager) getSystemService(USB_SERVICE);
        // BUG FIX: usbManager can be null on some devices
        if (usbManager == null) {
            broadcastConn("USB service unavailable on this device");
            return;
        }

        List<UsbSerialDriver> drivers = buildProber().findAllDrivers(usbManager);

        // Fallback: try all connected USB devices as CDC-ACM
        if (drivers.isEmpty()) {
            ProbeTable fb = new ProbeTable();
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                fb.addProduct(d.getVendorId(), d.getProductId(), CdcAcmSerialDriver.class);
            }
            drivers = new UsbSerialProber(fb).findAllDrivers(usbManager);
        }

        if (drivers.isEmpty()) {
            broadcastConn("No USB device found – plug in u-blox and tap Start");
            return;
        }

        UsbSerialDriver driver     = drivers.get(0);
        UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
        if (connection == null) {
            broadcastConn("Waiting for USB permission…");
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERM),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(driver.getDevice(), pi);
            return;
        }

        // BUG FIX: driver.getPorts() may be empty on malformed drivers
        List<UsbSerialPort> ports = driver.getPorts();
        if (ports.isEmpty()) {
            broadcastConn("USB driver has no ports – unsupported device");
            connection.close();
            return;
        }

        try {
            serialPort = ports.get(0);
            serialPort.open(connection);
            serialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            serialPort.setDTR(true);

            broadcastConn("Configuring u-blox (" + currentHz + " Hz)…");
            configureUblox();
            setupMockProviders();

            ioManager = new SerialInputOutputManager(serialPort, this);
            ioManager.setReadBufferSize(4096);
            ioManager.start();

            connectedDevName = driver.getDevice().getDeviceName();
            retryCount = 0;
            broadcastConn("Connected · " + connectedDevName + " · " + BAUD_RATE + " baud · " + currentHz + " Hz");
            Log.d(TAG, "Connected: " + connectedDevName);
        } catch (IOException e) {
            broadcastConn("USB open error: " + e.getMessage());
            Log.e(TAG, "connect error", e);
            stopIo();
        }
    }

    // ── UBX configuration ─────────────────────────────────────────────────────

    private void configureUblox() {
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        sendUbx(ubxCfgRate(currentHz));

        // Enable all constellations (M7 silently ignores Galileo/BeiDou blocks)
        sendUbx(ubxCfgGnss());
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        // Enable SBAS (MSAS has partial Philippines coverage; others auto-scanned)
        sendUbx(ubxCfgSbas());
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        sendUbx(ubxCfgMsg(0xF0, 0x41, 0)); // disable GPTXT
        sendUbx(ubxCfgMsg(0xF0, 0x02, 0)); // disable GSA
        sendUbx(ubxCfgMsg(0xF0, 0x01, 0)); // disable GLL
        sendUbx(ubxCfgMsg(0xF0, 0x05, 0)); // disable VTG
        sendUbx(ubxCfgMsg(0xF0, 0x00, 1)); // GGA on
        sendUbx(ubxCfgMsg(0xF0, 0x04, 1)); // RMC on
        sendUbx(ubxCfgMsg(0xF0, 0x03, 1)); // GSV on  (all constellations)

        // Save config to flash so it survives power cycles
        sendUbx(ubxCfgCfg());
        Log.d(TAG, "UBX configured at " + currentHz + " Hz with multi-constellation + SBAS");
    }

    private void applyHzConfig() {
        UsbSerialPort port = serialPort; // local snapshot prevents disconnect race
        if (port == null) return;
        sendUbx(ubxCfgRate(currentHz));
        broadcastConn("Connected · " + connectedDevName + " · " + BAUD_RATE + " baud · " + currentHz + " Hz");
        Log.d(TAG, "Hz updated to " + currentHz);
    }

    private void sendUbx(byte[] msg) {
        UsbSerialPort port = serialPort;
        if (port == null) return;
        try {
            port.write(msg, 500);
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "UBX write failed: " + e.getMessage());
        }
    }

    // ── Mock location providers ───────────────────────────────────────────────

    private void setupMockProviders() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission missing, skipping mock provider setup");
            return;
        }
        tryAddProvider(LocationManager.GPS_PROVIDER,
                android.location.provider.ProviderProperties.POWER_USAGE_HIGH,
                android.location.provider.ProviderProperties.ACCURACY_FINE);
        tryAddProvider(LocationManager.NETWORK_PROVIDER,
                android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                android.location.provider.ProviderProperties.ACCURACY_COARSE);
    }

    private void tryAddProvider(String p, int power, int acc) {
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
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);     } catch (Exception ignored) {}
        try { locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
    }

    // ── Serial data reception ─────────────────────────────────────────────────

    /**
     * Called by SerialInputOutputManager on a background thread whenever new
     * bytes arrive from the USB serial port.
     *
     * BUG FIX: totalBytes was incremented outside nmeaLock, creating a data
     * race with broadcastAll() which reads it under STATE_LOCK.  We use a
     * volatile long which is sufficient for the single-writer / multiple-reader
     * pattern here (SerialInputOutputManager calls onNewData serially).
     */
    @Override
    public void onNewData(byte[] data) {
        totalBytes += data.length; // volatile write — single writer thread

        synchronized (nmeaLock) {
            nmeaBuffer.append(new String(data, StandardCharsets.ISO_8859_1));
            int idx;
            while ((idx = nmeaBuffer.indexOf("\n")) >= 0) {
                String sentence = nmeaBuffer.substring(0, idx).trim();
                nmeaBuffer.delete(0, idx + 1);
                if (!sentence.isEmpty()) processSentence(sentence);
            }
            // Guard against a pathological stream with no newlines
            if (nmeaBuffer.length() > 512) {
                Log.w(TAG, "NMEA buffer overflow – discarding partial line");
                int lastNL = nmeaBuffer.lastIndexOf("\n");
                if (lastNL > 0) nmeaBuffer.delete(0, lastNL + 1);
                else            nmeaBuffer.setLength(0);
            }
        }
    }

    // ── NMEA sentence processing (called inside nmeaLock) ────────────────────

    private void processSentence(String sentence) {
        // Diagnostic TXT messages — log but don't parse as GPS data
        if (sentence.startsWith("$GPTXT") || sentence.startsWith("$GNTXT")) {
            appendSerialLine(NmeaParser.verifyChecksum(sentence)
                    ? "[TXT] " + sentence : "[BAD CRC] " + sentence);
            broadcastSerial();
            return;
        }

        if (!NmeaParser.verifyChecksum(sentence)) {
            appendSerialLine("[BAD CRC] " + sentence);
            broadcastSerial();
            Log.d(TAG, "BAD CRC: " + sentence);
            return;
        }

        // GSV — satellite-in-view data
        if (sentence.contains("GSV")) {
            List<NmeaParser.SatInfo> sats = NmeaParser.parseGSV(sentence);
            if (!sats.isEmpty()) {
                String[] parts = sentence.split(",", -1);
                // First message in a GSV sequence resets stale entries for that talker.
                // BUG FIX: use the talker-derived constellation from the raw sentence
                // header (parts[0] e.g. "$GLGSV") instead of sats.get(0).constellation,
                // which may have been relabeled (e.g. SBAS PRNs inside a $GPGSV sentence
                // would cause the reset key to be "SBAS" instead of "GPS", leaving stale
                // GPS entries permanently in seenSats).
                boolean isFirstMsg = parts.length > 2 && "1".equals(parts[2].trim());
                if (isFirstMsg) {
                    // Derive the reset prefix from the raw talker header, not the parsed sat
                    String talkerConstellation = NmeaParser.constellationFromTalker(parts[0]);
                    final String resetPrefix = talkerConstellation + ":";
                    seenSats.entrySet().removeIf(e -> e.getKey().startsWith(resetPrefix));
                }
                for (NmeaParser.SatInfo s : sats) {
                    seenSats.put(s.constellation + ":" + s.prn, s);
                }
                updateSatelliteSummary();
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
        Log.d(TAG, "✓ Valid " + d.type
                + ": lat=" + String.format("%.6f", d.latitude)
                + " lon=" + String.format("%.6f", d.longitude));

        if ("GGA".equals(d.type)) {
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
        } else if ("RMC".equals(d.type)) {
            speed   = d.speed;
            bearing = d.bearing;
            if (d.gpsTimeMs > 0) {
                gpsTimeMs   = d.gpsTimeMs;
                lastFixTime = d.gpsTimeMs;
            }
            // Only fall back to RMC position when GGA hasn't been received yet
            if (!hasGGA) {
                latitude  = d.latitude;
                longitude = d.longitude;
            }
            hasRMC = true;
        }

        pushLocation();
        broadcastAll();
    }

    // ── Satellite summary ─────────────────────────────────────────────────────

    private void updateSatelliteSummary() {
        int seen    = seenSats.size();
        Map<String, Integer> byConstellation   = new LinkedHashMap<>();
        Map<String, int[]>   snrByConstellation = new LinkedHashMap<>(); // [snrSum, satCount]

        for (NmeaParser.SatInfo s : seenSats.values()) {
            String key = constellationAbbr(s.constellation);
            byConstellation.put(key, byConstellation.getOrDefault(key, 0) + 1);
            if (s.snr > 0) {
                int[] acc = snrByConstellation.get(key);
                if (acc == null) { acc = new int[]{0, 0}; snrByConstellation.put(key, acc); }
                acc[0] += s.snr;
                acc[1]++;
            } else {
                snrByConstellation.putIfAbsent(key, new int[]{0, 0});
            }
        }

        // Build JSON for SignalBarsView: [{"label":"GPS","avgSnr":38,"count":6}, …]
        StringBuilder json = new StringBuilder("[");
        boolean firstEntry = true;
        for (Map.Entry<String, Integer> e : byConstellation.entrySet()) {
            String label    = e.getKey();
            int    totalSat = e.getValue();
            int[]  snrAcc   = snrByConstellation.get(label);
            int    avgSnr   = (snrAcc != null && snrAcc[1] > 0) ? (snrAcc[0] / snrAcc[1]) : 0;
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
        // In-view should never be less than in-use
        int displaySeen = Math.max(seen, displayUsed);

        StringBuilder sb = new StringBuilder();
        sb.append(displaySeen).append(" seen · ").append(displayUsed).append(" in use");
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

    // ── Location injection ────────────────────────────────────────────────────

    private void pushLocation() {
        if (!hasGGA && !hasRMC) return;
        if (locationManager == null) return;
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
            locationManager.setTestProviderLocation(provider, loc);
        } catch (Exception e) {
            Log.w(TAG, "push " + provider + ": " + e.getMessage());
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    private void broadcastConn(String msg) {
        synchronized (STATE_LOCK) { lastConn = msg; }
        updateNotification(msg);
        sendBroadcast(new Intent(ACTION_STATUS).putExtra("conn", msg));
        Log.d(TAG, msg);
    }

    private void broadcastAll() {
        long    nowMs   = System.currentTimeMillis();
        boolean stale   = (lastFixTime > 0) && (nowMs - lastFixTime > STALE_FIX_MS);
        String  satStr  = satellites == 1 ? "1 sat" : satellites + " sats";
        String  fixStr  = stale ? "Stale" : fixLabel(fixQuality);
        String  latDir  = latitude  >= 0 ? "N" : "S";
        String  lonDir  = longitude >= 0 ? "E" : "W";

        synchronized (STATE_LOCK) {
            lastSignal   = satStr + "  Fix: " + fixStr
                         + "  HDOP: " + String.format("%.1f", hdop)
                         + "  ±" + String.format("%.0f", accuracy) + " m";
            lastPos      = String.format("%.6f° %s\n%.6f° %s\nAlt: %.1f m",
                           Math.abs(latitude),  latDir,
                           Math.abs(longitude), lonDir,
                           altitude);
            lastMovement = String.format("Speed: %.1f km/h\nCourse: %.1f°\nHDOP: %.2f\nFix: %s",
                           speed * 3.6f, bearing, hdop, fixStr);
            lastHeading  = String.format("%.1f°", bearing);
        }

        updateNotification(String.format("%.5f, %.5f  %s  ±%.0f m",
                latitude, longitude, fixStr, accuracy));

        sendBroadcast(new Intent(ACTION_STATUS)
                .putExtra("signal",           lastSignal)
                .putExtra("position",         lastPos)
                .putExtra("movement",         lastMovement)
                .putExtra("serial",           lastSerialLog)
                .putExtra("satellites",       lastSatellites)
                .putExtra("bytes",            totalBytes)
                .putExtra("sents",            totalSents)
                .putExtra("fixtime",          lastFixTime)
                .putExtra("satsInView",       lastSatsInView)
                .putExtra("satsUsed",         lastSatsUsed)
                .putExtra("heading",          String.format("%.1f°", bearing))
                .putExtra("constellationJson", lastConstellationJson));
    }

    private void broadcastSerial() {
        sendBroadcast(new Intent(ACTION_STATUS)
                .putExtra("serial",           lastSerialLog)
                .putExtra("bytes",            totalBytes)
                .putExtra("sents",            totalSents)
                .putExtra("satsInView",       lastSatsInView)
                .putExtra("satsUsed",         lastSatsUsed)
                .putExtra("satellites",       lastSatellites)
                .putExtra("constellationJson", lastConstellationJson));
    }

    // ── Serial log ────────────────────────────────────────────────────────────

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

    // ── Fix / compass labels ──────────────────────────────────────────────────

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

    // ── Serial I/O error handling ─────────────────────────────────────────────

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "Serial I/O error", e);
        hasGGA = false;
        hasRMC = false;
        gpsTimeMs = 0;
        stopIo();
        if (!active.get()) return;

        retryCount++;
        if (retryCount > MAX_AUTO_RETRIES) {
            broadcastConn("USB disconnected – tap Start to reconnect");
            retryCount = 0;
            return;
        }
        broadcastConn("Disconnected – retrying in 3 s (" + retryCount + "/" + MAX_AUTO_RETRIES + ")");
        if (retryExecutor != null && !retryExecutor.isShutdown()) {
            retryExecutor.schedule(
                    () -> { if (active.get()) startConnectThread(); },
                    RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void stopIo() {
        if (ioManager != null)  { ioManager.stop(); ioManager = null; }
        if (serialPort != null) {
            try { serialPort.close(); } catch (IOException ignored) {}
            serialPort = null;
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "GPSLink GPS", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Live GPS from u-blox receiver");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPSLink · " + currentHz + " Hz")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(text));
    }
}
