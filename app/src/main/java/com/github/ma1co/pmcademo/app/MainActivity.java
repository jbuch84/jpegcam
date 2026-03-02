package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private CameraEx mCameraEx;
    private Camera mCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SurfaceView sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
        } catch (Exception e) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // This restores the "Emergency Exit" using the Trash Button
        if (keyCode == ScalarInput.ISV_KEY_DELETE || keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        
        // This is where we will listen for the Shutter (S2) to trigger the LUT baker
        if (keyCode == ScalarInput.ISV_KEY_S2) {
            // Future: triggerBaker();
            return false; // Let the camera still take the picture
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCameraEx.release();
            mCamera = null;
        }
    }

    public void surfaceCreated(SurfaceHolder h) {
        try { 
            if (mCamera != null) {
                mCamera.setPreviewDisplay(h); 
                mCamera.startPreview(); 
            }
        } catch (Exception e) {}
    }
    public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
    public void surfaceDestroyed(SurfaceHolder h) {}
}