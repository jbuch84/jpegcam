package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";

    private native boolean loadLutNative(String filePath);

    // Signature matches C++ exactly: 15 total parameters after env/obj
    private native boolean processImageNative(
        String inPath, String outPath, int scaleDenom, int opacity,
        int grain, int grainSize, int vignette, int rollOff,
        int colorChrome, int chromeBlue, int shadowToe,
        int subtractiveSat, int halation, int diffusion, int jpegQuality
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
                                  int subtractiveSat, int halation, int diffusion, int quality) {
        return processImageNative(in, out, scale, opacity, grain, grainSize, vignette,
                                 rollOff, colorChrome, chromeBlue, shadowToe,
                                 subtractiveSat, halation, diffusion, quality);
    }
}
