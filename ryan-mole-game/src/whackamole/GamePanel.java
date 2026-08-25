package whackamole;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Font;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The game board: draws the holes/moles, tracks the mouse, and runs the
 * spawn/score loop. Scoring rule: a mole is a hit if the cursor is over it
 * at the moment its up-timer expires (not a click).
 *
 * To extend the game (new mole types, power-ups, different scoring, a pause
 * feature, etc.) this is the file you'll touch — but the grid layout,
 * rendering, input handling, and level/scoring logic are already split into
 * separate, fairly small methods so changes stay localized.
 */
public class GamePanel extends JPanel {

    // Two boxes side by side, matching the physical rig: a 100 cm wide field
    // split into Box 1 (left) and Box 2 (right). Which box the player is in
    // comes from the TRIANGULATED x position (see SensorInputBridge).
    private static final int GRID_ROWS = 1;
    private static final int GRID_COLS = 2;
    private static final int HOLE_DIAMETER = 140;
    private static final int HOLE_GAP = 30;
    private static final int BOARD_WIDTH_PX = GRID_COLS * HOLE_DIAMETER + (GRID_COLS + 1) * HOLE_GAP;
    private static final int BOARD_HEIGHT_PX = GRID_ROWS * HOLE_DIAMETER + (GRID_ROWS + 1) * HOLE_GAP;
    private static final int GAME_DURATION_MS = 60_000; // one round = 60 seconds
    private static final int FRAME_DELAY_MS = 16;        // ~60 fps game loop

    private final List<Mole> moles = new ArrayList<>();
    private final List<GameListener> listeners = new ArrayList<>();
    private final Random random = new Random();
    private final LevelManager levelManager = new LevelManager();
    private final Timer loopTimer;
    private final SensorInputBridge sensorInputBridge = new SensorInputBridge(BOARD_WIDTH_PX, BOARD_HEIGHT_PX, 60.0);
    private final UdpSensorReader udpSensorReader;

    private Point mousePoint = new Point(-1, -1);
    private String sensorCellLabel = "Cell: --";
    private String sensorDebugLabel = "Wireless: waiting...";
    private int score = 0;
    private long nextSpawnAtMs = 0;
    private long roundEndAtMs = 0;
    private boolean running = false;

    public GamePanel() {
        setPreferredSize(new Dimension(BOARD_WIDTH_PX, BOARD_HEIGHT_PX));
        setBackground(new Color(94, 61, 30));

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mousePoint = e.getPoint();
            }
        });

        loopTimer = new Timer(FRAME_DELAY_MS, e -> tick());
        layoutHoles();

        udpSensorReader = new UdpSensorReader(this::handleSensorLine);
        udpSensorReader.start();
    }

    public void addGameListener(GameListener listener) {
        listeners.add(listener);
    }

    public void setSensorPosition(Point point) {
        sensorInputBridge.updatePosition(point.x, point.y);
    }

    public void handleSensorLine(String line) {
        sensorDebugLabel = "Wireless: " + line;
        Point point = sensorInputBridge.parseLine(line);
        if (point != null) {
            mousePoint = point;
            sensorCellLabel = describeCell(point);
        }
        repaint();
    }

    public void startGame() {
        for (Mole m : moles) m.hide();
        score = 0;
        levelManager.reset();

        long now = System.currentTimeMillis();
        roundEndAtMs = now + GAME_DURATION_MS;
        scheduleNextSpawn(now);
        running = true;
        loopTimer.start();

        fireScoreChanged();
        fireLevelChanged();
        fireTimeChanged(GAME_DURATION_MS);
    }

    public void stopGame() {
        running = false;
        loopTimer.stop();
        for (Mole m : moles) m.hide();
        repaint();
    }

    // ---- layout ----------------------------------------------------------

    private void layoutHoles() {
        moles.clear();
        int startX = HOLE_GAP;
        int startY = HOLE_GAP;
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int x = startX + col * (HOLE_DIAMETER + HOLE_GAP);
                int y = startY + row * (HOLE_DIAMETER + HOLE_GAP);
                moles.add(new Mole(x, y, HOLE_DIAMETER));
            }
        }
    }

    // ---- game loop ---------------------------------------------------------

    private void tick() {
        if (!running) return;
        long now = System.currentTimeMillis();

        if (now >= roundEndAtMs) {
            int finalScore = score;
            stopGame();
            fireGameOver(finalScore);
            return;
        }
        fireTimeChanged((int) Math.max(0, roundEndAtMs - now));

        Level level = levelManager.getCurrentLevel();
        maybeSpawnMole(now, level);
        resolveExpiredMoles(now, level);

        repaint();
    }

    private void maybeSpawnMole(long now, Level level) {
        long visibleCount = moles.stream().filter(Mole::isVisible).count();
        if (now >= nextSpawnAtMs && visibleCount < level.getMaxSimultaneousMoles()) {
            List<Mole> hidden = moles.stream().filter(m -> !m.isVisible()).toList();
            if (!hidden.isEmpty()) {
                Mole mole = hidden.get(random.nextInt(hidden.size()));
                int upTime = randomBetween(level.getMinUpTimeMs(), level.getMaxUpTimeMs());
                mole.popUp(now, upTime);
            }
            scheduleNextSpawn(now);
        }
    }

    private void resolveExpiredMoles(long now, Level level) {
        for (Mole mole : moles) {
            if (mole.isExpired(now) && !mole.isResolved()) {
                if (mole.contains(mousePoint)) {
                    score += level.getPointsPerHit();
                    fireScoreChanged();
                    if (levelManager.maybeAdvance(score)) {
                        fireLevelChanged();
                    }
                }
                mole.markResolved();
                mole.hide();
            }
        }
    }

    private void scheduleNextSpawn(long now) {
        Level level = levelManager.getCurrentLevel();
        int delay = randomBetween(level.getMinSpawnDelayMs(), level.getMaxSpawnDelayMs());
        nextSpawnAtMs = now + delay;
    }

    private int randomBetween(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min);
    }

    // ---- rendering ---------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        long now = System.currentTimeMillis();
        for (Mole mole : moles) {
            drawHole(g2, mole);
            if (mole.isVisible()) {
                drawMole(g2, mole, now);
            }
        }

        drawCursor(g2, mousePoint);
        drawSensorLabel(g2, sensorCellLabel);
        drawSerialLabel(g2, sensorDebugLabel);
        drawFixLabel(g2, sensorInputBridge.getLastFixDescription());
    }

    private void drawHole(Graphics2D g2, Mole mole) {
        g2.setColor(new Color(40, 24, 10));
        g2.fillOval(mole.getX(), mole.getY(), mole.getDiameter(), mole.getDiameter());
    }

    private void drawMole(Graphics2D g2, Mole mole, long now) {
        int pad = 10;
        int d = mole.getDiameter() - pad * 2;
        int x = mole.getX() + pad;
        int y = mole.getY() + pad;

        g2.setColor(new Color(121, 85, 61));
        g2.fillOval(x, y, d, d);

        g2.setColor(Color.BLACK);
        g2.fillOval(x + d / 3 - 4, y + d / 3, 8, 8);
        g2.fillOval(x + 2 * d / 3 - 4, y + d / 3, 8, 8);

        // shrinking arc shows how much up-time is left
        double frac = mole.timeRemainingFraction(now);
        g2.setColor(new Color(255, 215, 0));
        g2.setStroke(new BasicStroke(4));
        g2.drawArc(mole.getX(), mole.getY(), mole.getDiameter(), mole.getDiameter(),
                90, (int) (360 * frac));
    }

    private void drawCursor(Graphics2D g2, Point point) {
        if (point == null || point.x < 0 || point.y < 0) {
            return;
        }

        int size = 18;
        int x = point.x - size / 2;
        int y = point.y - size / 2;

        g2.setColor(new Color(0, 255, 140));
        g2.fillOval(x, y, size, size);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2));
        g2.drawOval(x, y, size, size);
        g2.drawLine(point.x - 12, point.y, point.x + 12, point.y);
        g2.drawLine(point.x, point.y - 12, point.x, point.y + 12);
    }

    private void drawSensorLabel(Graphics2D g2, String label) {
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g2.setColor(new Color(255, 255, 0));
        g2.drawString(label, 10, 20);
    }

    private void drawSerialLabel(Graphics2D g2, String label) {
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g2.setColor(new Color(220, 220, 220));
        g2.drawString(label, 10, 38);
    }

    /** Live triangulation readout — shows the computed (x, y) for the demo. */
    private void drawFixLabel(Graphics2D g2, String label) {
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g2.setColor(new Color(140, 235, 255));
        g2.drawString(label, 10, 56);
    }

    private String describeCell(Point point) {
        if (point == null || point.x < 0 || point.y < 0) {
            return "Cell: --";
        }

        int cellWidth = BOARD_WIDTH_PX / GRID_COLS;
        int cellHeight = BOARD_HEIGHT_PX / GRID_ROWS;
        int column = Math.max(0, Math.min(GRID_COLS - 1, point.x / cellWidth));
        int row = Math.max(0, Math.min(GRID_ROWS - 1, point.y / cellHeight));

        if (GRID_ROWS == 1) {
            return "Box: " + (column + 1);
        }
        if (GRID_COLS == 1) {
            return "Box: " + (row + 1);
        }
        char columnLetter = (char) ('A' + column);
        return "Cell: " + columnLetter + (row + 1);
    }

    // ---- listener notifications ---------------------------------------------

    private void fireScoreChanged() {
        for (GameListener l : listeners) l.onScoreChanged(score);
    }

    private void fireLevelChanged() {
        for (GameListener l : listeners) l.onLevelChanged(levelManager.getCurrentLevel().getNumber());
    }

    private void fireTimeChanged(int msRemaining) {
        for (GameListener l : listeners) l.onTimeChanged(msRemaining);
    }

    private void fireGameOver(int finalScore) {
        for (GameListener l : listeners) l.onGameOver(finalScore);
    }
}
