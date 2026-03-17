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
    
    private TextView tvTabRTL;
    private TextView tvTabSettings;
    private TextView tvTabNetwork;
    private TextView tvMenuSubtitle;

    private TextView tvTabSupport;
    private LinearLayout supportTabContainer;
    
    private LinearLayout[] menuRows = new LinearLayout[8];
    private TextView[] menuLabels = new TextView[8];
    private TextView[] menuValues = new TextView[8];
    
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
    
    private float detectedFocalLength = 50.0f;
    private float detectedMaxAperture = 2.8f;
    
    private float hardwareFocalLength = 0.0f;
    private boolean isNativeLensAttached = false;
    
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

    private boolean isNamingMode = false;
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
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode && !isProcessing && hasSurface) {
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        File thumbsDir = new File(Environment.getExternalStorageDirectory(), "DCIM/.thumbnails");
        if (!thumbsDir.exists()) thumbsDir.mkdirs();

        SharedPreferences prefs = getSharedPreferences("filmOS_Prefs", MODE_PRIVATE);
        prefShowFocusMeter = prefs.getBoolean("focusMeter", true);
        prefShowCinemaMattes = prefs.getBoolean("cinemaMattes", false);
        prefShowGridLines = prefs.getBoolean("gridLines", false);
        prefJpegQuality = prefs.getInt("jpegQuality", 95);
        
        cameraManager = new SonyCameraManager(this);
        inputManager = new InputManager(this);
        recipeManager = new RecipeManager();
        connectivityManager = new ConnectivityManager(this, this);

        lensManager = new LensProfileManager(this);
        availableLenses = lensManager.getAvailableLenses();
        
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
            @Override public void onPreloadStarted() { isReady = false; runOnUiThread(new Runnable() { public void run() { updateMainHUD(); } }); }
            @Override public void onPreloadFinished(boolean success) { isReady = true; runOnUiThread(new Runnable() { public void run() { updateMainHUD(); } }); }
            @Override public void onProcessStarted() { runOnUiThread(new Runnable() { public void run() { if (tvTopStatus != null) { tvTopStatus.setText("PROCESSING..."); tvTopStatus.setTextColor(Color.YELLOW); } } }); }
            @Override public void onProcessFinished(String res) { isProcessing = false; runOnUiThread(new Runnable() { public void run() { if (tvTopStatus != null) { tvTopStatus.setTextColor(Color.WHITE); } updateMainHUD(); } }); }
        });
        
        String baseDcim = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM";
        String[] possibleRoots = { Environment.getExternalStorageDirectory().getAbsolutePath(), "/mnt/sdcard", "/storage/sdcard0", "/sdcard" };
        
        File targetDir = new File(baseDcim, "100MSDCF"); 
        long newestDate = 0;

        for (String r : possibleRoots) {
            File dcim = new File(r + "/DCIM");
            if (dcim.exists() && dcim.isDirectory()) {
                File[] subDirs = dcim.listFiles();
                if (subDirs != null) {
                    for (File sub : subDirs) {
                        if (sub.isDirectory() && sub.getName().endsWith("MSDCF")) {
                            if (sub.lastModified() > newestDate) {
                                newestDate = sub.lastModified();
                                targetDir = sub;
                            }
                        }
                    }
                }
                break;
            }
        }
        
        mScanner = new SonyFileScanner(targetDir.getAbsolutePath(), new SonyFileScanner.ScannerCallback() {
            @Override 
            public boolean isReadyToProcess() { 
                RTLProfile p = recipeManager.getCurrentProfile();
                return isReady && !isProcessing && !isCalibrating && (p.lutIndex != 0 || p.grain != 0 || p.vignette != 0 || p.rollOff != 0); 
            }
            @Override 
            public void onNewPhotoDetected(final String path) { 
                processWhenFileReady(path);
            }
        });
        
        triggerLutPreload();
    }
    
    private void processWhenFileReady(final String path) {
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
                    File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
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
        if (isPlaybackMode) { exitPlayback(); return; }
        if (isMenuOpen) { exitMenu(); return; }
        if (isProcessing) return; 
        
        mDialMode = DIAL_MODE_RTL;
        
        if (displayState == 0 && !isMenuOpen) setHUDVisibility(View.GONE);
        if (cameraManager != null && cameraManager.getCamera() != null && !cachedIsManualFocus) {
            if (afOverlay != null) afOverlay.startFocus(cameraManager.getCamera()); 
        }
    }

    @Override 
    public void onShutterHalfReleased() {
        if (displayState == 0 && !isMenuOpen && !isPlaybackMode) setHUDVisibility(View.VISIBLE);
        if (afOverlay != null && cameraManager != null && cameraManager.getCamera() != null) {
            afOverlay.stopFocus(cameraManager.getCamera());
        }
    }

    @Override 
    public void onDeletePressed() { 
        finish(); 
    }

    @Override 
    public void onMenuPressed() {
        if (isPlaybackMode) { exitPlayback(); return; }
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
        menuContainer.setVisibility(View.GONE); 
        mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE); 
        
        recipeManager.savePreferences(); 
        
        SharedPreferences.Editor editor = getSharedPreferences("filmOS_Prefs", MODE_PRIVATE).edit();
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
        String model = android.os.Build.MODEL.toUpperCase();
        if (model.contains("ILCE-7") || model.contains("ILCE-9") || model.contains("ILCE-1") || model.contains("DSC-RX1") || model.contains("ILCA-99")) {
            return 0.030f; 
        }
        return 0.020f; 
    }
    
    private void requestHudUpdate() {
        if (!isHudUpdatePending) {
            isHudUpdatePending = true;
            uiHandler.postDelayed(hudUpdateRunnable, 100); 
        }
    }

    @Override 
    public void onEnterPressed() {
        if (isPlaybackMode) { exitPlayback(); return; }
        if (isProcessing) return;
        
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
                enterPlayback();
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
            else if (currentMainTab == 0 && currentPage == 1 && menuSelection == 1) {
                isNamingMode = !isNamingMode;
                if (isNamingMode) {
                    isMenuEditing = true;
                    nameCursorPos = 0;
                } else {
                    isMenuEditing = false;
                    recipeManager.savePreferences();
                }
                renderMenu();
            }
            else { isMenuEditing = !isMenuEditing; renderMenu(); }
        }
    }

    @Override 
    public void onUpPressed() { 
        if (isProcessing) return;
        if (waitingForProfileChoice) return;

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
                menuSelection--;
                if (menuSelection < 0) {
                    if (currentMainTab == 0 && currentPage > 1) { 
                        currentPage--; 
                        if (currentPage == 1) menuSelection = 7;
                        else if (currentPage == 2) menuSelection = 6;
                        else if (currentPage == 3) menuSelection = 5;
                        else if (currentPage == 4) menuSelection = 4;
                    } else { 
                        menuSelection = currentItemCount - 1; 
                    }
                }
                renderMenu();
            }
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_UP);
        }
    }

    @Override 
    public void onDownPressed() { 
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
                menuSelection++;
                if (menuSelection >= currentItemCount) {
                    if (currentMainTab == 0 && currentPage < 5) { 
                        currentPage++; 
                        menuSelection = 0; 
                    } else { 
                        menuSelection = 0; 
                    }
                }
                renderMenu();
            }
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_DOWN);
        }
    }
    
    @Override 
    public void onLeftPressed() { 
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
        
        if (!isMenuOpen && !isPlaybackMode && mDialMode == DIAL_MODE_FOCUS && lensManager != null && lensManager.isCurrentProfileManual()) {
            virtualFocusRatio = Math.max(0.0f, virtualFocusRatio - 0.02f);
            if (focusMeter != null) {
                float focalToUse = lensManager.getCurrentFocalLength();
                focusMeter.update(virtualFocusRatio, virtualAperture, focalToUse, false, lensManager.getCurrentPoints());
            }
            return; 
        }

        if (isPlaybackMode) { showPlaybackImage(playbackIndex - 1); } 
        else if (isMenuOpen) {
            if (isNamingMode) {
                nameCursorPos = Math.max(0, nameCursorPos - 1);
                renderMenu();
            } else if (isMenuEditing) { 
                handleMenuChange(-1); 
            } else {
                currentMainTab = Math.max(0, currentMainTab - 1);
                if (currentMainTab == 0) currentPage = 1;
                if (currentMainTab == 1) currentPage = 6;
                if (currentMainTab == 2) currentPage = 7;
                if (currentMainTab == 3) currentPage = 8;
                menuSelection = 0; 
                renderMenu();
            }
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_LEFT);
        }
    }
    
    @Override 
    public void onRightPressed() { 
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
            waitingForProfileChoice = false;
            isCalibrating = false;
            if (tvCalibrationPrompt != null) tvCalibrationPrompt.setVisibility(View.GONE);
            setHUDVisibility(View.VISIBLE);
            updateMainHUD(); 
            return;
        }
        
        if (!isMenuOpen && !isPlaybackMode && mDialMode == DIAL_MODE_FOCUS && lensManager != null && lensManager.isCurrentProfileManual()) {
            virtualFocusRatio = Math.min(1.0f, virtualFocusRatio + 0.02f);
            if (focusMeter != null) {
                float focalToUse = lensManager.getCurrentFocalLength();
                focusMeter.update(virtualFocusRatio, virtualAperture, focalToUse, false, lensManager.getCurrentPoints());
            }
            return; 
        }

        if (isPlaybackMode) { showPlaybackImage(playbackIndex + 1); } 
        else if (isMenuOpen) {
            if (isNamingMode) {
                nameCursorPos = Math.min(7, nameCursorPos + 1);
                renderMenu();
            } else if (isMenuEditing) { 
                handleMenuChange(1); 
            } else {
                currentMainTab = Math.min(3, currentMainTab + 1);
                if (currentMainTab == 0) currentPage = 1;
                if (currentMainTab == 1) currentPage = 6;
                if (currentMainTab == 2) currentPage = 7;
                if (currentMainTab == 3) currentPage = 8;
                menuSelection = 0; 
                renderMenu();
            }
        } else {
            navigateHomeSpatial(ScalarInput.ISV_KEY_RIGHT);
        }
    }
    
    @Override 
    public void onDialRotated(int direction) { 
        if (isPlaybackMode) { showPlaybackImage(playbackIndex + direction); } 
        else if (isMenuOpen) {
            if (isNamingMode) { 
                handleNamingChange(direction); 
            } else if (isMenuEditing) { 
                handleMenuChange(direction); 
            } else { 
                if (direction > 0) onDownPressed(); else onUpPressed(); 
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
        
        if (currentMainTab == 0) {
            if (currentPage == 1) {
                switch(sel) {
                    case 0: recipeManager.setCurrentSlot(recipeManager.getCurrentSlot() + dir); break;
                    case 1: break;
                    case 2: p.lutIndex = (p.lutIndex + dir + recipeManager.getRecipePaths().size()) % recipeManager.getRecipePaths().size(); break;
                    case 3: p.opacity = Math.max(0, Math.min(100, p.opacity + (dir * 10))); break;
                    case 4: p.grain = Math.max(0, Math.min(5, p.grain + dir)); break;
                    case 5: p.grainSize = Math.max(0, Math.min(2, p.grainSize + dir)); break;
                    case 6: p.rollOff = Math.max(0, Math.min(5, p.rollOff + dir)); break;
                    case 7: p.vignette = Math.max(0, Math.min(5, p.vignette + dir)); break;
                }
            } else if (currentPage == 2) {
                String[] wbLabels = {"AUTO", "DAY", "SHD", "CLD", "INC", "FLR"};
                String[] droLabels = {"OFF", "AUTO", "LV1", "LV2", "LV3", "LV4", "LV5"};
                
                switch(sel) {
                    case 0: 
                        int wbi = java.util.Arrays.asList(wbLabels).indexOf(p.whiteBalance); 
                        if (wbi == -1) wbi = 0;
                        p.whiteBalance = wbLabels[(wbi + dir + wbLabels.length) % wbLabels.length]; 
                        break;
                    case 1: p.wbShift = Math.max(-7, Math.min(7, p.wbShift + dir)); break;
                    case 2: p.wbShiftGM = Math.max(-7, Math.min(7, p.wbShiftGM + dir)); break;
                    case 3: 
                        int droi = java.util.Arrays.asList(droLabels).indexOf(p.dro); 
                        if (droi == -1) droi = 0;
                        p.dro = droLabels[(droi + dir + droLabels.length) % droLabels.length]; 
                        break;
                    case 4: p.contrast = Math.max(-3, Math.min(3, p.contrast + dir)); break;
                    case 5: p.saturation = Math.max(-16, Math.min(16, p.saturation + dir)); break;
                    case 6: p.sharpness = Math.max(-3, Math.min(3, p.sharpness + dir)); break;
                }
            } else if (currentPage == 3) {
                switch(sel) {
                    case 0: p.colorDepthRed = Math.max(-7, Math.min(7, p.colorDepthRed + dir)); break;
                    case 1: p.colorDepthGreen = Math.max(-7, Math.min(7, p.colorDepthGreen + dir)); break;
                    case 2: p.colorDepthBlue = Math.max(-7, Math.min(7, p.colorDepthBlue + dir)); break;
                    case 3: p.colorDepthCyan = Math.max(-7, Math.min(7, p.colorDepthCyan + dir)); break;
                    case 4: p.colorDepthMagenta = Math.max(-7, Math.min(7, p.colorDepthMagenta + dir)); break;
                    case 5: p.colorDepthYellow = Math.max(-7, Math.min(7, p.colorDepthYellow + dir)); break;
                }
            } else if (currentPage == 4) {
                String[] cmLabels = {"standard", "vivid", "portrait", "landscape", "mono", "sunset", "sepia"};
                String[] peLabels = {"off", "toy-camera", "pop-color", "posterization", "retro-photo", "soft-high-key", "part-color", "rough-mono", "soft-focus", "hdr-art", "richtone-mono", "miniature", "illust", "watercolor"};
                String[] toneLabels = {"normal", "cool", "warm", "green", "magenta"};
                switch(sel) {
                    case 0: int cmi = java.util.Arrays.asList(cmLabels).indexOf(p.colorMode != null ? p.colorMode.toLowerCase() : "standard"); if (cmi == -1) cmi = 0; p.colorMode = cmLabels[(cmi + dir + cmLabels.length) % cmLabels.length]; break;
                    case 1: int pei = java.util.Arrays.asList(peLabels).indexOf(p.pictureEffect != null ? p.pictureEffect.toLowerCase() : "off"); if (pei == -1) pei = 0; p.pictureEffect = peLabels[(pei + dir + peLabels.length) % peLabels.length]; break;
                    case 2: int ti = java.util.Arrays.asList(toneLabels).indexOf(p.peToyCameraTone != null ? p.peToyCameraTone.toLowerCase() : "normal"); if (ti == -1) ti = 0; p.peToyCameraTone = toneLabels[(ti + dir + toneLabels.length) % toneLabels.length]; break;
                    case 3: p.vignetteHardware = Math.max(-16, Math.min(16, p.vignetteHardware + dir)); break;
                    case 4: p.softFocusLevel = Math.max(1, Math.min(3, p.softFocusLevel + dir)); break;
                }
            } else if (currentPage == 5) {
                String[] matrixLabels = {"OFF", "TEST 1", "TEST 2", "TEST 3"};
                switch(sel) {
                    case 0: p.shadingRed = Math.max(-16, Math.min(16, p.shadingRed + dir)); break;
                    case 1: p.shadingBlue = Math.max(-16, Math.min(16, p.shadingBlue + dir)); break;
                    case 2: int mi = java.util.Arrays.asList(matrixLabels).indexOf(p.rgbMatrixPreset != null ? p.rgbMatrixPreset.toUpperCase() : "OFF"); if (mi == -1) mi = 0; p.rgbMatrixPreset = matrixLabels[(mi + dir + matrixLabels.length) % matrixLabels.length]; break;
                }
            }
        } else if (currentPage == 6) {
            switch(sel) {
                case 0: recipeManager.setQualityIndex(recipeManager.getQualityIndex() + dir); break;
                case 1: 
                    if (cameraManager != null && cameraManager.getCamera() != null) {
                        Camera c = cameraManager.getCamera();
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
                case 2: prefShowFocusMeter = !prefShowFocusMeter; break;
                case 3: prefShowCinemaMattes = !prefShowCinemaMattes; break;
                case 4: prefShowGridLines = !prefShowGridLines; break;
                case 5: prefJpegQuality = Math.max(60, Math.min(100, prefJpegQuality + (dir * 5))); break;
            }
        }
        renderMenu(); 
        recipeManager.savePreferences(); 
        uiHandler.removeCallbacks(applySettingsRunnable); 
        uiHandler.postDelayed(applySettingsRunnable, 100);
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
            List<String> valid = new ArrayList<String>(); 
            String[] desired = {"program-auto", "aperture-priority", "shutter-priority", "shutter-speed-priority", "manual-exposure"};
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
    
    private void applyHardwareRecipe() {
        if (cameraManager == null || cameraManager.getCamera() == null) return;
        Camera c = cameraManager.getCamera(); 
        RTLProfile prof = recipeManager.getCurrentProfile(); 
        Camera.Parameters p = c.getParameters();
        
        if (p.get("color-mode") != null) p.set("color-mode", prof.colorMode != null ? prof.colorMode : "standard");
        
        String wb = "auto";
        if ("DAY".equals(prof.whiteBalance)) wb = "daylight"; 
        else if ("SHD".equals(prof.whiteBalance)) wb = "shade"; 
        else if ("CLD".equals(prof.whiteBalance)) wb = "cloudy-daylight"; 
        else if ("INC".equals(prof.whiteBalance)) wb = "incandescent"; 
        else if ("FLR".equals(prof.whiteBalance)) wb = "fluorescent";
        
        p.setWhiteBalance(wb);
        
        if (p.get("white-balance-shift-mode") != null) p.set("white-balance-shift-mode", (prof.wbShift != 0 || prof.wbShiftGM != 0) ? "true" : "false");
        if (p.get("white-balance-shift-lb") != null) p.set("white-balance-shift-lb", String.valueOf(prof.wbShift)); 
        if (p.get("white-balance-shift-cc") != null) p.set("white-balance-shift-cc", String.valueOf(prof.wbShiftGM));

        if (p.get("dro-mode") != null) {
            if ("OFF".equals(prof.dro)) p.set("dro-mode", "off"); 
            else if ("AUTO".equals(prof.dro)) p.set("dro-mode", "auto"); 
            else if (prof.dro != null && prof.dro.startsWith("LV")) { 
                p.set("dro-mode", "on"); 
                try { p.set("dro-level", Integer.parseInt(prof.dro.replace("LV", ""))); } catch(Exception e) {}
            }
        } else if (p.get("sony-dro") != null) {
            p.set("sony-dro", prof.dro != null ? prof.dro.toLowerCase() : "off");
        }
        
        if (p.get("contrast") != null) p.set("contrast", String.valueOf(prof.contrast)); 
        if (p.get("saturation") != null) p.set("saturation", String.valueOf(prof.saturation)); 
        if (p.get("sharpness") != null) p.set("sharpness", String.valueOf(prof.sharpness));

        if (p.get("color-depth-red") != null) p.set("color-depth-red", String.valueOf(prof.colorDepthRed));
        if (p.get("color-depth-green") != null) p.set("color-depth-green", String.valueOf(prof.colorDepthGreen));
        if (p.get("color-depth-blue") != null) p.set("color-depth-blue", String.valueOf(prof.colorDepthBlue));
        if (p.get("color-depth-cyan") != null) p.set("color-depth-cyan", String.valueOf(prof.colorDepthCyan));
        if (p.get("color-depth-magenta") != null) p.set("color-depth-magenta", String.valueOf(prof.colorDepthMagenta));
        if (p.get("color-depth-yellow") != null) p.set("color-depth-yellow", String.valueOf(prof.colorDepthYellow));

        if (p.get("picture-effect") != null) {
            p.set("picture-effect", prof.pictureEffect != null ? prof.pictureEffect : "off");
            if ("toy-camera".equals(prof.pictureEffect)) {
                p.set("pe-toy-camera-effect", prof.peToyCameraTone != null ? prof.peToyCameraTone : "normal");
                p.set("pe-toy-camera-tuning", String.valueOf(prof.vignetteHardware)); 
            }
        }
        
        if (p.get("lens-correction") != null) p.set("lens-correction", "true");
        if (p.get("lens-correction-shading-color-red") != null) p.set("lens-correction-shading-color-red", String.valueOf(prof.shadingRed));
        if (p.get("lens-correction-shading-color-blue") != null) p.set("lens-correction-shading-color-blue", String.valueOf(prof.shadingBlue));
        
        if (p.get("pe-soft-focus-effect-level") != null) p.set("pe-soft-focus-effect-level", String.valueOf(prof.softFocusLevel));
        
        if (p.get("rgb-matrix-mode") != null) {
            if ("OFF".equals(prof.rgbMatrixPreset)) {
                p.set("rgb-matrix-mode", "false");
            } else {
                p.set("rgb-matrix-mode", "true");
                if ("TEST 1".equals(prof.rgbMatrixPreset)) p.set("rgb-matrix", "256,0,0,0,256,0,0,0,256");
                else if ("TEST 2".equals(prof.rgbMatrixPreset)) p.set("rgb-matrix", "0,0,0,0,256,0,0,0,256");
                else if ("TEST 3".equals(prof.rgbMatrixPreset)) p.set("rgb-matrix", "0,256,0,256,0,0,0,0,256");
            }
        }
        
        try { c.setParameters(p); } catch (Exception e) {}
    }

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

    private void renderMenu() {
        String scn = "UNKNOWN"; 
        if (cameraManager != null && cameraManager.getCamera() != null) {
            try { scn = cameraManager.getCamera().getParameters().getSceneMode().toUpperCase(); } catch(Exception e) {}
        }
        
        tvTabRTL.setTextColor(currentMainTab == 0 ? Color.rgb(230, 50, 15) : Color.GRAY);
        tvTabSettings.setTextColor(currentMainTab == 1 ? Color.rgb(230, 50, 15) : Color.GRAY);
        tvTabNetwork.setTextColor(currentMainTab == 2 ? Color.rgb(230, 50, 15) : Color.GRAY);
        tvTabSupport.setTextColor(currentMainTab == 3 ? Color.rgb(230, 50, 15) : Color.GRAY); 
        
        if (currentPage == 1) tvMenuSubtitle.setText("Software Engine (Page 1/5)");
        else if (currentPage == 2) tvMenuSubtitle.setText("Standard Tone (Page 2/5)");
        else if (currentPage == 3) tvMenuSubtitle.setText("6-Axis Color Matrix (Page 3/5)");
        else if (currentPage == 4) tvMenuSubtitle.setText("Experimental Optics (Page 4/5)");
        else if (currentPage == 5) tvMenuSubtitle.setText("Deep Hardware Hacks (Page 5/5)");
        else if (currentPage == 6) tvMenuSubtitle.setText("Global Settings");
        else if (currentPage == 7) tvMenuSubtitle.setText("Web Dashboard Server");
        else if (currentPage == 8) tvMenuSubtitle.setText("Resources & Community");

        for (int i = 0; i < 8; i++) menuRows[i].setVisibility(View.GONE);
        if (supportTabContainer != null) supportTabContainer.setVisibility(View.GONE); 

        if (currentPage == 8) {
            supportTabContainer.setVisibility(View.VISIBLE);
            currentItemCount = 0;
            return; 
        }

        RTLProfile p = recipeManager.getCurrentProfile();
        int itemCount = 0;

        String[] amtLabels = {"OFF", "LOW", "MED", "HIGH", "V.HIGH", "MAX"};
        String[] sizeLabels = {"SMALL", "MED", "LARGE"};
        String[] stepLabels = {"-3", "-2", "-1", "0", "+1", "+2", "+3"};

        if (currentMainTab == 0) {
            if (currentPage == 1) { 
                itemCount = 8; 
                String[] rLabels = {"Recipe Slot", "Profile Name", "LUT", "Opacity", "Grain Amount", "Grain Size", "Highlight Roll", "Vignette"};
                
                String rawName = p.profileName;
                if (rawName == null) rawName = "";
                while (rawName.length() < 8) rawName += " ";
                if (rawName.length() > 8) rawName = rawName.substring(0, 8);
                
                String displayHtmlName = rawName;
                
                if (isNamingMode && menuSelection == 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 8; i++) {
                        char c = rawName.charAt(i);
                        String cStr = (c == ' ') ? "&nbsp;" : String.valueOf(c);
                        if (i == nameCursorPos) {
                            sb.append("<font color='#00FFFF'><u>").append(cStr).append("</u></font>");
                        } else {
                            sb.append(cStr);
                        }
                    }
                    displayHtmlName = sb.toString();
                }

                String[] rValues = { 
                    String.valueOf(recipeManager.getCurrentSlot() + 1), 
                    displayHtmlName, 
                    recipeManager.getRecipeNames().get(p.lutIndex), 
                    p.opacity + "%", 
                    amtLabels[Math.max(0, Math.min(5, p.grain))], 
                    sizeLabels[Math.max(0, Math.min(2, p.grainSize))], 
                    amtLabels[Math.max(0, Math.min(5, p.rollOff))], 
                    amtLabels[Math.max(0, Math.min(5, p.vignette))] 
                };
                
                for (int i = 0; i < 8; i++) { 
                    menuLabels[i].setText(rLabels[i]); 
                    if (i == 1 && (isNamingMode || displayHtmlName.contains("&nbsp;"))) {
                        menuValues[i].setText(android.text.Html.fromHtml(rValues[i]));
                    } else {
                        menuValues[i].setText(rValues[i].trim()); 
                    }
                    menuRows[i].setVisibility(View.VISIBLE); 
                }
            } else if (currentPage == 2) {
                itemCount = 7;
                String[] cLabels = {"White Balance", "WB Shift (A-B)", "WB Shift (G-M)", "DRO", "Contrast", "Saturation", "Sharpness"};
                String abStr = p.wbShift == 0 ? "0" : (p.wbShift < 0 ? "B" + Math.abs(p.wbShift) : "A" + p.wbShift);
                String gmStr = p.wbShiftGM == 0 ? "0" : (p.wbShiftGM < 0 ? "M" + Math.abs(p.wbShiftGM) : "G" + p.wbShiftGM);
                String[] cValues = { p.whiteBalance, abStr, gmStr, p.dro, String.format("%+d", p.contrast), String.format("%+d", p.saturation), String.format("%+d", p.sharpness) };
                for (int i = 0; i < 7; i++) { menuLabels[i].setText(cLabels[i]); menuValues[i].setText(cValues[i]); menuRows[i].setVisibility(View.VISIBLE); }
            } else if (currentPage == 3) {
                itemCount = 6;
                String[] mLabels = {"Red Depth", "Green Depth", "Blue Depth", "Cyan Depth", "Magenta Depth", "Yellow Depth"};
                String[] mValues = { 
                    String.format("%+d", p.colorDepthRed), String.format("%+d", p.colorDepthGreen), 
                    String.format("%+d", p.colorDepthBlue), String.format("%+d", p.colorDepthCyan), 
                    String.format("%+d", p.colorDepthMagenta), String.format("%+d", p.colorDepthYellow) 
                };
                for (int i = 0; i < 6; i++) { menuLabels[i].setText(mLabels[i]); menuValues[i].setText(mValues[i]); menuRows[i].setVisibility(View.VISIBLE); }
            } else if (currentPage == 4) {
                itemCount = 5;
                String[] eLabels = {"Color Mode", "Picture Effect", "Toy Cam Tone", "HW Vignette", "Soft Focus Lvl"};
                String colorModeStr = p.colorMode != null ? p.colorMode.toUpperCase() : "STANDARD";
                String picEffStr = p.pictureEffect != null ? p.pictureEffect.toUpperCase() : "OFF";
                String toneStr = p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "NORMAL";
                String[] eValues = { colorModeStr, picEffStr, toneStr, String.format("%+d", p.vignetteHardware), String.valueOf(p.softFocusLevel) };
                for (int i = 0; i < 5; i++) { menuLabels[i].setText(eLabels[i]); menuValues[i].setText(eValues[i]); menuRows[i].setVisibility(View.VISIBLE); }
            } else if (currentPage == 5) {
                itemCount = 3;
                String[] dLabels = {"Edge Shading (Red)", "Edge Shading (Blue)", "RGB Matrix Exploit"};
                String matrixStr = p.rgbMatrixPreset != null ? p.rgbMatrixPreset.toUpperCase() : "OFF";
                String[] dValues = { String.format("%+d", p.shadingRed), String.format("%+d", p.shadingBlue), matrixStr };
                for (int i = 0; i < 3; i++) { menuLabels[i].setText(dLabels[i]); menuValues[i].setText(dValues[i]); menuRows[i].setVisibility(View.VISIBLE); }
            }
        } else if (currentPage == 6) {
            itemCount = 6;
            String[] qLabels = {"1/4 RES", "HALF RES", "FULL RES"};
            String[] gLabels = {"Global Resolution", "Base Scene", "Manual Focus Meter", "Anamorphic Crop", "Rule of Thirds Grid", "JPEG Quality"};
            String[] gValues = { qLabels[recipeManager.getQualityIndex()], scn, prefShowFocusMeter ? "ON" : "OFF", prefShowCinemaMattes ? "ON" : "OFF", prefShowGridLines ? "ON" : "OFF", String.valueOf(prefJpegQuality) };
            for (int i = 0; i < 6; i++) { menuLabels[i].setText(gLabels[i]); menuValues[i].setText(gValues[i]); menuRows[i].setVisibility(View.VISIBLE); }
        } else if (currentPage == 7) {
            itemCount = 3;
            String[] cLabels = {"Camera Hotspot", "Home Wi-Fi", "Stop Networking"};
            String[] cValues = { hotspotStatus, wifiStatus, "" };
            for (int i = 0; i < 3; i++) { menuLabels[i].setText(cLabels[i]); menuValues[i].setText(cValues[i]); menuRows[i].setVisibility(View.VISIBLE); }
        }

        for (int i = 0; i < itemCount; i++) {
            if (i == menuSelection) {
                if (isMenuEditing || isNamingMode) {
                    menuRows[i].setBackgroundColor(Color.TRANSPARENT);
                    menuLabels[i].setTextColor(Color.WHITE);
                    menuValues[i].setTextColor(Color.rgb(230, 50, 15)); 
                } else {
                    menuRows[i].setBackgroundColor(Color.rgb(230, 50, 15));
                    menuLabels[i].setTextColor(Color.WHITE);
                    menuValues[i].setTextColor(Color.WHITE);
                }
            } else {
                menuRows[i].setBackgroundColor(Color.TRANSPARENT);
                menuLabels[i].setTextColor(Color.WHITE);
                menuValues[i].setTextColor(Color.WHITE);
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
        batteryArea.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        
        tvBattery = new TextView(this); 
        tvBattery.setTextColor(Color.WHITE); 
        tvBattery.setTextSize(14); 
        tvBattery.setTypeface(Typeface.DEFAULT_BOLD); 
        tvBattery.setPadding(0, 0, 5, 0); 
        batteryArea.addView(tvBattery);
        
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
        tvSupportTitle.setText("filmOS");
        tvSupportTitle.setTextColor(Color.WHITE);
        tvSupportTitle.setTextSize(28);
        tvSupportTitle.setTypeface(Typeface.DEFAULT_BOLD);
        
        TextView tvSupportSub = new TextView(this);
        tvSupportSub.setText("by JPG Cookbook");
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
        tvUrl.setText("jpgcookbook.com/hub");
        tvUrl.setTextColor(Color.rgb(230, 50, 15));
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
    public boolean onKeyDown(int k, KeyEvent e) { 
        if (isProcessing && (k == ScalarInput.ISV_KEY_S1_1 || k == ScalarInput.ISV_KEY_S1_2 || k == ScalarInput.ISV_KEY_S2)) return true; 
        if (k == ScalarInput.ISV_KEY_PLAY) {
            if (isPlaybackMode) exitPlayback(); 
            else if (!isMenuOpen && !isProcessing) enterPlayback();
            return true;
        }
        if (inputManager != null) return inputManager.handleKeyDown(k, e) || super.onKeyDown(k, e); 
        return super.onKeyDown(k, e);
    }
    
    @Override 
    public boolean onKeyUp(int k, KeyEvent e) { 
        if (isProcessing && (k == ScalarInput.ISV_KEY_S1_1 || k == ScalarInput.ISV_KEY_S1_2 || k == ScalarInput.ISV_KEY_S2)) return true; 
        if (inputManager != null) return inputManager.handleKeyUp(k, e) || super.onKeyUp(k, e); 
        return super.onKeyUp(k, e);
    }
    
    @Override 
    protected void onResume() { 
        super.onResume(); 
        if (hasSurface && cameraManager != null) cameraManager.open(mSurfaceView.getHolder()); 
        
        IntentFilter sonyFilter = new IntentFilter("com.sony.scalar.database.avindex.action.AVINDEX_DATABASE_UPDATED");
        registerReceiver(sonyCameraReceiver, sonyFilter);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); 
        uiHandler.post(liveUpdater); 
    }
    
    @Override 
    protected void onPause() { 
        super.onPause(); 
        uiHandler.removeCallbacksAndMessages(null); 
        
        if (cameraManager != null) cameraManager.close(); 
        try { unregisterReceiver(sonyCameraReceiver); unregisterReceiver(batteryReceiver); } catch (Exception e) {}
        if (connectivityManager != null) connectivityManager.stopNetworking(); 
        if (recipeManager != null) recipeManager.savePreferences(); 
        setAutoPowerOffMode(true);
    }

    private void setHUDVisibility(int v) { 
        if (tvTopStatus != null) tvTopStatus.setVisibility(v); 
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
        Camera c = cameraManager.getCamera(); 
        Camera.Parameters p = c.getParameters(); 
        CameraEx.ParametersModifier pm = cameraManager.getCameraEx().createParametersModifier(p);
        
        RTLProfile prof = recipeManager.getCurrentProfile(); 
        String name = recipeManager.getRecipeNames().get(prof.lutIndex);
        String displayName = name.length() > 15 ? name.substring(0, 12) + "..." : name;
        
        String customName = prof.profileName != null ? prof.profileName.trim() : ("RECIPE " + (recipeManager.getCurrentSlot() + 1));
        if (customName.isEmpty()) customName = "RECIPE " + (recipeManager.getCurrentSlot() + 1);
        
        if (!isProcessing && tvTopStatus != null) {
            tvTopStatus.setText(customName + " [" + displayName + "]\n" + (isReady ? "READY" : "LOADING.."));
            tvTopStatus.setTextColor(mDialMode == DIAL_MODE_RTL ? Color.rgb(230, 50, 15) : Color.WHITE);
        }
        
        String sm = p.getSceneMode(); 
        if (tvMode != null) {
            if ("manual-exposure".equals(sm)) tvMode.setText("M"); 
            else if ("aperture-priority".equals(sm)) tvMode.setText("A"); 
            else if ("shutter-priority".equals(sm) || "shutter-speed-priority".equals(sm)) tvMode.setText("S"); 
            else if ("program-auto".equals(sm)) tvMode.setText("P");
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
        
        if (tvReview != null) tvReview.setBackgroundColor(mDialMode == DIAL_MODE_REVIEW ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));
        if (tvValShutter != null) tvValShutter.setTextColor(mDialMode == DIAL_MODE_SHUTTER ? Color.rgb(230, 50, 15) : Color.WHITE);
        if (tvValAperture != null) tvValAperture.setTextColor(mDialMode == DIAL_MODE_APERTURE ? Color.rgb(230, 50, 15) : Color.WHITE);
        if (tvValIso != null) tvValIso.setTextColor(mDialMode == DIAL_MODE_ISO ? Color.rgb(230, 50, 15) : Color.WHITE);
        if (tvValEv != null) tvValEv.setTextColor(mDialMode == DIAL_MODE_EXPOSURE ? Color.rgb(230, 50, 15) : Color.WHITE);
        if (tvMode != null) tvMode.setTextColor(mDialMode == DIAL_MODE_PASM ? Color.rgb(230, 50, 15) : Color.WHITE);
        
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
            tvFocusMode.setTextColor(mDialMode == DIAL_MODE_FOCUS ? Color.rgb(230, 50, 15) : Color.WHITE);
        }
        
        if (focusMeter != null) {
            boolean shouldShow = prefShowFocusMeter && cachedIsManualFocus;
            focusMeter.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
            if (shouldShow) {
                float focalToUse = isCalibrating ? detectedFocalLength : (lensManager != null ? lensManager.getCurrentFocalLength() : 50.0f);
                List<LensProfileManager.CalPoint> ptsToUse = isCalibrating ? tempCalPoints : (lensManager != null ? lensManager.getCurrentPoints() : null);
                
                float ratioToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !isCalibrating) ? virtualFocusRatio : cachedFocusRatio;
                float apToFeed = (lensManager != null && lensManager.isCurrentProfileManual() && !isCalibrating) ? virtualAperture : cachedAperture;
                
                focusMeter.update(ratioToFeed, apToFeed, focalToUse, isCalibrating, ptsToUse);
            }
        }
        
        if (gridLines != null) gridLines.setVisibility(prefShowGridLines ? View.VISIBLE : View.GONE); 
        if (cinemaMattes != null) cinemaMattes.setVisibility(prefShowCinemaMattes ? View.VISIBLE : View.GONE);

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
    public void onCameraReady() { 
        syncHardwareState();
        
        if (cameraManager != null) {
            hardwareFocalLength = cameraManager.getInitialFocalLength();
            if (hardwareFocalLength > 0.0f) {
                isNativeLensAttached = true;
                Log.d("filmOS_Lens", "Boot: Native Lens Detected: " + hardwareFocalLength + "mm");
                
                autoEquipMatchingLens(hardwareFocalLength);
                
                if (cameraManager.getCamera() != null) {
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
                Log.d("filmOS_Lens", "Boot: Manual Lens Detected");
            }
        }
        
        updateMainHUD();
    }
    
    @Override 
    public void onShutterSpeedChanged() { runOnUiThread(new Runnable() { public void run() { requestHudUpdate(); } }); }
    
    @Override 
    public void onApertureChanged() { runOnUiThread(new Runnable() { public void run() { requestHudUpdate(); } }); }
    
    @Override 
    public void onIsoChanged() { runOnUiThread(new Runnable() { public void run() { requestHudUpdate(); } }); }
    
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
                    focusMeter.update(ratioToFeed, apToFeed, focalToUse, isCalibrating, ptsToUse);
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
                    Log.d("filmOS_Lens", "Native Lens Zoomed: " + focalLengthMm + "mm");
                    
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
                    Log.d("filmOS_Lens", "Manual Lens Detected.");
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
                if (isMenuOpen && currentPage == 7) renderMenu(); 
            }
        });
    }

    private void enterPlayback() {
        playbackFiles.clear();
        File dir = new File(Environment.getExternalStorageDirectory(), "GRADED");
        if (dir.exists() && dir.listFiles() != null) {
            for (File f : dir.listFiles()) {
                if (f.getName().toLowerCase().endsWith(".jpg")) playbackFiles.add(f);
            }
        }
        Collections.sort(playbackFiles, new Comparator<File>() { 
            @Override public int compare(File f1, File f2) { return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified()); } 
        });
        if (playbackFiles.isEmpty()) return;
        
        isPlaybackMode = true; 
        mainUIContainer.setVisibility(View.GONE); 
        playbackContainer.setVisibility(View.VISIBLE); 
        showPlaybackImage(0);
    }

    private void exitPlayback() {
        isPlaybackMode = false;
        playbackContainer.setVisibility(View.GONE);
        mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
        if (playbackImageView != null) playbackImageView.setImageBitmap(null);
        if (currentPlaybackBitmap != null) { currentPlaybackBitmap.recycle(); currentPlaybackBitmap = null; }
    }

    private void showPlaybackImage(int idx) {
        if (playbackFiles.isEmpty()) return;
        if (idx < 0) idx = playbackFiles.size() - 1; 
        if (idx >= playbackFiles.size()) idx = 0; 
        
        playbackIndex = idx; 
        File file = playbackFiles.get(idx);
        
        try {
            if (playbackImageView != null) playbackImageView.setImageBitmap(null);
            if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) { 
                currentPlaybackBitmap.recycle(); 
                currentPlaybackBitmap = null; 
            }
            
            System.gc();

            if (file.length() == 0) {
                if (tvPlaybackInfo != null) tvPlaybackInfo.setText((idx + 1) + "/" + playbackFiles.size() + "\n[ERROR: 0-BYTE FILE]");
                return;
            }
            
            String path = file.getAbsolutePath();
            ExifInterface exif = new ExifInterface(path);
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

            String metaText = (idx + 1) + " / " + playbackFiles.size() + "\n" + file.getName() + "\n" + apStr + " | " + speedStr + " | " + isoStr;
            if (tvPlaybackInfo != null) tvPlaybackInfo.setText(metaText);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            
            final int reqWidth = 1024;
            final int reqHeight = 768;
            int inSampleSize = 1;

            if (opts.outHeight > reqHeight || opts.outWidth > reqWidth) {
                final int halfHeight = opts.outHeight / 2;
                final int halfWidth = opts.outWidth / 2;
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = inSampleSize;
            opts.inPreferredConfig = Bitmap.Config.RGB_565; 
            opts.inPurgeable = true;
            opts.inInputShareable = true;

            Bitmap raw = BitmapFactory.decodeFile(path, opts);
            if (raw == null) return;

            int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rot = 0; 
            if (orient == ExifInterface.ORIENTATION_ROTATE_90) rot = 90; 
            else if (orient == ExifInterface.ORIENTATION_ROTATE_180) rot = 180; 
            else if (orient == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            
            Matrix m = new Matrix(); 
            if (rot != 0) m.postRotate(rot); 
            m.postScale(0.8888f, 1.0f); 
            
            Bitmap bmp = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            
            if (raw != bmp) {
                raw.recycle();
                raw = null;
            }
            
            if (playbackImageView != null) {
                playbackImageView.setImageBitmap(bmp);
                playbackImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
            currentPlaybackBitmap = bmp;
            
        } catch (OutOfMemoryError oom) {
            android.util.Log.e("filmOS", "OOM Memory limit hit during playback. Recovering...");
            if (tvPlaybackInfo != null) tvPlaybackInfo.setText((idx + 1) + " / " + playbackFiles.size() + "\n[MEMORY ERROR - SKIPPED]");
            if (currentPlaybackBitmap != null) { 
                currentPlaybackBitmap.recycle(); 
                currentPlaybackBitmap = null; 
            }
            System.gc(); 
        } catch (Exception e) {
            android.util.Log.e("filmOS", "Playback error: " + e.getMessage());
        }
    }

    private void syncHardwareState() {
        if (cameraManager == null || cameraManager.getCamera() == null) return;
        Camera c = cameraManager.getCamera();
        String fMode = c.getParameters().getFocusMode();
        cachedIsManualFocus = "manual".equals(fMode);
    }
}