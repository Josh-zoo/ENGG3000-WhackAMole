package whackamole;

import java.awt.Color;

/**
 * Scenery for a level: sky, grass and hole colours plus which decorations
 * get drawn (sun or moon, clouds or stars, fireflies). GamePanel reads the
 * current level's theme every frame, so switching level is all it takes to
 * change the scenery.
 *
 * The moles themselves are never recoloured by a theme, so they stay just
 * as easy to spot at night as in the day.
 */
public enum Theme {

    DAY(
            new Color(120, 200, 255), new Color(170, 225, 255),   // sky top / bottom
            new Color(56, 128, 24), new Color(112, 176, 48),      // grass dark / light
            new Color(94, 51, 26), new Color(46, 24, 12),         // hole rim / inside
            Color.WHITE,                                          // cloud colour
            Celestial.SUN, false, false),

    NIGHT(
            new Color(12, 16, 48), new Color(34, 40, 92),
            new Color(18, 52, 30), new Color(34, 78, 44),
            new Color(52, 32, 22), new Color(18, 10, 6),
            new Color(70, 76, 120),
            Celestial.MOON, true, true),

    SUNSET(
            new Color(92, 58, 140), new Color(250, 140, 90),
            new Color(70, 104, 30), new Color(130, 150, 46),
            new Color(96, 50, 28), new Color(44, 22, 12),
            new Color(255, 190, 170),
            Celestial.SETTING_SUN, false, false);

    public enum Celestial { SUN, MOON, SETTING_SUN }

    public final Color skyTop;
    public final Color skyBottom;
    public final Color grassDark;
    public final Color grassLight;
    public final Color holeRim;
    public final Color holeInside;
    public final Color cloud;
    public final Celestial celestial;
    public final boolean stars;
    public final boolean fireflies;

    Theme(Color skyTop, Color skyBottom, Color grassDark, Color grassLight,
          Color holeRim, Color holeInside, Color cloud,
          Celestial celestial, boolean stars, boolean fireflies) {
        this.skyTop = skyTop;
        this.skyBottom = skyBottom;
        this.grassDark = grassDark;
        this.grassLight = grassLight;
        this.holeRim = holeRim;
        this.holeInside = holeInside;
        this.cloud = cloud;
        this.celestial = celestial;
        this.stars = stars;
        this.fireflies = fireflies;
    }
}
