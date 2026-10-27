package whackamole;

import javax.swing.JPanel;
import javax.swing.Timer;
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
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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
 * Tracking display: the player's sensor-derived position is shown as a
 * pixel-art hammer cursor instead of a raw dot, eased smoothly toward each
 * new fix rather than snapping to it (see the "hammer cursor" section below).
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

    // The whole board is always drawn at this fixed "design" size, in design
    // pixels -- every layout/sprite constant above assumes it. paintComponent()
    // then scales that design canvas up (uniformly, keeping aspect ratio) to
    // fill however big the real window turns out to be, so going fullscreen
    // is just a bigger scale factor rather than a layout rewrite. Any leftover
    // space from a mismatched aspect ratio is letterboxed in cabinet wood,
    // never left black.
    private static final int DESIGN_WIDTH = BOARD_WIDTH_PX + 2 * BORDER;
    private static final int DESIGN_HEIGHT = SKY_HEIGHT + BOARD_HEIGHT_PX + 2 * BORDER;

    private static final int LEVEL_DURATION_MS = 30_000; // fresh timer at the start of each level
    private static final int FRAME_DELAY_MS = 16;          // ~60 fps game loop

    // Hit check cadence. The hub sends a packet roughly every 170-220 ms, so
    // checking every 150 ms never misses a fix and never double-counts much.
    private static final int PING_INTERVAL_MS = 150;

    // ---- hammer cursor ----
    // Tracks the player's sensor-derived position with a pixel-art hammer
    // instead of a raw dot. The displayed position eases toward each new fix
    // rather than snapping straight to it, so motion stays smooth even though
    // fixes only arrive every ~150-220 ms (see CURSOR_SMOOTH_ALPHA below).
    private static final double CURSOR_SMOOTH_ALPHA = 0.25; // per-tick (60fps) ease factor toward the latest fix; lower = smoother but laggier
    private static final double HAMMER_IDLE_ANGLE = Math.toRadians(-45);  // resting pose: head up-and-right, diagonal
    private static final double HAMMER_SWING_ANGLE = 0.0;                 // just-hit pose: horizontal (the sprite is authored in this pose)
    private static final long CURSOR_SWING_MS = 280; // how long the cursor stays in the "swung" pose after a hit

    // Hand-authored pixel-art hammer, drawn as-is for the "just swung" pose
    // (horizontal, grip on the left) and rotated -45 deg for the idle pose
    // (head up-and-right, diagonal) -- same rotate-the-art trick as the mole
    // sprites use rise/sink animation, just applied to rotation instead.
    private static final int HAMMER_PIXEL = 5; // size (in screen px) of one hammer "pixel" block
    private static final String[] HAMMER_SPRITE = {
        "...........ooooorr",
        "...........oGGGGGr",
        ".........ooGGGGGGo",
        "oooooooooHHGGGGGGo",
        "oHhHHhHHhHHggggggo",
        "oooooooooHHggggggo",
        ".........ooggggggo",
        "...........ogggggo",
        "...........ooooooo",
    };
    private static final int HAMMER_SPRITE_COLS = 18;
    private static final int HAMMER_SPRITE_ROWS = 9;
    // Where the tracked point sits within the sprite grid: the left-hand tip
    // of the grip, vertically centered on the handle. Everything is drawn
    // relative to this so rotation pivots around the player's actual position.
    private static final int HAMMER_GRIP_COL = 0;
    private static final int HAMMER_GRIP_ROW = 4;
    private static final Color HAMMER_HEAD_LIGHT = new Color(205, 206, 214);
    private static final Color HAMMER_HEAD_DARK = new Color(122, 123, 133);
    private static final Color HAMMER_HANDLE_LIGHT = new Color(150, 103, 58);
    private static final Color HAMMER_HANDLE_DARK = new Color(103, 68, 36);
    private static final Color HAMMER_ACCENT = new Color(178, 94, 70);

    // ---- pixel-art tuning --------------------------------------------------
    private static final int PIXEL = 6; // size (in screen px) of one "pixel" block
    private static final int HOLE_W = 104;
    private static final int HOLE_H = 44;
    private static final int HOLE_BOTTOM_MARGIN = 10; // gap from cell bottom to hole center

    private static class FloatingText {
        final String message;
        final int x, y;
        final Color color;
        final long startTime;

        FloatingText(String message, int x, int y, Color color, long startTime) {
            this.message = message;
            this.x = x;
            this.y = y;
            this.color = color;
            this.startTime = startTime;
        }
    }

    private static final long FLOAT_DURATION_MS = 700;
    private final List<FloatingText> floatingTexts = new ArrayList<>();

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

    // Same 16x14 grid, shown briefly right after a successful hit. No hammer
    // anymore (that's the player's own cursor's job now) -- just a scatter of
    // impact sparks around the mole's head, with the mole itself unobscured.
    private static final String[] MOLE_HIT_SPRITE = {
        "..S..........S..",
        "S....oo..oo....S",
        ".S..oBBooBBo..S.",
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
    private static final long HIT_DISPLAY_MS = 260; // how long the spark sprite stays up before sinking

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

    private final List<Mole> moles = new ArrayList<>();
    private final List<Rectangle> cellBounds = new ArrayList<>();
    private final List<GameListener> listeners = new ArrayList<>();
    private final Random random = new Random();
    private final LevelManager levelManager = new LevelManager();
    private final Timer loopTimer;
    private final Timer pingTimer;
    private final SensorInputBridge sensorInputBridge = new SensorInputBridge();
    private final UdpSensorReader udpSensorReader;
    private final SensorSimulator simulator;

    private final BufferedImage grassBackground;

    // Smoothed, displayed hammer-cursor position (design-pixel space) — eased
    // toward the latest sensor fix each tick rather than snapping to it.
    // NaN until the first fix arrives.
    private double cursorX = Double.NaN;
    private double cursorY = Double.NaN;
    private long cursorSwingUntilMs = 0; // while now < this, the cursor shows the horizontal "swung" pose

    private String sensorCellLabel = "Cell: --";
    private volatile String sensorDebugLabel = "Wireless: waiting...";
    private boolean previousDeadZone = false;
    private int score = 0;
    private int totalMolesSpawned = 0;
    private int hits = 0;
    private int misses = 0;

    private long totalReactionTime = 0;
    private int successfulHits = 0;

    private long currentMoleSpawnTime = 0;
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
        // Just the starting hint for layout -- the frame maximizes over this,
        // and paintComponent() scales the design canvas to whatever size the
        // panel actually ends up being.
        setPreferredSize(new Dimension(DESIGN_WIDTH, DESIGN_HEIGHT));
        setBackground(CABINET_WOOD);

        grassBackground = createGrassTexture(BOARD_WIDTH_PX, BOARD_HEIGHT_PX);

        loopTimer = new Timer(FRAME_DELAY_MS, e -> tick());
        pingTimer = new Timer(PING_INTERVAL_MS, e -> checkForHit());
        layoutHoles();

        if (simulate) {
            udpSensorReader = null;
            simulator = new SensorSimulator(this::handleSensorLine);
            simulator.start();
        } else {
            simulator = null;
            udpSensorReader = new UdpSensorReader(this::handleSensorLine);
            udpSensorReader.start();
        }
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
        totalMolesSpawned = 0;
        hits = 0;
        misses = 0;
        totalReactionTime = 0;
        successfulHits = 0;
        currentMoleSpawnTime = 0;
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

        floatingTexts.clear();
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

    // ---- hammer cursor -------------------------------------------------------

    /**
     * Eases the displayed cursor position toward the latest sensor fix by a
     * fixed fraction every tick (60fps), instead of snapping straight to each
     * new fix. A fix only arrives every ~150-220 ms, so without this the
     * cursor would sit still and then teleport; with it, each new fix just
     * becomes a new target the cursor glides toward over the next several
     * frames.
     */
    private void updateCursorSmoothing() {
        SensorInputBridge.Fix fix = sensorInputBridge.getLatestFix();
        if (fix == null) return;
        Point target = toBoard(fix.xCm, fix.yCm);
        if (Double.isNaN(cursorX)) {
            cursorX = target.x;
            cursorY = target.y;
        } else {
            cursorX += (target.x - cursorX) * CURSOR_SMOOTH_ALPHA;
            cursorY += (target.y - cursorY) * CURSOR_SMOOTH_ALPHA;
        }
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

        updateCursorSmoothing(); // keep the hammer gliding even while frozen for intermission

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
        floatingTexts.removeIf(text ->
            now - text.startTime >= FLOAT_DURATION_MS);

        repaint();
    }

    /** Once the spark sprite has had its moment, sink the mole like any other. */
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

                totalMolesSpawned++;
                currentMoleSpawnTime = now;
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
        showFloatingText(mole, "+" + level.getPointsPerHit() + " HIT!", Color.GREEN, now);
        hits++;
        successfulHits++;
        totalReactionTime += now - currentMoleSpawnTime;
        fireScoreChanged();
        mole.markResolved();
        mole.markHit(now); // show the spark sprite briefly instead of vanishing instantly
        cursorSwingUntilMs = now + CURSOR_SWING_MS; // and swing the player's own hammer cursor to match
        if (levelManager.maybeAdvance(score)) {
            fireLevelChanged();
            enterIntermission(now);
        }
    }

    private void showFloatingText(Mole mole, String message, Color color, long now) {
        int index = moles.indexOf(mole);
        if (index < 0) return;

        Rectangle cell = cellBounds.get(index);
        int x = cell.x + cell.width / 2;
        int y = cell.y + cell.height / 2;

        floatingTexts.add(new FloatingText(message, x, y, color, now));
    }

    /** Moles whose up-time ran out without ever being matched by checkForHit() are simply misses. */
    private void resolveExpiredMoles(long now) {
        for (Mole mole : moles) {
            if (mole.isExpired(now) && !mole.isResolved()) {
                misses++;
                showFloatingText(mole, "MISS!", Color.RED, now);
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
        // Design-space coordinates: this is compared against toBoard(), which
        // also returns design-space points, and drawn inside the scaled
        // transform in paintComponent() -- never against the real (fullscreen)
        // panel size.
        int x = (DESIGN_WIDTH - CONTINUE_BTN_W) / 2;
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

        int panelW = getWidth();
        int panelH = getHeight();

        // Paint the *real* panel (which may be a much bigger fullscreen area
        // than the design canvas) in cabinet wood first, so any letterboxing
        // from an aspect-ratio mismatch shows as more cabinet, never black.
        g2.setColor(CABINET_WOOD);
        g2.fillRect(0, 0, panelW, panelH);

        double scale = Math.min(panelW / (double) DESIGN_WIDTH, panelH / (double) DESIGN_HEIGHT);
        int offsetX = (int) Math.round((panelW - DESIGN_WIDTH * scale) / 2.0);
        int offsetY = (int) Math.round((panelH - DESIGN_HEIGHT * scale) / 2.0);

        AffineTransform baseTransform = g2.getTransform();
        g2.translate(offsetX, offsetY);
        g2.scale(scale, scale);

        // Everything from here down is drawn in fixed "design pixel" units
        // (DESIGN_WIDTH x DESIGN_HEIGHT), exactly as before fullscreen support
        // was added -- the transform above is what stretches it to fill the
        // real window.
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

        drawFloatingTexts(g2, now);

        // ...but switch back to smoothed drawing for the tracking overlay, it reads much better.
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (!Double.isNaN(cursorX)) {
            boolean swung = now < cursorSwingUntilMs;
            drawHammerCursor(g2, (int) Math.round(cursorX), (int) Math.round(cursorY), swung);
        }

        drawDebugPanel(g2);

        if (intermissionActive) {
            drawIntermissionOverlay(g2, now);
        }

        if (sensorInputBridge.isInDeadZone()) {
            drawDeadZoneWarning(g2);
        }

        g2.setTransform(baseTransform);
    }

    private void drawCabinetBorder(Graphics2D g2) {
        g2.setColor(CABINET_WOOD);
        g2.fillRect(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT);
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

    private void drawFloatingTexts(Graphics2D g2, long now) {
        Font oldFont = g2.getFont();
        java.awt.Composite oldComposite = g2.getComposite();

        g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));

        for (FloatingText text : floatingTexts) {
            double progress = Math.min(1.0,
                    (now - text.startTime) / (double) FLOAT_DURATION_MS);

            int y = text.y - (int) (progress * 35);
            float alpha = (float) (1.0 - progress);

            g2.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER, alpha));

            g2.setColor(text.color);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text.message, text.x - fm.stringWidth(text.message) / 2, y);
        }

        g2.setComposite(oldComposite);
        g2.setFont(oldFont);
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
            case 'S' -> SUN_COLOR;
            default -> null;
        };
    }

    /** Dims the board and shows a "level complete, hover to continue" prompt. */
    private void drawIntermissionOverlay(Graphics2D g2, long now) {
        g2.setColor(OVERLAY_TINT);
        g2.fillRect(BORDER, BORDER, BOARD_WIDTH_PX, SKY_HEIGHT + BOARD_HEIGHT_PX);

        int centerX = DESIGN_WIDTH / 2;
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

    private void drawDeadZoneWarning(Graphics2D g2) {
        g2.setColor(new Color(220, 0, 0, 180));
        g2.fillRect(0, 0, DESIGN_WIDTH, 35);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));

        g2.drawString("DEAD ZONE - MOVE BACK", 10, 24);
    }

    /**
     * The player's tracked position, drawn as a pixel-art hammer instead of a
     * dot. (x, y) is the grip/hotspot -- where the hit actually registers --
     * with the hammer extending out from it. Idle pose holds the head up and
     * to the right on the diagonal; right after a hit it swings level to
     * horizontal, as if it's just come down, then eases back to idle.
     */
    private void drawHammerCursor(Graphics2D g2, int x, int y, boolean swung) {
        AffineTransform old = g2.getTransform();
        g2.translate(x, y);
        g2.rotate(swung ? HAMMER_SWING_ANGLE : HAMMER_IDLE_ANGLE);

        int originX = -HAMMER_GRIP_COL * HAMMER_PIXEL;
        int originY = -HAMMER_GRIP_ROW * HAMMER_PIXEL;
        for (int row = 0; row < HAMMER_SPRITE_ROWS; row++) {
            String line = HAMMER_SPRITE[row];
            for (int col = 0; col < HAMMER_SPRITE_COLS; col++) {
                Color c = hammerColorFor(line.charAt(col));
                if (c == null) continue;
                g2.setColor(c);
                g2.fillRect(originX + col * HAMMER_PIXEL, originY + row * HAMMER_PIXEL, HAMMER_PIXEL, HAMMER_PIXEL);
            }
        }

        g2.setTransform(old);
    }

    private Color hammerColorFor(char c) {
        return switch (c) {
            case 'o' -> MOLE_OUTLINE;
            case 'H' -> HAMMER_HANDLE_LIGHT;
            case 'h' -> HAMMER_HANDLE_DARK;
            case 'G' -> HAMMER_HEAD_LIGHT;
            case 'g' -> HAMMER_HEAD_DARK;
            case 'r' -> HAMMER_ACCENT;
            default -> null;
        };
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

        double accuracy =
                (hits + misses) == 0 ? 0 :
                hits * 100.0 / (hits + misses);

        double averageReaction =
                successfulHits == 0 ? 0 :
                totalReactionTime / (double) successfulHits;

        for (GameListener l : listeners) {
            l.onGameOver(
                    finalScore,
                    hits,
                    misses,
                    accuracy,
                    averageReaction
            );
        }
    }
}