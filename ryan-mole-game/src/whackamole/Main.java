package whackamole;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        // --sim: fake sensor packets, no hardware, no UDP socket
        boolean simulate = java.util.Arrays.asList(args).contains("--sim");
        SwingUtilities.invokeLater(() -> new GameFrame(simulate).setVisible(true));
    }
}
