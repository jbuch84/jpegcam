package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
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
import java.util.HashSet;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private int m_pictureReviewTime;
    
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    
    private final String SONY_PATH = "/sdcard/DCIM/100MSDCF";
    private HashSet<String> knownFiles = new HashSet<String>();
    private Handler m_handler = new Handler();
    private boolean isBaking = false;

    public enum DialMode { shutter, aperture, iso, exposure, recipe }
    private DialMode mDialMode = DialMode.shutter;
    private List<Integer> supportedIsos;
    private int curIso;
    private int curExpComp;
    private float expStep;

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
        
        initializeFileLibrary();
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    private void initializeFileLibrary() {
        File dir = new File(SONY_PATH);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) knownFiles.add(f.getName());
        }
        startHunterLoop();
    }

    private void startHunterLoop() {
        m_handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isBaking && recipeIndex > 0) {
                    File dir = new File(SONY_PATH);
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName();
                            if (name.toUpperCase().endsWith(".JPG") && !name.startsWith("COOKED_") && !knownFiles.contains(name)) {
                                knownFiles.add(name);
                                new BakeTask(name).execute();
                                break; 
                            }
                        }
                    }
                }
                m_handler.postDelayed(this, 2000); // 2 sec poll for total stability
            }
        }, 2000);
    }

    private class CubeLUT {
        int size = 0;
        float[] data;
        CubeLUT(File file) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(file));
                String line; int idx = 0;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("LUT_3D_SIZE")) {
                        size = Integer.parseInt(line.split("\\s+")[1]);
                        data = new float[size * size * size * 3];
                    } else if (line.length() > 0 && Character.isDigit(line.charAt(0))) {
                        String[] p = line.split("\\s+");
                        if (p.length >= 3 && data != null) {
                            data[idx++] = Float.parseFloat(p[0]);
                            data[idx++] = Float.parseFloat(p[1]);
                            data[idx++] = Float.parseFloat(p[2]);
                        }
                    }
                }
                br.close();
            } catch (Exception e) {}
        }
        int mapColor(int color) {
            if (size == 0 || data == null) return color;
            // The percentage-based mapping works regardless of bit-depth!
            float r = (Color.red(color) / 255.0f) * (size - 1);
            float g = (Color.green(color) / 255.0f) * (size - 1);
            float b = (Color.blue(color) / 255.0f) * (size - 1);
            int offset = ((int)r + size * ((int)g + size * (int)b)) * 3;
            if (offset >= data.length - 3) return color;
            return Color.rgb((int)(data[offset]*255), (int)(data[offset+1]*255), (int)(data[offset+2]*255));
        }
    }

    private class BakeTask extends AsyncTask<Void, Void, Boolean> {
        String fileName;
        BakeTask(String name) { this.fileName = name; }

        @Override protected void onPreExecute() { 
            isBaking = true;
            tvRecipe.setText("BAKING (6MP PROXY)...");
            tvRecipe.setTextColor(Color.RED);
            // CRITICAL: Fully release hardware to give Android all the RAM
            if (mCameraEx != null) {
                m_autoReviewControl.setPictureReviewTime(m_pictureReviewTime);
                mCameraEx.release();
                mCameraEx = null;
                mCamera = null;
            }
            mSurfaceView.setVisibility(View.INVISIBLE);
            System.gc(); 
        }

        @Override protected Boolean doInBackground(Void... voids) {
            try {
                Thread.sleep(1000); // Wait for Sony's own save to finish
                File lutFile = new File("/sdcard/LUTS/" + recipeList.get(recipeIndex));
                CubeLUT lut = new CubeLUT(lutFile);
                File original = new File(SONY_PATH, fileName);

                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = 2; // 6MP to fit in small RAM windows
                opt.inPreferredConfig = Bitmap.Config.RGB_565;
                opt.inMutable = true;
                
                Bitmap bmp = BitmapFactory.decodeFile(original.getAbsolutePath(), opt);
                if (bmp != null) {
                    int width = bmp.getWidth();
                    int height = bmp.getHeight();
                    int[] row = new int[width]; 
                    for (int y = 0; y < height; y++) {
                        bmp.getPixels(row, 0, width, 0, y, width, 1);
                        for (int x = 0; x < width; x++) { row[x] = lut.mapColor(row[x]); }
                        bmp.setPixels(row, 0, width, 0, y, width, 1);
                    }
                    File cooked = new File(SONY_PATH, "COOKED_" + fileName);
                    FileOutputStream fos = new FileOutputStream(cooked);
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                    fos.close();
                    bmp.recycle();
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(cooked)));
                    return true;
                }
            } catch (Exception e) {}
            return false;
        }

        @Override protected void onPostExecute(Boolean success) {
            isBaking = false;
            mSurfaceView.setVisibility(View.VISIBLE);
            // Re-open hardware after processing is done
            reopenCamera();
            updateRecipeDisplay();
            setDialMode(mDialMode);
        }
    }

    private void reopenCamera() {
        try {
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            m_autoReviewControl = new CameraEx.AutoPictureReviewControl();
            mCameraEx.setAutoPictureReviewControl(m_autoReviewControl);
            m_autoReviewControl.setPictureReviewTime(0);
            mCameraEx.setShutterSpeedChangeListener(this);
            mCamera.setPreviewDisplay(mSurfaceView.getHolder());
            mCamera.startPreview();
            syncUI();
        } catch (Exception e) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        reopenCamera();
        sendSonyBroadcast(true); 
    }

    private void syncUI() {
        if (mCamera == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            if (speed.first == 1 && speed.second != 1) tvShutter.setText(speed.first + "/" + speed.second);
            else tvShutter.setText(speed.first + "\"");
            tvAperture.setText("f/" + (pm.getAperture() / 100.0f));
            tvISO.setText(curIso == 0 ? "ISO AUTO" : "ISO " + curIso);
            tvExposure.setText(String.format("%.1f", curExpComp * expStep));
        } catch (Exception e) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { exitApp(); return true; }
        if (scanCode == ScalarInput.ISV_KEY_DOWN) { cycleMode(); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private void handleInput(int delta) {
        if (mCameraEx == null || isBaking) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            if (mDialMode == DialMode.shutter) {
                if (delta > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed();
            } else if (mDialMode == DialMode.aperture) {
                if (delta > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture();
            } else if (mDialMode == DialMode.iso) {
                int idx = supportedIsos.indexOf(curIso);
                int next = Math.max(0, Math.min(supportedIsos.size() - 1, idx + delta));
                curIso = supportedIsos.get(next);
                CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
                pm.setISOSensitivity(curIso);
                mCamera.setParameters(p);
            } else if (mDialMode == DialMode.exposure) {
                curExpComp = Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), curExpComp + delta));
                p.setExposureCompensation(curExpComp);
                mCamera.setParameters(p);
            } else if (mDialMode == DialMode.recipe) {
                recipeIndex = (recipeIndex + delta + recipeList.size()) % recipeList.size();
                updateRecipeDisplay();
            }
            syncUI();
        } catch (Exception e) {}
    }

    private void cycleMode() {
        if (isBaking) return;
        DialMode[] modes = DialMode.values();
        int next = (mDialMode.ordinal() + 1) % modes.length;
        setDialMode(modes[next]);
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
        recipeList.clear();
        recipeList.add("NONE (DEFAULT)");
        File lutDir = new File("/sdcard/LUTS");
        if (lutDir.exists()) {
            File[] files = lutDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.getName().startsWith("_") && f.getName().toUpperCase().contains("CUB")) recipeList.add(f.getName());
                }
            }
        }
        updateRecipeDisplay();
    }

    private void updateRecipeDisplay() {
        String name = recipeList.get(recipeIndex);
        String display = name.split("\\.")[0].toUpperCase();
        tvRecipe.setText("<  " + display + "  >");
        tvRecipe.setTextColor(mDialMode == DialMode.recipe ? Color.GREEN : Color.WHITE);
    }

    private void sendSonyBroadcast(boolean active) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    private void exitApp() {
        m_handler.removeCallbacksAndMessages(null);
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("class_name", getClass().getName());
        intent.putExtra("pullingback_key", new String[] {});
        intent.putExtra("resume_key", new String[] {});
        sendBroadcast(intent);
        finish();
    }

    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx camera) { syncUI(); }
    @Override public void surfaceCreated(SurfaceHolder h) { try { if (mCamera != null) { mCamera.setPreviewDisplay(h); mCamera.startPreview(); syncUI(); } } catch (Exception e) {} }
    @Override protected void onPause() { super.onPause(); m_handler.removeCallbacksAndMessages(null); if (mCameraEx != null) { m_autoReviewControl.setPictureReviewTime(m_pictureReviewTime); mCameraEx.release(); mCameraEx = null; } }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}