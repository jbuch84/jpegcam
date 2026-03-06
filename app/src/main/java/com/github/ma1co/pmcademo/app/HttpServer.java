package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.os.Environment;
import android.os.StatFs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Restored the NanoHTTPD Sub-Imports!
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoHTTPD.TempFile;
import fi.iki.elonen.NanoHTTPD.TempFileManager;
import fi.iki.elonen.NanoHTTPD.TempFileManagerFactory;

public class HttpServer extends NanoHTTPD {
    public static final int PORT = 8080;
    private Context context;

    public HttpServer(Context context) {
        super(PORT);
        this.context = context;
        
        // Route NanoHTTPD Temp files to Android Cache to prevent Sony /tmp crash
        this.setTempFileManagerFactory(new TempFileManagerFactory() {
            @Override
            public TempFileManager create() {
                return new AndroidTempFileManager(HttpServer.this.context);
            }
        });
    }

    // Android-Safe Temporary File Handlers
    private static class AndroidTempFile implements TempFile {
        private File file;
        private OutputStream fstream;
        public AndroidTempFile(Context ctx) throws IOException {
            File cacheDir = ctx.getCacheDir();
            if (cacheDir == null) {
                cacheDir = new File(Environment.getExternalStorageDirectory(), "LUTS/.tmp");
            }
            if (!cacheDir.exists()) cacheDir.mkdirs();
            file = File.createTempFile("NanoHTTPD-", "", cacheDir);
            fstream = new FileOutputStream(file);
        }
        @Override public void delete() throws Exception { file.delete(); }
        @Override public String getName() { return file.getAbsolutePath(); }
        @Override public OutputStream open() throws Exception { return fstream; }
    }

    private static class AndroidTempFileManager implements TempFileManager {
        private Context context;
        private List<TempFile> tempFiles;
        public AndroidTempFileManager(Context context) {
            this.context = context;
            this.tempFiles = new ArrayList<TempFile>();
        }
        @Override public void clear() {
            for (TempFile file : tempFiles) {
                try { file.delete(); } catch (Exception ignored) {}
            }
            tempFiles.clear();
        }
        @Override public TempFile createTempFile(String filename_hint) throws Exception {
            AndroidTempFile tempFile = new AndroidTempFile(context);
            tempFiles.add(tempFile);
            return tempFile;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        File root = Environment.getExternalStorageDirectory();

        try {
            // LUT UPLOAD ENDPOINT
            if (Method.POST.equals(session.getMethod()) && uri.equals("/api/upload_lut")) {
                try {
                    Map<String, String> files = new HashMap<String, String>();
                    session.parseBody(files);
                    String tempFilePath = files.get("file");
                    String originalFileName = session.getParms().get("file");

                    if (tempFilePath != null && originalFileName != null) {
                        File lutDir = new File(root, "LUTS");
                        if (!lutDir.exists()) lutDir.mkdirs();
                        
                        if (!originalFileName.toLowerCase().endsWith(".cube") && !originalFileName.toLowerCase().endsWith(".cub")) {
                            return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"error\":\"Only .cube files allowed\"}");
                        }

                        File destFile = new File(lutDir, originalFileName);
                        
                        FileInputStream in = new FileInputStream(tempFilePath);
                        FileOutputStream out = new FileOutputStream(destFile);
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        in.close();
                        out.flush();
                        out.close();

                        return newFixedLengthResponse(Status.OK, "application/json", "{\"status\":\"success\"}");
                    } else {
                        return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"error\":\"No file provided\"}");
                    }
                } catch (Exception e) {
                    return newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Upload failed\"}");
                }
            }

            if (uri.equals("/")) {
                InputStream is = context.getAssets().open("index.html");
                return newChunkedResponse(Status.OK, "text/html", is);
            }

            if (uri.equals("/api/system")) {
                StatFs stat = new StatFs(root.getPath());
                long bytesAvailable = (long)stat.getBlockSize() * (long)stat.getAvailableBlocks();
                double gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0);
                boolean hasGraded = new File(root, "GRADED").exists();
                String json = String.format("{\"storage_gb\": \"%.1f\", \"has_graded\": %b}", gbAvailable, hasGraded);
                return newFixedLengthResponse(Status.OK, "application/json", json);
            }

            if (uri.startsWith("/api/files")) {
                Map<String, String> params = session.getParms();
                String folderParam = params.get("folder"); 
                File targetDir = (folderParam != null && folderParam.equals("GRADED")) 
                                 ? new File(root, "GRADED") 
                                 : new File(root, "DCIM/100MSDCF");

                List<File> allFiles = getMediaFiles(targetDir);
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
                return newFixedLengthResponse(Status.OK, "application/json", json.toString());
            }

            if (uri.startsWith("/thumb/")) {
                Map<String, String> params = session.getParms();
                String folder = params.get("folder");
                String name = params.get("name");
                File file = new File(root, (folder.equals("GRADED") ? "GRADED" : "DCIM/100MSDCF") + "/" + name);

                if (file.exists()) {
                    if (folder.equals("DCIM")) {
                        try {
                            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                            byte[] thumb = exif.getThumbnail();
                            if (thumb != null) return newFixedLengthResponse(Status.OK, "image/jpeg", new ByteArrayInputStream(thumb), thumb.length);
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
                        return newFixedLengthResponse(Status.OK, "image/jpeg", new ByteArrayInputStream(data), data.length);
                    }
                }
            }

            if (uri.startsWith("/full/")) {
                Map<String, String> params = session.getParms();
                String folder = params.get("folder");
                String name = params.get("name");
                File file = new File(root, (folder.equals("GRADED") ? "GRADED" : "DCIM/100MSDCF") + "/" + name);
                if (file.exists()) return newFixedLengthResponse(Status.OK, "image/jpeg", new FileInputStream(file), file.length());
            }

        } catch (Exception e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Server Error");
        }
        return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404");
    }

    private List<File> getMediaFiles(File dir) {
        List<File> result = new ArrayList<File>();
        if (!dir.exists()) return result;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isDirectory() && f.getName().toLowerCase().endsWith(".jpg")) {
                    result.add(f);
                }
            }
        }
        Collections.sort(result, new Comparator<File>() {
            public int compare(File f1, File f2) { return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified()); }
        });
        return result;
    }
}