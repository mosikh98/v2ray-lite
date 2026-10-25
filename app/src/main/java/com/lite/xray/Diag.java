package com.lite.xray;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** One-shot self test: prints everything needed to see why a server does not work. */
final class Diag {
    private Diag() {}

    static String run(Context ctx, String link) {
        StringBuilder sb = new StringBuilder();
        File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libxray.so");
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", android ").append(Build.VERSION.SDK_INT)
                .append(", abi ").append(Build.SUPPORTED_ABIS[0]).append('\n');
        sb.append("xray file: ").append(bin.exists() ? bin.length() + " bytes" : "MISSING")
                .append(", exec=").append(bin.canExecute()).append('\n');
        sb.append("xray version: ").append(version(ctx, bin)).append("\n\n");

        String[] ep = null;
        try {
            ep = ConfigBuilder.endpoint(link);
        } catch (Exception e) {
            sb.append("config error: ").append(e).append('\n');
        }
        if (ep == null) return sb.append("no server endpoint found").toString();
        sb.append("server: ").append(ep[0]).append(':').append(ep[1]).append('\n');
        try {
            InetAddress[] all = InetAddress.getAllByName(ep[0]);
            sb.append("resolved: ");
            for (InetAddress a : all) sb.append(a.getHostAddress()).append(' ');
            sb.append('\n');
        } catch (Exception e) {
            sb.append("resolve FAILED: ").append(e).append('\n');
        }

        // throw-away xray with info-level log
        File cfg = null, log = null;
        Process p = null;
        try {
            int port = Net.freePort();
            String json = ConfigBuilder.build(link, port, Settings.dnsList(ctx), false);
            json = ConfigBuilder.resolveServers(json);
            JSONObject root = new JSONObject(json);
            root.getJSONObject("log").put("loglevel", "info");
            cfg = new File(ctx.getCacheDir(), "diag.json");
            log = new File(ctx.getCacheDir(), "diag.log");
            try (FileOutputStream o = new FileOutputStream(cfg)) {
                o.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "run", "-c", cfg.getAbsolutePath());
            Net.env(pb, ctx);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(log));
            p = pb.start();

            boolean up = Net.waitPort(p, port, 5000);
            sb.append("xray started: ").append(up ? "yes" : "NO (alive=" + p.isAlive() + ")").append('\n');
            if (up) {
                long r = Net.httpViaSocks(port, Settings.pingUrl(ctx), 8000);
                sb.append("http test via proxy: ").append(r >= 0 ? r + " ms OK" : "FAILED").append('\n');
                if (r < 0) sb.append("reason: ").append(Net.lastError).append('\n');
            }
            Thread.sleep(300);
            sb.append("\n--- xray log ---\n").append(Net.tail(log, 1800)).append('\n');
        } catch (Exception e) {
            sb.append("diag error: ").append(e).append('\n');
        } finally {
            if (p != null) p.destroyForcibly();
            if (cfg != null) cfg.delete();
            if (log != null) log.delete();
        }
        return sb.toString();
    }

    private static String version(Context ctx, File bin) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "version");
            Net.env(pb, ctx);
            pb.redirectErrorStream(true);
            p = pb.start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[512];
            int n;
            while (bo.size() < 300 && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor(3, TimeUnit.SECONDS);
            String t = bo.toString("UTF-8").trim();
            int nl = t.indexOf('\n');
            return nl > 0 ? t.substring(0, nl) : t;
        } catch (Exception e) {
            return "FAILED: " + e;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }
}
