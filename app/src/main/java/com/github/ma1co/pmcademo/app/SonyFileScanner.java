package com.github.ma1co.pmcademo.app;

import android.os.FileObserver;
import java.io.File;

public class SonyFileScanner {
    private String dcimPath;
    private ScannerCallback mCallback;
    private SonyFileObserver mFileObserver;
    private boolean isPolling = false;
    private long lastNewestFileTime = 0;

    // The Bridge back to MainActivity
    public interface ScannerCallback {
        void onNewPhotoDetected(String filePath);
        boolean isReadyToProcess(); 
    }

    public SonyFileScanner(String path, ScannerCallback callback) {
        this.dcimPath = path;
        this.mCallback = callback;
        this.mFileObserver = new SonyFileObserver(dcimPath);
    }

    public void start() {
        mFileObserver.startWatching();
        startPolling();
    }

    public void stop() {
        mFileObserver.stopWatching();
        isPolling = false;
    }

    private class SonyFileObserver extends FileObserver {
        public SonyFileObserver(String path) {
            super(path, FileObserver.CLOSE_WRITE);
        }

        @Override
        public void onEvent(int event, final String path) {
            if (path == null || !mCallback.isReadyToProcess()) return;
            if (path.toUpperCase().endsWith(".JPG") && !path.startsWith("PRCS") && !path.startsWith("GRADED")) {
                mCallback.onNewPhotoDetected(dcimPath + "/" + path);
            }
        }
    }

    private void startPolling() {
        isPolling = true;
        new Thread(new Runnable() {
            @Override 
            public void run() {
                while (isPolling) {
                    try {
                        Thread.sleep(150); 
                        File sonyDir = new File(dcimPath);
                        if (sonyDir.exists()) {
                            File[] files = sonyDir.listFiles();
                            if (files != null && files.length > 0) {
                                File newest = null; 
                                long maxModified = 0;
                                for (File f : files) {
                                    if (f.getName().toUpperCase().endsWith(".JPG") && !f.getName().startsWith("PRCS") && !f.getName().startsWith("GRADED")) {
                                        if (f.lastModified() > maxModified) { 
                                            maxModified = f.lastModified(); 
                                            newest = f; 
                                        }
                                    }
                                }
                                if (newest != null) {
                                    if (lastNewestFileTime == 0) { 
                                        lastNewestFileTime = maxModified; 
                                    } else if (maxModified > lastNewestFileTime) {
                                        lastNewestFileTime = maxModified;
                                        if (mCallback.isReadyToProcess()) {
                                            mCallback.onNewPhotoDetected(newest.getAbsolutePath());
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {}
                }
            }
        }).start();
    }
}