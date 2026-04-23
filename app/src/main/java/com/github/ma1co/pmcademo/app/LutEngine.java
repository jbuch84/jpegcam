package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";

    private native boolean loadLutNative(String filePath);
    
    // NEW: Native hook for loading the 512x512 texture into C++
    private native boolean loadGrainTextureNative(String filePath);

    // Signature matches C++ exactly: 16 total parameters after env/obj
    private native boolean processImageNative(
        String inPath, String outPath, int scaleDenom, int opacity,
        int grain, int grainSize, int vignette, int rollOff,
        int colorChrome, int chromeBlue, int shadowToe,
        int subtractiveSat, int halation, int bloom, int jpegQuality,
        boolean applyCrop, int numCores
    );

    /**
     * Loads either a .cube/.cub or .png HaldCLUT from the SD card.
     */
    public boolean loadLut(File lutFile, String lutName) {
        if (lutName.equals(currentLutName)) return true;
        if (loadLutNative(lutFile.getAbsolutePath())) {
            currentLutName = lutName;
            return true;
        }
        return false;
    }

    public boolean applyLutToJpeg(String in, String out, int scale, int opacity,
                                  int grain, int grainSize, int vignette, int rollOff,
                                  int colorChrome, int chromeBlue, int shadowToe,
                                  int subtractiveSat, int halation, int bloom,
                                  int quality,
                                  boolean applyCrop, int numCores) { 
        return processImageNative(in, out, scale, opacity, grain, grainSize, vignette,
                                 rollOff, colorChrome, chromeBlue, shadowToe,
                                 subtractiveSat, halation, bloom, quality,
                                 applyCrop, numCores); 
    }

    // NEW: Public wrapper to load the grain texture safely
    public boolean loadGrainTexture(File texFile) {
        if (texFile == null || !texFile.exists()) return false;
        return loadGrainTextureNative(texFile.getAbsolutePath());
    }
}