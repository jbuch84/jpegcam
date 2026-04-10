package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * JPEG.CAM UI: Legacy Sony Battery Icon
 * Replicates the specific 3-segment battery drawing logic from the original project.
 */
public class BatteryView extends View {
    private Paint paint;
    private int level = 100;

    public BatteryView(Context context) {
        super(context);
        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStrokeWidth(2); 
    }

    public void setLevel(int level) {
        this.level = level;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // JPEG.CAM Orange Color
        int orange = Color.rgb(227, 69, 20);

        // 1. Draw Border (Orange)
        paint.setColor(orange);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(2, 2, w - 6, h - 2, paint); 
        
        // 2. Draw Nub (Orange)
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(w - 6, h / 2 - 3, w - 1, h / 2 + 3, paint);

        // 3. Draw Segments (Orange, unless critically low)
        // --- FIXED: Changed Color.WHITE to orange ---
        int barColor = (level < 15) ? Color.RED : orange;
        paint.setColor(barColor);
        
        float fillW = (w - 11);
        float segW = fillW / 3.0f;

        if (level > 10) canvas.drawRect(5, 5, 5 + segW - 2, h - 5, paint);
        if (level > 40) canvas.drawRect(5 + segW + 1, 5, 5 + (segW * 2) - 2, h - 5, paint);
        if (level > 70) canvas.drawRect(5 + (segW * 2) + 1, 5, w - 9, h - 5, paint);
    }
}