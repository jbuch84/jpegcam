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

    public void open(SurfaceHolder holder) {
        if (cameraEx == null) {
            try {
                cameraEx = CameraEx.open(0, null);
                camera = cameraEx.getNormalCamera();
                
                // CRITICAL FIX: Let the camera take photos natively without UI lockups
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
                        Log.e("filmOS", "Failed to backup parameters: " + e.getMessage());
                    }
                }

                setupNativeListeners();
                
                camera.setPreviewDisplay(holder);
                camera.startPreview();
                
                // Force SINGLE drive mode so the shutter doesn't rapid-fire
                try {
                    Camera.Parameters params = camera.getParameters();
                    CameraEx.ParametersModifier pm = cameraEx.createParametersModifier(params);
                    pm.setDriveMode(CameraEx.ParametersModifier.DRIVE_MODE_SINGLE);
                    camera.setParameters(params);
                } catch(Exception e) {
                    Log.e("filmOS", "Failed to set drive mode: " + e.getMessage());
                }

                if (listener != null) {
                    listener.onCameraReady();
                }
            } catch (Exception e) {
                Log.e("filmOS", "Failed to open camera: " + e.getMessage());
            }
        }
    }

    public void close() {
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
            } catch (Exception e) {
                Log.e("filmOS", "Failed to restore parameters: " + e.getMessage());
            }
        }
        
        if (cameraEx != null) {
            cameraEx.release();
            cameraEx = null;
            camera = null;
        }
    }

    // --- NEW METHOD FOR LOGCAT RECONNAISSANCE ---
    public void logLensLive() {
        if (cameraEx == null || camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            CameraEx.ParametersModifier pm = cameraEx.createParametersModifier(p);
            
            // First, ask the hardware if it even supports LensInfo right now
            Boolean supported = (Boolean) pm.getClass().getMethod("isSupportedLensInfo").invoke(pm);
            if (supported != null && supported) {
                Object lensInfo = pm.getClass().getMethod("getLensInfo").invoke(pm);
                if (lensInfo != null) {
                    String name = (String) lensInfo.getClass().getField("LensName").get(lensInfo);
                    String type = (String) lensInfo.getClass().getField("LensType").get(lensInfo);
                    String phase = (String) lensInfo.getClass().getField("PhaseShiftSensor").get(lensInfo);
                    
                    Object focalObj = lensInfo.getClass().getField("FocalLength").get(lensInfo);
                    int currentFocal = 0;
                    if (focalObj != null) {
                        currentFocal = focalObj.getClass().getField("current").getInt(focalObj);
                    }
                    
                    Log.d("filmOS_Lens", "LENS DATA -> Name: [" + name + "] Type: [" + type + "] Sensor: [" + phase + "] Focal: [" + currentFocal + "mm]");
                } else {
                    Log.d("filmOS_Lens", "LENS DATA -> getLensInfo() returned null (Lens Detached?)");
                }
            } else {
                Log.d("filmOS_Lens", "LENS DATA -> LensInfo NOT supported currently (No contacts?)");
            }
        } catch (Exception e) {
            Log.e("filmOS_Lens", "LENS DATA ERROR: " + e.getMessage());
        }
    }

    private void setupNativeListeners() {
        cameraEx.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
            @Override 
            public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) {
                if (listener != null) {
                    listener.onShutterSpeedChanged();
                }
            }
        });

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$ApertureChangeListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), 
                new Class[]{lClass},
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
        } catch (Exception e) {
            Log.e("filmOS", "Failed to set Aperture proxy: " + e.getMessage());
        }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$AutoISOSensitivityListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), 
                new Class[]{lClass},
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
        } catch (Exception e) {
            Log.e("filmOS", "Failed to set ISO proxy: " + e.getMessage());
        }

        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$FocusDriveListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), 
                new Class[]{lClass},
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
        } catch (Exception e) {
            Log.e("filmOS", "Failed to set Focus proxy: " + e.getMessage());
        }

        // --- ADD TO THE END OF setupNativeListeners() ---
        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$FocalLengthChangeListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), 
                new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override 
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onFocalLengthChanged")) {
                            Log.d("filmOS_Lens", "HARDWARE EVENT: Focal Length Zoomed/Changed to " + a[0] + "mm");
                        }
                        return null;
                    }
                }
            );
            cameraEx.getClass().getMethod("setFocalLengthChangeListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) {
            Log.e("filmOS", "Failed to set FocalLength proxy: " + e.getMessage());
        }
    }
    
}