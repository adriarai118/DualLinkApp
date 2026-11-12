package com.duallink.downloader;

/**
 * JNI bridge to hev-socks5-tunnel (native/C). The native library registers these
 * methods on this exact class name (PKGNAME/CLSNAME in build.gradle.kts), so do not
 * rename or move this class.
 *
 * The tunnel reads raw IP packets from the VPN interface and turns every TCP
 * connection / UDP flow into a SOCKS5 request to our in-app SocksServer.
 */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
