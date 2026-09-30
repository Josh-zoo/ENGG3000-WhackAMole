package whackamole;

import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.AbstractAction;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.awt.Toolkit;

/**
 * The game board: draws the holes/moles, tracks the player's sensor-derived
 * position, and runs the spawn/score loop.
 *
 * Scoring rule: a mole is a hit the instant the player's tracked (column,
 * row) box matches the mole's box. That match is checked on a fixed-interval
 * "ping" (see pingTimer / PING_INTERVAL_MS below), decoupled from the ~60fps
 * render loop.
 *
 * V2 tracking display: the cursor now sits at the player's actual solved
 * (x, y) instead of snapping to a box centre, and every fix leaves a dot that
 * fades out over TRAIL_LIFETIME_MS. Moving produces a fading "snake", which
 * makes the multilateration visible to an audience. Press T to hide/show it.
 */
public class GamePanel extends JPanel {

    // Grid comes from the bridge so the two can never disagree.
    private static final int GRID_ROWS = SensorInputBridge.NUM_ROWS;
    private static final int GRID_COLS = SensorInputBridge.NUM_COLS;
    private static final int HOLE_DIAMETER = 140;
    private static final int HOLE_GAP = 30;
    private static final int BOARD_WIDTH_PX = GRID_COLS * HOLE_DIAMETER + (GRID_COLS + 1) * HOLE_GAP;
    private static final int BOARD_HEIGHT_PX = GRID_ROWS * HOLE_DIAMETER + (GRID_ROWS + 1) * HOLE_GAP;
    private static final int GAME_DURATION_MS = 60_000; // one round = 60 seconds
    private static final int FRAME_DELAY_MS = 16;        // ~60 fps game loop

    // Hit check cadence. The hub sends a packet roughly every 170-220 ms, so
    // checking every 150 ms never misses a fix and never double-counts much.
    private static final int PING_INTERVAL_MS = 150;

    // ---- snake trail ----
    private static final int TRAIL_POLL_MS = 100;       // checks for a new fix; one dot per real fix
    private static final int TRAIL_LIFETIME_MS = 3000;  // how long a dot takes to fade out
    private static final int TRAIL_MAX_POINTS = 40;
    private static final Color TRAIL_MULTI = new Color(0, 255, 140);
    private static final Color TRAIL_SINGLE = new Color(255, 180, 0);

    private record TrailPoint(double xCm, double yCm, boolean multilaterated, long bornAtMs) {}

    private final List<Mole> moles = new ArrayList<>();
    private final List<GameListener> listeners = new ArrayList<>();
    private final Random random = new Random();
    private final LevelManager levelManager = new LevelManager();
    private final Timer loopTimer;
    private final Timer pingTimer;
    private final Timer trailTimer;
    private final SensorInputBridge sensorInputBridge = new SensorInputBridge();
    private final UdpSensorReader udpSensorReader;
    private final SensorSimulator simulator;

    private final Deque<TrailPoint> trail = new ArrayDeque<>();
    private long lastTrailSeq = -1;
    private boolean showTrail = true;

    private String sensorCellLabel = "Cell: --";
    private volatile String sensorDebugLabel = "Wireless: waiting...";
    private boolean previousDeadZone = false;
    private int score = 0;
    private long nextSpawnAtMs = 0;
    private long roundEndAtMs = 0;
    private boolean running = false;

    public GamePanel() {
        this(false);
    }

    /** @param simulate true = generate fake sensor packets instead of listening on UDP (no hardware needed). */
    public GamePanel(boolean simulate) {
        setPreferredSize(new Dimension(BOARD_WIDTH_PX, BOARD_HEIGHT_PX));
        setBackground(new Color(94, 61, 30));

        loopTimer = new Timer(FRAME_DELAY_MS, e -> tick());
        pingTimer = new Timer(PING_INTERVAL_MS, e -> checkForHit());
        trailTimer = new Timer(TRAIL_POLL_MS, e -> updateTrail());
        layoutHoles();

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke('t'), "toggleTrail");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke('T'), "toggleTrail");
        getActionMap().put("toggleTrail", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showTrail = !showTrail;
                repaint();
            }
        });

        if (simulate) {
            udpSensorReader = null;
            simulator = new SensorSimulator(this::handleSensorLine);
            simulator.start();
        } else {
            simulator = null;
            udpSensorReader = new UdpSensorReader(this::handleSensorLine);
            udpSensorReader.start();
        }
        trailTimer.start(); // runs outside rounds too, so tracking can be shown on its own
    }

    public void addGameListener(GameListener listener) {
        listeners.add(listener);
    }

    /** Called on the UDP (or simulator) thread for every packet. */
    public void handleSensorLine(String line) {
        sensorDebugLabel = "Wireless: " + line;
        sensorInputBridge.accept(line);
        javax.swing.SwingUtilities.invokeLater(this::onBridgeUpdated);
    }

    private void onBridgeUpdated() {
        sensorCellLabel = describeCell();

        boolean inDeadZone = sensorInputBridge.isInDeadZone();
        if (inDeadZone && !previousDeadZone) {
            Toolkit.getDefaultToolkit().beep();
        }
        previousDeadZone = inDeadZone;
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
        pingTimer.start();

        fireScoreChanged();
        fireLevelChanged();
        fireTimeChanged(GAME_DURATION_MS);
    }

    public void stopGame() {
        running = false;
        loopTimer.stop();
        pingTimer.stop();
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

    // ---- snake trail -------------------------------------------------------

    /** Adds a dot only when the bridge has produced a NEW fix, then drops dots that have fully faded. */
    private void updateTrail() {
        long now = System.currentTimeMillis();
        SensorInputBridge.Fix fix = sensorInputBridge.getLatestFix();
        if (fix != null && fix.seq != lastTrailSeq) {
            lastTrailSeq = fix.seq;
            trail.addLast(new TrailPoint(fix.xCm, fix.yCm,
                    fix.kind == SensorInputBridge.Kind.MULTILATERATED, fix.timeMs));
            while (trail.size() > TRAIL_MAX_POINTS) trail.removeFirst();
        }
        while (!trail.isEmpty() && now - trail.peekFirst().bornAtMs() > TRAIL_LIFETIME_MS) {
            trail.removeFirst();
        }
        if (!running) repaint(); // the game loop repaints while a round is on
    }

    /** Field cm -> board px. x spans the field width; y spans the playable depth (row 0 at the top). */
    private static Point toBoard(double xCm, double yCm) {
        double fx = (xCm - SensorInputBridge.FIELD_MIN_X_CM)
                / (SensorInputBridge.FIELD_MAX_X_CM - SensorInputBridge.FIELD_MIN_X_CM);
        double fy = (yCm - SensorInputBridge.DEAD_ZONE_CM)
                / (SensorInputBridge.FIELD_FAR_CM - SensorInputBridge.DEAD_ZONE_CM);
        int px = (int) Math.round(fx * BOARD_WIDTH_PX);
        int py = (int) Math.round(fy * BOARD_HEIGHT_PX);
        return new Point(Math.max(0, Math.min(BOARD_WIDTH_PX - 1, px)),
                Math.max(0, Math.min(BOARD_HEIGHT_PX - 1, py)));
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
        resolveExpiredMoles(now);

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

    /**
     * The live ping: runs every PING_INTERVAL_MS, independent of the render
     * loop. Reads the player's current (column, row) from the sensor bridge
     * and scores any visible, unresolved mole occupying that same box.
     */
    private void checkForHit() {
        if (!running) return;

        int col = sensorInputBridge.getCurrentColumn();
        int row = sensorInputBridge.getCurrentRow();
        if (col < 0 || row < 0) return; // no fix yet, or standing in the dead zone

        for (int i = 0; i < moles.size(); i++) {
            Mole mole = moles.get(i);
            if (!mole.isVisible() || mole.isResolved()) continue;

            int moleRow = i / GRID_COLS;
            int moleCol = i % GRID_COLS;
            if (moleRow == row && moleCol == col) {
                registerHit(mole);
            }
        }
    }

    private void registerHit(Mole mole) {
        Level level = levelManager.getCurrentLevel();
        score += level.getPointsPerHit();
        fireScoreChanged();
        if (levelManager.maybeAdvance(score)) {
            fireLevelChanged();
        }
        mole.markResolved();
        mole.hide(); // pop back down immediately, like a real whack
    }

    /** Moles whose up-time ran out without ever being matched by checkForHit() are simply misses. */
    private void resolveExpiredMoles(long now) {
        for (Mole mole : moles) {
            if (mole.isExpired(now) && !mole.isResolved()) {
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

        if (showTrail) {
            drawTrail(g2, now);
        }
        SensorInputBridge.Fix fix = sensorInputBridge.getLatestFix();
        if (fix != null) {
            drawCursor(g2, toBoard(fix.xCm, fix.yCm));
        }

        drawSensorLabel(g2, sensorCellLabel);
        drawSerialLabel(g2, sensorDebugLabel);
        drawFixLabel(g2, sensorInputBridge.getLastFixDescription());
        drawTrailLegend(g2);

        if (sensorInputBridge.isInDeadZone()) {
            drawDeadZoneWarning(g2);
        }
    }

    /** Oldest dots first so newer ones paint on top. Alpha and size both shrink with age. */
    private void drawTrail(Graphics2D g2, long now) {
        TrailPoint prev = null;
        for (TrailPoint tp : trail) {
            double life = 1.0 - (now - tp.bornAtMs()) / (double) TRAIL_LIFETIME_MS;
            if (life <= 0) { prev = tp; continue; }
            int alpha = (int) Math.round(230 * life);
            Color base = tp.multilaterated() ? TRAIL_MULTI : TRAIL_SINGLE;
            Point p = toBoard(tp.xCm(), tp.yCm());

            if (prev != null) {
                Point q = toBoard(prev.xCm(), prev.yCm());
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha / 3));
                g2.drawLine(q.x, q.y, p.x, p.y);
            }

            int r = (int) Math.round(3 + 5 * life);
            g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
            g2.fillOval(p.x - r, p.y - r, 2 * r, 2 * r);
            prev = tp;
        }
    }

    private void drawTrailLegend(Graphics2D g2) {
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        int y = BOARD_HEIGHT_PX - 8;
        g2.setColor(TRAIL_MULTI);
        g2.fillOval(10, y - 8, 8, 8);
        g2.setColor(new Color(230, 230, 230));
        g2.drawString("multilaterated", 22, y);
        g2.setColor(TRAIL_SINGLE);
        g2.fillOval(112, y - 8, 8, 8);
        g2.setColor(new Color(230, 230, 230));
        g2.drawString("single echo    T = trail " + (showTrail ? "on" : "off"), 124, y);
    }

    private void drawDeadZoneWarning(Graphics2D g2) {
        g2.setColor(new Color(220, 0, 0, 180));
        g2.fillRect(0, 0, getWidth(), 35);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));

        g2.drawString("DEAD ZONE - MOVE BACK", 10, 24);
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
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        g2.setColor(new Color(220, 220, 220));
        g2.drawString(label, 10, 38);
    }

    /** Live multilateration readout: computed (x, y), sensors used, residual. */
    private void drawFixLabel(Graphics2D g2, String label) {
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        g2.setColor(new Color(140, 235, 255));
        g2.drawString(label, 10, 54);
    }

    /** Reads column/row straight from the bridge, so this always agrees with checkForHit(). */
    private String describeCell() {
        int col = sensorInputBridge.getCurrentColumn();
        int row = sensorInputBridge.getCurrentRow();
        if (col < 0 || row < 0) {
            return "Cell: --";
        }
        char columnLetter = (char) ('A' + col);
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
