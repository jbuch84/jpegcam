package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;

public class DiptychManager {
    public static final int STATE_NEED_FIRST = 0;
    public static final int STATE_NEED_SECOND = 1;
    public static final int STATE_STITCHING = 2;
    public static final int STATE_PROCESSING_FIRST = 3;

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
        if (activity != null) activity.updateDiptychPreviewWindow();
    }

    public int getState() { return state; }

    public void setThumbOnLeft(boolean left) {
        if (overlayView != null) overlayView.setThumbOnLeft(left);
        if (activity != null) activity.updateDiptychPreviewWindow();
    }

    public boolean isThumbOnLeft() { return overlayView != null && overlayView.isThumbOnLeft(); }
    public String getLeftFilename() { return leftFilename; }
    public String getRightFilename() { return rightFilename; }

    private native boolean stitchDiptychNative(String p1, String p2, String out, boolean left, int quality);

    public boolean interceptNewFile(String filename, final String originalPath) {
        if (!isEnabled) return false;
        if (state == STATE_NEED_FIRST) {
            leftFilename = filename;
            rightFilename = null;
            state = STATE_PROCESSING_FIRST;
            if (activity != null) activity.updateDiptychPreviewWindow();
            return true;
        } else if (state == STATE_NEED_SECOND) {
            rightFilename = filename;
            state = STATE_STITCHING;
            if (overlayView != null) overlayView.setState(STATE_STITCHING);
            if (activity != null) activity.updateDiptychPreviewWindow();
            return true;
        }
        return false;
    }

    public void processFirstShot(final String gradedPath) {
        state = STATE_NEED_SECOND;
        final Bitmap thumb = getDiptychThumbnail(gradedPath);
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (overlayView != null) {
                    overlayView.setThumbnail(thumb);
                    overlayView.setState(STATE_NEED_SECOND);
                }
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
            opts.inSampleSize = 16;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private void performDiptychStitch(String leftPath, String rightPath, boolean firstShotLeft) {
        try {
            System.gc();
            File finalOut = new File(Filepaths.getGradedDir(), "DIPTYCH_" + new File(rightPath).getName());

            Log.d("JPEG.CAM", "Diptych stitch start: left=" + leftPath + " right=" + rightPath + " out=" + finalOut.getAbsolutePath());
            final boolean success = stitchDiptychNative(leftPath, rightPath, finalOut.getAbsolutePath(), firstShotLeft, activity.getPrefJpegQuality());
            Log.d("JPEG.CAM", "Diptych stitch result: " + success);

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
            Log.e("JPEG.CAM", "Diptych stitch exception", e);
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
