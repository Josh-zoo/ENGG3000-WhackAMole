package whackamole;

/**
 * Immutable configuration describing how one difficulty level behaves.
 * To tune or add levels, edit LevelManager's LEVELS list — nothing else
 * in the game needs to change.
 */
public final class Level {

    private final int number;
    private final int minUpTimeMs;
    private final int maxUpTimeMs;
    private final int minSpawnDelayMs;
    private final int maxSpawnDelayMs;
    private final int maxSimultaneousMoles;
    private final int pointsPerHit;
    private final int scoreToAdvance; // total score needed to reach the *next* level

    public Level(int number,
                 int minUpTimeMs, int maxUpTimeMs,
                 int minSpawnDelayMs, int maxSpawnDelayMs,
                 int maxSimultaneousMoles, int pointsPerHit, int scoreToAdvance) {
        this.number = number;
        this.minUpTimeMs = minUpTimeMs;
        this.maxUpTimeMs = maxUpTimeMs;
        this.minSpawnDelayMs = minSpawnDelayMs;
        this.maxSpawnDelayMs = maxSpawnDelayMs;
        this.maxSimultaneousMoles = maxSimultaneousMoles;
        this.pointsPerHit = pointsPerHit;
        this.scoreToAdvance = scoreToAdvance;
    }

    public int getNumber() { return number; }
    public int getMinUpTimeMs() { return minUpTimeMs; }
    public int getMaxUpTimeMs() { return maxUpTimeMs; }
    public int getMinSpawnDelayMs() { return minSpawnDelayMs; }
    public int getMaxSpawnDelayMs() { return maxSpawnDelayMs; }
    public int getMaxSimultaneousMoles() { return maxSimultaneousMoles; }
    public int getPointsPerHit() { return pointsPerHit; }
    public int getScoreToAdvance() { return scoreToAdvance; }
}
