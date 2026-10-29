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
import java.util.List;

/**
 * Download + upload speed through each server (Cloudflare speed endpoints). Results are written straight
 * into Store.Server.dl / ul (Mbps). Servers are tested one after another because bandwidth tests must not overlap.
 * The currently connected server is tested through the running Xray, every other one through a temporary Xray.
 */
final class SpeedTest {
    interface Progress {
        /** called from the worker thread whenever a value changed */
        void update();
    }

    private volatile boolean cancel = false;
    private volatile Process proc;
    private Progress progress;

    void cancel() {
        cancel = true;
        Process p = proc;
        if (p != null) p.destroyForcibly();
    }

    /** blocking - call from a worker thread */
    void runAll(Context ctx, List<Store.Server> targets, String connectedId, int mb, Progress p) {
        progress = p;
        for (Store.Server s : targets) {
            if (cancel) break;
            testOne(ctx, s, connectedId, mb * 1000000L);
        }
    }

    private void testOne(Context ctx, Store.Server s, String connectedId, long bytes) {
        s.dl = -3;
        s.ul = -3;
        progress.update();
        if (s.real == -2) { // the ping already showed that this one is dead
            s.dl = -2;
            s.ul = -2;
            progress.update();
            return;
        }

        File cfg = null, log = null;
        Process p = null;
        boolean dlDone = false, ulDone = false;
        try {
            int port;
            if (XrayVpnService.running && s.id.equals(connectedId)) {
                port = ConfigBuilder.SOCKS_PORT;
            } else {
                port = Net.freePort();
                String json = ConfigBuilder.build(s.link, port, Settings.dnsList(ctx), false, Settings.frag(ctx));
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
                if (!Net.waitPort(p, port, 5000)) throw new Exception("xray did not start");
            }

            double d = download(port, bytes, s);
            if (cancel) return;
            s.dl = d;
            dlDone = true;
            progress.update();

            double u = upload(port, bytes, s);
            if (cancel) return;
            s.ul = u;
            ulDone = true;
        } catch (Exception e) {
            if (!cancel) {
                // a failed download means the server is not usable: skip the upload
                if (!dlDone) s.dl = -2;
                if (!ulDone) s.ul = -2;
                dlDone = true;
                ulDone = true;
            }
        } finally {
            if (p != null) p.destroyForcibly();
            proc = null;
            if (cfg != null) cfg.delete();
            if (log != null) log.delete();
            if (cancel) { // unfinished values must not look like results
                if (!dlDone) s.dl = -1;
                if (!ulDone) s.ul = -1;
            }
            progress.update();
        }
    }

    private HttpURLConnection open(String url, int port) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url)
                .openConnection(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port)));
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    private double download(int port, long bytes, Store.Server s) throws Exception {
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
                    s.dl = got * 8.0 / ((now - t0) / 1000.0) / 1e6; // live value
                    progress.update();
                }
            }
            double sec = Math.max(0.001, (SystemClock.elapsedRealtime() - t0) / 1000.0);
            return got * 8.0 / sec / 1e6;
        } finally {
            c.disconnect();
        }
    }

    private double upload(int port, long bytes, Store.Server s) throws Exception {
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
                    s.ul = sent * 8.0 / ((now - t0) / 1000.0) / 1e6; // live value
                    progress.update();
                }
            }
            if (cancel) return -1;
            o.flush();
            o.close();
            int code = c.getResponseCode(); // returns when the server has received everything
            double sec = Math.max(0.001, (SystemClock.elapsedRealtime() - t0) / 1000.0);
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            return bytes * 8.0 / sec / 1e6;
        } finally {
            c.disconnect();
        }
    }
}
