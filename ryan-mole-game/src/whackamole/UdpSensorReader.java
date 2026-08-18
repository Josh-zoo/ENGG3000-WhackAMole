package whackamole;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Listens for UDP packets from the ESP32 and forwards each text line to the game.
 */
public class UdpSensorReader implements Runnable {

    private final Consumer<String> lineHandler;
    private final int port;
    private Thread thread;
    private DatagramSocket socket;

    public UdpSensorReader(Consumer<String> lineHandler) {
        this(lineHandler, 4210);
    }

    public UdpSensorReader(Consumer<String> lineHandler, int port) {
        this.lineHandler = lineHandler;
        this.port = port;
    }

    public void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }

        thread = new Thread(this, "UdpSensorReader");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (socket != null) {
            socket.close();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[256];

        try {
            socket = new DatagramSocket(port);
            socket.setSoTimeout(1000);
            System.out.println("UDP sensor reader listening on port " + port);

            while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String line = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (!line.isEmpty() && lineHandler != null) {
                        lineHandler.accept(line);
                    }
                } catch (IOException ex) {
                    if (!socket.isClosed()) {
                        System.err.println("UDP sensor read error: " + ex.getMessage());
                    }
                }
            }
        } catch (SocketException ex) {
            System.err.println("Unable to start UDP sensor reader: " + ex.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}