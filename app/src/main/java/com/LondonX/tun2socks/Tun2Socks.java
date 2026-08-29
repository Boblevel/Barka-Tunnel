package com.LondonX.tun2socks;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

    public static boolean startTun2Socks(
            LogLevel logLevel,
            ParcelFileDescriptor vpnInterfaceFileDescriptor,
            int vpnInterfaceMtu,
            String socksServerAddress,
            int socksServerPort,
            String netIPv4Address,
            String netIPv6Address,
            String netmask,
            boolean forwardUdp,
            List<String> extraArgs) {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("badvpn-tun2socks");
        arguments.addAll(Arrays.asList("--logger", "stdout"));
        arguments.addAll(Arrays.asList("--loglevel", String.valueOf(logLevel.ordinal())));
        arguments.addAll(Arrays.asList("--tunfd", String.valueOf(vpnInterfaceFileDescriptor.getFd())));
        arguments.addAll(Arrays.asList("--tunmtu", String.valueOf(vpnInterfaceMtu)));
        arguments.addAll(Arrays.asList("--netif-ipaddr", netIPv4Address));
        if (!TextUtils.isEmpty(netIPv6Address)) {
            arguments.addAll(Arrays.asList("--netif-ip6addr", netIPv6Address));
        }
        arguments.addAll(Arrays.asList("--netif-netmask", netmask));
        arguments.addAll(Arrays.asList(
                "--socks-server-addr",
                String.format(Locale.US, "%s:%d", socksServerAddress, socksServerPort)));
        if (forwardUdp) {
            arguments.add("--socks5-udp");
        }
        arguments.addAll(extraArgs);
        return start_tun2socks(arguments.toArray(new String[0])) == 0;
    }

    private static native int start_tun2socks(String[] args);
}
