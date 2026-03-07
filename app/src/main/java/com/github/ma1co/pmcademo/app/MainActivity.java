package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
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

import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, 
    SonyCameraManager.CameraEventListener, InputManager.InputListener, ConnectivityManager.StatusUpdateListener {

    private SonyCameraManager cameraManager;
    private InputManager inputManager;
    private RecipeManager recipeManager;
    private ConnectivityManager connectivityManager;
    private MenuManager menuManager;
    
    private ImageProcessor mProcessor;
    private SonyFileScanner mScanner;

    private SurfaceView mSurfaceView;
    private boolean hasSurface = false;
    
    private FrameLayout mainUIContainer;
    private FrameLayout playbackContainer;
    private LinearLayout menuContainer;
    private LinearLayout llBottomBar;
    
    private TextView tvTopStatus;
    private TextView tvBattery;
    private TextView tvValShutter;
    private TextView tvValAperture;
    private TextView tvValIso;
    private TextView tvValEv;
    private TextView tvMode;
    private TextView tvFocusMode;
    private TextView tvReview;
    private TextView tvPlaybackInfo;
    private TextView tvMenuTitle;
    
    private TextView[] tvPageNumbers = new TextView[4];
    private LinearLayout[] menuRows = new LinearLayout[7];
    private TextView[] menuLabels = new TextView[7];
    private TextView[] menuValues = new TextView[7];
    
    private BatteryView batteryIcon;
    private ImageView playbackImageView;
    private List<File> playbackFiles = new ArrayList<File>();
    private Bitmap currentPlaybackBitmap = null;
    
    private int playbackIndex = 0;
    private boolean isPlaybackMode = false;
    private boolean isMenuOpen = false;
    private boolean isProcessing = false;
    private boolean isReady = false;
    private int displayState = 0; 
    
    private boolean prefShowFocusMeter = true;
    private boolean prefShowCinemaMattes = false;
    private boolean prefShowGridLines = false;
    
    // CACHE FIX: Prevent massive IPC lag during Focus Ring turning
    private boolean cachedIsManualFocus = false;
    private float cachedAperture = 2.8f;
    
    private GridLinesView gridLines;
    private CinemaMatteView cinemaMattes;
    private AdvancedFocusMeterView focusMeter;
    private ProReticleView afOverlay;
    
    private Handler uiHandler = new Handler();

    public static final int DIAL_MODE_SHUTTER = 0;
    public static final int DIAL_MODE_APERTURE = 1;
    public static final int DIAL_MODE_ISO = 2;
    public static final int DIAL_MODE_EXPOSURE = 3;
    public static final int DIAL_MODE_REVIEW = 4;
    public static final int DIAL_MODE_RTL = 5;
    public static final int DIAL_MODE_PASM = 6;
    public static final int DIAL_MODE_FOCUS = 7;
    
    private int mDialMode = DIAL_MODE_RTL;

    private Runnable applySettingsRunnable = new Runnable() {
        @Override 
        public void run() { 
            applyHardwareRecipe(); 
        }
    };

    private Runnable liveUpdater = new Runnable() {
        @Override
        public void run() {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode && !isProcessing && hasSurface && cameraManager.getCamera() != null) {
                boolean s1_1_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_1).status == 0;
                boolean s1_2_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_2).status == 0;
                
                if (s1_1_free && s1_2_free) {
                    if (afOverlay != null && afOverlay.isPolling()) {
                        afOverlay.stopFocus(cameraManager.getCamera());
                    }
                    if (tvTopStatus.getVisibility() != View.VISIBLE) {
                        setHUDVisibility(View.VISIBLE);
                    }
                }
                updateMainHUD();
            }
            uiHandler.postDelayed(this, 500); 
        }
    };

    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                final int pct = (level * 100) / scale;
                runOnUiThread(new Runnable() { 
                    public void run() { 
                        tvBattery.setText(pct + "%"); 
                        batteryIcon.setLevel(pct); 
                    } 
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        cameraManager = new SonyCameraManager(this);
        inputManager = new InputManager(this);
        recipeManager = new RecipeManager();
        connectivityManager = new ConnectivityManager(this, this);
        menuManager = new MenuManager();
        
        recipeManager.loadPreferences();
        
        FrameLayout rootLayout = new FrameLayout(this);
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        rootLayout.addView(mSurfaceView, new FrameLayout.LayoutParams(-1, -1));
        
        buildUI(rootLayout);
        setContentView(rootLayout);
        setupEngines();
    }

    private void setupEngines() {
        mProcessor = new ImageProcessor(this, new ImageProcessor.ProcessorCallback() {
            @Override 
            public void onPreloadStarted() { 
                isReady = false; 
                runOnUiThread(new Runnable() { 
                    public void run() { 
                        updateMainHUD(); 
                    } 
                }); 
            }
            
            @Override 
            public void onPreloadFinished(boolean success) { 
                isReady = true; 
                runOnUiThread(new Runnable() { 
                    public void run() { 
                        updateMainHUD(); 
                    } 
                }); 
            }
            
            @Override 
            public void onProcessStarted() { 
                runOnUiThread(new Runnable() { 
                    public void run() { 
                        tvTopStatus.setText("PROCESSING..."); 
                        tvTopStatus.setTextColor(Color.YELLOW); 
                    } 
                }); 
            }
            
            @Override 
            public void onProcessFinished(String res) { 
                isProcessing = false; 
                runOnUiThread(new Runnable() { 
                    public void run() { 
                        tvTopStatus.setTextColor(Color.WHITE); 
                        updateMainHUD(); 
                    } 
                }); 
            }
        });
        
        String dcimPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/100MSDCF";
        mScanner = new SonyFileScanner(dcimPath, new SonyFileScanner.ScannerCallback() {
            @Override 
            public boolean isReadyToProcess() { 
                return isReady && !isProcessing && recipeManager.getCurrentProfile().lutIndex != 0; 
            }
            
            @Override 
            public void onNewPhotoDetected(final String path) { 
                processWhenFileReady(path);
            }
        });
        mScanner.start();
        triggerLutPreload();
    }

    private void processWhenFileReady(final String path) {
        isProcessing = true; 
        runOnUiThread(new Runnable() { 
            public void run() { 
                tvTopStatus.setText("SAVING TO SD..."); 
                tvTopStatus.setTextColor(Color.YELLOW); 
                updateMainHUD(); 
            } 
        });
        
        final File f = new File(path);
        final long[] lastSize = {-1};
        final int[] retries = {0};
        
        Runnable checker = new Runnable() {
            @Override
            public void run() {
                long currentSize = f.length();
                if (currentSize > 0 && currentSize == lastSize[0]) {
                    File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
                    mProcessor.processJpeg(path, outDir.getAbsolutePath(), recipeManager.getQualityIndex(), recipeManager.getCurrentProfile());
                } else if (retries[0] < 30) { 
                    lastSize[0] = currentSize;
                    retries[0]++;
                    uiHandler.postDelayed(this, 500);
                } else {
                    isProcessing = false;
                    updateMainHUD();
                }
            }
        };
        uiHandler.postDelayed(checker, 500);
    }

    private void triggerLutPreload() {
        RTLProfile p = recipeManager.getCurrentProfile();
        mProcessor.triggerLutPreload(recipeManager.getRecipePaths().get(p.lutIndex), recipeManager.getRecipeNames().get(p.lutIndex));
    }

    @Override 
    public void onShutterHalfPressed() {
        if (isPlaybackMode) { 
            exitPlayback(); 
            return; 
        }
        if (isMenuOpen) { 
            exitMenu(); 
            return; 
        }
        if (isProcessing) {
            return; 
        }
        
        mDialMode = DIAL_MODE_RTL;
        if (displayState == 0 && !isMenuOpen) {
            setHUDVisibility(View.GONE);
        }
        
        Camera c = cameraManager.getCamera();
        if (afOverlay != null && c != null) {
            try { 
                if (!"manual".equals(c.getParameters().getFocusMode())) {
                    afOverlay.startFocus(c); 
                }
            } catch (Exception e) {}
        }
    }

    @Override 
    public void onShutterHalfReleased() {
        if (displayState == 0 && !isMenuOpen && !isPlaybackMode) {
            setHUDVisibility(View.VISIBLE);
        }
        if (afOverlay != null) {
            afOverlay.stopFocus(cameraManager.getCamera());
        }
    }

    @Override 
    public void onDeletePressed() { 
        finish(); 
    }

    @Override 
    public void onMenuPressed() {
        if (isPlaybackMode) { 
            exitPlayback(); 
            return; 
        }
        if (isProcessing) {
            return; 
        }
        
        if (!isMenuOpen) {
            recipeManager.scanRecipes(); 
            menuManager.setPage(1); 
            menuContainer.setVisibility(View.VISIBLE); 
            mainUIContainer.setVisibility(View.GONE); 
            isMenuOpen = true;
            renderMenu();
        } else {
            exitMenu();
        }
    }

    private void exitMenu() {
        isMenuOpen = false;
        menuContainer.setVisibility(View.GONE); 
        mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE); 
        recipeManager.savePreferences(); 
        triggerLutPreload(); 
        applyHardwareRecipe();
        syncHardwareState();
        updateMainHUD();
    }

    @Override 
    public void onEnterPressed() {
        if (isPlaybackMode) { 
            exitPlayback(); 
            return; 
        }
        if (isProcessing) {
            return;
        }
        
        if (!isMenuOpen) {
            if (mDialMode == DIAL_MODE_REVIEW) {
                enterPlayback();
            } else { 
                displayState = (displayState == 0) ? 1 : 0; 
                mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE); 
            }
        } else {
            if (menuManager.getSelection() == -1) {
                exitMenu();
            } else if (menuManager.getCurrentPage() == 4) { 
                handleConnectionAction(); 
            }
        }
    }

    @Override 
    public void onUpPressed() { 
        if (isProcessing) return;
        menuManager.moveSelection(-1); 
        renderMenu(); 
    }
    
    @Override 
    public void onDownPressed() { 
        if (isProcessing) return;
        menuManager.moveSelection(1); 
        renderMenu(); 
    }
    
    @Override 
    public void onLeftPressed() { 
        if (isPlaybackMode) {
            showPlaybackImage(playbackIndex - 1);
        } else if (isMenuOpen) {
            handleMenuHorizontal(-1); 
        } else if (!isProcessing) {
            cycleDialMode(-1); 
        }
    }
    
    @Override 
    public void onRightPressed() { 
        if (isPlaybackMode) {
            showPlaybackImage(playbackIndex + 1);
        } else if (isMenuOpen) {
            handleMenuHorizontal(1); 
        } else if (!isProcessing) {
            cycleDialMode(1); 
        }
    }
    
    @Override 
    public void onDialRotated(int direction) { 
        if (isPlaybackMode) {
            showPlaybackImage(playbackIndex + direction);
        } else if (isMenuOpen) {
            handleMenuChange(direction); 
        } else if (!isProcessing) {
            handleHardwareInput(direction); 
        }
    }

    private void handleMenuHorizontal(int d) { 
        if (menuManager.getSelection() == -1) { 
            menuManager.cyclePage(d); 
            renderMenu(); 
        } else { 
            handleMenuChange(d); 
        }
    }

    private void handleHardwareInput(int d) {
        Camera c = cameraManager.getCamera(); 
        CameraEx cx = cameraManager.getCameraEx();
        
        if (c == null || cx == null) {
            return;
        }
        
        Camera.Parameters p = c.getParameters(); 
        CameraEx.ParametersModifier pm = cx.createParametersModifier(p);
        
        if (mDialMode == DIAL_MODE_RTL) { 
            recipeManager.setCurrentSlot(recipeManager.getCurrentSlot() + d); 
            applyHardwareRecipe(); 
            triggerLutPreload(); 
        }
        else if (mDialMode == DIAL_MODE_SHUTTER) { 
            if (d > 0) {
                cx.incrementShutterSpeed(); 
            } else {
                cx.decrementShutterSpeed(); 
            }
        }
        else if (mDialMode == DIAL_MODE_APERTURE) { 
            if (d > 0) {
                cx.incrementAperture(); 
            } else {
                cx.decrementAperture(); 
            }
        }
        else if (mDialMode == DIAL_MODE_ISO) {
            List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities();
            if (isos != null) {
                int idx = isos.indexOf(pm.getISOSensitivity());
                if (idx != -1) { 
                    pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size()-1, idx + d)))); 
                    try { c.setParameters(p); } catch (Exception e) {}
                }
            }
        }
        else if (mDialMode == DIAL_MODE_EXPOSURE) {
            int ev = p.getExposureCompensation();
            p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), ev + d)));
            try { c.setParameters(p); } catch (Exception e) {}
        }
        else if (mDialMode == DIAL_MODE_PASM) {
            List<String> valid = new ArrayList<String>(); 
            // PASM STRING FIX: Included both shutter strings Sony uses
            String[] desired = {"program-auto", "aperture-priority", "shutter-priority", "shutter-speed-priority", "manual-exposure"};
            List<String> supported = p.getSupportedSceneModes();
            if (supported != null) {
                for (String s : desired) { 
                    if (supported.contains(s)) {
                        valid.add(s); 
                    }
                }
                if (!valid.isEmpty()) {
                    int idx = valid.indexOf(p.getSceneMode()); 
                    if (idx == -1) {
                        idx = 0; 
                    }
                    p.setSceneMode(valid.get((idx + d + valid.size()) % valid.size())); 
                    try { c.setParameters(p); } catch (Exception e) {}
                }
            }
        }
        else if (mDialMode == DIAL_MODE_FOCUS) {
            List<String> focusModes = p.getSupportedFocusModes();
            if (focusModes != null && !focusModes.isEmpty()) {
                int idx = focusModes.indexOf(p.getFocusMode());
                p.setFocusMode(focusModes.get((idx + d + focusModes.size()) % focusModes.size()));
                try { c.setParameters(p); } catch (Exception e) {}
            }
        }
        
        uiHandler.removeCallbacks(liveUpdater); 
        uiHandler.postDelayed(liveUpdater, 1000); 
        updateMainHUD();
    }

    private void applyHardwareRecipe() {
        Camera c = cameraManager.getCamera(); 
        if (c == null) {
            return;
        }
        
        RTLProfile prof = recipeManager.getCurrentProfile(); 
        Camera.Parameters p = c.getParameters();
        
        String wb = "auto";
        if ("DAY".equals(prof.whiteBalance)) {
            wb = "daylight"; 
        } else if ("SHD".equals(prof.whiteBalance)) {
            wb = "shade"; 
        } else if ("CLD".equals(prof.whiteBalance)) {
            wb = "cloudy-daylight"; 
        } else if ("INC".equals(prof.whiteBalance)) {
            wb = "incandescent"; 
        } else if ("FLR".equals(prof.whiteBalance)) {
            wb = "fluorescent";
        }
        
        p.setWhiteBalance(wb);
        
        if (p.get("dro-mode") != null) {
            if ("OFF".equals(prof.dro)) {
                p.set("dro-mode", "off"); 
            } else if ("AUTO".equals(prof.dro)) {
                p.set("dro-mode", "auto"); 
            } else if (prof.dro.startsWith("LV")) { 
                p.set("dro-mode", "on"); 
                p.set("dro-level", prof.dro.replace("LV", "")); 
            }
        }
        
        p.set("contrast", String.valueOf(prof.contrast)); 
        p.set("saturation", String.valueOf(prof.saturation)); 
        p.set("sharpness", String.valueOf(prof.sharpness));
        p.set("white-balance-shift-lb", String.valueOf(prof.wbShift)); 
        p.set("white-balance-shift-cc", String.valueOf(prof.wbShiftGM));
        
        // CAMERA DAEMON CRASH FIX: Try/Catch prevents bootloop if hardware rejects param combo
        try {
            c.setParameters(p);
        } catch (Exception e) {
            // Parameter combination rejected by current Scene Mode, gracefully ignore.
        }
    }

    @Override 
    public void onCameraReady() { 
        syncHardwareState();
        updateMainHUD(); 
    }
    
    @Override 
    public void onShutterSpeedChanged() { 
        runOnUiThread(new Runnable() { 
            public void run() { 
                updateMainHUD(); 
            } 
        }); 
    }
    
    @Override 
    public void onApertureChanged() { 
        runOnUiThread(new Runnable() { 
            public void run() { 
                updateMainHUD(); 
            } 
        }); 
    }
    
    @Override 
    public void onIsoChanged() { 
        runOnUiThread(new Runnable() { 
            public void run() { 
                updateMainHUD(); 
            } 
        }); 
    }
    
    @Override 
    public void onFocusPositionChanged(final float ratio) {
        // FOCUS IPC LAG FIX: Read variables from local cache instead of requesting from hardware API
        if (focusMeter != null && cachedIsManualFocus) { 
            runOnUiThread(new Runnable() { 
                public void run() {
                    focusMeter.update(ratio, cachedAperture, true); 
                }
            });
        }
    }

    @Override 
    public void onStatusUpdate(String target, String status) { 
        if (isMenuOpen && menuManager.getCurrentPage() == 4) {
            renderMenu(); 
        }
    }

    private void syncHardwareState() {
        Camera c = cameraManager.getCamera();
        if (c == null) return;
        
        String fMode = c.getParameters().getFocusMode();
        cachedIsManualFocus = "manual".equals(fMode);
        
        if (focusMeter != null) {
            focusMeter.setVisibility(cachedIsManualFocus ? View.VISIBLE : View.GONE);
        }
    }

    private void enterPlayback() {
        playbackFiles.clear();
        File dir = new File(Environment.getExternalStorageDirectory(), "GRADED");
        if (dir.exists() && dir.listFiles() != null) {
            for (File f : dir.listFiles()) {
                if (f.getName().toLowerCase().endsWith(".jpg")) {
                    playbackFiles.add(f);
                }
            }
        }
        
        Collections.sort(playbackFiles, new Comparator<File>() { 
            @Override 
            public int compare(File f1, File f2) { 
                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified()); 
            } 
        });
        
        if (playbackFiles.isEmpty()) {
            return;
        }
        
        isPlaybackMode = true; 
        mainUIContainer.setVisibility(View.GONE); 
        playbackContainer.setVisibility(View.VISIBLE); 
        showPlaybackImage(0);
    }

    private void exitPlayback() {
        isPlaybackMode = false;
        playbackContainer.setVisibility(View.GONE);
        mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
        
        playbackImageView.setImageBitmap(null);
        if (currentPlaybackBitmap != null) { 
            currentPlaybackBitmap.recycle(); 
            currentPlaybackBitmap = null; 
        }
        System.gc();
    }

    private void showPlaybackImage(int idx) {
        if (playbackFiles.isEmpty()) {
            return;
        }
        
        if (idx < 0) {
            idx = playbackFiles.size() - 1; 
        }
        
        if (idx >= playbackFiles.size()) {
            idx = 0; 
        }
        
        playbackIndex = idx; 
        File file = playbackFiles.get(idx);
        
        try {
            playbackImageView.setImageBitmap(null);
            if (currentPlaybackBitmap != null) { 
                currentPlaybackBitmap.recycle(); 
                currentPlaybackBitmap = null; 
            }
            System.gc();

            if (file.length() == 0) {
                tvPlaybackInfo.setText((idx + 1) + "/" + playbackFiles.size() + "\n[ERROR: 0-BYTE FILE]");
                return;
            }
            
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            tvPlaybackInfo.setText((idx + 1) + "/" + playbackFiles.size() + "\n" + file.getName());
            
            BitmapFactory.Options opts = new BitmapFactory.Options(); 
            opts.inSampleSize = 8; 
            Bitmap raw = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            
            if (raw == null) {
                return;
            }

            int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rot = 0; 
            if (orient == ExifInterface.ORIENTATION_ROTATE_90) {
                rot = 90; 
            } else if (orient == ExifInterface.ORIENTATION_ROTATE_180) {
                rot = 180; 
            } else if (orient == ExifInterface.ORIENTATION_ROTATE_270) {
                rot = 270;
            }
            
            Matrix m = new Matrix(); 
            if (rot != 0) {
                m.postRotate(rot); 
            }
            m.postScale(0.8888f, 1.0f);
            
            Bitmap bmp = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            playbackImageView.setImageBitmap(bmp);
            currentPlaybackBitmap = bmp;
        } catch (Exception e) {}
    }

    private void handleMenuChange(int dir) {
        RTLProfile p = recipeManager.getCurrentProfile(); 
        int sel = menuManager.getSelection(); 
        int pg = menuManager.getCurrentPage();
        
        // MENU LAG FIX: Removed the applyHardwareRecipe call here so UI doesn't stutter on clicks.
        if (pg == 1) {
            switch(sel) {
                case 0: 
                    recipeManager.setCurrentSlot(recipeManager.getCurrentSlot() + dir); 
                    break;
                case 1: 
                    p.lutIndex = (p.lutIndex + dir + recipeManager.getRecipePaths().size()) % recipeManager.getRecipePaths().size(); 
                    break;
                case 2: 
                    p.opacity = Math.max(0, Math.min(100, p.opacity + (dir * 10))); 
                    break;
                case 3: 
                    p.grain = Math.max(0, Math.min(5, p.grain + dir)); 
                    break;
                case 4: 
                    p.grainSize = Math.max(0, Math.min(2, p.grainSize + dir)); 
                    break;
                case 5: 
                    p.rollOff = Math.max(0, Math.min(5, p.rollOff + dir)); 
                    break;
                case 6: 
                    p.vignette = Math.max(0, Math.min(5, p.vignette + dir)); 
                    break;
            }
        } else if (pg == 2) {
            switch(sel) {
                case 0: 
                    int wbi = java.util.Arrays.asList(menuManager.wbLabels).indexOf(p.whiteBalance); 
                    p.whiteBalance = menuManager.wbLabels[(wbi + dir + 6) % 6]; 
                    break;
                case 1: 
                    p.wbShift = Math.max(-7, Math.min(7, p.wbShift + dir)); 
                    break;
                case 2: 
                    p.wbShiftGM = Math.max(-7, Math.min(7, p.wbShiftGM + dir)); 
                    break;
                case 3: 
                    int droi = java.util.Arrays.asList(menuManager.droLabels).indexOf(p.dro); 
                    p.dro = menuManager.droLabels[(droi + dir + 7) % 7]; 
                    break;
                case 4: 
                    p.contrast = Math.max(-3, Math.min(3, p.contrast + dir)); 
                    break;
                case 5: 
                    p.saturation = Math.max(-3, Math.min(3, p.saturation + dir)); 
                    break;
                case 6: 
                    p.sharpness = Math.max(-3, Math.min(3, p.sharpness + dir)); 
                    break;
            }
        } else if (pg == 3) {
            switch(sel) {
                case 0: 
                    recipeManager.setQualityIndex(recipeManager.getQualityIndex() + dir); 
                    break;
                case 1: 
                    Camera c = cameraManager.getCamera();
                    if (c != null) {
                        Camera.Parameters params = c.getParameters();
                        List<String> supported = params.getSupportedSceneModes();
                        if (supported != null && !supported.isEmpty()) {
                            int idx = supported.indexOf(params.getSceneMode());
                            if (idx == -1) idx = 0;
                            params.setSceneMode(supported.get((idx + dir + supported.size()) % supported.size()));
                            try { c.setParameters(params); } catch (Exception e) {}
                        }
                    }
                    break; 
                case 2: 
                    prefShowFocusMeter = !prefShowFocusMeter; 
                    break;
                case 3: 
                    prefShowCinemaMattes = !prefShowCinemaMattes; 
                    break;
                case 4: 
                    prefShowGridLines = !prefShowGridLines; 
                    break;
            }
        }
        
        renderMenu(); 
    }

    private void handleConnectionAction() {
        int sel = menuManager.getSelection(); 
        if (sel == 0) {
            connectivityManager.startHotspot(); 
        } else if (sel == 1) {
            connectivityManager.startHomeWifi(); 
        } else if (sel == 2) {
            connectivityManager.stopNetworking();
        }
    }

    private void renderMenu() {
        String scn = "UNKNOWN"; 
        try { 
            scn = cameraManager.getCamera().getParameters().getSceneMode().toUpperCase(); 
        } catch(Exception e) {}
        
        menuManager.render(tvMenuTitle, tvPageNumbers, menuRows, menuLabels, menuValues, recipeManager, connectivityManager, prefShowFocusMeter, prefShowCinemaMattes, prefShowGridLines, scn);
    }

    @Override 
    public boolean onKeyDown(int k, KeyEvent e) { 
        if (isProcessing && (k == ScalarInput.ISV_KEY_S1_1 || k == ScalarInput.ISV_KEY_S1_2 || k == ScalarInput.ISV_KEY_S2)) {
            return true; 
        }
        return inputManager.handleKeyDown(k, e) || super.onKeyDown(k, e); 
    }
    
    @Override 
    public boolean onKeyUp(int k, KeyEvent e) { 
        if (isProcessing && (k == ScalarInput.ISV_KEY_S1_1 || k == ScalarInput.ISV_KEY_S1_2 || k == ScalarInput.ISV_KEY_S2)) {
            return true; 
        }
        return inputManager.handleKeyUp(k, e) || super.onKeyUp(k, e); 
    }
    
    @Override 
    protected void onResume() { 
        super.onResume(); 
        if (hasSurface) {
            cameraManager.open(mSurfaceView.getHolder()); 
        }
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); 
        uiHandler.post(liveUpdater); 
    }
    
    @Override 
    protected void onPause() { 
        super.onPause(); 
        // WAKELOCK FIX: Instantly kill all UI threads and pending delays to allow OS power off
        uiHandler.removeCallbacksAndMessages(null); 
        cameraManager.close(); 
        connectivityManager.stopNetworking(); 
        recipeManager.savePreferences(); 
        try { 
            unregisterReceiver(batteryReceiver); 
        } catch(Exception e) {} 
    }

    private void setHUDVisibility(int v) { 
        tvTopStatus.setVisibility(v); 
        llBottomBar.setVisibility(v); 
        tvBattery.setVisibility(v); 
        batteryIcon.setVisibility(v); 
        tvMode.setVisibility(v); 
        tvFocusMode.setVisibility(v); 
        tvReview.setVisibility(v); 
        
        if (focusMeter != null) {
            focusMeter.setVisibility(v == View.VISIBLE && cachedIsManualFocus ? View.VISIBLE : View.GONE);
        }
    }

    private void updateMainHUD() {
        Camera c = cameraManager.getCamera(); 
        if (c == null) {
            return;
        }
        
        Camera.Parameters p = c.getParameters(); 
        CameraEx.ParametersModifier pm = cameraManager.getCameraEx().createParametersModifier(p);
        
        RTLProfile prof = recipeManager.getCurrentProfile(); 
        String name = recipeManager.getRecipeNames().get(prof.lutIndex);
        String displayName = name.length() > 15 ? name.substring(0, 12) + "..." : name;
        
        if (!isProcessing) {
            tvTopStatus.setText("RTL " + (recipeManager.getCurrentSlot() + 1) + " [" + displayName + "]\n" + (isReady ? "READY" : "LOADING.."));
            tvTopStatus.setTextColor(mDialMode == DIAL_MODE_RTL ? Color.rgb(230, 50, 15) : Color.WHITE);
        }
        
        String sm = p.getSceneMode(); 
        if ("manual-exposure".equals(sm)) {
            tvMode.setText("M"); 
        } else if ("aperture-priority".equals(sm)) {
            tvMode.setText("A"); 
        } else if ("shutter-priority".equals(sm) || "shutter-speed-priority".equals(sm)) {
            tvMode.setText("S"); 
        } else if ("program-auto".equals(sm)) {
            tvMode.setText("P");
        } else {
            // Will now safely print out whatever custom scene mode is active
            tvMode.setText(sm != null ? sm.toUpperCase() : "SCN");
        }
        
        // CACHE FIX: Update variables so Focus Ring UI never has to query hardware directly
        cachedAperture = pm.getAperture() / 100.0f;
        
        Pair<Integer, Integer> ss = pm.getShutterSpeed(); 
        tvValShutter.setText(ss.first == 1 && ss.second != 1 ? ss.first + "/" + ss.second : ss.first + "\"");
        tvValAperture.setText(String.format("f%.1f", cachedAperture)); 
        tvValIso.setText(pm.getISOSensitivity() == 0 ? "ISO AUTO" : "ISO " + pm.getISOSensitivity());
        tvValEv.setText(String.format("%+.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        
        tvReview.setBackgroundColor(mDialMode == DIAL_MODE_REVIEW ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));
        tvValShutter.setTextColor(mDialMode == DIAL_MODE_SHUTTER ? Color.rgb(230, 50, 15) : Color.WHITE);
        tvValAperture.setTextColor(mDialMode == DIAL_MODE_APERTURE ? Color.rgb(230, 50, 15) : Color.WHITE);
        tvValIso.setTextColor(mDialMode == DIAL_MODE_ISO ? Color.rgb(230, 50, 15) : Color.WHITE);
        tvValEv.setTextColor(mDialMode == DIAL_MODE_EXPOSURE ? Color.rgb(230, 50, 15) : Color.WHITE);
        tvMode.setTextColor(mDialMode == DIAL_MODE_PASM ? Color.rgb(230, 50, 15) : Color.WHITE);
        
        String fm = p.getFocusMode();
        cachedIsManualFocus = "manual".equals(fm);
        
        if ("auto".equals(fm)) {
            tvFocusMode.setText("AF-S"); 
        } else if (cachedIsManualFocus) {
            tvFocusMode.setText("MF"); 
        } else if ("continuous-video".equals(fm) || "continuous-picture".equals(fm)) {
            tvFocusMode.setText("AF-C"); 
        } else {
            // STRING REVEAL: Will print exactly what proprietary name the Sony OS uses for DMF
            tvFocusMode.setText(fm != null ? fm.toUpperCase() : "AF");
        }
        
        tvFocusMode.setTextColor(mDialMode == DIAL_MODE_FOCUS ? Color.rgb(230, 50, 15) : Color.WHITE);
        
        if (focusMeter != null) {
            focusMeter.setVisibility(cachedIsManualFocus ? View.VISIBLE : View.GONE);
        }
        
        gridLines.setVisibility(prefShowGridLines ? View.VISIBLE : View.GONE); 
        cinemaMattes.setVisibility(prefShowCinemaMattes ? View.VISIBLE : View.GONE);
    }

    private void buildUI(FrameLayout rootLayout) {
        mainUIContainer = new FrameLayout(this); 
        rootLayout.addView(mainUIContainer, new FrameLayout.LayoutParams(-1, -1));
        
        gridLines = new GridLinesView(this); 
        mainUIContainer.addView(gridLines, new FrameLayout.LayoutParams(-1, -1));
        
        cinemaMattes = new CinemaMatteView(this); 
        mainUIContainer.addView(cinemaMattes, new FrameLayout.LayoutParams(-1, -1));
        
        tvTopStatus = new TextView(this); 
        tvTopStatus.setTextColor(Color.WHITE); 
        tvTopStatus.setTextSize(20); 
        tvTopStatus.setTypeface(Typeface.DEFAULT_BOLD); 
        tvTopStatus.setGravity(Gravity.CENTER); 
        tvTopStatus.setShadowLayer(4, 0, 0, Color.BLACK);
        
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); 
        topParams.setMargins(0, 15, 0, 0); 
        mainUIContainer.addView(tvTopStatus, topParams);
        
        LinearLayout rightBar = new LinearLayout(this); 
        rightBar.setOrientation(LinearLayout.VERTICAL); 
        rightBar.setGravity(Gravity.RIGHT);
        
        LinearLayout batteryArea = new LinearLayout(this); 
        batteryArea.setOrientation(LinearLayout.HORIZONTAL); 
        batteryArea.setGravity(Gravity.CENTER_VERTICAL);
        
        tvBattery = new TextView(this); 
        tvBattery.setTextColor(Color.WHITE); 
        tvBattery.setTextSize(18); 
        tvBattery.setTypeface(Typeface.DEFAULT_BOLD); 
        tvBattery.setPadding(0, 0, 10, 0); 
        batteryArea.addView(tvBattery);
        
        batteryIcon = new BatteryView(this); 
        batteryArea.addView(batteryIcon, new LinearLayout.LayoutParams(45, 22)); 
        rightBar.addView(batteryArea);
        
        tvReview = createSideTextIcon("▶"); 
        LinearLayout.LayoutParams rvParams = new LinearLayout.LayoutParams(-2, -2); 
        rvParams.setMargins(0, 20, 0, 0); 
        tvReview.setLayoutParams(rvParams); 
        rightBar.addView(tvReview);
        
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT); 
        rightParams.setMargins(0, 20, 30, 0); 
        mainUIContainer.addView(rightBar, rightParams);
        
        LinearLayout leftBar = new LinearLayout(this); 
        leftBar.setOrientation(LinearLayout.VERTICAL); 
        tvMode = createSideTextIcon("M"); 
        leftBar.addView(tvMode); 
        tvFocusMode = createSideTextIcon("AF-S"); 
        leftBar.addView(tvFocusMode);
        
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT); 
        leftParams.setMargins(20, 20, 0, 0); 
        mainUIContainer.addView(leftBar, leftParams);
        
        focusMeter = new AdvancedFocusMeterView(this); 
        FrameLayout.LayoutParams fmParams = new FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); 
        fmParams.setMargins(0, 0, 0, 100); 
        mainUIContainer.addView(focusMeter, fmParams);
        
        llBottomBar = new LinearLayout(this); 
        llBottomBar.setOrientation(LinearLayout.HORIZONTAL); 
        llBottomBar.setGravity(Gravity.CENTER); 
        
        tvValShutter = createBottomText(); 
        tvValAperture = createBottomText(); 
        tvValIso = createBottomText(); 
        tvValEv = createBottomText();
        
        llBottomBar.addView(tvValShutter); 
        llBottomBar.addView(tvValAperture); 
        llBottomBar.addView(tvValIso); 
        llBottomBar.addView(tvValEv);
        
        FrameLayout.LayoutParams botParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); 
        botParams.setMargins(0, 0, 0, 25); 
        mainUIContainer.addView(llBottomBar, botParams);
        
        afOverlay = new ProReticleView(this); 
        mainUIContainer.addView(afOverlay, new FrameLayout.LayoutParams(-1, -1));
        
        menuContainer = new LinearLayout(this); 
        menuContainer.setOrientation(LinearLayout.VERTICAL); 
        menuContainer.setBackgroundColor(Color.argb(250, 15, 15, 15)); 
        menuContainer.setPadding(20, 20, 20, 20); 
        
        LinearLayout menuHeaderLayout = new LinearLayout(this); 
        menuHeaderLayout.setOrientation(LinearLayout.HORIZONTAL); 
        menuHeaderLayout.setGravity(Gravity.CENTER_VERTICAL); 
        menuHeaderLayout.setPadding(10, 0, 10, 15);
        
        tvMenuTitle = new TextView(this); 
        tvMenuTitle.setTextSize(22); 
        tvMenuTitle.setTypeface(Typeface.DEFAULT_BOLD); 
        tvMenuTitle.setTextColor(Color.WHITE); 
        menuHeaderLayout.addView(tvMenuTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));
        
        LinearLayout pagesLayout = new LinearLayout(this); 
        pagesLayout.setOrientation(LinearLayout.HORIZONTAL); 
        pagesLayout.setGravity(Gravity.RIGHT);
        
        for(int i=0; i<4; i++) { 
            tvPageNumbers[i] = new TextView(this); 
            tvPageNumbers[i].setText(String.valueOf(i+1)); 
            tvPageNumbers[i].setTextSize(20); 
            tvPageNumbers[i].setTypeface(Typeface.DEFAULT_BOLD); 
            tvPageNumbers[i].setPadding(15, 0, 15, 0); 
            pagesLayout.addView(tvPageNumbers[i]); 
        }
        
        menuHeaderLayout.addView(pagesLayout, new LinearLayout.LayoutParams(-2, -2)); 
        menuContainer.addView(menuHeaderLayout);
        
        View headerDivider = new View(this); 
        headerDivider.setBackgroundColor(Color.GRAY); 
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, 2); 
        divParams.setMargins(0, 0, 0, 15); 
        menuContainer.addView(headerDivider, divParams);
        
        for (int i = 0; i < 7; i++) { 
            menuRows[i] = new LinearLayout(this); 
            menuRows[i].setOrientation(LinearLayout.HORIZONTAL); 
            menuRows[i].setGravity(Gravity.CENTER_VERTICAL); 
            menuRows[i].setPadding(10, 0, 10, 0); 
            menuContainer.addView(menuRows[i], new LinearLayout.LayoutParams(-1, 0, 1.0f));
            
            menuLabels[i] = new TextView(this); 
            menuLabels[i].setTextSize(18); 
            menuLabels[i].setTypeface(Typeface.DEFAULT_BOLD); 
            
            menuValues[i] = new TextView(this); 
            menuValues[i].setTextSize(18); 
            menuValues[i].setGravity(Gravity.RIGHT);
            
            menuRows[i].addView(menuLabels[i], new LinearLayout.LayoutParams(0, -2, 1.0f)); 
            menuRows[i].addView(menuValues[i], new LinearLayout.LayoutParams(-2, -2));
            
            if (i < 6) { 
                View divider = new View(this); 
                divider.setBackgroundColor(Color.DKGRAY); 
                menuContainer.addView(divider, new LinearLayout.LayoutParams(-1, 1)); 
            }
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
    }

    private void cycleDialMode(int dir) { 
        mDialMode = (mDialMode + dir + 8) % 8; 
        updateMainHUD(); 
    }
    
    private TextView createBottomText() { 
        TextView tv = new TextView(this); 
        tv.setTextSize(26); 
        tv.setTypeface(Typeface.DEFAULT_BOLD); 
        tv.setShadowLayer(4, 0, 0, Color.BLACK); 
        tv.setPadding(20, 0, 20, 0); 
        return tv; 
    }
    
    private TextView createSideTextIcon(String text) { 
        TextView tv = new TextView(this); 
        tv.setText(text); 
        tv.setTextColor(Color.WHITE); 
        tv.setTextSize(22); 
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); 
        tv.setPadding(25, 15, 25, 15); 
        tv.setBackgroundColor(Color.argb(140, 40, 40, 40)); 
        tv.setGravity(Gravity.CENTER); 
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); 
        lp.setMargins(0, 0, 0, 15); 
        tv.setLayoutParams(lp); 
        return tv; 
    }
    
    @Override 
    public void surfaceCreated(SurfaceHolder h) { 
        hasSurface = true; 
        cameraManager.open(h); 
    }
    
    @Override 
    public void surfaceDestroyed(SurfaceHolder h) { 
        hasSurface = false; 
        cameraManager.close(); 
    }
    
    @Override 
    public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
}