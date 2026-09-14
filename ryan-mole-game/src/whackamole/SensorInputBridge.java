package whackamole;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small bridge that turns incoming sensor data into a point/cell the game can use.
 *
 * TRIANGULATION (trilateration) version. Physical rig, all in cm:
 *
 * <pre>
 * wall:  |----S1----------S2----|     S1 at x=25, S2 at x=75 (baseline 50)
 *        :      dead zone       :     0-50 cm out from the wall
 *        +----------+-----------+
 *        |  ROW 0   |   ROW 0   |     depth split into 3 rows away from
 *        +----------+-----------+     the wall. Row 0 = nearest the sensors
 *        |  ROW 1   |   ROW 1   |     = top row on screen. 2 columns x 3
 *        +----------+-----------+     rows = 6 boxes total.
 *        |  ROW 2   |   ROW 2   |
 *        +----------+-----------+
 *        COL 0        COL 1
 * </pre>
 *
 * Each sensor reading is the radius of a circle around that sensor. With the
 * known baseline between the sensors, the two circles intersect at exactly one
 * point in front of the wall:
 *
 *   xFromS1 = (r1^2 - r2^2 + B^2) / (2B)
 *   y       = sqrt(r1^2 - xFromS1^2)
 *
 * The triangulated x picks the column, the triangulated y picks the depth row.
 * This is the "calibrated spatial positioning" / triangulation requirement
 * made concrete: neither axis can be derived from a single sensor alone.
 *
 * If only one sensor echoes, we fall back to that sensor's half of the field
 * for the column (you can only be inside the beam of the sensor that sees
 * you), and approximate the row from that sensor's raw range as depth. This
 * is a coarser estimate than triangulated y (it ignores the small x offset),
 * but it keeps the game playable at the field edges where the beams don't
 * overlap.
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
    private static final double COLUMN_BOUNDARY_CM = TABLE_TEST ? 25.0 : 50.0;  // col 0 | col 1 split
    private static final double DEAD_ZONE_CM       = TABLE_TEST ? 25.0 : 50.0;  // empty strip at the sensors
    private static final double BOX_FAR_EDGE_CM    = TABLE_TEST ? 50.0 : 100.0; // far edge of the boxes

    /** 2 columns (left/right) x 3 depth rows (near/mid/far) = 6 boxes total. */
    private static final int NUM_COLS = 2;
    private static final int NUM_ROWS = 3;
    private static final double ROW_DEPTH_CM = (BOX_FAR_EDGE_CM - DEAD_ZONE_CM) / NUM_ROWS;

    // ---- filtering ----
    private static final double MIN_RANGE_CM       = TABLE_TEST ? 20.0 : 45.0;  // closer = noise / dead-zone object
    private static final double MAX_RANGE_CM       = TABLE_TEST ? 70.0 : 130.0; // longest legal slant range + margin
    private static final double HYSTERESIS_CM      = TABLE_TEST ? 3.0 : 5.0;    // sticky boundary, both axes
    private static final double FIELD_TOLERANCE_CM = TABLE_TEST ? 8.0 : 15.0;   // slack on field-edge validation

    private final int outputWidthPx;
    private final int outputHeightPx;

    /**
     * Column/row the player was last classified into (-1 = unknown), used for
     * hysteresis and read by the game's hit-check ping. volatile because the
     * UDP reader thread writes these (via parseLine/triangulate) while the
     * Swing timer thread reads them.
     */
    private volatile int currentColumn = -1;
    private volatile int currentRow = -1;

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

    /** Column the player is currently classified into (0..NUM_COLS-1), or -1 if no fix yet. */
    public int getCurrentColumn() {
        return currentColumn;
    }

    /** Depth row the player is currently classified into (0..NUM_ROWS-1, 0 = nearest), or -1 if no fix yet. */
    public int getCurrentRow() {
        return currentRow;
    }

    /** Combined index (row * NUM_COLS + col), matching GamePanel's mole list ordering. -1 if no fix. */
    public int getCurrentCellIndex() {
        if (currentColumn < 0 || currentRow < 0) {
            return -1;
        }
        return currentRow * NUM_COLS + currentColumn;
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

        int col = classifyColumn(x);
        int row = classifyRow(y);
        lastFixDescription = String.format("fix: x=%.0fcm y=%.0fcm (triangulated) -> Col %d Row %d",
                x, y, col, row);
        return mapCellToBoard(col, row);
    }

    /**
     * Only one sensor sees the player: the player must be inside that
     * sensor's beam, i.e. on that sensor's side of the field. Depth row is
     * approximated from that sensor's raw range (only exact when the player
     * is directly in front of it, since range is a slant distance).
     */
    private Point singleEcho(int sensorIndex, double range) {
        int col = sensorIndex; // S1 -> col 0 (left), S2 -> col 1 (right)
        currentColumn = col;
        int row = classifyRow(range);
        lastFixDescription = String.format("fix: S%d only, r=%.0fcm -> Col %d Row %d",
                sensorIndex + 1, range, col, row);
        return mapCellToBoard(col, row);
    }

    /** Classifies x into a column with a sticky boundary so small jitter near the split doesn't flicker. */
    private int classifyColumn(double x) {
        int col = (x < COLUMN_BOUNDARY_CM) ? 0 : 1;
        if (currentColumn >= 0 && col != currentColumn
                && Math.abs(x - COLUMN_BOUNDARY_CM) < HYSTERESIS_CM) {
            col = currentColumn;
        }
        currentColumn = col;
        return col;
    }

    /** Classifies depth y into a row (0 = nearest the sensors) with the same sticky-boundary treatment. */
    private int classifyRow(double y) {
        double depthIntoField = y - DEAD_ZONE_CM;
        int row = clampInt((int) Math.floor(depthIntoField / ROW_DEPTH_CM), 0, NUM_ROWS - 1);

        if (currentRow >= 0 && row != currentRow) {
            double nearestBoundary = Math.round(depthIntoField / ROW_DEPTH_CM) * ROW_DEPTH_CM;
            if (Math.abs(depthIntoField - nearestBoundary) < HYSTERESIS_CM) {
                row = currentRow;
            }
        }
        currentRow = row;
        return row;
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

    /** Maps a (column, row) cell to the pixel centre of that cell. Row 0 renders at the top. */
    private Point mapCellToBoard(int col, int row) {
        int cellWidth = outputWidthPx / NUM_COLS;
        int cellHeight = outputHeightPx / NUM_ROWS;
        int x = clampInt(col * cellWidth + cellWidth / 2, 0, outputWidthPx - 1);
        int y = clampInt(row * cellHeight + cellHeight / 2, 0, outputHeightPx - 1);
        return new Point(x, y);
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void notifyListeners(Point point) {
        for (PositionListener listener : new ArrayList<>(listeners)) {
            listener.onPositionChanged(new Point(point));
        }
    }
}
