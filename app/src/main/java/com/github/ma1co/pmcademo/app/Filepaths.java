// Part 1 of 1 - Filepaths.java (Replaces existing file)
// Location: app/src/main/java/com/github/ma1co/pmcademo/app/Filepaths.java

package com.github.ma1co.pmcademo.app;

import android.os.Environment;
import java.io.File;

public class Filepaths {

    public static File getStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    public static File getAppDir() {
        File dir = new File(getStorageRoot(), "JPGCAM");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getLutDir() {
        File dir = new File(getAppDir(), "LUTS");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getRecipeDir() {
        File dir = new File(getAppDir(), "RECIPES");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getLensesDir() {
        File dir = new File(getAppDir(), "LENSES");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getGradedDir() {
        File dir = new File(getAppDir(), "GRADED");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getDcimDir() {
        return new File(getStorageRoot(), "DCIM");
    }

    /**
     * Forces the creation of the entire JPGCAM folder skeleton.
     * Should be called once during app boot.
     */
    public static void buildAppStructure() {
        getAppDir();
        getLutDir();
        getRecipeDir();
        getLensesDir();
        getGradedDir();
    }
}