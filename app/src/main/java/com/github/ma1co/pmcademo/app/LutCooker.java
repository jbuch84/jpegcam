package com.github.ma1co.pmcademo.app;

import android.graphics.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

public class LutCooker {
    private int lutSize = 0;
    private int[] lutPixels;
    private String currentLutName = "";

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public String getCurrentLutName() {
        return currentLutName;
    }

    public boolean loadLut(File cubeFile, String lutName) {
        // THE SPEED FIX: If we already loaded this exact LUT, skip the text parsing!
        if (lutName.equals(currentLutName) && lutPixels != null) {
            return true;
        }

        try {
            BufferedReader br = new BufferedReader(new FileReader(cubeFile));
            String line;
            ArrayList<Integer> colors = new ArrayList<Integer>();

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
                        
                        r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));
                        
                        colors.add(Color.rgb(r, g, b));
                    } catch (Exception e) {}
                }
            }
            br.close();

            int expectedColors = lutSize * lutSize * lutSize;
            
            if (lutSize > 0 && colors.size() > 0) {
                lutPixels = new int[expectedColors];
                for (int i = 0; i < expectedColors; i++) {
                    if (i < colors.size()) {
                        lutPixels[i] = colors.get(i);
                    } else {
                        lutPixels[i] = colors.get(colors.size() - 1); // Pad if short
                    }
                }
                currentLutName = lutName; // Save the name so we don't load it twice
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public void applyLutToPixels(int[] pixels, ProgressCallback callback) {
        if (lutPixels == null || lutSize == 0 || pixels == null) return;

        int total = pixels.length;
        int step = total / 100;
        if (step == 0) step = 1;

        for (int i = 0; i < total; i++) {
            int pixel = pixels[i];
            int r = Color.red(pixel);
            int g = Color.green(pixel);
            int b = Color.blue(pixel);

            int lutX = (r * (lutSize - 1)) / 255;
            int lutY = (g * (lutSize - 1)) / 255;
            int lutZ = (b * (lutSize - 1)) / 255;

            int lutIndex = lutX + (lutY * lutSize) + (lutZ * lutSize * lutSize);

            if (lutIndex >= 0 && lutIndex < lutPixels.length) {
                pixels[i] = lutPixels[lutIndex];
            }

            if (callback != null && i % step == 0) {
                callback.onProgress((i * 100) / total);
            }
        }
    }
}