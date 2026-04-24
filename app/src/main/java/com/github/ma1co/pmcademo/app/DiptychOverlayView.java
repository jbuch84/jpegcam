package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;

public class DiptychOverlayView extends View {
    private Paint linePaint;
    private Paint thumbPaint;
    private Paint darkPaint;
    private Paint framePaint;
    private Bitmap thumbnail;
    private boolean thumbOnLeft = true;
    private int state = 0; // 0: Need Shot 1, 1: Need Shot 2

    public DiptychOverlayView(Context context) {
        super(context);
        
        linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(2);
        
        thumbPaint = new Paint();
        thumbPaint.setAlpha(200); // ~78% opacity to make the preview much more visible
        
        darkPaint = new Paint();
        darkPaint.setColor(Color.BLACK);
        darkPaint.setAlpha(180); // ~70% opacity for a much darker mask

        framePaint = new Paint();
        framePaint.setColor(Color.WHITE);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3);
        framePaint.setAntiAlias(false); // crisp lines on small camera screens
    }

    public void setState(int state) {
        this.state = state;
        if (state == 0) {
            if (thumbnail != null && !thumbnail.isRecycled()) {
                thumbnail.recycle();
            }
            thumbnail = null;
            thumbOnLeft = true;
        }
        invalidate();
    }

    public void setThumbnail(Bitmap thumb) {
        this.thumbnail = thumb;
        invalidate();
    }

    public void setThumbOnLeft(boolean onLeft) {
        this.thumbOnLeft = onLeft;
        invalidate();
    }

    public boolean isThumbOnLeft() {
        return thumbOnLeft;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int mid = w / 2;

        if (state == 0) {
            // BOTH MASKED: Subject in sensor center (Center 50% window)
            // Mask left 25% and right 25%. Subject in middle 50% hit AF point.
            int m25 = w / 4;
            int m75 = w * 3 / 4;
            
            darkPaint.setAlpha(100);
            canvas.drawRect(0, 0, m25, h, darkPaint);
            canvas.drawRect(m75, 0, w, h, darkPaint);
            darkPaint.setAlpha(180);

            // Framing brackets for the middle 50%
            int mg = Math.max(8, w / 32);
            int bl = h / 10;
            // Top-left
            canvas.drawLine(m25 + mg, mg, m25 + mg + bl, mg, framePaint);
            canvas.drawLine(m25 + mg, mg, m25 + mg, mg + bl, framePaint);
            // Top-right
            canvas.drawLine(m75 - mg, mg, m75 - mg - bl, mg, framePaint);
            canvas.drawLine(m75 - mg, mg, m75 - mg, mg + bl, framePaint);
            // Bottom-left
            canvas.drawLine(m25 + mg, h - mg, m25 + mg + bl, h - mg, framePaint);
            canvas.drawLine(m25 + mg, h - mg, m25 + mg, h - mg - bl, framePaint);
            // Bottom-right
            canvas.drawLine(m75 - mg, h - mg, m75 - mg - bl, h - mg, framePaint);
            canvas.drawLine(m75 - mg, h - mg, m75 - mg, h - mg - bl, framePaint);

            // Physical sensor center crosshair
            int cx = w / 2;
            int cy = h / 2;
            int crossLen = 14;
            canvas.drawLine(cx - crossLen, cy, cx + crossLen, cy, framePaint);
            canvas.drawLine(cx, cy - crossLen, cx, cy + crossLen, framePaint);

        } else if (state == 1) {
            // SHOW PREVIEW ON CHOSEN SIDE. 
            // ACTIVE SIDE (opposite) must show sensor center at its internal center.
            // If preview on LEFT: Active window is [mid, w]. Sensor center [w/4, 3w/4] must map here.
            // This means we mask edges of the sensor to center the AF point in the active half.
            
            if (thumbOnLeft) {
                // Preview on Left. Framing on Right.
                // Mask edges of the right half to center the sensor center.
                // Physical sensor range [25%, 75%] maps to UI range [50%, 100%].
                // Actually, to KEEP AF centered in the open window:
                // We just need to ensure the open window displays the center of the sensor.
                canvas.drawRect(0, 0, mid, h, darkPaint);
            } else {
                // Preview on Right. Framing on Left.
                canvas.drawRect(mid, 0, w, h, darkPaint);
            }

            if (thumbnail != null && !thumbnail.isRecycled()) {
                int tW = thumbnail.getWidth();
                int tH = thumbnail.getHeight();
                
                // Extract center 50% of the thumbnail (matches native stitch)
                Rect srcRect = new Rect(tW / 4, 0, tW * 3 / 4, tH);
                Rect dstRect = thumbOnLeft ? new Rect(0, 0, mid, h) : new Rect(mid, 0, w, h);
                
                canvas.drawBitmap(thumbnail, srcRect, dstRect, thumbPaint);
            }
            
            // Physical sensor center crosshair (nudged liveview logic)
            // The liveview is nudged by 25%, so the sensor center (AF) 
            // is now exactly at 1/4 or 3/4 of the screen width.
            int cx = thumbOnLeft ? (w * 3 / 4) : (w / 4);
            int cy = h / 2;
            int crossLen = 14;
            canvas.drawLine(cx - crossLen, cy, cx + crossLen, cy, framePaint);
            canvas.drawLine(cx, cy - crossLen, cx, cy + crossLen, framePaint);
        }
        
        // Always draw the center framing line
        canvas.drawLine(mid, 0, mid, h, linePaint);
    }
}
