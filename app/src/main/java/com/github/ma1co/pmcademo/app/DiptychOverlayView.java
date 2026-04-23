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
            // HALF FRAME STYLE for shot 1:
            // Light mask on the inactive right half so the user focuses on the left.
            // Corner brackets on the active left half evoke a half-frame camera viewfinder.
            darkPaint.setAlpha(100); // ~40% dark — subtle hint, not a heavy block
            canvas.drawRect(mid, 0, w, h, darkPaint);
            darkPaint.setAlpha(180); // restore for state-1 use

            // Corner bracket marks on the active (left) half
            int mg = Math.max(8, w / 32);  // margin from screen edge
            int bl = h / 10;               // bracket arm length
            // Top-left corner
            canvas.drawLine(mg,       mg,            mg + bl,       mg,            framePaint);
            canvas.drawLine(mg,       mg,            mg,            mg + bl,       framePaint);
            // Top-right corner of active half (near center line)
            canvas.drawLine(mid - mg, mg,            mid - mg - bl, mg,            framePaint);
            canvas.drawLine(mid - mg, mg,            mid - mg,      mg + bl,       framePaint);
            // Bottom-left corner
            canvas.drawLine(mg,       h - mg,        mg + bl,       h - mg,        framePaint);
            canvas.drawLine(mg,       h - mg,        mg,            h - mg - bl,   framePaint);
            // Bottom-right corner of active half
            canvas.drawLine(mid - mg, h - mg,        mid - mg - bl, h - mg,        framePaint);
            canvas.drawLine(mid - mg, h - mg,        mid - mg,      h - mg - bl,   framePaint);

            // Center reference crosshair — shows exact center of the active left half
            // so the user knows where to place their subject for the half-frame shot.
            int cx0 = mid / 2;
            int cy0 = h / 2;
            int crossLen = 14;
            canvas.drawLine(cx0 - crossLen, cy0, cx0 + crossLen, cy0, framePaint);
            canvas.drawLine(cx0, cy0 - crossLen, cx0, cy0 + crossLen, framePaint);

        } else if (state == 1) {
            if (thumbOnLeft) {
                canvas.drawRect(0, 0, mid, h, darkPaint);
            } else {
                canvas.drawRect(mid, 0, w, h, darkPaint);
            }

            if (thumbnail != null && !thumbnail.isRecycled()) {
                int tW = thumbnail.getWidth();
                int tH = thumbnail.getHeight();
                int tMid = tW / 2;
                
                Rect srcRect;
                Rect dstRect;
                
                if (thumbOnLeft) {
                    srcRect = new Rect(0, 0, tMid, tH);
                    dstRect = new Rect(0, 0, mid, h);
                } else {
                    srcRect = new Rect(0, 0, tMid, tH); // ALWAYS take the left crop of the first shot
                    dstRect = new Rect(mid, 0, w, h);
                }
                
                canvas.drawBitmap(thumbnail, srcRect, dstRect, thumbPaint);
            }
        }
        
        // Always draw the center framing line
        canvas.drawLine(mid, 0, mid, h, linePaint);
    }
}
