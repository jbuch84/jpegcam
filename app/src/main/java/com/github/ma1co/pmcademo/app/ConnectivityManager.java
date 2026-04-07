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
    private BroadcastReceiver wifiDirectStateReceiver;
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
        // SONY GEN 3 FIX: Use Application Context for all system services to avoid "Busy" errors
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.connManager = (android.net.ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        this.directManager = (DirectManager) context.getApplicationContext().getSystemService("wifi-direct");
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

        // SONY GEN 3 FIX: Explicitly track the "Enabling" state to prevent hangs
        wifiDirectStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(DirectManager.EXTRA_DIRECT_STATE, DirectManager.DIRECT_STATE_UNKNOWN);
                if (state == DirectManager.DIRECT_STATE_ENABLING) {
                    updateStatus("HOTSPOT", "Enabling Direct...");
                } else if (state == DirectManager.DIRECT_STATE_ENABLED) {
                    List<DirectConfiguration> configs = directManager.getConfigurations();
                    if (configs != null && !configs.isEmpty()) {
                        updateStatus("HOTSPOT", "Creating Group...");
                        directManager.startGo(configs.get(configs.size() - 1).getNetworkId()); //
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

        // NEW: Handle Group Creation Failure
        groupCreateFailureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateStatus("HOTSPOT", "Group Create Failed");
                stopNetworking();
            }
        };
        
        context.registerReceiver(wifiDirectStateReceiver, new IntentFilter(DirectManager.DIRECT_STATE_CHANGED_ACTION));
        context.registerReceiver(groupCreateSuccessReceiver, new IntentFilter(DirectManager.GROUP_CREATE_SUCCESS_ACTION));
        context.registerReceiver(groupCreateFailureReceiver, new IntentFilter(DirectManager.GROUP_CREATE_FAILURE_ACTION));

        // SONY GEN 3 FIX: Power Wi-Fi then use a thread to wait for the Service to become available
        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);

        new Thread(new Runnable() {
            @Override
            public void run() {
                int attempts = 0;
                while (isHotspotRunning && directManager == null && attempts < 15) {
                    directManager = (DirectManager) ConnectivityManager.this.context.getApplicationContext().getSystemService("wifi-direct");
                    if (directManager == null) {
                        try { Thread.sleep(500); } catch (Exception e) {}
                        attempts++;
                    }
                }

                ((android.app.Activity)ConnectivityManager.this.context).runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (directManager != null) {
                            directManager.setDirectEnabled(true); // Firing this triggers the wifiDirectStateReceiver
                        } else {
                            updateStatus("HOTSPOT", "Hardware Error: Try Again");
                            isHotspotRunning = false;
                        }
                    }
                });
            }
        }).start();
    }

    public void stopNetworking() {
        if (server != null && server.isAlive()) server.stop();
        if (isHomeWifiRunning) {
            try { context.unregisterReceiver(wifiReceiver); } catch (Exception e) {}
            wifiManager.disconnect(); 
            isHomeWifiRunning = false;
        }
        if (isHotspotRunning) {
            try { context.unregisterReceiver(wifiDirectStateReceiver); } catch (Exception e) {}
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