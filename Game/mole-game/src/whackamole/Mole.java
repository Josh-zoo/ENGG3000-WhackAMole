package whackamole;

import java.awt.Point;

/**
 * Represents a single hole on the board. A Mole only knows about its own
 * position/size and its lifecycle (hidden -> visible -> expired) — it knows
 * nothing about scoring or game rules, which keeps it easy to reuse or test
 * in isolation.
 */
public class Mole {

    private final int x, y, diameter; // position/size of the hole, in pixels

    private boolean visible = false;
    private long popTimeMs = 0;      // timestamp when it appeared
    private long upDurationMs = 0;   // how long it stays up before expiring
    private boolean resolved = true; // true once this pop-up has been handled (scored/missed)

    public Mole(int x, int y, int diameter) {
        this.x = x;
        this.y = y;
        this.diameter = diameter;
    }

    /** Makes the mole appear now, and stay up for upDurationMs. */
    public void popUp(long nowMs, long upDurationMs) {
        this.visible = true;
        this.popTimeMs = nowMs;
        this.upDurationMs = upDurationMs;
        this.resolved = false;
    }

    public void hide() {
        this.visible = false;
    }

    public boolean isVisible() {
        return visible;
    }

    /** True once this mole's up-time has run out (whether or not it's been handled yet). */
    public boolean isExpired(long nowMs) {
        return visible && (nowMs - popTimeMs >= upDurationMs);
    }

    public boolean isResolved() {
        return resolved;
    }

    public void markResolved() {
        resolved = true;
    }

    /** Fraction of up-time remaining, 0..1 — handy for drawing a shrinking timer ring. */
    public double timeRemainingFraction(long nowMs) {
        if (!visible || upDurationMs <= 0) return 0;
        double remaining = upDurationMs - (nowMs - popTimeMs);
        return Math.max(0, Math.min(1, remaining / (double) upDurationMs));
    }

    /** Whether the given point (e.g. the cursor) falls within this mole's hole. */
    public boolean contains(Point p) {
        if (p == null) return false;
        int cx = x + diameter / 2;
        int cy = y + diameter / 2;
        int dx = p.x - cx;
        int dy = p.y - cy;
        int r = diameter / 2;
        return (dx * dx + dy * dy) <= r * r;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getDiameter() { return diameter; }
}
