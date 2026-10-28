package com.lite.xray;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Download + upload speed through the proxy (Cloudflare speed endpoints).
 * Uses the running Xray if connected, otherwise starts a temporary one for the selected server.
 */
final class SpeedTest {
    interface Listener {
        void onUpdate(String text);

        void onDone();
    }

    private volatile boolean cancel = false;
    private volatile Process proc;
    private Listener listener;
    private String head = "";
    private String dlLine = "…";
    private String ulLine = "—";

    void cancel() {
        cancel = true;
        Process p = proc;
        if (p != null) p.destroyForcibly();
    }

    private void push() {
        listener.onUpdate(head + "↓ دانلود: " + dlLine + "\n↑ آپلود: " + ulLine);
    }

    private static String fmt(double mbps, double sec) {
        return String.format(Locale.US, "%.1f Mbps  (%.2f MB/s, %.1f s)", mbps, mbps / 8.0, sec);
    }

    /** blocking - call from a worker thread */
    void run(Context ctx, String link, String name, int mb, Listener l) {
        listener = l;
        final long bytes = mb * 1000000L;
        head = "سرور: " + name + "\nحجم هر تست: " + mb + " MB\n\n";
        File cfg = null, log = null;
        Process p = null;
        try {
            int port;
            if (XrayVpnService.running) {
                port = ConfigBuilder.SOCKS_PORT;
            } else {
                dlLine = "در حال راه‌اندازی Xray…";
                push();
                port = Net.freePort();
                String json = ConfigBuilder.build(link, port, Settings.dnsList(ctx), false, Settings.frag(ctx));
                json = ConfigBuilder.resolveServers(json);
                cfg = new File(ctx.getCacheDir(), "speed.json");
                log = new File(ctx.getCacheDir(), "speed.log");
                try (FileOutputStream o = new FileOutputStream(cfg)) {
                    o.write(json.getBytes(StandardCharsets.UTF_8));
                }
                File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libxray.so");
                ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "run", "-c", cfg.getAbsolutePath());
                Net.env(pb, ctx);
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.to(log));
                p = pb.start();
                proc = p;
                if (!Net.waitPort(p, port, 5000)) {
                    throw new Exception("xray did not start: " + Net.tail(log, 300));
                }
            }

            dlLine = "…";
            push();
            try {
                dlLine = download(port, bytes);
            } catch (Exception e) {
                dlLine = cancel ? "لغو شد" : "خطا: " + e.getMessage();
            }
            push();

            if (!cancel) {
                ulLine = "…";
                push();
                try {
                    ulLine = upload(port, bytes);
                } catch (Exception e) {
                    ulLine = cancel ? "لغو شد" : "خطا: " + e.getMessage();
                }
                push();
            }
        } catch (Exception e) {
            dlLine = "خطا: " + e.getMessage();
            push();
        } finally {
            if (p != null) p.destroyForcibly();
            if (cfg != null) cfg.delete();
            if (log != null) log.delete();
            listener.onDone();
        }
    }

    private HttpURLConnection open(String url, int port) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url)
                .openConnection(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port)));
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    private String download(int port, long bytes) throws Exception {
        HttpURLConnection c = open("https://speed.cloudflare.com/__down?bytes=" + bytes, port);
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[65536];
            long got = 0;
            long t0 = SystemClock.elapsedRealtime(); // headers are in: time the body only
            long lastUi = t0;
            int n;
            while (!cancel && (n = in.read(buf)) > 0) {
                got += n;
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi > 300) {
                    lastUi = now;
                    dlLine = String.format(Locale.US, "%.1f Mbps …", got * 8.0 / ((now - t0) / 1000.0) / 1e6);
                    push();
                }
            }
            if (cancel) return "لغو شد";
            double sec = Math.max(0.001, (SystemClock.elapsedRealtime() - t0) / 1000.0);
            return fmt(got * 8.0 / sec / 1e6, sec);
        } finally {
            c.disconnect();
        }
    }

    private String upload(int port, long bytes) throws Exception {
        HttpURLConnection c = open("https://speed.cloudflare.com/__up", port);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setFixedLengthStreamingMode(bytes);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        try {
            OutputStream o = c.getOutputStream();
            byte[] buf = new byte[65536];
            long sent = 0;
            long t0 = SystemClock.elapsedRealtime();
            long lastUi = t0;
            while (sent < bytes && !cancel) {
                int k = (int) Math.min(buf.length, bytes - sent);
                o.write(buf, 0, k);
                sent += k;
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi > 300) {
                    lastUi = now;
                    ulLine = String.format(Locale.US, "%.1f Mbps …", sent * 8.0 / ((now - t0) / 1000.0) / 1e6);
                    push();
                }
            }
            if (cancel) return "لغو شد";
            o.flush();
            o.close();
            int code = c.getResponseCode(); // returns when the server has received everything
            double sec = Math.max(0.001, (SystemClock.elapsedRealtime() - t0) / 1000.0);
            if (code / 100 != 2) return "خطا: HTTP " + code;
            return fmt(bytes * 8.0 / sec / 1e6, sec);
        } finally {
            c.disconnect();
        }
    }
}
