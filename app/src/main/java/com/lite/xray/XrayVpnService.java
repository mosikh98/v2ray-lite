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

import hev.htproxy.TProxyService;

public class XrayVpnService extends VpnService {
    private static final String TAG = "XrayLite";

    static volatile boolean running = false;
    static volatile String lastError = "";
    static volatile String lastLog = "";

    private Process proc;
    private ParcelFileDescriptor tun;
    private boolean tunnelStarted = false;

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && "stop".equals(in.getAction())) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        String cfg = in == null ? null : in.getStringExtra("cfg");
        if (cfg == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        lastError = "";
        try {
            startAll(cfg);
        } catch (Throwable e) {
            Log.e(TAG, "start failed", e);
            lastError = String.valueOf(e.getMessage());
            stopAll();
        }
        return START_NOT_STICKY;
    }

    private void startAll(String cfg) throws Exception {
        File dir = getFilesDir();
        File cf = new File(dir, "config.json");
        write(cf, ConfigBuilder.build(cfg));

        File bin = new File(getApplicationInfo().nativeLibraryDir, "libxray.so");
        if (!bin.exists()) throw new Exception("xray binary missing");

        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "run", "-c", cf.getAbsolutePath());
        pb.environment().put("XRAY_LOCATION_ASSET", dir.getAbsolutePath());
        pb.redirectErrorStream(true);
        proc = pb.start();
        final Process p = proc;

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

        Thread.sleep(700);
        if (!p.isAlive()) throw new Exception("xray exited: " + lastLog);

        Builder b = new Builder()
                .setSession("XrayLite")
                .setMtu(1500)
                .addAddress("26.26.26.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8");
        // xray runs inside our UID: exclude ourselves so its own traffic does not loop into the tunnel
        b.addDisallowedApplication(getPackageName());
        tun = b.establish();
        if (tun == null) throw new Exception("VPN permission missing");

        File yaml = new File(dir, "tun.yml");
        write(yaml, "tunnel:\n"
                + "  mtu: 1500\n"
                + "  ipv4: 26.26.26.1\n"
                + "socks5:\n"
                + "  port: " + ConfigBuilder.SOCKS_PORT + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'udp'\n"
                + "misc:\n"
                + "  log-level: warn\n");
        TProxyService.TProxyStartService(yaml.getAbsolutePath(), tun.getFd());
        tunnelStarted = true;

        running = true;
    }

    private synchronized void stopAll() {
        running = false;
        if (tunnelStarted) {
            try {
                TProxyService.TProxyStopService();
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
