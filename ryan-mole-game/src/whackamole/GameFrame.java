package whackamole;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Random;

/**
 * Top-level window styled like a retro arcade cabinet, built around three
 * CardLayout screens:
 *   "menu"  -> large PLAY / RULES buttons
 *   "rules" -> how-to-play text with a BACK button
 *   "game"  -> the LED-style status bar + the board itself
 *
 * GameFrame only talks to GamePanel through its public API and the
 * GameListener interface, so this screen flow can change independently of
 * game logic.
 */
public class GameFrame extends JFrame {

    private static final Color CABINET_BG = new Color(18, 18, 24);
    private static final Color READOUT_BG = new Color(28, 28, 36);
    private static final Color SCORE_COLOR = new Color(255, 191, 0);   // amber
    private static final Color LEVEL_COLOR = new Color(0, 229, 255);   // cyan
    private static final Color TIME_COLOR = new Color(255, 68, 68);    // red
    private static final Color BUTTON_BG = new Color(224, 178, 66);    // gold, matches board trim
    private static final Color BACK_BUTTON_BG = new Color(170, 170, 180); // neutral gray for Back
    private static final Font RETRO_FONT = new Font(Font.MONOSPACED, Font.BOLD, 18);
    private static final Font TITLE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 40);
    private static final Font MENU_BUTTON_FONT = new Font(Font.MONOSPACED, Font.BOLD, 26);
    private static final Font RULES_TEXT_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 15);

    private static final String CARD_MENU = "menu";
    private static final String CARD_RULES = "rules";
    private static final String CARD_GAME = "game";

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private final JLabel scoreLabel = new JLabel("SCORE 0000");
    private final JLabel levelLabel = new JLabel("LVL 1");
    private final JLabel timeLabel = new JLabel("TIME 30");
    private final GamePanel gamePanel;

    public GameFrame() {
        this(false);
    }

    public GameFrame(boolean simulate) {
        super(simulate ? "Whack-a-Mole (SIMULATED SENSORS)" : "Whack-a-Mole");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(CABINET_BG);

        gamePanel = new GamePanel(simulate);

        cards.setBackground(CABINET_BG);
        cards.add(buildMenuPanel(), CARD_MENU);
        cards.add(buildRulesPanel(), CARD_RULES);
        cards.add(buildGamePanel(gamePanel), CARD_GAME);
        add(cards, BorderLayout.CENTER);

        gamePanel.addGameListener(new GameListener() {
            @Override
            public void onScoreChanged(int newScore) {
                SwingUtilities.invokeLater(() ->
                        scoreLabel.setText(String.format("SCORE %04d", newScore)));
            }

            @Override
            public void onLevelChanged(int newLevelNumber) {
                SwingUtilities.invokeLater(() -> levelLabel.setText("LVL " + newLevelNumber));
            }

            @Override
            public void onTimeChanged(int msRemaining) {
                SwingUtilities.invokeLater(() ->
                        timeLabel.setText("TIME " + (msRemaining / 1000)));
            }

            @Override
            public void onGameOver(int finalScore) {
                SwingUtilities.invokeLater(() -> {
                    showGameOverDialog(finalScore);
                    cardLayout.show(cards, CARD_MENU);
                });
            }
        });

        cardLayout.show(cards, CARD_MENU);

        pack();
        setLocationRelativeTo(null);
        setResizable(false);
    }

    // ---- screens -------------------------------------------------------------

    private JPanel buildMenuPanel() {
        JPanel panel = new TitleBackgroundPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(40, 40, 40, 40));

        JLabel title = new JLabel("WhackAMole");
        title.setFont(TITLE_FONT);
        title.setForeground(BUTTON_BG);
        title.setOpaque(true);
        title.setBackground(new Color(0, 0, 0, 120));
        title.setBorder(new EmptyBorder(8, 20, 8, 20));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton playButton = new JButton("PLAY");
        styleMenuButton(playButton, BUTTON_BG);
        playButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        playButton.addActionListener(e -> {
            cardLayout.show(cards, CARD_GAME);
            gamePanel.startGame();
        });

        JButton rulesButton = new JButton("RULES");
        styleMenuButton(rulesButton, BACK_BUTTON_BG);
        rulesButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        rulesButton.addActionListener(e -> cardLayout.show(cards, CARD_RULES));

        panel.add(Box.createVerticalStrut(100));
        panel.add(title);
        panel.add(Box.createVerticalStrut(50));
        panel.add(playButton);
        panel.add(Box.createVerticalStrut(24));
        panel.add(rulesButton);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel buildRulesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CABINET_BG);
        panel.setBorder(new EmptyBorder(30, 40, 30, 40));

        JLabel title = new JLabel("HOW TO PLAY");
        title.setFont(TITLE_FONT.deriveFont(28f));
        title.setForeground(LEVEL_COLOR);
        title.setHorizontalAlignment(SwingConstants.CENTER);

        JTextArea rulesText = new JTextArea(
            "An ultrasonic sensor rig tracks where you stand on the mat,\n" +
            "splitting the field into a 3x3 grid of zones.\n\n" +
            "Moles pop up at random in the 9 holes. Step into the zone\n" +
            "under an active mole before its yellow timer bar runs out\n" +
            "to score a hit -- you'll see it get bonked with a hammer!\n\n" +
            "Miss the timer and the mole just slips back underground.\n\n" +
            "Clearing a level's score threshold pauses the game with a\n" +
            "LEVEL COMPLETE screen. Hover over the CONTINUE button\n" +
            "(just stand in that zone) to start the next level fresh,\n" +
            "with its own 30-second clock.\n\n" +
            "Moles get faster and more numerous each level. If the clock\n" +
            "runs out before you clear a level, the game ends.\n\n" +
            "Stay out of the dead zone near the screen -- a red banner\n" +
            "and a beep will warn you if you get too close!"
        );
        rulesText.setFont(RULES_TEXT_FONT);
        rulesText.setForeground(new Color(230, 230, 230));
        rulesText.setBackground(READOUT_BG);
        rulesText.setEditable(false);
        rulesText.setLineWrap(false);
        rulesText.setBorder(new CompoundBorder(
                new LineBorder(LEVEL_COLOR.darker(), 2),
                new EmptyBorder(16, 20, 16, 20)));

        JButton backButton = new JButton("BACK");
        styleMenuButton(backButton, BACK_BUTTON_BG);
        backButton.addActionListener(e -> cardLayout.show(cards, CARD_MENU));

        JPanel backWrapper = new JPanel();
        backWrapper.setBackground(CABINET_BG);
        backWrapper.setBorder(new EmptyBorder(20, 0, 0, 0));
        backWrapper.add(backButton);

        JPanel textWrapper = new JPanel(new BorderLayout());
        textWrapper.setBackground(CABINET_BG);
        textWrapper.setBorder(new EmptyBorder(20, 0, 0, 0));
        textWrapper.add(rulesText, BorderLayout.CENTER);

        panel.add(title, BorderLayout.NORTH);
        panel.add(textWrapper, BorderLayout.CENTER);
        panel.add(backWrapper, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildGamePanel(GamePanel gamePanel) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CABINET_BG);

        styleReadout(scoreLabel, SCORE_COLOR);
        styleReadout(levelLabel, LEVEL_COLOR);
        styleReadout(timeLabel, TIME_COLOR);

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 14));
        statusBar.setBackground(CABINET_BG);
        statusBar.setBorder(new EmptyBorder(4, 8, 4, 8));
        statusBar.add(scoreLabel);
        statusBar.add(levelLabel);
        statusBar.add(timeLabel);

        JPanel boardWrapper = new JPanel();
        boardWrapper.setBackground(CABINET_BG);
        boardWrapper.setBorder(new EmptyBorder(0, 12, 12, 12));
        boardWrapper.add(gamePanel);

        panel.add(statusBar, BorderLayout.NORTH);
        panel.add(boardWrapper, BorderLayout.CENTER);
        return panel;
    }

/**
 * The menu's backdrop: same sky/grass look as the game board (no holes),
 * with three mole sprites along the bottom edge — the rightmost one
 * mid-hammer-hit. Self-contained duplicate of GamePanel's pixel-art
 * palette/sprites/blit logic, since GamePanel's copies are private.
 */
    private static class TitleBackgroundPanel extends JPanel {
        private static final int PIXEL = 6;
        private static final Color SKY_COLOR = new Color(120, 200, 255);
        private static final Color CLOUD_COLOR = Color.WHITE;
        private static final Color SUN_COLOR = new Color(255, 221, 89);
        private static final Color GRASS_DARK = new Color(56, 128, 24);
        private static final Color GRASS_LIGHT = new Color(112, 176, 48);
        private static final Color MOLE_OUTLINE = new Color(33, 20, 12);
        private static final Color MOLE_BODY = new Color(158, 99, 61);
        private static final Color MOLE_BELLY = new Color(224, 178, 122);
        private static final Color MOLE_EYE = new Color(20, 14, 10);
        private static final Color MOLE_NOSE = new Color(214, 92, 122);
        private static final Color HAMMER_HEAD = new Color(140, 140, 150);
        private static final Color HAMMER_HANDLE = new Color(120, 80, 40);

        private static final int SPRITE_COLS = 16;
        private static final int SPRITE_ROWS = 14;

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

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

            int w = getWidth();
            int h = getHeight();
            int skyHeight = Math.max(80, h / 3);

            g2.setColor(SKY_COLOR);
            g2.fillRect(0, 0, w, skyHeight);
            drawSun(g2, w - 60, 40);
            drawCloud(g2, w / 2 - 90, 22);
            drawCloud(g2, 50, 38);

            g2.setColor(GRASS_DARK);
            g2.fillRect(0, skyHeight, w, h - skyHeight);
            Random rnd = new Random(42); // fixed seed => stable speckle pattern, not noisy
            g2.setColor(GRASS_LIGHT);
            for (int py = skyHeight; py < h; py += PIXEL) {
                for (int px = 0; px < w; px += PIXEL) {
                    if (rnd.nextInt(4) == 0) {
                        g2.fillRect(px, py, PIXEL, PIXEL);
                    }
                }
            }

            int moleW = SPRITE_COLS * PIXEL;
            int moleH = SPRITE_ROWS * PIXEL;
            int gap = 36;
            int totalW = moleW * 3 + gap * 2;
            int startX = (w - totalW) / 2;
            int moleY = h - moleH - 14;

            drawSprite(g2, MOLE_SPRITE, startX, moleY);
            drawSprite(g2, MOLE_SPRITE, startX + moleW + gap, moleY);
            drawSprite(g2, MOLE_HIT_SPRITE, startX + 2 * (moleW + gap), moleY);
        }

        private void drawSun(Graphics2D g2, int cx, int cy) {
            int rw = 17, rh = 17;
            g2.setColor(SUN_COLOR);
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

        private void drawCloud(Graphics2D g2, int x, int y) {
            g2.setColor(CLOUD_COLOR);
            g2.fillRect(x, y + PIXEL, PIXEL * 6, PIXEL * 2);
            g2.fillRect(x + PIXEL, y, PIXEL * 4, PIXEL);
            g2.fillRect(x + PIXEL, y + PIXEL * 3, PIXEL * 4, PIXEL);
        }

        private void drawSprite(Graphics2D g2, String[] sprite, int originX, int originY) {
            for (int row = 0; row < SPRITE_ROWS; row++) {
                String line = sprite[row];
                for (int col = 0; col < SPRITE_COLS; col++) {
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
    }

    private void showGameOverDialog(int finalScore) {
        JLabel message = new JLabel(String.format("GAME OVER — FINAL SCORE %04d", finalScore));
        message.setFont(RETRO_FONT);
        message.setForeground(SCORE_COLOR);
        message.setOpaque(true);
        message.setBackground(READOUT_BG);
        message.setBorder(new EmptyBorder(12, 16, 12, 16));
        JOptionPane.showMessageDialog(this, message, "Whack-a-Mole",
                JOptionPane.PLAIN_MESSAGE);
    }

    /** Gives a JLabel the look of an old arcade LED/segment readout. */
    private void styleReadout(JLabel label, Color color) {
        label.setFont(RETRO_FONT);
        label.setForeground(color);
        label.setBackground(READOUT_BG);
        label.setOpaque(true);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        Border line = new LineBorder(color.darker(), 2);
        Border padding = new EmptyBorder(6, 12, 6, 12);
        label.setBorder(new CompoundBorder(line, padding));
    }

    /** Gives a JButton a chunky, flat, pixel-art-style border instead of the platform default. */
    private void styleButton(JButton button, Color bg, Font font) {
        button.setFont(font);
        button.setForeground(Color.BLACK);
        button.setBackground(bg);
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        Border outer = BorderFactory.createMatteBorder(3, 3, 3, 3, Color.BLACK);
        Border inner = new EmptyBorder(6, 16, 6, 16);
        button.setBorder(new CompoundBorder(outer, inner));
    }

    /** Large central menu-style button: same chunky border, bigger font and footprint. */
    private void styleMenuButton(JButton button, Color bg) {
        styleButton(button, bg, MENU_BUTTON_FONT);
        button.setMaximumSize(new Dimension(260, 70));
        button.setPreferredSize(new Dimension(260, 70));
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
    }
}
