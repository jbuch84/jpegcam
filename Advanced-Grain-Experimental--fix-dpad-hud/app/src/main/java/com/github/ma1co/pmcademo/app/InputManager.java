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
        void onUpPressed();
        void onDownPressed();
        void onLeftPressed();
        void onRightPressed();
        void onCustomButtonPressed();
        
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

        // --- UNIFIED CUSTOM BUTTON (The "Omni-Button") ---
        // Catches C1, C2, and AEL explicitly using raw hardware ScanCodes (622, 623, 638)
        // as well as the standard Sony Framework constants.
        if (sc == 622 || sc == 623 || sc == 638 || 
            sc == ScalarInput.ISV_KEY_CUSTOM1 || sc == ScalarInput.ISV_KEY_CUSTOM2 || 
            sc == ScalarInput.ISV_KEY_CUSTOM3 || sc == ScalarInput.ISV_KEY_AEL) {
            
            listener.onCustomButtonPressed();
            return true;
        }

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

        // Swallow custom button releases so native OS features don't trigger
        if (sc == 622 || sc == 623 || sc == 638 || 
            sc == ScalarInput.ISV_KEY_CUSTOM1 || sc == ScalarInput.ISV_KEY_CUSTOM2 || 
            sc == ScalarInput.ISV_KEY_CUSTOM3 || sc == ScalarInput.ISV_KEY_AEL) {
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