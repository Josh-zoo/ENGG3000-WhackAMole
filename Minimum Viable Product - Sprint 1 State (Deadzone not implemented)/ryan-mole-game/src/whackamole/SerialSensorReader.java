package whackamole;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * Starts a small Python helper that reads serial data from the ESP32 and forwards
 * each line to the game.
 */
public class SerialSensorReader implements Runnable {

    private final Consumer<String> lineHandler;
    private final String port;
    private final int baudRate;
    private Thread thread;
    private Process process;

    public SerialSensorReader(Consumer<String> lineHandler) {
        this(lineHandler, "COM3", 115200);
    }

    public SerialSensorReader(Consumer<String> lineHandler, String port, int baudRate) {
        this.lineHandler = lineHandler;
        this.port = port;
        this.baudRate = baudRate;
    }

    public void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }

        thread = new Thread(this, "SerialSensorReader");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        try {
            ProcessBuilder builder = new ProcessBuilder(buildCommand());
            builder.directory(null);
            builder.redirectErrorStream(true);
            process = builder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lineHandler != null) {
                        lineHandler.accept(line);
                    }
                }
            }
        } catch (IOException ex) {
            System.err.println("Unable to start serial reader: " + ex.getMessage());
        }
    }

    public void stop() {
        if (process != null) {
            process.destroy();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private String[] buildCommand() {
        String pythonCommand = findPythonCommand();
        if ("py".equals(pythonCommand)) {
            return new String[]{pythonCommand, "-3", resolveScriptPath().toString(), "--port", port, "--baud", String.valueOf(baudRate)};
        }

        return new String[]{pythonCommand, resolveScriptPath().toString(), "--port", port, "--baud", String.valueOf(baudRate)};
    }

    private Path resolveScriptPath() {
        Path current = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path script = current.resolve("serial_sensor_reader.py");
        if (Files.exists(script)) {
            return script;
        }

        Path moleGameScript = current.resolve("mole-game").resolve("serial_sensor_reader.py");
        if (Files.exists(moleGameScript)) {
            return moleGameScript;
        }

        Path parent = current.getParent();
        if (parent != null) {
            Path parentScript = parent.resolve("serial_sensor_reader.py");
            if (Files.exists(parentScript)) {
                return parentScript;
            }

            Path parentMoleGameScript = parent.resolve("mole-game").resolve("serial_sensor_reader.py");
            if (Files.exists(parentMoleGameScript)) {
                return parentMoleGameScript;
            }
        }

        return script;
    }

    private String findPythonCommand() {
        String[] candidates = {"py", "python", "python3"};
        for (String candidate : candidates) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        return "python";
    }

    private boolean isExecutable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version").start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException ex) {
            return false;
        }
    }
}
