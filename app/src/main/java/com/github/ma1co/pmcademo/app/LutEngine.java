package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class LutEngine {
    private int lutSize = 0;
    private String currentLutName = "";
    
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
            int idx = 0;
            int expectedColors = 0;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("LUT_3D_SIZE")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1) {
                        lutSize = Integer.parseInt(parts[1]);
                        expectedColors = lutSize * lutSize * lutSize;
                        // EXTREME SPEED: Pre-allocate memory instantly
                        lutR = new int[expectedColors];
                        lutG = new int[expectedColors];
                        lutB = new int[expectedColors];
                    }
                    continue;
                }
                
                // Fast check to skip text headers
                char firstChar = line.charAt(0);
                if (firstChar < '0' || firstChar > '9') continue;

                if (idx < expectedColors) {
                    String[] rgb = line.split("\\s+");
                    if (rgb.length >= 3) {
                        try {
                            float r = Float.parseFloat(rgb[0]) * 255;
                            float g = Float.parseFloat(rgb[1]) * 255;
                            float b = Float.parseFloat(rgb[2]) * 255;
                            
                            lutR[idx] = (int) Math.max(0, Math.min(255, r));
                            lutG[idx] = (int) Math.max(0, Math.min(255, g));
                            lutB[idx] = (int) Math.max(0, Math.min(255, b));
                            idx++;
                        } catch (Exception e) {}
                    }
                }
            }
            br.close();

            // Fault-tolerant padding if file was truncated
            while (idx > 0 && idx < expectedColors) {
                lutR[idx] = lutR[idx - 1];
                lutG[idx] = lutG[idx - 1];
                lutB[idx] = lutB[idx - 1];
                idx++;
            }

            currentLutName = lutName;
            return true;
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public void applyLutToBitmap(Bitmap bitmap, ProgressCallback callback) {
        if (lutR == null || lutSize == 0 || bitmap == null) return;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] row = new int[width]; 
        
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            
            for (int x = 0; x < width; x++) {
                int pixel = row[x];
                
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                float fX = r * (lutSize - 1) / 255.0f;
                float fY = g * (lutSize - 1) / 255.0f;
                float fZ = b * (lutSize - 1) / 255.0f;

                int x0 = (int) fX; int y0 = (int) fY; int z0 = (int) fZ;
                int x1 = Math.min(x0 + 1, lutSize - 1);
                int y1 = Math.min(y0 + 1, lutSize - 1);
                int z1 = Math.min(z0 + 1, lutSize - 1);

                float dx = fX - x0; float dy = fY - y0; float dz = fZ - z0;
                float idx_x = 1.0f - dx; float idy = 1.0f - dy; float idz = 1.0f - dz;

                float w000 = idx_x * idy * idz;
                float w100 = dx * idy * idz;
                float w010 = idx_x * dy * idz;
                float w110 = dx * dy * idz;
                float w001 = idx_x * idy * dz;
                float w101 = dx * idy * dz;
                float w011 = idx_x * dy * dz;
                float w111 = dx * dy * dz;

                int i000 = x0 + y0 * lutSize + z0 * lutSize * lutSize;
                int i100 = x1 + y0 * lutSize + z0 * lutSize * lutSize;
                int i010 = x0 + y1 * lutSize + z0 * lutSize * lutSize;
                int i110 = x1 + y1 * lutSize + z0 * lutSize * lutSize;
                int i001 = x0 + y0 * lutSize + z1 * lutSize * lutSize;
                int i101 = x1 + y0 * lutSize + z1 * lutSize * lutSize;
                int i011 = x0 + y1 * lutSize + z1 * lutSize * lutSize;
                int i111 = x1 + y1 * lutSize + z1 * lutSize * lutSize;

                int outR = (int) (lutR[i000]*w000 + lutR[i100]*w100 + lutR[i010]*w010 + lutR[i110]*w110 + lutR[i001]*w001 + lutR[i101]*w101 + lutR[i011]*w011 + lutR[i111]*w111);
                int outG = (int) (lutG[i000]*w000 + lutG[i100]*w100 + lutG[i010]*w010 + lutG[i110]*w110 + lutG[i001]*w001 + lutG[i101]*w101 + lutG[i011]*w011 + lutG[i111]*w111);
                int outB = (int) (lutB[i000]*w000 + lutB[i100]*w100 + lutB[i010]*w010 + lutB[i110]*w110 + lutB[i001]*w001 + lutB[i101]*w101 + lutB[i011]*w011 + lutB[i111]*w111);

                row[x] = (0xFF << 24) | (outR << 16) | (outG << 8) | outB;
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
    }
}