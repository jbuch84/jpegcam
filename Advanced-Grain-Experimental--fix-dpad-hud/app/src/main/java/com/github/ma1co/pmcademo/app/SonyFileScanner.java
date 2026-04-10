package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.HashSet;
import java.util.Locale;

public class SonyFileScanner {
    private ScannerCallback mCallback;
    private Context mContext;
    private HashSet<String> knownFiles = new HashSet<String>();
    private HandlerThread scannerThread;
    private Handler backgroundHandler;
    private Handler mainHandler;
    
    public boolean isPolling = false;
    private int scanAttempts = 0; 

    public interface ScannerCallback {
        void onNewPhotoDetected(String filePath);
        boolean isReadyToProcess(); 
    }

    public SonyFileScanner(Context context, ScannerCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper()); 
        
        scannerThread = new HandlerThread("FileScannerThread");
        scannerThread.start();
        backgroundHandler = new Handler(scannerThread.getLooper());
        
        // Build initial baseline (silent, no polling)
        backgroundHandler.post(new Runnable() {
            @Override public void run() { findNewestFile(false); }
        });
    }

    public void start() {
        stop(); 
        isPolling = true;
        scanAttempts = 0; 
        scheduleNextPoll();
        Log.d("JPEG.CAM", "Scanner Woken Up: Starting 5-second window...");
    }

    public void stop() {
        isPolling = false;
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacksAndMessages(null);
        }
    }

    // --- ADDED THIS METHOD TO FIX LINE 241 ERROR ---
    public void checkNow() {
        if (backgroundHandler != null) {
            backgroundHandler.post(new Runnable() {
                @Override public void run() { findNewestFile(true); }
            });
        }
    }

    public void scheduleNextPoll() {
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPolling) {
                    // 10 attempts at 500ms = 5 seconds total search window
                    if (scanAttempts++ >= 10) {
                        stop();
                        Log.d("JPEG.CAM", "Scanner Timed Out: Going back to sleep.");
                        return;
                    }

                    findNewestFile(true);
                    
                    if (isPolling) scheduleNextPoll(); 
                }
            }
        }, 500);
    }

    private void findNewestFile(boolean triggerCallback) {
        File dcimDir = Filepaths.getDcimDir(); 
        if (!dcimDir.exists() || !dcimDir.isDirectory()) return;

        File[] subDirs = dcimDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                if (dir.isDirectory() && !dir.getName().startsWith(".")) { 
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName().toUpperCase(Locale.US);
                            if (name.endsWith(".JPG") && !name.startsWith("FILM_") && !name.startsWith("PRCS")) {
                                String currentFilePath = f.getAbsolutePath();
                                if (!knownFiles.contains(currentFilePath)) {
                                    
                                    if (f.length() < 1024) continue; 

                                    knownFiles.add(currentFilePath);
                                    
                                    // SUCCESS! Kill the loop immediately
                                    isPolling = false; 
                                    
                                    if (triggerCallback && mCallback != null && mCallback.isReadyToProcess()) {
                                        final String finalPathToProcess = currentFilePath; 
                                        mainHandler.post(new Runnable() {
                                            @Override public void run() { 
                                                mCallback.onNewPhotoDetected(finalPathToProcess); 
                                            }
                                        });
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