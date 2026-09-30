package whackamole;

import java.util.Random;
import java.util.function.Consumer;

/**
 * Fake hub: moves a virtual player around the field and emits packets in
 * exactly the format Ryan's hub firmware sends, so the full parse -> solve ->
 * draw path runs with no hardware and without binding UDP port 4210.
 *
 * Deliberately imperfect, like the real rig: each sensor only hears the
 * player inside its beam cone (aim angles from SensorInputBridge), plus
 * 0.5 cm range noise, occasional "No echo", and occasional crosstalk garbage on one sensor (the hub and node
 * fire on independent clocks), so the outlier rejection gets exercised.
 *
 * Run with:  java whackamole.Main --sim
 */
public class SensorSimulator implements Runnable {

    private static final int PACKET_INTERVAL_MS = 180; // roughly the hub's real loop time
    private static final double RANGE_NOISE_CM = 0.5;
    private static final double NO_ECHO_CHANCE = 0.04;
    private static final double CROSSTALK_CHANCE = 0.04;

    private final Consumer<String> sink;
    private final Random random = new Random();
    private long seq = 0;

    public SensorSimulator(Consumer<String> sink) {
        this.sink = sink;
    }

    public void start() {
        Thread t = new Thread(this, "SensorSimulator");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        long start = System.currentTimeMillis();
        while (true) {
            double t = (System.currentTimeMillis() - start) / 1000.0;
            double[] pos = playerPosition(t);
            sink.accept(buildPacket(pos[0], pos[1], System.currentTimeMillis() - start));
            try {
                Thread.sleep(PACKET_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Figure-eight wander over the field, dipping into the dead zone now and then. */
    static double[] playerPosition(double t) {
        double midX = (SensorInputBridge.FIELD_MIN_X_CM + SensorInputBridge.FIELD_MAX_X_CM) / 2;
        double halfW = (SensorInputBridge.FIELD_MAX_X_CM - SensorInputBridge.FIELD_MIN_X_CM) * 0.38;
        double midY = (SensorInputBridge.DEAD_ZONE_CM + SensorInputBridge.FIELD_FAR_CM) / 2 - 5;
        double halfD = (SensorInputBridge.FIELD_FAR_CM - SensorInputBridge.DEAD_ZONE_CM) * 0.5;
        return new double[]{midX + halfW * Math.sin(0.45 * t), midY + halfD * Math.sin(0.9 * t + 0.6)};
    }

    /** True if (x, y) is inside sensor i's beam cone, using the aim angles from the bridge. */
    static boolean inBeam(int i, double x, double y) {
        double bearing = Math.toDegrees(Math.atan2(x - SensorInputBridge.SENSOR_X_CM[i],
                y - SensorInputBridge.SENSOR_Y_CM[i]));
        return Math.abs(bearing - SensorInputBridge.SENSOR_AIM_DEG[i]) <= SensorInputBridge.BEAM_HALF_ANGLE_DEG;
    }

    String buildPacket(double x, double y, long espTimeMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Seq:").append(seq++).append("|Time:").append(espTimeMs);
        for (int i = 0; i < SensorInputBridge.SENSOR_X_CM.length; i++) {
            sb.append("|S").append(i + 1).append(':');
            if (!inBeam(i, x, y) || random.nextDouble() < NO_ECHO_CHANCE) {
                sb.append("No echo");
                continue;
            }
            double r = Math.hypot(x - SensorInputBridge.SENSOR_X_CM[i], y - SensorInputBridge.SENSOR_Y_CM[i])
                    + random.nextGaussian() * RANGE_NOISE_CM;
            if (random.nextDouble() < CROSSTALK_CHANCE) {
                r = 40 + random.nextDouble() * 250; // someone else's ping
            }
            sb.append(String.format("%.1f cm", r));
        }
        return sb.toString();
    }
}
