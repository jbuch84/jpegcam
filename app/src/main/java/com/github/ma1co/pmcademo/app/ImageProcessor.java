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
        new PreloadLutTask().execute(lutPath);
    }

    public void processJpeg(String originalPath, String outDirPath, int qualityIndex, RTLProfile p) {
        new ProcessTask(qualityIndex, p, outDirPath).execute(originalPath);
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override protected void onPreExecute() { mCallback.onPreloadStarted(); }
        @Override protected Boolean doInBackground(String... params) {
            return mEngine.loadLut(params[0]);
        }
        @Override protected void onPostExecute(Boolean success) { mCallback.onPreloadFinished(success); }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        private int qualityIndex;
        private RTLProfile p;
        private String outDirPath;

        public ProcessTask(int q, RTLProfile p, String out) { 
            this.qualityIndex = q; 
            this.p = p; 
            this.outDirPath = out; 
        }

        @Override protected void onPreExecute() { mCallback.onProcessStarted(); }

        @Override protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) return "ERR";

                // --- THE AH-HAH FIX: STABILIZATION LOOP ---
                long lastSize = -1; 
                int timeout = 0;
                while (timeout < 60) { // Max 6 seconds
                    long currentSize = original.length();
                    if (currentSize > 0 && currentSize == lastSize) break;
                    lastSize = currentSize; 
                    Thread.sleep(100); 
                    timeout++;
                }

                File outDir = new File(outDirPath);
                if (!outDir.exists()) outDir.mkdirs();
                
                // 8.3 naming for Sony FUSE stability
                String timeTag = Long.toHexString(System.currentTimeMillis() / 1000).toUpperCase();
                File outFile = new File(outDir, "F" + timeTag.substring(timeTag.length()-7) + ".JPG");

                // Sony FUSE Pre-create Workaround
                new FileOutputStream(outFile).write(1);

                // Pass qualityIndex (0=Proxy, 1=High, 2=Ultra)
                // We multiply grain/vig/roll by 20 to match your early logic
                boolean success = mEngine.applyLutToJpeg(
                    original.getAbsolutePath(), 
                    outFile.getAbsolutePath(), 
                    qualityIndex, 
                    p.opacity, 
                    p.grain * 20, 
                    p.grainSize, 
                    p.vignette * 20, 
                    p.rollOff * 20
                );

                if (success) {
                    mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                    return "SAVED";
                }
            } catch (Exception e) { 
                Log.e("COOKBOOK", "Java error: " + e.getMessage()); 
            }
            return "FAILED";
        }

        @Override protected void onPostExecute(String result) { mCallback.onProcessFinished(result); }
    }
}