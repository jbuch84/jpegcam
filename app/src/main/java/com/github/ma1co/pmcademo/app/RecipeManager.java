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

    private int qualityIndex = 1;
    private int prefC1 = 0;
    private int prefC2 = 0;
    private int prefC3 = 0;
    private int prefAel = 0;
    private int prefFn = 0;
    private ArrayList<String> recipePaths = new ArrayList<String>();
    private ArrayList<String> recipeNames = new ArrayList<String>();

    public RecipeManager() {
        recipeDir = new File(Filepaths.getAppDir(), "RECIPES");
        if (!recipeDir.exists()) recipeDir.mkdirs();

        scanRecipes();
        loadPreferences();
        loadAllWorkspaces();
    }

    // --- MAINACTIVITY GETTERS & SETTERS ---
    public int getCurrentSlot() { return currentSlot; }

    public void setCurrentSlot(int slot) {
        this.currentSlot = (slot + 10) % 10;
        savePreferences();
    }

    public int getQualityIndex() { return qualityIndex; }
    public void setQualityIndex(int index) {
        this.qualityIndex = (index + 3) % 3;
        savePreferences();
    }

    public int getPrefC1() { return prefC1; }
    public void setPrefC1(int v) { prefC1 = v; savePreferences(); }
    public int getPrefC2() { return prefC2; }
    public void setPrefC2(int v) { prefC2 = v; savePreferences(); }
    public int getPrefC3() { return prefC3; }
    public void setPrefC3(int v) { prefC3 = v; savePreferences(); }
    public int getPrefAel() { return prefAel; }
    public void setPrefAel(int v) { prefAel = v; savePreferences(); }
    public int getPrefFn() { return prefFn; }
    public void setPrefFn(int v) { prefFn = v; savePreferences(); }

    public RTLProfile getCurrentProfile() { return loadedProfiles[currentSlot]; }
    public RTLProfile getProfile(int index) { return loadedProfiles[index]; }

    public ArrayList<String> getRecipePaths() { return recipePaths; }
    public ArrayList<String> getRecipeNames() { return recipeNames; }

    // --- SMART LUT SCANNER (Pretty Names + Long Filename Support) ---
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
                        String name = f.getName();
                        String u = name.toUpperCase();

                        if (!u.startsWith(".") && !u.startsWith("_") &&
                            (u.endsWith(".CUB") || u.endsWith(".CUBE") || u.endsWith(".PNG"))) {

                            if (!recipePaths.contains(f.getAbsolutePath())) {
                                recipePaths.add(f.getAbsolutePath());

                                String prettyName = name.replaceAll("(?i)\\.(cube|cub|png)$", "");

                                if (u.endsWith(".CUBE") || u.endsWith(".CUB")) {
                                    try {
                                        BufferedReader br = new BufferedReader(new FileReader(f));
                                        String line;
                                        for (int j = 0; j < 10; j++) {
                                            line = br.readLine();
                                            if (line != null && line.toUpperCase().startsWith("TITLE")) {
                                                String[] pts = line.split("\"");
                                                if (pts.length > 1) prettyName = pts[1];
                                                break;
                                            }
                                        }
                                        br.close();
                                    } catch (Exception e) {}
                                }
                                recipeNames.add(prettyName);
                            }
                        }
                    }
                }
            }
        }
    }

    // --- WORKSPACE MANAGEMENT ---
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
            p.profileName = "SLOT " + (arrayIndex + 1);
            p.advMatrix = new int[]{100, 0, 0, 0, 100, 0, 0, 0, 100};
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
            p.opacity         = json.optInt("lutOpacity", 100);
            p.shadowToe       = json.optInt("shadowToe", 0);
            p.rollOff         = json.optInt("rollOff", 0);
            p.colorChrome     = json.optInt("colorChrome", 0);
            p.chromeBlue      = json.optInt("chromeBlue", 0);
            p.subtractiveSat  = json.optInt("subtractiveSat", 0);
            p.halation        = json.optInt("halation", 0);
            p.vignette        = json.optInt("vignette", 0);
            p.grain           = json.optInt("grain", 0);
            
            String loadedGrainName = json.optString("grainName", "NONE");
            List<String> grainOptions = java.util.Arrays.asList(MenuController.getGrainEngineOptions());

            if (!loadedGrainName.equals("NONE")) {
                p.grainSize = grainOptions.indexOf(loadedGrainName.toUpperCase());
                if (p.grainSize == -1) p.grainSize = 0;
            } else {
                // Legacy Recipe Fallback
                int legacySize = json.optInt("grainSize", 0);
                String legacyName = (legacySize == 0) ? "SMALL" : (legacySize == 1) ? "MED" : "LARGE";
                p.grainSize = grainOptions.indexOf(legacyName);
                if (p.grainSize == -1) p.grainSize = 0; // If not found, use first available
            }

            p.bloom           = json.optInt("bloom", 0);
            p.contrast        = json.optInt("contrast", 0);
            p.saturation      = json.optInt("saturation", 0);
            p.wbShift         = json.optInt("wbShift", 0);
            p.wbShiftGM       = json.optInt("wbShiftGM", 0);
            p.colorMode       = json.optString("colorMode", "Standard");
            p.whiteBalance    = json.optString("whiteBalance", "Auto");
            p.shadingRed      = json.optInt("shadingRed", 0);
            p.shadingBlue     = json.optInt("shadingBlue", 0);
            p.colorDepthRed   = json.optInt("colorDepthRed", 0);
            p.colorDepthGreen = json.optInt("colorDepthGreen", 0);
            p.colorDepthBlue  = json.optInt("colorDepthBlue", 0);
            p.colorDepthCyan  = json.optInt("colorDepthCyan", 0);
            p.colorDepthMagenta = json.optInt("colorDepthMagenta", 0);
            p.colorDepthYellow  = json.optInt("colorDepthYellow", 0);
            p.dro             = json.optString("dro", "OFF");
            p.pictureEffect   = json.optString("pictureEffect", "off");
            p.proColorMode    = json.optString("proColorMode", "off");
            p.sharpness       = json.optInt("sharpness", 0);
            p.sharpnessGain   = json.optInt("sharpnessGain", 0);
            p.vignetteHardware = json.optInt("vignetteHardware", 0);
            JSONArray arr = json.optJSONArray("advMatrix");
            if (arr != null && arr.length() == 9) {
                for (int i = 0; i < 9; i++) p.advMatrix[i] = arr.getInt(i);
            }
        } catch (Exception e) {
            p.profileName = "ERROR";
        }
        return p;
    }

    private void saveProfileToFile(File file, RTLProfile p) {
        try {
            String lutNameToSave = (p.lutIndex >= 0 && p.lutIndex < recipeNames.size()) ? recipeNames.get(p.lutIndex) : "OFF";
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

            String grainNameToSave = "NONE";
            if (p.grain > 0) {
                List<String> grainOptions = java.util.Arrays.asList(MenuController.getGrainEngineOptions());
                if (p.grainSize >= 0 && p.grainSize < grainOptions.size()) {
                    grainNameToSave = grainOptions.get(p.grainSize);
                }
            }
            sb.append("  \"grainName\": \"").append(grainNameToSave.replace("\"", "\\\"")).append("\",\n");

            sb.append("  \"bloom\": ").append(p.bloom).append(",\n");
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
            sb.append("  \"advMatrix\": [");
            for (int i = 0; i < 9; i++) sb.append(p.advMatrix[i]).append(i < 8 ? "," : "");
            sb.append("],\n");
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
        } catch (Exception e) {}
    }

    public void loadPreferences() {
        File prefsFile = new File(recipeDir, "PREFS.TXT");
        if (prefsFile.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(prefsFile));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("quality=")) qualityIndex = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("slot=")) currentSlot = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("c1=")) prefC1 = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("c2=")) prefC2 = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("c3=")) prefC3 = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("ael=")) prefAel = Integer.parseInt(line.split("=")[1]);
                    else if (line.startsWith("fn=")) prefFn = Integer.parseInt(line.split("=")[1]);
                }
                br.close();
            } catch (Exception e) {}
        }
    }

    public void savePreferences() {
        try {
            File prefsFile = new File(recipeDir, "PREFS.TXT");
            FileOutputStream fos = new FileOutputStream(prefsFile);
            String prefsData = "quality=" + qualityIndex + "\nslot=" + currentSlot + "\n" +
                               "c1=" + prefC1 + "\nc2=" + prefC2 + "\nc3=" + prefC3 + "\n" +
                               "ael=" + prefAel + "\nfn=" + prefFn + "\n";
            fos.write(prefsData.getBytes());
            fos.close();

            if (loadedProfiles[currentSlot] != null) {
                String filename = String.format("R_SLOT%02d.TXT", currentSlot + 1);
                File file = new File(recipeDir, filename);
                saveProfileToFile(file, loadedProfiles[currentSlot]);
            }
        } catch (Exception e) {}
    }

    // --- VAULT DATA STRUCTURE ---
    public static class VaultItem {
        public String filename;
        public String profileName;
        public VaultItem(String fn, String pn) { filename = fn; profileName = pn; }
    }
    private List<VaultItem> vaultItems = new ArrayList<VaultItem>();

    public void scanVault() {
        vaultItems.clear();
        File[] all = recipeDir.listFiles();
        if (all != null) {
            for (File f : all) {
                String n = f.getName().toUpperCase();
                if (!n.endsWith(".TXT") || n.startsWith("R_SLOT") || n.equals("PREFS.TXT")) continue;

                String pName = n.replace(".TXT", "");
                try {
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.contains("\"profileName\"")) {
                            String[] parts = line.split("\"");
                            if (parts.length >= 4) pName = parts[3];
                            break;
                        }
                    }
                    br.close();
                } catch (Exception e) {}
                vaultItems.add(new VaultItem(f.getName(), pName));
            }
        }
        if (vaultItems.isEmpty()) vaultItems.add(new VaultItem("NONE", "NO VAULT RECIPES"));
    }

    public List<VaultItem> getVaultItems() {
        if (vaultItems.isEmpty()) scanVault();
        return vaultItems;
    }

    public void deleteVaultItem(int index) {
        if (index >= 0 && index < vaultItems.size()) {
            VaultItem item = vaultItems.get(index);
            if (!item.filename.equals("NONE")) {
                File file = new File(recipeDir, item.filename);
                if (file.exists()) file.delete();
                scanVault();
            }
        }
    }

    public void previewVaultToSlot(String vaultFilename) {
        if (vaultFilename.equals("NONE") || vaultFilename.equals("NO VAULT RECIPES")) return;
        loadedProfiles[currentSlot] = loadProfileFromFile(vaultFilename, currentSlot);
    }

    public void resetCurrentSlot() {
        RTLProfile blank = new RTLProfile(currentSlot);
        blank.profileName = "SLOT " + (currentSlot + 1);
        loadedProfiles[currentSlot] = blank;
        savePreferences();
    }

    public void saveSlotToVault(String newPrettyName) {
        String targetFile = null;
        for (VaultItem item : getVaultItems()) {
            if (item.profileName.equalsIgnoreCase(newPrettyName)) {
                targetFile = item.filename;
                break;
            }
        }
        if (targetFile == null) {
            String base = newPrettyName.replaceAll("[^A-Z0-9]", "").toUpperCase();
            if (base.length() > 6) base = base.substring(0, 6);
            if (base.isEmpty()) base = "RECIPE";
            int count = 1;
            do {
                targetFile = base + String.format("%02d", count++) + ".TXT";
            } while (new File(recipeDir, targetFile).exists() && count < 100);
        }

        RTLProfile p = loadedProfiles[currentSlot];
        p.profileName = newPrettyName;
        saveProfileToFile(new File(recipeDir, targetFile), p);
        scanVault();
    }
}
