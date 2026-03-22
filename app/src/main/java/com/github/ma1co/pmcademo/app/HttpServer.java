// Part 1 of 1 - HttpServer.java (Replaces existing file)
// Location: app/src/main/java/com/github/ma1co/pmcademo/app/HttpServer.java

package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.os.StatFs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {
    public static final int PORT = 8080;
    private Context context;

    public HttpServer(Context context) {
        super(PORT);
        this.context = context;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        
        try {
            // Upload endpoint
            if (Method.POST.equals(session.getMethod()) && (uri.equals("/api/upload_lut") || uri.equals("/api/upload"))) {
                FileOutputStream out = null;
                File tempFile = null;
                File destFile = null;
                
                try {
                    Map<String, String> headers = session.getHeaders();
                    String fileName = headers.get("x-file-name");
                    
                    if (fileName == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing filename\"}");
                    }

                    // --- THE SMART ROUTER LOGIC ---
                    String lowerName = fileName.toLowerCase();
                    File targetDir = null;
                    
                    if (lowerName.endsWith(".cube") || lowerName.endsWith(".cub")) {
                        targetDir = Filepaths.getLutDir(); // Use centralized truth
                    } else if (lowerName.endsWith(".txt") || lowerName.endsWith(".lens")) {
                        targetDir = new File(Filepaths.getAppDir(), "LENSES"); // Keep it inside JPGCAM
                    } else {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Unsupported file type\"}");
                    }

                    if (!targetDir.exists()) targetDir.mkdirs();

                    String contentLengthStr = headers.get("content-length");
                    int contentLength = contentLengthStr != null ? Integer.parseInt(contentLengthStr) : 0;
                    
                    // Save as .tmp so Sony's MediaScanner completely ignores the incoming stream
                    tempFile = new File(targetDir, "upload_" + System.currentTimeMillis() + ".tmp");
                    destFile = new File(targetDir, fileName);

                    InputStream in = session.getInputStream();
                    out = new FileOutputStream(tempFile);
                    
                    byte[] buffer = new byte[8192];
                    int read;
                    int totalRead = 0;
                    
                    while (totalRead < contentLength) {
                        int bytesToRead = Math.min(buffer.length, contentLength - totalRead);
                        read = in.read(buffer, 0, bytesToRead);
                        if (read == -1) break;
                        out.write(buffer, 0, read);
                        totalRead += read;
                    }
                    
                    out.flush();
                    out.close(); 
                    out = null;

                    // --- THE SWITCH ---
                    if (tempFile.exists()) {
                        if (destFile.exists()) destFile.delete(); 
                        boolean success = tempFile.renameTo(destFile);
                        if (!success) {
                            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"OS blocked rename operation\"}");
                        }
                    }

                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"success\"}");
                } catch (Exception e) {
                    if (tempFile != null && tempFile.exists()) tempFile.delete(); 
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Upload stream failed\"}");
                } finally {
                    try { if (out != null) out.close(); } catch (Exception e) {}
                }
            }

            if (uri.equals("/")) {
                InputStream is = context.getAssets().open("index.html");
                return newChunkedResponse(Response.Status.OK, "text/html", is);
            }

            if (uri.equals("/api/system")) {
                StatFs stat = new StatFs(Filepaths.getStorageRoot().getPath());
                long bytesAvailable = (long)stat.getBlockSize() * (long)stat.getAvailableBlocks();
                double gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0);
                
                File gradedDir = Filepaths.getGradedDir();
                boolean hasGraded = gradedDir.exists() && gradedDir.listFiles() != null && gradedDir.listFiles().length > 0;
                
                String json = String.format("{\"storage_gb\": \"%.1f\", \"has_graded\": %b}", gbAvailable, hasGraded);
                return newFixedLengthResponse(Response.Status.OK, "application/json", json);
            }

            if (uri.startsWith("/api/files")) {
                Map<String, String> params = session.getParms();
                String folderParam = params.get("folder"); 
                
                List<File> allFiles = getMediaFiles(folderParam);
                StringBuilder json = new StringBuilder();
                json.append("{\"folder\": \"").append(folderParam).append("\", \"files\": [");
                for (int i = 0; i < allFiles.size(); i++) {
                    File f = allFiles.get(i);
                    json.append("{\"name\":\"").append(f.getName())
                        .append("\", \"date\":").append(f.lastModified())
                        .append(", \"size\":").append(f.length()).append("}");
                    if (i < allFiles.size() - 1) json.append(",");
                }
                json.append("]}");
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
            }

            if (uri.startsWith("/thumb/") || uri.startsWith("/full/")) {
                Map<String, String> params = session.getParms();
                String folder = params.get("folder");
                String name = params.get("name");
                
                File file = findRequestedFile(folder, name);

                if (file != null && file.exists()) {
                    if (uri.startsWith("/full/")) {
                        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(file), file.length());
                    } else {
                        // Thumbnail processing
                        if (folder != null && !folder.equals("GRADED")) {
                            try {
                                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                                byte[] thumb = exif.getThumbnail();
                                if (thumb != null) return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(thumb), thumb.length);
                            } catch (Exception e) {}
                        }
                        
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 8;
                        opts.inPurgeable = true; 
                        Bitmap bm = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                        if (bm != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bm.compress(Bitmap.CompressFormat.JPEG, 60, baos);
                            byte[] data = baos.toByteArray();
                            bm.recycle(); 
                            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(data), data.length);
                        }
                    }
                }
            }

        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error");
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404");
    }

    /**
     * Dynamically searches for original JPEGs across all Sony MSDCF folders, 
     * preventing the "100MSDCF" hardcode bug.
     */
    private List<File> getMediaFiles(String folderType) {
        List<File> result = new ArrayList<File>();
        
        if (folderType != null && folderType.equals("GRADED")) {
            File gradedDir = Filepaths.getGradedDir();
            if (gradedDir.exists()) {
                File[] files = gradedDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.isDirectory() && f.getName().toLowerCase().endsWith(".jpg")) result.add(f);
                    }
                }
            }
        } else {
            // Scan DCIM dynamically
            File dcimDir = Filepaths.getDcimDir();
            if (dcimDir.exists()) {
                File[] subDirs = dcimDir.listFiles();
                if (subDirs != null) {
                    for (File subDir : subDirs) {
                        if (subDir.isDirectory() && subDir.getName().toUpperCase().endsWith("MSDCF")) {
                            File[] files = subDir.listFiles();
                            if (files != null) {
                                for (File f : files) {
                                    if (!f.isDirectory() && f.getName().toLowerCase().endsWith(".jpg")) result.add(f);
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Collections.sort(result, new Comparator<File>() {
            public int compare(File f1, File f2) { return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified()); }
        });
        return result;
    }

    /**
     * Helper to safely locate a file without hardcoding its exact subdirectory.
     */
    private File findRequestedFile(String folder, String name) {
        if (folder != null && folder.equals("GRADED")) {
            return new File(Filepaths.getGradedDir(), name);
        } else {
            File dcimDir = Filepaths.getDcimDir();
            if (dcimDir.exists()) {
                File[] subDirs = dcimDir.listFiles();
                if (subDirs != null) {
                    for (File subDir : subDirs) {
                        if (subDir.isDirectory() && subDir.getName().toUpperCase().endsWith("MSDCF")) {
                            File testFile = new File(subDir, name);
                            if (testFile.exists()) return testFile; // Found it
                        }
                    }
                }
            }
        }
        return null; // Not found
    }
}