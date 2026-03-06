package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import java.io.File;

public class ImageProcessor {
    private LutEngine mEngine;
    private Context mContext;
    private ProcessorCallback mCallback;
    private PreloadLutTask currentPreloadTask = null;

    // This is the "Bridge" back to MainActivity
    public interface ProcessorCallback {
        void onPreloadStarted();
        void onPreloadFinished(boolean success);
        void onProcessStarted();
        void onProcessFinished(String result);
    }

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
        try {
            mEngine = new LutEngine();
        } catch (Throwable t) {
            Log.e("COOKBOOK", "Native library failed to load: " + t.getMessage());
        }
    }

    public void triggerLutPreload(String lutPath, String lutName) {
        if (currentPreloadTask != null) currentPreloadTask.cancel(true);
        
        if (lutPath != null && !lutPath.equals("NONE") && !lutName.equals("NONE")) {
            currentPreloadTask = new PreloadLutTask();
            currentPreloadTask.execute(lutPath, lutName);
        } else {
            // No LUT selected, instantly report ready
            mCallback.onPreloadFinished(true); 
        }
    }

    public void processJpeg(String originalFilePath, String outDirPath, int qualityIndex, RTLProfile p) {
        new ProcessTask(qualityIndex, p, outDirPath).execute(originalFilePath);
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override 
        protected void onPreExecute() { 
            mCallback.onPreloadStarted();
        }
        
        @Override 
        protected Boolean doInBackground(String... params) {
            if (mEngine == null) return false;
            return mEngine.loadLut(new File(params[0]), params[1]);
        }
        
        @Override 
        protected void onPostExecute(Boolean success) { 
            if (isCancelled()) return; 
            mCallback.onPreloadFinished(success);
        }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        private int qualityIndex;
        private RTLProfile p;
        private String outDirPath;

        public ProcessTask(int qualityIndex, RTLProfile p, String outDirPath) {
            this.qualityIndex = qualityIndex;
            this.p = p;
            this.outDirPath = outDirPath;
        }

        @Override 
        protected void onPreExecute() { 
            mCallback.onProcessStarted();
        }
        
        @Override 
        protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) return "ERR";
                
                // Wait for the camera to finish writing the file
                long lastSize = -1; 
                int timeout = 0;
                while (timeout < 100) {
                    long currentSize = original.length();
                    if (currentSize > 0 && currentSize == lastSize) break;
                    lastSize = currentSize; 
                    Thread.sleep(50); 
                    timeout++;
                }

                int scale = (qualityIndex == 0) ? 4 : (qualityIndex == 2 ? 1 : 2);
                File outDir = new File(outDirPath);
                if (!outDir.exists()) outDir.mkdirs();
                File outFile = new File(outDir, original.getName());

                if (mEngine != null && mEngine.applyLutToJpeg(original.getAbsolutePath(), outFile.getAbsolutePath(), scale, p.opacity, p.grain * 20, p.grainSize, p.vignette * 20, p.rollOff * 20)) {
                    mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                    return "SAVED " + (scale==1?"24MP":(scale==2?"6MP":"1.5MP"));
                }
                return "FAILED";
            } catch (Throwable t) { 
                return "ERR"; 
            }
        }
        
        @Override 
        protected void onPostExecute(String result) {
            mCallback.onProcessFinished(result);
        }
    }
}