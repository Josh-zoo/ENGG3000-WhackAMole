package whackamole;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * Top-level window. Only knows about GamePanel through its public API and
 * the GameListener interface, so the status bar (or a future scoreboard,
 * pause menu, sound toggle, etc.) can be changed without touching game logic.
 */
public class GameFrame extends JFrame {

    private final JLabel scoreLabel = new JLabel("Score: 0");
    private final JLabel levelLabel = new JLabel("Level: 1");
    private final JLabel timeLabel = new JLabel("Time: 60");
    private final JButton startButton = new JButton("Start");

    public GameFrame() {
        super("Whack-a-Mole");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        GamePanel gamePanel = new GamePanel();

        Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, 14);
        scoreLabel.setFont(labelFont);
        levelLabel.setFont(labelFont);
        timeLabel.setFont(labelFont);

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 8));
        statusBar.add(scoreLabel);
        statusBar.add(levelLabel);
        statusBar.add(timeLabel);
        statusBar.add(startButton);

        add(statusBar, BorderLayout.NORTH);
        add(gamePanel, BorderLayout.CENTER);

        gamePanel.addGameListener(new GameListener() {
            @Override
            public void onScoreChanged(int newScore) {
                SwingUtilities.invokeLater(() -> scoreLabel.setText("Score: " + newScore));
            }

            @Override
            public void onLevelChanged(int newLevelNumber) {
                SwingUtilities.invokeLater(() -> levelLabel.setText("Level: " + newLevelNumber));
            }

            @Override
            public void onTimeChanged(int msRemaining) {
                SwingUtilities.invokeLater(() -> timeLabel.setText("Time: " + (msRemaining / 1000)));
            }

            @Override
            public void onGameOver(int finalScore) {
                SwingUtilities.invokeLater(() -> {
                    startButton.setText("Play Again");
                    startButton.setEnabled(true);
                    JOptionPane.showMessageDialog(GameFrame.this,
                            "Game over! Final score: " + finalScore);
                });
            }
        });

        startButton.addActionListener(e -> {
            startButton.setEnabled(false);
            startButton.setText("Playing...");
            gamePanel.startGame();
        });

        pack();
        setLocationRelativeTo(null);
        setResizable(false);
    }
}
