package whackamole;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small bridge that turns incoming sensor data into a point the game can use.
 *
 * For the MVP, this keeps the mapping simple and visible: only the Y sensor
 * reading is used to choose a row, while the cursor stays in one fixed column.
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

    private static final double INTERVAL_CM = 30.0;
    private static final int GRID_SIZE = 3;
    private static final int FIXED_COLUMN = 2; // column C

    private final int outputWidthPx;
    private final int outputHeightPx;

    public interface PositionListener {
        void onPositionChanged(Point point);
    }

    private final List<PositionListener> listeners = new ArrayList<>();
    private final Point currentPoint = new Point(-1, -1);

    public SensorInputBridge() {
        this(380, 380, 60.0);
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

    public Point parseLine(String line) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();

        Matcher yPrefixMatcher = Y_PREFIX_PATTERN.matcher(trimmed);
        if (yPrefixMatcher.find()) {
            Double distance = parseDistance(yPrefixMatcher.group(1));
            if (distance == null) {
                return null;
            }
            return mapToBoard(distance);
        }

        Matcher directPositionMatcher = POSITION_PATTERN.matcher(trimmed);
        if (directPositionMatcher.find()) {
            return parsePoint(directPositionMatcher.group(1), directPositionMatcher.group(2));
        }

        Matcher yOnlyMatcher = Y_ONLY_PATTERN.matcher(trimmed);
        if (yOnlyMatcher.find()) {
            Double distance = parseDistance(yOnlyMatcher.group(1));
            if (distance == null) {
                return null;
            }
            return mapToBoard(distance);
        }

        Matcher sensorMatcher = SENSOR_PATTERN.matcher(trimmed);
        if (!sensorMatcher.find()) {
            return null;
        }

        Point sensorPoint = parseSensorDistances(sensorMatcher.group(1), sensorMatcher.group(2));
        return sensorPoint;
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

    private Point parseSensorDistances(String raw1, String raw2) {
        if ("No echo".equalsIgnoreCase(raw1) || "No echo".equalsIgnoreCase(raw2)) {
            return null;
        }

        try {
            double distance2 = Double.parseDouble(raw2);
            return mapToBoard(distance2);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Double parseDistance(String rawValue) {
        try {
            return Double.parseDouble(rawValue);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Point mapToBoard(double yDistanceCm) {
        int row = bucket(yDistanceCm);

        int cellWidth = outputWidthPx / GRID_SIZE;
        int cellHeight = outputHeightPx / GRID_SIZE;

        int x = clamp(FIXED_COLUMN * cellWidth + cellWidth / 2, 0, outputWidthPx - 1);
        int y = clamp(row * cellHeight + cellHeight / 2, 0, outputHeightPx - 1);
        return new Point(x, y);
    }

    private int bucket(double valueCm) {
        int bucket = (int) Math.floor(valueCm / INTERVAL_CM);
        return clamp(bucket, 0, GRID_SIZE - 1);
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
