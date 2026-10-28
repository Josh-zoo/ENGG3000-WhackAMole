package whackamole;

import java.util.List;

/**
 * Owns the ordered list of levels and tracks which one is currently active.
 * Everything about difficulty progression lives here, so adding a level 6,
 * changing a target, or giving a level different scenery means editing this
 * file only.
 *
 * Level(number, durationMs, targetScore, theme,
 *       minUpTimeMs, maxUpTimeMs, minSpawnDelayMs, maxSpawnDelayMs,
 *       maxSimultaneousMoles, pointsPerHit)
 *
 * targetScore is points scored *during that level* (not the running total).
 * Roughly: level 1 needs 8 hits in 60 s, level 2 needs 10, and so on.
 */
public class LevelManager {

    private static final List<Level> LEVELS = List.of(
            new Level(1, 60_000,  80, Theme.DAY,    3000, 3500, 700, 1400, 1, 10),
            new Level(2, 60_000, 150, Theme.NIGHT,  2800, 3300, 550, 1150, 1, 15),
            new Level(3, 60_000, 240, Theme.SUNSET, 2300, 2500, 450,  950, 1, 20),
            new Level(4, 60_000, 375, Theme.NIGHT,  1800, 2000, 350,  800, 2, 25),
            new Level(5, 60_000,   0, Theme.SUNSET, 1200, 1500, 250,  650, 2, 30) // final level: play until time runs out
    );

    private int levelIndex = 0;

    public Level getCurrentLevel() {
        return LEVELS.get(levelIndex);
    }

    public boolean isFinalLevel() {
        return levelIndex == LEVELS.size() - 1;
    }

    /**
     * Call after scoring with the points earned so far in the current level;
     * bumps the level if its target was reached. Returns true if it changed.
     */
    public boolean maybeAdvance(int levelScore) {
        Level current = getCurrentLevel();
        if (!isFinalLevel() && current.hasTarget() && levelScore >= current.getTargetScore()) {
            levelIndex++;
            return true;
        }
        return false;
    }

    public void reset() {
        levelIndex = 0;
    }
}
