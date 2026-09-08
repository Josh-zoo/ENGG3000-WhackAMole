package whackamole;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;

public class GameFrame extends JFrame {

    private static final Color CABINET_BG = new Color(18, 18, 24);
    private static final Color READOUT_BG = new Color(28, 28, 36);
    private static final Color SCORE_COLOR = new Color(255, 191, 0);   // amber
    private static final Color LEVEL_COLOR = new Color(0, 229, 255);   // cyan
    private static final Color TIME_COLOR = new Color(255, 68, 68);    // red
    private static final Color BUTTON_BG = new Color(224, 178, 66);    // gold, matches board trim
    private static final Font RETRO_FONT = new Font(Font.MONOSPACED, Font.BOLD, 18);

    private final JLabel scoreLabel = new JLabel("SCORE 0000");
    private final JLabel levelLabel = new JLabel("LVL 1");
    private final JLabel timeLabel = new JLabel("TIME 60");
    private final JButton startButton = new JButton("START");

    public GameFrame() {
        super("Whack-a-Mole");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(CABINET_BG);

        GamePanel gamePanel = new GamePanel();

        styleReadout(scoreLabel, SCORE_COLOR);
        styleReadout(levelLabel, LEVEL_COLOR);
        styleReadout(timeLabel, TIME_COLOR);
        styleButton(startButton);

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 14));
        statusBar.setBackground(CABINET_BG);
        statusBar.setBorder(new EmptyBorder(4, 8, 4, 8));
        statusBar.add(scoreLabel);
        statusBar.add(levelLabel);
        statusBar.add(timeLabel);
        statusBar.add(startButton);

        JPanel boardWrapper = new JPanel();
        boardWrapper.setBackground(CABINET_BG);
        boardWrapper.setBorder(new EmptyBorder(0, 12, 12, 12));
        boardWrapper.add(gamePanel);

        add(statusBar, BorderLayout.NORTH);
        add(boardWrapper, BorderLayout.CENTER);

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
                    startButton.setText("REPLAY");
                    startButton.setEnabled(true);
                    showGameOverDialog(finalScore);
                });
            }
        });

        startButton.addActionListener(e -> {
            startButton.setEnabled(false);
            startButton.setText("...");
            gamePanel.startGame();
        });

        pack();
        setLocationRelativeTo(null);
        setResizable(false);
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

    private void styleButton(JButton button) {
        button.setFont(RETRO_FONT);
        button.setForeground(Color.BLACK);
        button.setBackground(BUTTON_BG);
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        Border outer = BorderFactory.createMatteBorder(3, 3, 3, 3, Color.BLACK);
        Border inner = new EmptyBorder(6, 18, 6, 18);
        button.setBorder(new CompoundBorder(outer, inner));
    }
}