import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Random;

public class WhackAMoleConceptTester extends JFrame {
    private final JLabel levelValue = new JLabel("1");
    private final JLabel scoreValue = new JLabel("0");
    private final JLabel timerValue = new JLabel("4.0s");
    private final JLabel cursorValue = new JLabel("A1");
    private final JLabel moleValue = new JLabel("B2");
    private final JLabel messageValue = new JLabel("Press Start, then use Arrow Keys or WASD to move the cursor.");
    private final JLabel roundSpeedValue = new JLabel("4.0s");

    private final JButton startButton = new JButton("Start");
    private final JButton resetButton = new JButton("Reset");
    private final JButton[] cells = new JButton[9];

    private final Random random = new Random();
    private final Timer countdownTimer;

    private boolean running;
    private int score;
    private int level;
    private int cursorIndex;
    private int moleIndex;
    private int roundDurationMs;
    private long roundEndsAt;

    public WhackAMoleConceptTester() {
        super("Whack-a-Mole Concept Tester");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 720));
        setLocationRelativeTo(null);

        roundDurationMs = 4000;
        cursorIndex = 0;
        moleIndex = 4;

        setLayout(new BorderLayout(18, 18));
        getContentPane().setBackground(new Color(11, 18, 32));

        add(buildHeaderPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildSidebarPanel(), BorderLayout.EAST);

        registerKeyBindings();

        startButton.addActionListener(e -> startGame());
        resetButton.addActionListener(e -> resetGame());

        countdownTimer = new Timer(100, e -> updateTimer());
        resetGame();
    }

    private JPanel buildHeaderPanel() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(20, 24, 0, 24));

        JLabel eyebrow = new JLabel("Concept tester");
        eyebrow.setForeground(new Color(148, 163, 184));
        eyebrow.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        JLabel title = new JLabel("Whack-a-Mole Grid Prototype");
        title.setForeground(new Color(226, 232, 240));
        title.setFont(new Font("Segoe UI", Font.BOLD, 30));

        JLabel intro = new JLabel("This demo visualises the game loop behind the project: a player acts as a cursor, moves across a 3x3 grid, and must stand on the mole tile before the timer expires.");
        intro.setForeground(new Color(203, 213, 225));
        intro.setFont(new Font("Segoe UI", Font.PLAIN, 15));

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        statusBar.setOpaque(false);
        statusBar.add(makePill("Level", levelValue));
        statusBar.add(makePill("Score", scoreValue));
        statusBar.add(makePill("Timer", timerValue));
        statusBar.add(makePill("Cursor", cursorValue));
        statusBar.add(makePill("Mole", moleValue));

        header.add(eyebrow);
        header.add(Box.createVerticalStrut(8));
        header.add(title);
        header.add(Box.createVerticalStrut(10));
        header.add(intro);
        header.add(Box.createVerticalStrut(18));
        header.add(statusBar);
        return header;
    }

    private JPanel buildCenterPanel() {
        JPanel center = new JPanel(new BorderLayout(0, 14));
        center.setOpaque(false);
        center.setBorder(BorderFactory.createEmptyBorder(0, 24, 24, 0));

        JPanel board = new JPanel(new GridLayout(3, 3, 12, 12));
        board.setOpaque(false);
        board.setPreferredSize(new Dimension(540, 540));
        board.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        for (int i = 0; i < cells.length; i++) {
            JButton cell = new JButton();
            cell.setFocusPainted(false);
            cell.setFont(new Font("Segoe UI", Font.BOLD, 24));
            cell.setOpaque(true);
            cell.setContentAreaFilled(true);
            cell.setBorder(BorderFactory.createLineBorder(new Color(51, 65, 85), 2, true));
            cell.setBackground(new Color(17, 24, 39));
            cell.setForeground(Color.WHITE);
            cell.setName(cellLabel(i));
            final int index = i;
            cell.addActionListener(e -> moveCursor(index));
            cells[i] = cell;
            board.add(cell);
        }

        messageValue.setForeground(new Color(253, 224, 71));
        messageValue.setFont(new Font("Segoe UI", Font.PLAIN, 15));

        center.add(board, BorderLayout.CENTER);
        center.add(messageValue, BorderLayout.SOUTH);
        return center;
    }

    private JPanel buildSidebarPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(270, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 24));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(makeSidebarCard("Interface Logic", new String[] {
            "Current target rule: stand on the mole at timeout.",
            "Grid size: 3 x 3.",
            "Round speed changes every 3 hits.",
            "In the hardware version, the player's body would replace the cursor position.",
            "This keeps the demo focused on the game loop, not the sensor stack."
        }));
        panel.add(Box.createVerticalStrut(14));
        panel.add(makeSidebarCard("Controls", new String[] {
                "Arrow Keys or WASD move the cursor.",
                "Hit the mole before the timer ends.",
                "Difficulty increases by reducing the round duration."
        }));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttons.setOpaque(false);
        startButton.setBackground(new Color(37, 99, 235));
        startButton.setForeground(Color.WHITE);
        startButton.setFocusPainted(false);
        resetButton.setBackground(new Color(51, 65, 85));
        resetButton.setForeground(Color.WHITE);
        resetButton.setFocusPainted(false);
        buttons.add(startButton);
        buttons.add(resetButton);
        panel.add(buttons);

        return panel;
    }

    private JPanel makeSidebarCard(String title, String[] lines) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(new Color(15, 23, 42));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(148, 163, 184, 45), 1, true),
                BorderFactory.createEmptyBorder(16, 16, 16, 16)
        ));

        JLabel heading = new JLabel(title);
        heading.setForeground(Color.WHITE);
        heading.setFont(new Font("Segoe UI", Font.BOLD, 16));
        card.add(heading);
        card.add(Box.createVerticalStrut(10));

        JTextArea body = new JTextArea(String.join("\n\n", linesToBullets(lines)));
        body.setEditable(false);
        body.setFocusable(false);
        body.setOpaque(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setForeground(new Color(203, 213, 225));
        body.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        body.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setMaximumSize(new Dimension(240, Integer.MAX_VALUE));
        card.add(body);

        return card;
    }

    private String[] linesToBullets(String[] lines) {
        String[] bullets = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            bullets[i] = "• " + lines[i];
        }
        return bullets;
    }

    private JPanel makePill(String label, JLabel value) {
        JPanel pill = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        pill.setBackground(new Color(15, 23, 42, 180));
        pill.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(148, 163, 184, 40), 1, true),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)
        ));

        JLabel name = new JLabel(label);
        name.setForeground(new Color(148, 163, 184));
        name.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        value.setForeground(Color.WHITE);
        value.setFont(new Font("Segoe UI", Font.BOLD, 14));

        pill.add(name);
        pill.add(value);
        return pill;
    }

    private void registerKeyBindings() {
        JRootPane rootPane = getRootPane();
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();

        bind(inputMap, actionMap, "UP", () -> moveBy(-3));
        bind(inputMap, actionMap, "DOWN", () -> moveBy(3));
        bind(inputMap, actionMap, "LEFT", () -> moveHorizontally(-1));
        bind(inputMap, actionMap, "RIGHT", () -> moveHorizontally(1));
        bind(inputMap, actionMap, "W", () -> moveBy(-3));
        bind(inputMap, actionMap, "S", () -> moveBy(3));
        bind(inputMap, actionMap, "A", () -> moveHorizontally(-1));
        bind(inputMap, actionMap, "D", () -> moveHorizontally(1));
    }

    private void bind(InputMap inputMap, ActionMap actionMap, String key, Runnable action) {
        String mapKey = "action_" + key;
        inputMap.put(KeyStroke.getKeyStroke(key), mapKey);
        actionMap.put(mapKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void moveBy(int delta) {
        if (!running) {
            return;
        }
        int next = cursorIndex + delta;
        if (next >= 0 && next < 9) {
            moveCursor(next);
        }
    }

    private void moveHorizontally(int delta) {
        if (!running) {
            return;
        }
        int column = cursorIndex % 3;
        if (delta < 0 && column > 0) {
            moveCursor(cursorIndex - 1);
        } else if (delta > 0 && column < 2) {
            moveCursor(cursorIndex + 1);
        }
    }

    private void moveCursor(int index) {
        if (!running) {
            return;
        }
        cursorIndex = index;
        refreshBoard();
    }

    private void startGame() {
        if (running) {
            return;
        }
        running = true;
        startButton.setText("Running");
        setMessage("Game started. Move the cursor onto the mole before the timer ends.", new Color(134, 239, 172));
        startRound();
    }

    private void resetGame() {
        running = false;
        score = 0;
        level = 1;
        cursorIndex = 0;
        moleIndex = 4;
        roundDurationMs = 4000;
        roundEndsAt = 0L;
        countdownTimer.stop();
        startButton.setText("Start");
        levelValue.setText("1");
        scoreValue.setText("0");
        timerValue.setText("4.0s");
        roundSpeedValue.setText("4.0s");
        cursorValue.setText(cellLabel(cursorIndex));
        moleValue.setText(cellLabel(moleIndex));
        setMessage("Reset complete. Press Start to begin again.", new Color(253, 224, 71));
        refreshBoard();
    }

    private void startRound() {
        moleIndex = randomMole(cursorIndex);
        roundEndsAt = System.currentTimeMillis() + roundDurationMs;
        moleValue.setText(cellLabel(moleIndex));
        roundSpeedValue.setText(String.format("%.1fs", roundDurationMs / 1000.0));
        countdownTimer.restart();
        refreshBoard();
    }

    private void updateTimer() {
        if (!running) {
            return;
        }

        long remaining = Math.max(0L, roundEndsAt - System.currentTimeMillis());
        timerValue.setText(String.format("%.1fs", remaining / 1000.0));

        if (remaining <= 0L) {
            resolveRound();
        }
    }

    private void resolveRound() {
        if (!running) {
            return;
        }

        if (cursorIndex == moleIndex) {
            score++;
            scoreValue.setText(Integer.toString(score));
            setMessage("Hit confirmed on " + cellLabel(moleIndex) + ".", new Color(134, 239, 172));
            if (score % 3 == 0) {
                level++;
                levelValue.setText(Integer.toString(level));
                roundDurationMs = Math.max(1400, roundDurationMs - 450);
                roundSpeedValue.setText(String.format("%.1fs", roundDurationMs / 1000.0));
                setMessage("Level up. The timer is now faster.", new Color(253, 224, 71));
            }
        } else {
            setMessage("Missed " + cellLabel(moleIndex) + ". Move earlier next round.", new Color(248, 113, 113));
        }

        startRound();
    }

    private void refreshBoard() {
        cursorValue.setText(cellLabel(cursorIndex));
        moleValue.setText(cellLabel(moleIndex));

        for (int i = 0; i < cells.length; i++) {
            JButton cell = cells[i];
            cell.setText("");
            cell.setBackground(new Color(17, 24, 39));
            cell.setBorder(BorderFactory.createLineBorder(new Color(51, 65, 85), 2, true));

            if (i == moleIndex) {
                cell.setText("M");
                cell.setBackground(new Color(120, 53, 15));
                cell.setBorder(BorderFactory.createLineBorder(new Color(245, 158, 11), 2, true));
            }

            if (i == cursorIndex) {
                cell.setText("P");
                cell.setBackground(new Color(8, 47, 73));
                cell.setBorder(BorderFactory.createLineBorder(new Color(56, 189, 248), 2, true));
            }

            if (i == cursorIndex && i == moleIndex) {
                cell.setText("X");
                cell.setBackground(new Color(22, 101, 52));
                cell.setBorder(BorderFactory.createLineBorder(new Color(34, 197, 94), 2, true));
            }
        }
    }

    private void setMessage(String text, Color color) {
        messageValue.setText(text);
        messageValue.setForeground(color);
    }

    private int randomMole(int excludeIndex) {
        int next;
        do {
            next = random.nextInt(9);
        } while (next == excludeIndex);
        return next;
    }

    private String cellLabel(int index) {
        return switch (index) {
            case 0 -> "A1";
            case 1 -> "A2";
            case 2 -> "A3";
            case 3 -> "B1";
            case 4 -> "B2";
            case 5 -> "B3";
            case 6 -> "C1";
            case 7 -> "C2";
            case 8 -> "C3";
            default -> "A1";
        };
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new WhackAMoleConceptTester().setVisible(true));
    }
}