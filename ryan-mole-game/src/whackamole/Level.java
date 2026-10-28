package whackamole;

/**
 * Immutable configuration describing how one difficulty level behaves.
 * To tune or add levels, edit LevelManager's LEVELS list — nothing else
 * in the game needs to change.
 *
 * Progression: each level runs on its own clock (durationMs). Score
 * targetScore points *within this level* before the clock runs out to move
 * on; run out of time first and it's game over. targetScore == 0 marks the
 * final level, which simply plays until time runs out.
 */
public final class Level {

    private final int number;
    private final int durationMs;
    private final int targetScore;
    private final Theme theme;
    private final int minUpTimeMs;
    private final int maxUpTimeMs;
    private final int minSpawnDelayMs;
    private final int maxSpawnDelayMs;
    private final int maxSimultaneousMoles;
    private final int pointsPerHit;

    public Level(int number, int durationMs, int targetScore, Theme theme,
                 int minUpTimeMs, int maxUpTimeMs,
                 int minSpawnDelayMs, int maxSpawnDelayMs,
                 int maxSimultaneousMoles, int pointsPerHit) {
        this.number = number;
        this.durationMs = durationMs;
        this.targetScore = targetScore;
        this.theme = theme;
        this.minUpTimeMs = minUpTimeMs;
        this.maxUpTimeMs = maxUpTimeMs;
        this.minSpawnDelayMs = minSpawnDelayMs;
        this.maxSpawnDelayMs = maxSpawnDelayMs;
        this.maxSimultaneousMoles = maxSimultaneousMoles;
        this.pointsPerHit = pointsPerHit;
    }

    public int getNumber() { return number; }
    public int getDurationMs() { return durationMs; }
    /** Points needed within this level to advance; 0 = final level (no target). */
    public int getTargetScore() { return targetScore; }
    public boolean hasTarget() { return targetScore > 0; }
    public Theme getTheme() { return theme; }
    public int getMinUpTimeMs() { return minUpTimeMs; }
    public int getMaxUpTimeMs() { return maxUpTimeMs; }
    public int getMinSpawnDelayMs() { return minSpawnDelayMs; }
    public int getMaxSpawnDelayMs() { return maxSpawnDelayMs; }
    public int getMaxSimultaneousMoles() { return maxSimultaneousMoles; }
    public int getPointsPerHit() { return pointsPerHit; }
}
