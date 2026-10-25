package com.lite.xray;

import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class XrayVpnService extends VpnService {
    private static final String TAG = "XrayLite";

    static volatile boolean running = false;
    static volatile boolean starting = false;
    static volatile String lastError = "";
    static volatile String lastLog = "";

    private volatile boolean cancel = false;
    private Process proc;
    private ParcelFileDescriptor tun;
    private boolean tunnelStarted = false;

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && "stop".equals(in.getAction())) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (running || starting) return START_NOT_STICKY;
        final String cfg = in == null ? null : in.getStringExtra("cfg");
        if (cfg == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        lastError = "";
        lastLog = "";
        cancel = false;
        starting = true;
        // DNS lookup + waiting for xray must not block the main thread
        new Thread(() -> {
            try {
                startAll(cfg);
            } catch (Throwable e) {
                Log.e(TAG, "start failed", e);
                lastError = String.valueOf(e.getMessage());
                new Handler(Looper.getMainLooper()).post(this::stopAll);
            } finally {
                starting = false;
            }
        }, "xray-start").start();
        return START_NOT_STICKY;
    }

    private void startAll(String cfg) throws Exception {
        String[] dns = Settings.dnsList(this);
        int mtu = Settings.mtu(this);

        File dir = getFilesDir();
        File cf = new File(dir, "config.json");
        String json = ConfigBuilder.build(cfg, ConfigBuilder.SOCKS_PORT, dns, true);
        json = ConfigBuilder.resolveServers(json);
        write(cf, json);
        if (cancel) throw new Exception("cancelled");

        File bin = new File(getApplicationInfo().nativeLibraryDir, "libxray.so");
        if (!bin.exists()) throw new Exception("xray binary missing");

        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "run", "-c", cf.getAbsolutePath());
        pb.environment().put("XRAY_LOCATION_ASSET", dir.getAbsolutePath());
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        proc = p;

        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lastLog = line;
                    Log.i(TAG, line);
                }
            } catch (IOException ignored) {
            }
            if (running && p == proc) {
                lastError = "xray stopped: " + lastLog;
                new Handler(Looper.getMainLooper()).post(this::stopAll);
            }
        }, "xray-log").start();

        if (!Net.waitPort(p, ConfigBuilder.SOCKS_PORT, 5000)) {
            throw new Exception("xray did not start: " + lastLog);
        }
        if (cancel) throw new Exception("cancelled");

        Builder b = new Builder()
                .setSession("XrayLite")
                .setMtu(mtu)
                .addAddress("26.26.26.1", 30)
                .addRoute("0.0.0.0", 0);
        for (String d : dns) b.addDnsServer(d);
        // xray runs inside our UID: exclude ourselves so its own traffic does not loop into the tunnel
        b.addDisallowedApplication(getPackageName());
        tun = b.establish();
        if (tun == null) throw new Exception("VPN permission missing");
        if (cancel) throw new Exception("cancelled");

        File yaml = new File(dir, "tun.yml");
        write(yaml, "tunnel:\n"
                + "  mtu: " + mtu + "\n"
                + "  ipv4: 26.26.26.1\n"
                + "socks5:\n"
                + "  port: " + ConfigBuilder.SOCKS_PORT + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'udp'\n"
                + "misc:\n"
                + "  log-level: warn\n");
        Tun.start(yaml.getAbsolutePath(), tun.getFd());
        tunnelStarted = true;

        running = true;
    }

    private synchronized void stopAll() {
        cancel = true;
        running = false;
        if (tunnelStarted) {
            try {
                Tun.stop();
            } catch (Throwable ignored) {
            }
            tunnelStarted = false;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
            tun = null;
        }
        if (proc != null) {
            proc.destroyForcibly();
            proc = null;
        }
        stopSelf();
    }

    @Override
    public void onRevoke() {
        stopAll();
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    private static void write(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
