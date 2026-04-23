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
    private MainActivity activity;
    private DiptychOverlayView overlayView;
    private TextView tvTopStatus;

    private int state = 0; // 0: Need Shot 1, 1: Need Shot 2
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
        if (!enabled) {
            reset();
        }
        setVisibility(enabled);
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setVisibility(boolean visible) {
        if (overlayView != null) {
            overlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    public void reset() {
        state = 0;
        leftFilename = null;
        rightFilename = null;
        if (overlayView != null) {
            overlayView.setState(0);
        }
    }

    public int getState() {
        return state;
    }

    public void setThumbOnLeft(boolean left) {
        if (overlayView != null) {
            overlayView.setThumbOnLeft(left);
        }
    }

    public boolean isThumbOnLeft() {
        return overlayView != null && overlayView.isThumbOnLeft();
    }

    public String getLeftFilename() {
        return leftFilename;
    }

    public String getRightFilename() {
        return rightFilename;
    }

    public boolean interceptNewFile(String filename, final String originalPath) {
        if (!isEnabled) return false;
        
        if (state == 0) {
            leftFilename = filename;
            state = 1;
            
            // Instantly decode thumbnail from original file to prevent clunky UI wait!
            new Thread(new Runnable() {
                public void run() {
                    final Bitmap thumb = getDiptychThumbnail(originalPath);
                    activity.runOnUiThread(new Runnable() {
                        public void run() {
                            if (tvTopStatus != null) {
                                tvTopStatus.setText("SHOT 1 CAPTURED. [L/R] TO SWAP SIDE.");
                                tvTopStatus.setTextColor(Color.GREEN);
                            }
                            if (overlayView != null) {
                                overlayView.setThumbnail(thumb);
                                overlayView.setState(1);
                            }
                            activity.updateMainHUD();
                        }
                    });
                }
            }).start();
            return true;
        } else if (state == 1) {
            rightFilename = filename;
            state = 2; // Stitching
            return true;
        }
        return false;
    }

    public void processFirstShot(final String gradedPath) {
        // Thumbnail is now handled instantly by interceptNewFile
        activity.runOnUiThread(new Runnable() {
            public void run() {
                activity.setProcessing(false);
                activity.updateMainHUD();
            }
        });
    }

    public void processSecondShot(final String gradedLeftPath, final String gradedRightPath) {
        state = 0;
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (overlayView != null) overlayView.setState(0);
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
            opts.inSampleSize = 8;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inPurgeable = true;
            opts.inInputShareable = true;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private void performDiptychStitch(String leftPath, String rightPath, boolean firstShotLeft) {
        try {
            System.gc(); // Force memory cleanup after heavy C++ pass
            String pathL = firstShotLeft ? leftPath : rightPath;
            String pathR = firstShotLeft ? rightPath : leftPath;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inPurgeable = true;
            opts.inInputShareable = true;
            // PREVENT OOM on Sony Dalvik VM!
            // Decode at 1/8th scale (approx 750px wide halves) for 1500px total width
            opts.inSampleSize = 8; 

            BitmapRegionDecoder decoderL = BitmapRegionDecoder.newInstance(pathL, false);
            BitmapRegionDecoder decoderR = BitmapRegionDecoder.newInstance(pathR, false);
            if (decoderL == null || decoderR == null) throw new Exception("Failed to initialize Region Decoders.");

            // Get original dimensions
            int origLW = decoderL.getWidth();
            int origLH = decoderL.getHeight();
            int origLMid = origLW / 2;

            int origRW = decoderR.getWidth();
            int origRH = decoderR.getHeight();
            int origRMid = origRW / 2;

            // Calculate scaled down dimensions (since inSampleSize = 8)
            int scaledLW = origLW / 8;
            int scaledLH = origLH / 8;
            int scaledLMid = origLMid / 8;

            int scaledRW = origRW / 8;
            int scaledRH = origRH / 8;
            int scaledRMid = origRMid / 8;

            int finalW = scaledLMid + (scaledRW - scaledRMid);
            int finalH = Math.min(scaledLH, scaledRH);

            Bitmap composite = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(composite);

            // Region is specified in ORIGINAL image coordinates
            Rect srcL;
            Rect srcR;
            if (firstShotLeft) {
                srcL = new Rect(0, 0, origLMid, origLH); // LEFT half of 1st shot
                srcR = new Rect(origRMid, 0, origRW, origRH); // RIGHT half of 2nd shot
            } else {
                srcR = new Rect(0, 0, origRMid, origRH); // LEFT half of 1st shot
                srcL = new Rect(0, 0, origLMid, origLH); // LEFT half of 2nd shot
            }

            Bitmap bmpL = decoderL.decodeRegion(srcL, opts);
            decoderL.recycle();
            if (bmpL != null) {
                canvas.drawBitmap(bmpL, 0, 0, null);
                bmpL.recycle();
                bmpL = null;
            }

            Bitmap bmpR = decoderR.decodeRegion(srcR, opts);
            decoderR.recycle();
            if (bmpR != null) {
                canvas.drawBitmap(bmpR, scaledLMid, 0, null);
                bmpR.recycle();
                bmpR = null;
            }

            // Draw analog center divider
            Paint dividerPaint = new Paint();
            dividerPaint.setColor(Color.BLACK);
            dividerPaint.setStrokeWidth(Math.max(4, finalW / 400));
            canvas.drawLine(scaledLMid, 0, scaledLMid, finalH, dividerPaint);

            File finalOut = new File(Filepaths.getGradedDir(), "DIPTYCH_" + new File(rightPath).getName());
            FileOutputStream out = new FileOutputStream(finalOut);
            composite.compress(Bitmap.CompressFormat.JPEG, activity.getPrefJpegQuality(), out);
            out.close();
            composite.recycle();
            composite = null;

            // Delete the individual graded halves to keep the folder clean
            new File(leftPath).delete();
            new File(rightPath).delete();

            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.setProcessing(false);
                    if (tvTopStatus != null) {
                        tvTopStatus.setText("DIPTYCH SAVED");
                        tvTopStatus.setTextColor(Color.WHITE);
                    }
                    activity.updateMainHUD();
                }
            });
        } catch (final Throwable e) {
            e.printStackTrace();
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.setProcessing(false);
                    if (tvTopStatus != null) {
                        tvTopStatus.setText("DIPTYCH FAILED (OOM)");
                        tvTopStatus.setTextColor(Color.RED);
                    }
                    activity.updateMainHUD();
                }
            });
        }
    }
}