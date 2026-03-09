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

    public void processJpeg(String originalPath, String outDirPath, int qualityIndex, RTLProfile p) {
        new ProcessTask(qualityIndex, p, outDirPath).execute(originalPath);
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override protected void onPreExecute() { mCallback.onPreloadStarted(); }
        @Override protected Boolean doInBackground(String... params) {
            return mEngine.loadLut(params[0], params[1]);
        }
        @Override protected void onPostExecute(Boolean success) { mCallback.onPreloadFinished(success); }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        private int qualityIndex;
        private RTLProfile p;
        private String outDirPath;

        public ProcessTask(int q, RTLProfile p, String out) { this.qualityIndex = q; this.p = p; this.outDirPath = out; }

        @Override protected void onPreExecute() { mCallback.onProcessStarted(); }

        @Override protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) return "ERR";

                // --- THE STABILIZATION LOOP ---
                long lastSize = -1; 
                int timeout = 0;
                while (timeout < 50) {
                    long currentSize = original.length();
                    if (currentSize > 0 && currentSize == lastSize) break;
                    lastSize = currentSize; 
                    Thread.sleep(100); 
                    timeout++;
                }

                File outDir = new File(outDirPath);
                if (!outDir.exists()) outDir.mkdirs();
                
                String outName = "FLM_" + (System.currentTimeMillis() / 1000 % 10000) + ".JPG";
                File outFile = new File(outDir, outName);

                // --- SONY FUSE PRE-CREATE (Correctly Closed) ---
                FileOutputStream fos = new FileOutputStream(outFile);
                fos.write(1);
                fos.close();

                // Passing qualityIndex (0, 1, 2) directly to C++ for smart scaling
                if (mEngine.applyLutToJpeg(original.getAbsolutePath(), outFile.getAbsolutePath(), qualityIndex, p.opacity, p.grain * 20, p.grainSize, p.vignette * 20, p.rollOff * 20)) {
                    mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                    return "SAVED";
                }
            } catch (Exception e) { Log.e("COOKBOOK", "Java error: " + e.getMessage()); }
            return "FAILED";
        }

        @Override protected void onPostExecute(String result) { mCallback.onProcessFinished(result); }
    }
}