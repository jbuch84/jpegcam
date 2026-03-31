package com.github.ma1co.pmcademo.app;

import android.view.KeyEvent;
import android.util.Log;
import com.sony.scalar.sysutil.ScalarInput;

/**
 * JPEG.CAM Manager: Input Mapping
 * Translates raw Sony hardware scan codes into application commands.
 */
public class InputManager {

    public interface InputListener {
        void onShutterHalfPressed();
        void onShutterHalfReleased();
        void onDeletePressed();
        void onMenuPressed();
        void onEnterPressed();
        void onUpPressed();
        void onDownPressed();
        void onLeftPressed();
        void onRightPressed();
        
        // --- 3-DIAL SETUP RESTORED ---
        void onFrontDialRotated(int direction);
        void onRearDialRotated(int direction);
        void onControlWheelRotated(int direction);
    }

    private InputListener listener;

    public InputManager(InputListener listener) {
        this.listener = listener;
    }

    /**
     * Processes key down events from the Sony hardware.
     */
    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        
        // --- S1 SHUTTER (HALF-PRESS) ---
        if (sc == ScalarInput.ISV_KEY_S1_1 && event.getRepeatCount() == 0) {
            listener.onShutterHalfPressed();
            return true;
        }

        // --- CORE NAVIGATION ---
        if (sc == ScalarInput.ISV_KEY_DELETE || keyCode == KeyEvent.KEYCODE_DEL) {
            listener.onDeletePressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_MENU || keyCode == KeyEvent.KEYCODE_MENU) {
            listener.onMenuPressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_ENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            listener.onEnterPressed();
            return true;
        }

        // --- DIAL ROTATION (Catches Front, Rear, Kuru/Wheel, and Lens Rings) ---
        // Evaluated BEFORE directional pads to catch a6500 leakage!
        
        // 1. FRONT DIAL (INDEX FINGER)
        if (sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) { listener.onFrontDialRotated(1); return true; }
        if (sc == ScalarInput.ISV_DIAL_1_COUNTERCW) { listener.onFrontDialRotated(-1); return true; }

        // 2. REAR DIAL (THUMB)
        if (sc == ScalarInput.ISV_DIAL_2_CLOCKWISE) { listener.onRearDialRotated(1); return true; }
        if (sc == ScalarInput.ISV_DIAL_2_COUNTERCW) { listener.onRearDialRotated(-1); return true; }

        // 3. REAR CONTROL WHEEL & LENS RINGS
        if (sc == ScalarInput.ISV_DIAL_3_CLOCKWISE || 
            sc == ScalarInput.ISV_DIAL_KURU_CLOCKWISE || 
            sc == ScalarInput.ISV_RING_CLOCKWISE ||
            sc == ScalarInput.ISV_RING_LENS_APERTURE_CLOCKWISE) {
            listener.onControlWheelRotated(1);
            return true;
        }
        if (sc == ScalarInput.ISV_DIAL_3_COUNTERCW || 
            sc == ScalarInput.ISV_DIAL_KURU_COUNTERCW || 
            sc == ScalarInput.ISV_RING_COUNTERCW ||
            sc == ScalarInput.ISV_RING_LENS_APERTURE_COUNTERCW) {
            listener.onControlWheelRotated(-1);
            return true;
        }

        // --- D-PAD DIRECTIONS ---
        if (sc == ScalarInput.ISV_KEY_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            listener.onUpPressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            listener.onDownPressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            listener.onLeftPressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            listener.onRightPressed();
            return true;
        }

        // --- PREVENT OS CRASHES FROM MODE DIAL ---
        if (sc == ScalarInput.ISV_KEY_MODE_DIAL || 
           (sc >= ScalarInput.ISV_KEY_MODE_INVALID && sc <= ScalarInput.ISV_KEY_MODE_CUSTOM3) || 
            sc == 624) {
            return true;
        }

        // --- DIAGNOSTIC CATCH-ALL FOR THE a6500 ---
        Log.w("JPEG.CAM", "UNMAPPED DIAL EVENT -> Code: " + keyCode + " | Scan: " + sc);

        return false;
    }

    /**
     * Processes key up events.
     */
    public boolean handleKeyUp(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        if (sc == ScalarInput.ISV_KEY_S1_1) {
            listener.onShutterHalfReleased();
            return true;
        }
        
        if (sc == ScalarInput.ISV_KEY_MODE_DIAL || 
           (sc >= ScalarInput.ISV_KEY_MODE_INVALID && sc <= ScalarInput.ISV_KEY_MODE_CUSTOM3) || 
            sc == 624) {
            return true;
        }
        
        return false;
    }
}