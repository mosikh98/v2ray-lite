package hev.htproxy;

/** JNI bridge to hev-socks5-tunnel (class/package name must match the native lib). */
public class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native void TProxyStartService(String configPath, int fd);

    public static native void TProxyStopService();

    public static native long[] TProxyGetStats();
}
