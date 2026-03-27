package com.github.ma1co.pmcademo.app;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.HashSet;

public class SonyFileScanner {
    private ScannerCallback mCallback;
    
    // The Delta Tracker: Remembers every file that existed when the app booted
    private HashSet<String> knownFiles = new HashSet<String>();
    
    private HandlerThread scannerThread;
    private Handler backgroundHandler;
    private Handler mainHandler;
    private boolean isPolling = false;

    public interface ScannerCallback {
        void onNewPhotoDetected(String filePath);
        boolean isReadyToProcess(); 
    }

    public SonyFileScanner(ScannerCallback callback) {
        this.mCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper()); 
        
        Log.d("JPEG.CAM", "SonyFileScanner initialized. Root: " + Filepaths.getDcimDir().getAbsolutePath());
        
        scannerThread = new HandlerThread("FileScannerThread");
        scannerThread.start();
        backgroundHandler = new Handler(scannerThread.getLooper());
        
        // Build the baseline index in the background so it doesn't slow down the app boot
        backgroundHandler.post(new Runnable() {
            @Override public void run() { 
                findNewestFile(false); 
                start(); // Only start polling AFTER the baseline is built
            }
        });
    }

    public void start() {
        if (!isPolling) {
            isPolling = true;
            scheduleNextPoll();
        }
    }

    public void stop() {
        isPolling = false;
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacksAndMessages(null);
        }
    }

    public void checkNow() {
        if (backgroundHandler != null) {
            backgroundHandler.post(new Runnable() {
                @Override public void run() { findNewestFile(true); }
            });
        }
    }

    private void scheduleNextPoll() {
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPolling) {
                    findNewestFile(true);
                    scheduleNextPoll(); 
                }
            }
        }, 1000);
    }

    private void findNewestFile(boolean triggerCallback) {
        File dcimDir = Filepaths.getDcimDir(); 
        if (!dcimDir.exists() || !dcimDir.isDirectory()) return;

        File[] subDirs = dcimDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                String dirName = dir.getName().toUpperCase();
                
                // Only look inside valid photo folders
                if (dir.isDirectory() && (dirName.endsWith("MSDCF") || dirName.contains("ALPHA") || dirName.contains("SONY"))) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName().toUpperCase();
                            if (name.endsWith(".JPG") && !name.startsWith("FILM_") && !name.startsWith("PRCS") && !name.startsWith("TEMP_")) {
                                
                                // DELTA TRACKING: Ignore sorting entirely. Just ask, "Is this file brand new?"
                                String currentFilePath = f.getAbsolutePath();
                                
                                if (!knownFiles.contains(currentFilePath)) {
                                    // Add it to the tracker so we don't process it twice
                                    knownFiles.add(currentFilePath);
                                    
                                    if (triggerCallback) {
                                        Log.d("JPEG.CAM", "NEW FILE DETECTED: " + currentFilePath);
                                        
                                        if (mCallback != null) {
                                            if (mCallback.isReadyToProcess()) {
                                                
                                                // Create a final copy of the string to satisfy the Java compiler for the inner class
                                                final String finalPathToProcess = currentFilePath; 
                                                
                                                mainHandler.post(new Runnable() {
                                                    @Override public void run() { mCallback.onNewPhotoDetected(finalPathToProcess); }
                                                });
                                                
                                            } else {
                                                Log.w("JPEG.CAM", "Engine blocked processing. (LUT is 0/OFF or processor not initialized).");
                                            }
                                        }
                                    }
                                }
                                
                            }
                        }
                    }
                }
            }
        }
    }
}