package whackamole;

import java.awt.Point;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Triangulation {
    private static final double x1 = 0.0;
    private static final double x2 = 0.1;
    private static final double x3 = 1.4;
    private static final double x4 = 1.5;

    public static Point calculatePosition(
        double d1,
        double d2,
        double d3,
        double d4) {

       double xLeft = (d1 * d1 - d2 * d2 + 0.01)/0.2;

       double yLeft = Math.sqrt(Math.max(0, d1*d1 - xLeft*xLeft
       ));


       //double xRight = (d3*d3 - d4*d4 + x4*x4 - x3*x3) / (2 *(x4-x3));

       //double yRight = Math.sqrt(Math.max(0,d3*d3 - Math.pow(xRight-x3,2)));

       //double x = (xLeft + xRight)/2.0;
       //double y = (yLeft + yRight)/2.0;


       //System.out.println("x = " + x);
       //System.out.println("y = " + y);

       //System.out.printf("x = %.3f m, y = %.3f m%n", x, y);
       
       //return new Point((int)Math.round(x*100), (int)Math.round(y*100));

       double x = xLeft;
       double y = yLeft;

       System.out.printf("x = %.3f m, y = %.3f m%n", x, y);

       return new Point((int)Math.round(x * 100), (int)Math.round(y * 100));
    }

   // public static void main(String[] args)
    //{
      //  Triangulation t = new Triangulation();

       // Point p = t.calculatePosition(1.30,1.25,1.20,1.15);

       // System.out.println(p);
    //}

    public static void main(String[] args) throws Exception {

    DatagramSocket socket = new DatagramSocket(4210);

    byte[] buffer = new byte[128];

    Pattern pattern = Pattern.compile(
        "Sensor 1: ([0-9.]+) cm Sensor 2: ([0-9.]+) cm");

    System.out.println("Waiting for ESP32...");

    while (true) {
         DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);

        String msg = new String(packet.getData(), 0, packet.getLength());

        System.out.println("Packet received!");
        System.out.println(msg);

        if (msg.contains("No echo")) {
            continue;
        }

        Matcher matcher = pattern.matcher(msg);

        if (matcher.find()) {

            double d1 = Double.parseDouble(matcher.group(1)) / 100.0;
            double d2 = Double.parseDouble(matcher.group(2)) / 100.0;

            Point p = calculatePosition(d1, d2, 0, 0);

            System.out.println("Position = " + p);
        }

        System.out.println("Waiting for packet...");
    }
}



   



    
}
