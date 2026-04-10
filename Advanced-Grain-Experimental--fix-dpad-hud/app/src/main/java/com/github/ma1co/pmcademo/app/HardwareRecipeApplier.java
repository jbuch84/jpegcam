package com.github.ma1co.pmcademo.app;

import android.hardware.Camera;
import android.util.Log;

/**
 * JPEG.CAM Engine: Hardware Recipe Applier
 *
 * Responsible for translating a loaded RTLProfile into live Sony camera
 * hardware parameters via the Camera.Parameters API.
 *
 * Extracted from MainActivity as part of the God Class decomposition.
 * All Sony-specific parameter strings are intentionally centralised here
 * so future camera compatibility work has a single place to edit.
 *
 * Usage: HardwareRecipeApplier.apply(camera, profile);
 */
public class HardwareRecipeApplier {

    private static final String TAG = "JPEG.CAM";

    /**
     * Applies all settings from the given RTLProfile to the Sony camera hardware.
     *
     * Two-stage commit strategy:
     *   Stage 1 — Scene mode, picture effects, vignette (must commit first so
     *              Stage 2 reads a clean parameter state).
     *   Stage 2 — White balance, color matrix, DRO, tone, lens corrections.
     *
     * @param c    Active Camera instance from SonyCameraManager. Must not be null.
     * @param prof The RTLProfile to apply. Must not be null.
     */
    public static void apply(Camera c, RTLProfile prof) {
        if (c == null || prof == null) return;

        Camera.Parameters p = c.getParameters();

        // ----------------------------------------------------------------
        // STAGE 1: DRIVE MODE, SCENE, PICTURE EFFECTS, VIGNETTE
        // ----------------------------------------------------------------

        // Force Single-Shot drive mode — burst triggers RAM crash on BIONZ X.
        if (p.get("drive-mode") != null) p.set("drive-mode", "single");
        if (p.get("picture-profile") != null) p.set("picture-profile", "off");

        String safeProMode   = prof.proColorMode != null ? prof.proColorMode.toLowerCase() : "off";
        String safeColorMode = prof.colorMode    != null ? prof.colorMode.toLowerCase()    : "standard";

        if (!"off".equals(safeProMode)) {
            if (p.get("creative-style") != null) p.set("creative-style", "standard");
            if (p.get("color-mode")     != null) p.set("color-mode",     "standard");
            if (p.get("pro-color-mode") != null) p.set("pro-color-mode", safeProMode);
        } else {
            if (p.get("creative-style") != null) p.set("creative-style", safeColorMode);
            if (p.get("color-mode")     != null) p.set("color-mode",     safeColorMode);
            if (p.get("pro-color-mode") != null) p.set("pro-color-mode", "off");
        }

        if (p.get("picture-effect") != null) {
            String eff    = prof.pictureEffect   != null ? prof.pictureEffect.toLowerCase()   : "off";
            String effStr = prof.peToyCameraTone != null ? prof.peToyCameraTone.toLowerCase() : "normal";
            p.set("picture-effect", eff);

            if ("toy-camera".equals(eff)) {
                if (!effStr.equals("normal") && !effStr.equals("cool") && !effStr.equals("warm")
                        && !effStr.equals("green") && !effStr.equals("magenta")) effStr = "normal";
                if (p.get("pe-toy-camera-effect") != null) p.set("pe-toy-camera-effect", effStr);
                if (p.get("pe-toy-camera-tuning") != null) p.set("pe-toy-camera-tuning", String.valueOf(prof.vignetteHardware));

            } else if ("soft-focus".equals(eff) || "hdr-art".equals(eff)
                    || "illust".equals(eff) || "watercolor".equals(eff)) {
                String val = String.valueOf(Math.max(1, Math.min(3, prof.softFocusLevel)));
                if      ("soft-focus".equals(eff)  && p.get("pe-soft-focus-effect-level") != null) p.set("pe-soft-focus-effect-level", val);
                else if ("hdr-art".equals(eff)     && p.get("pe-hdr-art-effect-level")    != null) p.set("pe-hdr-art-effect-level",    val);
                else if ("illust".equals(eff)      && p.get("pe-illust-effect-level")     != null) p.set("pe-illust-effect-level",     val);
                else if ("watercolor".equals(eff)  && p.get("pe-watercolor-effect-level") != null) p.set("pe-watercolor-effect-level", val);

            } else if ("part-color".equals(eff)) {
                if (!effStr.equals("red") && !effStr.equals("green")
                        && !effStr.equals("blue") && !effStr.equals("yellow")) effStr = "red";
                if (p.get("pe-part-color-effect") != null) p.set("pe-part-color-effect", effStr);

            } else if ("miniature".equals(eff)) {
                if (!effStr.equals("auto") && !effStr.equals("left") && !effStr.equals("vcenter")
                        && !effStr.equals("right") && !effStr.equals("upper")
                        && !effStr.equals("hcenter") && !effStr.equals("lower")) effStr = "auto";
                if (p.get("pe-miniature-focus-area") != null) p.set("pe-miniature-focus-area", effStr);
            }
        }

        if (p.get("vignetting") != null) p.set("vignetting", String.valueOf(prof.vignetteHardware));
        if (p.get("vignette")   != null) p.set("vignette",   String.valueOf(prof.vignetteHardware));

        try { c.setParameters(p); } catch (Exception e) { Log.e(TAG, "Stage 1 Reject: " + e.getMessage()); }

        // ----------------------------------------------------------------
        // STAGE 2: WHITE BALANCE, COLOR, DRO, TONE, MATRIX, LENS
        // ----------------------------------------------------------------
        p = c.getParameters();

        // Kelvin White Balance — full 2500K–9900K range via "color-temp" key.
        // Note: Sony typo "color-temperture" is intentional — matches hardware key.
        String wb     = "auto";
        String profWb = prof.whiteBalance != null ? prof.whiteBalance : "Auto";
        if      (profWb.endsWith("K"))    { wb = "color-temp"; p.set("color-temperture-white-balance", profWb.replace("K", "")); }
        else if ("DAY".equals(profWb))    wb = "daylight";
        else if ("SHD".equals(profWb))    wb = "shade";
        else if ("CLD".equals(profWb))    wb = "cloudy-daylight";
        else if ("INC".equals(profWb))    wb = "incandescent";
        else if ("FLR".equals(profWb))    wb = "fluorescent";
        p.setWhiteBalance(wb);

        // WB Shift — A/B axis (lb) and G/M axis (cc). G/M is inverted for hardware.
        if (p.get("white-balance-shift-mode") != null) p.set("white-balance-shift-mode", (prof.wbShift != 0 || prof.wbShiftGM != 0) ? "true" : "false");
        if (p.get("white-balance-shift-lb")   != null) p.set("white-balance-shift-lb",   String.valueOf(prof.wbShift));
        if (p.get("white-balance-shift-cc")   != null) p.set("white-balance-shift-cc",   String.valueOf(-prof.wbShiftGM));

        // Tone controls
        if (p.get("contrast")            != null) p.set("contrast",            String.valueOf(prof.contrast));
        if (p.get("saturation")          != null) p.set("saturation",          String.valueOf(prof.saturation));
        if (p.get("sharpness")           != null) p.set("sharpness",           String.valueOf(prof.sharpness));
        if (p.get("sharpness-gain")      != null) p.set("sharpness-gain",      String.valueOf(prof.sharpnessGain));
        if (p.get("sharpness-gain-mode") != null) p.set("sharpness-gain-mode", "true");

        // Dynamic Range Optimizer
        if (p.get("dro-mode") != null) {
            String dro = prof.dro != null ? prof.dro.toUpperCase() : "OFF";
            if      ("OFF".equals(dro))        p.set("dro-mode", "off");
            else if ("AUTO".equals(dro))       p.set("dro-mode", "auto");
            else if (dro.startsWith("LVL")) {
                p.set("dro-mode", "on");
                if (p.get("dro-level") != null) p.set("dro-level", dro.replace("LVL", "").trim());
            }
        }

        // 6-Axis Color Depth
        if (p.get("color-depth-red") != null) {
            p.set("color-depth-red",     String.valueOf(prof.colorDepthRed));
            p.set("color-depth-green",   String.valueOf(prof.colorDepthGreen));
            p.set("color-depth-blue",    String.valueOf(prof.colorDepthBlue));
            p.set("color-depth-cyan",    String.valueOf(prof.colorDepthCyan));
            p.set("color-depth-magenta", String.valueOf(prof.colorDepthMagenta));
            p.set("color-depth-yellow",  String.valueOf(prof.colorDepthYellow));
        }

        // BIONZ RGB Color Matrix — 100% percentage scale → 1024 hardware units.
        if (p.get("rgb-matrix-mode") != null) {
            p.set("rgb-matrix-mode", "true");
            float mult = 10.24f;
            String mStr = String.format("%d,%d,%d,%d,%d,%d,%d,%d,%d",
                Math.round(prof.advMatrix[0] * mult), Math.round(prof.advMatrix[1] * mult), Math.round(prof.advMatrix[2] * mult),
                Math.round(prof.advMatrix[3] * mult), Math.round(prof.advMatrix[4] * mult), Math.round(prof.advMatrix[5] * mult),
                Math.round(prof.advMatrix[6] * mult), Math.round(prof.advMatrix[7] * mult), Math.round(prof.advMatrix[8] * mult));
            p.set("rgb-matrix", mStr);
        }

        // Lens Shading Correction
        if (p.get("lens-correction")                     != null) p.set("lens-correction",                     "true");
        if (p.get("lens-correction-shading-color-red")   != null) p.set("lens-correction-shading-color-red",   String.valueOf(prof.shadingRed));
        if (p.get("lens-correction-shading-color-blue")  != null) p.set("lens-correction-shading-color-blue",  String.valueOf(prof.shadingBlue));

        try { c.setParameters(p); } catch (Exception e) { Log.e(TAG, "Stage 2 Reject: " + e.getMessage()); }
    }
}
