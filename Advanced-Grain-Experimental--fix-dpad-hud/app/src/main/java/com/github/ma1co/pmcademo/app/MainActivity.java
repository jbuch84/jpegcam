package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.util.Log;
import android.widget.Toast;

import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback,
    SonyCameraManager.CameraEventListener, InputManager.InputListener,
    ConnectivityManager.StatusUpdateListener, PlaybackController.HostCallback,
    LensCalibrationController.HostCallback, MenuController.HostCallback,
    HudController.HostCallback {

    // --- GLOBAL DEBUG FLAG ---
    // Set to true to see diagnostic Toasts, false for clean public release
    public static final boolean DEBUG_MODE = false;

    private SonyCameraManager cameraManager;
    private InputManager inputManager;
    private RecipeManager recipeManager;
    private MatrixManager matrixManager;
    private ConnectivityManager connectivityManager;
    
    
    private Typeface digitalFont; 
    
    private ImageProcessor mProcessor;
    private SonyFileScanner mScanner;

    private SurfaceView mSurfaceView;
    private boolean hasSurface = false;
    
    private FrameLayout mainUIContainer;
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
    
    // --- UNIVERSAL HUD VARIABLES ---
    private HudController hudController;


     // --- RGB MATRIX MATH ---
     // Converts hardware value (e.g., 1024) to a human percentage (e.g., 100)
    private int matrixToPercent(int hardwareValue) {
        return Math.round((hardwareValue / 1024.0f) * 100.0f);
    }      
    // Converts a human percentage (e.g., 100) back to the hardware value (e.g., 1024)
    private int percentToMatrix(int percentValue) {
        return Math.round((percentValue / 100.0f) * 1024.0f);
    }

    
    private BatteryView batteryIcon;
    private PlaybackController playbackController;
    private MenuController menuController;
    private boolean isProcessing = false;
    private boolean isReady = false;
    private int displayState = 0; 
    
    private boolean prefShowFocusMeter = true;
    private boolean prefShowCinemaMattes = false;
    private boolean prefShowGridLines = false;
    private int prefJpegQuality = 95;

    private LensProfileManager lensManager;
    private List<String> availableLenses = new ArrayList<String>();
    private int currentLensIndex = 0;
    
    private LensCalibrationController calibController;

    private float hardwareFocalLength = 0.0f;
    private boolean isNativeLensAttached = false;
    private boolean hasPhysicalPasmDial = false;
    private boolean isFullFrame = false;
    private BroadcastReceiver hardwareStateReceiver; // <--- ADD THIS LINE
    
    private float virtualAperture = 2.8f;
    private float virtualFocusRatio = 0.5f;
    
    private TextView tvCalibrationPrompt;
    
    private boolean cachedIsManualFocus = false;
    private float cachedAperture = 2.8f;
    private float cachedFocusRatio = 0.5f;
    
    // isMenuEditing thin proxy removed — use menuController.isEditingMode()
    
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

// --- MATRIX PRESET DATA ---
    private final String[] MATRIX_PRESET_NAMES = {"STANDARD", "GOLDEN HOUR", "PNW GREEN", "CINEMATIC", "BLEACH BYPASS", "AEROCHROME", "CUSTOM"};
    private final int[][] MATRIX_PRESET_VALUES = {
        {100, 0, 0, 0, 100, 0, 0, 0, 100},   // Standard
        {115, 5, 0, 5, 105, 0, 0, 0, 95},    // Golden Hour
        {95, 0, 0, 0, 110, 5, 0, 15, 105},   // PNW Green
        {110, -10, 0, -5, 100, 10, 0, 5, 115}, // Cinematic
        {130, 0, 0, 0, 130, 0, 0, 0, 130},   // Bleach Bypass
        {0, 140, 0, 100, 0, 0, 0, 0, 100}    // Aerochrome
    };
    private final String[] MATRIX_PRESET_NOTES = {
        "Identity Matrix. Zero color shift.",
        "Broadens the yellow spectrum. Pro Tip: If skin looks too yellow, drop R-G to 2%.",
        "Fuji-style vintage teals. Pairs best with an Amber (A2) White Balance shift.",
        "Professional Teal/Orange separation. Uses negative values to 'clean' the Red channel.",
        "High color density. WARNING: May clip highlights. Use -0.7 EV on camera.",
        "False-color Infrared swap. Turns foliage (Green) into candy-apple Red.",
        "Manual matrix override active. Row-sum balance not guaranteed."
    };


    private BroadcastReceiver sonyCameraReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.sony.scalar.database.avindex.action.AVINDEX_DATABASE_UPDATED".equals(action)) {
                if (mScanner != null) mScanner.checkNow();
            }
        }
    };

    private Runnable applySettingsRunnable = new Runnable() {
        @Override 
        public void run() { applyHardwareRecipe(); }
    };
    
    private Runnable liveUpdater = new Runnable() {
        @Override
        public void run() {
            if (displayState == 0 && !menuController.isOpen() && !playbackController.isActive() && !isProcessing && hasSurface) {
                if (cameraManager != null && cameraManager.getCamera() != null) {
                    boolean s1_1_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_1).status == 0;
                    boolean s1_2_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_2).status == 0;
                    
                    if (s1_1_free && s1_2_free) {
                        if (afOverlay != null && afOverlay.isPolling()) {
                            afOverlay.stopFocus(cameraManager.getCamera());
                            requestHudUpdate(); 
                        }
                        if (tvTopStatus != null && tvTopStatus.getVisibility() != View.VISIBLE) {
                            if (!calibController.isActive()) {
                                setHUDVisibility(View.VISIBLE);
                            }
                        }
                    }
                }
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
                        if (tvBattery != null) tvBattery.setText(pct + "%"); 
                        if (batteryIcon != null) batteryIcon.setLevel(pct); 
                    } 
                });
            }
        }
    };

    // --- FACTORY BURN SYSTEM ---
    private void factoryBurnMatrices() {
        if (matrixManager != null && matrixManager.getCount() == 0) {
            matrixManager.saveMatrix("STANDARD", new int[]{100, 0, 0, 0, 100, 0, 0, 0, 100}, "Identity Matrix. Zero color shift.");
            matrixManager.saveMatrix("GOLDEN HOUR", new int[]{115, 5, 0, 5, 105, 0, 0, 0, 95}, "Broadens yellow spectrum. Drop R-G to 5% if too yellow.");
            matrixManager.saveMatrix("PAC. NW GREEN", new int[]{95, 0, 0, 0, 110, 5, 0, 15, 105}, "Vintage teals. Pairs best with Amber (A2) shift.");
            matrixManager.saveMatrix("CINEMATIC", new int[]{110, -10, 0, -5, 100, 10, 0, 5, 115}, "Teal/Orange separation. Uses negative values to clean Reds.");
            matrixManager.saveMatrix("BLEACH BYPASS", new int[]{130, 0, 0, 0, 130, 0, 0, 0, 130}, "High density. WARNING: May clip highlights. Use -0.7 EV.");
            matrixManager.saveMatrix("AEROCHROME", new int[]{0, 140, 0, 100, 0, 0, 0, 0, 100}, "False-color Infrared swap. Turns Green foliage Red.");
            matrixManager.scanMatrices(); // Reload list after saving
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- AUTOMATIC HARDWARE SCANNER ---
        // --- UNIVERSAL FEATURE DETECTION ---
        // Instead of guessing model names, we ask the Android Kernel if the 
        // physical keypad matrix has a Mode Dial wired into it.
        boolean hasDialKey1 = android.view.KeyCharacterMap.deviceHasKey(624);
        boolean hasDialKey2 = android.view.KeyCharacterMap.deviceHasKey(ScalarInput.ISV_KEY_MODE_DIAL);
        
        hasPhysicalPasmDial = hasDialKey1 || hasDialKey2;
        android.util.Log.e("JPEG.CAM", "Universal Dial Detection: " + hasPhysicalPasmDial);
        
        // Force creation of our JPEGCAM folder skeleton immediately on boot
        Filepaths.buildAppStructure();
        
        File thumbsDir = new File(Filepaths.getDcimDir(), ".thumbnails");
        if (!thumbsDir.exists()) thumbsDir.mkdirs();

        SharedPreferences prefs = getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE);
        prefShowFocusMeter = prefs.getBoolean("focusMeter", true);
        prefShowCinemaMattes = prefs.getBoolean("cinemaMattes", false);
        prefShowGridLines = prefs.getBoolean("gridLines", false);
        prefJpegQuality = prefs.getInt("jpegQuality", 95);
        
        cameraManager = new SonyCameraManager(this);
        inputManager = new InputManager(this);
        recipeManager = new RecipeManager();
        matrixManager = new MatrixManager(); // <-- NEW
        matrixManager.scanMatrices();        // <-- NEW
        factoryBurnMatrices();               // <-- NEW: Generate defaults if empty!
        connectivityManager = new ConnectivityManager(this, this);

        lensManager = new LensProfileManager(this);
        availableLenses = lensManager.getAvailableLenses();
        
        recipeManager.loadPreferences();
        
        try {
            digitalFont = Typeface.createFromAsset(getAssets(), "fonts/digital-7.ttf");
        } catch (Exception e) {
            Log.e("JPEG.CAM", "Could not load custom font. Did you add it to assets/fonts/?");
            digitalFont = null;
        }
        
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
            @Override public void onPreloadStarted() { isReady = false; runOnUiThread(new Runnable() { public void run() { updateMainHUD(); } }); }
            @Override public void onPreloadFinished(boolean success) { isReady = true; runOnUiThread(new Runnable() { public void run() { updateMainHUD(); } }); }
            @Override public void onProcessStarted() { runOnUiThread(new Runnable() { public void run() { if (tvTopStatus != null) { tvTopStatus.setText("PROCESSING..."); tvTopStatus.setTextColor(Color.YELLOW); } } }); }
            @Override public void onProcessFinished(String res) { isProcessing = false; runOnUiThread(new Runnable() { public void run() { if (tvTopStatus != null) { tvTopStatus.setTextColor(Color.WHITE); } updateMainHUD(); } }); }
        });
        
        mScanner = new SonyFileScanner(this, new SonyFileScanner.ScannerCallback() {
            @Override 
            public boolean isReadyToProcess() { 
                RTLProfile p = recipeManager.getCurrentProfile();
                // --- FIXED: Added shadowToe, subtractiveSat, and halation to the trigger ---
                return isReady && !isProcessing && !calibController.isCalibrating() && 
                       (p.lutIndex != 0 || p.grain != 0 || p.vignette != 0 || 
                        p.rollOff != 0 || p.colorChrome != 0 || p.chromeBlue != 0 ||
                        p.shadowToe != 0 || p.subtractiveSat != 0 || p.halation != 0 ||
                        p.advancedGrainExperimental != 0); 
            }
            @Override 
            public void onNewPhotoDetected(final String path) { 
                processWhenFileReady(path);
            }
        });
        
        triggerLutPreload();
    }
    
    private void processWhenFileReady(final String path) {
        // Wrap the diagnostic in our global debug flag
        if (DEBUG_MODE) {
            android.widget.Toast.makeText(this, "File Detected! Waiting for write...", android.widget.Toast.LENGTH_SHORT).show();
        }
        
        final File f = new File(path);
        if (!f.exists()) return; 

        isProcessing = true; 
        runOnUiThread(new Runnable() { 
            public void run() { 
                if (tvTopStatus != null) {
                    tvTopStatus.setText("SAVING TO SD..."); 
                    tvTopStatus.setTextColor(Color.YELLOW); 
                }
                updateMainHUD(); 
            } 
        });
        
        final long[] lastSize = {-1};
        final int[] retries = {0};
        
        Runnable checker = new Runnable() {
            @Override
            public void run() {
                if (!f.exists()) {
                    isProcessing = false;
                    updateMainHUD();
                    return;
                }
                
                long currentSize = f.length();
                if (currentSize > 0 && currentSize == lastSize[0]) {
                    File outDir = Filepaths.getGradedDir();
                    mProcessor.processJpeg(path, outDir.getAbsolutePath(), recipeManager.getQualityIndex(), prefJpegQuality, recipeManager.getCurrentProfile());
                } else if (retries[0] < 30) {
                    lastSize[0] = currentSize;
                    retries[0]++;
                    uiHandler.postDelayed(this, 300);
                } else {
                    isProcessing = false;
                    updateMainHUD();
                }
            }
        };
        
        uiHandler.postDelayed(checker, 200);
    }

    private void triggerLutPreload() {
        RTLProfile p = recipeManager.getCurrentProfile();
        mProcessor.triggerLutPreload(recipeManager.getRecipePaths().get(p.lutIndex), recipeManager.getRecipeNames().get(p.lutIndex));
    }

    private void refreshRecipes() {
        List<String> oldPaths = new ArrayList<String>(recipeManager.getRecipePaths());
        String[] savedPaths = new String[10];
        
        for (int i = 0; i < 10; i++) {
            RTLProfile p = recipeManager.getProfile(i);
            if (p.lutIndex >= 0 && p.lutIndex < oldPaths.size()) savedPaths[i] = oldPaths.get(p.lutIndex);
            else savedPaths[i] = "NONE";
        }
        
        recipeManager.scanRecipes();
        List<String> newPaths = recipeManager.getRecipePaths();
        
        for (int i = 0; i < 10; i++) {
            int idx = newPaths.indexOf(savedPaths[i]);
            recipeManager.getProfile(i).lutIndex = (idx != -1) ? idx : 0;
        }
    }

    @Override 
    public void onShutterHalfPressed() {
        if (playbackController.isActive()) { playbackController.exit(); return; }
        if (menuController.isOpen()) { menuController.close(); return; }
        if (isProcessing) return; 
        
        // mDialMode = DIAL_MODE_RTL; <-- DELETED. Cursor memory is now permanent!
        
        if (displayState == 0 && !menuController.isOpen()) setHUDVisibility(View.GONE);
        if (cameraManager != null && cameraManager.getCamera() != null && !cachedIsManualFocus) {
            if (afOverlay != null) afOverlay.startFocus(cameraManager.getCamera()); 
        }
    }

    @Override
    public void onShutterHalfReleased() {
        if (displayState == 0 && !menuController.isOpen() && !playbackController.isActive()) setHUDVisibility(View.VISIBLE);
        if (afOverlay != null && cameraManager != null && cameraManager.getCamera() != null) {
            afOverlay.stopFocus(cameraManager.getCamera());
        }
        if (mScanner != null) {
            mScanner.start();
        }
    }

    @Override 
    public void onDeletePressed() { 
        finish(); 
    }

    @Override 
    public void onMenuPressed() {
        if (playbackController.isActive()) { playbackController.exit(); return; }
        if (isProcessing) return;
        if (menuController.isOpen()) menuController.close();
        else menuController.open();
    }

    private Runnable hudUpdateRunnable = new Runnable() {
        @Override public void run() { updateMainHUD(); }
    };

    private void requestHudUpdate() {
        uiHandler.removeCallbacks(hudUpdateRunnable);
        uiHandler.postDelayed(hudUpdateRunnable, 100);
    }

    @Override 
public void onEnterPressed() {
    if (playbackController.isActive()) { playbackController.exit(); return; }
    if (isProcessing) return;

    if (hudController.isActive()) {
        // --- VAULT HUD (MODE 10) ---
        // --- VAULT HUD (MODE 10) ---
        if (hudController.getMode() == 10) {
            if (menuController.isNamingMode()) {
                menuController.setNamingMode(false);
                String finalName = new String(menuController.getNameBuffer()).trim();
                recipeManager.saveSlotToVault(finalName);
                hudController.refreshVaultItems();
                for (int i = 0; i < hudController.getVaultItems().size(); i++) {
                    if (hudController.getVaultItems().get(i).profileName.equalsIgnoreCase(finalName)) { hudController.setVaultIndex(i); break; }
                }
                hudController.update(); return;
            } else if (menuController.isConfirmingDelete()) {
                if (hudController.getSelection() == 0) {
                    recipeManager.deleteVaultItem(hudController.getVaultIndex()); hudController.setVaultIndex(0);
                    hudController.refreshVaultItems();
                    if (!hudController.getVaultItems().isEmpty() && !hudController.getVaultItems().get(0).filename.equals("NONE")) recipeManager.previewVaultToSlot(hudController.getVaultItems().get(0).filename);
                    else recipeManager.resetCurrentSlot();
                    triggerLutPreload(); applyHardwareRecipe();
                    menuController.setConfirmingDelete(false); hudController.setSelection(1); hudController.update(); return;
                } else if (hudController.getSelection() == 1) {
                    menuController.setConfirmingDelete(false); hudController.setSelection(0); hudController.update(); return;
                }
            } else {
                if (hudController.getSelection() == 0) {
                    menuController.setNamingMode(true);
                    RTLProfile activeProfile = recipeManager.getCurrentProfile();
                    String currentName = (activeProfile != null) ? activeProfile.profileName : "";
                    if (currentName != null && !currentName.isEmpty() && !currentName.startsWith("SLOT ")) menuController.fillNameBuffer(currentName);
                    else menuController.resetNameBuffer();
                    menuController.resetNameCursor(); hudController.update(); return;
                } else if (hudController.getSelection() == 1) { 
                    // NEW: Explicitly force the highlighted Vault recipe to load into memory!
                    if (!hudController.getVaultItems().isEmpty() && !hudController.getVaultItems().get(hudController.getVaultIndex()).filename.equals("NONE")) {
                        recipeManager.previewVaultToSlot(hudController.getVaultItems().get(hudController.getVaultIndex()).filename);
                        triggerLutPreload(); 
                        applyHardwareRecipe();
                    }
                    recipeManager.savePreferences(); 
                    hudController.close(); 
                    return;
                } else if (hudController.getSelection() == 2) {
                    recipeManager.resetCurrentSlot();
                    triggerLutPreload(); // Ensure the hardware actually clears the current LUT
                    applyHardwareRecipe();
                    hudController.close(); // NEW: Exit HUD immediately after reset
                    return;
                } else if (hudController.getSelection() == 3) {
                    if (!hudController.getVaultItems().isEmpty() && !hudController.getVaultItems().get(hudController.getVaultIndex()).filename.equals("NONE")) {
                        menuController.setConfirmingDelete(true); hudController.setSelection(1); hudController.update(); return;
                    }
                }
            }
            
            // Clean up UI if we exited the HUD naturally (like hitting Confirm Load)
            if (!hudController.isActive()) { 
                hudController.hideOverlays(); // Safely clear UI without firing redundant close callbacks
                menuController.getContainer().setVisibility(View.VISIBLE); 
                menuController.refreshDisplay(); 
            }
            return;
        }

        if (hudController.getMode() == 0 && hudController.getSelection() == -1) {
            RTLProfile p = recipeManager.getCurrentProfile();
            if (!menuController.isNamingMode()) {
                for (int i = 0; i < matrixManager.getCount(); i++) {
                    int[] existing = matrixManager.getValues(i); boolean isMatch = true;
                    for (int j = 0; j < 9; j++) if (p.advMatrix[j] != existing[j]) { isMatch = false; break; }
                    if (isMatch) { tvTopStatus.setText("ALREADY SAVED: " + matrixManager.getNames().get(i)); tvTopStatus.setTextColor(Color.GREEN); return; }
                }
            }
            menuController.setNamingMode(!menuController.isNamingMode());
            if (menuController.isNamingMode()) { menuController.resetNameBuffer(); menuController.resetNameCursor(); hudController.update();
            } else { String finalName = new String(menuController.getNameBuffer()).trim(); if (finalName.isEmpty()) finalName = "CUSTOM"; hudController.saveCustomMatrix(finalName); }
            return;
        }

        // Standard HUD exit — controller hides overlays and fires onHudClosed callback
        hudController.close();
        recipeManager.savePreferences();
        return;
    }
        
        if (menuController.dispatchHudLaunch()) return;
        if (calibController.handleEnter()) return;

        if (!menuController.isOpen()) {
            if (mDialMode == DIAL_MODE_REVIEW) {
                playbackController.enter();
            } else if (mDialMode == DIAL_MODE_FOCUS && cachedIsManualFocus) {
                calibController.beginWaiting();
                setHUDVisibility(View.GONE);
                if (focusMeter != null) focusMeter.setVisibility(View.VISIBLE);
            } else {
                displayState = (displayState == 0) ? 1 : 0;
                mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                updateMainHUD();
            }
        } else {
            menuController.handleEnter();
        }
    }

    @Override
    public void onUpPressed() {
        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            char[] buf = menuController.getNameBuffer();
            int pos = menuController.getNameCursorPos();
            int idx = MenuController.CHARSET.indexOf(buf[pos]);
            if (idx == -1) idx = 0;
            idx += 1;
            if (idx >= MenuController.CHARSET.length()) idx = 0;
            buf[pos] = MenuController.CHARSET.charAt(idx);
            hudController.update();
            return;
        }
        
        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleUp(); return; }
        
        if (isProcessing || calibController.isWaiting()) return;
        
        if (calibController.handleUp()) return;

        if (menuController.isOpen()) { menuController.handleUp(); return; }
        navigateHomeSpatial(ScalarInput.ISV_KEY_UP);
    }

    @Override
    public void onDownPressed() {
        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            char[] buf = menuController.getNameBuffer();
            int pos = menuController.getNameCursorPos();
            int idx = MenuController.CHARSET.indexOf(buf[pos]);
            if (idx == -1) idx = 0;
            idx -= 1;
            if (idx < 0) idx = MenuController.CHARSET.length() - 1;
            buf[pos] = MenuController.CHARSET.charAt(idx);
            hudController.update();
            return;
        }
        
        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleDown(); return; }
        
        if (isProcessing) return;
        
        if (calibController.handleDown()) return;

        if (menuController.isOpen()) { menuController.handleDown(); return; }
        navigateHomeSpatial(ScalarInput.ISV_KEY_DOWN);
    }

    @Override
    public void onLeftPressed() {
        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            menuController.advanceNameCursor(-1);
            hudController.update();
            return;
        }
        
        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleLeft(); return; }
        if (isProcessing) return;
        if (calibController.handleLeft()) return;

        if (menuController.isOpen()) { menuController.handleLeft(); return; }
        if (!playbackController.isActive() && mDialMode == DIAL_MODE_FOCUS && lensManager != null && lensManager.isCurrentProfileManual()) {
            virtualFocusRatio = Math.max(0.0f, virtualFocusRatio - 0.02f);
            if (focusMeter != null) focusMeter.update(virtualFocusRatio, virtualAperture, lensManager.getCurrentFocalLength(), false, lensManager.getCurrentPoints(), getCircleOfConfusion());
        } else if (playbackController.isActive()) {
            playbackController.navigate(-1);
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_LEFT);
        }
    }

    @Override
    public void onRightPressed() {
        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            menuController.advanceNameCursor(1);
            hudController.update();
            return;
        }
        
        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleRight(); return; }
        if (isProcessing) return;
        if (calibController.handleRight()) return;
        if (menuController.isOpen()) { menuController.handleRight(); return; } 
        
        if (!playbackController.isActive() && mDialMode == DIAL_MODE_FOCUS && lensManager != null && lensManager.isCurrentProfileManual()) {
            virtualFocusRatio = Math.min(1.0f, virtualFocusRatio + 0.02f);
            if (focusMeter != null) focusMeter.update(virtualFocusRatio, virtualAperture, lensManager.getCurrentFocalLength(), false, lensManager.getCurrentPoints(), getCircleOfConfusion());
        } else if (playbackController.isActive()) {
            playbackController.navigate(1);
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_RIGHT);
        }
    }

    @Override
    public void onCustomButtonPressed() {
        // Do nothing if we are in a menu, looking at photos, or processing
        if (playbackController.isActive() || menuController.isOpen() || isProcessing || calibController.isCalibrating()) return;

        // Simply jump the HUD cursor directly to the ISO slot
        mDialMode = DIAL_MODE_ISO;
        updateMainHUD();
    }
    
    @Override 
    public void onFrontDialRotated(int direction) { 
        // If UI is open, act like a normal scroll wheel
        if (hudController.isActive() || playbackController.isActive() || menuController.isOpen()) { onControlWheelRotated(direction); return; }
        
        // If shooting, forcefully change Shutter Speed
        if (!isProcessing && cameraManager != null && cameraManager.getCameraEx() != null) {
            if (direction > 0) cameraManager.getCameraEx().incrementShutterSpeed();
            else cameraManager.getCameraEx().decrementShutterSpeed();
            updateMainHUD();
        }
    }

    @Override 
    public void onRearDialRotated(int direction) { 
        // If UI is open, act like a normal scroll wheel
        if (hudController.isActive() || playbackController.isActive() || menuController.isOpen()) { onControlWheelRotated(direction); return; }
        
        // If shooting, forcefully change Aperture
        if (!isProcessing && cameraManager != null && cameraManager.getCameraEx() != null) {
            if (cachedIsManualFocus && lensManager != null && lensManager.isCurrentProfileManual()) {
                adjustVirtualAperture(direction);
            } else {
                if (direction > 0) cameraManager.getCameraEx().incrementAperture();
                else cameraManager.getCameraEx().decrementAperture();
            }
            updateMainHUD();
        }
    }

    @Override 
    public void onControlWheelRotated(int direction) { 
        // --- NEW: INTERCEPT WHEEL TURNS FOR MATRIX NAMING ---
        if (hudController.isActive() && (hudController.getMode() == 0 || hudController.getMode() == 10) && menuController.isNamingMode()) {
            char[] buf = menuController.getNameBuffer();
            int pos = menuController.getNameCursorPos();
            int idx = MenuController.CHARSET.indexOf(buf[pos]);
            if (idx == -1) idx = 0;
            idx = (idx + direction + MenuController.CHARSET.length()) % MenuController.CHARSET.length();
            buf[pos] = MenuController.CHARSET.charAt(idx);
            hudController.update();
            return;
        }
        
        if (hudController.isActive() && !menuController.isNamingMode()) { hudController.handleDial(direction); return; }
        
        if (playbackController.isActive()) { 
            playbackController.navigate(direction); 
        } else if (menuController.isOpen()) {
            menuController.handleDial(direction);
        } else if (!isProcessing) {
            handleHardwareInput(direction); 
        }
    }

    private void navigateHomeSpatial(int keyCode) {
        switch (mDialMode) {
            case DIAL_MODE_SHUTTER:
                if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_APERTURE;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_FOCUS;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_FOCUS; 
                break;
            case DIAL_MODE_APERTURE:
                if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_SHUTTER;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_ISO;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_PASM; 
                break;
            case DIAL_MODE_ISO:
                if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_APERTURE;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_EXPOSURE;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_REVIEW; 
                break;
            case DIAL_MODE_EXPOSURE:
                if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_ISO;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_REVIEW;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_REVIEW; 
                break;
            case DIAL_MODE_REVIEW: 
                if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_EXPOSURE;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_RTL;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_EXPOSURE; 
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_RTL; 
                break;
            case DIAL_MODE_RTL: 
                if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_PASM;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_REVIEW;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_PASM; 
                break;
            case DIAL_MODE_PASM: 
                if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_FOCUS;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_RTL;
                else if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_RTL;
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_FOCUS; 
                break;
            case DIAL_MODE_FOCUS: 
                if (keyCode == ScalarInput.ISV_KEY_UP) mDialMode = DIAL_MODE_PASM;
                else if (keyCode == ScalarInput.ISV_KEY_DOWN) mDialMode = DIAL_MODE_SHUTTER;
                else if (keyCode == ScalarInput.ISV_KEY_RIGHT) mDialMode = DIAL_MODE_PASM; 
                else if (keyCode == ScalarInput.ISV_KEY_LEFT) mDialMode = DIAL_MODE_SHUTTER; 
                break;
        }
        updateMainHUD(); 
    }
    


    
    private void autoEquipMatchingLens(float hwFocal) {
        availableLenses = lensManager.getAvailableLenses();
        String matchedLens = null;
        for (String lens : availableLenses) {
            if (lens.startsWith("e") || lens.startsWith("E")) {
                if (lens.contains((int)hwFocal + "mm")) {
                    matchedLens = lens;
                    break;
                }
            }
        }
        if (matchedLens != null) {
            currentLensIndex = availableLenses.indexOf(matchedLens);
            lensManager.loadProfileFromFile(matchedLens);
        } else {
            lensManager.clearCurrentProfile(); 
        }
    }
    
    private void adjustVirtualAperture(int direction) {
        float[] stops = {1.0f, 1.2f, 1.4f, 1.8f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f};
        int currentIndex = 0;
        float minDiff = Float.MAX_VALUE;
        
        for (int i = 0; i < stops.length; i++) {
            float diff = Math.abs(stops[i] - virtualAperture);
            if (diff < minDiff) {
                minDiff = diff;
                currentIndex = i;
            }
        }
        
        currentIndex += direction;
        if (currentIndex < 0) currentIndex = 0;
        if (currentIndex >= stops.length) currentIndex = stops.length - 1;
        
        virtualAperture = stops[currentIndex];
        
        if (lensManager != null && virtualAperture < lensManager.currentMaxAperture) {
            virtualAperture = lensManager.currentMaxAperture;
        }
    }

    private void handleHardwareInput(int d) {
        if (calibController.handleDial(d)) return;

        if (cameraManager == null || cameraManager.getCamera() == null || cameraManager.getCameraEx() == null) return;
        
        Camera c = cameraManager.getCamera(); 
        CameraEx cx = cameraManager.getCameraEx();
        Camera.Parameters p = c.getParameters(); 
        CameraEx.ParametersModifier pm = cx.createParametersModifier(p);
        
        if (mDialMode == DIAL_MODE_RTL) { 
            recipeManager.setCurrentSlot(recipeManager.getCurrentSlot() + d); 
            applyHardwareRecipe(); 
            triggerLutPreload(); 
        }
        else if (mDialMode == DIAL_MODE_SHUTTER) { 
            if (d > 0) cx.incrementShutterSpeed(); else cx.decrementShutterSpeed(); 
        }
        else if (mDialMode == DIAL_MODE_APERTURE) { 
            if (cachedIsManualFocus && lensManager != null && lensManager.isCurrentProfileManual()) {
                adjustVirtualAperture(d);
            } else {
                if (d > 0) cx.incrementAperture(); else cx.decrementAperture(); 
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
            if (hasPhysicalPasmDial) return; // <-- NEW: Prevent software override on A7II!
            
            List<String> valid = new ArrayList<String>(); 
            String[] desired = {"program-auto", "aperture-priority", "shutter-priority", "shutter-speed", "manual-exposure","auto"};
            List<String> supported = p.getSupportedSceneModes();
            if (supported != null) {
                for (String s : desired) if (supported.contains(s)) valid.add(s); 
                if (!valid.isEmpty()) {
                    int idx = valid.indexOf(p.getSceneMode()); 
                    if (idx == -1) idx = 0; 
                    p.setSceneMode(valid.get((idx + d + valid.size()) % valid.size())); 
                    try { c.setParameters(p); } catch (Exception e) {}
                }
            }
        }
        else if (mDialMode == DIAL_MODE_FOCUS) {
            List<String> hwModes = p.getSupportedFocusModes();
            List<String> virtualModes = new ArrayList<String>();
            
            if (hwModes != null) {
                for (String m : hwModes) {
                    if (m.equals("manual")) {
                        availableLenses = lensManager.getAvailableLenses();
                        virtualModes.add("manual_unmapped"); 
                        for (String l : availableLenses) {
                            virtualModes.add("manual_" + l);
                        }
                    } else {
                        virtualModes.add(m);
                    }
                }
            }
            
            String currentVirtual = p.getFocusMode();
            if ("manual".equals(currentVirtual)) {
                 String loaded = lensManager.getCurrentLensName();
                 if (loaded == null || loaded.equals("Unmapped Lens") || !availableLenses.contains(loaded)) {
                     currentVirtual = "manual_unmapped";
                 } else {
                     currentVirtual = "manual_" + loaded;
                 }
            }
            
            int idx = virtualModes.indexOf(currentVirtual);
            if (idx == -1) idx = 0;
            
            String nextVirtual = virtualModes.get((idx + d + virtualModes.size()) % virtualModes.size());
            
            if (nextVirtual.startsWith("manual_")) {
                p.setFocusMode("manual");
                String lensFile = nextVirtual.replace("manual_", "");
                if (!lensFile.equals("unmapped")) {
                    currentLensIndex = availableLenses.indexOf(lensFile);
                    lensManager.loadProfileFromFile(lensFile);
                    if (lensManager.isCurrentProfileManual()) {
                        virtualAperture = lensManager.currentMaxAperture;
                        virtualFocusRatio = 0.5f;
                    }
                } else {
                    lensManager.clearCurrentProfile();
                }
            } else {
                p.setFocusMode(nextVirtual);
            }
            try { c.setParameters(p); } catch (Exception e) {}
        }
        updateMainHUD(); 
    }
    
    // --- NEW: KELVIN CYCLE HELPER ---

    private void applyHardwareRecipe() {
        if (cameraManager == null || cameraManager.getCamera() == null) return;
        HardwareRecipeApplier.apply(cameraManager.getCamera(), recipeManager.getCurrentProfile());
    }

    // getWbString() removed — logic consolidated into HardwareRecipeApplier.

    private void setAutoPowerOffMode(boolean enable) {
        String mode = enable ? "APO/NORMAL" : "APO/NO";
        Intent intent = new Intent();
        intent.setAction("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", mode);
        sendBroadcast(intent);
    }


    



    // saveCurrentCustomMatrix removed — delegated to hudController.saveCustomMatrix()

    private void launchHudMode(int mode, int defaultSelection) {
        mainUIContainer.setVisibility(View.VISIBLE);
        setHUDVisibility(View.GONE);
        hudController.launch(mode, defaultSelection);
    }

    private void launchHudMode(int mode) { launchHudMode(mode, 0); }
    

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
        if (digitalFont != null) tvTopStatus.setTypeface(digitalFont);
        else tvTopStatus.setTypeface(Typeface.DEFAULT_BOLD); 
        tvTopStatus.setGravity(Gravity.CENTER);
        
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); 
        topParams.setMargins(0, 15, 0, 0); 
        // REVERT to mainUIContainer
        mainUIContainer.addView(tvTopStatus, topParams);
        
        LinearLayout rightBar = new LinearLayout(this); 
        rightBar.setOrientation(LinearLayout.VERTICAL); 
        rightBar.setGravity(Gravity.RIGHT);
        
        LinearLayout batteryArea = new LinearLayout(this); 
        batteryArea.setOrientation(LinearLayout.HORIZONTAL);
        batteryArea.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        
        tvBattery = new TextView(this); 
        tvBattery.setTextColor(Color.rgb(227, 69, 20)); // JPEG.CAM Orange
        tvBattery.setTextSize(14); 
        tvBattery.setTypeface(Typeface.DEFAULT_BOLD); 
        tvBattery.setPadding(0, 0, 5, 0); 
        batteryArea.addView(tvBattery); // <--- THIS line was missing!
        
        batteryIcon = new BatteryView(this); 
        batteryArea.addView(batteryIcon, new LinearLayout.LayoutParams(28, 12)); 
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
        FrameLayout.LayoutParams fmParams = new FrameLayout.LayoutParams(-1, 140, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); 
        fmParams.setMargins(0, 0, 0, 70); 
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
        
        tvCalibrationPrompt = new TextView(this); 
        tvCalibrationPrompt.setTextColor(Color.WHITE); 
        tvCalibrationPrompt.setTextSize(18); 
        tvCalibrationPrompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); 
        tvCalibrationPrompt.setGravity(Gravity.CENTER); 
        tvCalibrationPrompt.setBackgroundColor(Color.argb(200, 0, 0, 0)); 
        tvCalibrationPrompt.setPadding(10, 15, 10, 15);
        tvCalibrationPrompt.setVisibility(View.GONE);
        
        FrameLayout.LayoutParams cpParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); 
        cpParams.setMargins(0, 20, 0, 0); 
        mainUIContainer.addView(tvCalibrationPrompt, cpParams);

        // Calibration controller — receives the prompt view it needs to render into
        calibController = new LensCalibrationController(tvCalibrationPrompt, this);

        // Menu controller — builds and owns its view tree
        menuController = new MenuController(this, rootLayout, this);

        // Playback viewer — PlaybackController builds and owns its views
        playbackController = new PlaybackController(this, rootLayout, this);

        // HUD controller — builds and owns its overlay views
        hudController = new HudController(this, mainUIContainer, this);
    }


    private TextView createBottomText() { 
        TextView tv = new TextView(this); 
        tv.setTextSize(26); 
        if (digitalFont != null) tv.setTypeface(digitalFont);
        else tv.setTypeface(Typeface.DEFAULT_BOLD); 
        tv.setShadowLayer(4, 0, 0, Color.BLACK); 
        tv.setPadding(20, 0, 20, 0); 
        return tv; 
    }
    
    private TextView createSideTextIcon(String text) { 
        TextView tv = new TextView(this); 
        tv.setText(text); 
        tv.setTextColor(Color.WHITE); 
        tv.setTextSize(22); 
        if (digitalFont != null) tv.setTypeface(digitalFont);
        else tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); 
        tv.setPadding(25, 15, 25, 15); 
        tv.setBackgroundColor(Color.argb(140, 40, 40, 40)); 
        tv.setGravity(Gravity.CENTER); 
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); 
        lp.setMargins(0, 0, 0, 15); 
        tv.setLayoutParams(lp); 
        return tv; 
    }
    
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // --- 1. THE PASM DIAL SHIELD ---
        if (keyCode == 624 || keyCode == ScalarInput.ISV_KEY_MODE_DIAL || 
           (keyCode >= ScalarInput.ISV_KEY_MODE_INVALID && keyCode <= ScalarInput.ISV_KEY_MODE_CUSTOM3)) {
            
            if (action == android.view.KeyEvent.ACTION_DOWN) {
                if (!hasPhysicalPasmDial) hasPhysicalPasmDial = true;
                if (cameraManager != null) onHardwareStateChanged();
            }
            return true; // Bouncer defeated: Sony OS never sees the dial turn
        }

        // --- 2. THE PLAYBACK BUTTON HIJACK ---
        if (keyCode == ScalarInput.ISV_KEY_PLAY || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {
            
            // We only trigger our logic when the user RELEASES the button (ACTION_UP)
            // But we return 'true' for BOTH press and release to swallow it entirely.
            if (action == android.view.KeyEvent.ACTION_UP) {
                if (!isProcessing) {
                    if (playbackController.isActive()) playbackController.exit(); 
                    else if (!menuController.isOpen()) playbackController.enter();
                }
            }
            return true; // Bouncer defeated: Native gallery will not open
        }

        // Pass all other normal buttons (D-Pad, Center button, etc.) down to your normal listeners
        return super.dispatchKeyEvent(event);
    }
    
    @Override

    public boolean onKeyDown(int k, android.view.KeyEvent e) {

        // UNIVERSAL CRASH PROTECTION: Swallow dial events on ALL cameras

        if (k == 624 || k == ScalarInput.ISV_KEY_MODE_DIAL ||
           (k >= ScalarInput.ISV_KEY_MODE_INVALID && k <= ScalarInput.ISV_KEY_MODE_CUSTOM3)) {
           
            // AUTO-DISCOVERY: If boot detection failed but they turned a physical dial,
            // lock out the software wheel permanently for this session!
            if (!hasPhysicalPasmDial) hasPhysicalPasmDial = true;
           
            if (cameraManager != null) onHardwareStateChanged();
            return true; // Prevents the OS from force-closing the app
        }
       
        if (isProcessing && (k == ScalarInput.ISV_KEY_S1_1 || k == ScalarInput.ISV_KEY_S1_2 || k == ScalarInput.ISV_KEY_S2)) return true;

        // --- FIXED: Added standard Android keycode ---

        if (k == ScalarInput.ISV_KEY_PLAY || k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {
            if (playbackController.isActive()) playbackController.exit();
            else if (!menuController.isOpen() && !isProcessing) playbackController.enter();
            return true; // Swallow the press
        }

        if (inputManager != null) return inputManager.handleKeyDown(k, e) || super.onKeyDown(k, e);
        return super.onKeyDown(k, e);
    }

    @Override 
    public boolean onKeyUp(int k, android.view.KeyEvent e) { 
        // UNIVERSAL CRASH PROTECTION: Swallow dial events on ALL cameras
        if (k == 624 || k == ScalarInput.ISV_KEY_MODE_DIAL || 
           (k >= ScalarInput.ISV_KEY_MODE_INVALID && k <= ScalarInput.ISV_KEY_MODE_CUSTOM3)) {
            return true; 
        }
        
        // Protect shutter inputs while processing
        if (isProcessing && (k == ScalarInput.ISV_KEY_S1_1 || k == ScalarInput.ISV_KEY_S1_2 || k == ScalarInput.ISV_KEY_S2)) return true; 

        // --- CRITICAL: Swallow the release event so the Sony OS does nothing ---
        if (k == ScalarInput.ISV_KEY_PLAY || k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {
            return true; 
        }
        
        // Pass everything else down the chain
        if (inputManager != null) return inputManager.handleKeyUp(k, e) || super.onKeyUp(k, e); 
        return super.onKeyUp(k, e);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // REMOVED: cameraManager.start() which was causing the build error!
        
        // --- PREVENT PASM DIAL CRASH ON A7II ---
        // Register a receiver to swallow Sony's internal hardware state broadcasts
        // This stops ScalarABlackLauncher from force-closing the app.
        if (hardwareStateReceiver == null) {
            hardwareStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    android.util.Log.e("JPEG.CAM", "Hardware State Broadcast Intercepted!");
                    if (cameraManager != null) onHardwareStateChanged();
                }
            };
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.sony.scalar.hardware.action.MODE_DIAL_CHANGED");
        filter.addAction("com.android.server.DAConnectionManagerService.HardwareStateChanged");
        registerReceiver(hardwareStateReceiver, filter);
    }
    
    @Override 
    protected void onPause() { 
        super.onPause(); 
        uiHandler.removeCallbacksAndMessages(null); 
        
        // --- NEW: Release the A7II hardware dial lock ---
        if (hardwareStateReceiver != null) {
            try {
                unregisterReceiver(hardwareStateReceiver);
            } catch (Exception e) {}
        }
        
        if (cameraManager != null && cameraManager.getCamera() != null) {
            try {
                Camera c = cameraManager.getCamera();
                Camera.Parameters p = c.getParameters();
                
                // --- 1. SAVE PASM MODE ---
                String currentPasm = p.getSceneMode();
                if (currentPasm != null) {
                    getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE)
                        .edit().putString("savedPasmMode", currentPasm).apply();
                }
                
                // --- 2. CLEAN FOCUS RESTORE ---
                // Put the lens back to exactly how the user had it.
                if (menuController.getSavedFocusMode() != null) {
                    p.setFocusMode(menuController.getSavedFocusMode());
                } else {
                    p.setFocusMode("auto"); // Failsafe so stock OS doesn't get stuck
                }
                
                // --- 3. ZERO OUT HARDWARE HACKS ---
                if (p.get("picture-effect") != null) p.set("picture-effect", "off");
                if (p.get("rgb-matrix-mode") != null) p.set("rgb-matrix-mode", "false");
                if (p.get("pro-color-mode") != null) p.set("pro-color-mode", "off");
                if (p.get("sharpness-gain-mode") != null) p.set("sharpness-gain-mode", "false");
                if (p.get("white-balance-shift-mode") != null) p.set("white-balance-shift-mode", "false");
                if (p.get("rgb-matrix") != null) p.set("rgb-matrix", "256,0,0,0,256,0,0,0,256");
                if (p.get("lens-correction-shading-color-red") != null) p.set("lens-correction-shading-color-red", "0");
                if (p.get("lens-correction-shading-color-blue") != null) p.set("lens-correction-shading-color-blue", "0");
                
                if (p.get("color-depth-red") != null) {
                    p.set("color-depth-red", "0"); p.set("color-depth-green", "0");
                    p.set("color-depth-blue", "0"); p.set("color-depth-cyan", "0");
                    p.set("color-depth-magenta", "0"); p.set("color-depth-yellow", "0");
                }
                
                c.setParameters(p);
                Log.d("JPEG.CAM", "Successfully saved mode, zeroed hardware, and restored focus.");
                Thread.sleep(200);
            } catch (Exception e) {}
        }
        
        if (cameraManager != null) cameraManager.close(); 
        try { 
            unregisterReceiver(sonyCameraReceiver); 
            unregisterReceiver(batteryReceiver); 
        } catch (Exception e) {}
        
        if (connectivityManager != null) connectivityManager.stopNetworking(); 
        if (recipeManager != null) recipeManager.savePreferences(); 
        setAutoPowerOffMode(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        System.exit(0); // Restored to clear memory leaks on shutdown
    }
    
    private void setHUDVisibility(int v) { 
        if (tvTopStatus != null) {
            // FIX: If processing, FORCE visible. Otherwise, obey the display button.
            tvTopStatus.setVisibility(isProcessing ? View.VISIBLE : v); 
        }
        if (llBottomBar != null) llBottomBar.setVisibility(v); 
        if (tvBattery != null) tvBattery.setVisibility(v); 
        if (batteryIcon != null) batteryIcon.setVisibility(v); 
        if (tvMode != null) tvMode.setVisibility(v); 
        if (tvFocusMode != null) tvFocusMode.setVisibility(v); 
        if (tvReview != null) tvReview.setVisibility(v); 
        if (focusMeter != null) {
            focusMeter.setVisibility((v == View.VISIBLE && cachedIsManualFocus && prefShowFocusMeter) ? View.VISIBLE : View.GONE);
        }
    }

    private void updateMainHUD() {
        if (cameraManager == null || cameraManager.getCamera() == null) return;
        
        // --- 1. CLEAN DISPLAY CHECK ---
        if (hudController.isActive()) {
            // Hide the bottom bars and icons, but keep the Top Status bar for Preset names
            setHUDVisibility(View.GONE); 
            if (tvTopStatus != null) tvTopStatus.setVisibility(View.VISIBLE); 
            
            if (focusMeter != null) focusMeter.setVisibility(View.GONE);
            if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.GONE);
            return; 
        } else {
            // NORMAL STATE: Explicitly force the HUD to reappear
            setHUDVisibility(View.VISIBLE);
            if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.GONE);
        }
        
        // --- 2. GATHER HARDWARE DATA ---
        Camera c = cameraManager.getCamera(); 
        Camera.Parameters p = c.getParameters(); 
        CameraEx.ParametersModifier pm = cameraManager.getCameraEx().createParametersModifier(p);
        
        RTLProfile prof = recipeManager.getCurrentProfile(); 
        String name = recipeManager.getRecipeNames().get(prof.lutIndex);
        String displayName = name.length() > 15 ? name.substring(0, 12) + "..." : name;
        
        String customName = prof.profileName != null ? prof.profileName.trim() : ("RECIPE " + (recipeManager.getCurrentSlot() + 1));
        if (customName.isEmpty()) customName = "RECIPE " + (recipeManager.getCurrentSlot() + 1);
        
        // --- 3. UPDATE TEXT FIELDS ---
        if (!isProcessing && tvTopStatus != null) {
            // --- FIXED: Clearer Identity ---
            int slotNum = recipeManager.getCurrentSlot() + 1;
            tvTopStatus.setText("SLOT " + slotNum + ": " + customName + "\n" + (isReady ? "READY" : "LOADING.."));
            
            if (mDialMode == DIAL_MODE_RTL) {
                tvTopStatus.setTextColor(Color.WHITE); 
            } else if (isReady) {
                tvTopStatus.setTextColor(Color.rgb(0, 230, 118)); 
            } else {
                tvTopStatus.setTextColor(Color.rgb(227, 69, 20)); 
            }
        }
        
        String sm = p.getSceneMode(); 
        if (tvMode != null) {
            if ("manual-exposure".equals(sm)) tvMode.setText("M"); 
            else if ("aperture-priority".equals(sm)) tvMode.setText("A"); 
            else if ("shutter-priority".equals(sm) || "shutter-speed".equals(sm)) tvMode.setText("S"); 
            else if ("program-auto".equals(sm)) tvMode.setText("P");
            else if ("auto".equals(sm)) tvMode.setText("AUTO");
            else tvMode.setText(sm != null ? sm.toUpperCase() : "SCN");
        }
        
        cachedAperture = pm.getAperture() / 100.0f;
        Pair<Integer, Integer> ss = pm.getShutterSpeed(); 
        
        if (tvValAperture != null) {
            if (cachedIsManualFocus && lensManager != null && lensManager.isCurrentProfileManual()) {
                tvValAperture.setText(String.format("f%.1f", virtualAperture)); 
            } else {
                tvValAperture.setText(String.format("f%.1f", cachedAperture)); 
            }
        }
        
        if (tvValShutter != null) tvValShutter.setText(ss.first == 1 && ss.second != 1 ? ss.first + "/" + ss.second : ss.first + "\"");
        if (tvValIso != null) tvValIso.setText(pm.getISOSensitivity() == 0 ? "ISO AUTO" : "ISO " + pm.getISOSensitivity());
        if (tvValEv != null) tvValEv.setText(String.format("%+.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        
        if (tvReview != null) {
            tvReview.setBackgroundColor(mDialMode == DIAL_MODE_REVIEW ? Color.WHITE : Color.argb(140, 40, 40, 40));
            tvReview.setTextColor(mDialMode == DIAL_MODE_REVIEW ? Color.BLACK : Color.rgb(227, 69, 20));
        }

        if (tvValShutter != null) tvValShutter.setTextColor(mDialMode == DIAL_MODE_SHUTTER ? Color.WHITE : Color.rgb(227, 69, 20));
        if (tvValAperture != null) tvValAperture.setTextColor(mDialMode == DIAL_MODE_APERTURE ? Color.WHITE : Color.rgb(227, 69, 20));
        if (tvValIso != null) tvValIso.setTextColor(mDialMode == DIAL_MODE_ISO ? Color.WHITE : Color.rgb(227, 69, 20));
        if (tvValEv != null) tvValEv.setTextColor(mDialMode == DIAL_MODE_EXPOSURE ? Color.WHITE : Color.rgb(227, 69, 20));
        if (tvMode != null) tvMode.setTextColor(mDialMode == DIAL_MODE_PASM ? Color.WHITE : Color.rgb(227, 69, 20));
        
        String fm = p.getFocusMode();
        cachedIsManualFocus = "manual".equals(fm);
        
        if (tvFocusMode != null) {
            if ("auto".equals(fm)) tvFocusMode.setText("AF-S"); 
            else if (cachedIsManualFocus) {
                String rawName = lensManager != null ? lensManager.getCurrentLensName() : "Unmapped Lens";
                String lName = LensProfileManager.formatDisplayName(rawName);
                tvFocusMode.setText("MF [" + lName + "]"); 
            }
            else if ("continuous-video".equals(fm) || "continuous-picture".equals(fm)) tvFocusMode.setText("AF-C"); 
            else tvFocusMode.setText(fm != null ? fm.toUpperCase() : "AF");
            
            tvFocusMode.setTextColor(mDialMode == DIAL_MODE_FOCUS ? Color.WHITE : Color.rgb(227, 69, 20));
        }
        
        // --- 4. UPDATE FOCUS METER ---
        if (focusMeter != null) {
            boolean shouldShow = prefShowFocusMeter && cachedIsManualFocus;
            focusMeter.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
            if (shouldShow) {
                boolean cal = calibController.isCalibrating();
                float focalToUse = cal ? calibController.getDetectedFocalLength() : (lensManager != null ? lensManager.getCurrentFocalLength() : 50.0f);
                List<LensProfileManager.CalPoint> ptsToUse = cal ? calibController.getTempCalPoints() : (lensManager != null ? lensManager.getCurrentPoints() : null);
                float ratioToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !cal) ? virtualFocusRatio : cachedFocusRatio;
                float apToFeed    = (lensManager != null && lensManager.isCurrentProfileManual() && !cal) ? virtualAperture   : cachedAperture;
                
                focusMeter.update(ratioToFeed, apToFeed, focalToUse, cal, ptsToUse, getCircleOfConfusion());
            }
        }
        
        if (gridLines != null) gridLines.setVisibility(prefShowGridLines ? View.VISIBLE : View.GONE); 
        if (cinemaMattes != null) cinemaMattes.setVisibility(prefShowCinemaMattes ? View.VISIBLE : View.GONE);

        // --- 5. CALIBRATION OVERRIDES ---
        // Overrides the "Normal State" visibility if we are currently mapping a lens.
        if (calibController.isActive()) {
            setHUDVisibility(View.GONE);
            if (focusMeter != null) focusMeter.setVisibility(View.VISIBLE);
            if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.VISIBLE);
        }
    }

    @Override 
    public void surfaceCreated(SurfaceHolder h) { 
        hasSurface = true; 
        if (cameraManager != null) cameraManager.open(h); 
    }
    
    @Override 
    public void surfaceDestroyed(SurfaceHolder h) { 
        hasSurface = false; 
        if (cameraManager != null) cameraManager.close(); 
    }
    
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}

    @Override 
    public void onHardwareStateChanged() {
        runOnUiThread(new Runnable() {
            public void run() {
                requestHudUpdate();
            }
        });
    }
    
    @Override 
    public void onCameraReady() {
        syncHardwareState();
        if (cameraManager != null) {
            hardwareFocalLength = cameraManager.getInitialFocalLength();
            isNativeLensAttached = (hardwareFocalLength > 0.0f);
            if (isNativeLensAttached) autoEquipMatchingLens(hardwareFocalLength);

            if (cameraManager.getCamera() != null) {
                try {
                    Camera c = cameraManager.getCamera();
                    Camera.Parameters p = c.getParameters();
                    cachedIsManualFocus = "manual".equals(p.getFocusMode());
                    
                    // UNIVERSAL SENSOR DETECTION: Full frame cameras support APS-C crop mode switching.
                    isFullFrame = "true".equals(p.get("apsc-mode-supported"));
                    android.util.Log.e("JPEG.CAM", "Sensor size detected as: " + (isFullFrame ? "FULL FRAME" : "APS-C"));
                } catch (Exception e) {
                    android.util.Log.e("JPEG.CAM", "Boot sync failed: " + e.getMessage());
                }
            }
        }
        
        applyHardwareRecipe();
        
        // Do an initial HUD draw
        updateMainHUD();
        
        // --- FIRMWARE RACE CONDITION FIX ---
        // The physical dial takes a moment to report its true position to the OS on boot. 
        // Force a re-sync 500ms after the app opens to guarantee accuracy.
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cameraManager != null) onHardwareStateChanged();
            }
        }, 500);
    }
    
    @Override public void onShutterSpeedChanged() { runOnUiThread(new Runnable() { public void run() { requestHudUpdate(); } }); }
    @Override public void onApertureChanged() { runOnUiThread(new Runnable() { public void run() { requestHudUpdate(); } }); }
    @Override public void onIsoChanged() { runOnUiThread(new Runnable() { public void run() { requestHudUpdate(); } }); }
    
    @Override 
    public void onFocusPositionChanged(final float ratio) {
        if (focusMeter != null && cachedIsManualFocus) { 
            runOnUiThread(new Runnable() { 
                public void run() {
                    cachedFocusRatio = ratio;
                    boolean cal = calibController.isCalibrating();
                    float focalToUse = cal ? calibController.getDetectedFocalLength() : (lensManager != null ? lensManager.getCurrentFocalLength() : 50.0f);
                    List<LensProfileManager.CalPoint> ptsToUse = cal ? calibController.getTempCalPoints() : (lensManager != null ? lensManager.getCurrentPoints() : null);
                    float ratioToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !cal) ? virtualFocusRatio : cachedFocusRatio;
                    float apToFeed    = (lensManager != null && lensManager.isCurrentProfileManual() && !cal) ? virtualAperture   : cachedAperture;
                    focusMeter.update(ratioToFeed, apToFeed, focalToUse, cal, ptsToUse, getCircleOfConfusion());
                }
            });
        }
    }

    @Override
    public void onFocalLengthChanged(final float focalLengthMm) {
        runOnUiThread(new Runnable() {
            public void run() {
                hardwareFocalLength = focalLengthMm;
                if (focalLengthMm > 0.0f) {
                    boolean wasManual = !isNativeLensAttached;
                    isNativeLensAttached = true; 
                    Log.d("JPEG.CAM_Lens", "Native Lens Zoomed: " + focalLengthMm + "mm");
                    
                    autoEquipMatchingLens(focalLengthMm);
                    
                    if (wasManual && cameraManager != null && cameraManager.getCamera() != null) {
                        try {
                            Camera c = cameraManager.getCamera();
                            Camera.Parameters p = c.getParameters();
                            p.setFocusMode("auto");
                            c.setParameters(p);
                            cachedIsManualFocus = false;
                        } catch (Exception e) {}
                    }
                } else {
                    isNativeLensAttached = false; 
                    Log.d("JPEG.CAM_Lens", "Manual Lens Detected.");
                }
                updateMainHUD(); 
            }
        });
    }

    @Override 
    public void onStatusUpdate(final String target, final String status) { 
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                menuController.updateConnectionStatus(target, status);
            }
        });
    }

    // --- PlaybackController.HostCallback ---
    @Override public FrameLayout getMainUIContainer() { return mainUIContainer; }
    @Override public int getDisplayState()      { return displayState; }

    // --- LensCalibrationController.HostCallback ---
    @Override public LensProfileManager getLensManager()     { return lensManager; }
    @Override public boolean isNativeLensAttached()          { return isNativeLensAttached; }
    @Override public float getHardwareFocalLength()          { return hardwareFocalLength; }
    @Override public float getCircleOfConfusion()            { return isFullFrame ? 0.030f : 0.020f; }
    @Override public float getCachedFocusRatio()             { return cachedFocusRatio; }

    @Override
    public void onCalibrationComplete(String lensFilename) {
        availableLenses = lensManager.getAvailableLenses();
        currentLensIndex = availableLenses.indexOf(lensFilename);
        if (currentLensIndex == -1) currentLensIndex = 0;
        virtualAperture = lensManager.currentMaxAperture;
        virtualFocusRatio = 0.5f;
        setHUDVisibility(View.VISIBLE);
        updateMainHUD();
    }

    @Override
    public void onCalibrationCancelled() {
        setHUDVisibility(View.VISIBLE);
        updateMainHUD();
    }

    // --- MenuController.HostCallback ---
    @Override public RecipeManager       getRecipeManager()       { return recipeManager; }
    @Override public ConnectivityManager getConnectivityManager() { return connectivityManager; }
    @Override public MatrixManager       getMatrixManager()       { return matrixManager; }
    @Override public Camera              getCamera()              { return cameraManager != null ? cameraManager.getCamera() : null; }
    @Override public String              getAppVersion()          { return getPackageManager() != null ? tryGetVersion() : "?"; }

    private String tryGetVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    @Override public boolean isPrefFocusMeter()   { return prefShowFocusMeter; }
    @Override public boolean isPrefCinemaMattes() { return prefShowCinemaMattes; }
    @Override public boolean isPrefGridLines()    { return prefShowGridLines; }
    @Override public int     getPrefJpegQuality() { return prefJpegQuality; }
    @Override public void    setPrefFocusMeter(boolean v)   { prefShowFocusMeter   = v; }
    @Override public void    setPrefCinemaMattes(boolean v) { prefShowCinemaMattes = v; }
    @Override public void    setPrefGridLines(boolean v)    { prefShowGridLines    = v; }
    @Override public void    setPrefJpegQuality(int v)      { prefJpegQuality      = v; }

    @Override public void closeHud() { hudController.reset(); }

    @Override public void onMenuOpened()  { refreshRecipes(); }

    @Override public void onMenuClosed() {
        recipeManager.savePreferences();
        SharedPreferences.Editor ed = getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE).edit();
        ed.putBoolean("focusMeter",    prefShowFocusMeter);
        ed.putBoolean("cinemaMattes",  prefShowCinemaMattes);
        ed.putBoolean("gridLines",     prefShowGridLines);
        ed.putInt("jpegQuality",       prefJpegQuality);
        ed.apply();
        triggerLutPreload();
        applyHardwareRecipe();
        syncHardwareState();
        updateMainHUD();
    }

    @Override public void onLutPreloadNeeded()    { triggerLutPreload(); }
    @Override public void scheduleHardwareApply() {
        uiHandler.removeCallbacks(applySettingsRunnable);
        uiHandler.postDelayed(applySettingsRunnable, 150);
    }
    @Override public void onHudModeRequested(int mode) { launchHudMode(mode); }
    @Override public void onSetAutoPowerOffMode(boolean on) { setAutoPowerOffMode(on); }

    @Override public void restoreFocusMode(String savedMode) {
        if (savedMode != null && cameraManager != null && cameraManager.getCamera() != null) {
            try {
                Camera.Parameters p = cameraManager.getCamera().getParameters();
                p.setFocusMode(savedMode);
                cameraManager.getCamera().setParameters(p);
            } catch (Exception ignored) {}
        }
    }

    // --- HudController.HostCallback ---
    @Override public TextView  getTvTopStatus()   { return tvTopStatus; }
    @Override public Typeface  getDigitalFont()   { return digitalFont; }
    @Override public Handler   getUiHandler()     { return uiHandler; }
    @Override public MenuController getMenuController() { return menuController; }
    @Override public void applyHardwareRecipeNow() { applyHardwareRecipe(); }
    @Override public void onHudClosed() {
        mainUIContainer.setVisibility(View.GONE);
        menuController.getContainer().setVisibility(View.VISIBLE);
        menuController.refreshDisplay();
    }

    private void syncHardwareState() {
        if (cameraManager == null || cameraManager.getCamera() == null) return;
        Camera c = cameraManager.getCamera();
        String fMode = c.getParameters().getFocusMode();
        cachedIsManualFocus = "manual".equals(fMode);
    }
}
