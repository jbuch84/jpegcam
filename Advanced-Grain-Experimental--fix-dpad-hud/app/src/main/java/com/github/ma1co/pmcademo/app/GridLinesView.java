package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * JPEG.CAM UI: Rule of Thirds Grid Overlay
 * Extracts the grid drawing logic from MainActivity to reduce file size.
 */
public class GridLinesView extends View {
    private Paint paint;

    public GridLinesView(Context context) {
        super(context);
        paint = new Paint();
        paint.setColor(Color.argb(120, 255, 255, 255)); // Semi-transparent white
        paint.setStrokeWidth(2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        
        // Calculate 3:2 aspect ratio safe area for the grid
        int imgW = w;
        int imgH = (int) (w * (2.0f / 3.0f));
        if (imgH > h) {
            imgH = h;
            imgW = (int) (h * (3.0f / 2.0f));
        }
        int offsetX = (w - imgW) / 2;
        int offsetY = (h - imgH) / 2;

        // Draw vertical thirds
        canvas.drawLine(offsetX + imgW / 3f, offsetY, offsetX + imgW / 3f, offsetY + imgH, paint);
        canvas.drawLine(offsetX + (imgW * 2f) / 3f, offsetY, offsetX + (imgW * 2f) / 3f, offsetY + imgH, paint);
        
        // Draw horizontal thirds
        canvas.drawLine(offsetX, offsetY + imgH / 3f, offsetX + imgW, offsetY + imgH / 3f, paint);
        canvas.drawLine(offsetX, offsetY + (imgH * 2f) / 3f, offsetX + imgW, offsetY + (imgH * 2f) / 3f, paint);
    }
}