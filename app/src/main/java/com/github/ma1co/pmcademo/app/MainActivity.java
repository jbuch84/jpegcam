package com.github.ma1co.pmcademo.app;

import com.jpgcookbook.sony.R;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe, tvStatus, tvQuality; 
    
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    private int qualityIndex = 1; // 0 = PROXY, 1 = HIGH, 2 = ULTRA
    
    private boolean isProcessing = false;
    private boolean isReady = false; 
    private LutEngine mEngine = new LutEngine();
    private PreloadLutTask currentPreloadTask = null; 
    
    private boolean isPolling = false;
    private long lastNewestFileTime = 0;

    public enum DialMode { shutter, aperture, iso, exposure, recipe, quality }
    private DialMode mDialMode = DialMode.recipe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mSurfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        tvShutter = (TextView) findViewById(R.id.tvShutter);
        tvAperture = (TextView) findViewById(R.id.tvAperture);
        tvISO = (TextView) findViewById(R.id.tvISO);
        tvExposure = (TextView) findViewById(R.id.tvExposure);
        tvRecipe = (TextView) findViewById(R.id.tvRecipe);
        
        ViewGroup contentRoot = (ViewGroup) findViewById(android.R.id.content);
        tvStatus = new TextView(this);
        tvStatus.setText("STATUS: STANDBY");
        tvStatus.setTextColor(Color.LTGRAY);
        tvStatus.setTextSize(18); 
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        statusParams.setMargins(30, 80, 0, 0);
        contentRoot.addView(tvStatus, statusParams);

        tvQuality = new TextView(this);
        tvQuality.setText("SIZE: HIGH (6MP)");
        tvQuality.setTextColor(Color.LTGRAY);
        tvQuality.setTextSize(18); 
        FrameLayout.LayoutParams qualityParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        qualityParams.setMargins(0, 80, 30, 0);
        contentRoot.addView(tvQuality, qualityParams);
        
        ViewGroup root = (ViewGroup) ((ViewGroup) this.findViewById(android.R.id.content)).getChildAt(0);
        root.setFocusable(true); root.requestFocus();
        
        scanRecipes();
        setDialMode(mDialMode);
    }

    private void startAutoProcessPolling() {
        isPolling = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isPolling) {
                    try {
                        // OPTIMIZATION 1: High-Frequency Polling (300ms instead of 1000ms)
                        Thread.sleep(300); 
                        if (!isProcessing && isReady && recipeIndex > 0) {
                            File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
                            File sonyDir = new File(dcim, "100MSDCF");
                            if (sonyDir.exists()) {
                                File[] files = sonyDir.listFiles();
                                if (files != null && files.length > 0) {
                                    File newest = null; long maxModified = 0;
                                    for (File f : files) {
                                        if (f.getName().toUpperCase().endsWith(".JPG") && !f.getName().startsWith("PRCS")) {
                                            if (f.lastModified() > maxModified) {
                                                maxModified = f.lastModified(); newest = f;
                                            }
                                        }
                                    }
                                    if (newest != null) {
                                        if (lastNewestFileTime == 0) lastNewestFileTime = maxModified; 
                                        else if (maxModified > lastNewestFileTime) {
                                            lastNewestFileTime = maxModified;
                                            final String path = newest.getAbsolutePath();
                                            runOnUiThread(new Runnable() {
                                                @Override public void run() { if (!isProcessing) new ProcessTask().execute(path); }
                                            });
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

    private class PreloadLutTask extends AsyncTask<Integer, Void, Boolean> {
        @Override protected void onPreExecute() {
            isReady = false;
            if (mCameraEx != null) { mCameraEx.stopDirectShutter(new CameraEx.DirectShutterStoppedCallback() { @Override public void onShutterStopped(CameraEx cameraEx) {} }); }
            tvStatus.setText("STATUS: PRELOADING C++..."); tvStatus.setTextColor(Color.CYAN);
        }
        @Override protected Boolean doInBackground(Integer... params) {
            File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
            if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
            return mEngine.loadLut(new File(lutDir, recipeList.get(params[0])), recipeList.get(params[0]));
        }
        @Override protected void onPostExecute(Boolean success) {
            if (isCancelled()) return; 
            if (mCameraEx != null) mCameraEx.startDirectShutter();
            if (success) { isReady = true; tvStatus.setText("STATUS: ENGINE READY"); tvStatus.setTextColor(Color.GREEN); }
            else { tvStatus.setText("STATUS: ERROR"); tvStatus.setTextColor(Color.RED); }
        }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        @Override protected void onPreExecute() { 
            isProcessing = true;
            if (mCameraEx != null) { mCameraEx.stopDirectShutter(new CameraEx.DirectShutterStoppedCallback() { @Override public void onShutterStopped(CameraEx cameraEx) {} }); }
            tvStatus.setText("STATUS: SCANLINE PROCESSING..."); tvStatus.setTextColor(Color.YELLOW);
        }
        @Override protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) return "ERR: FILE MISSING";

                // OPTIMIZATION 2: Low-Latency Spin-Lock
                long lastSize = -1;
                int timeout = 0;
                while (timeout < 100) { // Max 10 seconds total wait
                    long currentSize = original.length();
                    if (currentSize > 0 && currentSize == lastSize) break;
                    lastSize = currentSize;
                    Thread.sleep(100); // Check every 100ms instead of 500ms
                    timeout++;
                }
                if (timeout >= 100) return "ERR: WRITE TIMEOUT";

                int scale = (qualityIndex == 0) ? 4 : (qualityIndex == 2 ? 1 : 2);
                File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
                if (!outDir.exists()) outDir.mkdirs();
                File outFile = new File(outDir, original.getName());

                if (mEngine.applyLutToJpeg(original.getAbsolutePath(), outFile.getAbsolutePath(), scale)) {
                    copyExif(original.getAbsolutePath(), outFile.getAbsolutePath()); // Proven Java EXIF Copy
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                    return "SUCCESS: SAVED " + (scale==1?"24MP":(scale==2?"6MP":"1.5MP"));
                }
                return "CRASH: C++ DECODE FAILED";
            } catch (Throwable t) { return "ERR: " + t.getMessage(); }
        }
        @Override protected void onPostExecute(String result) {
            isProcessing = false;
            if (mCameraEx != null) mCameraEx.startDirectShutter();
            tvStatus.setText(result); tvStatus.setTextColor(result.startsWith("SUCCESS") ? Color.GREEN : Color.RED);
        }
    }

    private void copyExif(String sourcePath, String destPath) {
        try {
            android.media.ExifInterface sourceExif = new android.media.ExifInterface(sourcePath);
            android.media.ExifInterface destExif = new android.media.ExifInterface(destPath);
            String[] tags = {"FNumber", "ExposureTime", "ISOSpeedRatings", "FocalLength", "DateTime", "Make", "Model", "WhiteBalance", "Flash"};
            for (String tag : tags) { String value = sourceExif.getAttribute(tag); if (value != null) destExif.setAttribute(tag, value); }
            destExif.saveAttributes();
        } catch (IOException e) {}
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        if (sc == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        if (!isProcessing) {
            if (sc == ScalarInput.ISV_KEY_DOWN) { cycleMode(); return true; }
            if (sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
            if (sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleInput(int d) {
        if (mCameraEx == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            if (mDialMode == DialMode.shutter) { if (d > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed(); }
            else if (mDialMode == DialMode.aperture) { if (d > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture(); }
            else if (mDialMode == DialMode.iso) {
                List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities();
                int idx = isos.indexOf(pm.getISOSensitivity());
                if (idx != -1) { pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size()-1, idx + d)))); mCamera.setParameters(p); }
            }
            else if (mDialMode == DialMode.exposure) { p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), p.getExposureCompensation() + d))); mCamera.setParameters(p); }
            else if (mDialMode == DialMode.recipe) {
                recipeIndex = (recipeIndex + d + recipeList.size()) % recipeList.size(); updateRecipeDisplay();
                if (currentPreloadTask != null) currentPreloadTask.cancel(true);
                if (recipeIndex > 0) { currentPreloadTask = new PreloadLutTask(); currentPreloadTask.execute(recipeIndex); }
                else { isReady = false; tvStatus.setText("STATUS: RAW"); tvStatus.setTextColor(Color.LTGRAY); }
            }
            else if (mDialMode == DialMode.quality) {
                qualityIndex = (qualityIndex + d + 3) % 3;
                tvQuality.setText("SIZE: " + (qualityIndex==0?"PROXY (1.5MP)":(qualityIndex==2?"ULTRA (24MP)":"HIGH (6MP)")));
            }
            syncUI();
        } catch (Exception e) {}
    }

    private void syncUI() {
        if (mCamera == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            tvShutter.setText(speed.first == 1 && speed.second != 1 ? speed.first + "/" + speed.second : speed.first + "\"");
            tvAperture.setText("f/" + (pm.getAperture() / 100.0f));
            int isoValue = pm.getISOSensitivity();
            tvISO.setText(isoValue == 0 ? "ISO AUTO" : "ISO " + isoValue);
            tvExposure.setText(String.format("%.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        } catch (Exception e) {}
    }

    private void cycleMode() { setDialMode(DialMode.values()[(mDialMode.ordinal() + 1) % DialMode.values().length]); }
    private void setDialMode(DialMode m) { 
        mDialMode = m; int g = Color.GREEN; int w = Color.WHITE;
        tvShutter.setTextColor(m==DialMode.shutter?g:w); tvAperture.setTextColor(m==DialMode.aperture?g:w);
        tvISO.setTextColor(m==DialMode.iso?g:w); tvExposure.setTextColor(m==DialMode.exposure?g:w);
        tvRecipe.setTextColor(m==DialMode.recipe?g:w); tvQuality.setTextColor(m==DialMode.quality?g:Color.LTGRAY);
        updateRecipeDisplay(); 
    }
    private void scanRecipes() { 
        recipeList.clear(); recipeList.add("NONE"); 
        File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
        if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
        if (lutDir.exists() && lutDir.listFiles() != null) {
            for (File f : lutDir.listFiles()) if (f.length() > 10240 && f.getName().toUpperCase().contains("CUB")) recipeList.add(f.getName());
        }
        updateRecipeDisplay(); 
    }
    private void updateRecipeDisplay() { tvRecipe.setText("< " + recipeList.get(recipeIndex).split("\\.")[0].toUpperCase() + " >"); }
    
    @Override public void surfaceCreated(SurfaceHolder h) { 
        try { 
            mCameraEx = CameraEx.open(0, null); mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            CameraEx.AutoPictureReviewControl apr = new CameraEx.AutoPictureReviewControl();
            mCameraEx.setAutoPictureReviewControl(apr); apr.setPictureReviewTime(0);
            mCamera.setPreviewDisplay(h); mCamera.startPreview(); syncUI();
        } catch (Exception e) {} 
    }
    @Override protected void onResume() { super.onResume(); if (mCamera != null) syncUI(); startAutoProcessPolling(); }
    @Override protected void onPause() { super.onPause(); if (mCameraEx != null) mCameraEx.release(); isPolling = false; }
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { syncUI(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}