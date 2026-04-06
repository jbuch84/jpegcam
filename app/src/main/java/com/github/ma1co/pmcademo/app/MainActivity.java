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
    ConnectivityManager.StatusUpdateListener, PlaybackController.HostCallback {

    // --- GLOBAL DEBUG FLAG ---
    // Set to true to see diagnostic Toasts, false for clean public release
    public static final boolean DEBUG_MODE = false;

    private boolean isScrollingMatrices = false;
    private SonyCameraManager cameraManager;
    private InputManager inputManager;
    private RecipeManager recipeManager;
    private MatrixManager matrixManager;
    private ConnectivityManager connectivityManager;
    
    private int activeMatrixIndex = 0; // <-- NEW: Tracks which JSON file we are viewing
    
    private Typeface digitalFont; 
    
    private ImageProcessor mProcessor;
    private SonyFileScanner mScanner;

    private SurfaceView mSurfaceView;
    private boolean hasSurface = false;
    
    private FrameLayout mainUIContainer;
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
    
    private TextView tvTabRTL;
    private TextView tvTabSettings;
    private TextView tvTabNetwork;
    private TextView tvMenuSubtitle;

    private TextView tvTabSupport;
    private LinearLayout supportTabContainer;
    
    private LinearLayout[] menuRows = new LinearLayout[8];
    private TextView[] menuLabels = new TextView[8];
    private TextView[] menuValues = new TextView[8];

    // --- UNIVERSAL HUD VARIABLES ---
    private boolean isHudActive = false;
    private int hudSelection = 0;
    private int currentHudMode = 0; 
    private LinearLayout hudOverlayContainer;
    private LinearLayout[] hudCells = new LinearLayout[9];
    private TextView[] hudLabels = new TextView[9];
    private TextView[] hudValues = new TextView[9];

    // --- VAULT UI VARIABLES ---
    private List<RecipeManager.VaultItem> vaultItems = new ArrayList<RecipeManager.VaultItem>();
    private int vaultIndex = 0;

     // --- RGB MATRIX MATH ---
     // Converts hardware value (e.g., 1024) to a human percentage (e.g., 100)
    private int matrixToPercent(int hardwareValue) {
        return Math.round((hardwareValue / 1024.0f) * 100.0f);
    }      
    // Converts a human percentage (e.g., 100) back to the hardware value (e.g., 1024)
    private int percentToMatrix(int percentValue) {
        return Math.round((percentValue / 100.0f) * 1024.0f);
    }

    // --- WB GRID HUD VARIABLES ---
    private FrameLayout wbGridContainer;
    private View wbCursor;
    private TextView wbValueText;

    private TextView hudTooltipText;
    
    private BatteryView batteryIcon;
    private PlaybackController playbackController;
    private boolean isMenuOpen = false;
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
    
    private boolean isCalibrating = false;
    private boolean waitingForProfileChoice = false;
    private List<LensProfileManager.CalPoint> tempCalPoints = new ArrayList<LensProfileManager.CalPoint>();
    private int calibStep = 0; 
    private float minDistanceInput = 0.3f;
    private String detectedLensName = "Manual Lens";

    private String getAppVersion() {
        try {
            // Reaches into the OS to grab the versionName from your build.gradle
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "Unknown"; // Failsafe
        }
    }
    
    private float detectedFocalLength = 50.0f;
    private float detectedMaxAperture = 2.8f;
    
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
    
    private boolean isMenuEditing = false;
    private int currentMainTab = 0; 
    private int currentPage = 1;    
    private int menuSelection = 0;
    private int currentItemCount = 0;
    private String savedFocusMode = null;

    private char[] matrixNameBuffer = "CUSTOM      ".toCharArray();
    
    private boolean isNamingMode = false;
    private boolean isConfirmingDelete = false; // <-- NEW
    private int nameCursorPos = 0;
    private final String CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_";

    private String hotspotStatus = "Press ENTER";
    private String wifiStatus = "Press ENTER";
    
    private GridLinesView gridLines;
    private CinemaMatteView cinemaMattes;
    private AdvancedFocusMeterView focusMeter;
    private ProReticleView afOverlay;
    
    private Handler uiHandler = new Handler();
    
    private boolean isHudUpdatePending = false;

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
    
    private Runnable hudUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            isHudUpdatePending = false;
            updateMainHUD();
        }
    };

    private Runnable liveUpdater = new Runnable() {
        @Override
        public void run() {
            if (displayState == 0 && !isMenuOpen && !playbackController.isActive() && !isProcessing && hasSurface) {
                if (cameraManager != null && cameraManager.getCamera() != null) {
                    boolean s1_1_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_1).status == 0;
                    boolean s1_2_free = ScalarInput.getKeyStatus(ScalarInput.ISV_KEY_S1_2).status == 0;
                    
                    if (s1_1_free && s1_2_free) {
                        if (afOverlay != null && afOverlay.isPolling()) {
                            afOverlay.stopFocus(cameraManager.getCamera());
                            requestHudUpdate(); 
                        }
                        if (tvTopStatus != null && tvTopStatus.getVisibility() != View.VISIBLE) {
                            if (!isCalibrating && !waitingForProfileChoice) {
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
                return isReady && !isProcessing && !isCalibrating && 
                       (p.lutIndex != 0 || p.grain != 0 || p.vignette != 0 || 
                        p.rollOff != 0 || p.colorChrome != 0 || p.chromeBlue != 0 ||
                        p.shadowToe != 0 || p.subtractiveSat != 0 || p.halation != 0); 
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
        if (isMenuOpen) { exitMenu(); return; }
        if (isProcessing) return; 
        
        // mDialMode = DIAL_MODE_RTL; <-- DELETED. Cursor memory is now permanent!
        
        if (displayState == 0 && !isMenuOpen) setHUDVisibility(View.GONE);
        if (cameraManager != null && cameraManager.getCamera() != null && !cachedIsManualFocus) {
            if (afOverlay != null) afOverlay.startFocus(cameraManager.getCamera()); 
        }
    }

    @Override
    public void onShutterHalfReleased() {
        if (displayState == 0 && !isMenuOpen && !playbackController.isActive()) setHUDVisibility(View.VISIBLE);
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
        
        isMenuOpen = !isMenuOpen;
        if (isMenuOpen) {
            if (cameraManager != null && cameraManager.getCamera() != null) {
                try {
                    Camera c = cameraManager.getCamera();
                    c.cancelAutoFocus();
                    Camera.Parameters p = c.getParameters();
                    savedFocusMode = p.getFocusMode();
                    List<String> fModes = p.getSupportedFocusModes();
                    if (fModes != null && fModes.contains("manual")) {
                        p.setFocusMode("manual");
                        c.setParameters(p);
                    }
                } catch (Exception e) {}
            }
            
            refreshRecipes();
            currentMainTab = 0;
            currentPage = 1; 
            menuSelection = 0; 
            isMenuEditing = false;
            isNamingMode = false;
            
            menuContainer.setVisibility(View.VISIBLE); 
            mainUIContainer.setVisibility(View.GONE); 
            renderMenu();
        } else {
            exitMenu();
        }
    }

    private void exitMenu() {
        isMenuOpen = false;
        isNamingMode = false;
        isConfirmingDelete = false; // <-- NEW
        
        isHudActive = false;
        if (hudOverlayContainer != null) hudOverlayContainer.setVisibility(View.GONE);
        if (hudTooltipText != null) hudTooltipText.setVisibility(View.GONE);  
        if (wbGridContainer != null) wbGridContainer.setVisibility(View.GONE); 
        
        menuContainer.setVisibility(View.GONE); 
        mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
        
        recipeManager.savePreferences();
        
        SharedPreferences.Editor editor = getSharedPreferences("JPEG.CAM_Prefs", MODE_PRIVATE).edit();
        editor.putBoolean("focusMeter", prefShowFocusMeter);
        editor.putBoolean("cinemaMattes", prefShowCinemaMattes);
        editor.putBoolean("gridLines", prefShowGridLines);
        editor.putInt("jpegQuality", prefJpegQuality);
        editor.apply();

        triggerLutPreload(); 
        applyHardwareRecipe();
        
        if (savedFocusMode != null && cameraManager != null && cameraManager.getCamera() != null) {
            try {
                Camera.Parameters p = cameraManager.getCamera().getParameters();
                p.setFocusMode(savedFocusMode);
                cameraManager.getCamera().setParameters(p);
            } catch (Exception e) {}
        }
        
        syncHardwareState();
        updateMainHUD(); 
    }

    private float getCircleOfConfusion() {
        return isFullFrame ? 0.030f : 0.020f; 
    }
    
    private void requestHudUpdate() {
        if (!isHudUpdatePending) {
            isHudUpdatePending = true;
            uiHandler.postDelayed(hudUpdateRunnable, 100); 
        }
    }

    @Override 
public void onEnterPressed() {
    if (playbackController.isActive()) { playbackController.exit(); return; }
    if (isProcessing) return;

    if (isHudActive) {
        // --- VAULT HUD (MODE 10) ---
        // --- VAULT HUD (MODE 10) ---
        if (currentHudMode == 10) {
            if (isNamingMode) {
                isNamingMode = false;
                String finalName = new String(matrixNameBuffer).trim();
                recipeManager.saveSlotToVault(finalName);
                
                // --- FIX: STAY IN HUD & SNAP TO NEW RECIPE ---
                vaultItems = recipeManager.getVaultItems(); 
                for (int i = 0; i < vaultItems.size(); i++) {
                    if (vaultItems.get(i).profileName.equalsIgnoreCase(finalName)) {
                        vaultIndex = i; 
                        break;
                    }
                }
                updateHudUI(); 
                return;
                
            } else if (isConfirmingDelete) {
                // --- HANDLE CONFIRM/CANCEL ---
                if (hudSelection == 0) { // CONFIRM
                    recipeManager.deleteVaultItem(vaultIndex); 
                    vaultIndex = 0; 
                    
                    vaultItems = recipeManager.getVaultItems();
                    if (!vaultItems.isEmpty() && !vaultItems.get(0).filename.equals("NONE")) {
                        recipeManager.previewVaultToSlot(vaultItems.get(0).filename);
                    } else {
                        recipeManager.resetCurrentSlot();
                    }
                    
                    // --- FIX: FORCE HARDWARE TO UPDATE LIVE ---
                    triggerLutPreload();
                    applyHardwareRecipe();
                    
                    isConfirmingDelete = false;
                    hudSelection = 1; 
                    updateHudUI();
                    return;
                } else if (hudSelection == 1) { // CANCEL
                    isConfirmingDelete = false;
                    hudSelection = 0; 
                    updateHudUI();
                    return;
                }
            } else {
                if (hudSelection == 0) { 
                    isNamingMode = true;
                    RTLProfile activeProfile = recipeManager.getCurrentProfile();
                    String currentName = (activeProfile != null) ? activeProfile.profileName : "";
                    
                    if (currentName != null && !currentName.isEmpty() && !currentName.startsWith("SLOT ")) {
                        StringBuilder sb = new StringBuilder(currentName);
                        while(sb.length() < 12) sb.append(" ");
                        matrixNameBuffer = sb.toString().substring(0, 12).toCharArray();
                    } else {
                        matrixNameBuffer = "CUSTOM      ".toCharArray();
                    }
                    
                    nameCursorPos = 0;
                    updateHudUI();
                    return; 
                } else if (hudSelection == 1) { 
                    // ACTION: CONFIRM LOAD (Exits HUD)
                    recipeManager.savePreferences(); 
                    isHudActive = false; 
                } else if (hudSelection == 2) { 
                    // ACTION: RESET
                    recipeManager.resetCurrentSlot();
                    
                    // --- FIX: FORCE HARDWARE TO UPDATE LIVE & STAY IN HUD ---
                    triggerLutPreload();
                    applyHardwareRecipe();
                    updateHudUI(); 
                    return; 
                } else if (hudSelection == 3) {
                    if (!vaultItems.isEmpty() && !vaultItems.get(vaultIndex).filename.equals("NONE")) {
                        isConfirmingDelete = true;
                        hudSelection = 1; 
                        updateHudUI();
                        return;
                    }
                }
            }
            
            // Clean up UI if we exited the HUD (like hitting Confirm Load)
            if (!isHudActive) { 
                hudOverlayContainer.setVisibility(View.GONE);
                mainUIContainer.setVisibility(View.GONE);
                menuContainer.setVisibility(View.VISIBLE); 
                renderMenu(); 
            }
            return;
        }

        // --- EXISTING: RGB MATRIX (MODE 0) ---
        if (currentHudMode == 0 && hudSelection == -1) {
            RTLProfile p = recipeManager.getCurrentProfile();
            
            // --- NEW: DUPLICATE MATH CHECK ---
            if (!isNamingMode) {
                for (int i = 0; i < matrixManager.getCount(); i++) {
                    int[] existing = matrixManager.getValues(i);
                    boolean isMatch = true;
                    for (int j = 0; j < 9; j++) {
                        if (p.advMatrix[j] != existing[j]) { isMatch = false; break; }
                    }
                    
                    if (isMatch) {
                        String existingName = matrixManager.getNames().get(i);
                        tvTopStatus.setText("ALREADY SAVED: " + existingName);
                        tvTopStatus.setTextColor(Color.GREEN);
                        return; 
                    }
                }
            }

            isNamingMode = !isNamingMode;
            if (isNamingMode) {
                matrixNameBuffer = "CUSTOM      ".toCharArray();
                nameCursorPos = 0; 
                updateHudUI(); 
            } else {
                String finalName = new String(matrixNameBuffer).trim();
                if (finalName.isEmpty()) finalName = "CUSTOM";
                saveCurrentCustomMatrix(finalName);
            }
            return;
        }
        
        // --- STANDARD HUD EXIT ---
        isHudActive = false;
        hudOverlayContainer.setVisibility(View.GONE);
        if (hudTooltipText != null) hudTooltipText.setVisibility(View.GONE);
        if (wbGridContainer != null) wbGridContainer.setVisibility(View.GONE);
        mainUIContainer.setVisibility(View.GONE);
        menuContainer.setVisibility(View.VISIBLE);
        recipeManager.savePreferences();
        renderMenu(); 
        return;
    }
        
        RTLProfile p = recipeManager.getCurrentProfile();
        
        // --- FIXED: Shifted down by +1 to accommodate "Load from Vault" at index 2 ---
        if (isMenuOpen && currentMainTab == 0 && currentPage == 1 && menuSelection == 1) {
            launchHudMode(10); return; // NEW: Recipe Manager HUD
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 1 && menuSelection == 2) {
            launchHudMode(6); return; // Foundation Base
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 1 && menuSelection == 3) {
            launchHudMode(3); return; // Tone & Style
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 1 && menuSelection == 4) {
            launchHudMode(9); return; // DRO
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 2 && menuSelection == 0) {
            launchHudMode(2); return;
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 2 && menuSelection == 1) {
            launchHudMode(7); return;
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 2 && menuSelection == 2) {
            launchHudMode(1); return;
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 2 && menuSelection == 3) {
            launchHudMode(0); return;
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 3 && menuSelection == 0) {
            launchHudMode(8); return;
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 3 && menuSelection == 1) {
            String eff = p.pictureEffect != null ? p.pictureEffect : "off";
            if ("toy-camera".equals(eff) || "soft-focus".equals(eff) || "hdr-art".equals(eff) || "illust".equals(eff) || "watercolor".equals(eff) || "part-color".equals(eff) || "miniature".equals(eff)) {
                launchHudMode(5); return;
            }
        }
        if (isMenuOpen && currentMainTab == 0 && currentPage == 3 && menuSelection == 2) {
            launchHudMode(4); return;
        }
        
        if (isCalibrating && calibStep == 0) {
            calibStep = 10; 
            updateCalibrationUI();
            return;
        }

        if (isCalibrating && calibStep == 10) {
            if (!isNativeLensAttached) {
                tempCalPoints = lensManager.generateManualDummyProfile(detectedFocalLength, detectedMaxAperture, getCircleOfConfusion());
                lensManager.saveProfileToFile(detectedFocalLength, detectedMaxAperture, tempCalPoints, true); 
                
                availableLenses = lensManager.getAvailableLenses();
                String newFilename = LensProfileManager.generateFilename(detectedFocalLength, detectedMaxAperture, true);
                currentLensIndex = availableLenses.indexOf(newFilename);
                if (currentLensIndex == -1) currentLensIndex = 0;
                
                lensManager.loadProfileFromFile(newFilename);
                
                virtualAperture = lensManager.currentMaxAperture;
                virtualFocusRatio = 0.5f; 
                
                isCalibrating = false;
                if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.GONE);
                setHUDVisibility(View.VISIBLE);
                updateMainHUD(); 
            } else {
                calibStep = 1; 
                minDistanceInput = 0.3f;
                updateCalibrationUI();
            }
            return;
        }
        
        if (isCalibrating && isNativeLensAttached) {
            if (calibStep == 1) {
                tempCalPoints.add(new LensProfileManager.CalPoint(cachedFocusRatio, minDistanceInput));
                calibStep = 2; 
                updateCalibrationUI();
            } else if (calibStep == 2) {
                tempCalPoints.add(new LensProfileManager.CalPoint(cachedFocusRatio, minDistanceInput));
                updateCalibrationUI(); 
            }
            return;
        }

        if (!isMenuOpen) {
            if (mDialMode == DIAL_MODE_REVIEW) {
                playbackController.enter();
            } else if (mDialMode == DIAL_MODE_FOCUS && cachedIsManualFocus) {
                waitingForProfileChoice = true;
                setHUDVisibility(View.GONE); 
                if (focusMeter != null) focusMeter.setVisibility(View.VISIBLE); 
                if (tvCalibrationPrompt != null) {
                    tvCalibrationPrompt.setVisibility(View.VISIBLE);
                    
                    boolean canAppend = isNativeLensAttached && lensManager.hasActiveProfile() && !lensManager.isCurrentProfileManual();
                    if (canAppend) {
                        tvCalibrationPrompt.setText("LENS MAPPING\n\n[DOWN] Map Attached Lens\n[LEFT] Append Points\n[RIGHT] Cancel");
                    } else {
                        tvCalibrationPrompt.setText("LENS MAPPING\n\n[DOWN] Map Attached Lens\n[RIGHT] Cancel");
                    }
                }
            } else {
                displayState = (displayState == 0) ? 1 : 0; 
                mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                updateMainHUD(); 
            }
        } else {
            if (currentPage == 7) handleConnectionAction(); 
            else { 
                // --- NEW: SONY NATIVE ENTER BEHAVIOR ---
                if (menuSelection == -2) {
                    // "Pressing ENTER while a tab is highlighted does nothing"
                    return; 
                } else if (menuSelection >= 0) {
                    // "Pressing ENTER on an item selects it... pressing ENTER again confirms"
                    isMenuEditing = !isMenuEditing; 
                    renderMenu(); 
                }
            }
        }
    }

    @Override
    public void onUpPressed() {
        if (isHudActive && (currentHudMode == 0 || currentHudMode == 10) && isNamingMode) {
            char currentChar = matrixNameBuffer[nameCursorPos];
            int idx = CHARSET.indexOf(currentChar);
            if (idx == -1) idx = 0;
            
            idx += 1; // Cycle forward through the alphabet
            if (idx >= CHARSET.length()) idx = 0;
            
            matrixNameBuffer[nameCursorPos] = CHARSET.charAt(idx);
            updateHudUI();
            return;
        }
        
        if (isHudActive) {
            if (currentHudMode == 2) {
                handleWbAdjustment(0, 1); 
            } else {
                hudSelection--;
                
                // --- NAVIGATION WRAP LOGIC ---
                // --- NAVIGATION WRAP LOGIC ---
                int minIdx = (currentHudMode == 0) ? -1 : 0;
                
                if (hudSelection < minIdx) {
                    // Wrap to the bottom based on how many cells are in the current mode
                    if (currentHudMode == 0) hudSelection = 8;
                    else if (currentHudMode == 1) hudSelection = 5;
                    else if (currentHudMode == 3) hudSelection = 2; 
                    else if (currentHudMode == 10) hudSelection = 3; // <-- CHANGED to 3
                    else if (currentHudMode == 4 || currentHudMode == 6) hudSelection = 1;
                    else hudSelection = 0;
                }
                updateHudUI();
            }
            return;
        }
        
        if (isProcessing || waitingForProfileChoice) return;
        
        if (isCalibrating) {
            if (calibStep == 2) {
                tempCalPoints.add(new LensProfileManager.CalPoint(1.0f, 999.0f));
                lensManager.saveProfileToFile(detectedFocalLength, detectedMaxAperture, tempCalPoints, false);
                availableLenses = lensManager.getAvailableLenses();
                String newFilename = LensProfileManager.generateFilename(detectedFocalLength, detectedMaxAperture, false);
                currentLensIndex = availableLenses.indexOf(newFilename);
                if (currentLensIndex == -1) currentLensIndex = 0;
                lensManager.loadProfileFromFile(newFilename);
                isCalibrating = false;
                if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.GONE);
                setHUDVisibility(View.VISIBLE);
                updateMainHUD();
            }
            return;
        }

        if (isMenuOpen) {
            if (isNamingMode) {
                handleNamingChange(1);
            } else if (isMenuEditing) {
                handleMenuChange(1);
            } else {
                // --- FIXED: INCLUDES TABS (-2) IN THE LOOP ---
                if (menuSelection == 0) {
                    // UP from the top item jumps to the Tabs
                    menuSelection = -2; 
                } else if (menuSelection == -2) {
                    // UP from the Tabs loops to the BOTTOM of the current page
                    menuSelection = currentItemCount - 1;
                    if (menuSelection < 0) menuSelection = -2; // Failsafe for empty page
                } else {
                    // Normal scroll up
                    menuSelection--;
                }
                renderMenu();
            }
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_UP);
        }
    }

    @Override
    public void onDownPressed() {
        if (isHudActive && (currentHudMode == 0 || currentHudMode == 10) && isNamingMode) {
            char currentChar = matrixNameBuffer[nameCursorPos];
            int idx = CHARSET.indexOf(currentChar);
            if (idx == -1) idx = 0;
            
            idx -= 1; // Cycle backward through the alphabet
            if (idx < 0) idx = CHARSET.length() - 1;
            
            matrixNameBuffer[nameCursorPos] = CHARSET.charAt(idx);
            updateHudUI();
            return;
        }
        
        if (isHudActive) {
            if (currentHudMode == 2) {
                handleWbAdjustment(0, -1); 
            } else {
                hudSelection++;
                
                // Determine the max index for the current mode
                int maxIdx = 0;
                if (currentHudMode == 0) maxIdx = 8;
                else if (currentHudMode == 1) maxIdx = 5;
                else if (currentHudMode == 3) maxIdx = 2; 
                else if (currentHudMode == 10) maxIdx = 3; // <-- CHANGED to 3
                else if (currentHudMode == 4 || currentHudMode == 6) maxIdx = 1;

                // --- NAVIGATION WRAP LOGIC ---
                if (hudSelection > maxIdx) {
                    hudSelection = (currentHudMode == 0) ? -1 : 0;
                }
                updateHudUI();
            }
            return;
        }
        
        if (isProcessing) return;
        
        if (waitingForProfileChoice) {
            waitingForProfileChoice = false;
            isCalibrating = true;
            if (isNativeLensAttached) {
                detectedLensName = "Electronic Lens";
                detectedFocalLength = hardwareFocalLength > 0.0f ? hardwareFocalLength : 50.0f;
                detectedMaxAperture = 2.8f;
                calibStep = 10;
                tempCalPoints.clear();
            } else {
                detectedLensName = "Manual Lens";
                detectedFocalLength = 50.0f;
                detectedMaxAperture = 2.8f;
                calibStep = 0;
                tempCalPoints.clear();
            }
            updateCalibrationUI();
            return;
        }

        if (isMenuOpen) {
            if (isNamingMode) {
                handleNamingChange(-1);
            } else if (isMenuEditing) {
                handleMenuChange(-1);
            } else {
                // --- FIXED: INCLUDES TABS (-2) IN THE LOOP ---
                if (menuSelection == -2) {
                    // DOWN from the Tabs drops to the TOP item
                    menuSelection = 0;
                    if (currentItemCount == 0) menuSelection = -2; // Failsafe if page is empty
                } else if (menuSelection == currentItemCount - 1) {
                    // DOWN from the bottom item loops back to the Tabs
                    menuSelection = -2;
                } else {
                    // Normal scroll down
                    menuSelection++;
                }
                renderMenu();
            }
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_DOWN);
        }
    }

    @Override
    public void onLeftPressed() {
        if (isHudActive && (currentHudMode == 0 || currentHudMode == 10) && isNamingMode) {
            nameCursorPos -= 1; // Move cursor left
            if (nameCursorPos < 0) nameCursorPos = 0;
            if (nameCursorPos > 11) nameCursorPos = 11;
            updateHudUI();
            return;
        }
        
        if (isHudActive) {
            if (currentHudMode == 2) handleWbAdjustment(-1, 0); 
            else {
                hudSelection = Math.max(0, hudSelection - 1);
                updateHudUI();
            }
            return;
        }
        if (isProcessing) return;
        if (isCalibrating && calibStep == 0) {
            detectedFocalLength = Math.max(10.0f, detectedFocalLength - 1.0f);
            updateCalibrationUI();
            return;
        }
        if (isCalibrating && calibStep == 10) {
            detectedMaxAperture = Math.max(1.0f, detectedMaxAperture - 0.1f);
            updateCalibrationUI();
            return;
        }
        if (waitingForProfileChoice) {
            boolean canAppend = isNativeLensAttached && lensManager.hasActiveProfile() && !lensManager.isCurrentProfileManual();
            if (canAppend) {
                waitingForProfileChoice = false;
                isCalibrating = true;
                tempCalPoints = new ArrayList<LensProfileManager.CalPoint>(lensManager.getCurrentPoints());
                detectedLensName = lensManager.getCurrentLensName();
                detectedFocalLength = lensManager.getCurrentFocalLength();
                detectedMaxAperture = lensManager.currentMaxAperture;
                if (!tempCalPoints.isEmpty() && tempCalPoints.get(tempCalPoints.size() - 1).ratio >= 0.99f) {
                    tempCalPoints.remove(tempCalPoints.size() - 1);
                }
                calibStep = 2;
                minDistanceInput = lensManager.getDistanceForRatio(cachedFocusRatio);
                if (minDistanceInput < 0) minDistanceInput = 1.0f;
                updateCalibrationUI();
            }
            return;
        }

        if (isMenuOpen) {
            if (menuSelection == -2) { // 1. TAB LEVEL NAVIGATION
                currentMainTab--;
                if (currentMainTab < 0) currentMainTab = 3; // Loop back to end
                
                // Drop into the first page of the newly selected tab
                if (currentMainTab == 0) currentPage = 1;
                else if (currentMainTab == 1) currentPage = 6;
                else if (currentMainTab == 2) currentPage = 7;
                else if (currentMainTab == 3) currentPage = 8;
                renderMenu();
                
            } else if (isNamingMode) {
                nameCursorPos = Math.max(0, nameCursorPos - 1);
                renderMenu();
            } else if (isMenuEditing) {
                handleMenuChange(-1); // Normal menu editing
            } else { // 2. PAGE LEVEL NAVIGATION
                currentPage--;
                if (currentPage < 1) currentPage = 8; // Wrap from Page 1 to Page 8
                
                // Sync the highlighted Tab at the top to match the new page
                if (currentPage <= 5) currentMainTab = 0;
                else if (currentPage == 6) currentMainTab = 1;
                else if (currentPage == 7) currentMainTab = 2;
                else if (currentPage == 8) currentMainTab = 3;
                
                menuSelection = 0; // Drop cursor to the top of the new page
                renderMenu();
            }
        } else if (!playbackController.isActive() && mDialMode == DIAL_MODE_FOCUS && lensManager != null && lensManager.isCurrentProfileManual()) {
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
        if (isHudActive && (currentHudMode == 0 || currentHudMode == 10) && isNamingMode) {
            nameCursorPos += 1; // Move cursor right
            if (nameCursorPos < 0) nameCursorPos = 0;
            if (nameCursorPos > 11) nameCursorPos = 11;
            updateHudUI();
            return;
        }
        
        if (isHudActive) {
            if (currentHudMode == 2) handleWbAdjustment(1, 0); 
            else {
                int maxSlots = 0;
                if (currentHudMode == 0) maxSlots = 8;
                else if (currentHudMode == 1) maxSlots = 5;
                else if (currentHudMode == 3) maxSlots = 2; 
                else if (currentHudMode == 10) maxSlots = 3; // <-- CHANGED to 3
                else if (currentHudMode == 4 || currentHudMode == 5 || currentHudMode == 6 || currentHudMode == 8) maxSlots = 1;
                
                hudSelection = Math.min(maxSlots, hudSelection + 1);
                updateHudUI();
            }
            return;
        }
        if (isProcessing) return;
        if (isCalibrating && calibStep == 0) {
            detectedFocalLength = Math.min(600.0f, detectedFocalLength + 1.0f);
            updateCalibrationUI();
            return;
        }
        if (isCalibrating && calibStep == 10) {
            detectedMaxAperture = Math.min(22.0f, detectedMaxAperture + 0.1f);
            updateCalibrationUI();
            return;
        }
        if (waitingForProfileChoice || isCalibrating) {
            waitingForProfileChoice = false; isCalibrating = false;
            if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.GONE);
            setHUDVisibility(View.VISIBLE); updateMainHUD();
            return;
        }

        if (isMenuOpen) {
            if (menuSelection == -2) { // 1. TAB LEVEL NAVIGATION
                currentMainTab++;
                if (currentMainTab > 3) currentMainTab = 0; // Loop back to start
                
                // Drop into the first page of the newly selected tab
                if (currentMainTab == 0) currentPage = 1;
                else if (currentMainTab == 1) currentPage = 6;
                else if (currentMainTab == 2) currentPage = 7;
                else if (currentMainTab == 3) currentPage = 8;
                renderMenu();
                
            } else if (isNamingMode) {
                nameCursorPos = Math.min(7, nameCursorPos + 1);
                renderMenu();
            } else if (isMenuEditing) {
                handleMenuChange(1); // Normal menu editing
            } else { // 2. PAGE LEVEL NAVIGATION
                currentPage++;
                if (currentPage > 8) currentPage = 1; // Wrap from Page 8 to Page 1
                
                // Sync the highlighted Tab at the top to match the new page
                if (currentPage <= 5) currentMainTab = 0;
                else if (currentPage == 6) currentMainTab = 1;
                else if (currentPage == 7) currentMainTab = 2;
                else if (currentPage == 8) currentMainTab = 3;
                
                menuSelection = 0; // Drop cursor to the top of the new page
                renderMenu();
            }
        } else if (!playbackController.isActive() && mDialMode == DIAL_MODE_FOCUS && lensManager != null && lensManager.isCurrentProfileManual()) {
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
        if (playbackController.isActive() || isMenuOpen || isProcessing || isCalibrating) return;

        // Simply jump the HUD cursor directly to the ISO slot
        mDialMode = DIAL_MODE_ISO;
        updateMainHUD();
    }
    
    @Override 
    public void onFrontDialRotated(int direction) { 
        // If UI is open, act like a normal scroll wheel
        if (isHudActive || playbackController.isActive() || isMenuOpen) { onControlWheelRotated(direction); return; }
        
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
        if (isHudActive || playbackController.isActive() || isMenuOpen) { onControlWheelRotated(direction); return; }
        
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
        if (isHudActive && (currentHudMode == 0 || currentHudMode == 10) && isNamingMode) {
            char currentChar = matrixNameBuffer[nameCursorPos];
            int idx = CHARSET.indexOf(currentChar);
            if (idx == -1) idx = 0;
            
            idx += direction; // Cycle forward or backward through the alphabet
            if (idx < 0) idx = CHARSET.length() - 1;
            if (idx >= CHARSET.length()) idx = 0;
            
            matrixNameBuffer[nameCursorPos] = CHARSET.charAt(idx);
            updateHudUI();
            return;
        }
        
        if (isHudActive) {
            if (currentHudMode == 2) handleWbAdjustment(direction, 0); 
            else handleHudAdjustment(direction);
            return;
        }
        
        if (playbackController.isActive()) { 
            playbackController.navigate(direction); 
        } else if (isMenuOpen) {
            if (isNamingMode) { 
                handleNamingChange(direction); 
            } else if (isMenuEditing) { 
                handleMenuChange(direction); 
            } else {
                if (direction > 0) onDownPressed(); 
                else onUpPressed(); 
            }
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
    
    private void handleNamingChange(int dir) {
        RTLProfile p = recipeManager.getCurrentProfile();
        String name = p.profileName;
        if (name == null) name = "";
        
        while(name.length() < 8) name += " ";
        if (name.length() > 8) name = name.substring(0, 8);
        
        char c = name.charAt(nameCursorPos);
        int idx = CHARSET.indexOf(c);
        if (idx == -1) idx = 0; 
        
        idx += dir;
        if (idx < 0) idx = CHARSET.length() - 1;
        if (idx >= CHARSET.length()) idx = 0;
        
        char newC = CHARSET.charAt(idx);
        p.profileName = name.substring(0, nameCursorPos) + newC + name.substring(nameCursorPos + 1);
        
        renderMenu();
    }

    private void handleMenuChange(int dir) {
        RTLProfile p = recipeManager.getCurrentProfile(); 
        int sel = menuSelection; 
        
        if (currentPage == 1) { 
            // ROW 0: Workspace Slot (1-10)
            if (sel == 0) {
                if (!isNamingMode) {
                    recipeManager.savePreferences();
                    recipeManager.setCurrentSlot(Math.max(0, Math.min(9, recipeManager.getCurrentSlot() + dir)));
                    triggerLutPreload(); // Updates live-view immediately when slot changes
                }
            }
            // ROW 1: Recipe Recipe Manager (Enter-Action only, no L/R adjustment)

            // ROW 2: Foundation Base (Shifted from Row 4)
            else if (sel == 2) {
                String[] styles = {"Standard", "Vivid", "Neutral", "Clear", "Deep", "Light", "Portrait", "Landscape", "Sunset", "Night Scene", "Autumn Leaves", "Black & White", "Sepia"};
                int idx = 0; for(int i=0; i<styles.length; i++) if(styles[i].equalsIgnoreCase(p.colorMode)) idx = i;
                p.colorMode = styles[(idx + dir + styles.length) % styles.length];
            }
            
            // ROW 3: Tone & Style (HUD-Action only, no L/R adjustment)

            // ROW 4: DRO (Shifted from Row 6)
            else if (sel == 4) {
                String[] droModes = {"OFF", "AUTO", "LVL 1", "LVL 2", "LVL 3", "LVL 4", "LVL 5"};
                int idx = 0; 
                for(int i=0; i < droModes.length; i++) {
                    if(droModes[i].equalsIgnoreCase(p.dro)) idx = i;
                }
                p.dro = droModes[(idx + dir + droModes.length) % droModes.length];
            }
            
        } else if (currentPage == 2) { 
            if (sel == 1) {
                p.whiteBalance = cycleKelvin(p.whiteBalance, dir);
            }

        } else if (currentPage == 3) {
            if (sel == 0) {
                String[] eff = {"off", "toy-camera", "pop-color", "posterization", "retro-photo", "soft-high-key", "partial-color", "high-contrast-mono", "soft-focus", "hdr-painting", "rich-tone-mono", "miniature", "watercolor", "illustration"};
                int idx = 0; for(int i=0; i<eff.length; i++) if(eff[i].equals(p.pictureEffect)) idx = i;
                p.pictureEffect = eff[(idx + dir + eff.length) % eff.length];
            }
            else if (sel == 2) p.softFocusLevel = Math.max(1, Math.min(3, p.softFocusLevel + dir));
            
        } else if (currentPage == 4) { // 4. LUTS & TEXTURES
            if (sel == 0) {
                if (dir > 0 && p.lutIndex < recipeManager.getRecipeNames().size() - 1) p.lutIndex++;
                else if (dir < 0 && p.lutIndex > 0) p.lutIndex--;
            }
            else if (sel == 1) {
                if (p.lutIndex > 0) p.opacity = Math.max(10, Math.min(100, p.opacity + (dir * 10)));
            }
            else if (sel == 2) p.grain = Math.max(0, Math.min(5, p.grain + dir));
            else if (sel == 3) {
                if (p.grain > 0) p.grainSize = Math.max(0, Math.min(2, p.grainSize + dir));
            }
            else if (sel == 4) p.vignette = Math.max(0, Math.min(5, p.vignette + dir));
            
        } else if (currentPage == 5) { // 5. ANALOG PHYSICS (SW)
            if (sel == 0) p.rollOff = Math.max(0, Math.min(5, p.rollOff + dir));
            else if (sel == 1) p.shadowToe = Math.max(0, Math.min(2, p.shadowToe + dir));
            else if (sel == 2) p.subtractiveSat = Math.max(0, Math.min(2, p.subtractiveSat + dir));
            else if (sel == 3) p.colorChrome = Math.max(0, Math.min(2, p.colorChrome + dir));
            else if (sel == 4) p.chromeBlue = Math.max(0, Math.min(2, p.chromeBlue + dir));
            else if (sel == 5) p.halation = Math.max(0, Math.min(2, p.halation + dir));
            
        } else if (currentPage == 6) { // 6. GLOBAL SETTINGS 
            if (sel == 0) recipeManager.setQualityIndex(Math.max(0, Math.min(2, recipeManager.getQualityIndex() + dir)));
            else if (sel == 2) prefShowFocusMeter = !prefShowFocusMeter;
            else if (sel == 3) prefShowCinemaMattes = !prefShowCinemaMattes;
            else if (sel == 4) prefShowGridLines = !prefShowGridLines;
            else if (sel == 5) prefJpegQuality = Math.max(60, Math.min(100, prefJpegQuality + (dir * 5)));
        } 
        
        renderMenu(); 
        recipeManager.savePreferences(); 
        
        uiHandler.removeCallbacks(applySettingsRunnable); 
        uiHandler.postDelayed(applySettingsRunnable, 300);
    }

    private String cycleProMode(String current, int dir) {
        String[] modes = {"off", "pro-standard", "pro-vivid", "pro-portrait"};
        int idx = 0;
        for (int i=0; i<modes.length; i++) if (modes[i].equals(current)) idx = i;
        return modes[(idx + dir + modes.length) % modes.length];
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
        if (isCalibrating && calibStep >= 1 && calibStep != 10) {
            minDistanceInput = Math.max(0.1f, minDistanceInput + (d * 0.1f));
            updateCalibrationUI();
            return;
        }

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
    private String cycleKelvin(String current, int dir) {
        if (current == null) current = "Auto";
        List<String> list = new ArrayList<String>();
        list.add("Auto");
        for (int i = 2500; i <= 9900; i += 100) { list.add(i + "K"); }
        
        int idx = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(current)) { idx = i; break; }
        }
        int newIdx = (idx + dir + list.size()) % list.size();
        return list.get(newIdx);
    }

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

    private void handleConnectionAction() {
        int sel = menuSelection; 
        if (sel == 0) {
            hotspotStatus = "Starting..."; 
            if (connectivityManager != null) { connectivityManager.startHotspot(); setAutoPowerOffMode(false); }
        } else if (sel == 1) {
            wifiStatus = "Connecting..."; 
            if (connectivityManager != null) { connectivityManager.startHomeWifi(); setAutoPowerOffMode(false); }
        } else if (sel == 2) {
            hotspotStatus = "Press ENTER"; wifiStatus = "Press ENTER";
            if (connectivityManager != null) { connectivityManager.stopNetworking(); setAutoPowerOffMode(true); }
        }
        renderMenu(); 
    }

    private void updateHudUI() {
        RTLProfile p = recipeManager.getCurrentProfile();
        String tooltip = ""; // Declared at top to prevent "cannot find symbol"

        // --- MODE 2: WB GRID ---
        if (currentHudMode == 2) {
            int ab = p.wbShift;   
            int gm = p.wbShiftGM; 
            
            int leftOffset = 153 + (ab * 20);
            int topOffset = 153 - (gm * 20);
            
            FrameLayout.LayoutParams cursorParams = (FrameLayout.LayoutParams) wbCursor.getLayoutParams();
            cursorParams.setMargins(leftOffset, topOffset, 0, 0);
            wbCursor.setLayoutParams(cursorParams);
            
            String abStr = ab == 0 ? "0" : (ab < 0 ? "B" + Math.abs(ab) : "A" + ab);
            String gmStr = gm == 0 ? "0" : (gm < 0 ? "M" + Math.abs(gm) : "G" + gm);
            wbValueText.setText(abStr + ", " + gmStr);
            return; 
        }

        int activeCells = 0;
        String[] labels = new String[9];
        String[] values = new String[9];

        // --- MODE 0: ADVANCED MATRIX (With Presets & Balance) ---
        if (currentHudMode == 0) { 
            activeCells = 9;
            labels = new String[]{"R-R", "G-R", "B-R", "R-G", "G-G", "B-G", "R-B", "G-B", "B-B"};
            
            // 1. Calculate Live Row Balance
            int rBal = p.advMatrix[0] + p.advMatrix[1] + p.advMatrix[2];
            int gBal = p.advMatrix[3] + p.advMatrix[4] + p.advMatrix[5];
            int bBal = p.advMatrix[6] + p.advMatrix[7] + p.advMatrix[8];
            String balText = String.format(" [ R:%d%% | G:%d%% | B:%d%% ]", rBal, gBal, bBal);

            // 2. Smart Lookup: Prevent "Teleportation Trap"
            String currentName = "CUSTOM (UNSAVED)";
            String matrixNote = "Use D-Pad to cycle SD Card matrices.";
            
            if (matrixManager != null && matrixManager.getCount() > 0) {
                if (isScrollingMatrices) {
                    // TRUST THE DIAL: Use the index the user actually scrolled to
                    currentName = matrixManager.getNames().get(activeMatrixIndex);
                    matrixNote = matrixManager.getNote(activeMatrixIndex);
                } else {
                    // REVERSE LOOKUP: Only search if the user manually tweaked values
                    for (int f = 0; f < matrixManager.getCount(); f++) {
                        int[] loaded = matrixManager.getValues(f);
                        boolean matches = true;
                        for (int i=0; i<9; i++) { if (p.advMatrix[i] != loaded[i]) matches = false; }
                        
                        if (matches) {
                            activeMatrixIndex = f; 
                            currentName = matrixManager.getNames().get(f);
                            matrixNote = matrixManager.getNote(f);
                            break; 
                        }
                    }
                }
            }

            // 3. Update top status header
            if (tvTopStatus != null) {
                if (isNamingMode) {
                    // Draw the naming interface with brackets around the active cursor letter
                    StringBuilder nameDisplay = new StringBuilder("NAME: ");
                    for (int i = 0; i < matrixNameBuffer.length; i++) {
                        if (i == nameCursorPos) {
                            nameDisplay.append("[").append(matrixNameBuffer[i]).append("]");
                        } else {
                            nameDisplay.append(matrixNameBuffer[i]);
                        }
                    }
                    tvTopStatus.setText(nameDisplay.toString());
                    tvTopStatus.setTextColor(Color.YELLOW); 
                } else {
                    // Standard display
                    tvTopStatus.setText("MATRIX: " + currentName);
                    tvTopStatus.setTextColor(hudSelection == -1 ? Color.rgb(227, 69, 20) : Color.WHITE);
                }
                tvTopStatus.setVisibility(View.VISIBLE); 
            }
            
            // 4. Update Description Tooltip based on selection
            if (hudSelection == -1) {
                tooltip = "FILE: " + matrixNote + "\n" + balText;
            } else {
                String[] t = {
                    "Red sensitivity to real-world Red light (Primary - baseline is 100)", "Pushes Green light into Red channel (baseline is 0)", "Pushes Blue light into Red channel (baseline is 0)",
                    "Pushes Red light into Green channel (baseline is 0)", "Green sensitivity to real-world Green light (Primary - baseline is 100)", "Pushes Blue light into Green channel (baseline is 0)",
                    "Pushes Red light into Blue channel (baseline is 0)", "Pushes Green light into Blue channel (baseline is 0)", "Blue sensitivity to real-world Blue light (Primary - baseline is 100)."
                };
                if (hudSelection >= 0 && hudSelection < t.length) {
                    tooltip = t[hudSelection] + "\n" + balText;
                }
            }

            for (int i=0; i<9; i++) {
                values[i] = p.advMatrix[i] + "%";
            }
            
        // --- ALL OTHER MODES (1, 3-9) ---
        } else if (currentHudMode == 1) {
            activeCells = 6;
            labels = new String[]{"RED", "GRN", "BLU", "CYN", "MAG", "YEL"};
            int[] depths = {p.colorDepthRed, p.colorDepthGreen, p.colorDepthBlue, p.colorDepthCyan, p.colorDepthMagenta, p.colorDepthYellow};
            for (int i=0; i<6; i++) values[i] = depths[i] == 0 ? "0" : String.format("%+d", depths[i]);
            tooltip = "Alters the luminance and depth of the target color phase";

        } else if (currentHudMode == 3) { 
            activeCells = 3;
            labels = new String[]{"CONTRAST", "SATURATION", "SHARPNESS"};
            int[] vals = {p.contrast, p.saturation, p.sharpness};
            for (int i=0; i<3; i++) values[i] = vals[i] == 0 ? "0" : String.format("%+d", vals[i]);
            if (hudSelection == 2) tooltip = "Standard hardware sharpness (Micro-Contrast is stronger)";

        } else if (currentHudMode == 4) { 
            activeCells = 2;
            labels = new String[]{"SHADE RED", "SHADE BLUE"};
            int[] vals = {p.shadingRed, p.shadingBlue};
            for (int i=0; i<2; i++) values[i] = vals[i] == 0 ? "0" : String.format("%+d", vals[i]);
            tooltip = "Injects color shifts into the corners to simulate vintage lens tinting";

        } else if (currentHudMode == 5) { 
            activeCells = 1;
            String eff = p.pictureEffect != null ? p.pictureEffect : "off";
            String genericStr = p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "NORM";
            if ("toy-camera".equals(eff)) {
                activeCells = 2; labels = new String[]{"TOY-TONE", "HW-VIGNETTE"};
                values[0] = genericStr.equals("NORMAL") ? "NORM" : (genericStr.equals("MAGENTA") ? "MAG" : genericStr);
                values[1] = p.vignetteHardware == 0 ? "0" : String.format("%+d", p.vignetteHardware);
            } else if ("soft-focus".equals(eff) || "hdr-art".equals(eff) || "illust".equals(eff) || "watercolor".equals(eff)) {
                labels = new String[]{"LEVEL"}; values[0] = String.valueOf(p.softFocusLevel);
            } else if ("part-color".equals(eff)) {
                labels = new String[]{"COLOR"}; values[0] = genericStr.equals("NORMAL") ? "RED" : genericStr; 
            } else if ("miniature".equals(eff)) {
                labels = new String[]{"AREA"}; values[0] = genericStr.equals("NORMAL") ? "AUTO" : genericStr;
            } else {
                labels = new String[]{"EFFECT"}; values[0] = "NO PARAMS";
            }

        } else if (currentHudMode == 6) { 
            activeCells = 2;
            labels = new String[]{"STYLE", "MICRO-CONTRAST"};
            values[0] = p.colorMode != null ? p.colorMode.toUpperCase() : "STD";
            values[1] = p.sharpnessGain == 0 ? "0" : String.format("%+d", p.sharpnessGain);
            if (hudSelection == 1) tooltip = "Aggressive frequency enhancement (Affects film grain texture)";

        } else if (currentHudMode == 7) { 
            activeCells = 1; labels = new String[]{"WHITE BALANCE"};
            values[0] = p.whiteBalance != null ? p.whiteBalance.toUpperCase() : "AUTO";
            tooltip = "Adjust Kelvin Temperature (2500K - 9900K)";

        } else if (currentHudMode == 8) {
            activeCells = 1; labels = new String[]{"EFFECT"};
            values[0] = p.pictureEffect != null ? p.pictureEffect.toUpperCase() : "OFF";

        } else if (currentHudMode == 9) { 
            activeCells = 1; labels = new String[]{"DYNAMIC RANGE"};
            values[0] = p.dro != null ? p.dro.toUpperCase() : "OFF";
            tooltip = "Dynamic Range Optimizer: Recovers shadow detail in high-contrast scenes";
        } else if (currentHudMode == 10) {
            if (isConfirmingDelete) {
                // --- DELETE CONFIRMATION STATE ---
                activeCells = 2;
                labels = new String[]{"ARE YOU SURE?", "CANCEL"};
                values[0] = "[ CONFIRM DELETE ]";
                values[1] = "[ GO BACK ]";
                
                if (hudSelection == 0) tooltip = "WARNING: This will permanently delete the recipe from the SD card.";
                else tooltip = "Cancel and return to the Recipe Manager.";
                
            } else {
                // --- STANDARD VAULT STATE ---
                activeCells = 4;
                labels = new String[]{"SAVE", "BROWSE", "RESET", "DELETE"};
                
                // FIX: Update the global class variable, do NOT create a local variable!
                vaultItems = recipeManager.getVaultItems();
                if (vaultIndex >= vaultItems.size() || vaultIndex < 0) vaultIndex = 0;
                
                String vName = (vaultItems.isEmpty() || vaultItems.get(0).filename.equals("NONE")) 
                               ? "EMPTY" : vaultItems.get(vaultIndex).profileName;
                
                // Shorten name if it's too long to fit cleanly in the cell
                if (vName.length() > 10) vName = vName.substring(0, 8) + "..";
                
                values[0] = "[ RENAME ]";
                values[1] = "< " + vName + " >";
                values[2] = "[ DEFAULT ]";
                
                // Only show a valid delete option if we are browsing an actual file, not "NONE"
                if (!vaultItems.isEmpty() && !vaultItems.get(vaultIndex).filename.equals("NONE")) {
                    values[3] = "[ TRASH ]";
                } else {
                    values[3] = "---";
                }
                
                if (hudSelection == 0) tooltip = "Press ENTER to RENAME and SAVE this Slot to the Vault.";
                else if (hudSelection == 1) tooltip = "Scroll wheel to browse. WARNING: LIVE VIEW will overwrite Slot.";
                else if (hudSelection == 2) tooltip = "Press ENTER to wipe this Slot back to default settings.";
                else if (hudSelection == 3) tooltip = "Permanently delete the currently selected Vault recipe.";
            }

            // --- TOP BAR NAMING UI ---
            if (tvTopStatus != null) {
                if (isNamingMode) {
                    StringBuilder sb = new StringBuilder("NAME: ");
                    for (int i = 0; i < matrixNameBuffer.length; i++) {
                        if (i == nameCursorPos) sb.append("[").append(matrixNameBuffer[i]).append("]");
                        else sb.append(matrixNameBuffer[i]);
                    }
                    tvTopStatus.setText(sb.toString());
                    tvTopStatus.setTextColor(android.graphics.Color.YELLOW);
                } else {
                    tvTopStatus.setText("RECIPE MANAGER - SLOT " + (recipeManager.getCurrentSlot() + 1));
                    tvTopStatus.setTextColor(android.graphics.Color.WHITE);
                }
                tvTopStatus.setVisibility(View.VISIBLE);
            }
        }

        // --- GENERAL UI RENDER LOOP ---
        for (int i = 0; i < 9; i++) {
            if (i < activeCells) {
                hudCells[i].setVisibility(View.VISIBLE);
                hudLabels[i].setText(labels[i]);
                hudValues[i].setText(values[i]);
                if (i == hudSelection) {
                    hudLabels[i].setTextColor(Color.rgb(227, 69, 20));
                    hudValues[i].setTextColor(Color.rgb(227, 69, 20));
                } else {
                    hudLabels[i].setTextColor(Color.GRAY);
                    hudValues[i].setTextColor(Color.WHITE);
                }
            } else {
                hudCells[i].setVisibility(View.GONE); 
            }
        }
        
        if (hudTooltipText != null) {
            hudTooltipText.setText(tooltip);
            hudTooltipText.setVisibility(tooltip.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void handleWbAdjustment(int dAb, int dGm) {
        RTLProfile p = recipeManager.getCurrentProfile();
        p.wbShift = Math.max(-7, Math.min(7, p.wbShift + dAb));
        p.wbShiftGM = Math.max(-7, Math.min(7, p.wbShiftGM + dGm));
        updateHudUI();
        uiHandler.removeCallbacks(applySettingsRunnable);
        uiHandler.postDelayed(applySettingsRunnable, 150);
    }

    private void handleHudAdjustment(int dir) {
        RTLProfile p = recipeManager.getCurrentProfile();
        
        if (currentHudMode == 0) { 
            if (hudSelection == -1) {
                // --- CYCLE SD CARD MATRICES ---
                if (matrixManager != null && matrixManager.getCount() > 0) {
                    // LOCK: Tells the UI to trust the index while the dial is spinning
                    isScrollingMatrices = true; 
                    
                    activeMatrixIndex += dir;
                    
                    // Keep your bulletproof wrapping exactly as it was
                    while (activeMatrixIndex < 0) activeMatrixIndex += matrixManager.getCount();
                    activeMatrixIndex = activeMatrixIndex % matrixManager.getCount();
                    
                    int[] loadedVals = matrixManager.getValues(activeMatrixIndex);
                    for(int i = 0; i < 9; i++) {
                        p.advMatrix[i] = loadedVals[i];
                    }
                }
            } else {
                // --- MANUAL MATH ADJUSTMENT ---
                // UNLOCK: User is tweaking numbers, so we allow Reverse Lookup again
                isScrollingMatrices = false; 

                int step = 5; 
                int target = p.advMatrix[hudSelection] + (dir * step);
                p.advMatrix[hudSelection] = Math.max(-200, Math.min(200, target)); 
            }
        } else if (currentHudMode == 1) {
            if (hudSelection == 0) p.colorDepthRed = Math.max(-7, Math.min(7, p.colorDepthRed + dir));
            else if (hudSelection == 1) p.colorDepthGreen = Math.max(-7, Math.min(7, p.colorDepthGreen + dir));
            else if (hudSelection == 2) p.colorDepthBlue = Math.max(-7, Math.min(7, p.colorDepthBlue + dir));
            else if (hudSelection == 3) p.colorDepthCyan = Math.max(-7, Math.min(7, p.colorDepthCyan + dir));
            else if (hudSelection == 4) p.colorDepthMagenta = Math.max(-7, Math.min(7, p.colorDepthMagenta + dir));
            else if (hudSelection == 5) p.colorDepthYellow = Math.max(-7, Math.min(7, p.colorDepthYellow + dir));
            
        } else if (currentHudMode == 3) { 
            if (hudSelection == 0) p.contrast = Math.max(-3, Math.min(3, p.contrast + dir));
            else if (hudSelection == 1) p.saturation = Math.max(-3, Math.min(3, p.saturation + dir));
            else if (hudSelection == 2) p.sharpness = Math.max(-3, Math.min(3, p.sharpness + dir));
            
        } else if (currentHudMode == 4) { 
            if (hudSelection == 0) p.shadingRed = Math.max(-7, Math.min(7, p.shadingRed + dir));
            else if (hudSelection == 1) p.shadingBlue = Math.max(-7, Math.min(7, p.shadingBlue + dir));
            
        } else if (currentHudMode == 5) { 
            String eff = p.pictureEffect != null ? p.pictureEffect : "off";
            if (hudSelection == 0) {
                if ("soft-focus".equals(eff) || "hdr-art".equals(eff) || "illust".equals(eff) || "watercolor".equals(eff)) {
                    p.softFocusLevel = Math.max(1, Math.min(3, p.softFocusLevel + dir));
                } else {
                    String[] opts = {"normal"};
                    if ("toy-camera".equals(eff)) opts = new String[]{"normal", "cool", "warm", "green", "magenta"};
                    else if ("part-color".equals(eff)) opts = new String[]{"red", "green", "blue", "yellow"};
                    else if ("miniature".equals(eff)) opts = new String[]{"auto", "left", "vcenter", "right", "upper", "hcenter", "lower"};
                    
                    if (opts.length > 1) {
                        int idx = 0; for(int i=0; i<opts.length; i++) if(opts[i].equals(p.peToyCameraTone)) idx = i;
                        p.peToyCameraTone = opts[(idx + dir + opts.length) % opts.length];
                    }
                }
            } else if (hudSelection == 1 && "toy-camera".equals(eff)) {
                p.vignetteHardware = Math.max(-16, Math.min(16, p.vignetteHardware + dir)); 
            }
            
        } else if (currentHudMode == 6) { 
            if (hudSelection == 0) {
                String[] styles = {"standard", "vivid", "portrait", "landscape", "mono", "sunset", "sepia"};
                int idx = 0; for(int i=0; i<styles.length; i++) if(styles[i].equalsIgnoreCase(p.colorMode)) idx = i;
                p.colorMode = styles[(idx + dir + styles.length) % styles.length];
            } else if (hudSelection == 1) {
                p.sharpnessGain = Math.max(-50, Math.min(50, p.sharpnessGain + (dir * 5)));
            }
            
        } else if (currentHudMode == 7) { 
            p.whiteBalance = cycleKelvin(p.whiteBalance, dir);
            
        } else if (currentHudMode == 8) {
            if (hudSelection == 0) {
                String[] eff = {"off", "toy-camera", "pop-color", "posterization", "retro-photo", "soft-high-key", "part-color", "rough-mono", "soft-focus", "hdr-art", "richtone-mono", "miniature", "watercolor", "illust"};
                int idx = 0; for(int i=0; i<eff.length; i++) if(eff[i].equals(p.pictureEffect)) idx = i;
                p.pictureEffect = eff[(idx + dir + eff.length) % eff.length];
            }
            
        } else if (currentHudMode == 9) { 
            if (hudSelection == 0) {
                String[] droModes = {"OFF", "AUTO", "LVL 1", "LVL 2", "LVL 3", "LVL 4", "LVL 5"};
                int idx = 0; for(int i=0; i < droModes.length; i++) if(droModes[i].equalsIgnoreCase(p.dro)) idx = i;
                p.dro = droModes[(idx + dir + droModes.length) % droModes.length];
            }
        } else if (currentHudMode == 10) {
            // Wheel browsing now only happens on the MIDDLE cell (Index 1)
            if (hudSelection == 1 && !vaultItems.isEmpty() && !vaultItems.get(0).filename.equals("NONE")) {
                vaultIndex = (vaultIndex + dir + vaultItems.size()) % vaultItems.size();
                
                // REACTIVE PREVIEW (Now safe because you have to click Right to get here)
                recipeManager.previewVaultToSlot(vaultItems.get(vaultIndex).filename);
                triggerLutPreload(); 
            }
        }
        
        updateHudUI();
        uiHandler.removeCallbacks(applySettingsRunnable); 
        uiHandler.postDelayed(applySettingsRunnable, 150);
    }

    // --- ON-CAMERA CUSTOM MATRIX SAVER ---
    private void saveCurrentCustomMatrix(String customName) {
        if (matrixManager == null) return;
        
        // --- NAME COLLISION CHECK ---
        String finalName = customName;
        for (String existing : matrixManager.getNames()) {
            if (existing.equalsIgnoreCase(finalName)) {
                finalName = finalName + "+"; // Quick "versioning" for collisions
            }
        }
        
        RTLProfile p = recipeManager.getCurrentProfile();
        matrixManager.saveMatrix(finalName, p.advMatrix, "Saved directly from camera UI.");
        matrixManager.scanMatrices();
        
        // Reset scrolling lock so the UI finds our brand new file immediately
        isScrollingMatrices = false; 
        updateHudUI(); 
    }

    // --- QUICK MATRIX NAME LOOKUP FOR MENU ---
    private String getActiveMatrixName() {
        if (matrixManager == null || matrixManager.getCount() == 0) return "CUSTOM";
        
        RTLProfile p = recipeManager.getCurrentProfile();
        
        for (int f = 0; f < matrixManager.getCount(); f++) {
            int[] loaded = matrixManager.getValues(f);
            boolean matches = true;
            for (int i = 0; i < 9; i++) { 
                if (p.advMatrix[i] != loaded[i]) {
                    matches = false; 
                    break;
                }
            }
            if (matches) return matrixManager.getNames().get(f);
        }
        return "CUSTOM"; // Fallback if it doesn't match any saved file
    }

    private void launchHudMode(int mode, int defaultSelection) {
        isHudActive = true;
        currentHudMode = mode;
        hudSelection = defaultSelection; 
        isConfirmingDelete = false; // <-- NEW
        
        menuContainer.setVisibility(View.GONE);
        mainUIContainer.setVisibility(View.VISIBLE);
        setHUDVisibility(View.GONE); 
        
        if (mode == 2) {
            hudOverlayContainer.setVisibility(View.GONE);
            if (hudTooltipText != null) hudTooltipText.setVisibility(View.GONE);
            if (wbGridContainer != null) wbGridContainer.setVisibility(View.VISIBLE);
        } else {
            hudOverlayContainer.setVisibility(View.VISIBLE);
            if (wbGridContainer != null) wbGridContainer.setVisibility(View.GONE);
        }
        updateHudUI();
    }
    
    private void launchHudMode(int mode) { 
        launchHudMode(mode, 0); 
    }
    
    private void renderMenu() {
        String scn = "UNKNOWN";
        if (cameraManager != null && cameraManager.getCamera() != null) {
            try { scn = cameraManager.getCamera().getParameters().getSceneMode().toUpperCase(); } catch(Exception e) {}
        }

        tvTabRTL.setBackgroundColor(menuSelection == -2 && currentMainTab == 0 ? Color.rgb(227, 69, 20) : Color.TRANSPARENT);
        tvTabSettings.setBackgroundColor(menuSelection == -2 && currentMainTab == 1 ? Color.rgb(227, 69, 20) : Color.TRANSPARENT);
        tvTabNetwork.setBackgroundColor(menuSelection == -2 && currentMainTab == 2 ? Color.rgb(227, 69, 20) : Color.TRANSPARENT);
        tvTabSupport.setBackgroundColor(menuSelection == -2 && currentMainTab == 3 ? Color.rgb(227, 69, 20) : Color.TRANSPARENT);
        tvTabRTL.setTextColor(currentMainTab == 0 ? Color.WHITE : Color.GRAY);
        tvTabSettings.setTextColor(currentMainTab == 1 ? Color.WHITE : Color.GRAY);
        tvTabNetwork.setTextColor(currentMainTab == 2 ? Color.WHITE : Color.GRAY);
        tvTabSupport.setTextColor(currentMainTab == 3 ? Color.WHITE : Color.GRAY);

        tvMenuSubtitle.setBackgroundColor(menuSelection == -1 ? Color.rgb(227, 69, 20) : Color.TRANSPARENT);
        if (currentPage == 1) tvMenuSubtitle.setText("1. Recipe Identity & Base [HW]");
        else if (currentPage == 2) tvMenuSubtitle.setText("2. Advanced Color Engine [HW]");
        else if (currentPage == 3) tvMenuSubtitle.setText("3. Effects & Shading [HW]");
        else if (currentPage == 4) tvMenuSubtitle.setText("4. LUTs & Textures [SW] - ADDS PROCESSING TIME");
        else if (currentPage == 5) tvMenuSubtitle.setText("5. Analog Physics [SW] - ADDS PROCESSING TIME");
        else if (currentPage == 6) tvMenuSubtitle.setText("Global Settings");
        else if (currentPage == 7) tvMenuSubtitle.setText("Web Dashboard Server");
        else if (currentPage == 8) tvMenuSubtitle.setText("Resources & Community");

        for (int i = 0; i < 8; i++) menuRows[i].setVisibility(View.GONE);
        if (supportTabContainer != null) supportTabContainer.setVisibility(View.GONE);
        
        if (currentPage == 8) { supportTabContainer.setVisibility(View.VISIBLE); currentItemCount = 0; return; }

        RTLProfile p = recipeManager.getCurrentProfile();
        int itemCount = 0;
        String[] amtLabels = {"OFF", "LOW", "MED", "HIGH", "V.HIGH", "MAX"};
        String[] sizeLabels = {"SMALL", "MED", "LARGE"};

        if (currentMainTab == 0) {
            if (currentPage == 1) {
                itemCount = 6; 
                String rawName = p.profileName != null ? p.profileName : "";
                while (rawName.length() < 8) rawName += " ";
                if (rawName.length() > 8) rawName = rawName.substring(0, 8);
                String displayHtmlName = rawName;
                if (isNamingMode && menuSelection == 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 8; i++) {
                        char c = rawName.charAt(i);
                        String cStr = (c == ' ') ? "&nbsp;" : String.valueOf(c);
                        if (i == nameCursorPos) sb.append("<font color='#00FFFF'><u>").append(cStr).append("</u></font>");
                        else sb.append(cStr);
                    }
                    displayHtmlName = sb.toString();
                }

                String fndStr = "[ " + (p.colorMode != null ? p.colorMode : "STD").toUpperCase() + " | M-CON " + String.format("%+d", p.sharpnessGain) + " ]";
                String tsStr = String.format("[ %+d,  %+d,  %+d ]", p.contrast, p.saturation, p.sharpness);

                // --- SHOW ACTIVE PROFILE NAME IN MENU ---
                String activeName = (p.profileName != null && !p.profileName.isEmpty()) ? p.profileName : "UNNAMED";
                String vaultValue = "< " + activeName + " >";

                String[] rLabels = {"Recipe Slot (1-10)", "Recipe Manager", "Foundation Base", "Tone & Style", "DRO (Dynamic Range)"};
                String[] rValues = { String.valueOf(recipeManager.getCurrentSlot() + 1), vaultValue, fndStr, tsStr, p.dro != null ? p.dro.toUpperCase() : "OFF" };
                
                for (int i = 0; i < 5; i++) {
                    menuLabels[i].setText(rLabels[i]);
                    menuValues[i].setText(rValues[i].trim());
                    menuRows[i].setVisibility(View.VISIBLE);
                }
            } else if (currentPage == 2) {
                itemCount = 4;
                String abStr = p.wbShift == 0 ? "0" : (p.wbShift < 0 ? "B" + Math.abs(p.wbShift) : "A" + p.wbShift);
                String gmStr = p.wbShiftGM == 0 ? "0" : (p.wbShiftGM < 0 ? "M" + Math.abs(p.wbShiftGM) : "G" + p.wbShiftGM);
                String combinedWb = "[ " + abStr + ", " + gmStr + " ]"; 

                boolean sixIsStd = p.colorDepthRed==0 && p.colorDepthGreen==0 && p.colorDepthBlue==0 && p.colorDepthCyan==0 && p.colorDepthMagenta==0 && p.colorDepthYellow==0;
                String sixStr = sixIsStd ? "[ STANDARD ]" : "[ CUSTOM ]";
                
                boolean mtxIsStd = p.advMatrix[0]==100 && p.advMatrix[1]==0 && p.advMatrix[2]==0 && p.advMatrix[3]==0 && p.advMatrix[4]==100 && p.advMatrix[5]==0 && p.advMatrix[6]==0 && p.advMatrix[7]==0 && p.advMatrix[8]==100;
                String mtxStr = mtxIsStd ? "[ STANDARD ]" : "[ " + getActiveMatrixName() + " ]";

                // --- UPDATED LABELS ---
                String[] rLabels = {"White Balance Shift", "White Balance", "6-Axis Color Depths", "BIONZ RGB Matrix"};
                String[] rValues = { combinedWb, (p.whiteBalance != null ? p.whiteBalance : "Auto").toUpperCase(), sixStr, mtxStr };
                for (int i = 0; i < 4; i++) { menuLabels[i].setText(rLabels[i]); menuValues[i].setText(rValues[i]); menuRows[i].setVisibility(View.VISIBLE); }
                
            } else if (currentPage == 3) {
                itemCount = 3;
                
                String paramStr = "N/A";
                boolean hasParams = false;
                String eff = p.pictureEffect != null ? p.pictureEffect : "off";
                
                if ("toy-camera".equals(eff)) {
                    String tTone = p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "NORM";
                    if (tTone.equals("NORMAL")) tTone = "NORM"; else if (tTone.equals("MAGENTA")) tTone = "MAG";
                    paramStr = "[ " + tTone + " | " + String.format("%+d", p.vignetteHardware) + " ]";
                    hasParams = true;
                } else if ("soft-focus".equals(eff) || "hdr-art".equals(eff) || "illust".equals(eff) || "watercolor".equals(eff)) {
                    paramStr = "[ LVL: " + p.softFocusLevel + " ]";
                    hasParams = true;
                } else if ("part-color".equals(eff)) {
                    paramStr = "[ COLOR: " + (p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "RED") + " ]";
                    hasParams = true;
                } else if ("miniature".equals(eff)) {
                    paramStr = "[ AREA: " + (p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "AUTO") + " ]";
                    hasParams = true;
                }

                String shadeStr = "[ R " + String.format("%+d", p.shadingRed) + " | B " + String.format("%+d", p.shadingBlue) + " ]";

                String[] rLabels = {"Picture Effect Base", "Effect Tweaker", "Edge Shading Editor"};
                String[] rValues = { eff.toUpperCase(), paramStr, shadeStr };
                
                for (int i = 0; i < 3; i++) { 
                    menuLabels[i].setText(rLabels[i]); 
                    menuValues[i].setText(rValues[i]); 
                    menuRows[i].setVisibility(View.VISIBLE); 
                }
            } else if (currentPage == 4) {
                itemCount = 5; 
                
                String[] rLabels = {"LUT File", "LUT Opacity", "Grain Amount", "Grain Size", "Vignette"};
                String[] rValues = { 
                    recipeManager.getRecipeNames().get(p.lutIndex), 
                    p.opacity + "%", 
                    amtLabels[Math.max(0, Math.min(5, p.grain))], 
                    sizeLabels[Math.max(0, Math.min(2, p.grainSize))], 
                    amtLabels[Math.max(0, Math.min(5, p.vignette))]
                };
                
                for (int i = 0; i < 5; i++) { 
                    menuLabels[i].setText(rLabels[i]); 
                    menuValues[i].setText(rValues[i]); 
                    menuRows[i].setVisibility(View.VISIBLE); 
                }
                
            } else if (currentPage == 5) {
                itemCount = 6; 
                
                String chromeStr = p.colorChrome == 0 ? "OFF" : (p.colorChrome == 1 ? "WEAK" : "STRONG");
                String chromeBlueStr = p.chromeBlue == 0 ? "OFF" : (p.chromeBlue == 1 ? "WEAK" : "STRONG");
                String toeStr = p.shadowToe == 0 ? "OFF" : (p.shadowToe == 1 ? "WEAK" : "FILMIC");
                String satStr = p.subtractiveSat == 0 ? "OFF" : (p.subtractiveSat == 1 ? "WEAK" : "HEAVY");
                String haloStr = p.halation == 0 ? "OFF" : (p.halation == 1 ? "WEAK" : "STRONG");

                String[] rLabels = {"Highlight Roll-Off", "Shadow Roll-Off (Toe)", "Subtractive Sat", "Color Chrome", "Chrome Blue", "Halation (Red Glow)"};
                String[] rValues = {
                    amtLabels[Math.max(0, Math.min(5, p.rollOff))],
                    toeStr,                                         
                    satStr,                                         
                    chromeStr,                                      
                    chromeBlueStr,                                  
                    haloStr                                         
                };
                
                for (int i = 0; i < 6; i++) { 
                    menuLabels[i].setText(rLabels[i]); 
                    menuValues[i].setText(rValues[i]); 
                    menuRows[i].setVisibility(View.VISIBLE); 
                }
            }
        } else if (currentPage == 6) { 
            itemCount = 6;
            String[] qLabels = {"1/4 RES", "HALF RES", "FULL RES"};
            String[] gLabels = {"SW Global Resolution", "Base Scene", "Manual Focus Meter", "Anamorphic Crop", "Rule of Thirds Grid", "SW JPEG Quality"};
            String[] gValues = { qLabels[recipeManager.getQualityIndex()], scn, prefShowFocusMeter ? "ON" : "OFF", prefShowCinemaMattes ? "ON" : "OFF", prefShowGridLines ? "ON" : "OFF", String.valueOf(prefJpegQuality) };
            for (int i = 0; i < 6; i++) { menuLabels[i].setText(gLabels[i]); menuValues[i].setText(gValues[i]); menuRows[i].setVisibility(View.VISIBLE); }
            
        } else if (currentPage == 7) { 
            itemCount = 3;
            String[] cLabels = {"Camera Hotspot", "Home Wi-Fi", "Stop Networking"};
            String[] cValues = { hotspotStatus, wifiStatus, "" };
            for (int i = 0; i < 3; i++) { menuLabels[i].setText(cLabels[i]); menuValues[i].setText(cValues[i]); menuRows[i].setVisibility(View.VISIBLE); }
        }

        for (int i = 0; i < itemCount; i++) {
            boolean isActive = true;
            
            if (currentMainTab == 0 && currentPage == 3 && i == 1) {
                String eff = p.pictureEffect != null ? p.pictureEffect : "off";
                isActive = ("toy-camera".equals(eff) || "soft-focus".equals(eff) || "hdr-art".equals(eff) || "illust".equals(eff) || "watercolor".equals(eff) || "part-color".equals(eff) || "miniature".equals(eff));
            }
            
            if (currentMainTab == 0 && currentPage == 4) {
                if (i == 1) isActive = (p.lutIndex > 0); 
                if (i == 3) isActive = (p.grain > 0);    
            }

            String plainText = menuLabels[i].getText().toString().replace("> ", "").replace("  ", "");

            if (i == menuSelection) {
                menuLabels[i].setText("> " + plainText); 

                if (!isActive) {
                    menuRows[i].setBackgroundColor(Color.TRANSPARENT);
                    menuLabels[i].setTextColor(Color.DKGRAY);
                    menuValues[i].setTextColor(Color.DKGRAY);
                } else if (isMenuEditing || isNamingMode) {
                    menuRows[i].setBackgroundColor(Color.TRANSPARENT);
                    menuLabels[i].setTextColor(Color.WHITE);
                    menuValues[i].setTextColor(Color.rgb(227, 69, 20)); 
                } else {
                    menuRows[i].setBackgroundColor(Color.rgb(227, 69, 20)); 
                    menuLabels[i].setTextColor(Color.WHITE);
                    menuValues[i].setTextColor(Color.WHITE);
                }
            } else {
                menuLabels[i].setText("  " + plainText); 

                menuRows[i].setBackgroundColor(Color.TRANSPARENT);
                if (!isActive) {
                    menuLabels[i].setTextColor(Color.DKGRAY);
                    menuValues[i].setTextColor(Color.DKGRAY);
                } else {
                    menuLabels[i].setTextColor(Color.WHITE);
                    menuValues[i].setTextColor(Color.WHITE);
                }
            }
        }
        currentItemCount = itemCount;
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

        menuContainer = new LinearLayout(this); 
        menuContainer.setOrientation(LinearLayout.VERTICAL); 
        menuContainer.setBackgroundColor(Color.argb(250, 15, 15, 15)); 
        menuContainer.setPadding(20, 20, 20, 20); 
        
        LinearLayout tabHeaderLayout = new LinearLayout(this);
        tabHeaderLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabHeaderLayout.setGravity(Gravity.CENTER);
        tabHeaderLayout.setPadding(0, 0, 0, 10);
        
        tvTabRTL = createTabHeader("RECIPES");
        tvTabSettings = createTabHeader("SETTINGS");
        tvTabNetwork = createTabHeader("NETWORK");
        tvTabSupport = createTabHeader("SUPPORT");
        
        tabHeaderLayout.addView(tvTabRTL);
        tabHeaderLayout.addView(tvTabSettings);
        tabHeaderLayout.addView(tvTabNetwork);
        tabHeaderLayout.addView(tvTabSupport); 
        menuContainer.addView(tabHeaderLayout);

        supportTabContainer = new LinearLayout(this);
        supportTabContainer.setOrientation(LinearLayout.VERTICAL);
        supportTabContainer.setGravity(Gravity.CENTER);
        supportTabContainer.setVisibility(View.GONE); 

        TextView tvSupportTitle = new TextView(this);
        tvSupportTitle.setText("JPEG.CAM");
        tvSupportTitle.setTextColor(Color.WHITE);
        tvSupportTitle.setTextSize(28);
        tvSupportTitle.setTypeface(Typeface.DEFAULT_BOLD);
        
        TextView tvSupportSub = new TextView(this);
        tvSupportSub.setText("by JPEG Cookbook • v" + getAppVersion());
        tvSupportSub.setTextColor(Color.LTGRAY);
        tvSupportSub.setTextSize(12);
        tvSupportSub.setPadding(0, 0, 0, 20);

        ImageView qrView = new ImageView(this);
        qrView.setImageResource(R.drawable.qr_hub);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(10, 10, 10, 10);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(240, 240); 
        qrParams.setMargins(0, 0, 0, 20);
        qrView.setLayoutParams(qrParams);

        TextView tvUrl = new TextView(this);
        tvUrl.setText(" "); // removed URL here
        tvUrl.setTextColor(Color.rgb(227, 69, 20));
        tvUrl.setTextSize(18);
        tvUrl.setTypeface(Typeface.DEFAULT_BOLD);

        TextView tvDesc = new TextView(this);
        tvDesc.setText("Manuals, Lens Profiles, & Support");
        tvDesc.setTextColor(Color.GRAY);
        tvDesc.setTextSize(12);

        supportTabContainer.addView(tvSupportTitle);
        supportTabContainer.addView(tvSupportSub);
        supportTabContainer.addView(qrView);
        supportTabContainer.addView(tvUrl);
        supportTabContainer.addView(tvDesc);

        menuContainer.addView(supportTabContainer, new LinearLayout.LayoutParams(-1, -1));
        
        tvMenuSubtitle = new TextView(this);
        tvMenuSubtitle.setTextSize(18);
        tvMenuSubtitle.setTextColor(Color.WHITE);
        tvMenuSubtitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvMenuSubtitle.setPadding(10, 0, 0, 15);
        menuContainer.addView(tvMenuSubtitle);
        
        View headerDivider = new View(this); 
        headerDivider.setBackgroundColor(Color.GRAY); 
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, 2); 
        divParams.setMargins(0, 0, 0, 15); 
        menuContainer.addView(headerDivider, divParams);
        
        for (int i = 0; i < 8; i++) { 
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
            
            if (i < 7) { 
                View divider = new View(this); 
                divider.setBackgroundColor(Color.DKGRAY); 
                menuContainer.addView(divider, new LinearLayout.LayoutParams(-1, 1)); 
            }
        }
        
        menuContainer.setVisibility(View.GONE); 
        rootLayout.addView(menuContainer, new FrameLayout.LayoutParams(-1, -1));

        // Playback viewer — PlaybackController builds and owns its views
        playbackController = new PlaybackController(this, rootLayout, this);

        hudOverlayContainer = new LinearLayout(this); 
        hudOverlayContainer.setOrientation(LinearLayout.HORIZONTAL); 
        hudOverlayContainer.setBackgroundColor(Color.argb(220, 15, 15, 15)); 
        hudOverlayContainer.setPadding(10, 15, 10, 15);
        hudOverlayContainer.setVisibility(View.GONE);
        
        for (int i = 0; i < 9; i++) {
            hudCells[i] = new LinearLayout(this);
            hudCells[i].setOrientation(LinearLayout.VERTICAL);
            hudCells[i].setGravity(Gravity.CENTER);
            
            hudLabels[i] = new TextView(this);
            hudLabels[i].setTextColor(Color.GRAY); 
            hudLabels[i].setTextSize(14);
            if (digitalFont != null) hudLabels[i].setTypeface(digitalFont);
            else hudLabels[i].setTypeface(Typeface.DEFAULT_BOLD);
            
            hudValues[i] = new TextView(this);
            hudValues[i].setTextColor(Color.WHITE);
            hudValues[i].setTextSize(18);
            if (digitalFont != null) hudValues[i].setTypeface(digitalFont);
            else hudValues[i].setTypeface(Typeface.DEFAULT_BOLD);
            
            hudCells[i].addView(hudLabels[i]);
            hudCells[i].addView(hudValues[i]);
            hudOverlayContainer.addView(hudCells[i], new LinearLayout.LayoutParams(0, -2, 1.0f));
        }
        
        // --- PINNED HUD MENU (MOVED TO BOTTOM) ---
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); 
        overlayParams.setMargins(0, 0, 0, 25); // Lowered from 130 to sit neatly at the bottom
        mainUIContainer.addView(hudOverlayContainer, overlayParams);

        hudTooltipText = new TextView(this);
        hudTooltipText.setTextColor(Color.LTGRAY);
        hudTooltipText.setTextSize(12);
        hudTooltipText.setGravity(Gravity.CENTER);
        hudTooltipText.setPadding(10, 8, 10, 8);
        hudTooltipText.setBackgroundColor(Color.argb(200, 15, 15, 15));
        hudTooltipText.setVisibility(View.GONE);
        
        // --- LOWERED HUD TOOLTIP ---
        FrameLayout.LayoutParams ttParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); 
        ttParams.setMargins(0, 0, 0, 130); // Raised slightly so it doesn't overlap 4-cell HUDs
        mainUIContainer.addView(hudTooltipText, ttParams);
        
        wbGridContainer = new FrameLayout(this);
        wbGridContainer.setBackgroundColor(Color.argb(160, 20, 20, 20));
        wbGridContainer.setVisibility(View.GONE);
        
        View vAxis = new View(this);
        vAxis.setBackgroundColor(Color.GRAY);
        wbGridContainer.addView(vAxis, new FrameLayout.LayoutParams(2, 280, Gravity.CENTER));
        
        View hAxis = new View(this);
        hAxis.setBackgroundColor(Color.GRAY);
        wbGridContainer.addView(hAxis, new FrameLayout.LayoutParams(280, 2, Gravity.CENTER));
        
        TextView lG = new TextView(this); lG.setText("G"); lG.setTextColor(Color.WHITE);
        wbGridContainer.addView(lG, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        
        TextView lM = new TextView(this); lM.setText("M"); lM.setTextColor(Color.WHITE);
        wbGridContainer.addView(lM, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        
        TextView lB = new TextView(this); lB.setText("B"); lB.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams pB = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        pB.setMargins(10, 0, 0, 0); wbGridContainer.addView(lB, pB);
        
        TextView lA = new TextView(this); lA.setText("A"); lA.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams pA = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        pA.setMargins(0, 0, 10, 0); wbGridContainer.addView(lA, pA);
        
        wbValueText = new TextView(this);
        wbValueText.setTextColor(Color.rgb(227, 69, 20));
        if (digitalFont != null) wbValueText.setTypeface(digitalFont);
        else wbValueText.setTypeface(Typeface.DEFAULT_BOLD);
        wbValueText.setTextSize(16);
        FrameLayout.LayoutParams pVal = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        pVal.setMargins(0, 10, 15, 0);
        wbGridContainer.addView(wbValueText, pVal);
        
        wbCursor = new View(this);
        wbCursor.setBackgroundColor(Color.rgb(227, 69, 20));
        FrameLayout.LayoutParams cursorParams = new FrameLayout.LayoutParams(14, 14, Gravity.TOP | Gravity.LEFT);
        cursorParams.setMargins(153, 153, 0, 0); 
        wbGridContainer.addView(wbCursor, cursorParams);
        
        mainUIContainer.addView(wbGridContainer, new FrameLayout.LayoutParams(320, 320, Gravity.CENTER));
    }

    private TextView createTabHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16); 
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER); 
        tv.setPadding(0, 0, 0, 10);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tv.setLayoutParams(lp);
        
        return tv;
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
                    else if (!isMenuOpen) playbackController.enter();
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
            else if (!isMenuOpen && !isProcessing) playbackController.enter();
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
                if (savedFocusMode != null) {
                    p.setFocusMode(savedFocusMode);
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

    private void updateCalibrationUI() {
        if (!isCalibrating) return;
        
        String distStr;
        if (minDistanceInput >= 999.0f) {
            distStr = "INFINITY";
        } else {
            float totalInches = minDistanceInput * 39.3701f;
            int ft = (int) (totalInches / 12);
            int in = (int) (totalInches % 12);
            distStr = String.format("%.2fm / %d'%d\"", minDistanceInput, ft, in);
        }
        
        String header = "<font color='#FFFFFF'><b>[ MAPPING: " + detectedLensName + " | POINTS LOGGED: " + tempCalPoints.size() + " ]</b></font><br>";
        String wheelText = "<font color='#00FFFF'><b>rear scroll wheel</b></font>"; 
        String sliderHtml = "<font color='#E6320F'><big><b>◄ " + distStr + " ►</b></big></font>"; 
        String enterBtn = "<font color='#00FF00'><b>[ENTER]</b></font>"; 
        String upBtn = "<font color='#00FF00'><b>[UP]</b></font>"; 
        
        String instructions = "";
        
        if (calibStep == 0) {
            String mmSlider = "<font color='#E6320F'><big><b>◄ " + (int)detectedFocalLength + "mm ►</b></big></font>";
            instructions = "<font color='#FFFFFF'><small>STEP 0A: Lens Detected.</small><br>";
            instructions += "<small>Use [LEFT] / [RIGHT] to set Focal Length: </small> " + mmSlider + "<br>";
            instructions += "<small>Press " + enterBtn + " to confirm.</small></font>";
        } else if (calibStep == 10) {
            String apSlider = "<font color='#E6320F'><big><b>◄ f/" + String.format("%.1f", detectedMaxAperture) + " ►</b></big></font>";
            instructions = "<font color='#FFFFFF'><small>STEP 0B: Set Max Aperture for Lens ID.</small><br>";
            instructions += "<small>Use [LEFT] / [RIGHT] to set Max Aperture: </small> " + apSlider + "<br>";
            instructions += "<small>Press " + enterBtn + " to confirm.</small></font>";
        } else if (calibStep == 1) {
            instructions = "<font color='#FFFFFF'><small>STEP 1: Turn lens ring to hard stop (MIN FOCUS).</small><br>";
            instructions += "<small>Use " + wheelText + " to dial distance: </small> " + sliderHtml + "<br>";
            instructions += "<small>Press " + enterBtn + " to lock min point.</small></font>";
        } else if (calibStep == 2) {
            instructions = "<font color='#FFFFFF'><small>STEP 2: Focus on next object.</small><br>";
            instructions += "<small>Use " + wheelText + " to dial distance: </small> " + sliderHtml + "<br>";
            instructions += "<small>Press " + enterBtn + " to log point, or " + upBtn + " to Save & Finish.</small></font>";
        }
        
        if (tvCalibrationPrompt != null) {
            try {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvCalibrationPrompt.getLayoutParams();
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.topMargin = 10; 
                tvCalibrationPrompt.setLayoutParams(lp);
            } catch (Exception e) { }
            tvCalibrationPrompt.setBackgroundColor(Color.argb(210, 15, 15, 15)); 
            tvCalibrationPrompt.setPadding(25, 15, 25, 15); 
            tvCalibrationPrompt.setText(android.text.Html.fromHtml(header + instructions));
        }
    }
    
    private void updateMainHUD() {
        if (cameraManager == null || cameraManager.getCamera() == null) return;
        
        // --- 1. CLEAN DISPLAY CHECK ---
        if (isHudActive) {
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
                float focalToUse = isCalibrating ? detectedFocalLength : (lensManager != null ? lensManager.getCurrentFocalLength() : 50.0f);
                List<LensProfileManager.CalPoint> ptsToUse = isCalibrating ? tempCalPoints : (lensManager != null ? lensManager.getCurrentPoints() : null);
                
                float ratioToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !isCalibrating) ? virtualFocusRatio : cachedFocusRatio;
                float apToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !isCalibrating) ? virtualAperture : cachedAperture;
                
                focusMeter.update(ratioToFeed, apToFeed, focalToUse, isCalibrating, ptsToUse, getCircleOfConfusion());
            }
        }
        
        if (gridLines != null) gridLines.setVisibility(prefShowGridLines ? View.VISIBLE : View.GONE); 
        if (cinemaMattes != null) cinemaMattes.setVisibility(prefShowCinemaMattes ? View.VISIBLE : View.GONE);

        // --- 5. CALIBRATION OVERRIDES ---
        // Overrides the "Normal State" visibility if we are currently mapping a lens.
        if (isCalibrating || waitingForProfileChoice) {
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
                    float focalToUse = isCalibrating ? detectedFocalLength : (lensManager != null ? lensManager.getCurrentFocalLength() : 50.0f);
                    List<LensProfileManager.CalPoint> ptsToUse = isCalibrating ? tempCalPoints : (lensManager != null ? lensManager.getCurrentPoints() : null);
                    float ratioToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !isCalibrating) ? virtualFocusRatio : cachedFocusRatio;
                    float apToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !isCalibrating) ? virtualAperture : cachedAperture;
                    focusMeter.update(ratioToFeed, apToFeed, focalToUse, isCalibrating, ptsToUse, getCircleOfConfusion());
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
                if (menuSelection == 0) hotspotStatus = status;
                else if (menuSelection == 1) wifiStatus = status;
                
                // --- FIXED: Changed from 6 to 7 to match the new Network tab ---
                if (isMenuOpen && currentPage == 7) renderMenu(); 
            }
        });
    }

    // --- PlaybackController.HostCallback ---
    @Override public View getMainUIContainer() { return mainUIContainer; }
    @Override public int getDisplayState() { return displayState; }

    private void syncHardwareState() {
        if (cameraManager == null || cameraManager.getCamera() == null) return;
        Camera c = cameraManager.getCamera();
        String fMode = c.getParameters().getFocusMode();
        cachedIsManualFocus = "manual".equals(fMode);
    }
}