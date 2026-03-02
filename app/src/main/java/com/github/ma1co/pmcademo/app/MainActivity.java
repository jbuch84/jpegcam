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

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    private boolean isBaking = false;
    
    private LutCooker mCooker = new LutCooker();
    private FileObserver photoObserver; // Watches for new photos!

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
        
        ViewGroup root = (ViewGroup) ((ViewGroup) this.findViewById(android.R.id.content)).getChildAt(0);
        root.setFocusable(true);
        root.requestFocus();
        
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    // THE AUTO-COOK LISTENER
    private void setupPhotoObserver() {
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        final File sonyDir = new File(dcim, "100MSDCF");
        if (!sonyDir.exists()) return;

        // Watch the directory for when a file is completely finished writing
        photoObserver = new FileObserver(sonyDir.getAbsolutePath(), FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.toUpperCase().endsWith(".JPG") && !path.startsWith("CKED")) {
                    final String fullPath = new File(sonyDir, path).getAbsolutePath();
                    
                    // Bounce back to the main UI thread to start the Bake
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isBaking && recipeIndex > 0) {
                                new BakeTask().execute(fullPath); // Pass the exact file path
                            }
                        }
                    });
                }
            }
        };
        photoObserver.startWatching();
    }

    private class BakeTask extends AsyncTask<String, Integer, String> {
        @Override protected void onPreExecute() { 
            isBaking = true;
            if (mCameraEx != null) {
                mCameraEx.stopDirectShutter(new CameraEx.DirectShutterStoppedCallback() {
                    @Override public void onShutterStopped(CameraEx cameraEx) {}
                });
            }
            String recipeName = recipeList.get(recipeIndex).split("\\.")[0].toUpperCase();
            tvRecipe.setText("PREPPING " + recipeName + "...");
            tvRecipe.setTextColor(Color.YELLOW);
        }

        @Override protected void onProgressUpdate(Integer... values) {
            String recipeName = recipeList.get(recipeIndex).split("\\.")[0].toUpperCase();
            if (values[0] == -1) {
                tvRecipe.setText("READING RECIPE DATA...");
            } else {
                tvRecipe.setText("COOKING " + recipeName + " [" + values[0] + "%]");
            }
        }

        @Override protected String doInBackground(String... params) {
            try {
                File original = null;
                
                // If the FileObserver gave us the exact path, use it! Otherwise, search (manual override)
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
                            if (f.getName().toUpperCase().endsWith(".JPG") && !f.getName().startsWith("CKED")) {
                                original = f; break;
                            }
                        }
                    }
                }
                
                if (original == null || !original.exists()) return "ERR: NO JPG";

                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = 4; // Keep at 4 for this test
                Bitmap bmp = BitmapFactory.decodeFile(original.getAbsolutePath(), opt);
                if (bmp == null) return "ERR: DECODE FAIL";

                int width = bmp.getWidth();
                int height = bmp.getHeight();
                int[] pixels = new int[width * height];
                bmp.getPixels(pixels, 0, width, 0, 0, width, height);
                bmp.recycle();
                bmp = null;

                if (recipeIndex > 0) {
                    File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
                    if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
                    
                    String selectedRecipe = recipeList.get(recipeIndex);
                    File cubeFile = new File(lutDir, selectedRecipe);
                    
                    if (cubeFile.exists()) {
                        if (!selectedRecipe.equals(mCooker.getCurrentLutName())) {
                            publishProgress(-1); 
                            if (!mCooker.loadLut(cubeFile, selectedRecipe)) return "ERR: BAD LUT FILE";
                        }
                        mCooker.applyLutToPixels(pixels, new LutCooker.ProgressCallback() {
                            public void onProgress(int percent) { publishProgress(percent); }
                        }); 
                    } else {
                        return "ERR: LUT NOT FOUND";
                    }
                }

                Bitmap cookedBmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
                pixels = null;

                // MOVED TO ROOT DIRECTORY
                File rootDir = Environment.getExternalStorageDirectory();
                File cookedDir = new File(rootDir, "COOKED");
                if (!cookedDir.exists()) cookedDir.mkdirs();
                
                String origName = original.getName();
                String numbers = origName.replaceAll("[^0-9]", "");
                if (numbers.length() > 4) numbers = numbers.substring(numbers.length() - 4);
                while (numbers.length() < 4) numbers = "0" + numbers;
                
                String newName = "CKED" + numbers + ".JPG";
                File outFile = new File(cookedDir, newName);

                FileOutputStream fos = new FileOutputStream(outFile);
                cookedBmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.flush();
                fos.close();
                cookedBmp.recycle();

                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                return "SUCCESS: " + newName;
                
            } catch (OutOfMemoryError oom) {
                return "ERR: OUT OF MEMORY";
            } catch (Throwable t) {
                return "CRASH: " + t.getMessage();
            }
        }

        @Override protected void onPostExecute(String result) {
            isBaking = false;
            if (mCameraEx != null) mCameraEx.startDirectShutter();
            tvRecipe.setText(result);
            tvRecipe.setTextColor(result.startsWith("SUCCESS") ? Color.GREEN : Color.RED);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        // Manual override still available!
        if (scanCode == ScalarInput.ISV_KEY_UP) { new BakeTask().execute(); return true; }
        if (!isBaking) {
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
            }
            syncUI();
            updateRecipeDisplay();
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
        if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");apackage com.github.ma1co.pmcademo.app;

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
import java.util.List; // <-- THE MISSING LINK IS BACK!

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    private boolean isBaking = false;
    
    private LutCooker mCooker = new LutCooker();
    private FileObserver photoObserver; // Watches for new photos!

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
        
        ViewGroup root = (ViewGroup) ((ViewGroup) this.findViewById(android.R.id.content)).getChildAt(0);
        root.setFocusable(true);
        root.requestFocus();
        
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    // THE AUTO-COOK LISTENER
    private void setupPhotoObserver() {
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        final File sonyDir = new File(dcim, "100MSDCF");
        if (!sonyDir.exists()) return;

        // Watch the directory for when a file is completely finished writing
        photoObserver = new FileObserver(sonyDir.getAbsolutePath(), FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.toUpperCase().endsWith(".JPG") && !path.startsWith("CKED")) {
                    final String fullPath = new File(sonyDir, path).getAbsolutePath();
                    
                    // Bounce back to the main UI thread to start the Bake
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isBaking && recipeIndex > 0) {
                                new BakeTask().execute(fullPath); // Pass the exact file path
                            }
                        }
                    });
                }
            }
        };
        photoObserver.startWatching();
    }

    private class BakeTask extends AsyncTask<String, Integer, String> {
        @Override protected void onPreExecute() { 
            isBaking = true;
            if (mCameraEx != null) {
                mCameraEx.stopDirectShutter(new CameraEx.DirectShutterStoppedCallback() {
                    @Override public void onShutterStopped(CameraEx cameraEx) {}
                });
            }
            String recipeName = recipeList.get(recipeIndex).split("\\.")[0].toUpperCase();
            tvRecipe.setText("PREPPING " + recipeName + "...");
            tvRecipe.setTextColor(Color.YELLOW);
        }

        @Override protected void onProgressUpdate(Integer... values) {
            String recipeName = recipeList.get(recipeIndex).split("\\.")[0].toUpperCase();
            if (values[0] == -1) {
                tvRecipe.setText("READING RECIPE DATA...");
            } else {
                tvRecipe.setText("COOKING " + recipeName + " [" + values[0] + "%]");
            }
        }

        @Override protected String doInBackground(String... params) {
            try {
                File original = null;
                
                // If the FileObserver gave us the exact path, use it! Otherwise, search (manual override)
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
                            if (f.getName().toUpperCase().endsWith(".JPG") && !f.getName().startsWith("CKED")) {
                                original = f; break;
                            }
                        }
                    }
                }
                
                if (original == null || !original.exists()) return "ERR: NO JPG";

                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = 4; // Keep at 4 for this test
                Bitmap bmp = BitmapFactory.decodeFile(original.getAbsolutePath(), opt);
                if (bmp == null) return "ERR: DECODE FAIL";

                int width = bmp.getWidth();
                int height = bmp.getHeight();
                int[] pixels = new int[width * height];
                bmp.getPixels(pixels, 0, width, 0, 0, width, height);
                bmp.recycle();
                bmp = null;

                if (recipeIndex > 0) {
                    File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
                    if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
                    
                    String selectedRecipe = recipeList.get(recipeIndex);
                    File cubeFile = new File(lutDir, selectedRecipe);
                    
                    if (cubeFile.exists()) {
                        if (!selectedRecipe.equals(mCooker.getCurrentLutName())) {
                            publishProgress(-1); 
                            if (!mCooker.loadLut(cubeFile, selectedRecipe)) return "ERR: BAD LUT FILE";
                        }
                        mCooker.applyLutToPixels(pixels, new LutCooker.ProgressCallback() {
                            public void onProgress(int percent) { publishProgress(percent); }
                        }); 
                    } else {
                        return "ERR: LUT NOT FOUND";
                    }
                }

                Bitmap cookedBmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
                pixels = null;

                // MOVED TO ROOT DIRECTORY
                File rootDir = Environment.getExternalStorageDirectory();
                File cookedDir = new File(rootDir, "COOKED");
                if (!cookedDir.exists()) cookedDir.mkdirs();
                
                String origName = original.getName();
                String numbers = origName.replaceAll("[^0-9]", "");
                if (numbers.length() > 4) numbers = numbers.substring(numbers.length() - 4);
                while (numbers.length() < 4) numbers = "0" + numbers;
                
                String newName = "CKED" + numbers + ".JPG";
                File outFile = new File(cookedDir, newName);

                FileOutputStream fos = new FileOutputStream(outFile);
                cookedBmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.flush();
                fos.close();
                cookedBmp.recycle();

                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                return "SUCCESS: " + newName;
                
            } catch (OutOfMemoryError oom) {
                return "ERR: OUT OF MEMORY";
            } catch (Throwable t) {
                return "CRASH: " + t.getMessage();
            }
        }

        @Override protected void onPostExecute(String result) {
            isBaking = false;
            if (mCameraEx != null) mCameraEx.startDirectShutter();
            tvRecipe.setText(result);
            tvRecipe.setTextColor(result.startsWith("SUCCESS") ? Color.GREEN : Color.RED);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        // Manual override still available!
        if (scanCode == ScalarInput.ISV_KEY_UP) { new BakeTask().execute(); return true; }
        if (!isBaking) {
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
            }
            syncUI();
            updateRecipeDisplay();
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
                String name = f.getName().toUpperCase();
                if (name.startsWith("._") || name.startsWith(".")) continue; 
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
        setupPhotoObserver(); // Turn on Auto-Cook
    }
    
    @Override protected void onPause() { 
        super.onPause(); 
        if (mCameraEx != null) mCameraEx.release(); 
        if (photoObserver != null) photoObserver.stopWatching(); // Turn off Auto-Cook
    }
    
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { syncUI(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}
        
        if (lutDir.exists() && lutDir.listFiles() != null) {
            for (File f : lutDir.listFiles()) {
                String name = f.getName().toUpperCase();
                if (name.startsWith("._") || name.startsWith(".")) continue; 
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
        setupPhotoObserver(); // Turn on Auto-Cook
    }
    
    @Override protected void onPause() { 
        super.onPause(); 
        if (mCameraEx != null) mCameraEx.release(); 
        if (photoObserver != null) photoObserver.stopWatching(); // Turn off Auto-Cook
    }
    
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { syncUI(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}