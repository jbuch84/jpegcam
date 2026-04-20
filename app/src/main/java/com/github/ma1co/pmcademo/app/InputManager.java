package com.github.ma1co.pmcademo.app;

import android.view.KeyEvent;
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
        boolean onUpPressed();
        boolean onDownPressed();
        boolean onLeftPressed();
        boolean onRightPressed();
        boolean onCustomButtonPressed(String keyId);
        
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
     * Maps scan codes to listener methods.
     */
    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();

        // --- TEMPORARY DEBUG TRACKER ---
        android.util.Log.d("JPEG.CAM", "Hardware Button Pressed! KeyCode: " + keyCode + " | ScanCode: " + sc);
        
        // --- S1 SHUTTER (HALF-PRESS) ---
        if (sc == ScalarInput.ISV_KEY_S1_1 && event.getRepeatCount() == 0) {
            listener.onShutterHalfPressed();
            return false; // <-- CHANGED: Let the Sony OS process the native AF/AE Lock!
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
        if (sc == ScalarInput.ISV_KEY_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            return listener.onUpPressed();
        }
        if (sc == ScalarInput.ISV_KEY_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            return listener.onDownPressed();
        }
        if (sc == ScalarInput.ISV_KEY_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return listener.onLeftPressed();
        }
        if (sc == ScalarInput.ISV_KEY_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return listener.onRightPressed();
        }

        // --- APP-SPECIFIC CUSTOM BUTTON ROUTER ---
        if (sc == ScalarInput.ISV_KEY_CUSTOM1) { if (listener.onCustomButtonPressed("C1")) return true; }
        if (sc == ScalarInput.ISV_KEY_CUSTOM2) { if (listener.onCustomButtonPressed("C2")) return true; }
        if (sc == ScalarInput.ISV_KEY_CUSTOM3) { if (listener.onCustomButtonPressed("C3")) return true; }
        if (sc == ScalarInput.ISV_KEY_AEL)     { if (listener.onCustomButtonPressed("AEL")) return true; }
        if (sc == ScalarInput.ISV_KEY_FN)      { if (listener.onCustomButtonPressed("FN")) return true; }

        // --- UNIFIED DIAL TRANSLATOR (Front, Rear, Control Wheel & a6500 Hacks) ---
        if (sc == ScalarInput.ISV_DIAL_1_CLOCKWISE || 
            sc == ScalarInput.ISV_DIAL_2_CLOCKWISE || 
            sc == ScalarInput.ISV_DIAL_3_CLOCKWISE || 
            sc == ScalarInput.ISV_DIAL_KURU_CLOCKWISE || 
            sc == ScalarInput.ISV_RING_CLOCKWISE ||
            keyCode == 212 || sc == 212) {
            
            listener.onControlWheelRotated(1);
            return true;
        }

        if (sc == ScalarInput.ISV_DIAL_1_COUNTERCW || 
            sc == ScalarInput.ISV_DIAL_2_COUNTERCW || 
            sc == ScalarInput.ISV_DIAL_3_COUNTERCW || 
            sc == ScalarInput.ISV_DIAL_KURU_COUNTERCW || 
            sc == ScalarInput.ISV_RING_COUNTERCW ||
            keyCode == 80 || sc == 80) {
            
            listener.onControlWheelRotated(-1);
            return true;
        }

        // --- PREVENT OS CRASHES FROM MODE DIAL ---
        if (sc == ScalarInput.ISV_KEY_MODE_DIAL || 
           (sc >= ScalarInput.ISV_KEY_MODE_INVALID && sc <= ScalarInput.ISV_KEY_MODE_CUSTOM3) || 
            sc == 624) {
            return true;
        }

        return false; // Tells the Sony OS: "We didn't use this button, you take it!"
    }

    /**
     * Processes key up events.
     */
    public boolean handleKeyUp(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        if (sc == ScalarInput.ISV_KEY_S1_1) {
            listener.onShutterHalfReleased();
            return false; // <-- CHANGED: Let the Sony OS know the shutter was released
        }

        // Swallow custom button releases so the Sony OS ignores them
        if (sc == ScalarInput.ISV_KEY_CUSTOM1 || sc == ScalarInput.ISV_KEY_CUSTOM2 || 
            sc == ScalarInput.ISV_KEY_CUSTOM3 || sc == ScalarInput.ISV_KEY_AEL || sc == ScalarInput.ISV_KEY_FN) {
            return true; 
        }
        
        // Ensure Mode Dial releases are also swallowed safely
        if (sc == ScalarInput.ISV_KEY_MODE_DIAL || 
           (sc >= ScalarInput.ISV_KEY_MODE_INVALID && sc <= ScalarInput.ISV_KEY_MODE_CUSTOM3) || 
            sc == 624) {
            return true;
        }

        // Swallow the fake a6500 Key Up events
        if (keyCode == 80 || keyCode == 212) {
            return true;
        }
        
        return false;
    }
}