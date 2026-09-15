package com.bigsinger.tvinstaller.net;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.bigsinger.tvinstaller.data.DeviceInfo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LanScanner {
    private static final String TAG = "LanScanner";
    private static final int SMB_PORT = 445;
    private static final int CONNECT_TIMEOUT_MS = 800;
    private static final int THREADS = 20;
    private static final int MAX_SCAN_HOSTS = 254;

    public interface Listener {
        void onScanStarted(int totalHosts);

        void onProgress(int completedHosts, int totalHosts, int foundCount);

        void onDeviceFound(DeviceInfo device);

        void onScanCompleted(List<DeviceInfo> devices);

        void onScanFailed(String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ExecutorService executor;

    public void scan(Context context, Listener listener) {
        cancel();
        cancelled.set(false);
        executor = Executors.newFixedThreadPool(THREADS);

        List<String> hosts = resolveSubnetHosts(context);
        if (hosts.isEmpty()) {
            postFailure(listener, "无法获取当前 Wi-Fi 网段，请确认已连接局域网");
            return;
        }

        List<DeviceInfo> found = Collections.synchronizedList(new ArrayList<DeviceInfo>());
        AtomicInteger completed = new AtomicInteger(0);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onScanStarted(hosts.size());
            }
        });

        for (String host : hosts) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (cancelled.get()) {
                        return;
                    }
                    boolean open = isPortOpen(host);
                    if (open) {
                        DeviceInfo device = new DeviceInfo("SMB 设备", host);
                        found.add(device);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onDeviceFound(device);
                            }
                        });
                    }

                    int done = completed.incrementAndGet();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onProgress(done, hosts.size(), found.size());
                            if (done == hosts.size() && !cancelled.get()) {
                                listener.onScanCompleted(new ArrayList<DeviceInfo>(found));
                            }
                        }
                    });
                }
            });
        }
    }

    public void cancel() {
        cancelled.set(true);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void postFailure(Listener listener, String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onScanFailed(message);
            }
        });
    }

    private boolean isPortOpen(String host) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, SMB_PORT), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                Log.d(TAG, "Socket close ignored");
            }
        }
    }

    private List<String> resolveSubnetHosts(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            if (dhcpInfo != null && dhcpInfo.ipAddress != 0) {
                List<String> hosts = hostsFromIpAndMask(dhcpInfo.ipAddress,
                        dhcpInfo.netmask == 0 ? 0xFFFFFF00 : dhcpInfo.netmask);
                if (!hosts.isEmpty()) {
                    return hosts;
                }
            }
        }

        // 回退：电视通常走网线(以太网)，WifiManager 拿不到地址。
        // 直接从 NetworkInterface 枚举本机 IPv4 地址，按 /24 推导网段。
        return hostsFromNetworkInterfaces();
    }

    private List<String> hostsFromIpAndMask(int ipAddress, int netmask) {
        int ip = littleEndianToInt(ipAddress);
        int mask = (netmask == 0) ? 0xFFFFFF00 : littleEndianToInt(netmask);
        int networkAddress = ip & mask;
        long network = unsigned(networkAddress);
        long broadcast = unsigned(networkAddress | ~mask);

        if (broadcast <= network || broadcast - network > MAX_SCAN_HOSTS + 1) {
            network = unsigned(ip & 0xFFFFFF00);
            broadcast = network | 0xFF;
        }

        List<String> hosts = new ArrayList<String>();
        for (long address = network + 1; address < broadcast && hosts.size() < MAX_SCAN_HOSTS; address++) {
            if (address != unsigned(ip)) {
                hosts.add(formatIp((int) address));
            }
        }
        return hosts;
    }

    private List<String> hostsFromNetworkInterfaces() {
        List<String> hosts = new ArrayList<String>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return hosts;
            }
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface netIf = interfaces.nextElement();
                if (netIf.isLoopback() || netIf.isVirtual() || !netIf.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addresses = netIf.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        byte[] raw = addr.getAddress();
                        int ip = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                                | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
                        List<String> subnet = hostsFromIpAndMask(ip, 0xFFFFFF00);
                        for (String h : subnet) {
                            if (!hosts.contains(h)) {
                                hosts.add(h);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to enumerate network interfaces", e);
        }
        return hosts;
    }

    private int littleEndianToInt(int value) {
        return ((value & 0xFF) << 24)
                | ((value & 0xFF00) << 8)
                | ((value & 0xFF0000) >>> 8)
                | ((value >>> 24) & 0xFF);
    }

    private long unsigned(int value) {
        return value & 0xFFFFFFFFL;
    }

    private String formatIp(int address) {
        return ((address >>> 24) & 0xFF) + "."
                + ((address >>> 16) & 0xFF) + "."
                + ((address >>> 8) & 0xFF) + "."
                + (address & 0xFF);
    }
}
