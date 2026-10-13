package whackamole;

/**
 * V1 baseline, reproduced on the V2 rig for the Assessment 2 comparison.
 *
 * This is the Sprint 1 solver exactly as demonstrated: two sensors only
 * (S1 and S2, the hub box), Saim's closed-form two-circle intersection,
 * depth anchored on r1, and NO filtering (no median, no smoothing, no
 * outlier rejection, no jump gate). S3 and S4 are ignored even if present.
 *
 *   x = (r1^2 - r2^2 + B^2) / 2B          (measured from S1)
 *   y = sqrt(r1^2 - x^2)
 *
 * Single echo: V1 fell back to "straight out from that sensor at that range".
 * Running this against the same packets as SensorInputBridge changes exactly
 * one variable (the processing), so any difference is attributable to it.
 */
public final class V1Solver {

    public static final class Fix {
        public final double xCm, yCm;
        public final boolean triangulated; // false = single-echo fallback
        Fix(double x, double y, boolean t) { xCm = x; yCm = y; triangulated = t; }
    }

    private static final double MIN_RANGE_CM = 5.0;
    private static final double MAX_RANGE_CM = 450.0;

    private V1Solver() {}

    private static boolean ok(double r) {
        return !Double.isNaN(r) && r >= MIN_RANGE_CM && r <= MAX_RANGE_CM;
    }

    /** @param ranges one per sensor, NaN = no echo. Only [0] and [1] are used. */
    public static Fix solve(double[] ranges) {
        double r1 = ranges.length > 0 ? ranges[0] : Double.NaN;
        double r2 = ranges.length > 1 ? ranges[1] : Double.NaN;
        double s1 = SensorInputBridge.SENSOR_X_CM[0];
        double s2 = SensorInputBridge.SENSOR_X_CM[1];
        double b = s2 - s1;

        if (ok(r1) && ok(r2)) {
            double xFromS1 = (r1 * r1 - r2 * r2 + b * b) / (2 * b);
            double ySq = r1 * r1 - xFromS1 * xFromS1;
            if (ySq < 0) return null; // circles don't intersect: V1 discarded the packet
            return new Fix(s1 + xFromS1, Math.sqrt(ySq), true);
        }
        if (ok(r1)) return new Fix(s1, r1, false);
        if (ok(r2)) return new Fix(s2, r2, false);
        return null;
    }
}
