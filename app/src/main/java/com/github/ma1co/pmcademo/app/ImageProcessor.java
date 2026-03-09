package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;

public class ImageProcessor {
    public interface ProcessorCallback {
        void onPreloadStarted(); void onPreloadFinished(boolean success);
        void onProcessStarted(); void onProcessFinished(String resultPath);
    }

    private Context mContext;
    private ProcessorCallback mCallback;

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
    }

    public void triggerLutPreload(String lutPath, String name) {
        new PreloadLutTask().execute(lutPath);
    }

    public void processJpeg(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
        new ProcessTask(inPath, outDir, qualityIndex, profile).execute();
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override protected void onPreExecute() { if (mCallback != null) mCallback.onPreloadStarted(); }
        @Override protected Boolean doInBackground(String... params) {
            return LutEngine.loadLut(params[0]);
        }
        @Override protected void onPostExecute(Boolean success) {
            if (mCallback != null) mCallback.onPreloadFinished(success);
        }
    }

    private class ProcessTask extends AsyncTask<Void, Void, String> {
        private String inPath, outDir;
        private int qualityIndex;
        private RTLProfile profile;

        public ProcessTask(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
            this.inPath = inPath; this.outDir = outDir;
            this.qualityIndex = qualityIndex; this.profile = profile;
        }

        @Override protected void onPreExecute() { if (mCallback != null) mCallback.onProcessStarted(); }

        @Override protected String doInBackground(Void... voids) {
            Log.d("filmOS", "Starting RAM-Safe process...");
            System.gc(); // Force memory cleanup before start

            File dir = new File(outDir);
            if (!dir.exists()) dir.mkdirs();

            // Unique 8.3 filename
            String timeTag = Long.toHexString(System.currentTimeMillis() / 1000).toUpperCase();
            String finalOutPath = new File(dir, "F" + timeTag.substring(timeTag.length()-7) + ".JPG").getAbsolutePath();

            String fileToProcess = inPath;
            int scaleDenom = (qualityIndex == 0) ? 4 : (qualityIndex == 1) ? 2 : 1;
            boolean usedTemp = false;

            // Thumbnail Ripping (Saves huge amount of RAM)
            if (qualityIndex == 0) {
                try {
                    ExifInterface exif = new ExifInterface(inPath);
                    byte[] thumb = exif.getThumbnail();
                    if (thumb != null) {
                        File temp = new File(outDir, "TEMP.JPG");
                        FileOutputStream fos = new FileOutputStream(temp);
                        fos.write(thumb); fos.close();
                        fileToProcess = temp.getAbsolutePath();
                        usedTemp = true;
                        scaleDenom = 1; // It's already small
                        Log.d("filmOS", "Using extracted thumbnail.");
                    }
                } catch (Exception e) { Log.e("filmOS", "Thumb rip failed"); }
            }

            // Pre-create output file for Sony FUSE bypass
            try { new FileOutputStream(finalOutPath).close(); } catch (Exception e) {}

            Log.d("filmOS", "Calling Native...");
            boolean success = LutEngine.processImageNative(
                    fileToProcess, finalOutPath, scaleDenom,
                    profile.opacity, profile.grain, profile.grainSize,
                    profile.vignette, profile.rollOff
            );

            if (usedTemp) new File(fileToProcess).delete();

            if (success) {
                Log.d("filmOS", "Success! Notifying system.");
                mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(new File(finalOutPath))));
                return finalOutPath;
            }
            return null;
        }

        @Override protected void onPostExecute(String resultPath) {
            if (mCallback != null) mCallback.onProcessFinished(resultPath);
        }
    }
}