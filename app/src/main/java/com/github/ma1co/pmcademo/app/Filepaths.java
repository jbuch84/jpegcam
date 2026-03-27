package com.github.ma1co.pmcademo.app;

import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Filepaths {

    public static File getStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    public static List<File> getStorageRoots() {
        ArrayList<File> roots = new ArrayList<File>();
        roots.add(getStorageRoot());             // Primary (a5100)
        roots.add(new File("/storage/sdcard1")); // Physical SD (A7II)
        roots.add(new File("/mnt/sdcard"));
        roots.add(new File("/storage/extSdCard"));
        return roots;
    }

    public static File getAppDir() {
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM");
            if (dir.exists()) return dir;
        }
        File def = new File(getStorageRoot(), "JPEGCAM");
        if (!def.exists()) def.mkdirs();
        return def;
    }

    public static File getLutDir() {
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM/LUTS");
            if (dir.exists()) return dir;
        }
        File def = new File(getAppDir(), "LUTS");
        if (!def.exists()) def.mkdirs();
        return def;
    }

    public static File getDcimDir() {
        // Check all possible roots to find where the camera is actively saving photos
        for (File root : getStorageRoots()) {
            File dcim = new File(root, "DCIM");
            if (dcim.exists() && dcim.isDirectory()) {
                File[] subdirs = dcim.listFiles();
                if (subdirs != null) {
                    for (File s : subdirs) {
                        String uName = s.getName().toUpperCase();
                        // Find the exact DCIM containing standard Sony photo folders
                        if (s.isDirectory() && (uName.endsWith("MSDCF") || uName.contains("ALPHA") || uName.contains("SONY"))) {
                            return dcim; 
                        }
                    }
                }
            }
        }
        // Failsafe fallback
        return new File(getStorageRoot(), "DCIM"); 
    }

    public static File getRecipeDir() { File d = new File(getAppDir(), "RECIPES"); if (!d.exists()) d.mkdirs(); return d; }
    public static File getLensesDir() { File d = new File(getAppDir(), "LENSES"); if (!d.exists()) d.mkdirs(); return d; }
    public static File getGradedDir() { File d = new File(getAppDir(), "GRADED"); if (!d.exists()) d.mkdirs(); return d; }

    public static void buildAppStructure() {
        getAppDir(); getLutDir(); getRecipeDir(); getLensesDir(); getGradedDir();
    }
}