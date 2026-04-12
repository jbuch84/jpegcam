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
            // Already in desired state, wait a tiny bit for stability and run
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
                        
                        // CRITICAL FIX FOR A6500: Wait 1 full second after the OS says "ENABLED" 
                        // before hitting the radio with commands, allowing hardware to settle.
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

        // UNIFIED LOGIC: All Sony cameras use Wi-Fi Direct.
        // We wait for the antenna to fully boot BEFORE fetching the service, 
        // preventing the A7II/A6500 from returning a null service.
        waitForHardwareAndExecute(true, new Runnable() {
            @Override
            public void run() {
                if (!isHotspotRunning) return;
                updateStatus("HOTSPOT", "Starting Hotspot...");

                if (directManager == null) {
                    // Safe to fetch now, hardware is awake.
                    directManager = (DirectManager) context.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
                }

                if (directManager == null) {
                    updateStatus("HOTSPOT", "HW Error: Service Missing");
                    isHotspotRunning = false;
                    return;
                }

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
                            if ("N/A".equals(password)) {
                                try {
                                    for (Method m : config.getClass().getMethods()) {
                                        if (m.getReturnType() == String.class && m.getParameterTypes().length == 0) {
                                            m.setAccessible(true);
                                            String val = (String) m.invoke(config);
                                            if (val != null && val.length() >= 8 && !val.contains("DIRECT-")) {
                                                password = val;
                                                break;
                                            }
                                        }
                                    }
                                } catch (Exception e) {}
                            }
                            // Sony's Wi-Fi Direct DHCP server always assigns the camera (Group Owner) to 122.1
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
        });
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