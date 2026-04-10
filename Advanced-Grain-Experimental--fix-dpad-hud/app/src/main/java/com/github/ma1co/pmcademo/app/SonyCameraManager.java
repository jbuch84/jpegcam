package com.github.ma1co.pmcademo.app;

import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;

import com.sony.scalar.hardware.CameraEx;

import java.util.List;

public class SonyCameraManager {
    private CameraEx cameraEx;
    private Camera camera;
    
    private String origSceneMode;
    private String origFocusMode;
    private String origWhiteBalance;
    private String origDroMode;
    private String origDroLevel;
    private String origSonyDro;
    private String origContrast;
    private String origSaturation;
    private String origSharpness;
    private String origWbShiftMode;
    private String origWbShiftLb;
    private String origWbShiftCc;

    public interface CameraEventListener {
        void onCameraReady();
        void onShutterSpeedChanged();
        void onApertureChanged();
        void onIsoChanged();
        void onFocusPositionChanged(float ratio);
        void onFocalLengthChanged(float focalLengthMm); 
        void onHardwareStateChanged(); // <-- MUST BE HERE
    }

    private CameraEventListener listener;

    public SonyCameraManager(CameraEventListener listener) {
        this.listener = listener;
    }

    public Camera getCamera() { 
        return camera; 
    }
    
    public CameraEx getCameraEx() { 
        return cameraEx; 
    }

    // --- NEW: Safe one-time check for Prime Lenses on boot ---
    public float getInitialFocalLength() {
        if (camera != null) {
            try {
                return camera.getParameters().getFocalLength();
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Could not read initial focal length.");
            }
        }
        return 0.0f; // 0.0 indicates a dumb manual lens
    }

    public void open(SurfaceHolder holder) {
        if (cameraEx == null) {
            try {
                cameraEx = CameraEx.open(0, null);
                camera = cameraEx.getNormalCamera();
                
                cameraEx.startDirectShutter();
                CameraEx.AutoPictureReviewControl apr = new CameraEx.AutoPictureReviewControl();
                cameraEx.setAutoPictureReviewControl(apr);
                apr.setPictureReviewTime(0);

                if (origSceneMode == null && camera != null) {
                    try {
                        Camera.Parameters p = camera.getParameters();
                        origSceneMode = p.getSceneMode();
                        origFocusMode = p.getFocusMode();
                        origWhiteBalance = p.getWhiteBalance();
                        origDroMode = p.get("dro-mode");
                        origDroLevel = p.get("dro-level");
                        origSonyDro = p.get("sony-dro");
                        origContrast = p.get("contrast");
                        origSaturation = p.get("saturation");
                        origSharpness = p.get("sharpness");
                        origWbShiftMode = p.get("white-balance-shift-mode");
                        origWbShiftLb = p.get("white-balance-shift-lb");
                        origWbShiftCc = p.get("white-balance-shift-cc");
                    } catch (Exception e) {
                        Log.e("JPEG.CAM", "Failed to backup parameters: " + e.getMessage());
                    }
                }

                setupNativeListeners();
                
                camera.setPreviewDisplay(holder);
                camera.startPreview();
                
                try {
                    Camera.Parameters params = camera.getParameters();
                    CameraEx.ParametersModifier pm = cameraEx.createParametersModifier(params);
                    pm.setDriveMode(CameraEx.ParametersModifier.DRIVE_MODE_SINGLE);
                    camera.setParameters(params);
                } catch(Exception e) {
                    Log.e("JPEG.CAM", "Failed to set drive mode: " + e.getMessage());
                }

                if (listener != null) {
                    listener.onCameraReady();
                }
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Failed to open camera: " + e.getMessage());
            }
        }
    }

    public void close() {
        // 1. First, gently stop any active hardware operations
        if (camera != null) {
            try {
                camera.cancelAutoFocus();
                
                // FIX: We MUST stop the live video stream before modifying final 
                // parameters or releasing the camera. Leaving the DMA pipeline open 
                // when releasing the camera causes a guaranteed kernel panic on BIONZ!
                camera.stopPreview(); 
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Failed to cancel AF or stop preview on close.");
            }
        }

        // 2. Restore the original standard Sony parameters
        if (camera != null && origSceneMode != null) {
            try {
                Camera.Parameters p = camera.getParameters();
                if (origSceneMode != null) p.setSceneMode(origSceneMode);
                if (origFocusMode != null) p.setFocusMode(origFocusMode);
                if (origWhiteBalance != null) p.setWhiteBalance(origWhiteBalance);
                if (origDroMode != null) p.set("dro-mode", origDroMode);
                if (origDroLevel != null) p.set("dro-level", origDroLevel);
                if (origSonyDro != null) p.set("sony-dro", origSonyDro);
                if (origContrast != null) p.set("contrast", origContrast);
                if (origSaturation != null) p.set("saturation", origSaturation);
                if (origSharpness != null) p.set("sharpness", origSharpness);
                if (origWbShiftMode != null) p.set("white-balance-shift-mode", origWbShiftMode);
                if (origWbShiftLb != null) p.set("white-balance-shift-lb", origWbShiftLb);
                if (origWbShiftCc != null) p.set("white-balance-shift-cc", origWbShiftCc);
                
                camera.setParameters(p);
                Log.d("JPEG.CAM", "Successfully restored standard Sony parameters.");
                
                // Give the BIONZ daemon time to digest these standard settings before calling release().
                Thread.sleep(300);
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Failed to restore parameters: " + e.getMessage());
            }
        }
        
        // 3. Safely release the hardware
        if (cameraEx != null) {
            try {
                cameraEx.release();
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Error releasing CameraEx: " + e.getMessage());
            }
            cameraEx = null;
            camera = null;
        }
    }

    private void setupNativeListeners() {
        cameraEx.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
            @Override 
            public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) {
                if (listener != null) listener.onShutterSpeedChanged();
            }
        });

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$ApertureChangeListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onApertureChange") && listener != null) {
                            listener.onApertureChanged();
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setApertureChangeListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$AutoISOSensitivityListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onChanged") && listener != null) {
                            listener.onIsoChanged();
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setAutoISOSensitivityListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$FocusDriveListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) throws Throwable {
                        if (m.getName().equals("onChanged") && a != null && a.length == 2) {
                            Object pos = a[0];
                            int cur = pos.getClass().getField("currentPosition").getInt(pos);
                            int max = pos.getClass().getField("maxPosition").getInt(pos);
                            if (max > 0 && listener != null) {
                                listener.onFocusPositionChanged((float) cur / max);
                            }
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setFocusDriveListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$FocalLengthChangeListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onFocalLengthChanged") && a.length > 0) {
                            if (listener != null) {
                                // Divide hardware's 10x value by 10 to get standard mm (e.g. 250 -> 25.0)
                                int focal10x = (Integer) a[0];
                                listener.onFocalLengthChanged(focal10x / 10.0f);
                            }
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setFocalLengthChangeListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$SettingChangedListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onChanged") && listener != null) {
                            listener.onHardwareStateChanged();
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setSettingChangedListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) { }
    }
}