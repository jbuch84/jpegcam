package com.github.ma1co.pmcademo.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

public class LutCooker {
    private int lutSize = 0;
    private String currentLutName = "";
    
    // PRE-SPLIT ARRAYS FOR EXTREME SPEED
    private int[] lutR;
    private int[] lutG;
    private int[] lutB;

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public String getCurrentLutName() {
        return currentLutName;
    }

    public boolean loadLut(File cubeFile, String lutName) {
        if (lutName.equals(currentLutName) && lutR != null) return true;

        try {
            BufferedReader br = new BufferedReader(new FileReader(cubeFile));
            String line;
            ArrayList<Integer> rawR = new ArrayList<Integer>();
            ArrayList<Integer> rawG = new ArrayList<Integer>();
            ArrayList<Integer> rawB = new ArrayList<Integer>();

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("LUT_3D_SIZE")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1) lutSize = Integer.parseInt(parts[1]);
                    continue;
                }
                
                if (line.matches("^[a-zA-Z_]+.*")) continue;

                String[] rgb = line.split("\\s+");
                if (rgb.length >= 3) {
                    try {
                        int r = (int) (Float.parseFloat(rgb[0]) * 255);
                        int g = (int) (Float.parseFloat(rgb[1]) * 255);
                        int b = (int) (Float.parseFloat(rgb[2]) * 255);
                        
                        rawR.add(Math.max(0, Math.min(255, r)));
                        rawG.add(Math.max(0, Math.min(255, g)));
                        rawB.add(Math.max(0, Math.min(255, b)));
                    } catch (Exception e) {}
                }
            }
            br.close();

            int expectedColors = lutSize * lutSize * lutSize;
            
            if (lutSize > 0 && rawR.size() > 0) {
                lutR = new int[expectedColors];
                lutG = new int[expectedColors];
                lutB = new int[expectedColors];
                
                for (int i = 0; i < expectedColors; i++) {
                    if (i < rawR.size()) {
                        lutR[i] = rawR.get(i);
                        lutG[i] = rawG.get(i);
                        lutB[i] = rawB.get(i);
                    } else {
                        // Pad truncated files
                        lutR[i] = rawR.get(rawR.size() - 1);
                        lutG[i] = rawG.get(rawG.size() - 1);
                        lutB[i] = rawB.get(rawB.size() - 1);
                    }
                }
                currentLutName = lutName;
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // THE TRILINEAR & BITWISE SPEED ENGINE
    public void applyLutToPixels(int[] pixels, ProgressCallback callback) {
        if (lutR == null || lutSize == 0 || pixels == null) return;

        int total = pixels.length;
        int step = total / 100;
        if (step == 0) step = 1;

        for (int i = 0; i < total; i++) {
            int pixel = pixels[i];
            
            // FAST BITWISE COLOR EXTRACTION (Bypasses Android Color class)
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            // TRILINEAR INTERPOLATION MATH
            float fX = r * (lutSize - 1) / 255.0f;
            float fY = g * (lutSize - 1) / 255.0f;
            float fZ = b * (lutSize - 1) / 255.0f;

            int x0 = (int) fX; int y0 = (int) fY; int z0 = (int) fZ;
            int x1 = Math.min(x0 + 1, lutSize - 1);
            int y1 = Math.min(y0 + 1, lutSize - 1);
            int z1 = Math.min(z0 + 1, lutSize - 1);

            float dx = fX - x0; float dy = fY - y0; float dz = fZ - z0;
            float idx = 1.0f - dx; float idy = 1.0f - dy; float idz = 1.0f - dz;

            // 8 Corner Weights
            float w000 = idx * idy * idz;
            float w100 = dx * idy * idz;
            float w010 = idx * dy * idz;
            float w110 = dx * dy * idz;
            float w001 = idx * idy * dz;
            float w101 = dx * idy * dz;
            float w011 = idx * dy * dz;
            float w111 = dx * dy * dz;

            // 8 Corner Indices in the 1D Array
            int i000 = x0 + y0 * lutSize + z0 * lutSize * lutSize;
            int i100 = x1 + y0 * lutSize + z0 * lutSize * lutSize;
            int i010 = x0 + y1 * lutSize + z0 * lutSize * lutSize;
            int i110 = x1 + y1 * lutSize + z0 * lutSize * lutSize;
            int i001 = x0 + y0 * lutSize + z1 * lutSize * lutSize;
            int i101 = x1 + y0 * lutSize + z1 * lutSize * lutSize;
            int i011 = x0 + y1 * lutSize + z1 * lutSize * lutSize;
            int i111 = x1 + y1 * lutSize + z1 * lutSize * lutSize;

            // Perfectly blend the colors
            int outR = (int) (lutR[i000]*w000 + lutR[i100]*w100 + lutR[i010]*w010 + lutR[i110]*w110 + lutR[i001]*w001 + lutR[i101]*w101 + lutR[i011]*w011 + lutR[i111]*w111);
            int outG = (int) (lutG[i000]*w000 + lutG[i100]*w100 + lutG[i010]*w010 + lutG[i110]*w110 + lutG[i001]*w001 + lutG[i101]*w101 + lutG[i011]*w011 + lutG[i111]*w111);
            int outB = (int) (lutB[i000]*w000 + lutB[i100]*w100 + lutB[i010]*w010 + lutB[i110]*w110 + lutB[i001]*w001 + lutB[i101]*w101 + lutB[i011]*w011 + lutB[i111]*w111);

            // Reconstruct the pixel
            pixels[i] = (0xFF << 24) | (outR << 16) | (outG << 8) | outB;

            if (callback != null && i % step == 0) {
                callback.onProgress((i * 100) / total);
            }
        }
    }
}