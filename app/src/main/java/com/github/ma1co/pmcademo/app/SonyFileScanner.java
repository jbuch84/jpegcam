package com.github.ma1co.pmcademo.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;

public class SonyFileScanner {
    private String dcimRoot;
    private ScannerCallback mCallback;
    private String lastSeenFilePath = "";
    
    private Handler pollHandler;
    private Runnable pollRunnable;
    private boolean isPolling = false;

    public interface ScannerCallback {
        void onNewPhotoDetected(String filePath);
        boolean isReadyToProcess(); 
    }

    public SonyFileScanner(String path, ScannerCallback callback) {
        File f = new File(path);
        this.dcimRoot = (f.getParent() != null) ? f.getParent() : path;
        this.mCallback = callback;
        
        Log.d("filmOS", "SonyFileScanner initialized. DCIM Root: " + dcimRoot);
        
        // Find baseline without triggering callback
        findNewestFile(false); 
        
        // Setup hybrid fallback polling (1-second intervals)
        pollHandler = new Handler(Looper.getMainLooper());
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPolling) {
                    findNewestFile(true);
                    pollHandler.postDelayed(this, 1000);
                }
            }
        };
        start();
    }

    public void start() {
        if (!isPolling) {
            Log.d("filmOS", "Starting file scanner polling loop...");
            isPolling = true;
            pollHandler.post(pollRunnable);
        }
    }

    public void stop() {
        Log.d("filmOS", "Stopping file scanner polling loop.");
        isPolling = false;
        pollHandler.removeCallbacks(pollRunnable);
    }

    public void checkNow() {
        Log.d("filmOS", "Hardware Broadcast caught! Forcing immediate check...");
        findNewestFile(true);
    }

    private void findNewestFile(boolean triggerCallback) {
        File dcimDir = new File(dcimRoot);
        if (!dcimDir.exists() || !dcimDir.isDirectory()) {
            if (triggerCallback) Log.e("filmOS", "DCIM directory not found: " + dcimRoot);
            return;
        }

        File newestFile = null;
        long maxModified = 0;

        File[] subDirs = dcimDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                if (dir.isDirectory() && dir.getName().toUpperCase().endsWith("MSDCF")) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName().toUpperCase();
                            // Look for original Sony JPEGs (Ignore our FILM_ outputs and temp files)
                            if (name.endsWith(".JPG") && !name.startsWith("FILM_") && !name.startsWith("PRCS") && !name.startsWith("temp_")) {
                                if (f.lastModified() > maxModified) {
                                    maxModified = f.lastModified();
                                    newestFile = f;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (newestFile != null) {
            String currentPath = newestFile.getAbsolutePath();
            if (!currentPath.equals(lastSeenFilePath)) {
                Log.d("filmOS", "NEW FILE DETECTED: " + currentPath);
                lastSeenFilePath = currentPath;
                
                if (triggerCallback && mCallback != null) {
                    boolean isReady = mCallback.isReadyToProcess();
                    Log.d("filmOS", "isReadyToProcess() evaluated to: " + isReady);
                    
                    if (isReady) {
                        Log.d("filmOS", "Firing onNewPhotoDetected callback!");
                        mCallback.onNewPhotoDetected(currentPath);
                    } else {
                        Log.w("filmOS", "Engine blocked processing. (Either LUT is 0/OFF or processor is not initialized).");
                    }
                }
            }
        }
    }
}