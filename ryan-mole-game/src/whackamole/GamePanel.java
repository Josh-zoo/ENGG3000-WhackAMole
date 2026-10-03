package whackamole;

import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.AbstractAction;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.awt.Toolkit;

/**
 * The game board: draws the holes/moles in a retro pixel-art style, tracks
 * the player's sensor-derived position, and runs the spawn/score loop.
 *
 * Scoring rule: a mole is a hit the instant the player's tracked (column,
 * row) box matches the mole's box. That match is checked on a fixed-interval
 * "ping" (see pingTimer / PING_INTERVAL_MS below), decoupled from the ~60fps
 * render loop.
 *
 * Tracking display: the cursor sits at the player's actual solved (x, y)
 * instead of snapping to a box centre, and every fix leaves a dot that fades
 * out over TRAIL_LIFETIME_MS. Moving produces a fading "snake", which makes
 * the multilateration visible to an audience. Press T to hide/show it.
 *
 * Levels: each level gets its own fresh LEVEL_DURATION_MS clock. Clearing a
 * level's score threshold freezes the board and shows a "LEVEL COMPLETE"
 * screen — hover your tracked position over the on-screen button (same
 * sensor input used to play) to start the next level.
 */
public class GamePanel extends JPanel {

    // Grid comes from the bridge so the two can never disagree.
    private static final int GRID_ROWS = SensorInputBridge.NUM_ROWS;
    private static final int GRID_COLS = SensorInputBridge.NUM_COLS;

    // ---- board layout (pixel-art cell grid) --------------------------------
    private static final int CELL_W = 132;
    private static final int CELL_H = 148;
    private static final int CELL_GAP = 14;
    private static final int BORDER = 20;      // thickness of the wooden cabinet frame
    private static final int SKY_HEIGHT = 56;  // strip of sky above the grass/holes

    // Field pixel span (the grass+holes area only — sky and cabinet frame sit outside this).
    // toBoard() scales real-world cm into this box exactly as before; FIELD_OFFSET_X/Y then
    // translate that into the full, framed panel.
    private static final int BOARD_WIDTH_PX = GRID_COLS * CELL_W + (GRID_COLS - 1) * CELL_GAP;
    private static final int BOARD_HEIGHT_PX = GRID_ROWS * CELL_H + (GRID_ROWS - 1) * CELL_GAP;
    private static final int FIELD_OFFSET_X = BORDER;
    private static final int FIELD_OFFSET_Y = BORDER + SKY_HEIGHT;

    private static final int LEVEL_DURATION_MS = 30_000; // fresh timer at the start of each level
    private static final int FRAME_DELAY_MS = 16;          // ~60 fps game loop

    // Hit check cadence. The hub sends a packet roughly every 170-220 ms, so
    // checking every 150 ms never misses a fix and never double-counts much.
    private static final int PING_INTERVAL_MS = 150;

    // ---- snake trail ----
    private static final int TRAIL_POLL_MS = 100;       // checks for a new fix; one dot per real fix
    private static final int TRAIL_LIFETIME_MS = 3000;  // how long a dot takes to fade out
    private static final int TRAIL_MAX_POINTS = 40;
    private static final Color TRAIL_MULTI = new Color(0, 255, 140);
    private static final Color TRAIL_SINGLE = new Color(255, 180, 0);

    // ---- pixel-art tuning --------------------------------------------------
    private static final int PIXEL = 6; // size (in screen px) of one "pixel" block
    private static final int HOLE_W = 104;
    private static final int HOLE_H = 44;
    private static final int HOLE_BOTTOM_MARGIN = 10; // gap from cell bottom to hole center

    // Hand-authored 16x14 pixel-art mole sprite. Each character is one
    // PIXEL x PIXEL block. '.' is transparent; see colorFor() for the rest.
    private static final String[] MOLE_SPRITE = {
        "................",
        ".....oo..oo.....",
        "....oBBooBBo....",
        "...oBBBBBBBBo...",
        "..oBBBBBBBBBBo..",
        "..oBBBBBBBBBBo..",
        "..oBBWBBBBWBBo..",
        "..oBBBBBBBBBBo..",
        "..oBBBBNNBBBBo..",
        "..oBBBLLLLBBBo..",
        "...oBBBLLBBBo...",
        "....oBBBBBBo....",
        "....oooooooo....",
        "................",
    };
    private static final int MOLE_SPRITE_COLS = 16;
    private static final int MOLE_SPRITE_ROWS = 14;

    // Same 16x14 grid, shown briefly right after a successful hit: a hammer
    // crashes down on the mole's head, with a couple of impact sparks.
    private static final String[] MOLE_HIT_SPRITE = {
        ".S........DD....",
        ".....HHHHHH.....",
        "..S..HHHHHH.S...",
        "...oBBBBBBBBo...",
        "..oBBBBBBBBBBo..",
        "..oBBBBBBBBBBo..",
        "..oBBWBBBBWBBo..",
        "..oBBBBBBBBBBo..",
        "..oBBBBNNBBBBo..",
        "..oBBBLLLLBBBo..",
        "...oBBBLLBBBo...",
        "....oBBBBBBo....",
        "....oooooooo....",
        "................",
    };
    private static final long HIT_DISPLAY_MS = 260; // how long the hammer sprite stays up before sinking

    // ---- palette -----------------------------------------------------------
    private static final Color CABINET_WOOD = new Color(90, 54, 30);
    private static final Color CABINET_TRIM = new Color(224, 178, 66);
    private static final Color GRASS_DARK = new Color(56, 128, 24);
    private static final Color GRASS_LIGHT = new Color(112, 176, 48);
    private static final Color DIRT_OUTER = new Color(94, 51, 26);
    private static final Color DIRT_INNER = new Color(46, 24, 12);
    private static final Color MOLE_OUTLINE = new Color(33, 20, 12);
    private static final Color MOLE_BODY = new Color(158, 99, 61);
    private static final Color MOLE_BELLY = new Color(224, 178, 122);
    private static final Color MOLE_EYE = new Color(20, 14, 10);
    private static final Color MOLE_NOSE = new Color(214, 92, 122);
    private static final Color HAMMER_HEAD = new Color(140, 140, 150);
    private static final Color HAMMER_HANDLE = new Color(120, 80, 40);
    private static final Color SKY_COLOR = new Color(120, 200, 255);
    private static final Color CLOUD_COLOR = Color.WHITE;
    private static final Color SUN_COLOR = new Color(255, 221, 89);
    private static final Color TIMER_BAR_BG = new Color(40, 30, 20);
    private static final Color TIMER_BAR_FILL = new Color(255, 215, 0);
    private static final Color OVERLAY_TINT = new Color(0, 0, 0, 165);
    private static final Font OVERLAY_TITLE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 22);
    private static final Font OVERLAY_TEXT_FONT = new Font(Font.MONOSPACED, Font.BOLD, 13);

    private static final int TIMER_BAR_WIDTH = 90;
    private static final int TIMER_BAR_HEIGHT = 6;
    private static final long POP_ANIM_MS = 110;   // mole rise animation duration
    private static final long SINK_ANIM_MS = 140;  // mole sink animation duration
    private static final int RISE_DISTANCE = 42;    // vertical travel, in px

    // ---- level-complete intermission ---------------------------------------
    private static final long HOVER_HOLD_MS = 900; // how long to hover the button to continue
    private static final int CONTINUE_BTN_W = 220;
    private static final int CONTINUE_BTN_H = 50;

    private record TrailPoint(double xCm, double yCm, boolean multilaterated, long bornAtMs) {}

    private final List<Mole> moles = new ArrayList<>();
    private final List<Rectangle> cellBounds = new ArrayList<>();
    private final List<GameListener> listeners = new ArrayList<>();
    private final Random random = new Random();
    private final LevelManager levelManager = new LevelManager();
    private final Timer loopTimer;
    private final Timer pingTimer;
    private final Timer trailTimer;
    private final SensorInputBridge sensorInputBridge = new SensorInputBridge();
    private final UdpSensorReader udpSensorReader;
    private final SensorSimulator simulator;

    private final BufferedImage grassBackground;

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

    private boolean intermissionActive = false;
    private long intermissionHoverStartMs = -1; // -1 while the player isn't over the continue button

    public GamePanel() {
        this(false);
    }

    /** @param simulate true = generate fake sensor packets instead of listening on UDP (no hardware needed). */
    public GamePanel(boolean simulate) {
        int panelW = BOARD_WIDTH_PX + 2 * BORDER;
        int panelH = SKY_HEIGHT + BOARD_HEIGHT_PX + 2 * BORDER;
        setPreferredSize(new Dimension(panelW, panelH));
        setBackground(CABINET_WOOD);

        grassBackground = createGrassTexture(BOARD_WIDTH_PX, BOARD_HEIGHT_PX);

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
        intermissionActive = false;
        intermissionHoverStartMs = -1;

        long now = System.currentTimeMillis();
        roundEndAtMs = now + LEVEL_DURATION_MS;
        scheduleNextSpawn(now);
        running = true;
        loopTimer.start();
        pingTimer.start();

        fireScoreChanged();
        fireLevelChanged();
        fireTimeChanged(LEVEL_DURATION_MS);
    }

    public void stopGame() {
        running = false;
        intermissionActive = false;
        loopTimer.stop();
        pingTimer.stop();
        for (Mole m : moles) m.hide();
        repaint();
    }

    // ---- layout --------------------------------------------------------------

    private void layoutHoles() {
        moles.clear();
        cellBounds.clear();
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int cellX = FIELD_OFFSET_X + col * (CELL_W + CELL_GAP);
                int cellY = FIELD_OFFSET_Y + row * (CELL_H + CELL_GAP);
                cellBounds.add(new Rectangle(cellX, cellY, CELL_W, CELL_H));
                moles.add(new Mole(hitX(cellX), hitY(cellY), hitDiameter()));
            }
        }
    }

    private int hitDiameter() {
        return 84;
    }

    private int hitX(int cellX) {
        return cellX + (CELL_W - hitDiameter()) / 2;
    }

    private int hitY(int cellY) {
        int holeOpeningY = holeOpeningY(cellY);
        int spriteH = MOLE_SPRITE_ROWS * PIXEL;
        int restingY = holeOpeningY - spriteH + 28;
        return restingY + 6;
    }

    private int holeCenterY(int cellY) {
        return cellY + CELL_H - HOLE_H / 2 - HOLE_BOTTOM_MARGIN;
    }

    private int holeOpeningY(int cellY) {
        return holeCenterY(cellY) - HOLE_H / 2 + 6;
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

    /** Field cm -> panel px. x spans the field width; y spans the playable depth (row 0 nearest the sky). */
    private static Point toBoard(double xCm, double yCm) {
        double fx = (xCm - SensorInputBridge.FIELD_MIN_X_CM)
                / (SensorInputBridge.FIELD_MAX_X_CM - SensorInputBridge.FIELD_MIN_X_CM);
        double fy = (yCm - SensorInputBridge.DEAD_ZONE_CM)
                / (SensorInputBridge.FIELD_FAR_CM - SensorInputBridge.DEAD_ZONE_CM);
        int px = (int) Math.round(fx * BOARD_WIDTH_PX);
        int py = (int) Math.round(fy * BOARD_HEIGHT_PX);
        px = Math.max(0, Math.min(BOARD_WIDTH_PX - 1, px));
        py = Math.max(0, Math.min(BOARD_HEIGHT_PX - 1, py));
        return new Point(FIELD_OFFSET_X + px, FIELD_OFFSET_Y + py);
    }

    // ---- game loop ---------------------------------------------------------

    private void tick() {
        if (!running) return;
        long now = System.currentTimeMillis();

        if (intermissionActive) {
            updateIntermission(now);
            repaint();
            return; // spawning and the round clock are frozen during intermission
        }

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
        finalizeHitMoles(now);
        finalizeSinkingMoles(now);

        repaint();
    }

    /** Once the hammer sprite has had its moment, sink the mole like any other. */
    private void finalizeHitMoles(long now) {
        for (Mole mole : moles) {
            if (mole.isHitDisplayExpired(now, HIT_DISPLAY_MS)) {
                mole.startSinking(now);
            }
        }
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
        if (!running || intermissionActive) return;

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
        long now = System.currentTimeMillis();
        Level level = levelManager.getCurrentLevel();
        score += level.getPointsPerHit();
        fireScoreChanged();
        mole.markResolved();
        mole.markHit(now); // show the hammer sprite briefly instead of vanishing instantly
        if (levelManager.maybeAdvance(score)) {
            fireLevelChanged();
            enterIntermission(now);
        }
    }

    /** Moles whose up-time ran out without ever being matched by checkForHit() are simply misses. */
    private void resolveExpiredMoles(long now) {
        for (Mole mole : moles) {
            if (mole.isExpired(now) && !mole.isResolved()) {
                mole.markResolved();
                mole.startSinking(now); // animate back down instead of vanishing instantly
            }
        }
    }

    private void finalizeSinkingMoles(long now) {
        for (Mole mole : moles) {
            if (mole.isSinking() && mole.popFraction(now, POP_ANIM_MS, SINK_ANIM_MS) <= 0) {
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

    // ---- level-complete intermission ---------------------------------------

    private void enterIntermission(long now) {
        intermissionActive = true;
        intermissionHoverStartMs = -1;
        for (Mole m : moles) {
            m.hide();
        }
    }

    /**
     * Hover-to-continue uses the SAME sensor-tracked position used to play —
     * no mouse needed. Stand in the zone that maps onto the on-screen button
     * for HOVER_HOLD_MS to start the next level.
     */
    private void updateIntermission(long now) {
        Rectangle btn = continueButtonBounds();
        SensorInputBridge.Fix fix = sensorInputBridge.getLatestFix();
        Point p = (fix != null) ? toBoard(fix.xCm, fix.yCm) : null;

        if (p != null && btn.contains(p)) {
            if (intermissionHoverStartMs < 0) {
                intermissionHoverStartMs = now;
            } else if (now - intermissionHoverStartMs >= HOVER_HOLD_MS) {
                exitIntermission(now);
            }
        } else {
            intermissionHoverStartMs = -1;
        }
    }

    private void exitIntermission(long now) {
        roundEndAtMs = now + LEVEL_DURATION_MS; // fresh full timer for the new level
        intermissionActive = false;
        intermissionHoverStartMs = -1;
        scheduleNextSpawn(now);
        fireTimeChanged(LEVEL_DURATION_MS);
    }

    private Rectangle continueButtonBounds() {
        int x = (getWidth() - CONTINUE_BTN_W) / 2;
        int y = FIELD_OFFSET_Y + BOARD_HEIGHT_PX / 2 + 10;
        return new Rectangle(x, y, CONTINUE_BTN_W, CONTINUE_BTN_H);
    }

    // ---- rendering ---------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        // Crisp, blocky edges for the board art...
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        drawCabinetBorder(g2);
        drawSky(g2);
        g2.drawImage(grassBackground, FIELD_OFFSET_X, FIELD_OFFSET_Y, null);

        long now = System.currentTimeMillis();
        for (int i = 0; i < moles.size(); i++) {
            Mole mole = moles.get(i);
            Rectangle cell = cellBounds.get(i);
            int holeOpeningY = drawPixelHole(g2, cell);
            if (mole.isVisible()) {
                drawPoppedMole(g2, mole, cell, holeOpeningY, now);
                if (!mole.isHit()) {
                    drawTimerBar(g2, cell, mole, now);
                }
            }
        }

        // ...but switch back to smoothed drawing for the tracking overlay, it reads much better.
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (showTrail) {
            drawTrail(g2, now);
        }
        SensorInputBridge.Fix fix = sensorInputBridge.getLatestFix();
        if (fix != null) {
            drawCursor(g2, toBoard(fix.xCm, fix.yCm));
        }

        drawDebugPanel(g2);
        drawTrailLegend(g2);

        if (intermissionActive) {
            drawIntermissionOverlay(g2, now);
        }

        if (sensorInputBridge.isInDeadZone()) {
            drawDeadZoneWarning(g2);
        }
    }

    private void drawCabinetBorder(Graphics2D g2) {
        g2.setColor(CABINET_WOOD);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(CABINET_TRIM);
        g2.fillRect(BORDER - 6, BORDER - 6, BOARD_WIDTH_PX + 12, SKY_HEIGHT + BOARD_HEIGHT_PX + 12);
    }

    /** Sky band across the top of the board: flat color plus a pixel-art sun and clouds. */
    private void drawSky(Graphics2D g2) {
        g2.setColor(SKY_COLOR);
        g2.fillRect(BORDER, BORDER, BOARD_WIDTH_PX, SKY_HEIGHT);
        drawPixelEllipse(g2, FIELD_OFFSET_X + BOARD_WIDTH_PX - 48, BORDER + 26, 34, 34, SUN_COLOR);
        drawCloud(g2, FIELD_OFFSET_X + BOARD_WIDTH_PX - 140, BORDER + 12);
        drawCloud(g2, FIELD_OFFSET_X + BOARD_WIDTH_PX - 210, BORDER + 30);
    }

    /** A small blocky cloud made of three stacked pixel-snapped rectangles. */
    private void drawCloud(Graphics2D g2, int x, int y) {
        g2.setColor(CLOUD_COLOR);
        g2.fillRect(x, y + PIXEL, PIXEL * 6, PIXEL * 2);
        g2.fillRect(x + PIXEL, y, PIXEL * 4, PIXEL);
        g2.fillRect(x + PIXEL, y + PIXEL * 3, PIXEL * 4, PIXEL);
    }

    private BufferedImage createGrassTexture(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(GRASS_DARK);
        g.fillRect(0, 0, w, h);
        Random seeded = new Random(42); // fixed seed => stable pattern, not noisy
        g.setColor(GRASS_LIGHT);
        for (int py = 0; py < h; py += PIXEL) {
            for (int px = 0; px < w; px += PIXEL) {
                if (seeded.nextInt(4) == 0) {
                    g.fillRect(px, py, PIXEL, PIXEL);
                }
            }
        }
        g.dispose();
        return img;
    }

    private int drawPixelHole(Graphics2D g2, Rectangle cell) {
        int cx = cell.x + cell.width / 2;
        int cy = holeCenterY(cell.y);

        drawPixelEllipse(g2, cx, cy, HOLE_W, HOLE_H, DIRT_OUTER);
        drawPixelEllipse(g2, cx, cy, HOLE_W - 22, HOLE_H - 14, DIRT_INNER);

        int openingY = holeOpeningY(cell.y);
        g2.setColor(GRASS_LIGHT);
        int tuftY = cy - HOLE_H / 2 - PIXEL;
        g2.fillRect(cx - HOLE_W / 2 + PIXEL, tuftY, PIXEL, PIXEL);
        g2.fillRect(cx - HOLE_W / 2 + PIXEL * 3, tuftY - PIXEL, PIXEL, PIXEL);
        g2.fillRect(cx + HOLE_W / 2 - PIXEL * 2, tuftY, PIXEL, PIXEL);
        g2.fillRect(cx + HOLE_W / 2 - PIXEL * 4, tuftY - PIXEL, PIXEL, PIXEL);

        return openingY;
    }

    private void drawPixelEllipse(Graphics2D g2, int cx, int cy, int w, int h, Color color) {
        g2.setColor(color);
        int rw = w / 2, rh = h / 2;
        for (int py = cy - rh; py <= cy + rh; py += PIXEL) {
            for (int px = cx - rw; px <= cx + rw; px += PIXEL) {
                double dx = (px + PIXEL / 2.0 - cx) / (double) rw;
                double dy = (py + PIXEL / 2.0 - cy) / (double) rh;
                if (dx * dx + dy * dy <= 1.0) {
                    g2.fillRect(px, py, PIXEL, PIXEL);
                }
            }
        }
    }

    private void drawPoppedMole(Graphics2D g2, Mole mole, Rectangle cell, int holeOpeningY, long now) {
        double pop = mole.popFraction(now, POP_ANIM_MS, SINK_ANIM_MS);
        int spriteW = MOLE_SPRITE_COLS * PIXEL;
        int spriteH = MOLE_SPRITE_ROWS * PIXEL;
        int baseX = cell.x + (cell.width - spriteW) / 2;
        int restingY = holeOpeningY - spriteH + 28;
        int currentY = restingY + (int) Math.round((1 - pop) * RISE_DISTANCE);

        Shape oldClip = g2.getClip();
        int clipHeight = Math.max(0, (holeOpeningY + 6) - cell.y);
        g2.clipRect(cell.x, cell.y, cell.width, clipHeight);
        String[] sprite = mole.isHit() ? MOLE_HIT_SPRITE : MOLE_SPRITE;
        drawMoleSprite(g2, baseX, currentY, sprite);
        g2.setClip(oldClip);
    }

    /** Draws a shrinking yellow countdown bar just under the hole, showing time left before it hides. */
    private void drawTimerBar(Graphics2D g2, Rectangle cell, Mole mole, long now) {
        double remaining = mole.timeRemainingFraction(now);
        int barX = cell.x + (cell.width - TIMER_BAR_WIDTH) / 2;
        int barY = cell.y + cell.height - TIMER_BAR_HEIGHT - 3;

        g2.setColor(TIMER_BAR_BG);
        g2.fillRect(barX, barY, TIMER_BAR_WIDTH, TIMER_BAR_HEIGHT);

        int filledWidth = (int) Math.round(TIMER_BAR_WIDTH * remaining);
        g2.setColor(TIMER_BAR_FILL);
        g2.fillRect(barX, barY, filledWidth, TIMER_BAR_HEIGHT);

        g2.setColor(MOLE_OUTLINE);
        g2.drawRect(barX, barY, TIMER_BAR_WIDTH, TIMER_BAR_HEIGHT);
    }

    private void drawMoleSprite(Graphics2D g2, int originX, int originY, String[] sprite) {
        for (int row = 0; row < MOLE_SPRITE_ROWS; row++) {
            String line = sprite[row];
            for (int col = 0; col < MOLE_SPRITE_COLS; col++) {
                Color c = colorFor(line.charAt(col));
                if (c == null) continue;
                g2.setColor(c);
                g2.fillRect(originX + col * PIXEL, originY + row * PIXEL, PIXEL, PIXEL);
            }
        }
    }

    private Color colorFor(char c) {
        return switch (c) {
            case 'o' -> MOLE_OUTLINE;
            case 'B' -> MOLE_BODY;
            case 'L' -> MOLE_BELLY;
            case 'W' -> MOLE_EYE;
            case 'N' -> MOLE_NOSE;
            case 'H' -> HAMMER_HEAD;
            case 'D' -> HAMMER_HANDLE;
            case 'S' -> CLOUD_COLOR;
            default -> null;
        };
    }

    /** Dims the board and shows a "level complete, hover to continue" prompt. */
    private void drawIntermissionOverlay(Graphics2D g2, long now) {
        g2.setColor(OVERLAY_TINT);
        g2.fillRect(BORDER, BORDER, BOARD_WIDTH_PX, SKY_HEIGHT + BOARD_HEIGHT_PX);

        int centerX = getWidth() / 2;
        int completedLevel = levelManager.getCurrentLevel().getNumber() - 1;

        g2.setFont(OVERLAY_TITLE_FONT);
        g2.setColor(Color.WHITE);
        drawCenteredString(g2, "LEVEL " + completedLevel + " COMPLETE!", centerX, FIELD_OFFSET_Y + 60);

        g2.setFont(OVERLAY_TEXT_FONT);
        g2.setColor(SUN_COLOR);
        drawCenteredString(g2, "GOOD JOB! STAND ON THE BUTTON", centerX, FIELD_OFFSET_Y + 92);
        drawCenteredString(g2, "TO START LEVEL " + levelManager.getCurrentLevel().getNumber(), centerX, FIELD_OFFSET_Y + 112);

        Rectangle btn = continueButtonBounds();
        double hoverProgress = 0;
        if (intermissionHoverStartMs >= 0) {
            hoverProgress = Math.min(1.0, (now - intermissionHoverStartMs) / (double) HOVER_HOLD_MS);
        }

        g2.setColor(TIMER_BAR_BG);
        g2.fillRect(btn.x, btn.y, btn.width, btn.height);
        int fillW = (int) Math.round(btn.width * hoverProgress);
        g2.setColor(SUN_COLOR);
        g2.fillRect(btn.x, btn.y, fillW, btn.height);
        g2.setColor(MOLE_OUTLINE);
        g2.drawRect(btn.x, btn.y, btn.width, btn.height);
        g2.drawRect(btn.x + 1, btn.y + 1, btn.width - 2, btn.height - 2);

        g2.setFont(OVERLAY_TEXT_FONT);
        g2.setColor(hoverProgress > 0.5 ? Color.BLACK : Color.WHITE);
        drawCenteredString(g2, "CONTINUE", btn.x + btn.width / 2, btn.y + btn.height / 2 + 5);
    }

    private void drawCenteredString(Graphics2D g2, String text, int centerX, int baselineY) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, centerX - fm.stringWidth(text) / 2, baselineY);
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
        int y = FIELD_OFFSET_Y + BOARD_HEIGHT_PX - 8;
        g2.setColor(TRAIL_MULTI);
        g2.fillOval(FIELD_OFFSET_X + 10, y - 8, 8, 8);
        g2.setColor(new Color(230, 230, 230));
        g2.drawString("multilaterated", FIELD_OFFSET_X + 22, y);
        g2.setColor(TRAIL_SINGLE);
        g2.fillOval(FIELD_OFFSET_X + 112, y - 8, 8, 8);
        g2.setColor(new Color(230, 230, 230));
        g2.drawString("single echo    T = trail " + (showTrail ? "on" : "off"), FIELD_OFFSET_X + 124, y);
    }

    private void drawDeadZoneWarning(Graphics2D g2) {
        g2.setColor(new Color(220, 0, 0, 180));
        g2.fillRect(0, 0, getWidth(), 35);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));

        g2.drawString("DEAD ZONE - MOVE BACK", 10, 24);
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

    /** Small translucent readout panel in the sky, holding the three debug/status lines. */
    private void drawDebugPanel(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 110));
        g2.fillRect(FIELD_OFFSET_X, BORDER, 230, 50);

        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g2.setColor(new Color(255, 255, 0));
        g2.drawString(sensorCellLabel, FIELD_OFFSET_X + 6, BORDER + 16);

        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        g2.setColor(new Color(220, 220, 220));
        g2.drawString(sensorDebugLabel, FIELD_OFFSET_X + 6, BORDER + 30);

        g2.setColor(new Color(140, 235, 255));
        g2.drawString(sensorInputBridge.getLastFixDescription(), FIELD_OFFSET_X + 6, BORDER + 44);
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
