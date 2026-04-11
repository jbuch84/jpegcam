package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * JPEG.CAM Controller: Photo Playback
 *
 * Owns all state and UI for in-camera review of GRADED photos.
 * Extracted from MainActivity as part of the God Class decomposition.
 *
 * Responsibilities:
 *   - Scanning /GRADED/ for processed JPEGs
 *   - Building and owning the playback FrameLayout container
 *   - Memory-safe JPEG decoding with EXIF rotation correction
 *   - Navigation (next / previous) with wrap-around
 *
 * Communicates back to MainActivity via the lightweight HostCallback interface.
 */
public class PlaybackController {

    // -----------------------------------------------------------------------
    // Host callback — lets the controller coordinate visibility of MainActivity's
    // main UI container without taking a hard reference to Activity internals.
    // -----------------------------------------------------------------------
    public interface HostCallback {
        /** Returns the primary camera viewfinder / HUD container. */
        View getMainUIContainer();
        /** Returns the current display state (0 = HUD visible, 1 = HUD hidden). */
        int getDisplayState();
    }

    private static final String TAG = "JPEG.CAM";

    private final Context      context;
    private final HostCallback host;

    // State
    private final List<File> files = new ArrayList<>();
    private int     index         = 0;
    private boolean active        = false;
    private Bitmap  currentBitmap = null;

    // Views — owned here, injected into rootLayout during construction
    private final FrameLayout container;
    private final ImageView    imageView;
    private final TextView     infoText;

    // -----------------------------------------------------------------------
    // Constructor — builds views and attaches them to the root layout
    // -----------------------------------------------------------------------
    public PlaybackController(Context context, FrameLayout rootLayout, HostCallback host) {
        this.context = context;
        this.host    = host;

        container = new FrameLayout(context);
        container.setBackgroundColor(Color.BLACK);
        container.setVisibility(View.GONE);

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        container.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        infoText = new TextView(context);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(18);
        infoText.setShadowLayer(3, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        infoParams.setMargins(0, 30, 30, 0);
        container.addView(infoText, infoParams);

        rootLayout.addView(container, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Returns true while the playback viewer is on screen. */
    public boolean isActive() { return active; }

    /**
     * Scans /GRADED/ for processed JPEGs (newest first) and enters full-screen review.
     * Does nothing if the folder is empty.
     */
    public void enter() {
        files.clear();
        File dir = Filepaths.getGradedDir();
        if (dir.exists() && dir.listFiles() != null) {
            for (File f : dir.listFiles()) {
                if (f.getName().toLowerCase().endsWith(".jpg")) files.add(f);
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File f1, File f2) {
                return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
            }
        });
        if (files.isEmpty()) return;

        active = true;
        host.getMainUIContainer().setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);
        showImage(0);
    }

    /** Hides the viewer and restores the main HUD to its previous state. */
    public void exit() {
        active = false;
        container.setVisibility(View.GONE);
        host.getMainUIContainer().setVisibility(host.getDisplayState() == 0 ? View.VISIBLE : View.GONE);
        imageView.setImageBitmap(null);
        if (currentBitmap != null) { currentBitmap.recycle(); currentBitmap = null; }
    }

    /**
     * Moves to the next (+1) or previous (-1) photo with wrap-around.
     * Also accepts arbitrary offsets from dial input.
     */
    public void navigate(int direction) {
        showImage(index + direction);
    }

    // -----------------------------------------------------------------------
    // Private — image loading
    // -----------------------------------------------------------------------

    private void showImage(int idx) {
        if (files.isEmpty()) return;
        if (idx < 0)              idx = files.size() - 1;
        if (idx >= files.size())  idx = 0;

        index = idx;
        File file = files.get(idx);

        try {
            // Free previous bitmap before allocating the next one
            imageView.setImageBitmap(null);
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
                currentBitmap = null;
            }
            System.gc();

            if (file.length() == 0) {
                infoText.setText((idx + 1) + "/" + files.size() + "\n[ERROR: 0-BYTE FILE]");
                return;
            }

            String path = file.getAbsolutePath();
            ExifInterface exif  = new ExifInterface(path);
            String fnum  = exif.getAttribute("FNumber");
            String speed = exif.getAttribute("ExposureTime");
            String iso   = exif.getAttribute("ISOSpeedRatings");

            String speedStr = "--s";
            if (speed != null) {
                try {
                    double s = Double.parseDouble(speed);
                    speedStr = (s < 1.0) ? "1/" + Math.round(1.0 / s) + "s" : Math.round(s) + "s";
                } catch (Exception ignored) {}
            }
            infoText.setText(
                (idx + 1) + " / " + files.size() + "\n"
                + file.getName() + "\n"
                + (fnum != null ? "f/" + fnum : "f/--")
                + " | " + speedStr
                + " | " + (iso != null ? "ISO " + iso : "ISO --"));

            // Memory-safe decode — 800×600 uses 40% less RAM than 1024×768 on BIONZ hardware
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);

            final int reqWidth = 800, reqHeight = 600;
            int inSampleSize = 1;
            if (opts.outHeight > reqHeight || opts.outWidth > reqWidth) {
                while ((opts.outHeight / inSampleSize) > reqHeight
                    || (opts.outWidth  / inSampleSize) > reqWidth) {
                    inSampleSize *= 2;
                }
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize       = inSampleSize;
            
            // --- FIX: TRUE COLOR & DITHERING ---
            // Upgrading from RGB_565 to ARGB_8888 to unlock full 8-bit gradients,
            // and enabling dithering to smooth out the downsampling process.
            opts.inPreferredConfig  = Bitmap.Config.ARGB_8888; 
            opts.inDither           = true; 
            
            opts.inPurgeable        = true;
            opts.inInputShareable   = true;

            Bitmap raw = BitmapFactory.decodeFile(path, opts);
            if (raw == null) {
                infoText.setText((idx + 1) + " / " + files.size() + "\n[DECODE ERROR]");
                return;
            }

            // Rotation + 16:9 aspect correction (Sony sensors save as 3:2)
            int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rot = 0;
            if      (orient == ExifInterface.ORIENTATION_ROTATE_90)  rot = 90;
            else if (orient == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (orient == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;

            Matrix matrix = new Matrix();
            if (rot != 0) matrix.postRotate(rot);
            matrix.postScale(0.8888f, 1.0f);

            Bitmap bmp = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
            if (raw != bmp) { raw.recycle(); raw = null; }

            imageView.setImageBitmap(bmp);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            currentBitmap = bmp;

        } catch (OutOfMemoryError oom) {
            android.util.Log.e(TAG, "OOM during playback. Recovering...");
            infoText.setText((idx + 1) + " / " + files.size() + "\n[MEMORY ERROR - SKIPPED]");
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
                currentBitmap = null;
            }
            System.gc();
        } catch (Exception e) {
            android.util.Log.e(TAG, "Playback error: " + e.getMessage());
        }
    }
}
