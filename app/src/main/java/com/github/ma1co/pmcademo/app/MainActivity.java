package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.media.ExifInterface;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.Context;

import java.util.List;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.*;
import java.util.ArrayList;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private boolean hasSurface = false; 
    
    private FrameLayout mainUIContainer;
    private LinearLayout menuContainer; 
    private LinearLayout[] menuRows = new LinearLayout[11];
    private TextView[] menuLabels = new TextView[11];
    private TextView[] menuValues = new TextView[11];
    private TextView tvBottomBar, tvTopStatus; 
    
    private FrameLayout playbackContainer;
    private ImageView playbackImageView;
    private TextView tvPlaybackInfo;
    private List<File> playbackFiles = new ArrayList<File>();
    private int playbackIndex = 0;
    private Bitmap currentPlaybackBitmap = null;
    private boolean isPlaybackMode = false;
    
    private boolean isProcessing = false;
    private boolean isReady = false; 
    private boolean isMenuOpen = false;
    private int displayState = 0; 
    
    private LutEngine mEngine; 
    private PreloadLutTask currentPreloadTask = null; 
    private SonyFileObserver mFileObserver;
    private String sonyDCIMPath = "";
    
    private boolean isPolling = false;
    private long lastNewestFileTime = 0;
    
    private ProReticleView afOverlay;

    private ArrayList<String> recipePaths = new ArrayList<String>();
    private ArrayList<String> recipeNames = new ArrayList<String>();

    private final String[] intensityLabels = {"OFF", "LOW", "LOW+", "MID", "MID+", "HIGH"};
    private final String[] grainSizeLabels = {"SM", "MED", "LG"};

    class RTLProfile {
        int lutIndex = 0;
        int opacity = 100; 
        int grain = 0;    
        int grainSize = 1; 
        int rollOff = 0;  
        int vignette = 0; 
        String whiteBalance = "AUTO";
        int wbShift = 0;
        String dro = "OFF";
    }
    
    private RTLProfile[] profiles = new RTLProfile[10];
    private int currentSlot = 0; 
    private int qualityIndex = 1; 
    private int menuSelection = 0; 

    public static final int DIAL_MODE_RTL = 0;
    public static final int DIAL_MODE_SHUTTER = 1;
    public static final int DIAL_MODE_APERTURE = 2;
    public static final int DIAL_MODE_ISO = 3;
    public static final int DIAL_MODE_EXPOSURE = 4;
    public static final int DIAL_MODE_REVIEW = 5;
    private int mDialMode = DIAL_MODE_RTL;

    private class SonyFileObserver extends FileObserver {
        public SonyFileObserver(String path) {
            super(path, FileObserver.CLOSE_WRITE);
        }

        @Override
        public void onEvent(int event, final String path) {
            if (path == null || isProcessing || !isReady) return;
            if (path.toUpperCase().endsWith(".JPG") && !path.startsWith("PRCS")) {
                final String fullPath = sonyDCIMPath + "/" + path;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        new ProcessTask().execute(fullPath);
                    }
                });
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            mEngine = new LutEngine();
        } catch (Throwable t) {
            Log.e("COOKBOOK", "Native library failed to load: " + t.getMessage());
        }

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
            if (f.exists()) {
                sonyDCIMPath = f.getAbsolutePath();
                break;
            }
        }
        if (sonyDCIMPath.isEmpty()) sonyDCIMPath = possibleRoots[0] + "/DCIM/100MSDCF";
        
        triggerLutPreload();
    }

    private void buildUI(FrameLayout rootLayout) {
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

        afOverlay = new ProReticleView(this);
        mainUIContainer.addView(afOverlay, new FrameLayout.LayoutParams(-1, -1));

        menuContainer = new LinearLayout(this);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        menuContainer.setBackgroundColor(Color.argb(245, 10, 10, 10)); 
        menuContainer.setPadding(30, 30, 30, 30);
        
        for (int i = 0; i < 11; i++) {
            menuRows[i] = new LinearLayout(this);
            menuRows[i].setOrientation(LinearLayout.HORIZONTAL);
            menuRows[i].setGravity(Gravity.CENTER_VERTICAL);
            
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
            menuContainer.addView(menuRows[i], rowParams);
            
            menuLabels[i] = new TextView(this);
            menuLabels[i].setTextSize(22);
            menuLabels[i].setTypeface(Typeface.DEFAULT_BOLD);
            
            menuValues[i] = new TextView(this);
            menuValues[i].setTextSize(22);
            menuValues[i].setGravity(Gravity.RIGHT);
            
            LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(0, -2, 1.0f);
            LinearLayout.LayoutParams lpVal = new LinearLayout.LayoutParams(-2, -2);
            
            menuRows[i].addView(menuLabels[i], lpLabel);
            menuRows[i].addView(menuValues[i], lpVal);
        }
        
        menuContainer.setVisibility(View.GONE);
        rootLayout.addView(menuContainer, new FrameLayout.LayoutParams(-1, -1));

        playbackContainer = new FrameLayout(this);
        playbackContainer.setBackgroundColor(Color.BLACK);
        playbackContainer.setVisibility(View.GONE);
        
        playbackImageView = new ImageView(this);
        playbackImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        playbackContainer.addView(playbackImageView, new FrameLayout.LayoutParams(-1, -1));
        
        tvPlaybackInfo = new TextView(this);
        tvPlaybackInfo.setTextColor(Color.WHITE);
        tvPlaybackInfo.setTextSize(18);
        tvPlaybackInfo.setShadowLayer(3, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams pbInfoParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        pbInfoParams.setMargins(0, 30, 30, 0);
        playbackContainer.addView(tvPlaybackInfo, pbInfoParams);
        
        rootLayout.addView(playbackContainer, new FrameLayout.LayoutParams(-1, -1));

        updateMainHUD();
        renderMenu();
    }

    private void refreshPlaybackFiles() {
        playbackFiles.clear();
        File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
        if (outDir.exists() && outDir.listFiles() != null) {
            for (File f : outDir.listFiles()) {
                if (f.getName().toUpperCase().endsWith(".JPG")) playbackFiles.add(f);
            }
        }
        java.util.Collections.sort(playbackFiles, new java.util.Comparator<File>() {
            public int compare(File f1, File f2) {
                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
            }
        });
    }

    private void showPlaybackImage(int index) {
        if (playbackFiles.isEmpty()) {
            tvPlaybackInfo.setText("NO GRADED PHOTOS");
            return;
        }
        
        if (index < 0) index = 0;
        if (index >= playbackFiles.size()) index = playbackFiles.size() - 1;
        playbackIndex = index;
        File imgFile = playbackFiles.get(playbackIndex);
        
        if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) {
            playbackImageView.setImageBitmap(null);
            currentPlaybackBitmap.recycle();
            currentPlaybackBitmap = null;
        }
        
        try {
            ExifInterface exif = new ExifInterface(imgFile.getAbsolutePath());
            String fnum = exif.getAttribute("FNumber");
            String speed = exif.getAttribute("ExposureTime");
            String iso = exif.getAttribute("ISOSpeedRatings");
            
            String speedStr = "--s";
            if (speed != null) {
                try {
                    double s = Double.parseDouble(speed);
                    if (s < 1.0) speedStr = "1/" + Math.round(1.0 / s) + "s";
                    else speedStr = Math.round(s) + "s";
                } catch (Exception e) {}
            }
            
            String apStr = fnum != null ? "f/" + fnum : "f/--";
            String isoStr = iso != null ? "ISO " + iso : "ISO --";

            String metaText = (playbackIndex + 1) + " / " + playbackFiles.size() + "\n" 
                              + imgFile.getName() + "\n" 
                              + apStr + " | " + speedStr + " | " + isoStr;
            tvPlaybackInfo.setText(metaText);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true; 
            BitmapFactory.decodeFile(imgFile.getAbsolutePath(), options);
            
            int scale = 1; 
            while ((options.outWidth / scale) > 1200 || (options.outHeight / scale) > 1200) { 
                scale *= 2; 
            }
            
            options.inJustDecodeBounds = false; 
            options.inSampleSize = scale;
            options.inPreferQualityOverSpeed = true; 
            
            Bitmap rawBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath(), options);
            
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotationAngle = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotationAngle = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotationAngle = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotationAngle = 270;
            
            Matrix matrix = new Matrix(); 
            if (rotationAngle != 0) matrix.postRotate(rotationAngle);
            matrix.postScale(0.8888f, 1.0f); 

            currentPlaybackBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.getWidth(), rawBitmap.getHeight(), matrix, true); 
            if (currentPlaybackBitmap != rawBitmap) rawBitmap.recycle();
            
            playbackImageView.setImageBitmap(currentPlaybackBitmap);
            
        } catch (Exception e) {
            tvPlaybackInfo.setText("DECODE ERROR");
        }
    }

    private void exitPlayback() {
        playbackContainer.setVisibility(View.GONE);
        mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
        isPlaybackMode = false;
        if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) {
            playbackImageView.setImageBitmap(null);
            currentPlaybackBitmap.recycle();
            currentPlaybackBitmap = null;
        }
    }

    private File getLutDir() {
        File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
        if (!lutDir.exists()) lutDir = new File("/storage/sdcard0/LUTS");
        if (!lutDir.exists()) lutDir = new File("/mnt/sdcard/LUTS");
        return lutDir;
    }

    private void savePreferences() {
        SharedPreferences.Editor editor = getSharedPreferences("RTL_PREFS", MODE_PRIVATE).edit();
        editor.putBoolean("has_saved", true);
        editor.putInt("qualityIndex", qualityIndex);
        editor.putInt("currentSlot", currentSlot);
        for(int i=0; i<10; i++) {
            editor.putString("slot_" + i + "_lutPath", recipePaths.get(profiles[i].lutIndex));
            editor.putInt("slot_" + i + "_opac", profiles[i].opacity);
            editor.putInt("slot_" + i + "_grain", profiles[i].grain);
            editor.putInt("slot_" + i + "_gSize", profiles[i].grainSize);
            editor.putInt("slot_" + i + "_roll", profiles[i].rollOff);
            editor.putInt("slot_" + i + "_vig", profiles[i].vignette);
        }
        editor.commit(); 

        try {
            File lutDir = getLutDir();
            if (!lutDir.exists()) lutDir.mkdirs(); 
            File backupFile = new File(lutDir, "RTLBAK.TXT");
            if (!backupFile.exists()) backupFile.createNewFile();
            
            FileOutputStream fos = new FileOutputStream(backupFile);
            StringBuilder sb = new StringBuilder();
            sb.append("quality=").append(qualityIndex).append("\n");
            sb.append("slot=").append(currentSlot).append("\n");
            for(int i=0; i<10; i++) {
                sb.append(i).append(",")
                  .append(recipePaths.get(profiles[i].lutIndex)).append(",")
                  .append(profiles[i].opacity).append(",")
                  .append(profiles[i].grain).append(",")
                  .append(profiles[i].grainSize).append(",")
                  .append(profiles[i].rollOff).append(",")
                  .append(profiles[i].vignette).append("\n");
            }
            fos.write(sb.toString().getBytes());
            fos.flush(); 
            fos.getFD().sync(); 
            fos.close();
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(backupFile)));
        } catch (Exception e) {}
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences("RTL_PREFS", MODE_PRIVATE);
        boolean hasSaved = prefs.getBoolean("has_saved", false);
        File backupFile = new File(getLutDir(), "RTLBAK.TXT");

        if (!hasSaved && backupFile.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(backupFile));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("quality=")) qualityIndex = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("slot=")) currentSlot = Integer.parseInt(line.split("=")[1]);
                    else {
                        String[] parts = line.split(",");
                        if (parts.length >= 6) {
                            int idx = Integer.parseInt(parts[0]);
                            int foundIndex = recipePaths.indexOf(parts[1]);
                            profiles[idx].lutIndex = (foundIndex != -1) ? foundIndex : 0;
                            profiles[idx].opacity = Integer.parseInt(parts[2]);
                            if (profiles[idx].opacity <= 5) profiles[idx].opacity = 100;
                            profiles[idx].grain = Math.min(5, Integer.parseInt(parts[3]));
                            if (parts.length == 7) {
                                profiles[idx].grainSize = Math.min(2, Integer.parseInt(parts[4]));
                                profiles[idx].rollOff = Math.min(5, Integer.parseInt(parts[5]));
                                profiles[idx].vignette = Math.min(5, Integer.parseInt(parts[6]));
                            } else {
                                profiles[idx].grainSize = 1;
                                profiles[idx].rollOff = Math.min(5, Integer.parseInt(parts[4]));
                                profiles[idx].vignette = Math.min(5, Integer.parseInt(parts[5]));
                            }
                        }
                    }
                }
                br.close(); 
                savePreferences(); 
                return;
            } catch (Exception e) {}
        }

        qualityIndex = prefs.getInt("qualityIndex", 1);
        currentSlot = prefs.getInt("currentSlot", 0);
        for(int i=0; i<10; i++) {
            String savedPath = prefs.getString("slot_" + i + "_lutPath", "NONE");
            int foundIndex = recipePaths.indexOf(savedPath);
            profiles[i].lutIndex = (foundIndex != -1) ? foundIndex : 0; 
            profiles[i].opacity = prefs.getInt("slot_" + i + "_opac", 100);
            if (profiles[i].opacity <= 5) profiles[i].opacity = 100; 
            profiles[i].grain = Math.min(5, prefs.getInt("slot_" + i + "_grain", 0));
            profiles[i].grainSize = Math.min(2, prefs.getInt("slot_" + i + "_gSize", 1));
            profiles[i].rollOff = Math.min(5, prefs.getInt("slot_" + i + "_roll", 0));
            profiles[i].vignette = Math.min(5, prefs.getInt("slot_" + i + "_vig", 0));
        }
    }

    @Override 
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        
        if (sc == ScalarInput.ISV_KEY_S1_1 && event.getRepeatCount() == 0) {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode) {
                tvTopStatus.setVisibility(View.GONE);
                tvBottomBar.setVisibility(View.GONE);
            }
            if (afOverlay != null) { afOverlay.startFocus(mCamera); }
            return super.onKeyDown(keyCode, event);
        }

        if (sc == ScalarInput.ISV_KEY_S1_2) {
            return super.onKeyDown(keyCode, event); 
        }

        if (sc == ScalarInput.ISV_KEY_DELETE) { 
            finish(); 
            return true; 
        }

        if (isPlaybackMode) {
            if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { showPlaybackImage(playbackIndex + 1); return true; }
            if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { showPlaybackImage(playbackIndex - 1); return true; }
            if (sc == ScalarInput.ISV_KEY_ENTER || sc == ScalarInput.ISV_KEY_MENU || sc == ScalarInput.ISV_KEY_PLAY) { exitPlayback(); return true; }
            return true; 
        }

        if (sc == ScalarInput.ISV_KEY_MENU) {
            isMenuOpen = !isMenuOpen;
            if (isMenuOpen) {
                menuContainer.setVisibility(View.VISIBLE); 
                mainUIContainer.setVisibility(View.GONE); 
                renderMenu();
            } else {
                menuContainer.setVisibility(View.GONE); 
                mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                savePreferences(); 
                triggerLutPreload(); 
                updateMainHUD();
            }
            return true;
        }

        if (sc == ScalarInput.ISV_KEY_ENTER) {
            if(!isMenuOpen) {
                if (mDialMode == DIAL_MODE_REVIEW) {
                    isPlaybackMode = true; 
                    refreshPlaybackFiles();
                    playbackContainer.setVisibility(View.VISIBLE); 
                    mainUIContainer.setVisibility(View.GONE); 
                    menuContainer.setVisibility(View.GONE);
                    showPlaybackImage(0); 
                } else {
                    displayState = (displayState == 0) ? 1 : 0; 
                    mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                }
            }
            return true;
        }

        if (!isProcessing) {
            if (isMenuOpen) {
                if (sc == ScalarInput.ISV_KEY_UP) { menuSelection = (menuSelection - 1 + 11) % 11; renderMenu(); return true; }
                if (sc == ScalarInput.ISV_KEY_DOWN) { menuSelection = (menuSelection + 1) % 11; renderMenu(); return true; }
                if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleMenuChange(-1); return true; }
                if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleMenuChange(1); return true; }
            } else {
                if (sc == ScalarInput.ISV_KEY_LEFT) { cycleMode(-1); return true; }
                if (sc == ScalarInput.ISV_KEY_RIGHT) { cycleMode(1); return true; }
                if (sc == ScalarInput.ISV_KEY_UP || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
                if (sc == ScalarInput.ISV_KEY_DOWN || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override 
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        
        if (sc == ScalarInput.ISV_KEY_S1_1) {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode) {
                tvTopStatus.setVisibility(View.VISIBLE);
                tvBottomBar.setVisibility(View.VISIBLE);
            }
            if (afOverlay != null) { afOverlay.stopFocus(mCamera); }
            return super.onKeyUp(keyCode, event);
        }
        return super.onKeyUp(keyCode, event);
    }

    private void handleMenuChange(int dir) {
        RTLProfile p = profiles[currentSlot];
        try {
            switch(menuSelection) {
                case 0: qualityIndex = (qualityIndex + dir + 3) % 3; break;
                case 1: currentSlot = (currentSlot + dir + 10) % 10; break; 
                case 2: p.lutIndex = (p.lutIndex + dir + recipePaths.size()) % recipePaths.size(); break;
                case 3: p.opacity = Math.max(0, Math.min(100, p.opacity + (dir * 10))); break;
                case 4: p.grain = Math.max(0, Math.min(5, p.grain + dir)); break;
                case 5: p.grainSize = Math.max(0, Math.min(2, p.grainSize + dir)); break;
                case 6: p.rollOff = Math.max(0, Math.min(5, p.rollOff + dir)); break;
                case 7: p.vignette = Math.max(0, Math.min(5, p.vignette + dir)); break;
            }
        } catch (Exception e) {}
        renderMenu();
    }

    private void renderMenu() {
        RTLProfile p = profiles[currentSlot];
        String[] qLabels = {"PROXY (1.5MP)", "HIGH (6MP)", "ULTRA (24MP)"};
        
        menuLabels[0].setText("Global Quality");   menuValues[0].setText("< " + qLabels[qualityIndex] + " >");
        menuLabels[1].setText("RTL Slot");         menuValues[1].setText("< " + (currentSlot + 1) + " >");
        menuLabels[2].setText("LUT");              menuValues[2].setText("< " + recipeNames.get(p.lutIndex) + " >");
        menuLabels[3].setText("Opacity");          menuValues[3].setText("< " + p.opacity + "% >");
        menuLabels[4].setText("Grain Amount");     menuValues[4].setText("< " + intensityLabels[p.grain] + " >");
        menuLabels[5].setText("Grain Size");       menuValues[5].setText("< " + grainSizeLabels[p.grainSize] + " >");
        menuLabels[6].setText("Highlight Roll");   menuValues[6].setText("< " + intensityLabels[p.rollOff] + " >");
        menuLabels[7].setText("Vignette");         menuValues[7].setText("< " + intensityLabels[p.vignette] + " >");
        menuLabels[8].setText("[LOCKED] W.Bal");   menuValues[8].setText("< " + p.whiteBalance + " >");
        menuLabels[9].setText("[LOCKED] WB Shift");menuValues[9].setText("< " + p.wbShift + " >");
        menuLabels[10].setText("[LOCKED] DRO");    menuValues[10].setText("< " + p.dro + " >");

        for (int i = 0; i < 11; i++) {
            boolean sel = (i == menuSelection);
            menuRows[i].setBackgroundColor(sel ? Color.WHITE : Color.TRANSPARENT);
            menuLabels[i].setTextColor(sel ? Color.BLACK : (i > 7 ? Color.DKGRAY : Color.WHITE));
            menuValues[i].setTextColor(sel ? Color.BLACK : (i > 7 ? Color.DKGRAY : Color.CYAN));
        }
    }

    private void cycleMode(int dir) {
        int ord = (mDialMode + dir + 6) % 6;
        mDialMode = ord;
        updateMainHUD();
    }

    private void handleInput(int d) {
        if (mCameraEx == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            
            if (mDialMode == DIAL_MODE_RTL) { 
                currentSlot = (currentSlot + d + 10) % 10; 
                triggerLutPreload(); 
            }
            else if (mDialMode == DIAL_MODE_SHUTTER) { 
                if (d > 0) mCameraEx.incrementShutterSpeed(); 
                else mCameraEx.decrementShutterSpeed(); 
            }
            else if (mDialMode == DIAL_MODE_APERTURE) { 
                if (d > 0) mCameraEx.incrementAperture(); 
                else mCameraEx.decrementAperture(); 
            }
            else if (mDialMode == DIAL_MODE_ISO) {
                List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities();
                int idx = isos.indexOf(pm.getISOSensitivity());
                if (idx != -1) { 
                    pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size()-1, idx + d)))); 
                    mCamera.setParameters(p); 
                }
            }
            else if (mDialMode == DIAL_MODE_EXPOSURE) { 
                p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), p.getExposureCompensation() + d))); 
                mCamera.setParameters(p); 
            }
            updateMainHUD();
        } catch (Exception e) {}
    }

    private void updateMainHUD() {
        if(mCamera == null) return;
        RTLProfile p = profiles[currentSlot];
        String uiLutName = recipeNames.get(p.lutIndex);
        tvTopStatus.setText("RTL " + (currentSlot + 1) + " [" + uiLutName + "]\n" + (isReady ? "READY" : "LOADING..."));
        
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
            String html = (mDialMode == DIAL_MODE_RTL ? g : w) + "[RTL " + (currentSlot+1) + "]</font>   " +
                          (mDialMode == DIAL_MODE_SHUTTER ? g : w) + "S: " + ss + "</font>   " + 
                          (mDialMode == DIAL_MODE_APERTURE ? g : w) + "A: " + ap + "</font>   " + 
                          (mDialMode == DIAL_MODE_ISO ? g : w) + "ISO: " + iso + "</font>   " + 
                          (mDialMode == DIAL_MODE_EXPOSURE ? g : w) + "EV: " + exp + "</font>   " +
                          (mDialMode == DIAL_MODE_REVIEW ? g : w) + "[REVIEW]</font>";
            tvBottomBar.setText(Html.fromHtml(html));
        } catch (Exception e) {}
    }

    private void scanRecipes() { 
        recipePaths.clear(); 
        recipeNames.clear();
        recipePaths.add("NONE"); 
        recipeNames.add("NONE");
        
        File lutDir = getLutDir();
        if (lutDir.exists() && lutDir.listFiles() != null) {
            for (File f : lutDir.listFiles()) {
                String u = f.getName().toUpperCase();
                if (f.length() > 10240 && (u.endsWith(".CUB") || u.endsWith(".CUBE"))) {
                    recipePaths.add(f.getAbsolutePath());
                    String prettyName = u.replace(".CUB", "").replace(".CUBE", "");
                    try {
                        BufferedReader br = new BufferedReader(new FileReader(f));
                        String line;
                        for(int j=0; j<15; j++) {
                            line = br.readLine();
                            if (line != null && line.startsWith("TITLE")) {
                                prettyName = line.replace("TITLE", "").replace("\"", "").trim().toUpperCase();
                                break;
                            }
                        }
                        br.close();
                    } catch (Exception e) {}
                    recipeNames.add(prettyName);
                }
            }
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
            @Override 
            public void run() {
                while (isPolling) {
                    try {
                        Thread.sleep(150); 
                        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
                        File sonyDir = new File(dcim, "100MSDCF");
                        if (sonyDir.exists()) {
                            File[] files = sonyDir.listFiles();
                            if (files != null && files.length > 0) {
                                File newest = null; 
                                long maxModified = 0;
                                for (File f : files) {
                                    if (f.getName().toUpperCase().endsWith(".JPG") && !f.getName().startsWith("PRCS") && !f.getName().startsWith("GRADED")) {
                                        if (f.lastModified() > maxModified) { 
                                            maxModified = f.lastModified(); 
                                            newest = f; 
                                        }
                                    }
                                }
                                if (newest != null) {
                                    if (lastNewestFileTime == 0) { 
                                        lastNewestFileTime = maxModified; 
                                    } else if (maxModified > lastNewestFileTime) {
                                        lastNewestFileTime = maxModified;
                                        if (!isProcessing && (isReady || profiles[currentSlot].lutIndex == 0)) {
                                            final String path = newest.getAbsolutePath();
                                            runOnUiThread(new Runnable() { 
                                                @Override 
                                                public void run() { 
                                                    if (!isProcessing) new ProcessTask().execute(path); 
                                                } 
                                            });
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
        @Override 
        protected void onPreExecute() { 
            isReady = false; 
            updateMainHUD(); 
        }
        @Override 
        protected Boolean doInBackground(Integer... params) {
            if (mEngine == null) return false;
            return mEngine.loadLut(new File(recipePaths.get(params[0])), recipeNames.get(params[0]));
        }
        @Override 
        protected void onPostExecute(Boolean success) { 
            if (isCancelled()) return; 
            isReady = true; 
            updateMainHUD(); 
        }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        @Override 
        protected void onPreExecute() { 
            isProcessing = true; 
            tvTopStatus.setText("PROCESSING..."); 
            tvTopStatus.setTextColor(Color.YELLOW);
        }
        @Override 
        protected String doInBackground(String... params) {
            try {
                File original = new File(params[0]);
                if (!original.exists()) return "ERR";
                long lastSize = -1; 
                int timeout = 0;
                while (timeout < 100) {
                    long currentSize = original.length();
                    if (currentSize > 0 && currentSize == lastSize) break;
                    lastSize = currentSize; 
                    Thread.sleep(50); 
                    timeout++;
                }

                int scale = (qualityIndex == 0) ? 4 : (qualityIndex == 2 ? 1 : 2);
                File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
                if (!outDir.exists()) outDir.mkdirs();
                File outFile = new File(outDir, original.getName());

                RTLProfile p = profiles[currentSlot];
                if (mEngine != null && mEngine.applyLutToJpeg(original.getAbsolutePath(), outFile.getAbsolutePath(), scale, p.opacity, p.grain * 20, p.grainSize, p.vignette * 20, p.rollOff * 20)) {
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)));
                    return "SAVED " + (scale==1?"24MP":(scale==2?"6MP":"1.5MP"));
                }
                return "FAILED";
            } catch (Throwable t) { 
                return "ERR"; 
            }
        }
        @Override 
        protected void onPostExecute(String result) {
            isProcessing = false; 
            tvTopStatus.setTextColor(Color.WHITE); 
            updateMainHUD(); 
        }
    }

    private void openCamera() {
        if (mCameraEx == null && hasSurface) {
            try { 
                mCameraEx = CameraEx.open(0, null); 
                mCamera = mCameraEx.getNormalCamera();
                mCameraEx.startDirectShutter(); 
                CameraEx.AutoPictureReviewControl apr = new CameraEx.AutoPictureReviewControl();
                mCameraEx.setAutoPictureReviewControl(apr); 
                apr.setPictureReviewTime(0);
                mCamera.setPreviewDisplay(mSurfaceView.getHolder()); 
                mCamera.startPreview(); 
                updateMainHUD();
            } catch (Exception e) {} 
        }
    }

    private void closeCamera() {
        if (mCameraEx != null) { 
            mCameraEx.release(); 
            mCameraEx = null; 
            mCamera = null; 
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { hasSurface = true; openCamera(); }
    @Override public void surfaceDestroyed(SurfaceHolder h) { hasSurface = false; closeCamera(); }
    
    @Override 
    protected void onResume() { 
        super.onResume(); 
        openCamera();
        if (mCamera != null) updateMainHUD(); 
        startAutoProcessPolling(); 
    }
    
    @Override 
    protected void onPause() { 
        super.onPause(); 
        closeCamera(); 
        isPolling = false; 
        savePreferences(); 
    }
    
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { updateMainHUD(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}

    // =========================================================================
    // PRO RETICLE VIEW (Fixed Geometry)
    // =========================================================================
    private class ProReticleView extends View {
        private Paint paint;
        public static final int STATE_IDLE = 0;
        public static final int STATE_SEARCHING = 1;
        public static final int STATE_LOCKED = 2;
        public static final int STATE_FAILED = 3;
        private int fallbackState = STATE_IDLE;
        private boolean isPolling = false;

        public ProReticleView(Context context) {
            super(context);
            paint = new Paint();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6);
            paint.setAntiAlias(true);
        }

        public void startFocus(Camera cam) {
            fallbackState = STATE_SEARCHING;
            if (cam != null) {
                try {
                    cam.autoFocus(new Camera.AutoFocusCallback() {
                        @Override
                        public void onAutoFocus(boolean success, Camera camera) {
                            fallbackState = success ? STATE_LOCKED : STATE_FAILED;
                            invalidate();
                        }
                    });
                } catch (Exception e) { 
                    fallbackState = STATE_IDLE; 
                }
            }
            isPolling = true;
            invalidate();
        }

        public void stopFocus(Camera cam) {
            if (cam != null) { 
                try { 
                    cam.cancelAutoFocus(); 
                } catch (Exception e) {} 
            }
            isPolling = false;
            fallbackState = STATE_IDLE;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!isPolling) return;

            switch (fallbackState) {
                case STATE_IDLE:      paint.setColor(Color.argb(100, 255, 255, 255)); break;
                case STATE_SEARCHING: paint.setColor(Color.YELLOW); break;
                case STATE_LOCKED:    paint.setColor(Color.GREEN); break;
                case STATE_FAILED:    paint.setColor(Color.RED); break;
            }

            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int size = 60;
            int bracket = 15;

            // Top Left
            canvas.drawLine(cx - size, cy - size, cx - size + bracket, cy - size, paint);
            canvas.drawLine(cx - size, cy - size, cx - size, cy - size + bracket, paint);
            
            // Top Right
            canvas.drawLine(cx + size, cy - size, cx + size - bracket, cy - size, paint);
            canvas.drawLine(cx + size, cy - size, cx + size, cy - size + bracket, paint);
            
            // Bottom Left
            canvas.drawLine(cx - size, cy + size, cx - size + bracket, cy + size, paint);
            canvas.drawLine(cx - size, cy + size, cx - size, cy + size - bracket, paint);
            
            // Bottom Right (FIXED MATH HERE)
            canvas.drawLine(cx + size, cy + size, cx + size - bracket, cy + size, paint);
            canvas.drawLine(cx + size, cy + size, cx + size, cy + size - bracket, paint);
            
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, 3, paint);
            paint.setStyle(Paint.Style.STROKE);
            
            postInvalidateDelayed(50);
        }
    }
}