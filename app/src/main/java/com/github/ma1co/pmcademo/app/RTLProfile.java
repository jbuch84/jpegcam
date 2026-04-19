package com.github.ma1co.pmcademo.app;

public class RTLProfile {
    public String profileName;

    // Look / Base (Software Engine)
    public int lutIndex = 0;
    public int opacity = 100;
    public int grain = 0;
    public int grainSize = 1; // 0=Small, 1=Med, 2=Large
    public int rollOff = 0;
    public int vignette = 0;


    // Color & Tone (Hardware)
    public String whiteBalance = "AUTO";
    public int wbShift = 0;
    public int wbShiftGM = 0;
    public String dro = "OFF";
    public int contrast = 0;
    public int saturation = 0;
    public int sharpness = 0;
    public String colorMode = "standard"; // Creative Styles

    // PHASE 3: HIDDEN HARDWARE COLOR MATRIX (-7 to +7)
    public int colorDepthRed = 0;
    public int colorDepthGreen = 0;
    public int colorDepthBlue = 0;
    public int colorDepthCyan = 0;
    public int colorDepthMagenta = 0;
    public int colorDepthYellow = 0;

    // -- ADVANCED MATRIX TOGGLE --
    public int[] advMatrix = {100, 0, 0,  0, 100, 0,  0, 0, 100};

    // PHASE 5: EFFECTS & HACKS
    public String proColorMode = "off";
    public String pictureEffect = "off";
    public String peToyCameraTone = "normal";
    public int vignetteHardware = 0;
    public int softFocusLevel = 1;
    public int shadingRed = 0;
    public int shadingBlue = 0;
    public int sharpnessGain = 0;

    // --- Fuji-Style Post-Processing Effects ---
    // 0 = OFF | 1 = WEAK | 2 = STRONG
    public int colorChrome = 0;
    public int chromeBlue = 0;

    // --- Analog Physics Controls ---
    public int shadowToe = 0;
    public int subtractiveSat = 0;
    public int halation = 0;

    // --- Optical Bloom & Soft Glow ---
    // Simulates the physical scattering of light through film layers.
    // 0 = OFF | 1 = STANDARD | 2 = RICH
    public int bloom = 0;

    public RTLProfile(int slotIndex) {
        this.profileName = "RECIPE " + (slotIndex + 1);
    }

    public RTLProfile() {
        this.profileName = "RECIPE";
    }
}
