package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

public class LutCooker {
    private int lutSize = 0;
    private int[] lutPixels;

    public boolean loadLut(File cubeFile) {
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
            if (lutSize > 0 && colors.size() >= expectedColors) {
                lutPixels = new int[expectedColors];
                for (int i = 0; i < expectedColors; i++) {
                    lutPixels[i] = colors.get(i);
                }
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // MEMORY SAFE: Overwrites the image row-by-row
    public void applyLutInPlace(Bitmap bitmap) {
        if (lutPixels == null || lutSize == 0 || bitmap == null) return;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        // Only allocate memory for ONE horizontal row of pixels (~8 Kilobytes)
        int[] rowPixels = new int[width];

        for (int y = 0; y < height; y++) {
            // Read one row
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1);
            
            for (int x = 0; x < width; x++) {
                int pixel = rowPixels[x];
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                int lutX = (r * (lutSize - 1)) / 255;
                int lutY = (g * (lutSize - 1)) / 255;
                int lutZ = (b * (lutSize - 1)) / 255;

                int lutIndex = lutX + (lutY * lutSize) + (lutZ * lutSize * lutSize);

                if (lutIndex >= 0 && lutIndex < lutPixels.length) {
                    rowPixels[x] = lutPixels[lutIndex];
                }
            }
            // Write the row back into the original image
            bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1);
        }
    }
}