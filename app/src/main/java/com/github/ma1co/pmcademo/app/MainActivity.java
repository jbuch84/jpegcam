package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    private List<Integer> supportedIsos;
    private int curIso;
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    
    enum DialMode { shutter, aperture, iso, recipe }
    private DialMode mDialMode = DialMode.shutter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);
        surfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        tvShutter = (TextView) findViewById(R.id.tvShutter);
        tvAperture = (TextView) findViewById(R.id.tvAperture);
        tvISO = (TextView) findViewById(R.id.tvISO);
        tvExposure = (TextView) findViewById(R.id.tvExposure);
        tvRecipe = (TextView) findViewById(R.id.tvRecipe);
        
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    private void scanRecipes() {
        recipeList.clear();
        recipeList.add("NONE (DEFAULT)");

        // SONY STORAGE FIX: Try multiple possible mount points for the SD Card
        String[] potentialPaths = {
            Environment.getExternalStorageDirectory().getAbsolutePath(),
            "/mnt/sdcard",
            "/storage/sdcard0",
            "/mnt/storage/sdcard1"
        };

        File lutDir = null;
        for (String path : potentialPaths) {
            File dir = new File(path, "LUTs");
            if (dir.exists() && dir.isDirectory()) {
                lutDir = dir;
                break;
            }
        }

        // If we still can't find it, try creating it in the default spot
        if (lutDir == null) {
            lutDir = new File(Environment.getExternalStorageDirectory(), "LUTs");
            lutDir.mkdirs();
        }

        File[] files = lutDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".cube") || name.endsWith(".png")) {
                    recipeList.add(f.getName());
                }
            }
        }
        
        // Debug info: If only "NONE" exists, show the path we searched
        if (recipeList.size() == 1) {
            tvRecipe.setText("NO LUTS FOUND IN /LUTs");
        } else {
            updateRecipeDisplay();
        }
    }

    private void updateRecipeDisplay() {
        String name = recipeList.get(recipeIndex);
        tvRecipe.setText("<  " + name.toUpperCase() + "  >");
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(mCamera.getParameters());
            supportedIsos = (List<Integer>) pm.getSupportedISOSensitivities();
            curIso = pm.getISOSensitivity();

            notifySonyStatus(true);
            syncUI();
        } catch (Exception e) {}
    }

    private void syncUI() {
        if (mCamera == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            
            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            if (speed.first >= speed.second) tvShutter.setText((speed.first / speed.second) + "\"");
            else tvShutter.setText(speed.first + "/" + speed.second);
            
            tvAperture.setText("f/" + (pm.getAperture() / 100.0f));
            tvISO.setText(curIso == 0 ? "ISO AUTO" : "ISO " + curIso);
            tvExposure.setText(String.format("%.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        } catch (Exception e) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) {
            notifySonyStatus(false);
            finish();
            return true;
        }
        if (scanCode == ScalarInput.ISV_KEY_DOWN) {
            cycleMode();
            return true;
        }
        if (scanCode == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private void handleInput(int delta) {
        if (mCameraEx == null) return;
        try {
            if (mDialMode == DialMode.shutter) {
                if (delta > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed();
            } else if (mDialMode == DialMode.aperture) {
                if (delta > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture();
            } else if (mDialMode == DialMode.iso) {
                int idx = supportedIsos.indexOf(curIso);
                int next = Math.max(0, Math.min(supportedIsos.size() - 1, idx + delta));
                curIso = supportedIsos.get(next);
                Camera.Parameters p = mCamera.getParameters();
                mCameraEx.createParametersModifier(p).setISOSensitivity(curIso);
                mCamera.setParameters(p);
            } else if (mDialMode == DialMode.recipe) {
                recipeIndex = (recipeIndex + delta + recipeList.size()) % recipeList.size();
                updateRecipeDisplay();
            }
            syncUI();
        } catch (Exception e) {}
    }

    private void cycleMode() {
        if (mDialMode == DialMode.shutter) setDialMode(DialMode.aperture);
        else if (mDialMode == DialMode.aperture) setDialMode(DialMode.iso);
        else if (mDialMode == DialMode.iso) setDialMode(DialMode.recipe);
        else setDialMode(DialMode.shutter);
    }

    private void setDialMode(DialMode mode) {
        mDialMode = mode;
        tvShutter.setTextColor(mode == DialMode.shutter ? Color.GREEN : Color.WHITE);
        tvAperture.setTextColor(mode == DialMode.aperture ? Color.GREEN : Color.WHITE);
        tvISO.setTextColor(mode == DialMode.iso ? Color.GREEN : Color.WHITE);
        tvRecipe.setTextColor(mode == DialMode.recipe ? Color.GREEN : Color.WHITE);
    }

    private void notifySonyStatus(boolean active) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        try { if (mCamera != null) { mCamera.setPreviewDisplay(h); mCamera.startPreview(); } } catch (Exception e) {}
    }
    @Override protected void onPause() { super.onPause(); if (mCameraEx != null) { mCameraEx.release(); mCameraEx = null; } }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}