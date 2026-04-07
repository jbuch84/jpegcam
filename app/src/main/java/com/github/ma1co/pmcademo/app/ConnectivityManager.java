package com.github.ma1co.pmcademo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.sony.wifi.direct.DirectConfiguration;
import com.sony.wifi.direct.DirectManager;

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
        // Standard initialization using Activity context
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
        updateStatus("HOTSPOT", "Initializing...");

        // Re-fetch manager if it was null at app launch
        if (directManager == null) {
            directManager = (DirectManager) context.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
        }

        if (directManager == null) {
            updateStatus("HOTSPOT", "Hardware Unavailable");
            isHotspotRunning = false;
            return;
        }

        // Logic matched to pmcaFilesystemServer state machine
        directStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(DirectManager.EXTRA_DIRECT_STATE, DirectManager.DIRECT_STATE_UNKNOWN);
                if (state == DirectManager.DIRECT_STATE_ENABLING) {
                    updateStatus("HOTSPOT", "Enabling Direct...");
                } else if (state == DirectManager.DIRECT_STATE_ENABLED) {
                    // GEN 3 FIX: Once enabled, we MUST explicitly start the group
                    List<DirectConfiguration> configs = directManager.getConfigurations();
                    if (configs != null && !configs.isEmpty()) {
                        updateStatus("HOTSPOT", "Creating Group...");
                        directManager.startGo(configs.get(configs.size() - 1).getNetworkId());
                    } else {
                        updateStatus("HOTSPOT", "Error: No Hotspot Configs");
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
                    // Hotspot IP is standardized for Sony cameras
                    updateStatus("HOTSPOT", "http://192.168.122.1:8080");
                    startServer();
                    setAutoPowerOffMode(false); 
                }
            }
        };

        groupCreateFailureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateStatus("HOTSPOT", "Group Creation Failed");
                stopNetworking();
            }
        };
        
        context.registerReceiver(directStateReceiver, new IntentFilter(DirectManager.DIRECT_STATE_CHANGED_ACTION));
        context.registerReceiver(groupCreateSuccessReceiver, new IntentFilter(DirectManager.GROUP_CREATE_SUCCESS_ACTION));
        context.registerReceiver(groupCreateFailureReceiver, new IntentFilter(DirectManager.GROUP_CREATE_FAILURE_ACTION));

        // Power-on sequence: Wi-Fi radio first, then the Direct service
        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);
        directManager.setDirectEnabled(true);
    }

    public void stopNetworking() {
        if (server != null && server.isAlive()) server.stop();
        if (isHomeWifiRunning) {
            try { context.unregisterReceiver(wifiReceiver); } catch (Exception e) {}
            wifiManager.disconnect(); 
            isHomeWifiRunning = false;
        }
        if (isHotspotRunning) {
            try { context.unregisterReceiver(directStateReceiver); } catch (Exception e) {}
            try { context.unregisterReceiver(groupCreateSuccessReceiver); } catch (Exception e) {}
            try { context.unregisterReceiver(groupCreateFailureReceiver); } catch (Exception e) {}
            try { if (directManager != null) directManager.setDirectEnabled(false); } catch (Exception e) {}
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