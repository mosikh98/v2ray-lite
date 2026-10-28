package com.lite.xray;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Subscription download + the three ping methods. */
final class Net {
    private Net() {}

    /** last failure reason of a real-delay test (diagnostics only) */
    static volatile String lastError = "";

    // ---------------- subscriptions ----------------

    static String fetch(String url) throws IOException {
        if (!XrayVpnService.running) return fetch(url, false);
        try {
            return fetch(url, true); // through our own running proxy
        } catch (IOException e) {
            return fetch(url, false);
        }
    }

    private static String fetch(String url, boolean viaProxy) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) (viaProxy
                ? u.openConnection(new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress("127.0.0.1", ConfigBuilder.SOCKS_PORT)))
                : u.openConnection());
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "v2rayNG/1.8.0");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 4 * 1024 * 1024) break;
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** Accepts plain lines or a base64 blob of lines; keeps only supported links. */
    static List<String> parseLinks(String body) {
        List<String> out = new ArrayList<>();
        String t = body == null ? "" : body.trim();
        if (t.isEmpty()) return out;
        if (!t.contains("://")) {
            try {
                t = ConfigBuilder.b64(t);
            } catch (Exception e) {
                return out;
            }
        }
        for (String line : t.split("\\r?\\n")) {
            line = line.trim();
            if (ConfigBuilder.isLink(line)) out.add(line);
        }
        return out;
    }

    // ---------------- ping ----------------

    /** 5 tries, each limited to 1 s (so <= 5 s in total); the result is the average of the successful tries. */
    static final int TRIES = 5;
    static final int TRY_MS = 1000;

    /** @return average ms (>=0) or -2 on failure */
    static long ping(Context ctx, String link) {
        try {
            int method = Settings.pingMethod(ctx);
            if (method == 2) return realDelay(ctx, link, Settings.pingUrl(ctx));
            String[] ep = ConfigBuilder.endpoint(link);
            if (ep == null) return -2;
            int port = Integer.parseInt(ep[1]);
            return method == 0 ? tcpAvg(ep[0], port) : icmpAvg(ep[0]);
        } catch (Throwable e) {
            return -2;
        }
    }

    private static long average(List<Long> v) {
        if (v.isEmpty()) return -2;
        long sum = 0;
        for (long x : v) sum += x;
        return Math.round(sum / (double) v.size());
    }

    private static long tcpAvg(String host, int port) {
        InetAddress a;
        try {
            a = InetAddress.getByName(host);
        } catch (IOException e) {
            return -2;
        }
        List<Long> ok = new ArrayList<>();
        for (int i = 0; i < TRIES; i++) {
            long t0 = SystemClock.elapsedRealtime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(a, port), TRY_MS);
                ok.add(SystemClock.elapsedRealtime() - t0);
            } catch (IOException ignored) {
            }
        }
        return average(ok);
    }

    private static long icmpAvg(String host) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/ping", "-c", String.valueOf(TRIES),
                    "-W", "1", "-w", String.valueOf(TRIES), host)
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            InputStream in = p.getInputStream();
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor(2, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("time=([0-9.]+)").matcher(bo.toString("UTF-8"));
            List<Long> ok = new ArrayList<>();
            while (m.find()) ok.add(Math.round(Double.parseDouble(m.group(1))));
            return average(ok);
        } catch (Exception e) {
            return -2;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    /** Starts a throw-away xray on a free port and times an HTTP request through it. */
    private static long realDelay(Context ctx, String link, String url) {
        File cfg = null, log = null;
        Process p = null;
        try {
            lastError = "";
            int port = freePort();
            String json = ConfigBuilder.build(link, port, Settings.dnsList(ctx), false, Settings.frag(ctx));
            json = ConfigBuilder.resolveServers(json);
            cfg = new File(ctx.getCacheDir(), "t" + port + ".json");
            log = new File(ctx.getCacheDir(), "t" + port + ".log");
            try (OutputStream o = new java.io.FileOutputStream(cfg)) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
            }
            File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libxray.so");
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "run", "-c", cfg.getAbsolutePath());
            env(pb, ctx);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(log));
            p = pb.start();

            if (!waitPort(p, port, 4000)) {
                lastError = "xray بالا نیومد: " + tail(log, 400);
                return -2;
            }
            List<Long> ok = new ArrayList<>();
            for (int i = 0; i < TRIES; i++) {
                long r = httpViaSocks(port, url, TRY_MS);
                if (r >= 0) ok.add(r);
            }
            if (ok.isEmpty()) {
                Thread.sleep(200);
                lastError = lastError + " | " + tail(log, 400);
            }
            return average(ok);
        } catch (Exception e) {
            lastError = String.valueOf(e);
            return -2;
        } finally {
            if (p != null) p.destroyForcibly();
            if (cfg != null) cfg.delete();
            if (log != null) log.delete();
        }
    }

    /** environment for every xray child process */
    static void env(ProcessBuilder pb, Context ctx) {
        String dir = ctx.getFilesDir().getAbsolutePath();
        pb.environment().put("XRAY_LOCATION_ASSET", dir);
        pb.environment().put("HOME", dir);
        pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
    }

    /** waits until 127.0.0.1:port accepts connections (or the process dies / time runs out) */
    static boolean waitPort(Process p, int port, int ms) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!p.isAlive()) return false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        return false;
    }

    static String tail(File f, int max) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), max)];
            long skip = f.length() - b.length;
            if (skip > 0) in.skip(skip);
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, StandardCharsets.UTF_8).replace('\n', ' ').trim();
        } catch (Exception e) {
            return "";
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static long httpViaSocks(int port, String url, int timeoutMs) {
        try (Socket s = new Socket()) {
            URL u = new URL(url);
            String host = u.getHost();
            int dport = u.getPort() > 0 ? u.getPort() : 80;
            String path = (u.getFile() == null || u.getFile().isEmpty()) ? "/" : u.getFile();

            final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            OutputStream o = s.getOutputStream();
            InputStream in = s.getInputStream();

            long t0 = SystemClock.elapsedRealtime();
            o.write(new byte[]{5, 1, 0});
            o.flush();
            left(s, deadline);
            byte[] r = new byte[2];
            readFully(in, r, 2);
            if (r[0] != 5 || r[1] != 0) return -2;

            byte[] h = host.getBytes(StandardCharsets.UTF_8);
            byte[] req = new byte[7 + h.length];
            req[0] = 5;
            req[1] = 1;
            req[2] = 0;
            req[3] = 3;
            req[4] = (byte) h.length;
            System.arraycopy(h, 0, req, 5, h.length);
            req[5 + h.length] = (byte) (dport >> 8);
            req[6 + h.length] = (byte) dport;
            o.write(req);
            o.flush();

            left(s, deadline);
            byte[] head = new byte[4];
            readFully(in, head, 4);
            if (head[1] != 0) {
                lastError = "socks reply " + head[1];
                return -2;
            }
            int skip = head[3] == 1 ? 6 : head[3] == 4 ? 18 : 0;
            if (head[3] == 3) skip = (in.read() & 0xff) + 2;
            left(s, deadline);
            readFully(in, new byte[skip], skip);

            String http = "GET " + path + " HTTP/1.1\r\nHost: " + host
                    + "\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n";
            o.write(http.getBytes(StandardCharsets.UTF_8));
            o.flush();

            left(s, deadline);
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1 && ch != '\n' && line.length() < 200) line.append((char) ch);
            if (!line.toString().startsWith("HTTP/1.")) {
                lastError = "پاسخ نامعتبر: " + line;
                return -2;
            }
            return SystemClock.elapsedRealtime() - t0;
        } catch (Exception e) {
            lastError = "http: " + e;
            return -2;
        }
    }

    private static void left(Socket s, long deadline) throws IOException {
        long rem = deadline - SystemClock.elapsedRealtime();
        if (rem <= 0) throw new java.net.SocketTimeoutException("timeout");
        s.setSoTimeout((int) rem);
    }

    private static void readFully(InputStream in, byte[] b, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int k = in.read(b, off, n - off);
            if (k < 0) throw new IOException("eof");
            off += k;
        }
    }
}
