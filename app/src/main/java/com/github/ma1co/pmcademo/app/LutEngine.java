package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";
    
    private native boolean loadLutNative(String filePath);
    // Verified: 8 parameters to match the native-lib.cpp
    private native boolean processImageNative(String inPath, String outPath, int scaleDenom, int opacity, int grain, int grainSize, int vignette, int rollOff);

    public String getCurrentLutName() { return currentLutName; }

    public boolean loadLut(File cubeFile, String lutName) {
        if (lutName.equals(currentLutName)) return true;
        if (loadLutNative(cubeFile.getAbsolutePath())) {
            currentLutName = lutName; return true;
        }
        return false;
    }

    public boolean applyLutToJpeg(String inPath, String outPath, int scaleDenom, int opacity, int grain, int grainSize, int vignette, int rollOff) {
        return processImageNative(inPath, outPath, scaleDenom, opacity, grain, grainSize, vignette, rollOff);
    }
}