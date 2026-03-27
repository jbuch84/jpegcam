package com.github.ma1co.pmcademo.app;

import android.util.Log;
import java.io.*;
import java.util.ArrayList;

public class RecipeManager {
    private RTLProfile[] profiles = new RTLProfile[10];
    private int currentSlot = 0;
    private int qualityIndex = 1;
    private ArrayList<String> recipePaths = new ArrayList<String>();
    private ArrayList<String> recipeNames = new ArrayList<String>();

    public RecipeManager() {
        for (int i = 0; i < 10; i++) profiles[i] = new RTLProfile(i);
        scanRecipes();
    }

    public int getCurrentSlot() { return currentSlot; }
    public void setCurrentSlot(int slot) { this.currentSlot = (slot + 10) % 10; }
    public int getQualityIndex() { return qualityIndex; }
    public void setQualityIndex(int index) { this.qualityIndex = (index + 3) % 3; }
    public RTLProfile getCurrentProfile() { return profiles[currentSlot]; }
    public ArrayList<String> getRecipePaths() { return recipePaths; }
    public ArrayList<String> getRecipeNames() { return recipeNames; }

    // Restored for MainActivity.java
    public RTLProfile getProfile(int index) {
        return profiles[index];
    }

    public void scanRecipes() { 
        recipePaths.clear(); 
        recipeNames.clear(); 
        recipePaths.add("NONE"); 
        recipeNames.add("NONE"); 
        
        for (File root : Filepaths.getStorageRoots()) {
            File lutDir = new File(root, "JPEGCAM/LUTS");
            if (lutDir.exists() && lutDir.isDirectory()) {
                Log.d("JPEG.CAM", "Scanning: " + lutDir.getAbsolutePath());
                File[] files = lutDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String u = f.getName().toUpperCase();
                        if (!u.startsWith(".") && (u.endsWith(".CUB") || u.endsWith(".CUBE"))) {
                            if (!recipePaths.contains(f.getAbsolutePath())) {
                                recipePaths.add(f.getAbsolutePath());
                                String name = u.replace(".CUBE", "").replace(".CUB", "");
                                try {
                                    BufferedReader br = new BufferedReader(new FileReader(f));
                                    String line;
                                    for(int j=0; j<10; j++) {
                                        line = br.readLine();
                                        if (line != null && line.toUpperCase().startsWith("TITLE")) {
                                            name = line.split("\"")[1].toUpperCase();
                                            break;
                                        }
                                    }
                                    br.close();
                                } catch (Exception e) {}
                                recipeNames.add(name);
                            }
                        }
                    }
                }
            }
        }
    }

    public void savePreferences() {
        try {
            File lutDir = Filepaths.getLutDir();
            if (!lutDir.exists()) lutDir.mkdirs(); 
            File backupFile = new File(lutDir, "RTLBAK.TXT");
            
            FileOutputStream fos = new FileOutputStream(backupFile);
            StringBuilder sb = new StringBuilder();
            sb.append("quality=").append(qualityIndex).append("\n");
            sb.append("slot=").append(currentSlot).append("\n");
            
            for(int i=0; i<10; i++) {
                RTLProfile p = profiles[i];
                String path = (p.lutIndex >= 0 && p.lutIndex < recipePaths.size()) ? recipePaths.get(p.lutIndex) : "NONE";
                
                // ROBUST FIX 1: Strip commas from names so they don't break the CSV format!
                String safeName = p.profileName != null ? p.profileName.replace(",", "") : "";
                
                sb.append(i).append(",").append(path).append(",") // 0, 1
                  .append(p.opacity).append(",").append(p.grain).append(",") // 2, 3
                  .append(p.grainSize).append(",").append(p.rollOff).append(",") // 4, 5
                  .append(p.vignette).append(",") // 6
                  .append(p.whiteBalance).append(",") // 7
                  .append(p.wbShift).append(",") // 8
                  .append(p.dro).append(",") // 9
                  .append(p.wbShiftGM).append(",") // 10
                  .append(p.contrast).append(",") // 11
                  .append(p.saturation).append(",") // 12
                  .append(p.sharpness).append(",") // 13
                  .append(safeName).append(",") // 14
                  .append(p.colorMode).append(",") // 15
                  .append(p.sharpnessGain).append(",") // 16
                  .append(p.colorDepthRed).append(":").append(p.colorDepthGreen).append(":")
                  .append(p.colorDepthBlue).append(":").append(p.colorDepthCyan).append(":")
                  .append(p.colorDepthMagenta).append(":").append(p.colorDepthYellow).append(",") // 17
                  .append(p.advMatrix[0]).append(":").append(p.advMatrix[1]).append(":")
                  .append(p.advMatrix[2]).append(":").append(p.advMatrix[3]).append(":")
                  .append(p.advMatrix[4]).append(":").append(p.advMatrix[5]).append(":")
                  .append(p.advMatrix[6]).append(":").append(p.advMatrix[7]).append(":")
                  .append(p.advMatrix[8]).append(",") // 18
                  .append(p.proColorMode).append(",") // 19
                  .append(p.pictureEffect).append(",") // 20
                  .append(p.peToyCameraTone).append(",") // 21
                  .append(p.vignetteHardware).append(",") // 22
                  .append(p.softFocusLevel).append(",") // 23
                  .append(p.shadingRed).append(",") // 24
                  .append(p.shadingBlue).append(",") // 25
                  .append(p.colorChrome).append(",") // 26
                  .append(p.chromeBlue).append(",") // 27
                  .append(p.shadowToe).append(",") // 28
                  .append(p.subtractiveSat).append(",") // 29
                  .append(p.halation).append("\n"); // 30
            }
            fos.write(sb.toString().getBytes()); 
            fos.flush(); 
            fos.getFD().sync(); 
            fos.close();
        } catch (Exception e) {
            Log.e("JPEG.CAM", "Failed to save RTLBAK: " + e.getMessage());
        }
    }

    public void loadPreferences() {
        File backupFile = new File(Filepaths.getLutDir(), "RTLBAK.TXT");
        if (backupFile.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(backupFile));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("quality=")) qualityIndex = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("slot=")) currentSlot = Integer.parseInt(line.split("=")[1]);
                    else if (!line.startsWith("prefs=")) { 
                        String[] parts = line.split(",", -1); 
                        
                        if (parts.length >= 6) {
                            int idx = Integer.parseInt(parts[0]); 
                            int foundIndex = recipePaths.indexOf(parts[1]);
                            
                            RTLProfile p = profiles[idx];
                            p.lutIndex = (foundIndex != -1) ? foundIndex : 0;
                            p.opacity = Integer.parseInt(parts[2]); 
                            if (p.opacity <= 5) p.opacity = 100;
                            p.grain = Math.min(5, Integer.parseInt(parts[3]));
                            
                            if (parts.length >= 7) {
                                p.grainSize = Math.min(2, Integer.parseInt(parts[4]));
                                p.rollOff = Math.min(5, Integer.parseInt(parts[5])); 
                                p.vignette = Math.min(5, Integer.parseInt(parts[6]));
                            }
                            if (parts.length >= 10) {
                                p.whiteBalance = parts[7];
                                p.wbShift = Integer.parseInt(parts[8]);
                                p.dro = parts[9];
                            }
                            if (parts.length >= 11) p.wbShiftGM = Integer.parseInt(parts[10]);
                            if (parts.length >= 14) {
                                p.contrast = Integer.parseInt(parts[11]);
                                p.saturation = Integer.parseInt(parts[12]);
                                p.sharpness = Integer.parseInt(parts[13]);
                            }
                            if (parts.length >= 15) p.profileName = parts[14];
                            
                            if (parts.length >= 26) {
                                p.colorMode = parts[15];
                                p.sharpnessGain = Integer.parseInt(parts[16]);
                                
                                // ROBUST FIX 2: Mini try-catches for arrays to prevent domino-crashing
                                try {
                                    String[] cDepths = parts[17].split(":");
                                    if (cDepths.length == 6) {
                                        p.colorDepthRed = Integer.parseInt(cDepths[0]); p.colorDepthGreen = Integer.parseInt(cDepths[1]);
                                        p.colorDepthBlue = Integer.parseInt(cDepths[2]); p.colorDepthCyan = Integer.parseInt(cDepths[3]);
                                        p.colorDepthMagenta = Integer.parseInt(cDepths[4]); p.colorDepthYellow = Integer.parseInt(cDepths[5]);
                                    }
                                } catch (Exception e) {} // Silently ignore bad color depths, keeping defaults
                                
                                try {
                                    String[] mtx = parts[18].split(":");
                                    if (mtx.length == 9) {
                                        for(int m=0; m<9; m++) p.advMatrix[m] = Integer.parseInt(mtx[m]);
                                    }
                                } catch (Exception e) {} // Silently ignore bad matrix arrays, keeping defaults
                                
                                p.proColorMode = parts[19]; p.pictureEffect = parts[20]; p.peToyCameraTone = parts[21];
                                p.vignetteHardware = Integer.parseInt(parts[22]); p.softFocusLevel = Integer.parseInt(parts[23]);
                                p.shadingRed = Integer.parseInt(parts[24]); p.shadingBlue = Integer.parseInt(parts[25]);
                            }

                            if (parts.length >= 28) {
                                p.colorChrome = Integer.parseInt(parts[26]);
                                p.chromeBlue = Integer.parseInt(parts[27]);
                            }

                            if (parts.length >= 31) {
                                p.shadowToe = Integer.parseInt(parts[28]);
                                p.subtractiveSat = Integer.parseInt(parts[29]);
                                p.halation = Integer.parseInt(parts[30]);
                            }
                        }
                    }
                }
                br.close(); 
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Failed to load RTLBAK fully: " + e.getMessage());
            }
        }
    } 
}