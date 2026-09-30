package whackamole;

import java.util.Random;

/**
 * Plain-Java checks for the N-sensor solver (no JUnit needed).
 *   javac -d out src/whackamole/*.java test/whackamole/*.java
 *   java -cp out whackamole.MultilaterationTest
 */
public class MultilaterationTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.printf("%s  %s  %s%n", ok ? "PASS" : "FAIL", name, detail);
    }

    static double[] exactRanges(double x, double y) {
        int n = SensorInputBridge.SENSOR_X_CM.length;
        double[] r = new double[n];
        for (int i = 0; i < n; i++)
            r[i] = Math.hypot(x - SensorInputBridge.SENSOR_X_CM[i], y - SensorInputBridge.SENSOR_Y_CM[i]);
        return r;
    }

    public static void main(String[] args) {
        // 1. exact 4-sensor ranges across the whole field
        double worst = 0;
        for (double x = 0; x <= 150; x += 5)
            for (double y = 55; y <= 200; y += 5) {
                SensorInputBridge b = new SensorInputBridge();
                SensorInputBridge.Fix f = b.acceptRanges(exactRanges(x, y), 0);
                worst = Math.max(worst, f == null ? 1e9 : Math.hypot(f.xCm - x, f.yCm - y));
            }
        check("exact 4-sensor fix over whole field", worst < 0.01, String.format("worst error %.4f cm", worst));

        // 2. parse Ryan's new packet
        double[] p = SensorInputBridge.parseRanges("Seq:12|Time:3456|S1:80.2 cm|S2:No echo|S3:101.0 cm|S4:95.5 cm");
        check("parse hub packet", p != null && p[0] == 80.2 && Double.isNaN(p[1]) && p[2] == 101.0 && p[3] == 95.5,
                java.util.Arrays.toString(p));

        // 3. parse legacy 2-sensor packet
        p = SensorInputBridge.parseRanges("Sensor 1: 45.0 cm Sensor 2: No echo");
        check("parse legacy packet", p != null && p[0] == 45.0 && Double.isNaN(p[1]) && Double.isNaN(p[2]),
                java.util.Arrays.toString(p));
        check("garbage packet rejected", SensorInputBridge.parseRanges("hello") == null, "");

        // 4. end-to-end through accept() with the real string format
        SensorInputBridge b = new SensorInputBridge();
        double[] r = exactRanges(100, 120);
        SensorInputBridge.Fix f = b.accept(String.format("Seq:1|Time:1|S1:%.1f cm|S2:%.1f cm|S3:%.1f cm|S4:%.1f cm",
                r[0], r[1], r[2], r[3]));
        check("end-to-end packet -> fix", f != null && Math.hypot(f.xCm - 100, f.yCm - 120) < 0.3,
                f == null ? "null" : String.format("(%.2f, %.2f)", f.xCm, f.yCm));

        // 5. crosstalk outlier on S3 is dropped
        b = new SensorInputBridge();
        r = exactRanges(60, 140);
        r[2] += 45;
        f = b.acceptRanges(r, 0);
        check("outlier range dropped", f != null && f.outliersDropped == 1 && Math.hypot(f.xCm - 60, f.yCm - 140) < 0.5,
                f == null ? "null" : String.format("(%.2f, %.2f), dropped %d", f.xCm, f.yCm, f.outliersDropped));

        // 6. only cross-box pair (S1 + S4) still solves
        b = new SensorInputBridge();
        r = exactRanges(90, 110);
        r[1] = Double.NaN; r[2] = Double.NaN;
        f = b.acceptRanges(r, 0);
        check("S1 + S4 only", f != null && Math.hypot(f.xCm - 90, f.yCm - 110) < 0.01,
                f == null ? "null" : String.format("(%.2f, %.2f)", f.xCm, f.yCm));

        // 7. single echo -> flagged rough fix straight in front of the sensor
        b = new SensorInputBridge();
        f = b.acceptRanges(new double[]{Double.NaN, Double.NaN, 120, Double.NaN}, 0);
        // S3 aims 40 deg left, so 120 cm along that line is about (62.9, 91.9)
        check("single echo along aim line", f != null && f.kind == SensorInputBridge.Kind.SINGLE_ECHO
                        && Math.abs(f.xCm - 62.87) < 0.1 && Math.abs(f.yCm - 91.93) < 0.1,
                f == null ? "null" : f.kind + String.format(" (%.0f, %.0f)", f.xCm, f.yCm));

        // 8. no echoes -> no fix
        b = new SensorInputBridge();
        check("all no echo -> null", b.acceptRanges(new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN}, 0) == null,
                b.getLastFixDescription());

        // 9. proximity alarm: raw range under 50 cm fires even without a clean fix
        b = new SensorInputBridge();
        b.acceptRanges(new double[]{30, Double.NaN, Double.NaN, Double.NaN}, 0);
        check("alarm on raw range 30 cm", b.isInDeadZone(), "");
        b = new SensorInputBridge();
        b.acceptRanges(exactRanges(75, 40), 0);
        check("alarm when solved y = 40", b.isInDeadZone() && b.getCurrentRow() == -1, "no box scored while too close");
        b = new SensorInputBridge();
        b.acceptRanges(exactRanges(75, 150), 0);
        check("no alarm at y = 150", !b.isInDeadZone(), "");

        // 10. every box centre classifies correctly
        int ok = 0;
        double colW = 150.0 / SensorInputBridge.NUM_COLS, rowD = 150.0 / SensorInputBridge.NUM_ROWS;
        for (int row = 0; row < SensorInputBridge.NUM_ROWS; row++)
            for (int col = 0; col < SensorInputBridge.NUM_COLS; col++) {
                b = new SensorInputBridge();
                b.acceptRanges(exactRanges(colW * (col + 0.5), 50 + rowD * (row + 0.5)), 0);
                if (b.getCurrentColumn() == col && b.getCurrentRow() == row) ok++;
            }
        int cells = SensorInputBridge.NUM_COLS * SensorInputBridge.NUM_ROWS;
        check("all box centres classify", ok == cells, ok + "/" + cells);

        // 11. hysteresis: jitter across the column line doesn't flicker
        b = new SensorInputBridge();
        b.acceptRanges(exactRanges(45, 120), 0);
        int c0 = b.getCurrentColumn();
        b.acceptRanges(exactRanges(53, 120), 0);
        check("hysteresis holds column 3 cm past the line", b.getCurrentColumn() == c0, "col " + b.getCurrentColumn());
        b.acceptRanges(exactRanges(60, 120), 0);
        check("switches once clearly across", b.getCurrentColumn() != c0, "col " + b.getCurrentColumn());

        // 12. beam model: coverage of the field with the recommended aims
        int both = 0, any = 0, total = 0;
        for (double x = 0; x <= 150; x += 5)
            for (double y = 50; y <= 200; y += 5) {
                boolean left = SensorSimulator.inBeam(0, x, y) || SensorSimulator.inBeam(1, x, y);
                boolean right = SensorSimulator.inBeam(2, x, y) || SensorSimulator.inBeam(3, x, y);
                total++;
                if (left && right) both++;
                if (left || right) any++;
            }
        check("beam coverage with 15/40 deg aims", any * 100 / total >= 95,
                String.format("both boxes see %d%%, at least one sensor sees %d%%", both * 100 / total, any * 100 / total));

        // 13. jump gate: one glitch is dropped, a real move is accepted on the second packet
        b = new SensorInputBridge();
        b.acceptRanges(exactRanges(40, 100), 1000);
        SensorInputBridge.Fix g1 = b.acceptRanges(exactRanges(120, 180), 1180);
        SensorInputBridge.Fix g2 = b.acceptRanges(exactRanges(42, 102), 1360);
        check("single-packet teleport rejected", g1 == null && g2 != null, "");
        b.acceptRanges(exactRanges(120, 180), 1540);
        SensorInputBridge.Fix g3 = b.acceptRanges(exactRanges(118, 178), 1720);
        check("confirmed big move accepted", g3 != null && Math.abs(g3.xCm - 118) < 0.1, "");

        // 14. simulator packets round-trip through the parser
        SensorSimulator sim = new SensorSimulator(s -> {});
        check("simulator packet parses", SensorInputBridge.parseRanges(sim.buildPacket(75, 120, 0)) != null, sim.buildPacket(75, 120, 0));

        System.out.printf("%n%d passed, %d failed%n%n", pass, fail);

        // ---- Monte Carlo: how much does sensor choice matter? ----
        System.out.println("Monte Carlo, sigma_r = 0.5 cm, 4000 trials per point (sigma_x / sigma_y in cm)");
        System.out.println("point        S1+S2 (10cm)   S1+S3 (140cm)   all four");
        Random rnd = new Random(42);
        double[][] pts = {{75, 125}, {30, 80}, {120, 180}, {75, 195}};
        for (double[] pt : pts) {
            StringBuilder line = new StringBuilder(String.format("(%3.0f,%3.0f)", pt[0], pt[1]));
            int[][] sets = {{0, 1}, {0, 2}, {0, 1, 2, 3}};
            for (int[] set : sets) {
                double sx = 0, sy = 0; int n = 0;
                for (int k = 0; k < 4000; k++) {
                    double[] rr = exactRanges(pt[0], pt[1]);
                    double[] use = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
                    for (int s : set) use[s] = rr[s] + rnd.nextGaussian() * 0.5;
                    SensorInputBridge.Fix ff = new SensorInputBridge().solve(use, 0);
                    if (ff == null) continue;
                    sx += (ff.xCm - pt[0]) * (ff.xCm - pt[0]);
                    sy += (ff.yCm - pt[1]) * (ff.yCm - pt[1]);
                    n++;
                }
                line.append(String.format("   %5.1f / %4.1f  ", Math.sqrt(sx / n), Math.sqrt(sy / n)));
                if (n < 3900) line.append("(" + (4000 - n) + " no-fix)");
            }
            System.out.println(line);
        }
        if (fail > 0) System.exit(1);
    }
}
