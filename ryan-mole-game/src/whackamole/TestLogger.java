package whackamole;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Assessment 2 physical test logger. Runs the V1 baseline solver and the V2
 * solver on the SAME packets, so every row is a paired comparison.
 *
 * Real rig (game must NOT be running, this binds UDP 4210):
 *   java -cp out whackamole.TestLogger --out v1v2_test.csv
 *   then stand on a taped X, type its label (A1..C3) and press Enter.
 *   Dead-zone check: type DZ25, DZ75 or DZ125 while standing 40 cm from the wall
 *   at x = 25, 75 or 125. Custom spot: type x,y in cm. q = quit.
 *
 * Simulation (predicted values, no hardware):
 *   java -cp out whackamole.TestLogger --sim --auto --out predicted.csv
 *
 * Grid: columns A,B,C left to right (x = 25, 75, 125), rows 1,2,3 from the
 * wall out (y = 75, 125, 175). Matches the taped field.
 */
public class TestLogger {

    static int packetsPerSpot = 40;      // ~7 s at the hub's ~176 ms cadence
    static final int SETTLE_PACKETS = 3; // discarded after each move
    static double simNoiseCm = 1.0;      // range noise for a clothed body (bench was 0.5)
    static double simNoEcho = 0.04;
    static double simCrosstalk = 0.03;
    static int simReps = 3;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        boolean sim = false, auto = false;
        String out = "v1v2_test.csv";
        long seed = 3000L;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sim" -> sim = true;
                case "--auto" -> auto = true;
                case "--out" -> out = args[++i];
                case "--packets" -> packetsPerSpot = Integer.parseInt(args[++i]);
                case "--noise" -> simNoiseCm = Double.parseDouble(args[++i]);
                case "--reps" -> simReps = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                default -> { System.err.println("unknown arg " + args[i]); return; }
            }
        }

        try (PrintWriter csv = new PrintWriter(new FileWriter(out))) {
            csv.println("source,trial,label,true_x,true_y,seq,S1,S2,S3,S4,"
                    + "v1_x,v1_y,v1_mode,v2_x,v2_y,v2_mode,v2_sensors,v2_alarm");
            if (sim) runSim(csv, auto, new Random(seed));
            else runReal(csv);
        }
        System.out.println("Saved " + out);
    }

    // ------------------------------------------------------------------ real rig

    static void runReal(PrintWriter csv) throws Exception {
        LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
        DatagramSocket socket = new DatagramSocket(4210);
        Thread rx = new Thread(() -> {
            byte[] buf = new byte[512];
            while (true) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    q.offer(new String(p.getData(), 0, p.getLength()).trim());
                } catch (Exception e) { return; }
            }
        }, "udp");
        rx.setDaemon(true);
        rx.start();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        int trial = 0;
        System.out.println("Listening on UDP 4210. Stand on a mark, type its label (A1..C3) or x,y and Enter. q to quit.");
        while (true) {
            System.out.print("> ");
            String line = in.readLine();
            if (line == null || line.trim().equalsIgnoreCase("q")) break;
            double[] truth = parseSpot(line.trim());
            if (truth == null) { System.out.println("  ? use A1..C3 or x,y"); continue; }
            String label = line.trim().toUpperCase();
            trial++;
            q.clear();
            SensorInputBridge v2 = new SensorInputBridge(true);
            int logged = 0, seen = 0;
            while (logged < packetsPerSpot) {
                String pkt = q.poll(3, TimeUnit.SECONDS);
                if (pkt == null) { System.out.println("  no packets for 3 s: check hotspot / PC_IP"); break; }
                double[] r = SensorInputBridge.parseRanges(pkt);
                if (r == null) continue;
                long seq = parseSeq(pkt);
                SensorInputBridge.Fix f2 = v2.acceptRanges(r, System.currentTimeMillis());
                if (++seen <= SETTLE_PACKETS) continue;
                writeRow(csv, "measured", trial, label, truth, seq, r, V1Solver.solve(r), f2, v2.isInDeadZone());
                logged++;
            }
            csv.flush();
            System.out.printf("  logged %d packets at %s%n", logged, label);
        }
        socket.close();
    }

    // ------------------------------------------------------------------ simulation

    static void runSim(PrintWriter csv, boolean auto, Random rnd) throws Exception {
        String[] cells = {"A1", "B1", "C1", "A2", "B2", "C2", "A3", "B3", "C3"};
        int trial = 0;
        if (!auto) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("SIM: type a label or x,y. q to quit.");
            String line;
            while ((line = in.readLine()) != null && !line.trim().equalsIgnoreCase("q")) {
                double[] t = parseSpot(line.trim());
                if (t != null) simSpot(csv, ++trial, line.trim().toUpperCase(), t, rnd);
            }
            return;
        }
        for (int rep = 0; rep < simReps; rep++)
            for (String c : cells) simSpot(csv, ++trial, c, parseSpot(c), rnd);
        // dead-zone check: stand 40 cm from the wall (inside the 50 cm zone) at left, centre, right
        for (int rep = 0; rep < simReps; rep++)
            for (int x : new int[]{25, 75, 125})
                simSpot(csv, ++trial, "DZ" + x, new double[]{x, 40}, rnd);
    }

    static void simSpot(PrintWriter csv, int trial, String label, double[] truth, Random rnd) {
        SensorInputBridge v2 = new SensorInputBridge(true);
        long t = 0;
        for (int k = 0; k < packetsPerSpot + SETTLE_PACKETS; k++) {
            // a standing person sways a little
            double x = truth[0] + rnd.nextGaussian() * 1.5, y = truth[1] + rnd.nextGaussian() * 1.5;
            double[] r = simRanges(x, y, rnd);
            t += 176;
            SensorInputBridge.Fix f2 = v2.acceptRanges(r, t);
            if (k < SETTLE_PACKETS) continue;
            writeRow(csv, "predicted", trial, label, truth, k, r, V1Solver.solve(r), f2, v2.isInDeadZone());
        }
    }

    static double[] simRanges(double x, double y, Random rnd) {
        int n = SensorInputBridge.SENSOR_X_CM.length;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            if (!SensorSimulator.inBeam(i, x, y) || rnd.nextDouble() < simNoEcho) { r[i] = Double.NaN; continue; }
            r[i] = Math.hypot(x - SensorInputBridge.SENSOR_X_CM[i], y - SensorInputBridge.SENSOR_Y_CM[i])
                    + rnd.nextGaussian() * simNoiseCm;
            if (rnd.nextDouble() < simCrosstalk) r[i] = 40 + rnd.nextDouble() * 250;
        }
        return r;
    }

    // ------------------------------------------------------------------ helpers

    static double[] parseSpot(String s) {
        s = s.toUpperCase();
        if (s.matches("DZ(25|75|125)")) return new double[]{Double.parseDouble(s.substring(2)), 40};
        if (s.matches("[ABC][123]")) {
            int col = s.charAt(0) - 'A', row = s.charAt(1) - '1';
            double band = (SensorInputBridge.FIELD_MAX_X_CM - SensorInputBridge.FIELD_MIN_X_CM) / 3;
            double depth = (SensorInputBridge.FIELD_FAR_CM - SensorInputBridge.DEAD_ZONE_CM) / 3;
            return new double[]{SensorInputBridge.FIELD_MIN_X_CM + band * (col + 0.5),
                    SensorInputBridge.DEAD_ZONE_CM + depth * (row + 0.5)};
        }
        String[] p = s.split("[,\\s]+");
        if (p.length == 2) {
            try { return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])}; }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

    static long parseSeq(String pkt) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Seq:(\\d+)").matcher(pkt);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static String num(double v) { return Double.isNaN(v) ? "" : String.format("%.2f", v); }

    static void writeRow(PrintWriter csv, String src, int trial, String label, double[] truth, long seq,
                         double[] r, V1Solver.Fix f1, SensorInputBridge.Fix f2, boolean alarm) {
        StringBuilder sb = new StringBuilder();
        sb.append(src).append(',').append(trial).append(',').append(label).append(',')
          .append(num(truth[0])).append(',').append(num(truth[1])).append(',').append(seq);
        for (int i = 0; i < 4; i++) sb.append(',').append(i < r.length ? num(r[i]) : "");
        if (f1 == null) sb.append(",,,none");
        else sb.append(',').append(num(f1.xCm)).append(',').append(num(f1.yCm))
               .append(',').append(f1.triangulated ? "tri" : "single");
        if (f2 == null) sb.append(",,,none,0");
        else sb.append(',').append(num(f2.xCm)).append(',').append(num(f2.yCm))
               .append(',').append(f2.kind == SensorInputBridge.Kind.MULTILATERATED ? "multi" : "single")
               .append(',').append(f2.sensorsUsed);
        sb.append(',').append(alarm ? 1 : 0);
        csv.println(sb);
    }
}
