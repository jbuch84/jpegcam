package com.github.ma1co.pmcademo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.sony.wifi.direct.DirectConfiguration;
import com.sony.wifi.direct.DirectManager;

import java.lang.reflect.Method;
import java.util.List;

/**
 * JPEG.CAM Manager: Connectivity & Networking
 * Manages Wi-Fi, Hotspot, and the JPEG.CAM Dashboard server.
 */
public class ConnectivityManager {
    private Context context;
    private WifiManager wifiManager;
    private android.net.ConnectivityManager connManager;
    private DirectManager directManager;
    private HttpServer server;

    private BroadcastReceiver wifiReceiver;
    private BroadcastReceiver directStateReceiver;
    private BroadcastReceiver groupCreateSuccessReceiver;
    private BroadcastReceiver groupCreateFailureReceiver;

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
        // Standard Sony initialization (Will gracefully return null on Gen 3 cameras)
        this.directManager = (DirectManager) context.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
        this.server = new HttpServer(context);
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

    public void startHomeWifi() {
        stopNetworking(); 
        isHomeWifiRunning = true;
        updateStatus("WIFI", "Connecting to Router...");
        
        wifiReceiver = new BroadcastReceiver() {
            int attempts = 0; 
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isHomeWifiRunning) return;
                String action = intent.getAction();
                
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    if (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED) {
                        wifiManager.reconnect(); 
                    }
                } else if (android.net.ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                    NetworkInfo info = connManager.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
                    if (info != null && info.isConnected()) {
                        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        int ip = wifiInfo.getIpAddress();
                        if (ip != 0) {
                            String ipAddress = String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                            updateStatus("WIFI", "http://" + ipAddress + ":" + HttpServer.PORT);
                            startServer();
                            setAutoPowerOffMode(false); 
                        }
                    } else {
                        attempts++;
                        if (attempts > 30) {
                            updateStatus("WIFI", "Timed out.");
                            stopNetworking();
                        } else {
                            updateStatus("WIFI", "Searching for network...");
                        }
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        context.registerReceiver(wifiReceiver, filter);
        
        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);
        else wifiManager.reconnect();
    }

    public void startHotspot() {
        stopNetworking(); 
        isHotspotRunning = true;
        updateStatus("HOTSPOT", "Starting Hotspot...");

        // Ensure Wi-Fi is enabled first
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }

        // --- GEN 2 LOGIC (A5100) ---
        if (directManager == null) {
            directManager = (DirectManager) context.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
        }

        if (directManager != null) {
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
                        updateStatus("HOTSPOT", "http://192.168.122.1:8080");
                        startServer();
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
            return;
        }

        // --- GEN 3 LOGIC (A7II, A6500) FALLBACK ---
        // If DirectManager is completely absent, use pure Android Reflection to force standard AP Tethering
        try {
            Method setWifiApEnabled = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            boolean success = (Boolean) setWifiApEnabled.invoke(wifiManager, null, true);
            
            if (success) {
                // Fetch the system's generated password
                Method getWifiApConfiguration = wifiManager.getClass().getMethod("getWifiApConfiguration");
                WifiConfiguration apConfig = (WifiConfiguration) getWifiApConfiguration.invoke(wifiManager);
                
                if (apConfig != null) {
                    updateStatus("HOTSPOT", "PW: " + apConfig.preSharedKey + " (192.168.43.1)");
                } else {
                    updateStatus("HOTSPOT", "Connect Phone (192.168.43.1)");
                }
                startServer();
                setAutoPowerOffMode(false); 
            } else {
                updateStatus("HOTSPOT", "Hardware Unsupported");
                isHotspotRunning = false;
            }
        } catch (Exception e) {
            updateStatus("HOTSPOT", "Error: " + e.getMessage());
            isHotspotRunning = false;
        }
    }

    // Safety wrapper to prevent the "Exit Crash"
    private void unregisterReceiverSafe(BroadcastReceiver receiver) {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception e) {
                // Ignore if it was never fully registered
            }
        }
    }

    public void stopNetworking() {
        if (server != null && server.isAlive()) server.stop();
        
        // Safely kill all receivers to prevent camera crashes
        unregisterReceiverSafe(wifiReceiver);
        wifiReceiver = null;
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
            // Cleanup Gen 2
            try { if (directManager != null) directManager.setDirectEnabled(false); } catch (Exception e) {}
            
            // Cleanup Gen 3 (Turn off Android Tethering)
            try {
                Method setWifiApEnabled = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                setWifiApEnabled.invoke(wifiManager, null, false);
            } catch (Exception e) {}

            isHotspotRunning = false;
        }
        
        updateStatus("WIFI", "Press ENTER to Start");
        updateStatus("HOTSPOT", "Press ENTER to Start");
        setAutoPowerOffMode(true); 
    }

    private void startServer() {
        try { if (!server.isAlive()) server.start(); } catch (Exception e) {}
    }

    private void updateStatus(String target, String status) {
        if ("HOTSPOT".equals(target)) connStatusHotspot = status;
        else connStatusWifi = status;
        if (listener != null) listener.onStatusUpdate(target, status);
    }
}