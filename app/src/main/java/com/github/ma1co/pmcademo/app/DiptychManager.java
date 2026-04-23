package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

public class DiptychManager {
    public static final int STATE_NEED_FIRST = 0;
    public static final int STATE_NEED_SECOND = 1;
    public static final int STATE_STITCHING = 2;

    private MainActivity activity;
    private DiptychOverlayView overlayView;
    private TextView tvTopStatus;

    private int state = STATE_NEED_FIRST;
    private String leftFilename = null;
    private String rightFilename = null;
    private boolean isEnabled = false;

    public DiptychManager(MainActivity activity, FrameLayout container, TextView tvTopStatus) {
        this.activity = activity;
        this.tvTopStatus = tvTopStatus;
        this.overlayView = new DiptychOverlayView(activity);
        this.overlayView.setVisibility(View.GONE);
        // ADD AT INDEX 0 to be behind HUD elements
        container.addView(this.overlayView, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) reset();
        setVisibility(enabled);
    }

    public boolean isEnabled() { return isEnabled; }

    public void setVisibility(boolean visible) {
        if (overlayView != null) overlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void reset() {
        state = STATE_NEED_FIRST;
        leftFilename = null;
        rightFilename = null;
        if (overlayView != null) overlayView.setState(STATE_NEED_FIRST);
    }

    public int getState() { return state; }
    public void setThumbOnLeft(boolean left) { if (overlayView != null) overlayView.setThumbOnLeft(left); }
    public boolean isThumbOnLeft() { return overlayView != null && overlayView.isThumbOnLeft(); }
    public String getLeftFilename() { return leftFilename; }
    public String getRightFilename() { return rightFilename; }

    private native boolean stitchDiptychNative(String p1, String p2, String out, boolean left, int quality);

    public boolean interceptNewFile(String filename, final String originalPath) {
        if (!isEnabled) return false;
        if (state == STATE_NEED_FIRST) {
            leftFilename = filename;
            rightFilename = null;
            state = STATE_NEED_SECOND;
            // INSTANT PREVIEW: Decode a tiny thumbnail from the original un-graded photo.
            // inSampleSize=16 gives ~375x250 — fast decode on slow ARM, plenty for a guide.
            // Wait loop shortened to 4x50ms (200ms max) instead of 10x100ms (1s max).
            new Thread(new Runnable() {
                public void run() {
                    File f = new File(originalPath);
                    long last = -1;
                    for (int i = 0; i < 4; i++) {
                        if (f.exists() && f.length() > 0 && f.length() == last) break;
                        last = f.length();
                        try { Thread.sleep(50); } catch (Exception e) {}
                    }
                    final Bitmap thumb = getDiptychThumbnail(originalPath);
                    activity.runOnUiThread(new Runnable() {
                        public void run() {
                            if (overlayView != null) {
                                overlayView.setThumbnail(thumb);
                                overlayView.setState(STATE_NEED_SECOND);
                            }
                            activity.updateMainHUD();
                        }
                    });
                }
            }).start();
            return true;
        } else if (state == STATE_NEED_SECOND) {
            rightFilename = filename;
            state = STATE_STITCHING;
            if (overlayView != null) overlayView.setState(STATE_STITCHING);
            return true;
        }
        return false;
    }

    public void processFirstShot(final String gradedPath) {
        // Unlock shutter after grading is done
        activity.runOnUiThread(new Runnable() {
            public void run() {
                activity.setProcessing(false);
                activity.armFileScanner();
                if (tvTopStatus != null) {
                    tvTopStatus.setText("SHOT 1 SAVED. [L/R] TO SWAP SIDE.");
                    tvTopStatus.setTextColor(Color.GREEN);
                }
                activity.updateMainHUD();
            }
        });
    }

    public void processSecondShot(final String gradedLeftPath, final String gradedRightPath) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (overlayView != null) overlayView.setState(STATE_STITCHING);
                if (tvTopStatus != null) {
                    tvTopStatus.setText("STITCHING DIPTYCH...");
                    tvTopStatus.setTextColor(Color.YELLOW);
                }
            }
        });

        final boolean firstShotLeft = isThumbOnLeft();
        new Thread(new Runnable() {
            public void run() {
                performDiptychStitch(gradedLeftPath, gradedRightPath, firstShotLeft);
            }
        }).start();
    }

    private Bitmap getDiptychThumbnail(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 16; // ~375x250 — fast decode, plenty for a composition guide
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) { return null; }
    }

    private void performDiptychStitch(String leftPath, String rightPath, boolean firstShotLeft) {
        try {
            System.gc();
            File finalOut = new File(Filepaths.getGradedDir(), "DIPTYCH_" + new File(rightPath).getName());
            
            // USE C++ ENGINE FOR FULL RESOLUTION STITCHING!
            final boolean success = stitchDiptychNative(leftPath, rightPath, finalOut.getAbsolutePath(), firstShotLeft, activity.getPrefJpegQuality());

            if (success) {
                new File(leftPath).delete();
                new File(rightPath).delete();
            }

            activity.runOnUiThread(new Runnable() {
                public void run() {
                    activity.setProcessing(false);
                    reset();
                    if (tvTopStatus != null) {
                        tvTopStatus.setText(success ? "DIPTYCH SAVED" : "DIPTYCH FAILED");
                        tvTopStatus.setTextColor(success ? Color.WHITE : Color.RED);
                    }
                    activity.updateMainHUD();
                }
            });
        } catch (Throwable e) {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    activity.setProcessing(false);
                    reset();
                    if (tvTopStatus != null) {
                        tvTopStatus.setText("DIPTYCH FAILED");
                        tvTopStatus.setTextColor(Color.RED);
                    }
                    activity.updateMainHUD();
                }
            });
        }
    }
}
