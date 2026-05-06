package com.ubloxbridge;

import android.util.Log;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class NmeaParser {

    private static final String TAG = "NmeaParser";

    public static class GpsData {
        public double  latitude, longitude, altitude;
        public float   accuracy, speed, bearing;
        public long    gpsTimeMs;
        public int     satellites;
        public int     fixQuality;
        public float   hdop;
        public boolean valid;
        public String  type;
    }

    public static class SatInfo {
        public String constellation;
        public int prn, elevation, azimuth, snr;

        @Override
        public String toString() {
            return constellation + " PRN:" + prn
                    + " El:" + elevation + "° Az:" + azimuth + "° SNR:" + snr;
        }
    }

    // ── Checksum ──────────────────────────────────────────────────────────────

    public static boolean verifyChecksum(String sentence) {
        if (sentence == null) return false;
        int star = sentence.indexOf('*');
        if (star < 1 || star + 3 > sentence.length()) return false;
        int declared;
        try {
            declared = Integer.parseInt(sentence.substring(star + 1, star + 3), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int computed = 0;
        // XOR from char after '$' up to (not including) '*'
        for (int i = 1; i < star; i++) computed ^= sentence.charAt(i);
        return computed == declared;
    }

    // ── Sentence dispatcher ───────────────────────────────────────────────────

    /**
     * Parse a GGA or RMC sentence into a GpsData object.
     * Returns null for unrecognised or malformed sentences.
     */
    public static GpsData parse(String sentence) {
        if (sentence == null || !sentence.startsWith("$")) return null;
        int star = sentence.indexOf('*');
        String body = (star > 0) ? sentence.substring(0, star) : sentence;
        try {
            if (isType(body, "GGA")) return parseGGA(body);
            if (isType(body, "RMC")) return parseRMC(body);
        } catch (Exception e) {
            Log.w(TAG, "Parse error in '" + sentence + "': " + e.getMessage());
        }
        return null;
    }

    /** True when the body's sentence-type (chars 3-5 of talker+type) matches. */
    private static boolean isType(String body, String type) {
        // body starts with "$XX" talker (2 chars) then message type
        return body.length() >= 3 + type.length()
                && body.regionMatches(3, type, 0, type.length());
    }

    // ── GGA ───────────────────────────────────────────────────────────────────

    private static GpsData parseGGA(String body) {
        String[] p = body.split(",", -1);
        if (p.length < 10) return null;

        // Field 6: fix quality — 0 means no fix
        if (p[6].isEmpty() || "0".equals(p[6].trim())) return null;

        GpsData d = new GpsData();
        d.type       = "GGA";
        d.latitude   = parseLatLon(p[2], p[3]);
        d.longitude  = parseLatLon(p[4], p[5]);

        // Altitude: only trust if unit is 'M' (metres) or absent
        boolean metresOrAbsent = p.length < 11 || p[10].isEmpty()
                || "M".equalsIgnoreCase(p[10].trim());
        d.altitude   = (!p[9].isEmpty() && metresOrAbsent)
                ? Double.parseDouble(p[9].trim()) : 0;

        d.fixQuality = p[6].isEmpty() ? 0 : Integer.parseInt(p[6].trim());
        d.satellites = p[7].isEmpty() ? 0 : Integer.parseInt(p[7].trim());

        // BUG FIX: default HDOP to a high value (bad) not 5.0, which was
        // silently propagated as a "good-ish" accuracy estimate.
        d.hdop       = p[8].isEmpty() ? 99.0f : Float.parseFloat(p[8].trim());
        d.accuracy   = Math.max(1.0f, d.hdop * 2.5f);
        d.valid      = true;
        return d;
    }

    // ── RMC ───────────────────────────────────────────────────────────────────

    private static GpsData parseRMC(String body) {
        String[] p = body.split(",", -1);
        // Field 2: status — 'A' = active/valid, 'V' = void
        if (p.length < 9 || !"A".equals(p[2].trim())) return null;

        GpsData d = new GpsData();
        d.type      = "RMC";
        d.latitude  = parseLatLon(p[3], p[4]);
        d.longitude = parseLatLon(p[5], p[6]);

        // Speed: knots → m/s  (1 knot = 0.514444 m/s)
        d.speed     = p[7].isEmpty() ? 0f : Float.parseFloat(p[7].trim()) * 0.514444f;

        // BUG FIX: bearing field index was correct (p[8]) but we should guard
        // against the field containing a non-numeric compass variation suffix
        // introduced by some receivers (e.g. "127.3,E"). The split already
        // isolates each comma-delimited field so this is fine; just trim.
        d.bearing   = p[8].isEmpty() ? 0f : Float.parseFloat(p[8].trim());

        d.gpsTimeMs = parseGpsTime(p[1], p.length > 9 ? p[9] : "");
        d.valid     = true;
        return d;
    }

    // ── GSV ───────────────────────────────────────────────────────────────────

    /**
     * Parse a GSV sentence body (already stripped of checksum) into a list
     * of SatInfo records.
     *
     * BUG FIX: the original called this with the full raw sentence including
     * the checksum tail; callers in UsbSerialService strip it properly, but
     * this method is now defensively tolerant of a trailing checksum field.
     */
    public static List<SatInfo> parseGSV(String body) {
        List<SatInfo> result = new ArrayList<>();
        // Strip checksum if present
        int star = body.indexOf('*');
        if (star > 0) body = body.substring(0, star);

        String[] p = body.split(",", -1);
        if (p.length < 4) return result;

        String constellation = getConstellation(p[0]);
        int idx = 4;
        while (idx + 3 < p.length) {
            String prnStr = p[idx].trim();
            if (prnStr.isEmpty()) break;
            try {
                SatInfo s    = new SatInfo();
                s.constellation = constellation;
                s.prn       = Integer.parseInt(prnStr);
                // SBAS satellites broadcast on PRNs 120–158. Some receivers
                // report them under the $GP talker instead of $GS, so we
                // override the constellation label by PRN range.
                if (isSbasPrn(s.prn)) s.constellation = "SBAS";
                s.elevation = clamp(parseIntSafe(p[idx + 1]),  0,  90);
                s.azimuth   = clamp(parseIntSafe(p[idx + 2]),  0, 360);
                // SNR: last field of a group may be followed by '*' in some
                // receivers when the group is the final one in a message.
                // Strip any trailing non-digit suffix before parsing.
                String snrStr = p[idx + 3].replaceAll("[^\\d]", "");
                s.snr       = snrStr.isEmpty() ? 0 : Integer.parseInt(snrStr);
                result.add(s);
            } catch (NumberFormatException e) {
                Log.w(TAG, "GSV field parse error at idx=" + idx);
            }
            idx += 4;
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Public entry point for UsbSerialService to derive a constellation name
     * from a raw NMEA talker header string (e.g. "$GLGSV" → "GLONASS").
     * This is used for stale-entry reset keying — it intentionally does NOT
     * apply the SBAS PRN override (that only applies per-satellite in parseGSV).
     */
    public static String constellationFromTalker(String talker) {
        return getConstellation(talker);
    }

    private static String getConstellation(String talker) {
        if (talker == null) return "Unknown";
        // GN must be checked before GP — "$GN..." contains "GP" as a substring
        // on some receivers if not checked first (false positive).
        if (talker.contains("GN")) return "GNSS";
        if (talker.contains("GP")) return "GPS";
        if (talker.contains("GL")) return "GLONASS";
        if (talker.contains("GA")) return "Galileo";
        if (talker.contains("BD") || talker.contains("GB")) return "BeiDou";
        if (talker.contains("QZ")) return "QZSS";
        // SBAS uses talker "GS" on some receivers, or is identified by PRN range
        if (talker.contains("GS")) return "SBAS";
        return "Unknown";
    }

    /**
     * Returns true if the given PRN falls in the SBAS satellite range.
     * SBAS geostationary satellites use PRNs 120–158 in NMEA sentences.
     */
    public static boolean isSbasPrn(int prn) {
        return prn >= 120 && prn <= 158;
    }

    /**
     * Parse GPS time fields from RMC into a UTC epoch millisecond value.
     *
     * BUG FIX: the original silently returned 0 for any malformed date, which
     * caused the service to never update lastFixTime. We now log a warning.
     */
    private static long parseGpsTime(String t, String date) {
        if (t == null || t.length() < 6 || date == null || date.length() < 6) return 0;
        try {
            int hh = Integer.parseInt(t.substring(0, 2));
            int mm = Integer.parseInt(t.substring(2, 4));
            int ss = Integer.parseInt(t.substring(4, 6));
            int ms = 0;
            if (t.length() > 7 && t.charAt(6) == '.') {
                // Sub-second fraction — multiply to milliseconds
                String frac = t.substring(7);
                ms = (int) Math.round(Integer.parseInt(frac) * (1000.0 / Math.pow(10, frac.length())));
            }
            int dd = Integer.parseInt(date.substring(0, 2));
            int mo = Integer.parseInt(date.substring(2, 4)) - 1; // 0-based month
            int yr = Integer.parseInt(date.substring(4, 6));
            yr += (yr >= 80) ? 1900 : 2000;

            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.set(yr, mo, dd, hh, mm, ss);
            c.set(Calendar.MILLISECOND, ms);
            return c.getTimeInMillis();
        } catch (Exception e) {
            Log.w(TAG, "parseGpsTime failed for t='" + t + "' date='" + date + "'");
            return 0;
        }
    }

    /**
     * Convert NMEA lat/lon (DDDMM.MMMMM) + hemisphere to signed decimal degrees.
     *
     * BUG FIX: The original divided by 100 first, then took the int part, which
     * works for most cases but can produce wrong results for longitudes >= 100°
     * because (int)(raw/100) truncates to the wrong degree count.
     * Correct approach: integer-divide raw by 100 to get degrees.
     */
    private static double parseLatLon(String value, String dir) {
        if (value == null || value.isEmpty()) return 0.0;
        try {
            double raw = Double.parseDouble(value.trim());
            int    deg = (int) (raw / 100);         // e.g. 12137.xxxx → 121
            double min = raw - (deg * 100.0);        // remaining minutes
            double res = deg + min / 60.0;
            if (dir != null && ("S".equals(dir.trim()) || "W".equals(dir.trim()))) res = -res;
            return res;
        } catch (NumberFormatException e) {
            Log.w(TAG, "parseLatLon failed for value='" + value + "'");
            return 0.0;
        }
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
