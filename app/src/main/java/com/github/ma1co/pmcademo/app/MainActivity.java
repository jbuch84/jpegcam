package com.github.ma1co.pmcademo.app;

import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sony.scalar.hardware.CameraEx;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity implements SurfaceHolder.Callback {

    private SurfaceHolder mSurfaceHolder;
    private CameraEx mCameraEx;
    private Camera mNormalCamera;
    
    private TextView mRecipeText;
    private TextView mStatusText;
    
    private boolean mIsTakingPicture = false;
    private List<String> availableLuts = new ArrayList<String>();
    private String selectedLutPath = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);

        SurfaceView surfaceView = new SurfaceView(this);
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mSurfaceHolder.addCallback(this);
        layout.addView(surfaceView);

        mStatusText = new TextView(this);
        mStatusText.setTextColor(Color.WHITE);
        mStatusText.setTextSize(24);
        mStatusText.setVisibility(View.GONE);
        FrameLayout.LayoutParams sParams = new FrameLayout.LayoutParams(-2, -2);
        sParams.gravity = 17; // Center
        layout.addView(mStatusText, sParams);

        mRecipeText = new TextView(this);
        mRecipeText.setTextColor(Color.parseColor("#ff5000"));
        mRecipeText.setTextSize(18);
        mRecipeText.setPadding(20, 20, 20, 20);
        FrameLayout.LayoutParams rParams = new FrameLayout.LayoutParams(-2, -2);
        rParams.gravity = 81; // Bottom Center
        layout.addView(mRecipeText, rParams);

        setContentView(layout);

        // Wait 500ms to let the camera sensor initialize before reading SD
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                loadAvailableLUTs();
            }
        }, 500);
    }

    private void loadAvailableLUTs() {
        File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTs");
        if (!lutDir.exists()) lutDir.mkdirs();

        File[] files = lutDir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].getName().toLowerCase().endsWith(".cube")) {
                    availableLuts.add(files[i].getAbsolutePath());
                }
            }
        }
        updateRecipeUI();
    }

    private void updateRecipeUI() {
        if (availableLuts.isEmpty()) {
            mRecipeText.setText("Recipe: Standard (No LUTs found)");
        } else {
            selectedLutPath = availableLuts.get(0);
            mRecipeText.setText("Recipe: " + new File(selectedLutPath).getName().replace(".cube", ""));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mCameraEx = CameraEx.open(0, null);
            mNormalCamera = mCameraEx.getNormalCamera();
            Camera.Parameters p = mNormalCamera.getParameters();
            p.setFocusMode("auto");
            mNormalCamera.setParameters(p);
        } catch (Exception e) {
            Toast.makeText(this, "Sensor Error", 1).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mNormalCamera != null) {
            mNormalCamera.stopPreview();
            mCameraEx.release();
            mNormalCamera = null;
        }
    }

    @Override
    protected boolean onShutterKeyDown() {
        if (mNormalCamera != null && !mIsTakingPicture) {
            mIsTakingPicture = true;
            mStatusText.setText("Cooking Image...");
            mStatusText.setVisibility(View.VISIBLE);
            mRecipeText.setVisibility(View.GONE);
            mNormalCamera.takePicture(null, null, mPictureCallback);
        }
        return true;
    }

    @Override
    protected boolean onShutterKeyUp() { return true; }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Raw ScanCode 644 is Shutter Half-Press
        // Raw keyCode 80 is Focus
        if (event.getScanCode() == 644 || keyCode == 80) {
            if (mNormalCamera != null && !mIsTakingPicture) mNormalCamera.autoFocus(null);
            return true;
        }

        // Raw keyCode 22 is Right, 21 is Left
        if (keyCode == 22 || keyCode == 21) {
            if (!availableLuts.isEmpty()) {
                int idx = availableLuts.indexOf(selectedLutPath);
                idx = (keyCode == 22) ? (idx + 1) % availableLuts.size() : (idx - 1 + availableLuts.size()) % availableLuts.size();
                selectedLutPath = availableLuts.get(idx);
                mRecipeText.setText("Recipe: " + new File(selectedLutPath).getName().replace(".cube", ""));
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private Camera.PictureCallback mPictureCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            File dir = new File(Environment.getExternalStorageDirectory(), "DCIM/COOKBOOK");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "DSC_" + System.currentTimeMillis() + ".JPG");
            try {
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(data);
                fos.close();
            } catch (Exception e) {}
            mStatusText.setVisibility(View.GONE);
            mRecipeText.setVisibility(View.VISIBLE);
            camera.startPreview();
            mIsTakingPicture = false;
        }
    };

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try { if (mNormalCamera != null) { mNormalCamera.setPreviewDisplay(holder); mNormalCamera.startPreview(); } } catch (IOException e) {}
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}