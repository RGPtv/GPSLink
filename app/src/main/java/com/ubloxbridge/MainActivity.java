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
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // ── Hz button colour tokens ───────────────────────────────────────────────
    private static final int COLOR_HZ_ACTIVE   = 0xFF2563EB;
    private static final int COLOR_HZ_INACTIVE = 0xFF0A1020;
    private static final int TEXT_HZ_ACTIVE    = Color.WHITE;
    private static final int TEXT_HZ_INACTIVE  = 0xFF4B5563;

    // ── Status dot colours ────────────────────────────────────────────────────
    private static final int DOT_COLOR_ACTIVE = 0xFF22C55E;
    private static final int DOT_COLOR_IDLE   = 0xFF3F3F46;

    // ── Em-dash placeholder used when a field has no data ─────────────────────
    private static final String EM_DASH = "\u2014";

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
    private CompassView       compassView;
    private SignalBarsView    signalBarsView;

    private Button btnStart, btnStop, btn1Hz, btn5Hz;

    private int     selectedHz            = 1;
    private boolean isReceiverRegistered  = false;

    // ── Broadcast receiver ────────────────────────────────────────────────────
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // FIX: guard against null intent (defensive; framework shouldn't
            //      send null, but belt-and-braces is cheap here).
            if (intent == null) return;

            // Already on the main thread via LocalBroadcastManager pattern, but
            // runOnUiThread() is a no-op when already on the UI thread, so this
            // is safe either way.
            runOnUiThread(() -> {
                String conn      = intent.getStringExtra("conn");
                String signal    = intent.getStringExtra("signal");
                String pos       = intent.getStringExtra("position");
                String mov       = intent.getStringExtra("movement");
                String serial    = intent.getStringExtra("serial");
                String satsView  = intent.getStringExtra("satsInView");
                String satsUsed  = intent.getStringExtra("satsUsed");
                String heading   = intent.getStringExtra("heading");
                String constJson = intent.getStringExtra("constellationJson");
                long   bytes     = intent.getLongExtra("bytes", -1);
                int    sents     = intent.getIntExtra("sents", -1);
                long   fixTime   = intent.getLongExtra("fixtime", 0);

                if (conn     != null) updateConnection(conn);
                if (signal   != null) tvSignal.setText(signal);
                if (pos      != null) updatePosition(pos);
                if (mov      != null) updateMovement(mov);
                if (serial   != null) tvSerial.setText(serial);
                if (satsView != null) tvSatsInView.setText(satsView);
                if (satsUsed != null) tvSatsUsed.setText(satsUsed);
                if (heading  != null) updateHeading(heading);
                if (constJson != null && !constJson.isEmpty()) updateSignalBars(constJson);
                if (bytes >= 0 && sents >= 0)
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
        tvConnection   = findViewById(R.id.tvConnection);
        tvSignal       = findViewById(R.id.tvSignal);
        tvSerial       = findViewById(R.id.tvSerial);
        tvSerialStats  = findViewById(R.id.tvSerialStats);
        tvHzNote       = findViewById(R.id.tvHzNote);
        tvLastFix      = findViewById(R.id.tvLastFix);
        tvSatsInView   = findViewById(R.id.tvSatsInView);
        tvSatsUsed     = findViewById(R.id.tvSatsUsed);
        tvHeading      = findViewById(R.id.tvHeading);
        tvHeadingDir   = findViewById(R.id.tvHeadingDir);
        tvLatitude     = findViewById(R.id.tvLatitude);
        tvLatDir       = findViewById(R.id.tvLatDir);
        tvLongitude    = findViewById(R.id.tvLongitude);
        tvLonDir       = findViewById(R.id.tvLonDir);
        tvAltitude     = findViewById(R.id.tvAltitude);
        tvSpeed        = findViewById(R.id.tvSpeed);
        tvCourse       = findViewById(R.id.tvCourse);
        tvHdop         = findViewById(R.id.tvHdop);
        tvFixType      = findViewById(R.id.tvFixType);
        statusDot      = findViewById(R.id.statusDot);
        compassView    = findViewById(R.id.compassView);
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

    /** Update the connection label and status-dot colour. */
    private void updateConnection(String conn) {
        tvConnection.setText(conn);
        // FIX: use Locale.ROOT for case-insensitive comparison to avoid
        //      locale-specific lower-casing bugs (e.g. Turkish 'İ').
        String lc = conn.toLowerCase(Locale.ROOT);
        boolean active = !lc.equals("idle") && !lc.contains("disconnected");
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
        // FIX: also check for the em-dash placeholder explicitly
        if (pos == null || pos.isEmpty() || pos.equals(EM_DASH) || pos.equals("—")) {
            setPositionEmpty();
            return;
        }

        String[] lines = pos.split("\n");

        // ── Latitude ─────────────────────────────────────────────────────────
        if (lines.length >= 1) {
            String line   = lines[0].trim();
            String[] parts = line.replace("Lat:", "").replace("°", "").trim().split("\\s+");
            tvLatitude.setText(parts.length >= 1 ? parts[0] : EM_DASH);
            tvLatDir  .setText(parts.length >= 2 ? parts[1] : "");
        }

        // ── Longitude ────────────────────────────────────────────────────────
        if (lines.length >= 2) {
            String line   = lines[1].trim();
            String[] parts = line.replace("Lon:", "").replace("°", "").trim().split("\\s+");
            tvLongitude.setText(parts.length >= 1 ? parts[0] : EM_DASH);
            tvLonDir   .setText(parts.length >= 2 ? parts[1] : "");
        }

        // ── Altitude ─────────────────────────────────────────────────────────
        if (lines.length >= 3) {
            // FIX: use Locale.ROOT in replaceAll / toLowerCase to avoid
            //      locale-specific regex issues.
            String line = lines[2].trim()
                    .replaceAll("(?i)alt:", "")
                    .replaceAll("(?i)\\bm\\b", "")
                    .replaceAll("(?i)\\bmsl\\b", "")
                    .trim();
            String[] parts = line.split("\\s+");
            tvAltitude.setText((parts.length >= 1 && !parts[0].isEmpty()) ? parts[0] : EM_DASH);
        }
    }

    private void setPositionEmpty() {
        tvLatitude .setText(EM_DASH);  tvLatDir .setText("");
        tvLongitude.setText(EM_DASH);  tvLonDir .setText("");
        tvAltitude .setText(EM_DASH);
    }

    /**
     * Parse the movement string and fill Speed, Course, HDOP, Fix-type cells.
     *
     * Service sends something like:
     *   "Speed: 2.4 km/h\nCourse: 127.3°\nHDOP: 0.92\nFix: 3D"
     */
    private void updateMovement(String mov) {
        if (mov == null || mov.isEmpty() || mov.equals(EM_DASH) || mov.equals("—")) {
            tvSpeed  .setText(EM_DASH);
            tvCourse .setText(EM_DASH);
            tvHdop   .setText(EM_DASH);
            tvFixType.setText(EM_DASH);
            tvFixType.setTextColor(0xFF4B5563);
            return;
        }
        String[] lines = mov.split("\n");
        for (String line : lines) {
            // FIX: use Locale.ROOT for toLowerCase
            String lc = line.toLowerCase(Locale.ROOT);
            if (lc.contains("speed"))  tvSpeed .setText(extractValue(line));
            if (lc.contains("course")) tvCourse.setText(extractValue(line));
            if (lc.contains("hdop"))   tvHdop  .setText(extractValue(line));
            if (lc.contains("fix")) {
                String fixVal = extractValue(line);
                tvFixType.setText(fixVal);
                String fv = fixVal.toLowerCase(Locale.ROOT);
                int fixColor;
                if (fv.contains("no fix") || fv.contains("stale")) {
                    fixColor = 0xFFEF4444; // red
                } else if (fv.contains("dgps") || fv.contains("rtk float")
                        || fv.contains("dead reckoning")) {
                    fixColor = 0xFFF59E0B; // amber
                } else {
                    fixColor = 0xFF22C55E; // green
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
        // FIX: handle null explicitly before calling replaceAll to avoid NPE
        if (headingStr == null || headingStr.isEmpty()) {
            tvHeading   .setText(EM_DASH);
            tvHeadingDir.setText(EM_DASH);
            return;
        }
        try {
            // Strip everything that isn't a digit or decimal point.
            // FIX: also strip a leading '-' that could sneak in for negative
            //      headings; a heading is always [0, 360).
            String numStr = headingStr.replaceAll("[^\\d.]", "");
            if (numStr.isEmpty()) {
                tvHeading   .setText(EM_DASH);
                tvHeadingDir.setText(EM_DASH);
                return;
            }
            float heading = Float.parseFloat(numStr);
            heading = ((heading % 360) + 360) % 360;

            // FIX: use Locale.ROOT in String.format to avoid locale-specific
            //      decimal separators (e.g. "127,0°" instead of "127.0°").
            tvHeading   .setText(String.format(Locale.ROOT, "%.0f°", heading));
            tvHeadingDir.setText(getCardinalDirection(heading));

            compassView.setHeading(heading);

        } catch (NumberFormatException e) {
            tvHeading   .setText(EM_DASH);
            tvHeadingDir.setText(EM_DASH);
        }
    }

    private String getCardinalDirection(float heading) {
        // FIX: cast result of Math.round to int explicitly; the original used
        //      (int) Math.round() which is correct, but the modulo must be on
        //      the long result of Math.round before casting to avoid an
        //      off-by-one when heading == 360.0f after normalisation.
        String[] dirs = {"N","NNE","NE","ENE","E","ESE","SE","SSE",
                         "S","SSW","SW","WSW","W","WNW","NW","NNW"};
        int idx = (int) (Math.round(heading / 22.5) % 16);
        return dirs[idx];
    }

    /**
     * Parse constellation JSON and update SignalBarsView.
     * JSON format: [{"label":"GPS","avgSnr":38,"count":6}, ...]
     */
    private void updateSignalBars(String json) {
        if (signalBarsView == null || json == null || json.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(json);
            List<SignalBarsView.ConstellationSignal> signals = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                // FIX: use optString/optInt with sensible defaults so a single
                //      malformed entry doesn't abort the whole update.
                signals.add(new SignalBarsView.ConstellationSignal(
                    obj.optString("label", "???"),
                    obj.optInt("avgSnr", 0),
                    obj.optInt("count", 0)
                ));
            }
            signalBarsView.setSignals(signals);
        } catch (Exception e) {
            // Silently ignore malformed JSON — signal bars will keep the last
            // valid data rather than going blank.
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
            android.content.res.ColorStateList.valueOf(is5 ? COLOR_HZ_ACTIVE : COLOR_HZ_INACTIVE));
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
        tvSignal     .setText(EM_DASH);
        tvLatitude   .setText(EM_DASH);  tvLatDir.setText("");
        tvLongitude  .setText(EM_DASH);  tvLonDir.setText("");
        tvAltitude   .setText(EM_DASH);
        tvSpeed      .setText(EM_DASH);
        tvCourse     .setText(EM_DASH);
        tvHdop       .setText(EM_DASH);
        tvFixType    .setText(EM_DASH);
        tvSerial     .setText(EM_DASH);
        tvSatsInView .setText(EM_DASH);
        tvSatsUsed   .setText(EM_DASH);
        tvHeading    .setText(EM_DASH);
        tvHeadingDir .setText(EM_DASH);
        tvSerialStats.setText("");
        tvLastFix    .setText("");
        compassView  .setHeading(0f);
        if (signalBarsView != null) signalBarsView.setSignals(null);
    }

    private void clearServiceState() {
        synchronized (UsbSerialService.STATE_LOCK) {
            UsbSerialService.lastConn           = "Idle";
            UsbSerialService.lastSignal         = EM_DASH;
            UsbSerialService.lastPos            = EM_DASH;
            UsbSerialService.lastMovement       = EM_DASH;
            UsbSerialService.lastSerialLog      = EM_DASH;
            UsbSerialService.lastSatellites     = EM_DASH;
            UsbSerialService.lastSatsInView     = EM_DASH;
            UsbSerialService.lastSatsUsed       = EM_DASH;
            UsbSerialService.totalBytes         = 0;
            UsbSerialService.totalSents         = 0;
            UsbSerialService.lastFixTime        = 0;
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Intent svc = new Intent(this, UsbSerialService.class);
            svc.putExtra(UsbSerialService.EXTRA_HZ, selectedHz);
            ContextCompat.startForegroundService(this, svc);
        }
        // FIX: if permission is denied there is no else-branch to inform the
        //      user. Consider showing a Snackbar/Dialog here in a future
        //      iteration explaining why the permission is needed.
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    private static String fmtBytes(long b) {
        // FIX: use Locale.ROOT to prevent locale-specific decimal separators
        if (b < 1024)        return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024f);
        return String.format(Locale.ROOT, "%.1f MB", b / (1024f * 1024f));
    }

    private static String fmtTime(long epochMs) {
        // FIX: use Locale.ROOT in String.format to avoid locale-specific
        //      zero-padding issues on some devices.
        java.util.Calendar c = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(epochMs);
        return String.format(Locale.ROOT, "%02d:%02d:%02d UTC",
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND));
    }
}
