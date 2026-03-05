package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.Html;
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
    private LinearLayout[] menuRows = new LinearLayout[12];
    private TextView[] menuLabels = new TextView[12];
    private TextView[] menuValues = new TextView[12];
    
    private TextView tvBottomBar, tvTopStatus, tvBattery, tvReview, tvMode, tvFocusMode; 
    
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
    
    private ImageProcessor mProcessor;
    private SonyFileScanner mScanner;
    private String sonyDCIMPath = "";
    
    private ProReticleView afOverlay;
    private AdvancedFocusMeterView focusMeter; 
    private ArrayList<String> recipePaths = new ArrayList<String>();
    private ArrayList<String> recipeNames = new ArrayList<String>();

    private final String[] intensityLabels = {"OFF", "LOW", "LOW+", "MID", "MID+", "HIGH"};
    private final String[] grainSizeLabels = {"SM", "MED", "LG"};

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
    public static final int DIAL_MODE_PASM = 6;
    public static final int DIAL_MODE_FOCUS = 7;
    private int mDialMode = DIAL_MODE_RTL;

    private float lastKnownFocusRatio = 0.5f;
    private float lastKnownAperture = 2.8f;

    private int lastBatteryLevel = 100;
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                lastBatteryLevel = (level * 100) / scale;
                if (tvBattery != null) tvBattery.setText(lastBatteryLevel + "%");
            }
        }
    };

    private Handler uiHandler = new Handler();
    private Runnable liveUpdater = new Runnable() {
        @Override
        public void run() {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode && !isProcessing && hasSurface && mCamera != null) {
                updateMainHUD();
            }
            uiHandler.postDelayed(this, 500); 
        }
    };

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
            if (f.exists()) {
                sonyDCIMPath = f.getAbsolutePath();
                break;
            }
        }
        if (sonyDCIMPath.isEmpty()) sonyDCIMPath = possibleRoots[0] + "/DCIM/100MSDCF";
        
        setupEngines();
        triggerLutPreload();
    }

    private void setupEngines() {
        mProcessor = new ImageProcessor(this, new ImageProcessor.ProcessorCallback() {
            @Override public void onPreloadStarted() { isReady = false; runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
            @Override public void onPreloadFinished(boolean success) { isReady = true; runOnUiThread(new Runnable() { @Override public void run() { updateMainHUD(); } }); }
            @Override public void onProcessStarted() { 
                isProcessing = true; 
                runOnUiThread(new Runnable() { @Override public void run() { tvTopStatus.setText("PROCESSING..."); tvTopStatus.setTextColor(Color.YELLOW); } }); 
            }
            @Override public void onProcessFinished(String result) { 
                isProcessing = false; 
                runOnUiThread(new Runnable() { @Override public void run() { tvTopStatus.setTextColor(Color.WHITE); updateMainHUD(); } }); 
            }
        });

        mScanner = new SonyFileScanner(sonyDCIMPath, new SonyFileScanner.ScannerCallback() {
            @Override public boolean isReadyToProcess() { return isReady && !isProcessing && profiles[currentSlot].lutIndex != 0; }
            @Override public void onNewPhotoDetected(final String filePath) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        File outDir = new File(Environment.getExternalStorageDirectory(), "GRADED");
                        mProcessor.processJpeg(filePath, outDir.getAbsolutePath(), qualityIndex, profiles[currentSlot]);
                    }
                });
            }
        });
    }

    private void buildUI(FrameLayout rootLayout) {
        mainUIContainer = new FrameLayout(this);
        rootLayout.addView(mainUIContainer, new FrameLayout.LayoutParams(-1, -1));

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

        View batteryIcon = new View(this) {
            @Override protected void onDraw(Canvas canvas) { drawSonyBattery(canvas, this); }
        };
        batteryArea.addView(batteryIcon, new LinearLayout.LayoutParams(40, 22));
        rightBar.addView(batteryArea);

        tvReview = createSideTextIcon("▶");
        tvReview.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                mDialMode = (mDialMode == DIAL_MODE_REVIEW) ? DIAL_MODE_RTL : DIAL_MODE_REVIEW;
                updateMainHUD();
            }
        });
        LinearLayout.LayoutParams rvParams = new LinearLayout.LayoutParams(-2, -2);
        rvParams.setMargins(0, 20, 0, 0);
        tvReview.setLayoutParams(rvParams);
        rightBar.addView(tvReview);

        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        rightParams.setMargins(0, 20, 20, 0);
        mainUIContainer.addView(rightBar, rightParams);

        LinearLayout leftBar = new LinearLayout(this);
        leftBar.setOrientation(LinearLayout.VERTICAL);
        
        tvMode = createSideTextIcon("M");
        tvMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                mDialMode = (mDialMode == DIAL_MODE_PASM) ? DIAL_MODE_RTL : DIAL_MODE_PASM;
                updateMainHUD();
            }
        });
        leftBar.addView(tvMode);

        tvFocusMode = createSideTextIcon("AF-S");
        tvFocusMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                mDialMode = (mDialMode == DIAL_MODE_FOCUS) ? DIAL_MODE_RTL : DIAL_MODE_FOCUS;
                updateMainHUD();
            }
        });
        leftBar.addView(tvFocusMode);
        
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        leftParams.setMargins(20, 20, 0, 0);
        mainUIContainer.addView(leftBar, leftParams);

        focusMeter = new AdvancedFocusMeterView(this);
        FrameLayout.LayoutParams fmParams = new FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        fmParams.setMargins(0, 0, 0, 100); 
        mainUIContainer.addView(focusMeter, fmParams);

        tvBottomBar = new TextView(this);
        tvBottomBar.setTextSize(26);
        tvBottomBar.setTypeface(Typeface.DEFAULT_BOLD);
        tvBottomBar.setShadowLayer(4, 0, 0, Color.BLACK);
        tvBottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams botParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        botParams.setMargins(0, 0, 0, 25);
        mainUIContainer.addView(tvBottomBar, botParams);

        afOverlay = new ProReticleView(this);
        mainUIContainer.addView(afOverlay, new FrameLayout.LayoutParams(-1, -1));

        menuContainer = new LinearLayout(this);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        menuContainer.setBackgroundColor(Color.argb(250, 15, 15, 15)); 
        menuContainer.setPadding(30, 30, 30, 30);
        
        for (int i = 0; i < 12; i++) { 
            menuRows[i] = new LinearLayout(this);
            menuRows[i].setOrientation(LinearLayout.HORIZONTAL);
            menuRows[i].setGravity(Gravity.CENTER_VERTICAL);
            menuRows[i].setPadding(10, 0, 10, 0);
            
            menuContainer.addView(menuRows[i], new LinearLayout.LayoutParams(-1, 0, 1.0f));
            
            menuLabels[i] = new TextView(this); menuLabels[i].setTextSize(22); menuLabels[i].setTypeface(Typeface.DEFAULT_BOLD);
            menuValues[i] = new TextView(this); menuValues[i].setTextSize(22); menuValues[i].setGravity(Gravity.RIGHT);
            
            menuRows[i].addView(menuLabels[i], new LinearLayout.LayoutParams(0, -2, 1.0f));
            menuRows[i].addView(menuValues[i], new LinearLayout.LayoutParams(-2, -2));

            if (i < 11) {
                View divider = new View(this); divider.setBackgroundColor(Color.DKGRAY);
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

        updateMainHUD();
        renderMenu();
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

    private void drawSonyBattery(Canvas canvas, View v) {
        Paint p = new Paint(); p.setAntiAlias(true); p.setStrokeWidth(2);
        p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE);
        canvas.drawRect(2, 2, v.getWidth() - 6, v.getHeight() - 2, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawRect(v.getWidth() - 6, v.getHeight()/2 - 4, v.getWidth() - 2, v.getHeight()/2 + 4, p);
        int barColor = (lastBatteryLevel < 15) ? Color.RED : Color.WHITE;
        p.setColor(barColor);
        int fillW = (v.getWidth() - 12);
        if (lastBatteryLevel > 10) canvas.drawRect(6, 6, 6 + (fillW/3) - 2, v.getHeight() - 6, p);
        if (lastBatteryLevel > 40) canvas.drawRect(6 + (fillW/3) + 2, 6, 6 + (2*fillW/3) - 2, v.getHeight() - 6, p);
        if (lastBatteryLevel > 70) canvas.drawRect(6 + (2*fillW/3) + 2, 6, v.getWidth() - 10, v.getHeight() - 6, p);
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
        if (playbackFiles.isEmpty()) { tvPlaybackInfo.setText("NO GRADED PHOTOS"); return; }
        
        if (index < 0) index = 0;
        if (index >= playbackFiles.size()) index = playbackFiles.size() - 1;
        playbackIndex = index;
        File imgFile = playbackFiles.get(playbackIndex);
        
        if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) {
            playbackImageView.setImageBitmap(null); currentPlaybackBitmap.recycle(); currentPlaybackBitmap = null;
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

            String metaText = (playbackIndex + 1) + " / " + playbackFiles.size() + "\n" + imgFile.getName() + "\n" + apStr + " | " + speedStr + " | " + isoStr;
            tvPlaybackInfo.setText(metaText);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true; 
            BitmapFactory.decodeFile(imgFile.getAbsolutePath(), options);
            
            int scale = 1; 
            while ((options.outWidth / scale) > 1200 || (options.outHeight / scale) > 1200) { scale *= 2; }
            
            options.inJustDecodeBounds = false; options.inSampleSize = scale; options.inPreferQualityOverSpeed = true; 
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
        } catch (Exception e) { tvPlaybackInfo.setText("DECODE ERROR"); }
    }

    private void exitPlayback() {
        playbackContainer.setVisibility(View.GONE); mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
        isPlaybackMode = false;
        if (currentPlaybackBitmap != null && !currentPlaybackBitmap.isRecycled()) {
            playbackImageView.setImageBitmap(null); currentPlaybackBitmap.recycle(); currentPlaybackBitmap = null;
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
        editor.putBoolean("has_saved", true); editor.putInt("qualityIndex", qualityIndex); editor.putInt("currentSlot", currentSlot);
        for(int i=0; i<10; i++) {
            editor.putString("slot_" + i + "_lutPath", recipePaths.get(profiles[i].lutIndex));
            editor.putInt("slot_" + i + "_opac", profiles[i].opacity); editor.putInt("slot_" + i + "_grain", profiles[i].grain);
            editor.putInt("slot_" + i + "_gSize", profiles[i].grainSize); editor.putInt("slot_" + i + "_roll", profiles[i].rollOff);
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
            sb.append("quality=").append(qualityIndex).append("\n").append("slot=").append(currentSlot).append("\n");
            for(int i=0; i<10; i++) {
                sb.append(i).append(",").append(recipePaths.get(profiles[i].lutIndex)).append(",")
                  .append(profiles[i].opacity).append(",").append(profiles[i].grain).append(",")
                  .append(profiles[i].grainSize).append(",").append(profiles[i].rollOff).append(",").append(profiles[i].vignette).append("\n");
            }
            fos.write(sb.toString().getBytes()); fos.flush(); fos.getFD().sync(); fos.close();
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
                            int idx = Integer.parseInt(parts[0]); int foundIndex = recipePaths.indexOf(parts[1]);
                            profiles[idx].lutIndex = (foundIndex != -1) ? foundIndex : 0;
                            profiles[idx].opacity = Integer.parseInt(parts[2]); if (profiles[idx].opacity <= 5) profiles[idx].opacity = 100;
                            profiles[idx].grain = Math.min(5, Integer.parseInt(parts[3]));
                            if (parts.length == 7) {
                                profiles[idx].grainSize = Math.min(2, Integer.parseInt(parts[4]));
                                profiles[idx].rollOff = Math.min(5, Integer.parseInt(parts[5])); profiles[idx].vignette = Math.min(5, Integer.parseInt(parts[6]));
                            } else {
                                profiles[idx].grainSize = 1; profiles[idx].rollOff = Math.min(5, Integer.parseInt(parts[4])); profiles[idx].vignette = Math.min(5, Integer.parseInt(parts[5]));
                            }
                        }
                    }
                }
                br.close(); savePreferences(); return;
            } catch (Exception e) {}
        }

        qualityIndex = prefs.getInt("qualityIndex", 1); currentSlot = prefs.getInt("currentSlot", 0);
        for(int i=0; i<10; i++) {
            String savedPath = prefs.getString("slot_" + i + "_lutPath", "NONE"); int foundIndex = recipePaths.indexOf(savedPath);
            profiles[i].lutIndex = (foundIndex != -1) ? foundIndex : 0; 
            profiles[i].opacity = prefs.getInt("slot_" + i + "_opac", 100); if (profiles[i].opacity <= 5) profiles[i].opacity = 100; 
            profiles[i].grain = Math.min(5, prefs.getInt("slot_" + i + "_grain", 0)); profiles[i].grainSize = Math.min(2, prefs.getInt("slot_" + i + "_gSize", 1));
            profiles[i].rollOff = Math.min(5, prefs.getInt("slot_" + i + "_roll", 0)); profiles[i].vignette = Math.min(5, prefs.getInt("slot_" + i + "_vig", 0));
        }
    }

    @Override 
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        if (sc == ScalarInput.ISV_KEY_S1_1 && event.getRepeatCount() == 0) {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode) {
                tvTopStatus.setVisibility(View.GONE); tvBottomBar.setVisibility(View.GONE);
                tvBattery.setVisibility(View.GONE); tvMode.setVisibility(View.GONE); tvFocusMode.setVisibility(View.GONE); tvReview.setVisibility(View.GONE);
                if (focusMeter != null) focusMeter.setVisibility(View.GONE);
            }
            if (afOverlay != null && mCamera != null) { 
                try {
                    String fm = mCamera.getParameters().getFocusMode();
                    if (!"manual".equals(fm)) afOverlay.startFocus(mCamera); 
                } catch (Exception e) {}
            }
            return super.onKeyDown(keyCode, event);
        }

        if (sc == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        if (isPlaybackMode) {
            if (sc == ScalarInput.ISV_KEY_LEFT || sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { showPlaybackImage(playbackIndex + 1); return true; }
            if (sc == ScalarInput.ISV_KEY_RIGHT || sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { showPlaybackImage(playbackIndex - 1); return true; }
            if (sc == ScalarInput.ISV_KEY_ENTER || sc == ScalarInput.ISV_KEY_MENU || sc == ScalarInput.ISV_KEY_PLAY) { exitPlayback(); return true; }
            return true; 
        }

        if (sc == ScalarInput.ISV_KEY_MENU) {
            isMenuOpen = !isMenuOpen;
            if (isMenuOpen) {
                menuContainer.setVisibility(View.VISIBLE); mainUIContainer.setVisibility(View.GONE); renderMenu();
            } else {
                menuContainer.setVisibility(View.GONE); mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                savePreferences(); triggerLutPreload(); updateMainHUD();
            }
            return true;
        }

        if (sc == ScalarInput.ISV_KEY_ENTER) {
            if(!isMenuOpen) {
                if (mDialMode == DIAL_MODE_REVIEW) {
                    isPlaybackMode = true; refreshPlaybackFiles();
                    playbackContainer.setVisibility(View.VISIBLE); mainUIContainer.setVisibility(View.GONE); menuContainer.setVisibility(View.GONE);
                    showPlaybackImage(0); 
                } else {
                    displayState = (displayState == 0) ? 1 : 0; mainUIContainer.setVisibility(displayState == 0 ? View.VISIBLE : View.GONE);
                }
            }
            return true;
        }

        if (!isProcessing) {
            if (isMenuOpen) {
                if (sc == ScalarInput.ISV_KEY_UP) { menuSelection = (menuSelection - 1 + 12) % 12; renderMenu(); return true; }
                if (sc == ScalarInput.ISV_KEY_DOWN) { menuSelection = (menuSelection + 1) % 12; renderMenu(); return true; }
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
        if (event.getScanCode() == ScalarInput.ISV_KEY_S1_1) {
            if (displayState == 0 && !isMenuOpen && !isPlaybackMode) {
                tvTopStatus.setVisibility(View.VISIBLE); tvBottomBar.setVisibility(View.VISIBLE);
                tvBattery.setVisibility(View.VISIBLE); tvMode.setVisibility(View.VISIBLE); tvFocusMode.setVisibility(View.VISIBLE);
                if (focusMeter != null) focusMeter.setVisibility(View.VISIBLE);
                tvReview.setVisibility(View.VISIBLE);
            }
            if (afOverlay != null && mCamera != null) { 
                try {
                    String fm = mCamera.getParameters().getFocusMode();
                    if (!"manual".equals(fm)) afOverlay.stopFocus(mCamera); 
                } catch (Exception e) {}
            }
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
                case 11: 
                    Camera.Parameters cp = mCamera.getParameters();
                    List<String> scnModes = cp.getSupportedSceneModes();
                    if (scnModes != null && scnModes.size() > 0) {
                        int idx = scnModes.indexOf(cp.getSceneMode());
                        if (idx == -1) idx = 0;
                        cp.setSceneMode(scnModes.get((idx + dir + scnModes.size()) % scnModes.size()));
                        mCamera.setParameters(cp);
                    }
                    break;
            }
        } catch (Exception e) {}
        renderMenu();
    }

    private void renderMenu() {
        RTLProfile p = profiles[currentSlot];
        String[] qLabels = {"PROXY (1.5MP)", "HIGH (6MP)", "ULTRA (24MP)"};
        
        menuLabels[0].setText("Global Quality");   menuValues[0].setText(qLabels[qualityIndex]);
        menuLabels[1].setText("RTL Slot");         menuValues[1].setText(String.valueOf(currentSlot + 1));
        menuLabels[2].setText("LUT");              menuValues[2].setText(recipeNames.get(p.lutIndex));
        menuLabels[3].setText("Opacity");          menuValues[3].setText(p.opacity + "%");
        menuLabels[4].setText("Grain Amount");     menuValues[4].setText(intensityLabels[p.grain]);
        menuLabels[5].setText("Grain Size");       menuValues[5].setText(grainSizeLabels[p.grainSize]);
        menuLabels[6].setText("Highlight Roll");   menuValues[6].setText(intensityLabels[p.rollOff]);
        menuLabels[7].setText("Vignette");         menuValues[7].setText(intensityLabels[p.vignette]);
        menuLabels[8].setText("[LOCKED] W.Bal");   menuValues[8].setText(p.whiteBalance);
        menuLabels[9].setText("[LOCKED] WB Shift");menuValues[9].setText(String.valueOf(p.wbShift));
        menuLabels[10].setText("[LOCKED] DRO");    menuValues[10].setText(p.dro);

        String currentScene = "UNKNOWN";
        try {
            if(mCamera != null) {
                String sm = mCamera.getParameters().getSceneMode();
                if(sm != null) currentScene = sm.toUpperCase();
            }
        } catch(Exception e){}
        menuLabels[11].setText("Base Scene");      menuValues[11].setText(currentScene);

        for (int i = 0; i < 12; i++) {
            boolean sel = (i == menuSelection);
            menuRows[i].setBackgroundColor(sel ? Color.rgb(230, 50, 15) : Color.TRANSPARENT);
            boolean isLocked = (i >= 8 && i <= 10);
            menuLabels[i].setTextColor(sel ? Color.WHITE : (isLocked ? Color.GRAY : Color.WHITE));
            menuValues[i].setTextColor(sel ? Color.WHITE : (isLocked ? Color.GRAY : Color.WHITE));
        }
    }

    private void cycleMode(int dir) {
        mDialMode = (mDialMode + dir + 8) % 8;
        updateMainHUD();
    }

    private void handleInput(int d) {
        if (mCameraEx == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            
            if (mDialMode == DIAL_MODE_RTL) { currentSlot = (currentSlot + d + 10) % 10; triggerLutPreload(); }
            else if (mDialMode == DIAL_MODE_SHUTTER) { if (d > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed(); }
            else if (mDialMode == DIAL_MODE_APERTURE) { if (d > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture(); }
            else if (mDialMode == DIAL_MODE_ISO) {
                List<Integer> isos = (List<Integer>) pm.getSupportedISOSensitivities();
                if (isos != null) {
                    int idx = isos.indexOf(pm.getISOSensitivity());
                    if (idx != -1) { pm.setISOSensitivity(isos.get(Math.max(0, Math.min(isos.size()-1, idx + d)))); mCamera.setParameters(p); }
                }
            }
            else if (mDialMode == DIAL_MODE_EXPOSURE) { 
                p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), p.getExposureCompensation() + d))); 
                mCamera.setParameters(p); 
            }
            else if (mDialMode == DIAL_MODE_PASM) {
                List<String> modes = p.getSupportedSceneModes();
                if (modes != null) { 
                    List<String> validPasm = new ArrayList<String>();
                    String[] desired = {"manual-exposure", "aperture-priority", "shutter-priority", "program-auto", "auto", "intelligent-active"};
                    for(String m : desired) { if (modes.contains(m)) validPasm.add(m); }
                    
                    if(!validPasm.isEmpty()) {
                        int idx = validPasm.indexOf(p.getSceneMode());
                        if (idx == -1) idx = 0; 
                        p.setSceneMode(validPasm.get((idx + d + validPasm.size()) % validPasm.size())); 
                        mCamera.setParameters(p); 
                    }
                }
            }
            else if (mDialMode == DIAL_MODE_FOCUS) {
                List<String> modes = p.getSupportedFocusModes();
                if (modes != null && modes.size() > 1) {
                    int idx = modes.indexOf(p.getFocusMode());
                    if (idx != -1) { p.setFocusMode(modes.get((idx + d + modes.size()) % modes.size())); mCamera.setParameters(p); }
                }
            }
            updateMainHUD();
        } catch (Exception e) {}

        uiHandler.removeCallbacks(liveUpdater);
        uiHandler.postDelayed(liveUpdater, 1000); 
    }

    private void updateMainHUD() {
        if(mCamera == null) return;
        RTLProfile prof = profiles[currentSlot];
        
        String rawName = recipeNames.get(prof.lutIndex);
        String displayName = rawName.length() > 15 ? rawName.substring(0, 12) + "..." : rawName;
        
        if (!isProcessing) {
            tvTopStatus.setText("RTL " + (currentSlot + 1) + " [" + displayName + "]\n" + (isReady ? "READY" : "LOADING..."));
            tvTopStatus.setTextColor(mDialMode == DIAL_MODE_RTL ? Color.rgb(230, 50, 15) : Color.WHITE);
        }
        
        tvReview.setBackgroundColor(mDialMode == DIAL_MODE_REVIEW ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));

        try {
            Camera.Parameters params = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(params);
            
            tvMode.setBackgroundColor(mDialMode == DIAL_MODE_PASM ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));
            String sceneMode = params.getSceneMode();
            if (sceneMode != null) {
                if (sceneMode.equals(CameraEx.ParametersModifier.SCENE_MODE_MANUAL_EXPOSURE)) tvMode.setText("M");
                else if (sceneMode.equals(CameraEx.ParametersModifier.SCENE_MODE_APERTURE_PRIORITY)) tvMode.setText("A");
                else if (sceneMode.equals(CameraEx.ParametersModifier.SCENE_MODE_SHUTTER_PRIORITY)) tvMode.setText("S");
                else if (sceneMode.equals(CameraEx.ParametersModifier.SCENE_MODE_PROGRAM_AUTO)) tvMode.setText("P");
                else if (sceneMode.equals("auto") || sceneMode.equals("intelligent-active")) tvMode.setText("AUTO");
                else tvMode.setText("SCN"); 
            }

            tvFocusMode.setBackgroundColor(mDialMode == DIAL_MODE_FOCUS ? Color.rgb(230, 50, 15) : Color.argb(140, 40, 40, 40));
            String fMode = params.getFocusMode();
            if (fMode != null) {
                if (fMode.equals("manual")) tvFocusMode.setText("MF");
                else if (fMode.equals("auto")) tvFocusMode.setText("AF-S");
                else if (fMode.equals("continuous-picture")) tvFocusMode.setText("AF-C");
                else tvFocusMode.setText(fMode.toUpperCase());
            }

            lastKnownAperture = pm.getAperture() / 100.0f;
            if ("manual".equals(fMode)) {
                if (focusMeter != null) focusMeter.update(lastKnownFocusRatio, lastKnownAperture, true);
            } else {
                if (focusMeter != null) focusMeter.update(0, 0, false);
            }

            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            String ss = speed.first == 1 && speed.second != 1 ? speed.first + "/" + speed.second : speed.first + "\"";
            String ap = String.format("f%.1f", lastKnownAperture);
            String iso = pm.getISOSensitivity() == 0 ? "ISO AUTO" : "ISO " + pm.getISOSensitivity();
            String exp = String.format("%+.1f", params.getExposureCompensation() * params.getExposureCompensationStep());

            String cAct = "<font color='#E6320F'>"; String cDef = "<font color='#FFFFFF'>"; String cEnd = "</font>";
            String space = "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"; 

            StringBuilder sb = new StringBuilder();
            sb.append(mDialMode == DIAL_MODE_SHUTTER ? cAct : cDef).append(ss).append(cEnd).append(space);
            sb.append(mDialMode == DIAL_MODE_APERTURE ? cAct : cDef).append(ap).append(cEnd).append(space);
            sb.append(mDialMode == DIAL_MODE_ISO ? cAct : cDef).append(iso).append(cEnd).append(space);
            sb.append(mDialMode == DIAL_MODE_EXPOSURE ? cAct : cDef).append(exp).append(cEnd);

            tvBottomBar.setText(Html.fromHtml(sb.toString()));
        } catch (Exception e) {}
    }

    private void scanRecipes() { 
        recipePaths.clear(); recipeNames.clear(); recipePaths.add("NONE"); recipeNames.add("NONE");
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
        if(mProcessor != null) {
            mProcessor.triggerLutPreload(recipePaths.get(profiles[currentSlot].lutIndex), recipeNames.get(profiles[currentSlot].lutIndex));
        }
    }

    private void openCamera() {
        if (mCameraEx == null && hasSurface) {
            try { 
                mCameraEx = CameraEx.open(0, null); 
                mCamera = mCameraEx.getNormalCamera();
                mCameraEx.startDirectShutter(); 
                
                try {
                    Class<?> listenerClass = Class.forName("com.sony.scalar.hardware.CameraEx$FocusDriveListener");
                    Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[] { listenerClass },
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                                if (method.getName().equals("onChanged") && args != null && args.length == 2) {
                                    Object pos = args[0];
                                    if (pos != null) {
                                        int cur = pos.getClass().getField("currentPosition").getInt(pos);
                                        int max = pos.getClass().getField("maxPosition").getInt(pos);
                                        if (max > 0) {
                                            lastKnownFocusRatio = (float) cur / max;
                                            runOnUiThread(new Runnable() {
                                                @Override public void run() {
                                                    if (focusMeter != null && mDialMode == DIAL_MODE_FOCUS) {
                                                        focusMeter.update(lastKnownFocusRatio, lastKnownAperture, true);
                                                    }
                                                }
                                            });
                                        }
                                    }
                                }
                                return null;
                            }
                        }
                    );
                    mCameraEx.getClass().getMethod("setFocusDriveListener", listenerClass).invoke(mCameraEx, proxy);
                } catch (Exception e) {}

                CameraEx.AutoPictureReviewControl apr = new CameraEx.AutoPictureReviewControl();
                mCameraEx.setAutoPictureReviewControl(apr); 
                apr.setPictureReviewTime(0);
                mCamera.setPreviewDisplay(mSurfaceView.getHolder()); 
                mCamera.startPreview(); 
                
                try {
                    Camera.Parameters params = mCamera.getParameters();
                    CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(params);
                    pm.setDriveMode(CameraEx.ParametersModifier.DRIVE_MODE_SINGLE);
                    mCamera.setParameters(params);
                } catch(Exception e) {}

                updateMainHUD();
            } catch (Exception e) {} 
        }
    }

    private void closeCamera() {
        if (mCameraEx != null) { mCameraEx.release(); mCameraEx = null; mCamera = null; }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { hasSurface = true; openCamera(); }
    @Override public void surfaceDestroyed(SurfaceHolder h) { hasSurface = false; closeCamera(); }
    
    @Override protected void onResume() { 
        super.onResume(); openCamera(); 
        if (mCamera != null) updateMainHUD(); 
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (mScanner != null) mScanner.start(); 
        uiHandler.post(liveUpdater);
    }
    
    @Override protected void onPause() { 
        super.onPause(); closeCamera(); 
        try { unregisterReceiver(batteryReceiver); } catch (Exception e) {}
        if (mScanner != null) mScanner.stop(); 
        uiHandler.removeCallbacks(liveUpdater);
        savePreferences(); 
    }
    
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { updateMainHUD(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}

    // ==========================================
    // PHASE 3: CINEMA STYLE FOCUS METER
    // ==========================================
    private class AdvancedFocusMeterView extends View {
        private Paint trackPaint, needlePaint, dofPaint, textPaint;
        private float ratio = 0.5f; 
        private float aperture = 2.8f;
        private boolean isActive = false;

        public AdvancedFocusMeterView(Context context) {
            super(context);
            trackPaint = new Paint(); 
            trackPaint.setColor(Color.argb(150, 200, 200, 200)); 
            trackPaint.setStrokeWidth(4);
            
            dofPaint = new Paint(); 
            dofPaint.setColor(Color.argb(180, 50, 150, 255)); // Cinema Blue
            dofPaint.setStrokeWidth(12);
            dofPaint.setStrokeCap(Paint.Cap.ROUND);
            
            needlePaint = new Paint(); 
            needlePaint.setColor(Color.WHITE); 
            needlePaint.setStrokeWidth(6);
            needlePaint.setStrokeCap(Paint.Cap.ROUND);
            
            textPaint = new Paint(); 
            textPaint.setColor(Color.WHITE); 
            textPaint.setTextSize(18); 
            textPaint.setAntiAlias(true); 
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void update(float currentRatio, float fStop, boolean active) {
            this.ratio = currentRatio;
            this.aperture = fStop;
            this.isActive = active;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (!isActive) return;
            int w = getWidth();
            int h = getHeight();
            int pad = 50;
            int trackW = w - (pad * 2);
            int y = h / 2 + 10;

            canvas.drawLine(pad, y, w - pad, y, trackPaint);

            canvas.drawText("0.1m", pad, y - 20, textPaint);
            canvas.drawText("0.5m", pad + trackW * 0.25f, y - 20, textPaint);
            canvas.drawText("1m", pad + trackW * 0.5f, y - 20, textPaint);
            canvas.drawText("5m", pad + trackW * 0.75f, y - 20, textPaint);
            canvas.drawText("INF", w - pad, y - 20, textPaint);

            float needleX = pad + (trackW * ratio);

            float baseDof = (aperture / 22.0f) * (trackW * 0.15f);
            float distanceMultiplier = 1.0f + (ratio * 2.0f); 
            float dofRadius = baseDof * distanceMultiplier;

            canvas.save();
            canvas.clipRect(pad, 0, w - pad, h);
            canvas.drawLine(needleX - dofRadius, y, needleX + dofRadius, y, dofPaint);
            canvas.restore();
            
            canvas.drawLine(needleX, y - 15, needleX, y + 15, needlePaint);
        }
    }

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
            paint = new Paint(); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(6); paint.setAntiAlias(true);
        }

        public void startFocus(Camera cam) {
            if (cam == null) return;
            try {
                if ("manual".equals(cam.getParameters().getFocusMode())) return;
                fallbackState = STATE_SEARCHING;
                cam.autoFocus(new Camera.AutoFocusCallback() {
                    @Override public void onAutoFocus(boolean success, Camera camera) { fallbackState = success ? STATE_LOCKED : STATE_FAILED; invalidate(); }
                });
                isPolling = true; invalidate();
            } catch (Exception e) {}
        }

        public void stopFocus(Camera cam) {
            isPolling = false; fallbackState = STATE_IDLE; invalidate();
            if (cam != null) { 
                try { 
                    if (!"manual".equals(cam.getParameters().getFocusMode())) cam.cancelAutoFocus(); 
                } catch (Exception e) {} 
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!isPolling) return;
            switch (fallbackState) {
                case STATE_IDLE:      paint.setColor(Color.argb(100, 255, 255, 255)); break;
                case STATE_SEARCHING: paint.setColor(Color.YELLOW); break;
                case STATE_LOCKED:    paint.setColor(Color.GREEN); break;
                case STATE_FAILED:    paint.setColor(Color.RED); break;
            }
            int cx = getWidth() / 2, cy = getHeight() / 2, size = 60, bracket = 15;
            canvas.drawLine(cx-size, cy-size, cx-size+bracket, cy-size, paint); canvas.drawLine(cx-size, cy-size, cx-size, cy-size+bracket, paint);
            canvas.drawLine(cx+size, cy-size, cx+size-bracket, cy-size, paint); canvas.drawLine(cx+size, cy-size, cx+size, cy-size+bracket, paint);
            canvas.drawLine(cx-size, cy+size, cx-size+bracket, cy+size, paint); canvas.drawLine(cx-size, cy+size, cx-size, cy+size-bracket, paint);
            canvas.drawLine(cx+size, cy+size, cx+size-bracket, cy+size, paint); canvas.drawLine(cx+size, cy+size, cx+size, cy+size-bracket, paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy, 3, paint); paint.setStyle(Paint.Style.STROKE);
            postInvalidateDelayed(50);
        }
    }
}