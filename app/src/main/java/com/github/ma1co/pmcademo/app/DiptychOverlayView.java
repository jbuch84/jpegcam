package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

public class DiptychOverlayView extends View {
    private Paint linePaint;
    private Paint thumbPaint;
    private Paint darkPaint;
    private Paint framePaint;
    private Bitmap thumbnail;
    private boolean thumbOnLeft = true;
    private int state = DiptychManager.STATE_NEED_FIRST;

    public DiptychOverlayView(Context context) {
        super(context);

        linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(2);

        thumbPaint = new Paint();
        thumbPaint.setAlpha(255);

        darkPaint = new Paint();
        darkPaint.setColor(Color.BLACK);
        darkPaint.setAlpha(180);

        framePaint = new Paint();
        framePaint.setColor(Color.WHITE);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3);
        framePaint.setAntiAlias(false);
    }

    public void setState(int state) {
        this.state = state;
        if (state == DiptychManager.STATE_NEED_FIRST) {
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

        if (state == DiptychManager.STATE_NEED_FIRST) {
            int quarter = w / 4;
            int mg = Math.max(8, w / 32);
            int bl = h / 10;
            int cy = h / 2;
            int crossLen = 14;

            darkPaint.setAlpha(220);
            canvas.drawRect(0, 0, quarter, h, darkPaint);
            canvas.drawRect(w - quarter, 0, w, h, darkPaint);
            darkPaint.setAlpha(180);

            canvas.drawLine(quarter + mg, mg, quarter + mg + bl, mg, framePaint);
            canvas.drawLine(quarter + mg, mg, quarter + mg, mg + bl, framePaint);
            canvas.drawLine(w - quarter - mg, mg, w - quarter - mg - bl, mg, framePaint);
            canvas.drawLine(w - quarter - mg, mg, w - quarter - mg, mg + bl, framePaint);
            canvas.drawLine(quarter + mg, h - mg, quarter + mg + bl, h - mg, framePaint);
            canvas.drawLine(quarter + mg, h - mg, quarter + mg, h - mg - bl, framePaint);
            canvas.drawLine(w - quarter - mg, h - mg, w - quarter - mg - bl, h - mg, framePaint);
            canvas.drawLine(w - quarter - mg, h - mg, w - quarter - mg, h - mg - bl, framePaint);

            canvas.drawLine(mid - crossLen, cy, mid + crossLen, cy, framePaint);
            canvas.drawLine(mid, cy - crossLen, mid, cy + crossLen, framePaint);
        } else if (state == DiptychManager.STATE_NEED_SECOND || state == DiptychManager.STATE_STITCHING) {
            if (thumbOnLeft) {
                canvas.drawRect(0, 0, mid, h, darkPaint);
            } else {
                canvas.drawRect(mid, 0, w, h, darkPaint);
            }

            if (thumbnail != null && !thumbnail.isRecycled()) {
                int tW = thumbnail.getWidth();
                int tH = thumbnail.getHeight();
                int srcLeft = tW / 4;
                int srcRight = srcLeft + (tW / 2);

                Rect srcRect = new Rect(srcLeft, 0, srcRight, tH);
                Rect dstRect = thumbOnLeft ? new Rect(0, 0, mid, h) : new Rect(mid, 0, w, h);
                canvas.drawBitmap(thumbnail, srcRect, dstRect, thumbPaint);
            }

            if (state == DiptychManager.STATE_STITCHING) {
                darkPaint.setAlpha(120);
                canvas.drawRect(0, 0, w, h, darkPaint);
                darkPaint.setAlpha(180);
            }
        }

        if (state != DiptychManager.STATE_NEED_FIRST) {
            canvas.drawLine(mid, 0, mid, h, linePaint);
        }
    }
}
