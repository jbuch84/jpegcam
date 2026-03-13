package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LensProfileManager {

    public static class CalPoint {
        public final float ratio;     
        public final float distance;  

        public CalPoint(float ratio, float distance) {
            this.ratio = ratio;
            this.distance = distance;
        }
    }

    // Active State
    public float currentFocalLength = 50.0f;
    public float currentMaxAperture = 2.8f;
    public String currentLensName = "Unmapped Lens";
    private List<CalPoint> activePoints = new ArrayList<CalPoint>();
    private boolean hasActiveProfile = false;

    public LensProfileManager(Context context) {
        // Constructor left intentionally blank to avoid stale SD card references on boot.
    }
    
    // Dynamically fetches the root exactly like GRADED does
    private File getLensesDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), "LENSES");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static String generateFilename(float focalLength, float maxAperture) {
        int focalInt = (int) focalLength;
        int apInt = Math.round(maxAperture * 10.0f); 
        return focalInt + "mm" + apInt + ".txt"; // Using .txt to bypass Sony OS restrictions
    }

    public List<CalPoint> generateManualDummyProfile() {
        List<CalPoint> ghostPoints = new ArrayList<CalPoint>();
        ghostPoints.add(new CalPoint(0.0f, 0.3f)); // Virtual Near
        ghostPoints.add(new CalPoint(0.5f, 2.0f)); // Virtual Mid
        ghostPoints.add(new CalPoint(1.0f, 999.0f)); // Virtual Infinity
        return ghostPoints;
    }
    
    public List<String> getAvailableLenses() {
        List<String> lenses = new ArrayList<String>();
        File dir = getLensesDir();
        if (dir.exists() && dir.listFiles() != null) {
            for (File f : dir.listFiles()) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".txt") && name.contains("mm")) {
                    lenses.add(f.getName());
                }
            }
        }
        Collections.sort(lenses); 
        return lenses;
    }

    public void saveProfileToFile(float focalLength, float maxAperture, List<CalPoint> points) {
        File dir = getLensesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e("filmOS_Lens", "CRITICAL: Could not create LENSES directory.");
            return;
        }

        String filename = generateFilename(focalLength, maxAperture);
        File outFile = new File(dir, filename);

        try {
            // Using FileOutputStream mirroring native byte writing for safety
            FileOutputStream fos = new FileOutputStream(outFile);
            
            fos.write(("FOCAL:" + focalLength + "\n").getBytes());
            fos.write(("APERTURE:" + maxAperture + "\n").getBytes());
            
            StringBuilder ptsBuilder = new StringBuilder();
            for (int i = 0; i < points.size(); i++) {
                ptsBuilder.append(points.get(i).ratio).append(",").append(points.get(i).distance);
                if (i < points.size() - 1) ptsBuilder.append(";");
            }
            fos.write(("POINTS:" + ptsBuilder.toString() + "\n").getBytes());
            
            fos.flush();
            fos.close();
            Log.d("filmOS_Lens", "Saved lens profile to SD Card: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("filmOS_Lens", "Failed to save lens file: " + e.getMessage());
        }
    }

    public void loadProfileFromFile(String filename) {
        File dir = getLensesDir();
        File inFile = new File(dir, filename);
        if (!inFile.exists()) {
            clearCurrentProfile();
            return;
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(inFile));
            String line;
            
            float loadedFocal = 50.0f;
            float loadedAperture = 2.8f;
            List<CalPoint> loadedPoints = new ArrayList<CalPoint>();

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("FOCAL:")) {
                    loadedFocal = Float.parseFloat(line.split(":")[1]);
                } else if (line.startsWith("APERTURE:")) {
                    loadedAperture = Float.parseFloat(line.split(":")[1]);
                } else if (line.startsWith("POINTS:")) {
                    String[] pairs = line.split(":")[1].split(";");
                    for (String pair : pairs) {
                        String[] parts = pair.split(",");
                        if (parts.length == 2) {
                            loadedPoints.add(new CalPoint(Float.parseFloat(parts[0]), Float.parseFloat(parts[1])));
                        }
                    }
                }
            }
            reader.close();

            this.currentFocalLength = loadedFocal;
            this.currentMaxAperture = loadedAperture;
            this.currentLensName = filename.replace(".txt", "");
            this.activePoints = loadedPoints;
            this.hasActiveProfile = true;
            
            Log.d("filmOS_Lens", "Loaded profile from SD: " + filename);

        } catch (Exception e) {
            Log.e("filmOS_Lens", "Failed to load lens file: " + e.getMessage());
            clearCurrentProfile();
        }
    }

    public void clearCurrentProfile() {
        this.currentLensName = "Unmapped Lens";
        this.activePoints.clear();
        this.hasActiveProfile = false;
    }

    public boolean hasActiveProfile() {
        return hasActiveProfile && activePoints.size() >= 2;
    }

    public List<CalPoint> getCurrentPoints() {
        return activePoints;
    }

    public float getCurrentFocalLength() {
        return currentFocalLength;
    }
    
    public String getCurrentLensName() {
        return currentLensName;
    }

    public float getDistanceForRatio(float targetRatio) {
        if (activePoints.size() < 2) return -1f;
        
        for (int i = 0; i < activePoints.size() - 1; i++) {
            CalPoint p1 = activePoints.get(i);
            CalPoint p2 = activePoints.get(i + 1);
            
            if (targetRatio >= p1.ratio && targetRatio <= p2.ratio) {
                float range = p2.ratio - p1.ratio;
                float pct = (targetRatio - p1.ratio) / range;
                return p1.distance + (pct * (p2.distance - p1.distance));
            }
        }
        return -1f;
    }
}