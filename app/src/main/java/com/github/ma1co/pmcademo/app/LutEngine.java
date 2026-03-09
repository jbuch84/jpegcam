package com.github.ma1co.pmcademo.app;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";
    
    private native boolean loadLutNative(String filePath);
    private native boolean processImageNative(String inPath, String outPath, int scaleDenom, int opacity, int grain, int grainSize, int vignette, int rollOff);

    public boolean loadLut(java.io.File path, String name) {
        if (name.equals(currentLutName)) return true;
        if (loadLutNative(path.getAbsolutePath())) {
            currentLutName = name; return true;
        }
        return false;
    }

    public boolean applyLutToJpeg(String inPath, String outPath, int scaleDenom, int opacity, int grain, int grainSize, int vignette, int rollOff) {
        return processImageNative(inPath, outPath, scaleDenom, opacity, grain, grainSize, vignette, rollOff);
    }
}