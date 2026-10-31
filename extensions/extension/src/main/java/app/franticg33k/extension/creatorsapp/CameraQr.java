package app.franticg33k.extension.creatorsapp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sony camera Wi-Fi QR: "W01:S:&lt;id&gt;;P:&lt;password&gt;;C:&lt;model&gt;;M:&lt;mac&gt;;" (Imaging Edge's WifiQrData, as
 * decoded by ZVLink). The hotspot is "DIRECT-&lt;id&gt;:&lt;model&gt;". Values may escape ; : \ with a backslash.
 */
public final class CameraQr {
    public final String ssid;
    public final String pass;
    /** Colon-separated MAC, or null when the QR has none / it is malformed. */
    public final String bssid;

    private CameraQr(String ssid, String pass, String bssid) {
        this.ssid = ssid;
        this.pass = pass;
        this.bssid = bssid;
    }

    private static String field(String raw, String tag) {
        Matcher m = Pattern.compile("(?:^|;|:)" + tag + ":((?:\\\\.|[^;\\\\])*)").matcher(raw);
        return m.find() ? m.group(1).replaceAll("\\\\(.)", "$1") : null;
    }

    /** Null when [raw] is not a Sony camera Wi-Fi QR. */
    public static CameraQr parse(String raw) {
        if (raw == null || !raw.startsWith("W")) return null;
        String s = field(raw, "S");
        String p = field(raw, "P");
        String c = field(raw, "C");
        if (s == null || p == null || c == null) return null;
        String mac = field(raw, "M");
        String bssid = null;
        if (mac != null) {
            String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
            if (hex.length() == 12) bssid = hex.replaceAll("(..)(?!$)", "$1:");
        }
        return new CameraQr("DIRECT-" + s + ":" + c, p, bssid);
    }

    /** Self-check: java -cp ... app.franticg33k.extension.creatorsapp.CameraQr */
    public static void main(String[] args) {
        CameraQr q = parse("W01:S:abcd;P:pa\\;ss;C:ZV-E10;M:AA-BB-CC-DD-EE-FF;");
        if (q == null || !q.ssid.equals("DIRECT-abcd:ZV-E10") || !q.pass.equals("pa;ss")
                || !"AA:BB:CC:DD:EE:FF".equals(q.bssid) || parse("https://x") != null) {
            throw new AssertionError("CameraQr.parse");
        }
        System.out.println("ok");
    }
}
