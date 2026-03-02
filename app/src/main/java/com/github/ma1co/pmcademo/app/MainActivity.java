package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
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
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    private boolean isBaking = false;

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
        
        // Ensure the app has focus for the dials
        findViewById(R.id.container).setFocusable(true);
        findViewById(R.id.container).requestFocus();
        
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    private class FinalMirrorTask extends AsyncTask<Void, Void, String> {
        @Override protected void onPreExecute() { 
            isBaking = true;
            tvRecipe.setText("BAKING...");
            tvRecipe.setTextColor(Color.YELLOW);
        }

        @Override protected String doInBackground(Void... voids) {
            try {
                // Use the absolute internal mount point
                File dir = new File("/storage/sdcard0/DCIM/100MSDCF");
                if (!dir.exists()) dir = new File("/sdcard/DCIM/100MSDCF");
                
                File[] files = dir.listFiles();
                if (files == null || files.length == 0) return "ERR: NO FILES";

                Arrays.sort(files, new Comparator<File>() {
                    public int compare(File f1, File f2) {
                        return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
                    }
                });

                File original = null;
                for (File f : files) {
                    if (f.getName().toUpperCase().endsWith(".JPG") && !f.getName().startsWith("MIRROR_")) {
                        original = f; break;
                    }
                }
                if (original == null) return "ERR: NO JPG";

                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = 4;
                Bitmap bmp = BitmapFactory.decodeFile(original.getAbsolutePath(), opt);
                if (bmp == null) return "ERR: BIONZ LOCK";

                // FORCE FOLDER CREATION
                File outDir = new File(dir.getParentFile(), "COOKED");
                if (!outDir.exists()) outDir.mkdirs();
                
                File outFile = new File(outDir, "MIRROR_" + original.getName());

                FileOutputStream fos = new FileOutputStream(outFile);
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.flush();
                fos.close();
                bmp.recycle();

                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                return "SUCCESS!";
            } catch (Exception e) {
                return "ERR: " + e.getMessage();
            }
        }

        @Override protected void onPostExecute(String result) {
            isBaking = false;
            tvRecipe.setText(result);
            tvRecipe.setTextColor(result.equals("SUCCESS!") ? Color.GREEN : Color.RED);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        
        // Always allow exit
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        
        // Manual Bake
        if (scanCode == ScalarInput.ISV_KEY_UP) { 
            new FinalMirrorTask().execute(); 
            return true; 
        }

        if (isBaking) return true;

        // Dial controls
        if (scanCode == ScalarInput.ISV_KEY_DOWN) { cycleMode(); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
        
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
            int iso = pm.getISOSensitivity();
            tvISO.setText(iso == 0 ? "ISO AUTO" : "ISO " + iso);
            tvExposure.setText(String.format("%.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        } catch (Exception e) {}
    }

    private void cycleMode() {
        DialMode[] modes = DialMode.values();
        setDialMode(modes[(mDialMode.ordinal() + 1) % modes.length]);
    }

    private void setDialMode(DialMode mode) {
        mDialMode = mode;
        tvShutter.setTextColor(mode == DialMode.shutter ? Color.GREEN : Color.WHITE);
        tvAperture.setTextColor(mode == DialMode.aperture ? Color.GREEN : Color.WHITE);
        tvISO.setTextColor(mode == DialMode.iso ? Color.GREEN : Color.WHITE);
        tvExposure.setTextColor(mode == DialMode.exposure ? Color.GREEN : Color.WHITE);
        updateRecipeDisplay();
    }

    private void scanRecipes() {
        recipeList.clear(); recipeList.add("NONE");
        File lutDir = new File("/storage/sdcard0/LUTS");
        if (!lutDir.exists()) lutDir = new File("/sdcard/LUTS");
        if (lutDir.exists() && lutDir.listFiles() != null) {
            for (File f : lutDir.listFiles()) if (f.getName().toUpperCase().contains("CUB")) recipeList.add(f.getName());
        }
        updateRecipeDisplay();
    }

    private void updateRecipeDisplay() {
        String name = recipeList.get(recipeIndex);
        tvRecipe.setText("< " + name.split("\\.")[0].toUpperCase() + " >");
        tvRecipe.setTextColor(mDialMode == DialMode.recipe ? Color.GREEN : Color.WHITE);
    }

    @Override public void surfaceCreated(SurfaceHolder h) { 
        try { 
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            
            m_autoReviewControl = new CameraEx.AutoPictureReviewControl();
            mCameraEx.setAutoPictureReviewControl(m_autoReviewControl);
            m_autoReviewControl.setPictureReviewTime(0);

            mCamera.setPreviewDisplay(h);
            mCamera.startPreview();
            syncUI();
        } catch (Exception e) {} 
    }
    @Override protected void onResume() { super.onResume(); if (mCamera != null) syncUI(); }
    @Override protected void onPause() { super.onPause(); if (mCameraEx != null) mCameraEx.release(); }
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { syncUI(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}