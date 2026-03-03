package com.github.ma1co.pmcademo.app;

import com.jpgcookbook.sony.R;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.text.Html;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    
    // UI Elements
    private FrameLayout mainUIContainer;
    private ScrollView menuScrollView;
    private LinearLayout menuContainer;
    private TextView tvBottomBar, tvTopStatus; 
    private TextView[] menuItems = new TextView[7];
    
    // State & Engine
    private boolean isProcessing = false;
    private boolean isReady = false; 
    private boolean isMenuOpen = false;
    private int displayState = 0; // 0 = Standard, 1 = Clean Screen
    
    private LutEngine mEngine = new LutEngine();
    private PreloadLutTask currentPreloadTask = null; 
    private SonyFileObserver mFileObserver;
    private String sonyDCIMPath = "";
    
    private boolean isPolling = false;
    private long lastNewestFileTime = 0;
    private ArrayList<String> recipeList = new ArrayList<String>();

    // --- REAL TIME LOOKS (RTL) DATA STRUCTURE ---
    class RTLProfile {
        int lutIndex = 0;
        int opacity = 100;
        int grain = 0;
        int vignette = 0;
        int rollOff = 0;
    }
    
    private RTLProfile[] profiles = new RTLProfile[10];
    private int currentSlot = 0; // 0 to 9
    private int qualityIndex = 1; // 0=Proxy, 1=High, 2=Ultra
    private int menuSelection = 0; 

    public enum DialMode { rtl, shutter, aperture, iso, exposure }
    private DialMode mDialMode = DialMode.rtl;

    private class SonyFileObserver extends FileObserver {
        public SonyFileObserver(String path) { super(path, FileObserver.CLOSE_WRITE); }
        @Override public void onEvent(int event, final String path) {
            if (path == null || isProcessing || !isReady) return;
            if (path.toUpperCase().endsWith(".JPG") && !path.startsWith("PRCS")) {
                final String fullPath = sonyDCIMPath + "/" + path;
                runOnUiThread(new Runnable() { @Override public void run() { new ProcessTask().execute(fullPath); }});
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        FrameLayout rootLayout = new FrameLayout(this);
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        rootLayout.addView(mSurfaceView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(rootLayout);

        scanRecipes();
        for(int i=0; i<10; i++) profiles[i] = new RTLProfile();
        loadPreferences();

        buildUI(rootLayout);

        String[] possibleRoots = { Environment.getExternalStorageDirectory().getAbsolutePath(), "/mnt/sdcard", "/storage/sdcard0", "/sdcard" };
        for (String r : possibleRoots) {
            File f = new File(r + "/DCIM/100MSDCF");
            if (f.exists()) { sonyDCIMPath = f.getAbsolutePath(); break; }
        }
        if (sonyDCIMPath.isEmpty()) sonyDCIMPath = possibleRoots[0] + "/DCIM/100MSDCF";
        
        triggerLutPreload();
    }

    private void buildUI(FrameLayout rootLayout) {
        // Main HUD
        mainUIContainer = new FrameLayout(this);
        rootLayout.addView(mainUIContainer, new FrameLayout.LayoutParams(-1, -1));

        tvTopStatus = new TextView(this);
        tvTopStatus.setTextColor(Color.WHITE);
        tvTopStatus.setTextSize(20);
        tvTopStatus.setShadowLayer(3, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        topParams.setMargins(30, 30, 0, 0);
        mainUIContainer.addView(tvTopStatus, topParams);

        tvBottomBar = new TextView(this);
        tvBottomBar.setTextSize(18);
        tvBottomBar.setShadowLayer(3, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams botParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        botParams.setMargins(0, 0, 0, 30);
        mainUIContainer.addView(tvBottomBar, botParams);

        // Menu Overlay (With ScrollView to prevent cutoffs)
        menuScrollView = new ScrollView(this);
        menuScrollView.setBackgroundColor(Color.argb(220, 20, 20, 20));
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(600, 350, Gravity.CENTER);
        
        menuContainer = new LinearLayout(this);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        menuContainer.setPadding(40, 40, 40, 40);
        menuScrollView.addView(menuContainer);

        for (int i = 0; i < 7; i++) {
            menuItems[i] = new TextView(this);
            menuItems[i].setTextSize(20);
            menuItems[i].setPadding(0, 12, 0, 12);
            menuContainer.addView(menuItems[i]);
        }
        menuScrollView.setVisibility(View.GONE);
        rootLayout.addView(menuScrollView, scrollParams);
        
        updateMainHUD();
        renderMenu();
    }

    private void savePreferences() {
        SharedPreferences.Editor editor = getSharedPreferences("RTL_PREFS", MODE_PRIVATE).edit();
        editor.putInt("qualityIndex", qualityIndex);
        editor.putInt("currentSlot", currentSlot);
        for(int i=0; i<10; i++) {
            editor.putString("slot_" + i + "_lutName", recipeList.get(profiles[i].lutIndex));
            editor.putInt("slot_" + i + "_opac", profiles[i].opacity);
            editor.putInt("slot_" + i + "_grain", profiles[i].grain);
            editor.putInt("slot_" + i + "_roll", profiles[i].rollOff);
            editor.putInt("slot_" + i + "_vig", profiles[i].vignette);
        }
        editor.commit();
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences("RTL_PREFS", MODE_PRIVATE);
        qualityIndex = prefs.getInt("qualityIndex", 1);
        currentSlot = prefs.getInt("currentSlot", 0);
        for(int i=0; i<10; i++) {
            String savedLutName = prefs.getString("slot_" + i + "_lutName", "NONE");
            int foundIndex = recipeList.indexOf(savedLutName);
            profiles[i].lutIndex = (foundIndex != -1) ? foundIndex : 0; 
            profiles[i].opacity = prefs.getInt("slot_" + i + "_opac", 100);
            profiles[i].grain = prefs.getInt("slot_" + i + "_grain", 0);
            profiles[i].rollOff = prefs.getInt("slot_" + i + "_roll", 0);
            profiles[i].vignette = prefs.getInt("slot_" + i + "_vig", 0);
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        if (sc == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        
        // OPEN/CLOSE MENU
        if (sc == ScalarInput.ISV_KEY_MENU) {
            isMenuOpen = !isMenuOpen;
            if (isMenuOpen) {
                menuScrollView.setVisibility(View.VISIBLE);
                mainUIContainer.setVisibility(View.GONE);
                renderMenu();
            } else {
                menuScrollView.setVisibility(View.GONE);
                mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                savePreferences();
                triggerLutPreload();
                updateMainHUD();
            }
            return true;
        }

        // TOGGLE CLEAN SCREEN (Mapped to Center 'ENTER' button)
        if (sc == ScalarInput.ISV_KEY_ENTER) {
            if(!isMenuOpen) {
                displayState = (displayState == 0) ? 1 : 0;
                mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
            }
            return true;
        }

        if (!isProcessing) {
            if (isMenuOpen) {
                if (sc == ScalarInput.ISV_KEY_UP) { menuSelection = (menuSelection - 1 + 7) % 7; renderMenu(); return true; }
                if (sc == ScalarInput.ISV_KEY_DOWN) { menuSelection = (menuSelection + 1) % 7; renderMenu(); return true; }
                if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleMenuChange(-1); return true; }
                if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleMenuChange(1); return true; }
            } else {
                // Main Screen Navigation
                if (sc == ScalarInput.ISV_KEY_UP) { cycleMode(-1); return true; }
                if (sc == ScalarInput.ISV_KEY_DOWN) { cycleMode(1); return true; }
                if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
                if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleMenuChange(int dir) {
        RTLProfile p = profiles[currentSlot];
        int d10 = dir * 10; 
        try {
            switch(menuSelection) {
                case 0: qualityIndex = (qualityIndex + dir + 3) % 3; break;
                case 1: currentSlot = (currentSlot + dir + 10) % 10; break; 
                case 2: p.lutIndex = (p.lutIndex + dir + recipeList.size()) % recipeList.size(); break;
                case 3: p.opacity = Math.max(0, Math.min(100, p.opacity + d10)); break;
                case 4: p.grain = Math.max(0, Math.min(100, p.grain + d10)); break;
                case 5: p.rollOff = Math.max(0, Math.min(100, p.rollOff + d10)); break;
                case 6: p.vignette = Math.max(0, Math.min(100, p.vignette + d10)); break;
            }
        } catch (Exception e) {}
        renderMenu();
    }

    private void renderMenu() {
        RTLProfile p = profiles[currentSlot];
        String[] qLabels = {"PROXY (1.5MP)", "HIGH (6MP)", "ULTRA (24MP)"};
        String lutName = recipeList.get(p.lutIndex).split("\\.")[0].toUpperCase();

        menuItems[0].setText("Global Quality: < " + qLabels[qualityIndex] + " >");
        menuItems[1].setText("RTL Slot: < " + (currentSlot + 1) + " >");
        menuItems[2].setText("LUT: < " + lutName + " >");
        menuItems[3].setText("Opacity: < " + p.opacity + "% >");
        menuItems[4].setText("Grain: < " + p.grain + " >");
        menuItems[5].setText("Highlight Roll: < " + p.rollOff + " >");
        menuItems[6].setText("Vignette: < " + p.vignette + " >");

        for (int i = 0; i < 7; i++) {
            menuItems[i].setTextColor(i == menuSelection ? Color.GREEN : Color.WHITE);
        }

        // PERFECT AUTO-CENTERING SCROLL LOGIC
        menuScrollView.post(new Runnable() {
            @Override
            public void run() {
                if (menuItems[menuSelection] != null) {
                    int targetTop = menuItems[menuSelection].getTop();
                    int itemHeight = menuItems[menuSelection].getHeight();
                    int scrollHeight = menuScrollView.getHeight();
                    // Calculates exact Y position to put the highlighted text in the absolute center
                    int scrollY = targetTop - (scrollHeight / 2) + (itemHeight / 2);
                    menuScrollView.smoothScrollTo(0, Math.max(0, scrollY));
                }
            }
        });
    }

    private void cycleMode(int dir) {
        DialMode[] modes = DialMode.values();
        int ord = (mDialMode.ordinal() + dir + modes.length) % modes.length;
        mDialMode = modes[ord];
        updateMainHUD();
    }

    private void handleInput(int d) {
        if (mCameraEx == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            
            if (mDialMode == DialMode.rtl) { 
                currentSlot = (currentSlot + d + 10) % 10; 
                triggerLutPreload(); 
            }
            else if (mDialMode == DialMode.shutter) { 
                if (d > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed(); 
            }
            else if (mDialMode == DialMode.aperture) { 
                if (d > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture(); 
            }
            else if (mDialMode == DialMode.iso) {
                List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities();
                int idx = isos.indexOf(pm.getISOSensitivity());
                if (idx != -1) { pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size()-1, idx + d)))); mCamera.setParameters(p); }
            }
            else if (mDialMode == DialMode.exposure) { 
                p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), p.getExposureCompensation() + d))); 
                mCamera.setParameters(p); 
            }
            updateMainHUD();
        } catch (Exception e) {}
    }

    private void updateMainHUD() {
        if(mCamera == null) return;
        RTLProfile p = profiles[currentSlot];
        String lutName = recipeList.get(p.lutIndex).split("\\.")[0].toUpperCase();
        tvTopStatus.setText("RTL " + (currentSlot + 1) + " [" + lutName + "]\n" + (isReady ? "READY" : "LOADING..."));
        
        try {
            Camera.Parameters params = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(params);
            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            String ss = speed.first == 1 && speed.second != 1 ? speed.first + "/" + speed.second : speed.first + "\"";
            String ap = "f/" + (pm.getAperture() / 100.0f);
            String iso = pm.getISOSensitivity() == 0 ? "AUTO" : String.valueOf(pm.getISOSensitivity());
            String exp = String.format("%.1f", params.getExposureCompensation() * params.getExposureCompensationStep());

            String g = "<font color='#00FF00'>";
            String w = "<font color='#FFFFFF'>";
            
            String html = (mDialMode == DialMode.rtl ? g : w) + "[RTL " + (currentSlot+1) + "]</font>   " +
                          (mDialMode == DialMode.shutter ? g : w) + "S: " + ss + "</font>   " + 
                          (mDialMode == DialMode.aperture ? g : w) + "A: " + ap + "</font>   " + 
                          (mDialMode == DialMode.iso ? g : w) + "ISO: " + iso + "</font>   " + 
                          (mDialMode == DialMode.exposure ? g : w) + "EV: " + exp + "</font>";
                          
            tvBottomBar.setText(Html.fromHtml(html));
        } catch (Exception e) {}
    }

    private void scanRecipes() { 
        recipeList.clear(); recipeList.add("NONE"); 
        File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
        if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
        if (lutDir.exists() && lutDir.listFiles() != null) {
            for (File f : lutDir.listFiles()) if (f.length() > 10240 && f.getName().toUpperCase().contains("CUB")) recipeList.add(f.getName());
        }
    }

    private void triggerLutPreload() {
        if (currentPreloadTask != null) currentPreloadTask.cancel(true);
        if (profiles[currentSlot].lutIndex > 0) {
            currentPreloadTask = new PreloadLutTask(); 
            currentPreloadTask.execute(profiles[currentSlot].lutIndex);
        } else {
            isReady = true; 
            updateMainHUD();
        }
    }

    private void startAutoProcessPolling() {
        isPolling = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (isPolling) {
                    try {
                        Thread.sleep(300); 
                        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
                        File sonyDir = new File(dcim, "100MSDCF");
                        if (sonyDir.exists()) {
                            File[] files = sonyDir.listFiles();
                            if (files != null && files.length > 0) {
                                File newest = null; long maxModified = 0;
                                for (File f : files) {
                                    if (f.getName().toUpperCase().endsWith(".JPG") && !f.getName().startsWith("PRCS") && !f.getName().startsWith("GRADED")) {
                                        if (f.lastModified() > maxModified) { maxModified = f.lastModified(); newest = f; }
                                    }
                                }
                                if (newest != null) {
                                    if (lastNewestFileTime == 0) {
                                        lastNewestFileTime = maxModified;
                                    } else if (maxModified > lastNewestFileTime) {
                                        lastNewestFileTime = maxModified;
                                        if (!isProcessing && (isReady || profiles[currentSlot].lutIndex == 0)) {
                                            final String path = newest.getAbsolutePath();
                                            runOnUiThread(new Runnable() { @Override public void run() { if (!isProcessing) new ProcessTask().execute(path); } });
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {}
                }
            }
        }).start();
    }

    private class PreloadLutTask extends AsyncTask<Integer, Void, Boolean> {
        @Override protected void onPreExecute() {
            isReady = false; updateMainHUD();
        }
        @Override protected Boolean doInBackground(Integer... params) {
            File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
            if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
            return mEngine.loadLut(new File(lutDir, recipeList.get(params[0])), recipeList.get(params[0]));
        }
        @Override protected void onPostExecute(Boolean success) {
            if (isCancelled()) return; 
            isReady = true; updateMainHUD();
        }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        @Override protected void onPreExecute() { 
            isProcessing = true;
            tvTopStatus.setText("PROCESSING..."); tvTopStatus.setTextColor(Color.YELLOW);
        }
        @Override protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) return "ERR";

                long lastSize = -1; int timeout = 0;
                while (timeout < 100) {
                    long currentSize = original.length();
                    if (currentSize > 0 && currentSize == lastSize) break;
                    lastSize = currentSize; Thread.sleep(100); timeout++;
                }

                int scale = (qualityIndex == 0) ? 4 : (qualityIndex == 2 ? 1 : 2);
                File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
                if (!outDir.exists()) outDir.mkdirs();
                File outFile = new File(outDir, original.getName());

                RTLProfile p = profiles[currentSlot];
                if (mEngine.applyLutToJpeg(original.getAbsolutePath(), outFile.getAbsolutePath(), scale, p.opacity, p.grain, p.vignette, p.rollOff)) {
                    copyExif(original.getAbsolutePath(), outFile.getAbsolutePath());
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                    return "SAVED " + (scale==1?"24MP":(scale==2?"6MP":"1.5MP"));
                }
                return "FAILED";
            } catch (Throwable t) { return "ERR"; }
        }
        @Override protected void onPostExecute(String result) {
            isProcessing = false;
            tvTopStatus.setTextColor(Color.WHITE);
            updateMainHUD(); 
        }
    }

    private void copyExif(String sourcePath, String destPath) {
        try {
            android.media.ExifInterface sourceExif = new android.media.ExifInterface(sourcePath);
            android.media.ExifInterface destExif = new android.media.ExifInterface(destPath);
            String[] tags = {"FNumber", "ExposureTime", "ISOSpeedRatings", "FocalLength", "DateTime", "Make", "Model", "WhiteBalance", "Flash"};
            for (String tag : tags) { String value = sourceExif.getAttribute(tag); if (value != null) destExif.setAttribute(tag, value); }
            destExif.saveAttributes();
        } catch (IOException e) {}
    }

    @Override public void surfaceCreated(SurfaceHolder h) { 
        try { 
            mCameraEx = CameraEx.open(0, null); mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            CameraEx.AutoPictureReviewControl apr = new CameraEx.AutoPictureReviewControl();
            mCameraEx.setAutoPictureReviewControl(apr); apr.setPictureReviewTime(0);
            mCamera.setPreviewDisplay(h); mCamera.startPreview(); updateMainHUD();
        } catch (Exception e) {} 
    }
    @Override protected void onResume() { super.onResume(); if (mCamera != null) updateMainHUD(); startAutoProcessPolling(); }
    @Override protected void onPause() { super.onPause(); if (mCameraEx != null) mCameraEx.release(); isPolling = false; savePreferences(); }
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { updateMainHUD(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}