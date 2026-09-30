package whackamole;

import java.awt.Point;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Triangulation {

    private static final double x1 = 0.0;
    private static final double x2 = 0.08;
    private static final double x3 = 1.4;
    private static final double x4 = 1.51;

    public static Point calculatePosition(
        double d1,
        double d2,
        double d3,
        double d4) {

        double hubSpacing = 0.08;

        double xLeft =(d1 * d1 - d2 * d2 + hubSpacing * hubSpacing) / (2 * hubSpacing);
        double yLeft = Math.sqrt(Math.max(0, d1 * d1 - xLeft * xLeft));

       double nodeSpacing = 0.11;

       double xRight =(d3 * d3 - d4 * d4 + x4 * x4 - x3 * x3) / (2 * nodeSpacing);

        double yRight =
            Math.sqrt(Math.max(0, d3 * d3 - Math.pow(xRight - x3, 2)));

        System.out.printf("xLeft = %.3f%n", xLeft);
        System.out.printf("xRight = %.3f%n", xRight);

        System.out.printf("yLeft = %.3f%n", yLeft);
        System.out.printf("yRight = %.3f%n", yRight);

        double x = (xLeft + xRight) / 2.0;
        double y = (yLeft + yRight) / 2.0;

        System.out.printf("x = %.3f m, y = %.3f m%n", x, y);

        return new Point(
                (int)Math.round(x * 100),
                (int)Math.round(y * 100));
    }

    public static void main(String[] args) throws Exception {

        DatagramSocket socket = new DatagramSocket(4210);

        byte[] buffer = new byte[256];

        Pattern pattern = Pattern.compile(
            "Seq:(\\d+)\\|Time:(\\d+)\\|S1:([0-9.]+) cm\\|S2:([0-9.]+) cm\\|S3:([0-9.]+) cm\\|S4:([0-9.]+) cm");

        System.out.println("Waiting for ESP32...");

        while (true) {

            DatagramPacket packet =
                new DatagramPacket(buffer, buffer.length);

            socket.receive(packet);
            System.out.println("Packet length = " + packet.getLength());

            String msg =
                new String(packet.getData(), 0, packet.getLength());

            System.out.println("Packet received!");
            System.out.println(msg);

            if (msg.contains("No echo")) {
                continue;
            }

            Matcher matcher = pattern.matcher(msg);

            if (matcher.find()) {

                double d1 =
                    Double.parseDouble(matcher.group(3)) / 100.0;

                double d2 =
                    Double.parseDouble(matcher.group(4)) / 100.0;

                double d3 =
                    Double.parseDouble(matcher.group(5)) / 100.0;

                double d4 =
                    Double.parseDouble(matcher.group(6)) / 100.0;

                Point p = calculatePosition(d1, d2, d3, d4);

                System.out.println("Position = " + p);
            }

            System.out.println("Waiting for packet...");
        }
    }
}
