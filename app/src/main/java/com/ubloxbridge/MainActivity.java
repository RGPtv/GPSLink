package com.ubloxbridge;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // ── Hz button colour tokens ───────────────────────────────────────────────
    private static final int COLOR_HZ_ACTIVE   = 0xFF2563EB;
    private static final int COLOR_HZ_INACTIVE = 0xFF0A1020;
    private static final int TEXT_HZ_ACTIVE    = Color.WHITE;
    private static final int TEXT_HZ_INACTIVE  = 0xFF4B5563;

    // ── Status dot colours ────────────────────────────────────────────────────
    private static final int DOT_COLOR_ACTIVE  = 0xFF22C55E;
    private static final int DOT_COLOR_IDLE    = 0xFF3F3F46;

    private static final int REQUEST_LOCATION_PERMISSION = 1;

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView tvConnection, tvSignal, tvSerial, tvSerialStats,
                     tvHzNote, tvLastFix, tvSatsInView, tvSatsUsed,
                     tvHeading, tvHeadingDir,
                     tvLatitude, tvLatDir,
                     tvLongitude, tvLonDir,
                     tvAltitude,
                     tvSpeed, tvCourse, tvHdop, tvFixType;

    private android.view.View statusDot;
    private CompassView compassView;
    private SignalBarsView signalBarsView;

    private Button btnStart, btnStop, btn1Hz, btn5Hz;

    private int selectedHz = 1;
    private boolean isReceiverRegistered = false;

    // ── Broadcast receiver ────────────────────────────────────────────────────
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runOnUiThread(() -> {
                String conn      = intent.getStringExtra("conn");
                String signal    = intent.getStringExtra("signal");
                String pos       = intent.getStringExtra("position");
                String mov       = intent.getStringExtra("movement");
                String serial    = intent.getStringExtra("serial");
                String sats      = intent.getStringExtra("satellites");
                String satsView  = intent.getStringExtra("satsInView");
                String satsUsed  = intent.getStringExtra("satsUsed");
                String heading   = intent.getStringExtra("heading");
                String constJson = intent.getStringExtra("constellationJson");
                long   bytes     = intent.getLongExtra("bytes", -1);
                int    sents     = intent.getIntExtra("sents", -1);
                long   fixTime   = intent.getLongExtra("fixtime", 0);

                if (conn   != null) updateConnection(conn);
                if (signal != null) tvSignal.setText(signal);
                if (pos    != null) updatePosition(pos);
                if (mov    != null) updateMovement(mov);
                if (serial != null) tvSerial.setText(serial);
                if (satsView != null) tvSatsInView.setText(satsView);
                if (satsUsed != null) tvSatsUsed.setText(satsUsed);
                if (heading!= null) updateHeading(heading);
                if (constJson != null && !constJson.isEmpty()) updateSignalBars(constJson);
                if (bytes  >= 0 && sents >= 0)
                    tvSerialStats.setText(sents + " sentences · " + fmtBytes(bytes));
                if (fixTime > 0)
                    tvLastFix.setText("Last fix: " + fmtTime(fixTime));
            });
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views
        tvConnection  = findViewById(R.id.tvConnection);
        tvSignal      = findViewById(R.id.tvSignal);
        tvSerial      = findViewById(R.id.tvSerial);
        tvSerialStats = findViewById(R.id.tvSerialStats);
        tvHzNote      = findViewById(R.id.tvHzNote);
        tvLastFix     = findViewById(R.id.tvLastFix);
        tvSatsInView  = findViewById(R.id.tvSatsInView);
        tvSatsUsed    = findViewById(R.id.tvSatsUsed);
        tvHeading     = findViewById(R.id.tvHeading);
        tvHeadingDir  = findViewById(R.id.tvHeadingDir);
        tvLatitude    = findViewById(R.id.tvLatitude);
        tvLatDir      = findViewById(R.id.tvLatDir);
        tvLongitude   = findViewById(R.id.tvLongitude);
        tvLonDir      = findViewById(R.id.tvLonDir);
        tvAltitude    = findViewById(R.id.tvAltitude);
        tvSpeed       = findViewById(R.id.tvSpeed);
        tvCourse      = findViewById(R.id.tvCourse);
        tvHdop        = findViewById(R.id.tvHdop);
        tvFixType     = findViewById(R.id.tvFixType);
        statusDot     = findViewById(R.id.statusDot);
        compassView   = findViewById(R.id.compassView);
        signalBarsView = findViewById(R.id.signalBarsView);

        btnStart = findViewById(R.id.btnStart);
        btnStop  = findViewById(R.id.btnStop);
        btn1Hz   = findViewById(R.id.btn1Hz);
        btn5Hz   = findViewById(R.id.btn5Hz);

        // Hz selector
        selectedHz = UsbSerialService.currentHz;
        applyHzUi(selectedHz);
        btn1Hz.setOnClickListener(v -> setHz(1));
        btn5Hz.setOnClickListener(v -> setHz(5));

        // Start / Stop
        btnStart.setOnClickListener(v -> {
            if (!checkPermissions()) return;
            Intent svc = new Intent(this, UsbSerialService.class);
            svc.putExtra(UsbSerialService.EXTRA_HZ, selectedHz);
            ContextCompat.startForegroundService(this, svc);
        });
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, UsbSerialService.class));
            clearServiceState();
            resetCards();
        });

        if (UsbSerialService.isRunning) restoreState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(UsbSerialService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(statusReceiver, f);
        isReceiverRegistered = true;

        if (UsbSerialService.isRunning) {
            selectedHz = UsbSerialService.currentHz;
            applyHzUi(selectedHz);
            restoreState();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isReceiverRegistered) {
            try { unregisterReceiver(statusReceiver); }
            catch (IllegalArgumentException ignored) {}
            isReceiverRegistered = false;
        }
    }

    // ── UI update helpers ─────────────────────────────────────────────────────

    /**
     * Update the connection label and status-dot colour.
     */
    private void updateConnection(String conn) {
        tvConnection.setText(conn);
        boolean active = !conn.equalsIgnoreCase("Idle")
                      && !conn.toLowerCase().contains("disconnected");
        statusDot.setBackgroundColor(active ? DOT_COLOR_ACTIVE : DOT_COLOR_IDLE);
    }

    /**
     * Parse the position string from UsbSerialService and distribute
     * Latitude, Longitude and Altitude into their own TextViews.
     *
     * The service currently sends a multi-line string such as:
     *   "13.7563° N\n100.5018° E\nAlt: 14.2 m"
     *
     * This method is tolerant of format variations and also accepts
     * the legacy single-line format so nothing breaks if UsbSerialService
     * is not yet updated.
     */
    private void updatePosition(String pos) {
        if (pos == null || pos.equals("—") || pos.isEmpty()) {
            tvLatitude .setText("―");  tvLatDir .setText("");
            tvLongitude.setText("―");  tvLonDir .setText("");
            tvAltitude .setText("―");
            return;
        }

        String[] lines = pos.split("\n");

        // ── Latitude ──────────────────────────────────────────────────────────
        // Expected format: "13.7563° N"
        if (lines.length >= 1) {
            String line = lines[0].trim();
            // Remove "Lat:" prefix if present, strip degree symbol
            String[] parts = line.replace("Lat:", "").replace("°", "").trim().split("\\s+");
            tvLatitude.setText(parts.length >= 1 ? parts[0] : "―");
            tvLatDir  .setText(parts.length >= 2 ? parts[1] : "");
        }

        // ── Longitude ─────────────────────────────────────────────────────────
        // Expected format: "121.0854° E"
        if (lines.length >= 2) {
            String line = lines[1].trim();
            String[] parts = line.replace("Lon:", "").replace("°", "").trim().split("\\s+");
            tvLongitude.setText(parts.length >= 1 ? parts[0] : "―");
            tvLonDir   .setText(parts.length >= 2 ? parts[1] : "");
        }

        // ── Altitude ──────────────────────────────────────────────────────────
        // Expected format: "Alt: 49.9 m"
        if (lines.length >= 3) {
            String line = lines[2].trim()
                    .replaceAll("(?i)alt:", "")
                    .replaceAll("(?i)\\bm\\b", "")
                    .replaceAll("(?i)\\bmsl\\b", "")
                    .trim();
            String[] parts = line.split("\\s+");
            tvAltitude.setText((parts.length >= 1 && !parts[0].isEmpty()) ? parts[0] : "―");
        }
    }

    /**
     * Parse the movement string and fill Speed, Course, HDOP, Fix-type cells.
     *
     * Service sends something like:
     *   "Speed: 2.4 km/h\nCourse: 127.3°\nHDOP: 0.92\nFix: 3D"
     */
    private void updateMovement(String mov) {
        if (mov == null || mov.equals("—") || mov.isEmpty()) {
            tvSpeed  .setText("―");
            tvCourse .setText("―");
            tvHdop   .setText("―");
            tvFixType.setText("―");
            tvFixType.setTextColor(0xFF4B5563); // grey when no data
            return;
        }
        String[] lines = mov.split("\n");
        for (String line : lines) {
            String lc = line.toLowerCase();
            if (lc.contains("speed"))  tvSpeed  .setText(extractValue(line));
            if (lc.contains("course")) tvCourse .setText(extractValue(line));
            if (lc.contains("hdop"))   tvHdop   .setText(extractValue(line));
            if (lc.contains("fix")) {
                String fixVal = extractValue(line);
                tvFixType.setText(fixVal);
                // Color: green for good fix, yellow for degraded, red for no fix
                String fv = fixVal.toLowerCase();
                int fixColor;
                if (fv.contains("no fix") || fv.contains("stale")) {
                    fixColor = 0xFFEF4444; // red
                } else if (fv.contains("dgps") || fv.contains("rtk float")
                        || fv.contains("dead reckoning")) {
                    fixColor = 0xFFF59E0B; // amber
                } else {
                    fixColor = 0xFF22C55E; // green (GPS, PPS, RTK Fixed)
                }
                tvFixType.setTextColor(fixColor);
            }
        }
    }

    /** Extract the value part after the colon in "Label: value" strings. */
    private String extractValue(String line) {
        int colon = line.indexOf(':');
        return colon >= 0 ? line.substring(colon + 1).trim() : line.trim();
    }

    /**
     * Parse the heading string, update the numeric/cardinal labels and
     * smoothly rotate the CompassView needle.
     */
    private void updateHeading(String headingStr) {
        try {
            String numStr = headingStr.replaceAll("[^\\d.]", "");
            if (numStr.isEmpty()) {
                tvHeading   .setText("―");
                tvHeadingDir.setText("―");
                return;
            }
            float heading = Float.parseFloat(numStr);
            heading = ((heading % 360) + 360) % 360;

            tvHeading   .setText(String.format("%.0f°", heading));
            tvHeadingDir.setText(getCardinalDirection(heading));

            // ► Animate the compass needle
            compassView.setHeading(heading);

        } catch (Exception e) {
            tvHeading   .setText("―");
            tvHeadingDir.setText("―");
        }
    }

    private String getCardinalDirection(float heading) {
        String[] dirs = {"N","NNE","NE","ENE","E","ESE","SE","SSE",
                         "S","SSW","SW","WSW","W","WNW","NW","NNW"};
        return dirs[(int) Math.round(heading / 22.5) % 16];
    }

    /**
     * Parse constellation JSON and update SignalBarsView.
     * JSON format: [{"label":"GPS","avgSnr":38,"count":6}, ...]
     */
    private void updateSignalBars(String json) {
        if (signalBarsView == null || json == null || json.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(json);
            List<SignalBarsView.ConstellationSignal> signals = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                signals.add(new SignalBarsView.ConstellationSignal(
                    obj.getString("label"),
                    obj.getInt("avgSnr"),
                    obj.getInt("count")
                ));
            }
            signalBarsView.setSignals(signals);
        } catch (Exception e) {
            // silently ignore malformed JSON
        }
    }

    // ── Hz UI ─────────────────────────────────────────────────────────────────

    private void setHz(int hz) {
        selectedHz = hz;
        applyHzUi(hz);
        if (UsbSerialService.isRunning) {
            Intent intent = new Intent(UsbSerialService.ACTION_SET_HZ);
            intent.putExtra(UsbSerialService.EXTRA_HZ, hz);
            sendBroadcast(intent);
        }
    }

    private void applyHzUi(int hz) {
        boolean is5 = (hz == 5);
        btn1Hz.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(is5 ? COLOR_HZ_INACTIVE : COLOR_HZ_ACTIVE));
        btn5Hz.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(is5 ? COLOR_HZ_ACTIVE   : COLOR_HZ_INACTIVE));
        btn1Hz.setTextColor(is5 ? TEXT_HZ_INACTIVE : TEXT_HZ_ACTIVE);
        btn5Hz.setTextColor(is5 ? TEXT_HZ_ACTIVE   : TEXT_HZ_INACTIVE);
        tvHzNote.setText(is5
            ? "5 Hz · 5 updates / second (UBX-CFG-RATE)"
            : "1 Hz · 1 update per second");
    }

    // ── State restore / reset ─────────────────────────────────────────────────

    private void restoreState() {
        synchronized (UsbSerialService.STATE_LOCK) {
            updateConnection(UsbSerialService.lastConn);
            tvSignal    .setText(UsbSerialService.lastSignal);
            updatePosition(UsbSerialService.lastPos);
            updateMovement(UsbSerialService.lastMovement);
            updateHeading(UsbSerialService.lastHeading);
            updateSignalBars(UsbSerialService.lastConstellationJson);
            tvSerial    .setText(UsbSerialService.lastSerialLog);
            tvSatsInView.setText(UsbSerialService.lastSatsInView);
            tvSatsUsed  .setText(UsbSerialService.lastSatsUsed);
            long bytes = UsbSerialService.totalBytes;
            int  sents = UsbSerialService.totalSents;
            if (bytes >= 0 && sents >= 0)
                tvSerialStats.setText(sents + " sentences · " + fmtBytes(bytes));
            long ft = UsbSerialService.lastFixTime;
            if (ft > 0) tvLastFix.setText("Last fix: " + fmtTime(ft));
        }
    }

    private void resetCards() {
        tvConnection .setText("Idle");
        statusDot    .setBackgroundColor(DOT_COLOR_IDLE);
        tvSignal     .setText("\u2014");
        tvLatitude   .setText("\u2014");  tvLatDir.setText("");
        tvLongitude  .setText("\u2014");  tvLonDir.setText("");
        tvAltitude   .setText("\u2014");
        tvSpeed      .setText("\u2014");
        tvCourse     .setText("\u2014");
        tvHdop       .setText("\u2014");
        tvFixType    .setText("\u2014");
        tvSerial     .setText("\u2014");
        tvSatsInView .setText("\u2014");
        tvSatsUsed   .setText("\u2014");
        tvHeading    .setText("\u2014");
        tvHeadingDir .setText("\u2014");
        tvSerialStats.setText("");
        tvLastFix    .setText("");
        compassView  .setHeading(0f);
        if (signalBarsView != null) signalBarsView.setSignals(null);
    }

    private void clearServiceState() {
        synchronized (UsbSerialService.STATE_LOCK) {
            UsbSerialService.lastConn       = "Idle";
            UsbSerialService.lastSignal     = "\u2014";
            UsbSerialService.lastPos        = "\u2014";
            UsbSerialService.lastMovement   = "\u2014";
            UsbSerialService.lastSerialLog  = "\u2014";
            UsbSerialService.lastSatellites = "\u2014";
            UsbSerialService.lastSatsInView = "\u2014";
            UsbSerialService.lastSatsUsed   = "\u2014";
            UsbSerialService.totalBytes     = 0;
            UsbSerialService.totalSents     = 0;
            UsbSerialService.lastFixTime    = 0;
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private boolean checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_LOCATION_PERMISSION);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Intent svc = new Intent(this, UsbSerialService.class);
            svc.putExtra(UsbSerialService.EXTRA_HZ, selectedHz);
            ContextCompat.startForegroundService(this, svc);
        }
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    private static String fmtBytes(long b) {
        if (b < 1024)        return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024f);
        return String.format("%.1f MB", b / (1024f * 1024f));
    }

    private static String fmtTime(long epochMs) {
        java.util.Calendar c = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(epochMs);
        return String.format("%02d:%02d:%02d UTC",
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND));
    }
}
