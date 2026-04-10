package com.github.ma1co.pmcademo.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class MatrixManager {
    private File matrixDir;
    private List<String> matrixNames = new ArrayList<String>();
    private List<int[]> matrixValues = new ArrayList<int[]>();
    private List<String> matrixNotes = new ArrayList<String>();

    public MatrixManager() {
        matrixDir = new File(Filepaths.getAppDir(), "MATRIX");
        if (!matrixDir.exists()) matrixDir.mkdirs();
    }

    public void scanMatrices() {
        matrixNames.clear();
        matrixValues.clear();
        matrixNotes.clear();

        File[] files = matrixDir.listFiles();
        if (files == null) return;

        // FIX: Sort files so they appear in a predictable, alphabetical order on the HUD
        java.util.Arrays.sort(files);

        for (File f : files) {
            if (f.getName().toUpperCase().endsWith(".TXT")) {
                loadMatrixFile(f);
            }
        }
    }

    private void loadMatrixFile(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();

            JSONObject json = new JSONObject(new String(data, "UTF-8"));
            JSONArray arr = json.getJSONArray("advMatrix");
            
            int[] values = new int[9];
            for (int i = 0; i < 9; i++) values[i] = arr.getInt(i);

            // LUT TRICK: Read the internal title. Fallback to stripped filename if missing.
            String uiName = json.optString("title", "");
            if (uiName.equals("")) {
                uiName = file.getName().toUpperCase().replace(".TXT", "").replace("_", " ");
                if (uiName.contains("~")) uiName = uiName.substring(0, uiName.indexOf("~"));
            }

            matrixNames.add(uiName);
            matrixValues.add(values);
            matrixNotes.add(json.optString("note", "User defined matrix."));
        } catch (Exception e) {
            android.util.Log.e("JPEG.CAM", "Failed to load matrix: " + file.getName());
        }
    }

    public void saveMatrix(String name, int[] values, String note) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"title\": \"").append(name.replace("\"", "\\\"")).append("\",\n");
            sb.append("  \"advMatrix\": [");
            for (int i = 0; i < values.length; i++) {
                sb.append(values[i]);
                if (i < values.length - 1) sb.append(", ");
            }
            sb.append("],\n  \"note\": \"").append(note.replace("\"", "\\\"")).append("\"\n}");

            // FIX: Prevent FAT32 overwrites by finding the next available M_####.TXT slot
            String safeFilename;
            int counter = 1;
            File file;
            do {
                safeFilename = String.format("M_%04d.TXT", counter);
                file = new File(matrixDir, safeFilename);
                counter++;
            } while (file.exists());

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            android.util.Log.e("JPEG.CAM", "Save failed: " + e.getMessage());
        }
    }

    public List<String> getNames() { return matrixNames; }
    public int[] getValues(int index) { return matrixValues.get(index); }
    public String getNote(int index) { return matrixNotes.get(index); }
    public int getCount() { return matrixNames.size(); }
}