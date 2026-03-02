package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterListener, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private SurfaceHolder mSurfaceHolder;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    
    private int curIso;
    private List<Integer> supportedIsos;
    private float expStep;
    
    enum DialMode { shutter, aperture, iso, recipe }
    private DialMode mDialMode = DialMode.shutter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mSurfaceHolder = ((SurfaceView) findViewById(R.id.surfaceView)).getHolder();
        mSurfaceHolder.addCallback(this);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        tvShutter = (TextView) findViewById(R.id.tvShutter);
        tvAperture = (TextView) findViewById(R.id.tvAperture);
        tvISO = (TextView) findViewById(R.id.tvISO);
        tvExposure = (TextView) findViewById(R.id.tvExposure);
        tvRecipe = (TextView) findViewById(R.id.tvRecipe); // New LUT title
        
        setDialMode(DialMode.shutter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCameraEx = CameraEx.open(0, null);
        mCameraEx.setShutterListener(this);
        mCameraEx.setShutterSpeedChangeListener(this);
        
        // ISSUE 2 & 6 FIX: Setup Aperture Listener and Restore Review Time
        mCameraEx.setApertureChangeListener(new CameraEx.ApertureChangeListener() {
            @Override
            public void onApertureChange(CameraEx.ApertureInfo info, CameraEx camera) {
                tvAperture.setText("f/" + (float)info.currentAperture / 100.0f);
            }
        });

        // Enable 2-second image review after taking a photo
        CameraEx.AutoPictureReviewControl arc = new CameraEx.AutoPictureReviewControl();
        arc.setPictureReviewTime(2); // Seconds
        mCameraEx.setAutoPictureReviewControl(arc);

        mCameraEx.startDirectShutter();
        
        syncCameraParams();
        sendSonyBroadcast(true);
    }

    private void syncCameraParams() {
        Camera.Parameters p = mCameraEx.getNormalCamera().getParameters();
        CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
        
        // ISSUE 3 FIX: Correct ISO initialization
        supportedIsos = (List<Integer>) pm.getSupportedISOSensitivities();
        curIso = pm.getISOSensitivity();
        tvISO.setText("ISO " + (curIso == 0 ? "AUTO" : curIso));
        
        // ISSUE 4 FIX: Exposure Compensation
        expStep = p.getExposureCompensationStep();
        tvExposure.setText(String.format("%.1f", p.getExposureCompensation() * expStep));
        
        Pair<Integer, Integer> speed = pm.getShutterSpeed();
        tvShutter.setText(CameraUtil.formatShutterSpeed(speed.first, speed.second));
        tvAperture.setText("f/" + (float)pm.getAperture() / 100.0f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { sendSonyBroadcast(false); finish(); return true; }

        // Mode Switching Logic
        if (scanCode == ScalarInput.ISV_KEY_DOWN) {
            cycleDialMode();
            return true;
        }

        // ISSUE 1 & 2 FIX: Wheel Control handling
        if (scanCode == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }

        return super.onKeyDown(keyCode, event);
    }

    private void handleInput(int delta) {
        switch(mDialMode) {
            case shutter: 
                if (delta > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed();
                break;
            case aperture:
                if (delta > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture();
                break;
            case iso:
                adjustIso(delta);
                break;
            case recipe:
                // future: switchLut(delta);
                break;
        }
    }

    private void adjustIso(int delta) {
        int idx = supportedIsos.indexOf(curIso);
        int next = Math.max(0, Math.min(supportedIsos.size() - 1, idx + delta));
        curIso = supportedIsos.get(next);
        Camera.Parameters p = mCameraEx.getNormalCamera().getParameters();
        mCameraEx.createParametersModifier(p).setISOSensitivity(curIso);
        mCameraEx.getNormalCamera().setParameters(p);
        tvISO.setText("ISO " + (curIso == 0 ? "AUTO" : curIso));
    }

    private void cycleDialMode() {
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

    private void sendSonyBroadcast(boolean active) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    @Override
    public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx camera) {
        tvShutter.setText(CameraUtil.formatShutterSpeed(info.currentShutterSpeed_n, info.currentShutterSpeed_d));
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        try { mCameraEx.getNormalCamera().setPreviewDisplay(h); mCameraEx.getNormalCamera().startPreview(); } catch (Exception e) {}
    }

    @Override public void onShutter(int i, CameraEx c) {}
    @Override protected void onPause() { super.onPause(); if (mCameraEx != null) { mCameraEx.release(); mCameraEx = null; } }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}