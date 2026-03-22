// Part 1 of 1 - Filepaths.java (Replaces existing file)
// Location: app/src/main/java/com/github/ma1co/pmcademo/app/Filepaths.java

package com.github.ma1co.pmcademo.app;

import android.os.Environment;
import java.io.File;

public class Filepaths {

    /**
     * Dynamically resolves the root storage directory for the active media.
     * Replaces brittle hardcoded strings like "/storage/sdcard0".
     */
    public static File getStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    /**
     * Base directory for the JPGCAM app.
     */
    public static File getAppDir() {
        File dir = new File(getStorageRoot(), "JPGCAM");
        if (!dir.exists()) {
            dir.mkdirs(); // Safe API 10 call to ensure the directory exists
        }
        return dir;
    }

    /**
     * Directory for 3D LUT files (.cube).
     */
    public static File getLutDir() {
        File dir = new File(getAppDir(), "LUTS");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Directory for saved film recipes/settings.
     */
    public static File getRecipeDir() {
        File dir = new File(getAppDir(), "RECIPES");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Standard Sony camera image directory (DCIM).
     * Useful for the photo scanner to locate the original captures.
     */
    public static File getDcimDir() {
        return new File(getStorageRoot(), "DCIM");
    }

    /**
     * Directory for the processed/graded output images.
     */
    public static File getGradedDir() {
        File dir = new File(getAppDir(), "GRADED");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}