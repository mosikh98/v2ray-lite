package com.lite.xray;

/** JNI bridge to hev-socks5-tunnel (native side is native/hev-jni.c.in). */
final class Tun {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private Tun() {}

    static native void start(String configPath, int fd);

    static native void stop();
}
