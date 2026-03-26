package com.github.ma1co.pmcademo.app;

import android.os.Environment;
import android.util.Log;
import java.io.*;
import java.util.ArrayList;

public class RecipeManager {
    private RTLProfile[] profiles = new RTLProfile[10];
    private int currentSlot = 0;
    private int qualityIndex = 1;
    
    private ArrayList<String> recipePaths = new ArrayList<String>();
    private ArrayList<String> recipeNames = new ArrayList<String>();

    // Centralized lists to eliminate duplication and ensure consistency
    private static final String[] POSSIBLE_MOUNTS = {
        Environment.getExternalStorageDirectory().getAbsolutePath(),
        "/storage/sdcard0", 
        "/storage/sdcard1", 
        "/mnt/sdcard",
        "/mnt/extSdCard",
        "/storage/extSdCard"
    };
    
    private static final String[] POSSIBLE_FOLDERS = { 
        "JPEGCAM/LUTS", "jpegcam/luts", "JPEGCAM/luts", "jpegcam/LUTS" 
    };

    public RecipeManager() {
        for (int i = 0; i < 10; i++) {
            profiles[i] = new RTLProfile(i); 
        }
        scanRecipes();
    }

    public int getCurrentSlot() { return currentSlot; }
    public void setCurrentSlot(int slot) { this.currentSlot = (slot + 10) % 10; }
    public int getQualityIndex() { return qualityIndex; }
    public void setQualityIndex(int index) { this.qualityIndex = (index + 3) % 3; }
    public RTLProfile getCurrentProfile() { return profiles[currentSlot]; }
    public RTLProfile getProfile(int index) { return profiles[index]; }
    public ArrayList<String> getRecipePaths() { return recipePaths; }
    public ArrayList<String> getRecipeNames() { return recipeNames; }

    /**
     * Aggressively scans all possible mount points for LUT files inside the JPEGCAM folder.
     */
    public void scanRecipes() { 
        recipePaths.clear(); 
        recipeNames.clear(); 
        recipePaths.add("NONE"); 
        recipeNames.add("NONE"); 
        
        for (String mount : POSSIBLE_MOUNTS) {
            for (String folder : POSSIBLE_FOLDERS) {
                File lutDir = new File(mount, folder);
                if (lutDir.exists() && lutDir.isDirectory()) {
                    Log.d("JPEG.CAM", "Scanning for LUTs in: " + lutDir.getAbsolutePath());
                    File[] files = lutDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String rawName = f.getName();
                            String u = rawName.toUpperCase();
                            
                            if (rawName.startsWith(".")) continue;

                            // .CUBE files check (> 10 bytes)
                            if (f.length() > 10 && (u.endsWith(".CUB") || u.endsWith(".CUBE"))) {
                                if (!recipePaths.contains(f.getAbsolutePath())) {
                                    recipePaths.add(f.getAbsolutePath());
                                    
                                    // Default name from filename
                                    String prettyName = u.replace(".CUBE", "").replace(".CUB", "");
                                    if (prettyName.contains("~")) {
                                        prettyName = prettyName.substring(0, prettyName.indexOf("~"));
                                    }
                                    
                                    // Try to parse internal LUT title
                                    try {
                                        BufferedReader br = new BufferedReader(new FileReader(f));
                                        String line;
                                        for(int j=0; j<15; j++) {
                                            line = br.readLine();
                                            if (line != null && line.toUpperCase().startsWith("TITLE")) {
                                                prettyName = line.replace("TITLE", "").replace("title", "")
                                                                .replace("\"", "").trim().toUpperCase();
                                                break;
                                            }
                                        }
                                        br.close();
                                    } catch (Exception e) {}
                                    
                                    recipeNames.add(prettyName);
                                    Log.d("JPEG.CAM", "Found LUT: " + prettyName + " at " + f.getAbsolutePath());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the primary directory for saving preference backups.
     */
    private File getLutDir() {
        for (String mount : POSSIBLE_MOUNTS) {
            for (String folder : POSSIBLE_FOLDERS) {
                File testDir = new File(mount, folder);
                if (testDir.exists() && testDir.isDirectory()) {
                    return testDir;
                }
            }
        }
        // Failsafe: return default folder on external storage
        return new File(Environment.getExternalStorageDirectory(), "JPEGCAM/LUTS");
    }

    public void savePreferences() {
        try {
            File lutDir = getLutDir();
            if (!lutDir.exists()) lutDir.mkdirs(); 
            File backupFile = new File(lutDir, "RTLBAK.TXT");
            
            FileOutputStream fos = new FileOutputStream(backupFile);
            StringBuilder sb = new StringBuilder();
            sb.append("quality=").append(qualityIndex).append("\n");
            sb.append("slot=").append(currentSlot).append("\n");
            
            for(int i=0; i<10; i++) {
                RTLProfile p = profiles[i];
                String path = (p.lutIndex >= 0 && p.lutIndex < recipePaths.size()) ? recipePaths.get(p.lutIndex) : "NONE";
                String safeName = p.profileName != null ? p.profileName : "";
                
                sb.append(i).append(",").append(path).append(",") 
                  .append(p.opacity).append(",").append(p.grain).append(",") 
                  .append(p.grainSize).append(",").append(p.rollOff).append(",") 
                  .append(p.vignette).append(",") 
                  .append(p.whiteBalance).append(",") 
                  .append(p.wbShift).append(",") 
                  .append(p.dro).append(",") 
                  .append(p.wbShiftGM).append(",") 
                  .append(p.contrast).append(",") 
                  .append(p.saturation).append(",") 
                  .append(p.sharpness).append(",") 
                  .append(safeName).append(",") 
                  .append(p.colorMode).append(",") 
                  .append(p.sharpnessGain).append(",") 
                  .append(p.colorDepthRed).append(":").append(p.colorDepthGreen).append(":")
                  .append(p.colorDepthBlue).append(":").append(p.colorDepthCyan).append(":")
                  .append(p.colorDepthMagenta).append(":").append(p.colorDepthYellow).append(",") 
                  .append(p.advMatrix[0]).append(":").append(p.advMatrix[1]).append(":")
                  .append(p.advMatrix[2]).append(":").append(p.advMatrix[3]).append(":")
                  .append(p.advMatrix[4]).append(":").append(p.advMatrix[5]).append(":")
                  .append(p.advMatrix[6]).append(":").append(p.advMatrix[7]).append(":")
                  .append(p.advMatrix[8]).append(",") 
                  .append(p.proColorMode).append(",") 
                  .append(p.pictureEffect).append(",") 
                  .append(p.peToyCameraTone).append(",") 
                  .append(p.vignetteHardware).append(",") 
                  .append(p.softFocusLevel).append(",") 
                  .append(p.shadingRed).append(",") 
                  .append(p.shadingBlue).append(",") 
                  .append(p.colorChrome).append(",") 
                  .append(p.chromeBlue).append(",") 
                  .append(p.shadowToe).append(",") 
                  .append(p.subtractiveSat).append(",") 
                  .append(p.halation).append("\n"); 
            }
            fos.write(sb.toString().getBytes()); 
            fos.flush(); 
            fos.getFD().sync(); 
            fos.close();
            Log.d("JPEG.CAM", "Preferences saved to: " + backupFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("JPEG.CAM", "Save error: " + e.getMessage());
        }
    }

    public void loadPreferences() {
        File backupFile = new File(getLutDir(), "RTLBAK.TXT");
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
                                
                                String[] cDepths = parts[17].split(":");
                                if (cDepths.length == 6) {
                                    p.colorDepthRed = Integer.parseInt(cDepths[0]); p.colorDepthGreen = Integer.parseInt(cDepths[1]);
                                    p.colorDepthBlue = Integer.parseInt(cDepths[2]); p.colorDepthCyan = Integer.parseInt(cDepths[3]);
                                    p.colorDepthMagenta = Integer.parseInt(cDepths[4]); p.colorDepthYellow = Integer.parseInt(cDepths[5]);
                                }
                                
                                String[] mtx = parts[18].split(":");
                                if (mtx.length == 9) {
                                    for(int m=0; m<9; m++) p.advMatrix[m] = Integer.parseInt(mtx[m]);
                                }
                                
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
                Log.d("JPEG.CAM", "Preferences loaded successfully.");
            } catch (Exception e) {
                Log.e("JPEG.CAM", "Load error: " + e.getMessage());
            }
        }
    } 
}