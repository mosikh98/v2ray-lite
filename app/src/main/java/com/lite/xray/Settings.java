package com.lite.xray;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** All user-tunable options + their preset lists. */
final class Settings {
    private Settings() {}

    // last entry of DNS_NAMES is "custom"
    static final String[] DNS_NAMES = {
            "Cloudflare", "Google", "Quad9", "AdGuard", "OpenDNS",
            "شکن (Shecan)", "الکترو (Electro)", "سفارشی"};
    static final String[][] DNS_IPS = {
            {"1.1.1.1", "1.0.0.1"},
            {"8.8.8.8", "8.8.4.4"},
            {"9.9.9.9", "149.112.112.112"},
            {"94.140.14.14", "94.140.15.15"},
            {"208.67.222.222", "208.67.220.220"},
            {"178.22.122.100", "185.51.200.2"},
            {"78.157.42.100", "78.157.42.101"},
            {}};

    static final int[] MTUS = {1280, 1360, 1400, 1450, 1500, 8500};

    static final String[] PING_NAMES = {
            "TCP (اتصال به پورت سرور)",
            "ICMP (پینگ معمولی)",
            "تأخیر واقعی (عبور از Xray)"};

    // last entry of URL_NAMES is "custom"
    static final String[] URL_NAMES = {
            "Cloudflare (cp.cloudflare.com)",
            "Google (gstatic.com)",
            "Firefox (detectportal)",
            "سفارشی"};
    static final String[] URLS = {
            "http://cp.cloudflare.com/generate_204",
            "http://www.gstatic.com/generate_204",
            "http://detectportal.firefox.com/success.txt",
            ""};

    static final int[] TIMEOUTS = {2, 3, 5, 8, 10};

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static void putInt(Context c, String k, int v) {
        sp(c).edit().putInt(k, v).apply();
    }

    static void putStr(Context c, String k, String v) {
        sp(c).edit().putString(k, v).apply();
    }

    // ----- DNS -----
    static int dnsIndex(Context c) {
        int i = sp(c).getInt("dns", 0);
        return (i < 0 || i >= DNS_NAMES.length) ? 0 : i;
    }

    static String dnsCustom(Context c) {
        return sp(c).getString("dns_custom", "");
    }

    static String[] dnsList(Context c) {
        int i = dnsIndex(c);
        if (i == DNS_NAMES.length - 1) {
            List<String> ok = new ArrayList<>();
            for (String p : dnsCustom(c).trim().split("[,\\s]+")) {
                if (p.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) ok.add(p);
            }
            if (!ok.isEmpty()) return ok.toArray(new String[0]);
            return DNS_IPS[0];
        }
        return DNS_IPS[i];
    }

    // ----- MTU -----
    static int mtu(Context c) {
        return sp(c).getInt("mtu", 1500);
    }

    // ----- ping -----
    static int pingMethod(Context c) {
        int i = sp(c).getInt("ping_method", 2);
        return (i < 0 || i >= PING_NAMES.length) ? 2 : i;
    }

    static int urlIndex(Context c) {
        int i = sp(c).getInt("ping_url", 0);
        return (i < 0 || i >= URL_NAMES.length) ? 0 : i;
    }

    static String urlCustom(Context c) {
        return sp(c).getString("ping_url_custom", "");
    }

    static String pingUrl(Context c) {
        int i = urlIndex(c);
        String u = (i == URL_NAMES.length - 1) ? urlCustom(c).trim() : URLS[i];
        if (u.isEmpty()) u = URLS[0];
        if (u.startsWith("https://")) u = "http://" + u.substring(8);
        if (!u.startsWith("http://")) u = "http://" + u;
        return u;
    }

    static int timeoutSec(Context c) {
        return sp(c).getInt("ping_timeout", 5);
    }
}
