package com.github.ma1co.pmcademo.app;

import java.util.List;

public class LensMath {

    // --- NEW: DYNAMIC SENSOR SIZE VARIABLE ---
    // This will be updated by the Focus Meter, which gets it from MainActivity's hardware check.
    private static double currentCoC = 0.020; // Default to APS-C

    public static void setCircleOfConfusion(double coc) {
        currentCoC = coc;
    }

    public static double getCircleOfConfusion() {
        return currentCoC;
    }

    public static class DiopterModel {
        public final double a;
        public final double b;
        public DiopterModel(double a, double b) { this.a = a; this.b = b; }
    }

    public static DiopterModel computeDiopterModel(List<LensProfileManager.CalPoint> points) {
        int n = points.size();
        if (n < 2) return null;
        
        double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumXX = 0.0;
        for (LensProfileManager.CalPoint p : points) {
            double x = p.ratio;
            // 1/Distance = Diopters. (Treat Infinity as 0.0 diopters)
            double y = (p.distance >= 999.0f) ? 0.0 : (1.0 / p.distance); 
            sumX += x; sumY += y; sumXY += x * y; sumXX += x * x;
        }
        
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0.0) return null;
        
        double a = (n * sumXY - sumX * sumY) / denom;
        double b = (sumY * sumXX - sumX * sumXY) / denom;
        return new DiopterModel(a, b);
    }

    public static Double motorToDistanceMeters(double motorRatio, DiopterModel model) {
        if (model == null) return null;
        double diopters = model.a * motorRatio + model.b;
        if (diopters <= 0.001) return 999.0; // Infinity threshold
        return 1.0 / diopters;
    }

    // Direct Algebraic Inverse: Instant translation from Meters to Motor Ratio!
    public static Double distanceToMotorRatio(double distanceMeters, DiopterModel model) {
        if (model == null || model.a == 0.0) return null;
        if (distanceMeters >= 999.0 || Double.isInfinite(distanceMeters)) return 1.0;
        
        double targetDiopters = 1.0 / distanceMeters;
        double ratio = (targetDiopters - model.b) / model.a;
        
        return Math.max(0.0, Math.min(1.0, ratio)); // Clamp to screen bounds
    }

    public static double hyperfocalMeters(double focalMm, double aperture, double cocMm) {
        double H_mm = (focalMm * focalMm) / (aperture * cocMm) + focalMm;
        return H_mm / 1000.0;
    }

    public static class DofBounds {
        public final double near;
        public final Double far;
        public DofBounds(double near, Double far) { this.near = near; this.far = far; }
    }

    public static DofBounds dofBounds(double focusDistanceM, double focalMm, double aperture, double cocMm) {
        if (focusDistanceM >= 999.0) return new DofBounds(hyperfocalMeters(focalMm, aperture, cocMm), null);
        double f = focalMm / 1000.0;
        double H = hyperfocalMeters(focalMm, aperture, cocMm);
        double s = focusDistanceM;
        
        double near = (H * s) / (H + (s - f));
        double far;
        if (H <= s) far = Double.POSITIVE_INFINITY;
        else far = (H * s) / (H - (s - f));
        
        return new DofBounds(near, Double.isInfinite(far) ? null : far);
    }

    public static class GaugeState {
        public final double focusDist;
        public final double hyperfocalDist;
        public final Double hyperMotor;
        public final double nearDist;
        public final Double farDist;
        public final Double nearMotor;
        public final Double farMotor;

        public GaugeState(double focusDist, double hyperfocalDist, Double hyperMotor, double nearDist, Double farDist, Double nearMotor, Double farMotor) {
            this.focusDist = focusDist; this.hyperfocalDist = hyperfocalDist; this.hyperMotor = hyperMotor;
            this.nearDist = nearDist; this.farDist = farDist; this.nearMotor = nearMotor; this.farMotor = farMotor;
        }
    }

    public static GaugeState buildGaugeState(double motorRatio, double aperture, double focalLengthMm, List<LensProfileManager.CalPoint> points) {
        DiopterModel model = computeDiopterModel(points);
        if (model == null) return null;

        Double focusDist = motorToDistanceMeters(motorRatio, model);
        if (focusDist == null) focusDist = 999.0;

        // --- FIXED: Now uses dynamic sensor size! ---
        double cocMm = getCircleOfConfusion(); 
        double H = hyperfocalMeters(focalLengthMm, aperture, cocMm);
        Double hyperMotor = distanceToMotorRatio(H, model);

        DofBounds dof = dofBounds(focusDist, focalLengthMm, aperture, cocMm);
        Double nearMotor = distanceToMotorRatio(dof.near, model);
        Double farMotor = (dof.far != null) ? distanceToMotorRatio(dof.far, model) : 1.0; 

        return new GaugeState(focusDist, H, hyperMotor, dof.near, dof.far, nearMotor, farMotor);
    }
}