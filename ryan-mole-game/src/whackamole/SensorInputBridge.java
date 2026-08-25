package whackamole;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small bridge that turns incoming sensor data into a point the game can use.
 *
 * TRIANGULATION (trilateration) version. Physical rig, all in cm:
 *
 * <pre>
 * wall:  |----S1----------S2----|     S1 at x=25, S2 at x=75 (baseline 50)
 *        :      dead zone       :     0-50 cm out from the wall
 *        +----------+-----------+
 *        |  BOX 1   |   BOX 2   |     both boxes 50-100 cm deep,
 *        | (x 0-50) | (x 50-100)|     side by side across a 100 cm field
 *        +----------+-----------+
 * </pre>
 *
 * Each sensor reading is the radius of a circle around that sensor. With the
 * known baseline between the sensors, the two circles intersect at exactly one
 * point in front of the wall:
 *
 *   xFromS1 = (r1^2 - r2^2 + B^2) / (2B)
 *   y       = sqrt(r1^2 - xFromS1^2)
 *
 * The triangulated x picks the column (box). This is the "calibrated spatial
 * positioning" / triangulation requirement made concrete: the horizontal
 * position cannot be derived from either sensor alone, only from fusing both.
 *
 * If only one sensor echoes, we fall back to that sensor's half of the field
 * (you can only be inside the beam of the sensor that sees you), so the game
 * stays playable at the field edges where the beams don't overlap.
 *
 * The diagram above shows the full floor-scale numbers; the TABLE_TEST flag
 * below switches every dimension to a 1:2 scale tabletop rig (50 cm field,
 * 25 cm dead zone, boxes at 25-50 cm) for the Assessment 1 mini demo.
 */
public class SensorInputBridge {

    private static final Pattern POSITION_PATTERN = Pattern.compile(
            "X\\s*[:=]\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*(?:,|\\s+)Y\\s*[:=]\\s*([-+]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE
    );
        private static final Pattern Y_ONLY_PATTERN = Pattern.compile(
            "Y\\s*[:=]\\s*([-+]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE
        );
            private static final Pattern Y_PREFIX_PATTERN = Pattern.compile(
                "^y\\s*[:=]\\s*([-+]?\\d+(?:\\.\\d+)?)$",
                Pattern.CASE_INSENSITIVE
            );
    private static final Pattern SENSOR_PATTERN = Pattern.compile(
            "Sensor 1:\\s*(No echo|[-+]?\\d+(?:\\.\\d+)?)\\s*(?:cm)?\\s*Sensor 2:\\s*(No echo|[-+]?\\d+(?:\\.\\d+)?)\\s*(?:cm)?",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * TABLE_TEST = true selects the half-scale tabletop rig for the Assessment 1
     * mini demo (track a hand or a bottle instead of a person). Set it to false
     * to get the full floor-scale geometry back. Same maths either way.
     */
    private static final boolean TABLE_TEST = true;

    // ---- physical rig geometry (cm) — calibrate these to the real build ----
    //                                                    table  : floor
    private static final double SENSOR_1_X_CM      = TABLE_TEST ? 12.5 : 25.0;
    private static final double SENSOR_2_X_CM      = TABLE_TEST ? 37.5 : 75.0;
    private static final double BASELINE_CM = SENSOR_2_X_CM - SENSOR_1_X_CM;
    private static final double FIELD_WIDTH_CM     = TABLE_TEST ? 50.0 : 100.0;
    private static final double COLUMN_BOUNDARY_CM = TABLE_TEST ? 25.0 : 50.0;  // box 1 | box 2 split
    private static final double DEAD_ZONE_CM       = TABLE_TEST ? 25.0 : 50.0;  // empty strip at the sensors
    private static final double BOX_FAR_EDGE_CM    = TABLE_TEST ? 50.0 : 100.0; // far edge of the boxes
    private static final int NUM_BOXES = 2;

    // ---- filtering ----
    private static final double MIN_RANGE_CM       = TABLE_TEST ? 20.0 : 45.0;  // closer = noise / dead-zone object
    private static final double MAX_RANGE_CM       = TABLE_TEST ? 70.0 : 130.0; // longest legal slant range + margin
    private static final double HYSTERESIS_CM      = TABLE_TEST ? 3.0 : 5.0;    // sticky column boundary (in x)
    private static final double FIELD_TOLERANCE_CM = TABLE_TEST ? 8.0 : 15.0;   // slack on field-edge validation

    private final int outputWidthPx;
    private final int outputHeightPx;

    /** Box the player was last classified into (-1 = unknown), used for hysteresis. */
    private int currentBox = -1;

    /** Human-readable description of the latest position fix, for the on-screen demo readout. */
    private String lastFixDescription = "fix: --";

    public interface PositionListener {
        void onPositionChanged(Point point);
    }

    private final List<PositionListener> listeners = new ArrayList<>();
    private final Point currentPoint = new Point(-1, -1);

    public SensorInputBridge() {
        this(380, 380, BASELINE_CM);
    }

    public SensorInputBridge(int outputWidthPx, int outputHeightPx, double sensorSeparationCm) {
        this.outputWidthPx = outputWidthPx;
        this.outputHeightPx = outputHeightPx;
    }

    public void addPositionListener(PositionListener listener) {
        listeners.add(listener);
    }

    public void updateFromLine(String line) {
        Point point = parseLine(line);
        if (point != null) {
            currentPoint.setLocation(point);
            notifyListeners(point);
        }
    }

    public void updatePosition(int x, int y) {
        Point point = new Point(x, y);
        currentPoint.setLocation(point);
        notifyListeners(point);
    }

    public Point getCurrentPoint() {
        return new Point(currentPoint);
    }

    public String getLastFixDescription() {
        return lastFixDescription;
    }

    public Point parseLine(String line) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();

        Matcher yPrefixMatcher = Y_PREFIX_PATTERN.matcher(trimmed);
        if (yPrefixMatcher.find()) {
            lastFixDescription = "fix: legacy packet (no x info)";
            return null;
        }

        Matcher directPositionMatcher = POSITION_PATTERN.matcher(trimmed);
        if (directPositionMatcher.find()) {
            return parsePoint(directPositionMatcher.group(1), directPositionMatcher.group(2));
        }

        Matcher sensorMatcher = SENSOR_PATTERN.matcher(trimmed);
        if (!sensorMatcher.find()) {
            Matcher yOnlyMatcher = Y_ONLY_PATTERN.matcher(trimmed);
            if (yOnlyMatcher.find()) {
                lastFixDescription = "fix: legacy packet (no x info)";
            }
            return null;
        }

        return parseSensorDistances(sensorMatcher.group(1), sensorMatcher.group(2));
    }

    private static Point parsePoint(String rawX, String rawY) {
        try {
            int x = (int) Math.round(Double.parseDouble(rawX));
            int y = (int) Math.round(Double.parseDouble(rawY));
            return new Point(x, y);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Two valid ranges -> triangulate. One valid range -> that sensor's half
     * of the field. None -> no update (cursor stays put).
     */
    private Point parseSensorDistances(String raw1, String raw2) {
        Double r1 = validRange(parseOptionalDistance(raw1));
        Double r2 = validRange(parseOptionalDistance(raw2));

        if (r1 != null && r2 != null) {
            return triangulate(r1, r2);
        }
        if (r1 != null) {
            return singleEcho(0, r1);
        }
        if (r2 != null) {
            return singleEcho(1, r2);
        }
        lastFixDescription = "fix: no echo";
        return null;
    }

    /**
     * Intersect the two range circles. The wall makes the solution unique:
     * of the two mathematical intersections, only the one in front of the
     * wall (y > 0) is physically possible.
     */
    private Point triangulate(double r1, double r2) {
        double xFromS1 = (r1 * r1 - r2 * r2 + BASELINE_CM * BASELINE_CM) / (2.0 * BASELINE_CM);
        double ySquared = r1 * r1 - xFromS1 * xFromS1;

        if (ySquared <= 0) {
            // circles don't intersect (noisy pair) — don't move the cursor
            lastFixDescription = "fix: degenerate pair, ignored";
            return null;
        }

        double x = SENSOR_1_X_CM + xFromS1;
        double y = Math.sqrt(ySquared);

        boolean inField = x >= -FIELD_TOLERANCE_CM
                && x <= FIELD_WIDTH_CM + FIELD_TOLERANCE_CM
                && y >= DEAD_ZONE_CM - FIELD_TOLERANCE_CM
                && y <= BOX_FAR_EDGE_CM + FIELD_TOLERANCE_CM;
        if (!inField) {
            lastFixDescription = String.format("fix: x=%.0f y=%.0f (outside field, ignored)", x, y);
            return null;
        }

        int box = (x < COLUMN_BOUNDARY_CM) ? 0 : 1;
        // sticky boundary: inside the +/- hysteresis band, keep the current box
        if (currentBox >= 0 && box != currentBox
                && Math.abs(x - COLUMN_BOUNDARY_CM) < HYSTERESIS_CM) {
            box = currentBox;
        }
        currentBox = box;
        lastFixDescription = String.format("fix: x=%.0fcm y=%.0fcm (triangulated) -> Box %d", x, y, box + 1);
        return mapBoxToBoard(box);
    }

    /**
     * Only one sensor sees the player: the player must be inside that
     * sensor's beam, i.e. on that sensor's side of the field.
     */
    private Point singleEcho(int sensorIndex, double range) {
        int box = sensorIndex; // S1 -> box 1 (left), S2 -> box 2 (right)
        currentBox = box;
        lastFixDescription = String.format("fix: S%d only, r=%.0fcm -> Box %d", sensorIndex + 1, range, box + 1);
        return mapBoxToBoard(box);
    }

    /** Returns the range if it's inside the plausible window, else null. */
    private Double validRange(Double rangeCm) {
        if (rangeCm == null || rangeCm < MIN_RANGE_CM || rangeCm > MAX_RANGE_CM) {
            return null;
        }
        return rangeCm;
    }

    /** Returns the parsed distance, or null for "No echo" / invalid values. */
    private Double parseOptionalDistance(String rawValue) {
        if (rawValue == null || "No echo".equalsIgnoreCase(rawValue.trim())) {
            return null;
        }
        try {
            return Double.parseDouble(rawValue);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** The board is one row of NUM_BOXES cells: box index picks the cell. */
    private Point mapBoxToBoard(int box) {
        int cellWidth = outputWidthPx / NUM_BOXES;
        int x = clamp(box * cellWidth + cellWidth / 2, 0, outputWidthPx - 1);
        int y = outputHeightPx / 2;
        return new Point(x, y);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void notifyListeners(Point point) {
        for (PositionListener listener : new ArrayList<>(listeners)) {
            listener.onPositionChanged(new Point(point));
        }
    }
}
