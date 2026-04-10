package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.view.View;

/**
 * JPEG.CAM UI: Pro AF Reticle
 * Handles the visual brackets and color states for the autofocus system.
 */
public class ProReticleView extends View {
    private Paint paint;
    public static final int STATE_IDLE = 0;
    public static final int STATE_SEARCHING = 1;
    public static final int STATE_LOCKED = 2;
    public static final int STATE_FAILED = 3;
    private int fallbackState = STATE_IDLE;
    private boolean isPolling = false;

    public ProReticleView(Context context) {
        super(context);
        paint = new Paint(); 
        paint.setStyle(Paint.Style.STROKE); 
        paint.setStrokeWidth(6); 
        paint.setAntiAlias(true);
    }
    
    public boolean isPolling() { return isPolling; }

    public void startFocus(Camera cam) {
        if (cam == null) return;
        try {
            if ("manual".equals(cam.getParameters().getFocusMode())) return;
            fallbackState = STATE_SEARCHING;
            cam.autoFocus(new Camera.AutoFocusCallback() {
                @Override public void onAutoFocus(boolean success, Camera camera) { 
                    fallbackState = success ? STATE_LOCKED : STATE_FAILED; 
                    invalidate(); 
                }
            });
            isPolling = true; 
            invalidate();
        } catch (Exception e) {}
    }

    public void stopFocus(Camera cam) {
        isPolling = false; 
        fallbackState = STATE_IDLE; 
        invalidate();
        if (cam != null) { 
            try { 
                if (!"manual".equals(cam.getParameters().getFocusMode())) cam.cancelAutoFocus(); 
            } catch (Exception e) {} 
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isPolling) return;
        
        switch (fallbackState) {
            case STATE_IDLE:      paint.setColor(Color.argb(100, 255, 255, 255)); break;
            case STATE_SEARCHING: paint.setColor(Color.YELLOW); break;
            case STATE_LOCKED:    paint.setColor(Color.GREEN); break;
            case STATE_FAILED:    paint.setColor(Color.RED); break;
        }
        
        int cx = getWidth() / 2, cy = getHeight() / 2, size = 60, bracket = 15;
        
        // Draw AF Corners
        canvas.drawLine(cx-size, cy-size, cx-size+bracket, cy-size, paint);
        canvas.drawLine(cx-size, cy-size, cx-size, cy-size+bracket, paint);
        canvas.drawLine(cx+size, cy-size, cx+size-bracket, cy-size, paint);
        canvas.drawLine(cx+size, cy-size, cx+size, cy-size+bracket, paint);
        canvas.drawLine(cx-size, cy+size, cx-size+bracket, cy+size, paint); 
        canvas.drawLine(cx-size, cy+size, cx-size, cy+size-bracket, paint);
        canvas.drawLine(cx+size, cy+size, cx+size-bracket, cy+size, paint);
        canvas.drawLine(cx+size, cy+size, cx+size, cy+size-bracket, paint);
        
        // Draw Center dot
        paint.setStyle(Paint.Style.FILL); 
        canvas.drawCircle(cx, cy, 3, paint); 
        paint.setStyle(Paint.Style.STROKE);
        
        postInvalidateDelayed(50);
    }
}