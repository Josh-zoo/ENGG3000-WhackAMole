package whackamole;

import java.util.List;

/**
 * Owns the ordered list of levels and tracks which one is currently active.
 * Everything about difficulty progression lives here, so adding a level 6,
 * or changing how quickly players advance, means editing this file only.
 *
 * Level(number, minUpTimeMs, maxUpTimeMs, minSpawnDelayMs, maxSpawnDelayMs,
 *       maxSimultaneousMoles, pointsPerHit, scoreToAdvance)
 */
public class LevelManager {

    private static final List<Level> LEVELS = List.of(
            new Level(1, 1200, 1400, 700, 1400, 1, 10, 50),
            new Level(2, 1000, 1200, 550, 1150, 2, 15, 150),
            new Level(3, 800, 1000, 450, 950, 2, 20, 300),
            new Level(4, 700, 850, 350, 800, 3, 25, 500),
            new Level(5, 600, 700, 250, 650, 3, 30, Integer.MAX_VALUE) // final level
    );

    private int levelIndex = 0;

    public Level getCurrentLevel() {
        return LEVELS.get(levelIndex);
    }

    public boolean isFinalLevel() {
        return levelIndex == LEVELS.size() - 1;
    }

    /** Call after scoring; bumps the level if the threshold was reached. Returns true if it changed. */
    public boolean maybeAdvance(int score) {
        if (!isFinalLevel() && score >= getCurrentLevel().getScoreToAdvance()) {
            levelIndex++;
            return true;
        }
        return false;
    }

    public void reset() {
        levelIndex = 0;
    }
}
