package whackamole;

import java.awt.Color;

/**
 * What kind of mole pops up. Power-up moles score like a normal mole and
 * also trigger an effect when hit. Each has its own fur colour and a small
 * badge above its head so the player can tell them apart from across the
 * room.
 *
 * To add a new power-up: add a constant here, give it a badge in
 * GamePanel.drawPowerUpBadge(), and handle it in GamePanel.applyPowerUp().
 */
public enum MoleType {

    NORMAL      (null,                          null,                         "",           0),
    DOUBLE_POINTS(new Color(232, 182, 40),      new Color(255, 230, 120),     "DOUBLE POINTS!", 10_000),
    EXTRA_TIME  (new Color(70, 150, 230),       new Color(170, 215, 255),     "+10 SECONDS!",   0),
    TIME_FREEZE (new Color(150, 225, 240),      new Color(230, 250, 255),     "TIME FROZEN!",   5_000);

    /** Body/belly colours for the sprite; null = use the normal mole colours. */
    public final Color body;
    public final Color belly;
    /** Floating text shown on hit (after the points). */
    public final String banner;
    /** How long the effect lasts; 0 = instant effect. */
    public final int effectMs;

    MoleType(Color body, Color belly, String banner, int effectMs) {
        this.body = body;
        this.belly = belly;
        this.banner = banner;
        this.effectMs = effectMs;
    }

    public boolean isPowerUp() {
        return this != NORMAL;
    }
}
