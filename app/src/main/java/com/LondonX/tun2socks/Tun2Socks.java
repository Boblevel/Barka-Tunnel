package com.LondonX.tun2socks;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.util.List;

public final class Tun2Socks {
    private Tun2Socks() {}
    public enum LogLevel { NONE, ERROR, WARNING, NOTICE, INFO, DEBUG }
    private static boolean loaded;

    public static native void stopTun2Socks();

    public static synchronized void initialize(Context context) {
        if (loaded) return;
        try {
            System.loadLibrary("tun2socks");
            loaded = true;
        } catch (LinkageError error) {
            throw new IllegalStateException(
                    "Le moteur tun2socks n'est pas disponible pour cet appareil.",
                    error
            );
        }
    }

    public static native boolean startTun2Socks(
            LogLevel logLevel,
            ParcelFileDescriptor vpnInterfaceFileDescriptor,
            int vpnInterfaceMtu,
            String socksServerAddress,
            int socksServerPort,
            String netIPv4Address,
            String netIPv6Address,
            String netmask,
            boolean forwardUdp,
            List<String> extraArgs);
}
