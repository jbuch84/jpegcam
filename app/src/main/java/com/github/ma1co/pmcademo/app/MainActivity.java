package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    private TextView tvStatus; 
    
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    private boolean isProcessing = false;
    
    private LutEngine mEngine = new LutEngine();
    private PreloadLutTask currentPreloadTask = null; 
    
    private boolean isPolling = false;
    private long lastNewestFileTime = 0;

    public enum DialMode { shutter, aperture, iso, exposure, recipe }
    private DialMode mDialMode = DialMode.shutter;

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
        
        ViewGroup layout = (ViewGroup) tvRecipe.getParent();
        tvStatus = new TextView(this);
        tvStatus.setText("STATUS: STANDBY");
        tvStatus.setTextColor(Color.LTGRAY);
        tvStatus.setTextSize(18); 
        tvStatus.setPadding(0, 10, 0, 0);
        layout.addView(tvStatus);
        
        ViewGroup root = (ViewGroup) ((ViewGroup) this.findViewById(android.R.id.content)).getChildAt(0);
        root.setFocusable(true);
        root.requestFocus();
        
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    private void startAutoProcessPolling() {
        isPolling = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isPolling) {
                    try {
                        Thread.sleep(1000); 
                        if (!isProcessing && recipeIndex > 0) {
                            File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
                            File sonyDir = new File(dcim, "100MSDCF");
                            if (sonyDir.exists()) {
                                File[] files = sonyDir.listFiles();
                                if (files != null && files.length > 0) {
                                    File newest = null;
                                    long maxModified = 0;
                                    for (File f : files) {
                                        // Simplified check: we just care about native JPEGs in the Sony folder
                                        if (f.getName().toUpperCase().endsWith(".JPG")) {
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
                                            Thread.sleep(2000); 
                                            lastNewestFileTime = maxModified;
                                            final String path = newest.getAbsolutePath();
                                            runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    if (!isProcessing) new ProcessTask().execute(path);
                                                }
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

    private void stopAutoProcessPolling() { isPolling = false; }

    private class PreloadLutTask extends AsyncTask<Integer, Void, Boolean> {
        @Override protected void onPreExecute() {
            tvStatus.setText("STATUS: PRELOADING...");
            tvStatus.setTextColor(Color.CYAN);
        }

        @Override protected Boolean doInBackground(Integer... params) {
            int index = params[0];
            if (index > 0) {
                File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
                if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
                String selectedRecipe = recipeList.get(index);
                File cubeFile = new File(lutDir, selectedRecipe);
                if (cubeFile.exists()) {
                    return mEngine.loadLut(cubeFile, selectedRecipe);
                }
            }
            return false;
        }

        @Override protected void onPostExecute(Boolean success) {
            if (isCancelled()) return; 
            if (success) {
                tvStatus.setText("STATUS: READY");
                tvStatus.setTextColor(Color.GREEN);
            } else {
                tvStatus.setText("STATUS: BAD LUT FILE");
                tvStatus.setTextColor(Color.RED);
            }
        }
    }

    private class ProcessTask extends AsyncTask<String, Integer, String> {
        @Override protected void onPreExecute() { 
            isProcessing = true;
            if (mCameraEx != null) {
                mCameraEx.stopDirectShutter(new CameraEx.DirectShutterStoppedCallback() {
                    @Override public void onShutterStopped(CameraEx cameraEx) {}
                });
            }
            tvStatus.setText("STATUS: PREPARING...");
            tvStatus.setTextColor(Color.YELLOW);
        }

        @Override protected void onProgressUpdate(Integer... values) {
            if (values[0] == -1) {
                tvStatus.setText("STATUS: READING DATA..."); 
            } else {
                tvStatus.setText("STATUS: PROCESSING [" + values[0] + "%]");
            }
        }

        @Override protected String doInBackground(String... params) {
            try {
                File original = null;
                
                if (params != null && params.length > 0) {
                    original = new File(params[0]);
                } else {
                    File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
                    File sonyDir = new File(dcim, "100MSDCF");
                    File[] files = sonyDir.listFiles();
                    if (files != null) {
                        Arrays.sort(files, new Comparator<File>() {
                            public int compare(File f1, File f2) {
                                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
                            }
                        });
                        for (File f : files) {
                            if (f.getName().toUpperCase().endsWith(".JPG")) {
                                original = f; break;
                            }
                        }
                    }
                }
                
                if (original == null || !original.exists()) return "ERR: NO JPG FOUND";

                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = 2; 
                opt.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap rawBmp = BitmapFactory.decodeFile(original.getAbsolutePath(), opt);
                if (rawBmp == null) return "ERR: DECODE FAIL";

                Bitmap processedBmp = rawBmp.copy(Bitmap.Config.RGB_565, true);
                rawBmp.recycle();
                rawBmp = null;

                if (recipeIndex > 0) {
                    File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
                    if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
                    
                    String selectedRecipe = recipeList.get(recipeIndex);
                    File cubeFile = new File(lutDir, selectedRecipe);
                    
                    if (cubeFile.exists()) {
                        if (!selectedRecipe.equals(mEngine.getCurrentLutName())) {
                            publishProgress(-1); 
                            if (!mEngine.loadLut(cubeFile, selectedRecipe)) return "ERR: BAD LUT FILE";
                        }
                        
                        mEngine.applyLutToBitmap(processedBmp, new LutEngine.ProgressCallback() {
                            public void onProgress(int percent) { publishProgress(percent); }
                        }); 
                    } else {
                        return "ERR: LUT NOT FOUND";
                    }
                }

                // DEDICATED RENDER FOLDER
                File rootDir = Environment.getExternalStorageDirectory();
                File processedDir = new File(rootDir, "PROCESSED");
                if (!processedDir.exists()) processedDir.mkdirs();
                
                // PRESERVING EXACT ORIGINAL FILENAME
                String newName = original.getName();
                File outFile = new File(processedDir, newName);

                FileOutputStream fos = new FileOutputStream(outFile);
                processedBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos); 
                fos.flush();
                fos.close();
                processedBmp.recycle();

                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                return "SUCCESS: " + newName;
                
            } catch (OutOfMemoryError oom) {
                return "ERR: OUT OF MEMORY";
            } catch (Throwable t) {
                return "CRASH: " + t.getMessage();
            }
        }

        @Override protected void onPostExecute(String result) {
            isProcessing = false;
            if (mCameraEx != null) mCameraEx.startDirectShutter();
            tvStatus.setText("STATUS: " + result);
            tvStatus.setTextColor(result.startsWith("SUCCESS") ? Color.GREEN : Color.RED);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        if (scanCode == ScalarInput.ISV_KEY_UP) { 
            if (!isProcessing && recipeIndex > 0) new ProcessTask().execute(); 
            return true; 
        }
        if (!isProcessing) {
            if (scanCode == ScalarInput.ISV_KEY_DOWN) { cycleMode(); return true; }
            if (scanCode == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
            if (scanCode == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleInput(int d) {
        if (mCameraEx == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            if (mDialMode == DialMode.shutter) {
                if (d > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed();
            } else if (mDialMode == DialMode.aperture) {
                if (d > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture();
            } else if (mDialMode == DialMode.iso) {
                List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities();
                int idx = isos.indexOf(pm.getISOSensitivity());
                if (idx != -1) {
                    pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size() - 1, idx + d))));
                    mCamera.setParameters(p);
                }
            } else if (mDialMode == DialMode.exposure) {
                p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), p.getExposureCompensation() + d)));
                mCamera.setParameters(p);
            } else if (mDialMode == DialMode.recipe) {
                recipeIndex = (recipeIndex + d + recipeList.size()) % recipeList.size();
                updateRecipeDisplay();
                
                if (currentPreloadTask != null) currentPreloadTask.cancel(true);
                
                if (recipeIndex > 0) {
                    currentPreloadTask = new PreloadLutTask();
                    currentPreloadTask.execute(recipeIndex);
                } else {
                    tvStatus.setText("STATUS: RAW (NO LUT)");
                    tvStatus.setTextColor(Color.LTGRAY);
                }
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
        mDialMode = m; 
        tvShutter.setTextColor(m == DialMode.shutter ? Color.GREEN : Color.WHITE);
        tvAperture.setTextColor(m == DialMode.aperture ? Color.GREEN : Color.WHITE);
        tvISO.setTextColor(m == DialMode.iso ? Color.GREEN : Color.WHITE);
        tvExposure.setTextColor(m == DialMode.exposure ? Color.GREEN : Color.WHITE);
        updateRecipeDisplay(); 
    }
    
    private void scanRecipes() { 
        recipeList.clear(); 
        recipeList.add("NONE"); 
        File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
        if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
        
        if (lutDir.exists() && lutDir.listFiles() != null) {
            for (File f : lutDir.listFiles()) {
                if (f.length() < 10240) continue; 
                String name = f.getName().toUpperCase();
                if (name.contains("CUB")) recipeList.add(f.getName());
            }
        }
        updateRecipeDisplay(); 
    }
    
    private void updateRecipeDisplay() { 
        String name = recipeList.get(recipeIndex).split("\\.")[0].toUpperCase();
        tvRecipe.setText("< " + name + " >");
        tvRecipe.setTextColor(mDialMode == DialMode.recipe ? Color.GREEN : Color.WHITE);
    }
    
    @Override public void surfaceCreated(SurfaceHolder h) { 
        try { 
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            CameraEx.AutoPictureReviewControl apr = new CameraEx.AutoPictureReviewControl();
            mCameraEx.setAutoPictureReviewControl(apr);
            apr.setPictureReviewTime(0);
            mCamera.setPreviewDisplay(h);
            mCamera.startPreview();
            syncUI();
        } catch (Exception e) {} 
    }
    
    @Override protected void onResume() { 
        super.onResume(); 
        if (mCamera != null) syncUI(); 
        startAutoProcessPolling(); 
    }
    
    @Override protected void onPause() { 
        super.onPause(); 
        if (mCameraEx != null) mCameraEx.release(); 
        stopAutoProcessPolling(); 
    }
    
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { syncUI(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}