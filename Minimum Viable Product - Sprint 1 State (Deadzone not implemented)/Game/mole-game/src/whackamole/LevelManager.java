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
            new Level(1, 3000, 3200, 1300, 1400, 1, 10, 50),
            new Level(2, 2800, 3000, 1050, 1150, 1, 15, 150),
            new Level(3, 2300, 2500, 850, 950, 1, 20, 300),
            new Level(4, 1300, 1500, 700, 800, 2, 25, 500),
            new Level(5, 800, 1100, 550, 650, 2, 30, Integer.MAX_VALUE) // final level
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
