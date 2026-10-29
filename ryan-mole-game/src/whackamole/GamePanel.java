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
 * Levels: each level gets its own fresh clock (Level.getDurationMs(), 60 s by
 * default) and a points target that must be reached *within that level*.
 * Hit the target and the board freezes on a "LEVEL COMPLETE" screen — hover
 * your tracked position over the on-screen button (same sensor input used to
 * play) to start the next level. Run out of time first and it's game over.
 * Each level also has its own scenery (Theme): day, night with a moon and
 * fireflies, sunset, and so on, with a short fade when it changes.
 *
 * Power-ups: now and then a coloured power-up mole pops instead of a normal
 * one (see MoleType). Hitting it scores as usual and also triggers its
 * effect: double points for a while, extra time, or a time freeze. Active
 * effects are shown in the HUD strip under the board.
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
    // HUD strip under the board (level target progress + active power-ups),
    // sitting in the cabinet wood so it never covers the play field.
    private static final int HUD_HEIGHT = 30;
    private static final int HUD_Y = FIELD_OFFSET_Y + BOARD_HEIGHT_PX + 10;

    private static final int DESIGN_WIDTH = BOARD_WIDTH_PX + 2 * BORDER;
    private static final int DESIGN_HEIGHT = SKY_HEIGHT + BOARD_HEIGHT_PX + 2 * BORDER + HUD_HEIGHT;

    private static final int FRAME_DELAY_MS = 16;          // ~60 fps game loop

    // ---- power-ups -----------------------------------------------------------
    private static final double POWERUP_CHANCE = 0.15;  // chance a spawn is a power-up mole (max one up at a time)
    private static final int EXTRA_TIME_MS = 10_000;     // what the EXTRA_TIME mole adds to the clock

    // ---- low-time warning -----------------------------------------------------
    private static final int LOW_TIME_MS = 10_000;        // last 10 s: target bar and countdown flash red
    private static final long LOW_TIME_FLASH_MS = 250;    // blink rate
    private static final Color LOW_TIME_RED = new Color(235, 40, 40);
    private static final Color LOW_TIME_RED_DIM = new Color(130, 20, 20);

    // ---- scenery -------------------------------------------------------------
    private static final long THEME_FADE_MS = 900;       // fade-in when the scenery changes
    private static final int FIREFLY_COUNT = 16;
    private static final Color FIREFLY_GLOW = new Color(210, 255, 120);
    private static final Color MOON_COLOR = new Color(245, 240, 205);
    private static final Color STAR_COLOR = new Color(255, 255, 230);

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
    // Smash animation on a hit: wind up (head lifts back), slam down past
    // horizontal onto the mole, hold a beat, then ease back to idle.
    private static final double HAMMER_WINDUP_ANGLE = Math.toRadians(-80); // raised back before the slam
    private static final double HAMMER_SLAM_ANGLE = Math.toRadians(12);    // head driven slightly down past horizontal
    private static final long CURSOR_SWING_MS = 420;  // whole wind-up -> slam -> recover cycle
    private static final double SWING_WINDUP_END = 0.25; // fractions of CURSOR_SWING_MS
    private static final double SWING_SLAM_END = 0.40;
    private static final double SWING_HOLD_END = 0.55;
    // The mole reacts when the hammer actually lands, not when the hit registers.
    private static final long IMPACT_DELAY_MS = (long) (CURSOR_SWING_MS * SWING_SLAM_END);
    private static final long SHAKE_MS = 130;         // little board shake on impact
    private static final int SHAKE_PX = 3;

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
        "................",
        ".....oo..oo.....",
        "....oBBooBBo....",
        "...oBBBBBBBBo...",
        "..oBBBBBBBBBBo..",
        "..oBWBWBBWBWBo..",
        "..oBBWBBBBWBBo..",
        "..oBWBWBBWBWBo..",
        "..oBBBBNNBBBBo..",
        "..oBBBLLLLBBBo..",
        "...oBBBLLBBBo...",
        "....oBBBBBBo....",
        "....oooooooo....",
        "................",
    };
    // How long the squashed, dizzy mole stays up before sinking (counted from the hit,
    // so it includes the IMPACT_DELAY_MS wait for the hammer to land).
    private static final long HIT_DISPLAY_MS = IMPACT_DELAY_MS + 520;
    private static final long SQUASH_IN_MS = 70;   // how fast it flattens when the hammer lands
    private static final double SQUASH_Y = 0.55;   // squashed height, as a fraction of normal
    private static final double SQUASH_X = 1.35;   // squashed width

    // ---- palette -----------------------------------------------------------
    private static final Color CABINET_WOOD = new Color(90, 54, 30);
    private static final Color CABINET_TRIM = new Color(224, 178, 66);
    // Sky, grass and hole colours now come from the level's Theme.
    private static final Color MOLE_OUTLINE = new Color(33, 20, 12);
    private static final Color MOLE_BODY = new Color(158, 99, 61);
    private static final Color MOLE_BELLY = new Color(224, 178, 122);
    private static final Color MOLE_EYE = new Color(20, 14, 10);
    private static final Color MOLE_NOSE = new Color(214, 92, 122);
    private static final Color HAMMER_HEAD = new Color(140, 140, 150);
    private static final Color HAMMER_HANDLE = new Color(120, 80, 40);
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
    private static final long INTERMISSION_DISPLAY_MS = 5000; // how long to show "LEVEL COMPLETE" before auto-advancing

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

    // One grass texture per theme, built once on first use.
    private final java.util.EnumMap<Theme, BufferedImage> grassByTheme = new java.util.EnumMap<>(Theme.class);
    private final List<Firefly> fireflies = new ArrayList<>();
    private long themeFadeStartMs = -1; // when the current scenery started fading in; -1 = no fade running

    /** A drifting, pulsing firefly. Position is worked out from time each frame, so there's nothing to update. */
    private static class Firefly {
        final double baseX, baseY, phase, speed, wanderX, wanderY;

        Firefly(double baseX, double baseY, double phase, double speed, double wanderX, double wanderY) {
            this.baseX = baseX;
            this.baseY = baseY;
            this.phase = phase;
            this.speed = speed;
            this.wanderX = wanderX;
            this.wanderY = wanderY;
        }
    }

    // Smoothed, displayed hammer-cursor position (design-pixel space) — eased
    // toward the latest sensor fix each tick rather than snapping to it.
    // NaN until the first fix arrives.
    private double cursorX = Double.NaN;
    private double cursorY = Double.NaN;
    private long cursorSwingStartMs = -1; // start of the current smash animation; -1 = idle
    private long lastImpactMs = -1;       // when the hammer last landed (drives the board shake)

    private String sensorCellLabel = "Cell: --";
    private volatile String sensorDebugLabel = "Wireless: waiting...";
    private boolean previousDeadZone = false;
    private int score = 0;
    private int levelScore = 0; // points scored in the current level only; compared against its target
    private int totalMolesSpawned = 0;

    // Active power-up effects (wall-clock ms; effect is on while now < until).
    private long doublePointsUntilMs = 0;
    private long freezeUntilMs = 0;
    private long lastTickMs = 0;
    private int hits = 0;
    private int misses = 0;

    private long totalReactionTime = 0;
    private int successfulHits = 0;

    private long currentMoleSpawnTime = 0;
    private long nextSpawnAtMs = 0;
    private long roundEndAtMs = 0;
    private boolean running = false;

    private boolean intermissionActive = false;
    // Level target reached: the LEVEL COMPLETE screen waits until this time so the
    // winning hit's smash animation plays out first. -1 = nothing pending.
    private long pendingIntermissionAtMs = -1;
    private long intermissionEndsAtMs = -1; // wall-clock time the auto-advance fires; -1 while not in intermission

    public GamePanel() {
        this(false);
    }

    /** @param simulate true = generate fake sensor packets instead of listening on UDP (no hardware needed). */
    public GamePanel(boolean simulate) {
        this(simulate, false);
    }

    /**
     * @param simulate true = generate fake sensor packets instead of listening on UDP.
     * @param mouse    true = the mouse stands in for the player's body: hovering over a
     *                 box counts as standing in it, and the sky strip acts as the dead
     *                 zone. No sensors, simulator or UDP socket. Wins over simulate.
     */
    public GamePanel(boolean simulate, boolean mouse) {
        this.mouseMode = mouse;
        // Just the starting hint for layout -- the frame maximizes over this,
        // and paintComponent() scales the design canvas to whatever size the
        // panel actually ends up being.
        setPreferredSize(new Dimension(DESIGN_WIDTH, DESIGN_HEIGHT));
        setBackground(CABINET_WOOD);

        createFireflies();

        loopTimer = new Timer(FRAME_DELAY_MS, e -> tick());
        pingTimer = new Timer(PING_INTERVAL_MS, e -> checkForHit());
        layoutHoles();

        if (mouseMode) {
            udpSensorReader = null;
            simulator = null;
            installMouseInput();
        } else if (simulate) {
            udpSensorReader = null;
            simulator = new SensorSimulator(this::handleSensorLine);
            simulator.start();
        } else {
            simulator = null;
            udpSensorReader = new UdpSensorReader(this::handleSensorLine);
            udpSensorReader.start();
        }
    }

    // ---- mouse test mode --------------------------------------------------------

    private final boolean mouseMode;
    private volatile Point mousePoint = null; // design-pixel space; null when the mouse is off the panel
    // Last transform paintComponent used, so mouse pixels can be mapped back to design pixels.
    private double lastScale = 1, lastOffsetX = 0, lastOffsetY = 0;

    private void installMouseInput() {
        sensorDebugLabel = "MOUSE MODE: no sensors";
        java.awt.event.MouseAdapter ma = new java.awt.event.MouseAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) { onMouse(e.getPoint()); }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) { onMouse(e.getPoint()); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { mousePoint = null; onPlayerMoved(); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        // Hide the arrow: the hammer is the cursor.
        try {
            setCursor(Toolkit.getDefaultToolkit().createCustomCursor(
                    new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "none"));
        } catch (java.awt.HeadlessException | IndexOutOfBoundsException ignored) {
            // No screen (e.g. automated tests): just keep the normal arrow.
        }
    }

    private void onMouse(Point panelPx) {
        mousePoint = new Point(
                (int) Math.round((panelPx.x - lastOffsetX) / lastScale),
                (int) Math.round((panelPx.y - lastOffsetY) / lastScale));
        onPlayerMoved();
    }

    private boolean mouseInField(Point p) {
        return p != null
                && p.x >= FIELD_OFFSET_X && p.x < FIELD_OFFSET_X + BOARD_WIDTH_PX
                && p.y >= FIELD_OFFSET_Y && p.y < FIELD_OFFSET_Y + BOARD_HEIGHT_PX;
    }

    // ---- player position (sensors or mouse) -------------------------------------
    // Everything in the game reads the player through these four, so mouse
    // mode behaves exactly like the real rig: same boxes, same dead zone.

    /** Player position in design pixels, or null if unknown. */
    private Point playerPoint() {
        if (mouseMode) return mousePoint;
        SensorInputBridge.Fix fix = sensorInputBridge.getLatestFix();
        return fix != null ? toBoard(fix.xCm, fix.yCm) : null;
    }

    private int playerColumn() {
        if (!mouseMode) return sensorInputBridge.getCurrentColumn();
        Point p = mousePoint;
        return mouseInField(p) ? (p.x - FIELD_OFFSET_X) * GRID_COLS / BOARD_WIDTH_PX : -1;
    }

    private int playerRow() {
        if (!mouseMode) return sensorInputBridge.getCurrentRow();
        Point p = mousePoint;
        return mouseInField(p) ? (p.y - FIELD_OFFSET_Y) * GRID_ROWS / BOARD_HEIGHT_PX : -1;
    }

    /** In mouse mode the sky strip (top of the screen = closest to the wall) is the dead zone. */
    private boolean playerInDeadZone() {
        if (!mouseMode) return sensorInputBridge.isInDeadZone();
        Point p = mousePoint;
        return p != null && p.y < FIELD_OFFSET_Y
                && p.x >= FIELD_OFFSET_X && p.x < FIELD_OFFSET_X + BOARD_WIDTH_PX;
    }

    public void addGameListener(GameListener listener) {
        listeners.add(listener);
    }

    /** Called on the UDP (or simulator) thread for every packet. */
    public void handleSensorLine(String line) {
        sensorDebugLabel = "Wireless: " + line;
        sensorInputBridge.accept(line);
        javax.swing.SwingUtilities.invokeLater(this::onPlayerMoved);
    }

    private void onPlayerMoved() {
        sensorCellLabel = describeCell();

        boolean inDeadZone = playerInDeadZone();
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
        intermissionEndsAtMs = -1;
        pendingIntermissionAtMs = -1;
        levelScore = 0;
        clearPowerUps();
        themeFadeStartMs = -1;

        long now = System.currentTimeMillis();
        int duration = levelManager.getCurrentLevel().getDurationMs();
        roundEndAtMs = now + duration;
        lastTickMs = now;
        scheduleNextSpawn(now);
        running = true;
        loopTimer.start();
        pingTimer.start();

        fireScoreChanged();
        fireLevelChanged();
        fireTimeChanged(duration);

        floatingTexts.clear();
    }

    public void stopGame() {
        running = false;
        intermissionActive = false;
        pendingIntermissionAtMs = -1;
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
        Point target = playerPoint();
        if (target == null) return;
        if (mouseMode || Double.isNaN(cursorX)) { // mouse is already smooth: follow it exactly
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

        long dt = now - lastTickMs;
        lastTickMs = now;

        updateCursorSmoothing(); // keep the hammer gliding even while frozen for intermission

        if (intermissionActive) {
            updateIntermission(now);
            repaint();
            return; // spawning and the round clock are frozen during intermission
        }

        // Level already won: freeze the clock and spawning until the smash
        // animation has played, then show the LEVEL COMPLETE screen.
        if (pendingIntermissionAtMs >= 0) {
            roundEndAtMs += dt;
            if (now >= pendingIntermissionAtMs) {
                pendingIntermissionAtMs = -1;
                if (levelManager.maybeAdvance(levelScore)) {
                    fireLevelChanged();
                }
                enterIntermission(now);
            } else {
                finalizeHitMoles(now);
                floatingTexts.removeIf(text -> now - text.startTime >= FLOAT_DURATION_MS);
            }
            repaint();
            return;
        }

        // TIME_FREEZE: push the end of the round back by however long this
        // frame took, so the clock stands still while moles keep popping.
        if (now < freezeUntilMs) {
            roundEndAtMs += dt;
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
                mole.popUp(now, upTime, pickMoleType());

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
        if (!running || intermissionActive || pendingIntermissionAtMs >= 0) return;

        int col = playerColumn();
        int row = playerRow();
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

    /**
     * Mostly normal moles; now and then a power-up, picked evenly from the
     * power-up types. Never more than one power-up on the board at once, so
     * they stay special.
     */
    private MoleType pickMoleType() {
        boolean powerUpAlreadyUp = moles.stream()
                .anyMatch(m -> m.isVisible() && m.getType().isPowerUp());
        if (powerUpAlreadyUp || random.nextDouble() >= POWERUP_CHANCE) {
            return MoleType.NORMAL;
        }
        MoleType[] all = MoleType.values();
        return all[1 + random.nextInt(all.length - 1)]; // index 0 is NORMAL
    }

    private boolean doublePointsActive(long now) {
        return now < doublePointsUntilMs;
    }

    private void registerHit(Mole mole) {
        long now = System.currentTimeMillis();
        Level level = levelManager.getCurrentLevel();
        MoleType type = mole.getType();

        // Apply the power-up first, so a DOUBLE_POINTS mole counts double itself.
        applyPowerUp(type, now);

        int points = level.getPointsPerHit() * (doublePointsActive(now) ? 2 : 1);
        score += points;
        levelScore += points;

        if (type.isPowerUp()) {
            showFloatingText(mole, "+" + points + " " + type.banner, type.belly, now);
        } else {
            showFloatingText(mole, "+" + points + " HIT!", Color.GREEN, now);
        }
        hits++;
        successfulHits++;
        totalReactionTime += now - currentMoleSpawnTime;
        fireScoreChanged();
        mole.markResolved();
        mole.markHit(now); // show the spark sprite briefly instead of vanishing instantly
        cursorSwingStartMs = now;                // swing the player's own hammer cursor down onto it
        lastImpactMs = now + IMPACT_DELAY_MS;    // the moment it lands
        if (!levelManager.isFinalLevel() && level.hasTarget() && levelScore >= level.getTargetScore()) {
            // Target reached. Advance once the smash has finished playing, so the
            // scenery and HUD don't switch under the winning hit.
            pendingIntermissionAtMs = now + HIT_DISPLAY_MS;
        }
    }

    private void applyPowerUp(MoleType type, long now) {
        switch (type) {
            case DOUBLE_POINTS -> doublePointsUntilMs = Math.max(doublePointsUntilMs, now) + type.effectMs;
            case EXTRA_TIME -> {
                roundEndAtMs += EXTRA_TIME_MS;
                fireTimeChanged((int) Math.max(0, roundEndAtMs - now));
            }
            case TIME_FREEZE -> freezeUntilMs = Math.max(freezeUntilMs, now) + type.effectMs;
            case NORMAL -> { }
        }
    }

    private void clearPowerUps() {
        doublePointsUntilMs = 0;
        freezeUntilMs = 0;
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
        intermissionEndsAtMs = now + INTERMISSION_DISPLAY_MS;
        clearPowerUps(); // effects don't carry over into the next level
        levelScore = 0;  // HUD now previews the next level's target from zero
        themeFadeStartMs = now; // the new level's scenery fades in behind the "complete" screen
        for (Mole m : moles) {
            m.hide();
        }
    }

    /**
     * No player action needed — the "LEVEL COMPLETE" screen is shown for
     * INTERMISSION_DISPLAY_MS and then the next level starts automatically.
     */
    private void updateIntermission(long now) {
        if (now >= intermissionEndsAtMs) {
            exitIntermission(now);
        }
    }

    private void exitIntermission(long now) {
        int duration = levelManager.getCurrentLevel().getDurationMs();
        roundEndAtMs = now + duration; // fresh full timer for the new level
        levelScore = 0;                 // and a fresh target to chase
        intermissionActive = false;
        intermissionEndsAtMs = -1;
        scheduleNextSpawn(now);
        fireTimeChanged(duration);
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

        lastScale = scale;
        lastOffsetX = offsetX;
        lastOffsetY = offsetY;

        AffineTransform baseTransform = g2.getTransform();
        g2.translate(offsetX, offsetY);
        g2.scale(scale, scale);

        // Everything from here down is drawn in fixed "design pixel" units
        // (DESIGN_WIDTH x DESIGN_HEIGHT), exactly as before fullscreen support
        // was added -- the transform above is what stretches it to fill the
        // real window.
        long now = System.currentTimeMillis();
        Theme theme = levelManager.getCurrentLevel().getTheme();

        drawCabinetBorder(g2);

        // Short shake when the hammer lands. Only the field moves; the cabinet stays put.
        AffineTransform beforeShake = g2.getTransform();
        if (lastImpactMs >= 0 && now >= lastImpactMs && now - lastImpactMs < SHAKE_MS) {
            double fade = 1 - (now - lastImpactMs) / (double) SHAKE_MS;
            int dx = (int) Math.round(Math.sin(now * 0.9) * SHAKE_PX * fade);
            int dy = (int) Math.round(Math.cos(now * 1.3) * SHAKE_PX * fade);
            g2.translate(dx, dy);
        }
        drawSky(g2, theme, now);
        g2.drawImage(grassFor(theme), FIELD_OFFSET_X, FIELD_OFFSET_Y, null);

        for (int i = 0; i < moles.size(); i++) {
            Mole mole = moles.get(i);
            Rectangle cell = cellBounds.get(i);
            int holeOpeningY = drawPixelHole(g2, cell, theme);
            if (mole.isVisible()) {
                drawPoppedMole(g2, mole, cell, holeOpeningY, now);
                if (!mole.isSquashed()) {
                    drawTimerBar(g2, cell, mole, now);
                    if (mole.getType().isPowerUp()) {
                        drawPowerUpBadge(g2, mole, cell, holeOpeningY, now);
                    }
                }
            }
        }

        if (theme.fireflies) {
            drawFireflies(g2, now);
        }

        g2.setTransform(beforeShake);
        drawThemeFade(g2, now);
        drawFreezeTint(g2, now);
        drawFloatingTexts(g2, now);
        drawHud(g2, now);

        // ...but switch back to smoothed drawing for the tracking overlay, it reads much better.
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (!Double.isNaN(cursorX)) {
            drawHammerCursor(g2, (int) Math.round(cursorX), (int) Math.round(cursorY), hammerAngle(now));
        }

        drawDebugPanel(g2);

        if (intermissionActive) {
            drawIntermissionOverlay(g2, now);
        }

        if (playerInDeadZone()) {
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

    // ---- scenery ----------------------------------------------------------------

    /**
     * Sky band across the top of the board, drawn from the level's Theme:
     * a stepped (pixel-band) gradient, then stars, the sun or moon, and clouds.
     */
    private void drawSky(Graphics2D g2, Theme theme, long now) {
        // Stepped gradient: one flat band per PIXEL rows keeps the retro look.
        int bands = SKY_HEIGHT / PIXEL + 1;
        for (int i = 0; i < bands; i++) {
            double t = bands == 1 ? 0 : i / (double) (bands - 1);
            g2.setColor(lerp(theme.skyTop, theme.skyBottom, t));
            int y = BORDER + i * PIXEL;
            g2.fillRect(BORDER, y, BOARD_WIDTH_PX, Math.min(PIXEL, BORDER + SKY_HEIGHT - y));
        }

        if (theme.stars) {
            drawStars(g2, now);
        }

        int sunX = FIELD_OFFSET_X + BOARD_WIDTH_PX - 48;
        switch (theme.celestial) {
            case SUN -> drawPixelEllipse(g2, sunX, BORDER + 26, 34, 34, SUN_COLOR);
            case MOON -> drawMoon(g2, sunX, BORDER + 26);
            case SETTING_SUN -> {
                // Big low sun, half sunk behind the grass line.
                Shape oldClip = g2.getClip();
                g2.clipRect(BORDER, BORDER, BOARD_WIDTH_PX, SKY_HEIGHT);
                drawPixelEllipse(g2, sunX - 40, BORDER + SKY_HEIGHT, 58, 58, new Color(255, 120, 60));
                drawPixelEllipse(g2, sunX - 40, BORDER + SKY_HEIGHT, 40, 40, new Color(255, 190, 90));
                g2.setClip(oldClip);
            }
        }

        if (theme.stars) {
            // Just one thin wisp at night, drifting slowly past the moon.
            int drift = (int) ((now / 120) % (BOARD_WIDTH_PX + 60)) - 60;
            drawCloud(g2, FIELD_OFFSET_X + drift, BORDER + 30, theme.cloud);
        } else {
            drawCloud(g2, FIELD_OFFSET_X + BOARD_WIDTH_PX - 140, BORDER + 12, theme.cloud);
            drawCloud(g2, FIELD_OFFSET_X + BOARD_WIDTH_PX - 210, BORDER + 30, theme.cloud);
        }
    }

    /** A small blocky cloud made of three stacked pixel-snapped rectangles. */
    private void drawCloud(Graphics2D g2, int x, int y, Color color) {
        Shape oldClip = g2.getClip();
        g2.clipRect(BORDER, BORDER, BOARD_WIDTH_PX, SKY_HEIGHT);
        g2.setColor(color);
        g2.fillRect(x, y + PIXEL, PIXEL * 6, PIXEL * 2);
        g2.fillRect(x + PIXEL, y, PIXEL * 4, PIXEL);
        g2.fillRect(x + PIXEL, y + PIXEL * 3, PIXEL * 4, PIXEL);
        g2.setClip(oldClip);
    }

    /** Pixel-art crescent moon with a soft halo: a disc with a second, offset disc bitten out of it. */
    private void drawMoon(Graphics2D g2, int cx, int cy) {
        int r = 16;
        // Halo (smooth, it's light rather than an object).
        Object oldAa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(245, 240, 205, 40));
        g2.fillOval(cx - r - 10, cy - r - 10, 2 * r + 20, 2 * r + 20);
        g2.setColor(new Color(245, 240, 205, 30));
        g2.fillOval(cx - r - 5, cy - r - 5, 2 * r + 10, 2 * r + 10);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);

        int biteX = cx - 9, biteY = cy - 5; // centre of the disc that's "bitten out"
        int step = PIXEL / 2;               // finer pixels so the crescent reads as a curve
        g2.setColor(MOON_COLOR);
        for (int py = cy - r; py <= cy + r; py += step) {
            for (int px = cx - r; px <= cx + r; px += step) {
                double mx = px + step / 2.0, my = py + step / 2.0;
                boolean inMoon = sq(mx - cx) + sq(my - cy) <= r * r;
                boolean inBite = sq(mx - biteX) + sq(my - biteY) <= sq(r - 2);
                if (inMoon && !inBite) {
                    g2.fillRect(px, py, step, step);
                }
            }
        }
    }

    /** Fixed scatter of stars across the sky; a few of them twinkle. */
    private void drawStars(Graphics2D g2, long now) {
        Random seeded = new Random(7); // fixed seed => same sky every frame
        for (int i = 0; i < 26; i++) {
            int x = FIELD_OFFSET_X + 4 + seeded.nextInt(BOARD_WIDTH_PX - 8);
            int y = BORDER + 3 + seeded.nextInt(SKY_HEIGHT - 8);
            double phase = seeded.nextDouble() * Math.PI * 2;
            boolean twinkles = seeded.nextInt(3) == 0;
            int size = seeded.nextInt(4) == 0 ? 3 : 2;
            int alpha = twinkles ? (int) (120 + 135 * (0.5 + 0.5 * Math.sin(now / 350.0 + phase))) : 200;
            g2.setColor(new Color(STAR_COLOR.getRed(), STAR_COLOR.getGreen(), STAR_COLOR.getBlue(), alpha));
            g2.fillRect(x, y, size, size);
        }
    }

    private void createFireflies() {
        Random seeded = new Random(2026);
        for (int i = 0; i < FIREFLY_COUNT; i++) {
            double x = FIELD_OFFSET_X + 15 + seeded.nextDouble() * (BOARD_WIDTH_PX - 30);
            double y = BORDER + 20 + seeded.nextDouble() * (SKY_HEIGHT + BOARD_HEIGHT_PX - 40);
            fireflies.add(new Firefly(x, y,
                    seeded.nextDouble() * Math.PI * 2,
                    0.6 + seeded.nextDouble() * 0.8,
                    18 + seeded.nextDouble() * 26,
                    12 + seeded.nextDouble() * 20));
        }
    }

    /**
     * Fireflies drifting over the whole field. Each one wanders on a slow
     * looping path and pulses on and off, with a soft glow round a bright
     * pixel core. Drawn above the moles but they're tiny and translucent,
     * so they never hide one.
     */
    private void drawFireflies(Graphics2D g2, long now) {
        Shape oldClip = g2.getClip();
        g2.clipRect(BORDER, BORDER, BOARD_WIDTH_PX, SKY_HEIGHT + BOARD_HEIGHT_PX);
        Object oldAa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double t = now / 1000.0;
        for (Firefly f : fireflies) {
            double s = f.speed;
            double x = f.baseX + Math.sin(t * 0.7 * s + f.phase) * f.wanderX
                    + Math.sin(t * 0.23 * s + f.phase * 2) * f.wanderX * 0.5;
            double y = f.baseY + Math.cos(t * 0.9 * s + f.phase * 1.3) * f.wanderY;
            double pulse = Math.max(0, Math.sin(t * 2.2 * s + f.phase)); // dark half the time, like real ones
            double glow = 0.25 + 0.75 * pulse * pulse;

            int r = FIREFLY_GLOW.getRed(), gr = FIREFLY_GLOW.getGreen(), b = FIREFLY_GLOW.getBlue();
            g2.setColor(new Color(r, gr, b, (int) (45 * glow)));
            g2.fillOval((int) x - 9, (int) y - 9, 18, 18);
            g2.setColor(new Color(r, gr, b, (int) (90 * glow)));
            g2.fillOval((int) x - 5, (int) y - 5, 10, 10);
            g2.setColor(new Color(255, 255, 210, (int) (255 * glow)));
            g2.fillRect((int) x - 1, (int) y - 1, 3, 3);
        }

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
        g2.setClip(oldClip);
    }

    /** New scenery fades in out of darkness for THEME_FADE_MS after a level change. */
    private void drawThemeFade(Graphics2D g2, long now) {
        if (themeFadeStartMs < 0) return;
        double f = (now - themeFadeStartMs) / (double) THEME_FADE_MS;
        if (f >= 1) {
            themeFadeStartMs = -1;
            return;
        }
        g2.setColor(new Color(0, 0, 0, (int) (230 * (1 - f))));
        g2.fillRect(BORDER, BORDER, BOARD_WIDTH_PX, SKY_HEIGHT + BOARD_HEIGHT_PX);
    }

    /** Icy tint and frame while TIME_FREEZE is running, so it's obvious the clock is stopped. */
    private void drawFreezeTint(Graphics2D g2, long now) {
        if (now >= freezeUntilMs || intermissionActive) return;
        Color ice = MoleType.TIME_FREEZE.belly;
        g2.setColor(new Color(ice.getRed(), ice.getGreen(), ice.getBlue(), 38));
        g2.fillRect(BORDER, BORDER, BOARD_WIDTH_PX, SKY_HEIGHT + BOARD_HEIGHT_PX);
        g2.setColor(new Color(ice.getRed(), ice.getGreen(), ice.getBlue(), 200));
        for (int i = 0; i < 3; i++) {
            g2.drawRect(BORDER + i, BORDER + i, BOARD_WIDTH_PX - 1 - 2 * i, SKY_HEIGHT + BOARD_HEIGHT_PX - 1 - 2 * i);
        }
    }

    private BufferedImage grassFor(Theme theme) {
        return grassByTheme.computeIfAbsent(theme, th -> createGrassTexture(BOARD_WIDTH_PX, BOARD_HEIGHT_PX, th));
    }

    private BufferedImage createGrassTexture(int w, int h, Theme theme) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(theme.grassDark);
        g.fillRect(0, 0, w, h);
        Random seeded = new Random(42); // fixed seed => stable pattern, not noisy
        g.setColor(theme.grassLight);
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

    private static Color lerp(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static double sq(double v) {
        return v * v;
    }

    private int drawPixelHole(Graphics2D g2, Rectangle cell, Theme theme) {
        int cx = cell.x + cell.width / 2;
        int cy = holeCenterY(cell.y);

        drawPixelEllipse(g2, cx, cy, HOLE_W, HOLE_H, theme.holeRim);
        drawPixelEllipse(g2, cx, cy, HOLE_W - 22, HOLE_H - 14, theme.holeInside);

        int openingY = holeOpeningY(cell.y);
        g2.setColor(theme.grassLight);
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
            double progress = Math.max(0.0, Math.min(1.0,
                    (now - text.startTime) / (double) FLOAT_DURATION_MS));

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
        long sinceHit = mole.msSinceHit(now);
        boolean landed = mole.isSquashed() && sinceHit >= IMPACT_DELAY_MS;

        if (!landed) {
            // Not hit yet, or hit but the hammer is still on its way down.
            drawMoleSprite(g2, baseX, currentY, MOLE_SPRITE, mole.getType());
            g2.setClip(oldClip);
            return;
        }

        // Squash: flatten into the hole, anchored at the hole opening, with a
        // quick wobble as it settles. X eyes while squashed.
        long sinceImpact = sinceHit - IMPACT_DELAY_MS;
        double k = Math.min(1.0, sinceImpact / (double) SQUASH_IN_MS);
        double wobble = Math.sin(sinceImpact / 28.0) * 0.06 * Math.max(0, 1 - sinceImpact / 300.0);
        double sy = 1 + (SQUASH_Y - 1) * easeOut(k) + wobble;
        double sx = 1 + (SQUASH_X - 1) * easeOut(k) - wobble;
        double anchorX = baseX + spriteW / 2.0;
        double anchorY = holeOpeningY + 6;

        AffineTransform before = g2.getTransform();
        g2.translate(anchorX, anchorY);
        g2.scale(sx, sy);
        g2.translate(-anchorX, -anchorY);
        drawMoleSprite(g2, baseX, currentY, MOLE_HIT_SPRITE, mole.getType());
        g2.setTransform(before);
        g2.setClip(oldClip);

        // Head top of the squashed sprite, in board pixels.
        double headY = anchorY - (anchorY - currentY) * sy;
        drawImpactBurst(g2, anchorX, headY, sinceImpact);
        if (!mole.isSinking()) {
            drawDizzyStars(g2, anchorX, headY - 6, now);
        }
    }

    /** Pixel dashes flying out from the head for a moment when the hammer lands. */
    private void drawImpactBurst(Graphics2D g2, double cx, double cy, long sinceImpact) {
        final long burstMs = 220;
        if (sinceImpact > burstMs) return;
        double t = sinceImpact / (double) burstMs;
        int alpha = (int) (255 * (1 - t));
        g2.setColor(new Color(SUN_COLOR.getRed(), SUN_COLOR.getGreen(), SUN_COLOR.getBlue(), alpha));
        for (int i = 0; i < 8; i++) {
            double a = Math.PI + i * Math.PI / 7; // fan across the top half
            double r = 18 + t * 26;
            int x = (int) Math.round(cx + Math.cos(a) * r * 1.4);
            int y = (int) Math.round(cy + 10 + Math.sin(a) * r);
            g2.fillRect(x - 2, y - 2, 5, 5);
        }
    }

    /** Three little stars circling the squashed mole's head. */
    private void drawDizzyStars(Graphics2D g2, double cx, double cy, long now) {
        for (int i = 0; i < 3; i++) {
            double a = now / 140.0 + i * (2 * Math.PI / 3);
            int x = (int) Math.round(cx + Math.cos(a) * 26);
            int y = (int) Math.round(cy + Math.sin(a) * 7);
            g2.setColor(MOLE_OUTLINE);
            g2.fillRect(x - 4, y - 1, 9, 3);
            g2.fillRect(x - 1, y - 4, 3, 9);
            g2.setColor(SUN_COLOR);
            g2.fillRect(x - 3, y, 7, 1);
            g2.fillRect(x, y - 3, 1, 7);
            g2.fillRect(x - 1, y - 1, 3, 3);
        }
    }

    /**
     * Label floating above a power-up mole ("x2", "+10s", "FREEZE"), bobbing
     * gently so it catches the eye. Only shown once the mole is mostly up.
     */
    private void drawPowerUpBadge(Graphics2D g2, Mole mole, Rectangle cell, int holeOpeningY, long now) {
        if (mole.popFraction(now, POP_ANIM_MS, SINK_ANIM_MS) < 0.6) return;
        MoleType type = mole.getType();
        String label = switch (type) {
            case DOUBLE_POINTS -> "x2";
            case EXTRA_TIME -> "+" + (EXTRA_TIME_MS / 1000) + "s";
            case TIME_FREEZE -> "FREEZE";
            case NORMAL -> "";
        };

        Font oldFont = g2.getFont();
        g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(label) + 12;
        int h = 18;
        int bob = (int) Math.round(Math.sin(now / 160.0) * 2);
        int x = cell.x + (cell.width - w) / 2;
        int y = cell.y + 10 + bob;

        g2.setColor(MOLE_OUTLINE);
        g2.fillRect(x - 2, y - 2, w + 4, h + 4);
        g2.setColor(type.body);
        g2.fillRect(x, y, w, h);
        g2.setColor(type.belly);
        g2.fillRect(x, y, w, 3); // top highlight
        g2.setColor(MOLE_OUTLINE);
        g2.drawString(label, x + 6, y + h - 5);
        g2.setFont(oldFont);
    }

    /**
     * Strip under the board: current level, progress toward this level's
     * target, and any active power-ups with seconds left.
     */
    private void drawHud(Graphics2D g2, long now) {
        if (!running) return;
        Level level = levelManager.getCurrentLevel();
        Font oldFont = g2.getFont();
        g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        FontMetrics fm = g2.getFontMetrics();
        int textY = HUD_Y + 15;

        g2.setColor(CABINET_TRIM);
        g2.drawString("LV" + level.getNumber(), BORDER, textY);

        int barX = BORDER + 36;
        int barY = HUD_Y + 4;
        int barW = 190;
        int barH = 14;

        // Time left in this level (during the level-complete screen, the next level's full clock).
        long msLeft = intermissionActive ? level.getDurationMs() : Math.max(0, roundEndAtMs - now);
        boolean lowTime = !intermissionActive && msLeft <= LOW_TIME_MS;
        boolean flashOn = (now / LOW_TIME_FLASH_MS) % 2 == 0; // blink on/off when time is low

        if (level.hasTarget()) {
            double frac = Math.min(1.0, levelScore / (double) level.getTargetScore());
            g2.setColor(TIMER_BAR_BG);
            g2.fillRect(barX, barY, barW, barH);
            Color fill;
            if (frac >= 1) fill = Color.GREEN;
            else if (lowTime) fill = flashOn ? LOW_TIME_RED : LOW_TIME_RED_DIM; // hurry up!
            else fill = TIMER_BAR_FILL;
            g2.setColor(fill);
            g2.fillRect(barX, barY, (int) Math.round(barW * frac), barH);
            g2.setColor(MOLE_OUTLINE);
            g2.drawRect(barX, barY, barW, barH);
            String txt = "TARGET " + levelScore + "/" + level.getTargetScore();
            int tx = barX + (barW - fm.stringWidth(txt)) / 2;
            g2.setColor(Color.BLACK); // shadow so it reads on both the yellow and dark halves
            g2.drawString(txt, tx + 1, textY + 1);
            g2.setColor(Color.WHITE);
            g2.drawString(txt, tx, textY);
            if (lowTime) {
                // Red frame round the bar too, so it reads even before the fill gets there.
                g2.setColor(LOW_TIME_RED);
                g2.drawRect(barX - 1, barY - 1, barW + 2, barH + 2);
            }
        } else {
            g2.setColor(SUN_COLOR);
            g2.drawString("FINAL LEVEL!", barX, textY);
        }

        // Countdown right after the bar: white normally, flashing red in the last LOW_TIME_MS.
        String timeTxt = (int) Math.ceil(msLeft / 1000.0) + "s";
        int timeX = barX + barW + 8;
        if (now < freezeUntilMs && !intermissionActive) {
            g2.setColor(MoleType.TIME_FREEZE.belly); // icy while frozen
        } else if (lowTime) {
            g2.setColor(flashOn ? LOW_TIME_RED : Color.WHITE);
        } else {
            g2.setColor(Color.WHITE);
        }
        g2.drawString(timeTxt, timeX, textY);

        // Active effects, right-aligned.
        int right = DESIGN_WIDTH - BORDER;
        right = drawEffectChip(g2, fm, right, barY, barH, textY, MoleType.TIME_FREEZE, "FROZEN", freezeUntilMs, now);
        drawEffectChip(g2, fm, right, barY, barH, textY, MoleType.DOUBLE_POINTS, "x2", doublePointsUntilMs, now);
        g2.setFont(oldFont);
    }

    /** Draws one "label Ns" chip ending at rightX if its effect is active; returns the new right edge. */
    private int drawEffectChip(Graphics2D g2, FontMetrics fm, int rightX, int y, int h, int textY,
                               MoleType type, String label, long untilMs, long now) {
        if (now >= untilMs) return rightX;
        int secs = (int) Math.ceil((untilMs - now) / 1000.0);
        String txt = label + " " + secs + "s";
        int w = fm.stringWidth(txt) + 10;
        int x = rightX - w;
        g2.setColor(type.body);
        g2.fillRect(x, y, w, h);
        g2.setColor(MOLE_OUTLINE);
        g2.drawRect(x, y, w, h);
        g2.drawString(txt, x + 5, textY);
        return x - 6;
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

    private void drawMoleSprite(Graphics2D g2, int originX, int originY, String[] sprite, MoleType type) {
        for (int row = 0; row < MOLE_SPRITE_ROWS; row++) {
            String line = sprite[row];
            for (int col = 0; col < MOLE_SPRITE_COLS; col++) {
                char ch = line.charAt(col);
                Color c;
                if (ch == 'B' && type.body != null) c = type.body;        // power-up fur
                else if (ch == 'L' && type.belly != null) c = type.belly; // and face
                else c = colorFor(ch);
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

    /** Dims the board and shows a "level complete" message that auto-advances after a few seconds. */
    private void drawIntermissionOverlay(Graphics2D g2, long now) {
        g2.setColor(OVERLAY_TINT);
        g2.fillRect(BORDER, BORDER, BOARD_WIDTH_PX, SKY_HEIGHT + BOARD_HEIGHT_PX);

        int centerX = DESIGN_WIDTH / 2;

        g2.setFont(OVERLAY_TITLE_FONT);
        g2.setColor(Color.WHITE);
        drawCenteredString(g2, "CONGRATULATIONS! LEVEL COMPLETE.", centerX, FIELD_OFFSET_Y + 60);

        g2.setFont(OVERLAY_TEXT_FONT);
        g2.setColor(SUN_COLOR);
        Level next = levelManager.getCurrentLevel();
        drawCenteredString(g2, "STARTING LEVEL " + next.getNumber(), centerX, FIELD_OFFSET_Y + 96);
        g2.setColor(Color.WHITE);
        String goal = next.hasTarget()
                ? "NEXT TARGET: " + next.getTargetScore() + " PTS IN " + (next.getDurationMs() / 1000) + "s"
                : "FINAL LEVEL: " + (next.getDurationMs() / 1000) + "s TO SCORE ALL YOU CAN";
        drawCenteredString(g2, goal, centerX, FIELD_OFFSET_Y + 120);

        // A shrinking bar shows how much longer until the automatic advance.
        long msLeft = Math.max(0, intermissionEndsAtMs - now);
        double remainingFraction = Math.min(1.0, msLeft / (double) INTERMISSION_DISPLAY_MS);
        int barW = 220;
        int barH = 14;
        int barX = centerX - barW / 2;
        int barY = FIELD_OFFSET_Y + BOARD_HEIGHT_PX / 2 + 10;

        g2.setColor(TIMER_BAR_BG);
        g2.fillRect(barX, barY, barW, barH);
        g2.setColor(SUN_COLOR);
        g2.fillRect(barX, barY, (int) Math.round(barW * remainingFraction), barH);
        g2.setColor(MOLE_OUTLINE);
        g2.drawRect(barX, barY, barW, barH);
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
    /**
     * Hammer angle for the smash animation. Idle sits at HAMMER_IDLE_ANGLE;
     * after a hit it winds up, slams down (accelerating, like a real swing),
     * holds on the mole for a beat, then eases back to idle.
     */
    private double hammerAngle(long now) {
        if (cursorSwingStartMs < 0) return HAMMER_IDLE_ANGLE;
        double t = (now - cursorSwingStartMs) / (double) CURSOR_SWING_MS;
        if (t >= 1) {
            cursorSwingStartMs = -1;
            return HAMMER_IDLE_ANGLE;
        }
        if (t < SWING_WINDUP_END) {
            double k = easeOut(t / SWING_WINDUP_END);
            return HAMMER_IDLE_ANGLE + (HAMMER_WINDUP_ANGLE - HAMMER_IDLE_ANGLE) * k;
        }
        if (t < SWING_SLAM_END) {
            double k = (t - SWING_WINDUP_END) / (SWING_SLAM_END - SWING_WINDUP_END);
            return HAMMER_WINDUP_ANGLE + (HAMMER_SLAM_ANGLE - HAMMER_WINDUP_ANGLE) * k * k; // ease-in: speeds up into the hit
        }
        if (t < SWING_HOLD_END) {
            double k = (t - SWING_SLAM_END) / (SWING_HOLD_END - SWING_SLAM_END);
            return HAMMER_SLAM_ANGLE - Math.sin(k * Math.PI) * Math.toRadians(8); // small rebound off the mole
        }
        double k = easeOut((t - SWING_HOLD_END) / (1 - SWING_HOLD_END));
        return HAMMER_SLAM_ANGLE + (HAMMER_IDLE_ANGLE - HAMMER_SLAM_ANGLE) * k;
    }

    private static double easeOut(double k) {
        return 1 - (1 - k) * (1 - k);
    }

    private void drawHammerCursor(Graphics2D g2, int x, int y, double angle) {
        AffineTransform old = g2.getTransform();
        g2.translate(x, y);
        g2.rotate(angle);

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
        String fixLine = mouseMode
                ? (mousePoint == null ? "mouse: off board" : "mouse: px " + mousePoint.x + ", " + mousePoint.y)
                : sensorInputBridge.getLastFixDescription();
        g2.drawString(fixLine, FIELD_OFFSET_X + 6, BORDER + 44);
    }

    /** Reads column/row straight from the bridge, so this always agrees with checkForHit(). */
    private String describeCell() {
        int col = playerColumn();
        int row = playerRow();
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