package whackamole;

/**
 * Lets anything (the status bar, a sound effect player, an achievements
 * system, a high-score tracker...) react to game events without GamePanel
 * needing to know those things exist. Default methods mean you only
 * override what you care about.
 */
public interface GameListener {
    default void onScoreChanged(int newScore) {}
    default void onLevelChanged(int newLevelNumber) {}
    default void onTimeChanged(int msRemaining) {}
    default void onGameOver(int finalScore) {}
}
