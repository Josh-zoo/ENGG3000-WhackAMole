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
    private long hideStartMs = -1;   // timestamp the sinking animation began; -1 if not sinking

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
        this.hideStartMs = -1;
    }

    public void hide() {
        this.visible = false;
        this.hideStartMs = -1;
    }

    public boolean isVisible() {
        return visible;
    }

    public void startSinking(long nowMS) {
        this.hideStartMs = nowMS;
    }

    public boolean isSinking() {
        return hideStartMs >= 0;
    }

    /** True once this mole's up-time has run out (whether or not it's been handled yet). */
    public boolean isExpired(long nowMs) {
        return visible && !isSinking() && (nowMs - popTimeMs >= upDurationMs);
    }

    public boolean isResolved() {
        return resolved;
    }

    public void markResolved() {
        resolved = true;
    }

    
    public double popFraction(long nowMs, long risingAnimMs, long sinkingAnimMs) {
    if (isSinking()) {
        if (sinkingAnimMs <= 0) return 0;
        double t = (nowMs - hideStartMs) / (double) sinkingAnimMs;
        return 1 - Math.max(0, Math.min(1, t));
    }
    if (!visible || risingAnimMs <= 0) return visible ? 1 : 0;
    double t = (nowMs - popTimeMs) / (double) risingAnimMs;
    return Math.max(0, Math.min(1, t));
}

/** 0..1 fraction of up-time remaining — 1 = just popped up, 0 = about to expire or sinking. */
    public double remainingFraction(long nowMs) {
        if (!visible || isSinking() || upDurationMs <= 0) return 0;
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
