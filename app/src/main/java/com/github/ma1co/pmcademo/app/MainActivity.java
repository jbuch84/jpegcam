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
        tvRecipe = (TextView) findViewById(R.id.tvRecipe); 
        
        setDialMode(DialMode.shutter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCameraEx = CameraEx.open(0, null);
        mCameraEx.setShutterListener(this);
        mCameraEx.setShutterSpeedChangeListener(this);
        mCameraEx.startDirectShutter();
        
        // Auto Review for 2 seconds (Issue 6 Fix)
        CameraEx.AutoPictureReviewControl arc = new CameraEx.AutoPictureReviewControl();
        arc.setPictureReviewTime(2); 
        mCameraEx.setAutoPictureReviewControl(arc);

        syncParams();
        sendSonyBroadcast(true);
    }

    private void syncParams() {
        Camera.Parameters p = mCameraEx.getNormalCamera().getParameters();
        CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
        
        supportedIsos = (List<Integer>) pm.getSupportedISOSensitivities();
        curIso = pm.getISOSensitivity();
        
        Pair<Integer, Integer> speed = pm.getShutterSpeed();
        tvShutter.setText(formatShutter(speed.first, speed.second));
        tvAperture.setText("f/" + (float)pm.getAperture() / 100.0f);
        tvISO.setText("ISO " + (curIso == 0 ? "AUTO" : curIso));
        tvExposure.setText(String.format("%.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
    }

    // Integrated helper to fix Shutter display (Issue 1 Fix)
    private String formatShutter(int n, int d) {
        if (n >= d) return (n / d) + "\"";
        return n + "/" + d;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        
        if (scanCode == ScalarInput.ISV_KEY_DELETE) {
            sendSonyBroadcast(false);
            finish();
            return true;
        }

        if (scanCode == ScalarInput.ISV_KEY_DOWN) {
            cycleMode();
            return true;
        }

        if (scanCode == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleDial(1); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleDial(-1); return true; }

        return super.onKeyDown(keyCode, event);
    }

    private void handleDial(int delta) {
        if (mDialMode == DialMode.shutter) {
            if (delta > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed();
        } else if (mDialMode == DialMode.aperture) {
            if (delta > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture();
        } else if (mDialMode == DialMode.iso) {
            int idx = supportedIsos.indexOf(curIso);
            int next = Math.max(0, Math.min(supportedIsos.size() - 1, idx + delta));
            curIso = supportedIsos.get(next);
            Camera.Parameters p = mCameraEx.getNormalCamera().getParameters();
            mCameraEx.createParametersModifier(p).setISOSensitivity(curIso);
            mCameraEx.getNormalCamera().setParameters(p);
            tvISO.setText("ISO " + (curIso == 0 ? "AUTO" : curIso));
        }
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

    private void sendSonyBroadcast(boolean active) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    @Override
    public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx camera) {
        tvShutter.setText(formatShutter(info.currentShutterSpeed_n, info.currentShutterSpeed_d));
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