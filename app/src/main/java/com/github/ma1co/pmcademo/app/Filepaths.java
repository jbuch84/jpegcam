package com.github.ma1co.pmcademo.app;

import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Filepaths {

    /**
     * Returns a list of all possible storage locations.
     * Necessary because A7II mounts physical SD cards at /storage/sdcard1.
     */
    public static List<File> getStorageRoots() {
        ArrayList<File> roots = new ArrayList<File>();
        roots.add(Environment.getExternalStorageDirectory()); // Usually /storage/sdcard0
        roots.add(new File("/storage/sdcard1"));              // Physical SD on A7 series
        roots.add(new File("/mnt/sdcard"));
        roots.add(new File("/storage/extSdCard"));
        return roots;
    }

    public static File getAppDir() {
        // Look for the existing JPEGCAM folder on any mount point
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM");
            if (dir.exists()) return dir;
        }
        // Failsafe: Create it on the default external storage
        File defaultDir = new File(Environment.getExternalStorageDirectory(), "JPEGCAM");
        if (!defaultDir.exists()) defaultDir.mkdirs();
        return defaultDir;
    }

    public static File getLutDir() {
        // Look for the existing JPEGCAM/LUTS folder on any mount point
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM/LUTS");
            if (dir.exists()) return dir;
        }
        // Failsafe: Create it in the app directory
        File defaultDir = new File(getAppDir(), "LUTS");
        if (!defaultDir.exists()) defaultDir.mkdirs();
        return defaultDir;
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
        // Prioritize physical SD card for the DCIM folder on A7II
        File sd1 = new File("/storage/sdcard1/DCIM");
        if (sd1.exists()) return sd1;
        return new File(Environment.getExternalStorageDirectory(), "DCIM");
    }

    public static void buildAppStructure() {
        getAppDir();
        getLutDir();
        getRecipeDir();
        getLensesDir();
        getGradedDir();
    }
}