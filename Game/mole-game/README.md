# Whack-a-Mole (Java / Swing)

A simple, dependency-free Whack-a-Mole game. Moles pop up in a 3x3 grid of
holes at random intervals; each stays up for a random amount of time. **A
point is scored if the cursor is hovering over a mole at the instant its
timer runs out** (no click needed).

## How to build & run

Requires a JDK (17+ is fine; written/tested logic against 21).

```bash
cd whackamole/src
javac whackamole/*.java
java whackamole.Main
```

## Project layout

```
whackamole/
  Main.java          entry point
  GameFrame.java      JFrame: status bar (score/level/time) + Start button
  GamePanel.java       the board: game loop, spawning, scoring, rendering
  Mole.java            model for a single hole (position, visible/expired state)
  Level.java            immutable difficulty settings for one level
  LevelManager.java     ordered list of levels + progression logic
  GameListener.java     interface so the UI reacts to game events
```

## Why it's split up this way (and how to extend it)

- **Mole** only knows about its own position and pop-up timing. It has no
  idea about scoring, levels, or the UI — so you can reuse it, unit test it,
  or give it new states (e.g. a "golden mole" flag) without touching
  anything else.

- **Level** is just a data bundle (spawn rate, up-time, points, how many
  moles can be up at once, score needed to advance). **To add a new level,
  add one line to the `LEVELS` list in `LevelManager.java`** — nothing else
  changes:

  ```java
  new Level(6, 350, 600, 200, 550, 4, 35, Integer.MAX_VALUE)
  ```

- **LevelManager** owns progression. If you want levels to advance by time
  survived instead of score, or want a "endless mode" that keeps generating
  harder levels procedurally, this is the only file to touch.

- **GamePanel** runs the loop and owns the moles, but it doesn't know or
  care who's listening to score/level/time/game-over events — it just fires
  them via **GameListener**. That means you can add new listeners (a sound
  effect on hit, a persisted high-score table, an achievements system, a
  combo multiplier) by writing a new `GameListener` implementation and
  calling `gamePanel.addGameListener(...)`, without editing `GamePanel` at all.

- **GameFrame** is the only place that knows about Swing widgets like labels
  and buttons, so swapping in a nicer UI, adding a pause button, or a "best
  score" panel is isolated there.

## Ideas for extending further

- **Combo/streak scoring**: track consecutive hits in a new field on
  `GamePanel`, and adjust `pointsPerHit` on the fly, or add a `getCombo()`
  method and a new `onComboChanged` event to `GameListener`.
- **Different mole types**: give `Mole` a `type` field (normal / bonus /
  decoy) and adjust scoring in `resolveExpiredMoles`.
- **Pause/resume**: add `pauseGame()`/`resumeGame()` to `GamePanel` that
  stop/start `loopTimer` and freeze `roundEndAtMs`/`nextSpawnAtMs` by the
  paused duration.
- **Bigger/smaller grid per level**: add `gridRows`/`gridCols` to `Level`
  and call `layoutHoles()` again when the level changes.
