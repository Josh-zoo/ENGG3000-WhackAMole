package whackamole;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        // --sim: fake sensor packets, no hardware, no UDP socket
        boolean simulate = java.util.Arrays.asList(args).contains("--sim");
        // --mouse: the mouse is the player (hover a box = stand in it), no sensors at all
        boolean mouse = java.util.Arrays.asList(args).contains("--mouse");
        SwingUtilities.invokeLater(() -> new GameFrame(simulate, mouse).setVisible(true));
    }
}
