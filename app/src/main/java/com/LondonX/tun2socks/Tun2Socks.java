package com.LondonX.tun2socks;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.util.List;

public final class Tun2Socks {
    private Tun2Socks() {}
    public enum LogLevel { NONE, ERROR, WARNING, NOTICE, INFO, DEBUG }
    public static native void stopTun2Socks();
    public static void initialize(Context context) { System.loadLibrary("tun2socks"); }
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
