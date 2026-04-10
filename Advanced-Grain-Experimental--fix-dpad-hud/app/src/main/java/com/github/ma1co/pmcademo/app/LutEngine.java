package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";
    
    private native boolean loadLutNative(String filePath);
    private native void clearLutNative();
    
    // Signature matches C++ exactly: 14 total parameters after env/obj
    private native boolean processImageNative(
        String inPath, String outPath, int scaleDenom, int opacity, 
        int grain, int grainSize, int vignette, int rollOff, 
        int colorChrome, int chromeBlue, int shadowToe, 
        int subtractiveSat, int halation, int advancedGrainExperimental, int jpegQuality
    );

    /**
     * Loads either a .cube or .png HaldCLUT from the SD card.
     */
    public boolean loadLut(File lutFile, String lutName) {
        if (lutFile == null || lutName == null || "OFF".equalsIgnoreCase(lutName) ||
                "NONE".equalsIgnoreCase(lutName) || !lutFile.exists()) {
            clearLutNative();
            currentLutName = "";
            return true;
        }
        if (lutName.equals(currentLutName)) return true;
        
        // C++ Route A/B logic triggers based on the .png or .cube extension here
        if (loadLutNative(lutFile.getAbsolutePath())) {
            currentLutName = lutName; 
            return true;
        }
        return false;
    }

    public boolean applyLutToJpeg(String in, String out, int scale, int opacity, 
                                  int grain, int grainSize, int vignette, int rollOff, 
                                  int colorChrome, int chromeBlue, int shadowToe, 
                                  int subtractiveSat, int halation, int advancedGrainExperimental, int quality) {
        return processImageNative(in, out, scale, opacity, grain, grainSize, vignette, 
                                 rollOff, colorChrome, chromeBlue, shadowToe, 
                                 subtractiveSat, halation, advancedGrainExperimental, quality);
    }
}
