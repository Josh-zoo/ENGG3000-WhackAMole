package whackamole;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GamePanel extends JPanel {

    // ---- board layout ----------------------------------------------------
    private static final int GRID_ROWS = 3;
    private static final int GRID_COLS = 3;
    private static final int CELL_W = 132;
    private static final int CELL_H = 148;
    private static final int CELL_GAP = 14;
    private static final int BORDER = 20; // thickness of the frame
    private static final int SKY_HEIGHT = 56; // strip of sky above the grass/holes

    // ---- pixel-art tuning --------------------------------------------------
    private static final int PIXEL = 6; // size (in screen px) of one "pixel" block
    private static final int HOLE_W = 104;
    private static final int HOLE_H = 44;
    private static final int HOLE_BOTTOM_MARGIN = 10; // gap from cell bottom to hole center

    // 16x14 pixel-art mole sprite. Each character is one
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
    private static final Color SKY_COLOR = new Color(120, 200, 255);
    private static final Color CLOUD_COLOR = Color.WHITE;
    private static final Color SUN_COLOR = new Color(255, 221, 89);
    private static final Color TIMER_BAR_BG = new Color(40, 30, 20);
    private static final Color TIMER_BAR_FILL = new Color(255, 215, 0);

    // ---- timing --------------------------------------------------------------
    private static final int GAME_DURATION_MS = 60_000; // one round = 60 seconds
    private static final int FRAME_DELAY_MS = 16;         // ~60 fps game loop
    private static final long POP_ANIM_MS = 110;           // rise animation duration
    private static final long SINK_ANIM_MS = 140;          // sink animation duration
    private static final int RISE_DISTANCE = 42;            // vertical travel, in px
    private static final int TIMER_BAR_WIDTH = 90;
    private static final int TIMER_BAR_HEIGHT = 6;


    private final List<Mole> moles = new ArrayList<>();
    private final List<Rectangle> cellBounds = new ArrayList<>();
    private final List<GameListener> listeners = new ArrayList<>();
    private final Random random = new Random();
    private final LevelManager levelManager = new LevelManager();
    private final Timer loopTimer;

    private final int boardWidth;
    private final int boardHeight;
    private final BufferedImage grassBackground;

    private Point mousePoint = new Point(-1, -1);
    private int score = 0;
    private long nextSpawnAtMs = 0;
    private long roundEndAtMs = 0;
    private boolean running = false;

    public GamePanel() {
        boardWidth = GRID_COLS * CELL_W + (GRID_COLS - 1) * CELL_GAP;
        boardHeight = SKY_HEIGHT + GRID_ROWS * CELL_H + (GRID_ROWS - 1) * CELL_GAP;
        setPreferredSize(new Dimension(boardWidth + 2 * BORDER, boardHeight + 2 * BORDER));
        setBackground(CABINET_WOOD);

        grassBackground = createGrassTexture(boardWidth, boardHeight - SKY_HEIGHT);

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mousePoint = e.getPoint();
            }
        });

        loopTimer = new Timer(FRAME_DELAY_MS, e -> tick());
        layoutHoles();
    }

    public void addGameListener(GameListener listener) {
        listeners.add(listener);
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

    // ---- layout --------------------------------------------------------------

    private void layoutHoles() {
        moles.clear();
        cellBounds.clear();
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int cellX = BORDER + col * (CELL_W + CELL_GAP);
                int cellY = BORDER + SKY_HEIGHT + row * (CELL_H + CELL_GAP);
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

    // ---- game loop -----------------------------------------------------------

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
        finalizeSinkingMoles(now);

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

    // ---- rendering -------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        drawCabinetBorder(g2);
        drawSky(g2);
        g2.drawImage(grassBackground, BORDER, BORDER + SKY_HEIGHT, null);

        long now = System.currentTimeMillis();
        for (int i = 0; i < moles.size(); i++) {
            Mole mole = moles.get(i);
            Rectangle cell = cellBounds.get(i);
            int holeOpeningY = drawPixelHole(g2, cell);
            if (mole.isVisible()) {
                drawPoppedMole(g2, mole, cell, holeOpeningY, now);
                drawTimerBar(g2, cell, mole, now);
            }
        }
    }

    private void drawCabinetBorder(Graphics2D g2) {
        g2.setColor(CABINET_WOOD);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(CABINET_TRIM);
        g2.fillRect(BORDER - 6, BORDER - 6, boardWidth + 12, boardHeight + 12);
    }

    /** Sky band across the top of the board: flat color plus a pixel-art sun and clouds. */
    private void drawSky(Graphics2D g2) {
        g2.setColor(SKY_COLOR);
        g2.fillRect(BORDER, BORDER, boardWidth, SKY_HEIGHT);
        drawPixelEllipse(g2, BORDER + 40, BORDER + 26, 34, 34, SUN_COLOR);
        drawCloud(g2, BORDER + 120, BORDER + 12);
        drawCloud(g2, BORDER + 250, BORDER + 28);
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
        drawMoleSprite(g2, baseX, currentY);
        g2.setClip(oldClip);
    }


    /** Draws a shrinking yellow countdown bar just under the hole, showing time left before it hides. */
    private void drawTimerBar(Graphics2D g2, Rectangle cell, Mole mole, long now) {
        double remaining = mole.remainingFraction(now);
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

    private void drawMoleSprite(Graphics2D g2, int originX, int originY) {
        for (int row = 0; row < MOLE_SPRITE_ROWS; row++) {
            String line = MOLE_SPRITE[row];
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
            default -> null;
        };
    }

    // ---- listener notifications -------------------------------------------------

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