package com.ubloxbridge;

import android.util.Log; // ✅ FIX: Added missing import
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class NmeaParser {
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
        @Override public String toString() {
            return constellation + " PRN:" + prn + " El:" + elevation + "° Az:" + azimuth + "° SNR:" + snr;
        }
    }

    public static boolean verifyChecksum(String sentence) {
        int star = sentence.indexOf('*');
        if (star < 0 || star + 3 > sentence.length()) return false;
        int declared;
        try { declared = Integer.parseInt(sentence.substring(star + 1, star + 3), 16); }
        catch (NumberFormatException e) { return false; }
        int computed = 0;
        for (int i = 1; i < star; i++) computed ^= sentence.charAt(i);
        return computed == declared;
    }

    public static GpsData parse(String sentence) {
        if (sentence == null || !sentence.startsWith("$")) return null;
        int star = sentence.indexOf('*');
        String body = (star > 0) ? sentence.substring(0, star) : sentence;
        try {
            if (body.startsWith("$GPGGA") || body.startsWith("$GNGGA") || body.startsWith("$GLGGA")
                    || body.startsWith("$GAGGA") || body.startsWith("$GBGGA") || body.startsWith("$BDGGA"))
                return parseGGA(body);
            if (body.startsWith("$GPRMC") || body.startsWith("$GNRMC") || body.startsWith("$GLRMC")
                    || body.startsWith("$GARMC") || body.startsWith("$GBRMC") || body.startsWith("$BDRMC"))
                return parseRMC(body);
        } catch (Exception e) { 
            Log.w("NmeaParser", "Parse error: " + e.getMessage()); 
        }
        return null;
    }

    private static GpsData parseGGA(String body) {
        String[] p = body.split(",", -1);
        if (p.length < 10) return null;
        if (p[6].isEmpty() || "0".equals(p[6])) return null;

        GpsData d = new GpsData();
        d.type = "GGA";
        d.latitude  = parseLatLon(p[2], p[3]);
        d.longitude = parseLatLon(p[4], p[5]);
        boolean metresOrAbsent = p.length < 11 || p[10].isEmpty() || "M".equalsIgnoreCase(p[10]);
        d.altitude  = (!p[9].isEmpty() && metresOrAbsent) ? Double.parseDouble(p[9]) : 0;
        d.fixQuality = p[6].isEmpty() ? 0 : Integer.parseInt(p[6]);
        d.satellites = p[7].isEmpty() ? 0 : Integer.parseInt(p[7]);
        d.hdop       = p[8].isEmpty() ? 5.0f : Float.parseFloat(p[8]);
        d.accuracy   = Math.max(1.0f, d.hdop * 2.5f);
        d.valid = true;
        return d;
    }

    private static GpsData parseRMC(String body) {
        String[] p = body.split(",", -1);
        if (p.length < 9 || !"A".equals(p[2])) return null;
        GpsData d = new GpsData();
        d.type = "RMC";
        d.latitude  = parseLatLon(p[3], p[4]);
        d.longitude = parseLatLon(p[5], p[6]);
        d.speed     = p[7].isEmpty() ? 0 : Float.parseFloat(p[7]) * 0.5144f;
        d.bearing   = p[8].isEmpty() ? 0 : Float.parseFloat(p[8]);
        d.gpsTimeMs = parseGpsTime(p[1], p.length > 9 ? p[9] : "");
        d.valid = true;
        return d;
    }

    public static List<SatInfo> parseGSV(String body) {
        List<SatInfo> result = new ArrayList<>();
        String[] p = body.split(",", -1);
        if (p.length < 4) return result;
        String constellation = getConstellation(p[0]);
        int idx = 4;
        while (idx + 3 < p.length) {
            String prnStr = p[idx].trim();
            if (prnStr.isEmpty()) break;
            try {
                SatInfo s = new SatInfo();
                s.constellation = constellation;
                s.prn = Integer.parseInt(prnStr);
                s.elevation = p[idx + 1].isEmpty() ? 0 : Math.min(90, Math.max(0, Integer.parseInt(p[idx + 1])));
                s.azimuth   = p[idx + 2].isEmpty() ? 0 : Math.min(360, Math.max(0, Integer.parseInt(p[idx + 2])));
                s.snr       = p[idx + 3].isEmpty() ? 0 : Integer.parseInt(p[idx + 3]);
                result.add(s);
            } catch (NumberFormatException ignored) {}
            idx += 4;
        }
        return result;
    }

    private static String getConstellation(String talker) {
        if (talker.contains("GP")) return "GPS";
        if (talker.contains("GL")) return "GLONASS";
        if (talker.contains("GA")) return "Galileo";
        if (talker.contains("BD") || talker.contains("GB")) return "BeiDou";
        if (talker.contains("QZ")) return "QZSS";
        return "Unknown";
    }

    private static long parseGpsTime(String t, String date) {
        if (t.length() < 6 || date.length() < 6) return 0;
        try {
            int hh = Integer.parseInt(t.substring(0, 2));
            int mm = Integer.parseInt(t.substring(2, 4));
            int ss = Integer.parseInt(t.substring(4, 6));
            int ms = t.length() > 6 && t.charAt(6) == '.' ? (int)(Double.parseDouble("0" + t.substring(6)) * 1000) : 0;
            int dd = Integer.parseInt(date.substring(0, 2));
            int mo = Integer.parseInt(date.substring(2, 4)) - 1;
            int yr = Integer.parseInt(date.substring(4, 6));
            yr += (yr >= 80) ? 1900 : 2000;
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.set(yr, mo, dd, hh, mm, ss);
            c.set(Calendar.MILLISECOND, ms);
            return c.getTimeInMillis();
        } catch (Exception e) { return 0; }
    }

    private static double parseLatLon(String value, String dir) {
        if (value == null || value.isEmpty()) return 0;
        double raw = Double.parseDouble(value);
        int deg = (int)(raw / 100);
        double min = raw - (deg * 100.0);
        double res = deg + min / 60.0;
        if (dir != null && ("S".equals(dir) || "W".equals(dir))) res = -res;
        return res;
    }
}
