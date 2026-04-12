package com.github.ma1co.pmcademo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;

import com.sony.wifi.direct.DirectConfiguration;
import com.sony.wifi.direct.DirectManager;

import java.lang.reflect.Method;
import java.util.List;

public class ConnectivityManager {
    private Context context;
    private WifiManager wifiManager;
    private android.net.ConnectivityManager connManager;
    private DirectManager directManager;
    private HttpServer server;

    private BroadcastReceiver hardwareBootReceiver;
    private BroadcastReceiver directStateReceiver;
    private BroadcastReceiver groupCreateSuccessReceiver;
    private BroadcastReceiver groupCreateFailureReceiver;

    private Handler wifiPollHandler;
    private Runnable wifiPollRunnable;

    private boolean isHomeWifiRunning = false;
    private boolean isHotspotRunning = false;

    private String connStatusHotspot = "Press ENTER to Start";
    private String connStatusWifi = "Press ENTER to Start";

    public interface StatusUpdateListener {
        void onStatusUpdate(String target, String status);
    }

    private StatusUpdateListener listener;

    public ConnectivityManager(Context context, StatusUpdateListener listener) {
        this.context = context;
        this.listener = listener;
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.connManager = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        this.server = new HttpServer(context);
        try {
            if (!server.isAlive()) server.start();
        } catch (Exception e) {}
    }

    public String getConnStatusHotspot() { return connStatusHotspot; }
    public String getConnStatusWifi() { return connStatusWifi; }
    public boolean isHomeWifiRunning() { return isHomeWifiRunning; }
    public boolean isHotspotRunning() { return isHotspotRunning; }

    private void setAutoPowerOffMode(boolean enable) {
        String mode = enable ? "APO/NORMAL" : "APO/NO";
        Intent intent = new Intent();
        intent.setAction("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", mode);
        context.sendBroadcast(intent);
    }

    private void wakeUpSonyWifiHardware() {
        try {
            Class<?> nmClass = Class.forName("com.sony.scalar.sysutil.NetworkManager");
            Object nmInstance = nmClass.newInstance();
            Method enableWifi = nmClass.getMethod("enableWifi");
            enableWifi.invoke(nmInstance);
        } catch (Exception e) {}
    }

    private void sleepSonyWifiHardware() {
        try {
            Class<?> nmClass = Class.forName("com.sony.scalar.sysutil.NetworkManager");
            Object nmInstance = nmClass.newInstance();
            Method disableWifi = nmClass.getMethod("disableWifi");
            disableWifi.invoke(nmInstance);
        } catch (Exception e) {}
    }

    private void waitForHardwareAndExecute(final boolean requireWifiOn, final Runnable action) {
        wakeUpSonyWifiHardware();

        if (wifiManager.isWifiEnabled() == requireWifiOn) {
            new Handler().postDelayed(action, 500);
            return;
        }

        updateStatus("WIFI", "Waking up antenna...");
        updateStatus("HOTSPOT", "Waking up antenna...");

        hardwareBootReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                    int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                    int targetState = requireWifiOn ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED;

                    if (state == targetState) {
                        unregisterReceiverSafe(hardwareBootReceiver);
                        hardwareBootReceiver = null;
                        new Handler().postDelayed(action, 1000);
                    }
                }
            }
        };
        context.registerReceiver(hardwareBootReceiver, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
        wifiManager.setWifiEnabled(requireWifiOn);
    }

    public void startHomeWifi() {
        stopNetworking();
        isHomeWifiRunning = true;

        waitForHardwareAndExecute(true, new Runnable() {
            @Override
            public void run() {
                if (!isHomeWifiRunning) return;
                updateStatus("WIFI", "Connecting to Router...");
                wifiManager.reconnect();

                wifiPollHandler = new Handler();
                wifiPollRunnable = new Runnable() {
                    int attempts = 0;
                    @Override
                    public void run() {
                        if (!isHomeWifiRunning) return;

                        NetworkInfo info = connManager.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
                        if (info != null && info.isConnected()) {
                            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                            int ip = wifiInfo.getIpAddress();
                            if (ip != 0) {
                                String ipAddress = String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                                updateStatus("WIFI", "http://" + ipAddress + ":" + HttpServer.PORT);
                                setAutoPowerOffMode(false);
                                return;
                            }
                        }

                        attempts++;
                        if (attempts > 15) {
                            updateStatus("WIFI", "Timed out.");
                            stopNetworking();
                        } else {
                            updateStatus("WIFI", "Searching... (" + attempts + "/15)");
                            wifiPollHandler.postDelayed(this, 2000);
                        }
                    }
                };
                wifiPollHandler.postDelayed(wifiPollRunnable, 2000);
            }
        });
    }

    public void startHotspot() {
        stopNetworking();
        isHotspotRunning = true;

        waitForHardwareAndExecute(true, new Runnable() {
            @Override
            public void run() {
                if (!isHotspotRunning) return;
                updateStatus("HOTSPOT", "Starting Hotspot...");

                // ---------------------------------------------------------
                // PATH 1: Try Gen 2 DirectManager (a5100)
                // RESTORED: Use the official OpenMemories constant so it doesn't return null
                // ---------------------------------------------------------
                Object dm = null;
                try { dm = context.getApplicationContext().getSystemService(DirectManager.WIFI_DIRECT_SERVICE); } catch (Throwable t) {}
                if (dm != null) {
                    startHotspotGen2(dm);
                    return;
                }

                // ---------------------------------------------------------
                // PATH 2: Try Gen 3 Standard Android WifiP2pManager (a7ii/a6500)
                // ---------------------------------------------------------
                Object p2p = null;
                try { p2p = context.getApplicationContext().getSystemService("wifip2p"); } catch (Throwable t) {}
                if (p2p != null) {
                    startHotspotGen3(p2p);
                    return;
                }

                updateStatus("HOTSPOT", "HW Error: Unrecognized Camera");
                isHotspotRunning = false;
            }
        });
    }

    private void startHotspotGen2(Object dm) {
        directManager = (DirectManager) dm;

        directStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getIntExtra(DirectManager.EXTRA_DIRECT_STATE, DirectManager.DIRECT_STATE_UNKNOWN) == DirectManager.DIRECT_STATE_ENABLED) {
                    List<DirectConfiguration> configs = directManager.getConfigurations();
                    if (configs != null && !configs.isEmpty()) {
                        directManager.startGo(configs.get(configs.size() - 1).getNetworkId());
                    } else {
                        updateStatus("HOTSPOT", "Error: No Configs");
                        stopNetworking();
                    }
                }
            }
        };

        groupCreateSuccessReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                DirectConfiguration config = intent.getParcelableExtra(DirectManager.EXTRA_DIRECT_CONFIG);
                if (config != null) {
                    String password = "N/A";
                    String[] methodNames = {"getPassphrase", "getPassword", "getNetworkKey", "getPreSharedKey"};
                    for (String methodName : methodNames) {
                        try {
                            Method m = config.getClass().getMethod(methodName);
                            m.setAccessible(true);
                            String val = (String) m.invoke(config);
                            if (val != null && val.length() >= 8) {
                                password = val;
                                break;
                            }
                        } catch (Exception e) {}
                    }
                    updateStatus("HOTSPOT", "PW: " + password + " (192.168.122.1)");
                    setAutoPowerOffMode(false);
                }
            }
        };

        groupCreateFailureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateStatus("HOTSPOT", "Hardware Error: Retry");
                stopNetworking();
            }
        };

        context.registerReceiver(directStateReceiver, new IntentFilter(DirectManager.DIRECT_STATE_CHANGED_ACTION));
        context.registerReceiver(groupCreateSuccessReceiver, new IntentFilter(DirectManager.GROUP_CREATE_SUCCESS_ACTION));
        context.registerReceiver(groupCreateFailureReceiver, new IntentFilter(DirectManager.GROUP_CREATE_FAILURE_ACTION));

        directManager.setDirectEnabled(true);
    }

    private void startHotspotGen3(final Object p2pManager) {
        try {
            final Class<?> p2pClass = Class.forName("android.net.wifi.p2p.WifiP2pManager");
            final Class<?> channelClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$Channel");
            final Class<?> actionListenerClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$ActionListener");

            Method initialize = p2pClass.getMethod("initialize", Context.class, android.os.Looper.class, Class.forName("android.net.wifi.p2p.WifiP2pManager$ChannelListener"));
            final Object channel = initialize.invoke(p2pManager, context, context.getMainLooper(), null);

            // Execute with 3 retries to bypass Error 2 (Busy)
            executeP2pCreateGroup(p2pManager, p2pClass, channelClass, actionListenerClass, channel, 3);

        } catch (Exception e) {
            updateStatus("HOTSPOT", "P2P Reflection Error: " + e.getMessage());
            isHotspotRunning = false;
        }
    }

    private void executeP2pCreateGroup(final Object p2pManager, final Class<?> p2pClass, final Class<?> channelClass, final Class<?> actionListenerClass, final Object channel, final int retriesLeft) {
        try {
            final Class<?> groupInfoListenerClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$GroupInfoListener");

            Object actionListener = java.lang.reflect.Proxy.newProxyInstance(
                    context.getClassLoader(),
                    new Class<?>[]{actionListenerClass},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("onSuccess".equals(method.getName())) {
                                Object groupInfoListener = java.lang.reflect.Proxy.newProxyInstance(
                                        context.getClassLoader(),
                                        new Class<?>[]{groupInfoListenerClass},
                                        new java.lang.reflect.InvocationHandler() {
                                            @Override
                                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                                if ("onGroupInfoAvailable".equals(method.getName())) {
                                                    Object group = args[0];
                                                    if (group != null) {
                                                        Method getPassphrase = group.getClass().getMethod("getPassphrase");
                                                        String pass = (String) getPassphrase.invoke(group);
                                                        
                                                        // Android P2P framework defaults to 192.168.49.1
                                                        updateStatus("HOTSPOT", "PW: " + pass + " (192.168.49.1)");
                                                        setAutoPowerOffMode(false);
                                                    } else {
                                                        updateStatus("HOTSPOT", "Error: No Group Info");
                                                    }
                                                }
                                                return null;
                                            }
                                        }
                                );
                                Method requestGroupInfo = p2pClass.getMethod("requestGroupInfo", channelClass, groupInfoListenerClass);
                                requestGroupInfo.invoke(p2pManager, channel, groupInfoListener);

                            } else if ("onFailure".equals(method.getName())) {
                                int reason = (Integer) args[0];
                                if (reason == 2 && retriesLeft > 0) { // Error 2 = BUSY
                                    updateStatus("HOTSPOT", "P2P Busy, clearing... (" + retriesLeft + ")");
                                    
                                    // 1. Blindly issue a removeGroup to kill hanging Sony tasks
                                    try {
                                        Method removeGroup = p2pClass.getMethod("removeGroup", channelClass, actionListenerClass);
                                        removeGroup.invoke(p2pManager, channel, null);
                                    } catch (Exception e) {}

                                    // 2. Wait exactly 1 second for the radio buffer to settle, then retry
                                    new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                                        public void run() {
                                            if (isHotspotRunning) {
                                                executeP2pCreateGroup(p2pManager, p2pClass, channelClass, actionListenerClass, channel, retriesLeft - 1);
                                            }
                                        }
                                    }, 1000);
                                } else {
                                    updateStatus("HOTSPOT", "P2P Error: " + reason);
                                    isHotspotRunning = false;
                                }
                            }
                            return null;
                        }
                    }
            );

            Method createGroup = p2pClass.getMethod("createGroup", channelClass, actionListenerClass);
            createGroup.invoke(p2pManager, channel, actionListener);

        } catch (Exception e) {
            updateStatus("HOTSPOT", "P2P Exec Error: " + e.getMessage());
            isHotspotRunning = false;
        }
    }

    private void unregisterReceiverSafe(BroadcastReceiver receiver) {
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (Exception e) {}
        }
    }

    public void stopNetworking() {
        if (wifiPollHandler != null && wifiPollRunnable != null) {
            wifiPollHandler.removeCallbacks(wifiPollRunnable);
            wifiPollHandler = null;
            wifiPollRunnable = null;
        }

        unregisterReceiverSafe(hardwareBootReceiver);
        hardwareBootReceiver = null;
        unregisterReceiverSafe(directStateReceiver);
        directStateReceiver = null;
        unregisterReceiverSafe(groupCreateSuccessReceiver);
        groupCreateSuccessReceiver = null;
        unregisterReceiverSafe(groupCreateFailureReceiver);
        groupCreateFailureReceiver = null;

        if (isHomeWifiRunning) {
            try { wifiManager.disconnect(); } catch (Exception e) {}
            isHomeWifiRunning = false;
        }

        if (isHotspotRunning) {
            try { if (directManager != null) directManager.setDirectEnabled(false); } catch (Exception e) {}
            try {
                Object p2pManager = context.getSystemService("wifip2p");
                if (p2pManager == null) p2pManager = context.getApplicationContext().getSystemService("wifip2p");
                if (p2pManager != null) {
                    Class<?> p2pClass = Class.forName("android.net.wifi.p2p.WifiP2pManager");
                    Class<?> channelClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$Channel");
                    Class<?> actionListenerClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$ActionListener");
                    Method initialize = p2pClass.getMethod("initialize", Context.class, android.os.Looper.class, Class.forName("android.net.wifi.p2p.WifiP2pManager$ChannelListener"));
                    Object channel = initialize.invoke(p2pManager, context, context.getMainLooper(), null);
                    Method removeGroup = p2pClass.getMethod("removeGroup", channelClass, actionListenerClass);
                    removeGroup.invoke(p2pManager, channel, null);
                }
            } catch (Exception e) {}

            isHotspotRunning = false;
        }

        updateStatus("WIFI", "Press ENTER to Start");
        updateStatus("HOTSPOT", "Press ENTER to Start");
        setAutoPowerOffMode(true);
    }

    public void shutdown() {
        stopNetworking();
        if (server != null && server.isAlive()) server.stop();

        sleepSonyWifiHardware();
        try { wifiManager.setWifiEnabled(false); } catch (Exception e) {}
    }

    private void updateStatus(String target, String status) {
        if ("HOTSPOT".equals(target)) connStatusHotspot = status;
        else connStatusWifi = status;
        if (listener != null) listener.onStatusUpdate(target, status);
    }
}