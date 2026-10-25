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

    /** @return ms (>=0) or -2 on failure */
    static long ping(Context ctx, String link) {
        try {
            int method = Settings.pingMethod(ctx);
            int to = Settings.timeoutSec(ctx) * 1000;
            if (method == 2) return realDelay(ctx, link, to, Settings.pingUrl(ctx));
            String[] ep = ConfigBuilder.endpoint(link);
            if (ep == null) return -2;
            return method == 0 ? tcp(ep[0], Integer.parseInt(ep[1]), to) : icmp(ep[0], to);
        } catch (Throwable e) {
            return -2;
        }
    }

    private static long tcp(String host, int port, int timeoutMs) {
        try {
            InetAddress a = InetAddress.getByName(host);
            long t0 = SystemClock.elapsedRealtime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(a, port), timeoutMs);
            }
            return SystemClock.elapsedRealtime() - t0;
        } catch (IOException e) {
            return -2;
        }
    }

    private static long icmp(String host, int timeoutMs) {
        Process p = null;
        try {
            int w = Math.max(1, timeoutMs / 1000);
            p = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", String.valueOf(w), host)
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            InputStream in = p.getInputStream();
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor(w + 2L, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("time=([0-9.]+)").matcher(bo.toString("UTF-8"));
            if (m.find()) return Math.round(Double.parseDouble(m.group(1)));
            return -2;
        } catch (Exception e) {
            return -2;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    /** Starts a throw-away xray on a free port and times an HTTP request through it. */
    private static long realDelay(Context ctx, String link, int timeoutMs, String url) {
        File cfg = null;
        Process p = null;
        try {
            int port = freePort();
            String json = ConfigBuilder.build(link, port, Settings.dnsList(ctx), false);
            cfg = new File(ctx.getCacheDir(), "t" + port + ".json");
            try (OutputStream o = new java.io.FileOutputStream(cfg)) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
            }
            File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libxray.so");
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "run", "-c", cfg.getAbsolutePath());
            pb.environment().put("XRAY_LOCATION_ASSET", ctx.getFilesDir().getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();

            long deadline = SystemClock.elapsedRealtime() + 4000;
            boolean ready = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!p.isAlive()) return -2;
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    ready = true;
                    break;
                } catch (IOException e) {
                    Thread.sleep(100);
                }
            }
            if (!ready) return -2;
            return httpViaSocks(port, url, timeoutMs);
        } catch (Exception e) {
            return -2;
        } finally {
            if (p != null) p.destroyForcibly();
            if (cfg != null) cfg.delete();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static long httpViaSocks(int port, String url, int timeoutMs) {
        try (Socket s = new Socket()) {
            URL u = new URL(url);
            String host = u.getHost();
            int dport = u.getPort() > 0 ? u.getPort() : 80;
            String path = (u.getFile() == null || u.getFile().isEmpty()) ? "/" : u.getFile();

            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream o = s.getOutputStream();
            InputStream in = s.getInputStream();

            long t0 = SystemClock.elapsedRealtime();
            o.write(new byte[]{5, 1, 0});
            o.flush();
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

            byte[] head = new byte[4];
            readFully(in, head, 4);
            if (head[1] != 0) return -2;
            int skip = head[3] == 1 ? 6 : head[3] == 4 ? 18 : 0;
            if (head[3] == 3) skip = (in.read() & 0xff) + 2;
            readFully(in, new byte[skip], skip);

            String http = "GET " + path + " HTTP/1.1\r\nHost: " + host
                    + "\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n";
            o.write(http.getBytes(StandardCharsets.UTF_8));
            o.flush();

            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1 && ch != '\n' && line.length() < 200) line.append((char) ch);
            if (!line.toString().startsWith("HTTP/1.")) return -2;
            return SystemClock.elapsedRealtime() - t0;
        } catch (Exception e) {
            return -2;
        }
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
