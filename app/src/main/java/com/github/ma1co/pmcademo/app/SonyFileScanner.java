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

    // NEW: Lightweight PNG Metadata parser (Safely handles .txt disguises)
    public static String getGrainTitle(File file) {
        String name = file.getName();
        String lowerName = name.toLowerCase();
        
        // Accept PNGs, or TXT files (which might be disguised PNGs)
        if (lowerName.endsWith(".png") || lowerName.endsWith(".txt")) {
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                byte[] header = new byte[8];
                
                // Read the first 8 bytes and check the PNG Magic Signature
                if (fis.read(header) == 8 && header[0] == (byte)137 && header[1] == 80 && header[2] == 78 && header[3] == 71) {
                    while (true) {
                        byte[] lenBuf = new byte[4];
                        if (fis.read(lenBuf) < 4) break;
                        int len = ((lenBuf[0] & 0xFF) << 24) | ((lenBuf[1] & 0xFF) << 16) | 
                                  ((lenBuf[2] & 0xFF) << 8) | (lenBuf[3] & 0xFF);
                        
                        // Prevent OOM from corrupted chunks
                        if (len < 0 || len > 10000) { fis.skip(len + 4); continue; }
                        
                        byte[] typeBuf = new byte[4];
                        if (fis.read(typeBuf) < 4) break;
                        String type = new String(typeBuf, "US-ASCII");

                        if (type.equals("IDAT") || type.equals("IEND")) break; 

                        if (type.equals("tEXt")) {
                            byte[] data = new byte[len];
                            fis.read(data);
                            String textChunk = new String(data, "ISO-8859-1");
                            if (textChunk.startsWith("Title\0")) {
                                fis.close();
                                return textChunk.substring(6); 
                            }
                        } else {
                            fis.skip(len); 
                        }
                        fis.skip(4); // Skip CRC
                    }
                }
                fis.close();
            } catch (Exception e) {
                // Silently fallback on failure
            }
        }
        
        // Fallback: Return the filename minus the extension (.png / .jpg / .txt)
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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