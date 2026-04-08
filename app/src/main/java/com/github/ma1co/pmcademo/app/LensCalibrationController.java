package com.github.ma1co.pmcademo.app;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * JPEG.CAM Controller: Lens Calibration State Machine
 *
 * Owns all state and logic for the interactive lens focus-mapping workflow.
 * Extracted from MainActivity as part of the God Class decomposition.
 *
 * The calibration flow has two top-level states:
 *   WAITING  — user chose ENTER on DIAL_MODE_FOCUS; prompt shows options
 *   ACTIVE   — user confirmed DOWN; step-by-step point capture is underway
 *
 * Steps within ACTIVE state:
 *   0  — Set focal length (manual lens detected)
 *   10 — Set max aperture
 *   1  — Log minimum focus point
 *   2  — Log additional points until user presses UP to finish
 *
 * All UI rendering is handled internally via the prompt TextView passed at
 * construction. Completion and cancellation are signalled via HostCallback.
 */
public class LensCalibrationController {

    // -----------------------------------------------------------------------
    // Host callback — MainActivity provides hardware context and receives
    // completion/cancellation signals without being directly referenced.
    // -----------------------------------------------------------------------
    public interface HostCallback {
        LensProfileManager getLensManager();
        boolean isNativeLensAttached();
        float getHardwareFocalLength();
        float getCircleOfConfusion();
        float getCachedFocusRatio();
        /** Called when calibration finishes successfully. Filename is the saved lens profile. */
        void onCalibrationComplete(String lensFilename);
        /** Called when calibration is cancelled via RIGHT button. */
        void onCalibrationCancelled();
    }

    // -----------------------------------------------------------------------
    // Owned state — none of these live in MainActivity after this refactor
    // -----------------------------------------------------------------------
    private boolean isCalibrating         = false;
    private boolean waitingForChoice      = false;
    private final List<LensProfileManager.CalPoint> tempCalPoints = new ArrayList<>();
    private int   calibStep               = 0;
    private float minDistanceInput        = 0.3f;
    private String detectedLensName       = "Manual Lens";
    private float detectedFocalLength     = 50.0f;
    private float detectedMaxAperture     = 2.8f;

    private final TextView     prompt;
    private final HostCallback host;

    public LensCalibrationController(TextView calibPrompt, HostCallback host) {
        this.prompt = calibPrompt;
        this.host   = host;
    }

    // -----------------------------------------------------------------------
    // Public state queries — used by MainActivity for HUD and focusMeter logic
    // -----------------------------------------------------------------------
    /** True if either calibration or the initial profile-choice prompt is active. */
    public boolean isActive()        { return isCalibrating || waitingForChoice; }
    public boolean isCalibrating()   { return isCalibrating; }
    public boolean isWaiting()       { return waitingForChoice; }

    // Read-only getters for updateMainHUD() and onFocusPositionChanged()
    public float  getDetectedFocalLength()              { return detectedFocalLength; }
    public List<LensProfileManager.CalPoint> getTempCalPoints() { return tempCalPoints; }

    // -----------------------------------------------------------------------
    // Entry point — called from onEnterPressed when DIAL_MODE_FOCUS + manual
    // -----------------------------------------------------------------------
    /**
     * Shows the "LENS MAPPING" choice prompt. The HUD hide and focus meter
     * show are handled by the caller (MainActivity) so they stay in one place.
     */
    public void beginWaiting() {
        waitingForChoice = true;
        if (prompt == null) return;
        LensProfileManager lm = host.getLensManager();
        boolean canAppend = host.isNativeLensAttached()
                && lm.hasActiveProfile()
                && !lm.isCurrentProfileManual();
        prompt.setVisibility(View.VISIBLE);
        if (canAppend) {
            prompt.setText("LENS MAPPING\n\n[DOWN] Map Attached Lens\n[LEFT] Append Points\n[RIGHT] Cancel");
        } else {
            prompt.setText("LENS MAPPING\n\n[DOWN] Map Attached Lens\n[RIGHT] Cancel");
        }
    }

    // -----------------------------------------------------------------------
    // Input handlers — return true if the event was consumed
    // -----------------------------------------------------------------------

    /** ENTER: advance the calibration state machine one step. */
    public boolean handleEnter() {
        if (!isActive()) return false;
        LensProfileManager lm = host.getLensManager();

        if (isCalibrating && calibStep == 0) {
            calibStep = 10;
            updateUI();
            return true;
        }

        if (isCalibrating && calibStep == 10) {
            if (!host.isNativeLensAttached()) {
                // Manual lens — generate a synthetic profile from geometry
                tempCalPoints.clear();
                tempCalPoints.addAll(lm.generateManualDummyProfile(
                        detectedFocalLength, detectedMaxAperture, host.getCircleOfConfusion()));
                lm.saveProfileToFile(detectedFocalLength, detectedMaxAperture, tempCalPoints, true);
                String filename = LensProfileManager.generateFilename(detectedFocalLength, detectedMaxAperture, true);
                lm.loadProfileFromFile(filename);
                finish(filename);
            } else {
                calibStep = 1;
                minDistanceInput = 0.3f;
                updateUI();
            }
            return true;
        }

        if (isCalibrating && host.isNativeLensAttached()) {
            tempCalPoints.add(new LensProfileManager.CalPoint(host.getCachedFocusRatio(), minDistanceInput));
            if (calibStep == 1) calibStep = 2;
            updateUI();
            return true;
        }
        return false;
    }

    /** UP: finish the active calibration by adding an infinity point and saving. */
    public boolean handleUp() {
        if (!isCalibrating) return false;
        if (calibStep == 2) {
            LensProfileManager lm = host.getLensManager();
            tempCalPoints.add(new LensProfileManager.CalPoint(1.0f, 999.0f));
            lm.saveProfileToFile(detectedFocalLength, detectedMaxAperture, tempCalPoints, false);
            String filename = LensProfileManager.generateFilename(detectedFocalLength, detectedMaxAperture, false);
            lm.loadProfileFromFile(filename);
            finish(filename);
            return true;
        }
        return false;
    }

    /** DOWN: start active calibration from the waiting/choice state. */
    public boolean handleDown() {
        if (!waitingForChoice) return false;
        waitingForChoice = false;
        isCalibrating = true;
        tempCalPoints.clear();
        if (host.isNativeLensAttached()) {
            detectedLensName      = "Electronic Lens";
            detectedFocalLength   = host.getHardwareFocalLength() > 0f ? host.getHardwareFocalLength() : 50.0f;
            detectedMaxAperture   = 2.8f;
            calibStep             = 10;
        } else {
            detectedLensName      = "Manual Lens";
            detectedFocalLength   = 50.0f;
            detectedMaxAperture   = 2.8f;
            calibStep             = 0;
        }
        updateUI();
        return true;
    }

    /** LEFT: adjust focal length (step 0), aperture (step 10), or enter append mode (waiting). */
    public boolean handleLeft() {
        if (!isActive()) return false;
        if (isCalibrating && calibStep == 0) {
            detectedFocalLength = Math.max(10.0f, detectedFocalLength - 1.0f);
            updateUI();
            return true;
        }
        if (isCalibrating && calibStep == 10) {
            detectedMaxAperture = Math.max(1.0f, detectedMaxAperture - 0.1f);
            updateUI();
            return true;
        }
        if (waitingForChoice) {
            LensProfileManager lm = host.getLensManager();
            boolean canAppend = host.isNativeLensAttached()
                    && lm.hasActiveProfile() && !lm.isCurrentProfileManual();
            if (canAppend) {
                waitingForChoice  = false;
                isCalibrating     = true;
                tempCalPoints.clear();
                tempCalPoints.addAll(lm.getCurrentPoints());
                // Strip the infinity endpoint so we can append real points after it
                if (!tempCalPoints.isEmpty()
                        && tempCalPoints.get(tempCalPoints.size() - 1).ratio >= 0.99f) {
                    tempCalPoints.remove(tempCalPoints.size() - 1);
                }
                detectedLensName    = lm.getCurrentLensName();
                detectedFocalLength = lm.getCurrentFocalLength();
                detectedMaxAperture = lm.currentMaxAperture;
                calibStep           = 2;
                minDistanceInput    = lm.getDistanceForRatio(host.getCachedFocusRatio());
                if (minDistanceInput < 0) minDistanceInput = 1.0f;
                updateUI();
            }
            return true;
        }
        return false;
    }

    /** RIGHT: adjust focal length (step 0), aperture (step 10), or cancel entirely. */
    public boolean handleRight() {
        if (!isActive()) return false;
        if (isCalibrating && calibStep == 0) {
            detectedFocalLength = Math.min(600.0f, detectedFocalLength + 1.0f);
            updateUI();
            return true;
        }
        if (isCalibrating && calibStep == 10) {
            detectedMaxAperture = Math.min(22.0f, detectedMaxAperture + 0.1f);
            updateUI();
            return true;
        }
        // Cancel — RIGHT from any other state aborts the whole flow
        cancel();
        return true;
    }

    /**
     * DIAL: adjust the current minimum focus distance (steps 1 & 2 only).
     * Returns true if the dial turn was consumed.
     */
    public boolean handleDial(int direction) {
        if (!isCalibrating) return false;
        if (calibStep >= 1 && calibStep != 10) {
            minDistanceInput = Math.max(0.1f, minDistanceInput + (direction * 0.1f));
            updateUI();
            return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void finish(String lensFilename) {
        isCalibrating    = false;
        waitingForChoice = false;
        if (prompt != null) prompt.setVisibility(View.GONE);
        host.onCalibrationComplete(lensFilename);
    }

    private void cancel() {
        isCalibrating    = false;
        waitingForChoice = false;
        if (prompt != null) prompt.setVisibility(View.GONE);
        host.onCalibrationCancelled();
    }

    private void updateUI() {
        if (prompt == null || !isCalibrating) return;

        String distStr;
        if (minDistanceInput >= 999.0f) {
            distStr = "INFINITY";
        } else {
            float totalInches = minDistanceInput * 39.3701f;
            int ft = (int) (totalInches / 12);
            int in = (int) (totalInches % 12);
            distStr = String.format("%.2fm / %d'%d\"", minDistanceInput, ft, in);
        }

        String header      = "<font color='#FFFFFF'><b>[ MAPPING: " + detectedLensName + " | POINTS LOGGED: " + tempCalPoints.size() + " ]</b></font><br>";
        String wheelText   = "<font color='#00FFFF'><b>rear scroll wheel</b></font>";
        String sliderHtml  = "<font color='#E6320F'><big><b>\u25c4 " + distStr + " \u25ba</b></big></font>";
        String enterBtn    = "<font color='#00FF00'><b>[ENTER]</b></font>";
        String upBtn       = "<font color='#00FF00'><b>[UP]</b></font>";

        String instructions = "";
        if (calibStep == 0) {
            String mmSlider = "<font color='#E6320F'><big><b>\u25c4 " + (int) detectedFocalLength + "mm \u25ba</b></big></font>";
            instructions = "<font color='#FFFFFF'><small>STEP 0A: Lens Detected.</small><br>"
                    + "<small>Use [LEFT] / [RIGHT] to set Focal Length: </small> " + mmSlider + "<br>"
                    + "<small>Press " + enterBtn + " to confirm.</small></font>";
        } else if (calibStep == 10) {
            String apSlider = "<font color='#E6320F'><big><b>\u25c4 f/" + String.format("%.1f", detectedMaxAperture) + " \u25ba</b></big></font>";
            instructions = "<font color='#FFFFFF'><small>STEP 0B: Set Max Aperture for Lens ID.</small><br>"
                    + "<small>Use [LEFT] / [RIGHT] to set Max Aperture: </small> " + apSlider + "<br>"
                    + "<small>Press " + enterBtn + " to confirm.</small></font>";
        } else if (calibStep == 1) {
            instructions = "<font color='#FFFFFF'><small>STEP 1: Turn lens ring to hard stop (MIN FOCUS).</small><br>"
                    + "<small>Use " + wheelText + " to dial distance: </small> " + sliderHtml + "<br>"
                    + "<small>Press " + enterBtn + " to lock min point.</small></font>";
        } else if (calibStep == 2) {
            instructions = "<font color='#FFFFFF'><small>STEP 2: Focus on next object.</small><br>"
                    + "<small>Use " + wheelText + " to dial distance: </small> " + sliderHtml + "<br>"
                    + "<small>Press " + enterBtn + " to log point, or " + upBtn + " to Save &amp; Finish.</small></font>";
        }

        try {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) prompt.getLayoutParams();
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = 10;
            prompt.setLayoutParams(lp);
        } catch (Exception ignored) {}

        prompt.setBackgroundColor(Color.argb(210, 15, 15, 15));
        prompt.setPadding(25, 15, 25, 15);
        prompt.setVisibility(View.VISIBLE);
        prompt.setText(android.text.Html.fromHtml(header + instructions));
    }
}
