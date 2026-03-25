package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;

public class ImageProcessor {
    private LutEngine mEngine;
    private Context mContext;
    private ProcessorCallback mCallback;

    public interface ProcessorCallback {
        void onPreloadStarted();
        void onPreloadFinished(boolean success);
        void onProcessStarted();
        void onProcessFinished(String result);
    }

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
        this.mEngine = new LutEngine();
    }

    public void triggerLutPreload(String lutPath, String lutName) {
        new PreloadLutTask().execute(lutPath, lutName);
    }

    // ADDED: int jpegQuality
    public void processJpeg(String originalPath, String outDirPath, int qualityIndex, int jpegQuality, RTLProfile p) {
        new ProcessTask(qualityIndex, jpegQuality, p, outDirPath).execute(originalPath);
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override protected void onPreExecute() { mCallback.onPreloadStarted(); }
        @Override protected Boolean doInBackground(String... params) {
            return mEngine.loadLut(new File(params[0]), params[1]);
        }
        @Override protected void onPostExecute(Boolean success) { mCallback.onPreloadFinished(success); }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        private int qualityIdx;
        private int jpegQuality; // ADDED
        private RTLProfile p;
        private String outDir;

        // ADDED: int jpegQuality to constructor
        public ProcessTask(int q, int jpegQuality, RTLProfile p, String out) { 
            this.qualityIdx = q; 
            this.jpegQuality = jpegQuality; 
            this.p = p; 
            this.outDir = out; 
        }

        @Override protected void onPreExecute() { mCallback.onProcessStarted(); }

        @Override protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) return "ERR";

                long lastSize = -1; int timeout = 0;
                while (timeout < 50) {
                    long currentSize = original.length();
                    if (currentSize > 0 && currentSize == lastSize) break;
                    lastSize = currentSize; 
                    Thread.sleep(100); timeout++;
                }

                File dir = new File(outDir);
                if (!dir.exists()) dir.mkdirs();
                
                File outFile = new File(dir, original.getName());

                FileOutputStream fos = new FileOutputStream(outFile);
                fos.write(1);
                fos.close();

                // 0=1/4 RES (4), 1=HALF RES (2), 2=FULL RES (1)
                int scale = (qualityIdx == 0) ? 4 : (qualityIdx == 2 ? 1 : 2);

                // Dynamically set JPEG encode quality
                int jpegQuality = 95; // FULL RES
                if (scale == 4) {
                    jpegQuality = 85; // 1/4 RES
                } else if (scale == 2) {
                    jpegQuality = 90; // HALF RES
                }

                // --- FIXED: Passing the 2 new variables (p.colorChrome and p.chromeBlue) ---
                if (mEngine.applyLutToJpeg(original.getAbsolutePath(), outFile.getAbsolutePath(), scale, p.opacity, p.grain, p.grainSize, p.vignette, p.rollOff, p.colorChrome, p.chromeBlue, p.shadowToe, p.subtractiveSat, p.halation, jpegQuality)) {
        return "SAVED";
    }
            } catch (Exception e) { Log.e("COOKBOOK", "Java error: " + e.getMessage()); }
            return "FAILED";
        }

        @Override protected void onPostExecute(String result) { mCallback.onProcessFinished(result); }
    }
}