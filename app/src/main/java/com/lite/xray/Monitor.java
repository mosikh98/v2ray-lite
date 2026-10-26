package com.lite.xray;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

/** Reads CPU time + RSS of our process and the xray child straight from /proc (a few bytes per sample). */
final class Monitor {
    /** pid of the running xray child, 0 if none (set by the VPN service) */
    static volatile int xrayPid = 0;

    private final long tck = Math.max(1, Os.sysconf(OsConstants._SC_CLK_TCK));
    private final long page = Math.max(1024, Os.sysconf(OsConstants._SC_PAGESIZE));
    private long lastTicks = -1;
    private long lastWall = 0;

    String sample() {
        long now = SystemClock.elapsedRealtime();
        long[] a = read(android.os.Process.myPid());
        long[] x = xrayPid > 0 ? read(xrayPid) : null;

        long ticks = (a == null ? 0 : a[0]) + (x == null ? 0 : x[0]);
        double cpu = 0;
        if (lastTicks >= 0 && now > lastWall && ticks >= lastTicks) {
            cpu = (ticks - lastTicks) / (double) tck / ((now - lastWall) / 1000.0) * 100.0;
        }
        lastTicks = ticks;
        lastWall = now;

        long appMb = a == null ? 0 : a[1] / 1024;
        long xMb = x == null ? 0 : x[1] / 1024;
        return String.format(Locale.US, "CPU %.1f%%   RAM %d MB  (app %d · xray %s)",
                cpu, appMb + xMb, appMb, x == null ? "—" : String.valueOf(xMb));
    }

    /** @return {cpuTicks, rssKb} or null */
    private long[] read(int pid) {
        try {
            String st = slurp("/proc/" + pid + "/stat");
            int rp = st.lastIndexOf(')');
            String[] f = st.substring(rp + 2).split(" ");
            long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]); // utime + stime
            String[] m = slurp("/proc/" + pid + "/statm").trim().split(" ");
            long rssKb = Long.parseLong(m[1]) * page / 1024;
            return new long[]{ticks, rssKb};
        } catch (Exception e) {
            return null;
        }
    }

    private static String slurp(String path) throws Exception {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] b = new byte[1024];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n);
        }
    }

    /** one-time scan of /proc for the process whose command line contains all needles */
    static int findPid(String... needles) {
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return 0;
        for (File d : dirs) {
            String n = d.getName();
            if (!n.matches("[0-9]+")) continue;
            try {
                String cmd = slurp("/proc/" + n + "/cmdline");
                boolean all = !cmd.isEmpty();
                for (String s : needles) if (!cmd.contains(s)) all = false;
                if (all) return Integer.parseInt(n);
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
