package whackamole;

import java.awt.Point;

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

       double xLeft = (d1 * d1 - d2 * d2 - 0.01)/0.2;

       double yLeft = Math.sqrt(Math.max(0, d1*d1 - xLeft*xLeft
       ));


       double xRight = (d3*d3 - d4*d4 + x4*x4 - x3*x3) / (2 *(x4-x3));

       double yRight = Math.sqrt(Math.max(0,d3*d3 - Math.pow(xRight-x3,2)));

       double x = (xLeft + xRight)/2.0;
       double y = (yLeft + yRight)/2.0;


       System.out.println("x = " + x);
       System.out.println("y = " + y);

       System.out.printf("x = %.3f m, y = %.3f m%n", x, y);
       
       return new Point((int)Math.round(x*100), (int)Math.round(y*100));
        }

   // public static void main(String[] args)
    //{
      //  Triangulation t = new Triangulation();

       // Point p = t.calculatePosition(1.30,1.25,1.20,1.15);

       // System.out.println(p);
    //}

    
}
