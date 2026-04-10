package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * JPEG.CAM UI: 2.35:1 Anamorphic Cinema Matte
 * Handles the black bars for cinematic framing.
 */
public class CinemaMatteView extends View {
    private Paint mattePaint;

    public CinemaMatteView(Context context) {
        super(context);
        mattePaint = new Paint();
        mattePaint.setColor(Color.BLACK);
        mattePaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        
        // Calculate the height of the active 2.35:1 area
        int imgW = w;
        int imgH = (int) (w * (2.0f / 3.0f));
        if (imgH > h) {
            imgH = h;
            imgW = (int) (h * (3.0f / 2.0f));
        }
        
        int targetHeight = (int) (imgW / 2.35f);
        int topBarBottom = (h - targetHeight) / 2;
        int bottomBarTop = (h + targetHeight) / 2;

        // Draw top and bottom mattes
        canvas.drawRect(0, 0, w, topBarBottom, mattePaint);
        canvas.drawRect(0, bottomBarTop, w, h, mattePaint);
    }
}