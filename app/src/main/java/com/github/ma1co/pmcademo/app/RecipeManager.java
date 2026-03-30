package com.github.ma1co.pmcademo.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import android.util.Log;

public class RecipeManager {
    // --- VARIABLES ---
    private File recipeDir;
    private RTLProfile[] loadedProfiles = new RTLProfile[10]; 
    private int currentSlot = 0;
    
    // MainActivity Dependencies
    private int qualityIndex = 1; 
    private ArrayList<String> recipePaths = new ArrayList<String>(); 
    private ArrayList<String> recipeNames = new ArrayList<String>(); 

    public RecipeManager() {
        recipeDir = new File(Filepaths.getAppDir(), "RECIPES");
        if (!recipeDir.exists()) recipeDir.mkdirs();
        
        scanRecipes(); // MUST run before loading profiles so LUT validation works
        loadPreferences(); // Grabs the last used slot and quality
        loadAllWorkspaces(); // Loads R_SLOT01 through R_SLOT10
    }

    // --- MAINACTIVITY GETTERS & SETTERS ---
    public int getCurrentSlot() { return currentSlot; }
    public void setCurrentSlot(int slot) { this.currentSlot = (slot + 10) % 10; } 
    
    public int getQualityIndex() { return qualityIndex; }
    public void setQualityIndex(int index) { this.qualityIndex = (index + 3) % 3; } 
    
    public RTLProfile getCurrentProfile() { return loadedProfiles[currentSlot]; }
    public RTLProfile getProfile(int index) { return loadedProfiles[index]; }
    
    public ArrayList<String> getRecipePaths() { return recipePaths; }
    public ArrayList<String> getRecipeNames() { return recipeNames; }

    // --- SMART LUT SCANNER (Universal Version) ---
    public void scanRecipes() { 
        recipePaths.clear(); 
        recipeNames.clear(); 
        recipePaths.add("NONE"); 
        recipeNames.add("OFF"); 
        
        for (File root : Filepaths.getStorageRoots()) {
            File lutDir = new File(root, "JPEGCAM/LUTS");
            if (lutDir.exists() && lutDir.isDirectory()) {
                File[] files = lutDir.listFiles();
                if (files != null) {
                    java.util.Arrays.sort(files); 
                    for (File f : files) {
                        String u = f.getName().toUpperCase();
                        // Filter out macOS hidden files (_), dots (.), and temp files (~)
                        if (!u.startsWith(".") && !u.startsWith("_") && !u.contains("~") && 
                            (u.endsWith(".CUB") || u.endsWith(".CUBE") || u.endsWith(".PNG"))) {
                            
                            if (!recipePaths.contains(f.getAbsolutePath())) {
                                recipePaths.add(f.getAbsolutePath());
                                String name = u.replace(".CUBE", "").replace(".CUB", "").replace(".PNG", "");
                                
                                // Only try to read "TITLE" from text-based .cube files
                                if (u.endsWith(".CUBE") || u.endsWith(".CUB")) {
                                    try {
                                        BufferedReader br = new BufferedReader(new FileReader(f));
                                        String line;
                                        for(int j=0; j<10; j++) {
                                            line = br.readLine();
                                            if (line != null && line.toUpperCase().startsWith("TITLE")) {
                                                String[] pts = line.split("\"");
                                                if (pts.length > 1) name = pts[1].toUpperCase();
                                                break;
                                            }
                                        }
                                        br.close();
                                    } catch (Exception e) {}
                                }
                                recipeNames.add(name);
                            }
                        }
                    }
                }
            }
        }
    }

    // --- WORKSPACE MANAGEMENT (THE 10 SLOTS) ---
    private void loadAllWorkspaces() {
        for (int i = 0; i < 10; i++) {
            String filename = String.format("R_SLOT%02d.TXT", i + 1);
            loadedProfiles[i] = loadProfileFromFile(filename, i);
        }
    }

    private RTLProfile loadProfileFromFile(String filename, int arrayIndex) {
        File file = new File(recipeDir, filename);
        RTLProfile p = new RTLProfile(arrayIndex); 
        
        if (!file.exists()) {
            // Fresh Workspace Setup!
            p.profileName = "SLOT " + (arrayIndex + 1);
            p.advMatrix = new int[]{100, 0, 0, 0, 100, 0, 0, 0, 100}; // Safety baseline
            saveProfileToFile(file, p);
            return p;
        }

        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();

            JSONObject json = new JSONObject(new String(data, "UTF-8"));
            
            p.profileName = json.optString("profileName", "RECIPE");
            
            String loadedLutName = json.optString("lutName", "OFF");
            p.lutIndex = recipeNames.indexOf(loadedLutName);
            if (p.lutIndex == -1) p.lutIndex = 0; 

            p.opacity = json.optInt("lutOpacity", 100);
            p.shadowToe = json.optInt("shadowToe", 0);
            p.rollOff = json.optInt("rollOff", 0);
            p.colorChrome = json.optInt("colorChrome", 0);
            p.chromeBlue = json.optInt("chromeBlue", 0);
            p.subtractiveSat = json.optInt("subtractiveSat", 0);
            p.halation = json.optInt("halation", 0);
            p.vignette = json.optInt("vignette", 0);
            p.grain = json.optInt("grain", 0);
            p.grainSize = json.optInt("grainSize", 0);
            p.contrast = json.optInt("contrast", 0);
            p.saturation = json.optInt("saturation", 0);
            p.wbShift = json.optInt("wbShift", 0);
            p.wbShiftGM = json.optInt("wbShiftGM", 0);
            p.colorMode = json.optString("colorMode", "Standard");
            p.whiteBalance = json.optString("whiteBalance", "Auto");
            p.shadingRed = json.optInt("shadingRed", 0);
            p.shadingBlue = json.optInt("shadingBlue", 0);
            p.colorDepthRed = json.optInt("colorDepthRed", 0);
            p.colorDepthGreen = json.optInt("colorDepthGreen", 0);
            p.colorDepthBlue = json.optInt("colorDepthBlue", 0);
            p.colorDepthCyan = json.optInt("colorDepthCyan", 0);
            p.colorDepthMagenta = json.optInt("colorDepthMagenta", 0);
            p.colorDepthYellow = json.optInt("colorDepthYellow", 0);
            p.dro = json.optString("dro", "OFF");
            p.pictureEffect = json.optString("pictureEffect", "off");
            p.proColorMode = json.optString("proColorMode", "off");
            p.sharpness = json.optInt("sharpness", 0);
            p.sharpnessGain = json.optInt("sharpnessGain", 0);
            p.vignetteHardware = json.optInt("vignetteHardware", 0);

            JSONArray arr = json.optJSONArray("advMatrix");
            if (arr != null && arr.length() == 9) {
                boolean isAllZero = true;
                for (int i = 0; i < 9; i++) {
                    p.advMatrix[i] = arr.getInt(i);
                    if (p.advMatrix[i] != 0) isAllZero = false;
                }
                if (isAllZero) p.advMatrix = new int[]{100, 0, 0, 0, 100, 0, 0, 0, 100};
            } else {
                p.advMatrix = new int[]{100, 0, 0, 0, 100, 0, 0, 0, 100};
            }

            if (!loadedLutName.equalsIgnoreCase("OFF") && p.lutIndex == 0) {
                Log.w("JPEG.CAM", "Missing LUT data for: " + loadedLutName);
            }

        } catch (Exception e) {
            Log.e("JPEG.CAM", "Failed to parse JSON: " + filename);
            p.profileName = "ERROR";
        }
        return p;
    }

    private void saveProfileToFile(File file, RTLProfile p) {
        try {
            String lutNameToSave = "OFF";
            if (p.lutIndex >= 0 && p.lutIndex < recipeNames.size()) {
                lutNameToSave = recipeNames.get(p.lutIndex);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"profileName\": \"").append(p.profileName.replace("\"", "\\\"")).append("\",\n");
            sb.append("  \"lutName\": \"").append(lutNameToSave.replace("\"", "\\\"")).append("\",\n");
            sb.append("  \"lutOpacity\": ").append(p.opacity).append(",\n");
            sb.append("  \"shadowToe\": ").append(p.shadowToe).append(",\n");
            sb.append("  \"rollOff\": ").append(p.rollOff).append(",\n");
            sb.append("  \"colorChrome\": ").append(p.colorChrome).append(",\n");
            sb.append("  \"chromeBlue\": ").append(p.chromeBlue).append(",\n");
            sb.append("  \"subtractiveSat\": ").append(p.subtractiveSat).append(",\n");
            sb.append("  \"halation\": ").append(p.halation).append(",\n");
            sb.append("  \"vignette\": ").append(p.vignette).append(",\n");
            sb.append("  \"grain\": ").append(p.grain).append(",\n");
            sb.append("  \"grainSize\": ").append(p.grainSize).append(",\n");
            sb.append("  \"contrast\": ").append(p.contrast).append(",\n");
            sb.append("  \"saturation\": ").append(p.saturation).append(",\n");
            sb.append("  \"wbShift\": ").append(p.wbShift).append(",\n");
            sb.append("  \"wbShiftGM\": ").append(p.wbShiftGM).append(",\n");
            sb.append("  \"colorMode\": \"").append(p.colorMode).append("\",\n");
            sb.append("  \"whiteBalance\": \"").append(p.whiteBalance).append("\",\n");
            sb.append("  \"shadingRed\": ").append(p.shadingRed).append(",\n");
            sb.append("  \"shadingBlue\": ").append(p.shadingBlue).append(",\n");
            sb.append("  \"colorDepthRed\": ").append(p.colorDepthRed).append(",\n");
            sb.append("  \"colorDepthGreen\": ").append(p.colorDepthGreen).append(",\n");
            sb.append("  \"colorDepthBlue\": ").append(p.colorDepthBlue).append(",\n");
            sb.append("  \"colorDepthCyan\": ").append(p.colorDepthCyan).append(",\n");
            sb.append("  \"colorDepthMagenta\": ").append(p.colorDepthMagenta).append(",\n");
            sb.append("  \"colorDepthYellow\": ").append(p.colorDepthYellow).append(",\n");
            
            sb.append("  \"advMatrix\": [\n    ");
            for (int i = 0; i < 9; i++) {
                sb.append(p.advMatrix[i]);
                if (i < 8) sb.append(",\n    ");
                else sb.append("\n  ],\n");
            }
            
            sb.append("  \"dro\": \"").append(p.dro).append("\",\n");
            sb.append("  \"pictureEffect\": \"").append(p.pictureEffect).append("\",\n");
            sb.append("  \"proColorMode\": \"").append(p.proColorMode).append("\",\n");
            sb.append("  \"sharpness\": ").append(p.sharpness).append(",\n");
            sb.append("  \"sharpnessGain\": ").append(p.sharpnessGain).append(",\n");
            sb.append("  \"vignetteHardware\": ").append(p.vignetteHardware).append("\n");
            sb.append("}");

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e("JPEG.CAM", "Failed to save profile.");
        }
    }

    // --- GLOBAL PREFERENCES ---
    public void loadPreferences() {
        File prefsFile = new File(recipeDir, "GLOBAL_PREFS.TXT");
        if (prefsFile.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(prefsFile));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("quality=")) qualityIndex = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("slot=")) currentSlot = Integer.parseInt(line.split("=")[1]);
                }
                br.close();
            } catch (Exception e) {}
        }
    }

    public void savePreferences() {
        try {
            File prefsFile = new File(recipeDir, "GLOBAL_PREFS.TXT");
            FileOutputStream fos = new FileOutputStream(prefsFile);
            fos.write(("quality=" + qualityIndex + "\nslot=" + currentSlot + "\n").getBytes());
            fos.close();
        } catch (Exception e) {
            Log.e("JPEG.CAM", "Failed to save global prefs.");
        }

        // Always auto-save the current workspace so the user never loses tweaks
        String currentFilename = String.format("R_SLOT%02d.TXT", currentSlot + 1);
        File file = new File(recipeDir, currentFilename);
        saveProfileToFile(file, loadedProfiles[currentSlot]);
    }

    // ==========================================================
    // --- THE VAULT ENGINE (NEW) ---
    // ==========================================================

    public List<String> getVaultFiles() {
        List<String> availableFiles = new ArrayList<String>();
        if (recipeDir.exists() && recipeDir.isDirectory()) {
            File[] files = recipeDir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files);
                for (File f : files) {
                    String name = f.getName().toUpperCase();
                    // Ignore our volatile workspaces and system files
                    if (name.endsWith(".TXT") && !name.startsWith("R_SLOT") && !name.equals("GLOBAL_PREFS.TXT") && !name.equals("ACTIVE_SLOTS.TXT")) {
                        availableFiles.add(f.getName());
                    }
                }
            }
        }
        if (availableFiles.isEmpty()) availableFiles.add("NO VAULT RECIPES");
        return availableFiles;
    }

    // Action 1: The User hits "Load" on a Vault File. We pour it into the current Workspace.
    public void copyVaultToSlot(String vaultFilename) {
        if (vaultFilename.equals("NO VAULT RECIPES")) return;
        
        // 1. Read the Vault file into our memory array
        loadedProfiles[currentSlot] = loadProfileFromFile(vaultFilename, currentSlot);
        
        // 2. Immediately overwrite the physical R_SLOTxx.TXT file so it persists
        String currentFilename = String.format("R_SLOT%02d.TXT", currentSlot + 1);
        File workspaceFile = new File(recipeDir, currentFilename);
        saveProfileToFile(workspaceFile, loadedProfiles[currentSlot]);
    }

    // Action 2: The User hits "Save" and names their Sandbox. We drop a new file into the Vault.
    public void saveSlotToVault(String customName) {
        String safeName = customName.trim().replaceAll("[^A-Za-z0-9_\\- ]", "").toUpperCase();
        if (safeName.isEmpty()) safeName = "CUSTOM";
        
        String filename = "R_" + safeName.replace(" ", "_") + ".TXT";
        
        File newFile = new File(recipeDir, filename);
        int counter = 1;
        while (newFile.exists()) {
            filename = "R_" + safeName.replace(" ", "_") + "_" + counter + ".TXT";
            newFile = new File(recipeDir, filename);
            counter++;
        }
        
        // Update the internal profile name so the UI reflects the new name instantly
        loadedProfiles[currentSlot].profileName = safeName;
        
        // Save the current math to the new Vault file
        saveProfileToFile(newFile, loadedProfiles[currentSlot]);
        
        // Save the updated name to the current Workspace file too
        savePreferences();
    }
}