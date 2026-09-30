package whackamole;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw ultrasonic ranges into a player position (x, y) in cm, then into
 * the (column, row) box the game scores against.
 *
 * V2: N-sensor multilateration, full scale (1.5 m x 1.5 m, 3 x 3 boxes). Works with Ryan's hub + node firmware
 * (4 sensors, packet "Seq:..|Time:..|S1:..|S2:..|S3:..|S4:..") and still
 * accepts the old 2-sensor "Sensor 1: .. Sensor 2: .." packet.
 *
 * Geometry and sensor placement: see the constants block below.
 *
 * How a fix is made:
 *  1. Every valid range is a circle around its sensor.
 *  2. Seed: intersect the two circles with the WIDEST baseline among the
 *     sensors that echoed (Saim's closed form, generalised to any pair and
 *     any sensor positions). Wide baseline matters: sideways error scales
 *     with range / baseline, so pairing S1 with S3 beats S1 with S2.
 *  3. Refine: least squares over every valid range (Gauss-Newton). With 3+
 *     ranges the extra ones average out noise instead of being thrown away.
 *  4. Outlier check: if one range disagrees with the rest by more than
 *     OUTLIER_RESIDUAL_CM (e.g. crosstalk, since the hub and node fire on
 *     independent clocks), drop it and solve again.
 *  5. One echo only: no geometry is possible, so fall back to "along that
 *     sensor's aim line at that range". Flagged as SINGLE on screen.
 */
public class SensorInputBridge {

    // ---------------------------------------------------------------------
    // Rig geometry, full scale (cm). Origin = the wall/screen line, level with
    // the LEFT edge of the playing area. x runs right along the wall, y runs
    // out into the room. MEASURE THE REAL TRANSDUCER CENTRES AND EDIT THESE.
    //
    //   wall  [S1 S2]==========(screen)==========[S3 S4]
    //         x=0 10                             140 150
    //          :        dead zone, y 0..50 (alarm)     :
    //          +---------+-----------+---------+  y = 50
    //          |  A1     |    B1     |    C1   |
    //          |  A2     |    B2     |    C2   |   1.5 m x 1.5 m
    //          |  A3     |    B3     |    C3   |   3 x 3 boxes, 50 cm each
    //          +---------+-----------+---------+  y = 200
    //
    // Index 0 = S1 ... index 3 = S4, matching the packet labels.
    // ---------------------------------------------------------------------
    public static final double[] SENSOR_X_CM = {0.0, 10.0, 140.0, 150.0};
    public static final double[] SENSOR_Y_CM = {0.0, 0.0, 0.0, 0.0};

    /**
     * Where each sensor points, in degrees from straight out into the room
     * (positive = turned toward +x, i.e. toward the right). Outer sensors
     * 15 deg in, inner sensors 40 deg in. With a ~15 deg half-angle beam this
     * lets both boxes see about 84% of the field and at least one sensor see
     * about 99%. The solver maths doesn't use these; they're only used for
     * the single-echo fallback and by the simulator.
     */
    public static final double[] SENSOR_AIM_DEG = {15.0, 40.0, -40.0, -15.0};
    public static final double BEAM_HALF_ANGLE_DEG = 15.0;

    /** Playing field in front of the sensors, in the same coordinates. */
    public static final double FIELD_MIN_X_CM = 0.0;
    public static final double FIELD_MAX_X_CM = 150.0;
    /** Brief: alarm when the player is within 50 cm of the screen. */
    public static final double DEAD_ZONE_CM = 50.0;
    /** 50 cm dead zone + 150 cm playing depth. */
    public static final double FIELD_FAR_CM = 200.0;

    /** 3 columns x 3 depth rows = 9 boxes of 50 x 50 cm. GamePanel reads these, so they stay in sync. */
    public static final int NUM_COLS = 3;
    public static final int NUM_ROWS = 3;

    // ---- filtering ----
    /** RCWL-1601 physical floor. Must be well under DEAD_ZONE_CM or the alarm can never fire. */
    private static final double MIN_RANGE_CM = 5.0;
    /** 30 ms echo timeout on the ESP32 = ~514 cm; anything past this is junk. */
    private static final double MAX_RANGE_CM = 450.0;
    /** Ranges that disagree with the others by more than this are treated as bad (crosstalk, stray echo). */
    private static final double OUTLIER_RESIDUAL_CM = 8.0;
    /**
     * Sensors range to the target's nearest surface, not its centre. 0 is right
     * for the bench proxy (bottle / flat hand). For a person, calibrate: stand on
     * a taped mark, compare solved y with the tape, adjust until they match.
     * Expect roughly 10 to 15 cm for a torso.
     */
    private static final double TARGET_RADIUS_CM = 0.0;
    private static final double HYSTERESIS_CM = 5.0;
    /** Max believable move between packets (~180 ms apart): 40 cm is about 2.2 m/s, a fast lunge. */
    private static final double MAX_STEP_CM = 40.0;
    private static final long GATE_RESET_MS = 1000;
    private static final double FIELD_TOLERANCE_CM = 30.0;

    public enum Kind { MULTILATERATED, SINGLE_ECHO }

    /** One position fix. Immutable so the UDP thread can hand it to the Swing thread safely. */
    public static final class Fix {
        public final double xCm, yCm;
        public final Kind kind;
        public final int sensorsUsed;      // how many ranges went into the solve
        public final int outliersDropped;
        public final double residualRmsCm; // how well the ranges agree (0 for 1-2 sensors)
        public final long seq;             // increments on every new fix
        public final long timeMs;

        Fix(double xCm, double yCm, Kind kind, int sensorsUsed, int outliersDropped,
            double residualRmsCm, long seq, long timeMs) {
            this.xCm = xCm; this.yCm = yCm; this.kind = kind;
            this.sensorsUsed = sensorsUsed; this.outliersDropped = outliersDropped;
            this.residualRmsCm = residualRmsCm; this.seq = seq; this.timeMs = timeMs;
        }
    }

    private static final Pattern NEW_FORMAT = Pattern.compile(
            "S(\\d)\\s*:\\s*(No echo|[-+]?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_FORMAT = Pattern.compile(
            "Sensor\\s*(\\d)\\s*:\\s*(No echo|[-+]?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    private volatile Fix latestFix = null;
    private volatile int currentColumn = -1;
    private volatile int currentRow = -1;
    private volatile boolean inDeadZone = false;
    private volatile String lastFixDescription = "fix: waiting...";
    private long fixCounter = 0;
    private Fix pendingJump = null;

    // ---------------------------------------------------------------------
    // public API
    // ---------------------------------------------------------------------

    /** Feed one UDP line. Returns the new fix, or null if this packet gave no usable position. */
    public synchronized Fix accept(String line) {
        double[] ranges = parseRanges(line);
        if (ranges == null) {
            lastFixDescription = "fix: unrecognised packet";
            return null;
        }
        return acceptRanges(ranges, System.currentTimeMillis());
    }

    /** Same as accept() but with ranges already parsed (NaN = no echo). Used by tests and sim mode. */
    public synchronized Fix acceptRanges(double[] ranges, long nowMs) {
        // Proximity alarm runs on RAW ranges, before any field filtering, so it
        // still fires when the player is too close for a clean fix.
        boolean close = false;
        for (double r : ranges) {
            if (isValid(r) && r < DEAD_ZONE_CM) close = true;
        }

        Fix fix = solve(ranges, nowMs);
        if (fix != null && fix.yCm < DEAD_ZONE_CM) close = true;
        inDeadZone = close;

        if (fix == null) return null;
        if (!passesJumpGate(fix)) return null;

        latestFix = fix;
        if (fix.yCm < DEAD_ZONE_CM) {
            // Too close to the screen: no box can be scored until they step back.
            currentColumn = -1;
            currentRow = -1;
        } else {
            currentColumn = classify(fix.xCm, FIELD_MIN_X_CM, FIELD_MAX_X_CM, NUM_COLS, currentColumn);
            currentRow = classify(fix.yCm, DEAD_ZONE_CM, FIELD_FAR_CM, NUM_ROWS, currentRow);
        }

        String cell = currentColumn < 0 ? "none" : "Col " + currentColumn + " Row " + currentRow;
        if (fix.kind == Kind.SINGLE_ECHO) {
            lastFixDescription = String.format("fix: x=%.0f y=%.0f (single echo, rough) -> %s",
                    fix.xCm, fix.yCm, cell);
        } else {
            lastFixDescription = String.format("fix: x=%.0f y=%.0f (%d sensors%s, rms %.1f) -> %s",
                    fix.xCm, fix.yCm, fix.sensorsUsed,
                    fix.outliersDropped > 0 ? ", " + fix.outliersDropped + " outlier dropped" : "",
                    fix.residualRmsCm, cell);
        }
        return fix;
    }

    /**
     * A person can't teleport. A fix that lands more than MAX_STEP_CM from the
     * last one is held back; if the NEXT fix agrees with it, the move was real
     * and it's accepted, otherwise it was a one-packet glitch and is dropped.
     * After GATE_RESET_MS with no accepted fix (player left and came back)
     * the gate opens again.
     */
    private boolean passesJumpGate(Fix fix) {
        Fix last = latestFix;
        if (last == null || fix.timeMs - last.timeMs > GATE_RESET_MS) {
            pendingJump = null;
            return true;
        }
        if (Math.hypot(fix.xCm - last.xCm, fix.yCm - last.yCm) <= MAX_STEP_CM) {
            pendingJump = null;
            return true;
        }
        Fix pending = pendingJump;
        if (pending != null && Math.hypot(fix.xCm - pending.xCm, fix.yCm - pending.yCm) <= MAX_STEP_CM) {
            pendingJump = null;
            return true; // two packets agree on the new spot: it's a real move
        }
        pendingJump = fix;
        lastFixDescription = String.format("fix: x=%.0f y=%.0f (sudden jump, waiting for confirmation)", fix.xCm, fix.yCm);
        return false;
    }

    public Fix getLatestFix() { return latestFix; }
    public int getCurrentColumn() { return currentColumn; }
    public int getCurrentRow() { return currentRow; }
    public boolean isInDeadZone() { return inDeadZone; }
    public String getLastFixDescription() { return lastFixDescription; }

    // ---------------------------------------------------------------------
    // parsing
    // ---------------------------------------------------------------------

    /** Returns one range per sensor (NaN = no echo / not reported), or null if the line has no sensor data. */
    static double[] parseRanges(String line) {
        if (line == null) return null;
        double[] out = new double[SENSOR_X_CM.length];
        java.util.Arrays.fill(out, Double.NaN);
        boolean found = false;

        for (Pattern p : new Pattern[]{NEW_FORMAT, LEGACY_FORMAT}) {
            Matcher m = p.matcher(line);
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1)) - 1;
                if (idx < 0 || idx >= out.length) continue;
                found = true;
                String raw = m.group(2).trim();
                if (!"No echo".equalsIgnoreCase(raw)) {
                    try {
                        out[idx] = Double.parseDouble(raw);
                    } catch (NumberFormatException ignored) {
                        // leave NaN
                    }
                }
            }
            if (found) break;
        }
        return found ? out : null;
    }

    // ---------------------------------------------------------------------
    // the solver
    // ---------------------------------------------------------------------

    private static boolean isValid(double r) {
        return !Double.isNaN(r) && r >= MIN_RANGE_CM && r <= MAX_RANGE_CM;
    }

    Fix solve(double[] rawRanges, long nowMs) {
        // Each sensor measures to the NEAREST SURFACE of the target, not its
        // centre. Adding the target's radius back puts every circle through
        // the centre. 0 for a bottle or flat hand; calibrate for a torso.
        double[] ranges = new double[rawRanges.length];
        for (int i = 0; i < rawRanges.length; i++) {
            ranges[i] = isValid(rawRanges[i]) ? rawRanges[i] + TARGET_RADIUS_CM : Double.NaN;
        }

        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < ranges.length && i < SENSOR_X_CM.length; i++) {
            if (!Double.isNaN(ranges[i])) valid.add(i);
        }

        if (valid.isEmpty()) {
            lastFixDescription = "fix: no echo";
            return null;
        }
        if (valid.size() == 1) {
            int s = valid.get(0);
            double aim = Math.toRadians(SENSOR_AIM_DEG[s]);
            return new Fix(SENSOR_X_CM[s] + ranges[s] * Math.sin(aim),
                    SENSOR_Y_CM[s] + ranges[s] * Math.cos(aim), Kind.SINGLE_ECHO,
                    1, 0, 0, ++fixCounter, nowMs);
        }

        Solution best = solveSet(valid, ranges);
        if (best == null) {
            lastFixDescription = "fix: ranges don't intersect, ignored";
            return null;
        }

        // Outlier rejection (needs 3+ ranges, since 2 circles always fit exactly).
        // Leave each sensor out in turn and keep the subset that agrees best.
        // When only 2 would remain, geometry can't say which pair is right, so
        // the tie-break is "closest to where the player just was".
        int dropped = 0;
        while (best.maxResidual > OUTLIER_RESIDUAL_CM && valid.size() >= 3) {
            List<Integer> bestSubset = null;
            Solution bestSub = null;
            double bestScore = Double.MAX_VALUE;
            for (int out : valid) {
                List<Integer> subset = new ArrayList<>(valid);
                subset.remove(Integer.valueOf(out));
                Solution c = solveSet(subset, ranges);
                if (c == null) continue;
                double excludedResidual = Math.abs(dist(c.x, c.y, out) - ranges[out]);
                if (excludedResidual <= OUTLIER_RESIDUAL_CM) continue; // it wasn't the odd one out
                if (c.maxResidual > OUTLIER_RESIDUAL_CM && subset.size() >= 3) continue;
                double score = subset.size() >= 3 ? c.rms : distanceFromLastFix(c);
                if (score < bestScore) {
                    bestScore = score;
                    bestSub = c;
                    bestSubset = subset;
                }
            }
            if (bestSub == null) break;
            best = bestSub;
            valid = bestSubset;
            dropped++;
        }

        if (best.maxResidual > OUTLIER_RESIDUAL_CM) {
            lastFixDescription = String.format("fix: ranges disagree by %.0f cm, ignored", best.maxResidual);
            return null;
        }

        boolean inField = best.x >= FIELD_MIN_X_CM - FIELD_TOLERANCE_CM
                && best.x <= FIELD_MAX_X_CM + FIELD_TOLERANCE_CM
                && best.y >= 0
                && best.y <= FIELD_FAR_CM + FIELD_TOLERANCE_CM;
        if (!inField) {
            lastFixDescription = String.format("fix: x=%.0f y=%.0f (outside field, ignored)", best.x, best.y);
            return null;
        }
        return new Fix(best.x, best.y, Kind.MULTILATERATED, valid.size(), dropped, best.rms, ++fixCounter, nowMs);
    }

    private record Solution(double x, double y, double rms, double maxResidual) {}

    /** Seed from the widest intersecting pair, refine by least squares, report how well the ranges agree. */
    private static Solution solveSet(List<Integer> set, double[] r) {
        double[] seed = seedFromWidestPair(set, r);
        if (seed == null) return null;
        double[] p = refine(seed, set, r);
        double sumSq = 0, max = 0;
        for (int s : set) {
            double res = Math.abs(dist(p[0], p[1], s) - r[s]);
            sumSq += res * res;
            max = Math.max(max, res);
        }
        return new Solution(p[0], p[1], Math.sqrt(sumSq / set.size()), max);
    }

    private double distanceFromLastFix(Solution c) {
        Fix last = latestFix;
        return last == null ? 0 : Math.hypot(c.x - last.xCm, c.y - last.yCm);
    }

    /**
     * Saim's two-circle intersection, generalised: any two sensors at any
     * positions. Tries pairs from widest baseline down and returns the first
     * that intersects, taking the solution in front of the sensors.
     */
    private static double[] seedFromWidestPair(List<Integer> valid, double[] r) {
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++)
            for (int j = i + 1; j < valid.size(); j++)
                pairs.add(new int[]{valid.get(i), valid.get(j)});
        pairs.sort((a, b) -> Double.compare(baseline(b[0], b[1]), baseline(a[0], a[1])));

        for (int[] pr : pairs) {
            double[] p = intersect(pr[0], r[pr[0]], pr[1], r[pr[1]]);
            if (p != null) return p;
        }
        return null;
    }

    static double[] intersect(int a, double ra, int b, double rb) {
        double dx = SENSOR_X_CM[b] - SENSOR_X_CM[a];
        double dy = SENSOR_Y_CM[b] - SENSOR_Y_CM[a];
        double d = Math.hypot(dx, dy);
        if (d < 1e-6) return null;
        double along = (ra * ra - rb * rb + d * d) / (2 * d);
        double hSq = ra * ra - along * along;
        if (hSq < 0) return null;
        double h = Math.sqrt(hSq);
        double px = SENSOR_X_CM[a] + along * dx / d;
        double py = SENSOR_Y_CM[a] + along * dy / d;
        double x1 = px - h * dy / d, y1 = py + h * dx / d;
        double x2 = px + h * dy / d, y2 = py - h * dx / d;
        // The screen/wall removes the mirror solution: keep the one in front.
        return (y1 >= y2) ? new double[]{x1, y1} : new double[]{x2, y2};
    }

    /** Gauss-Newton least squares over all valid ranges. With exactly 2 ranges this just returns the seed. */
    private static double[] refine(double[] seed, List<Integer> valid, double[] r) {
        double x = seed[0], y = seed[1];
        if (valid.size() < 3) return new double[]{x, y};

        for (int iter = 0; iter < 15; iter++) {
            double a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
            for (int s : valid) {
                double dx = x - SENSOR_X_CM[s], dy = y - SENSOR_Y_CM[s];
                double d = Math.max(1e-6, Math.hypot(dx, dy));
                double jx = dx / d, jy = dy / d;
                double res = r[s] - d;
                a11 += jx * jx; a12 += jx * jy; a22 += jy * jy;
                b1 += jx * res; b2 += jy * res;
            }
            double det = a11 * a22 - a12 * a12;
            if (Math.abs(det) < 1e-9) break;
            double stepX = (a22 * b1 - a12 * b2) / det;
            double stepY = (a11 * b2 - a12 * b1) / det;
            x += stepX;
            y += stepY;
            if (y < 0) y = -y; // stay in front of the sensor line
            if (Math.abs(stepX) + Math.abs(stepY) < 1e-4) break;
        }
        return new double[]{x, y};
    }

    private static double baseline(int a, int b) {
        return Math.hypot(SENSOR_X_CM[b] - SENSOR_X_CM[a], SENSOR_Y_CM[b] - SENSOR_Y_CM[a]);
    }

    private static double dist(double x, double y, int s) {
        return Math.hypot(x - SENSOR_X_CM[s], y - SENSOR_Y_CM[s]);
    }

    // ---------------------------------------------------------------------
    // box classification
    // ---------------------------------------------------------------------

    /** Splits [min, max] into n bands with a sticky boundary so jitter on a line doesn't flicker. */
    private static int classify(double v, double min, double max, int n, int previous) {
        double band = (max - min) / n;
        int idx = (int) Math.floor((v - min) / band);
        idx = Math.max(0, Math.min(n - 1, idx));
        if (previous >= 0 && Math.abs(idx - previous) == 1) {
            double nearestBoundary = min + Math.round((v - min) / band) * band;
            if (Math.abs(v - nearestBoundary) < HYSTERESIS_CM) idx = previous;
        }
        return idx;
    }
}
