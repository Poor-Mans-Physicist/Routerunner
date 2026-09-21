package com.routerunner;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Looting telemetry: total chests mined plus net (wall-clock), active (unpaused) and sliding 1-minute rates.
 *
 * <p>Clocks accumulate from vault entry as durations; {@link #advanceClock(boolean)} runs every in-vault
 * tick and drops per-call deltas over 1 s. Vault totals are cleared only by {@link #reset()};
 * {@link #newLap()} moves the lap baselines the HUD counts from.
 */
public class MetricsTracker {
    private static final MetricsTracker INSTANCE = new MetricsTracker();
    public static MetricsTracker get() { return INSTANCE; }

    private static final long SLIDING_WINDOW_MS = 60_000L;
    private static final long MAX_TICK_DELTA_MS = 1_000L;

    private int total = 0;
    /** Accumulated wall-clock time in the vault, pauses included. */
    private long netMs = 0L;
    /** Accumulated unpaused time in the vault. */
    private long activeMs = 0L;
    private long lastRealMs = System.currentTimeMillis();
    /** Active-clock stamp of each mine within the sliding window. */
    private final Deque<Long> recentMineActive = new ArrayDeque<>();

    /** Current lap (1-based) and the chest total and clocks when it began. */
    private int lap = 1;
    private int lapStartTotal = 0;
    private long lapStartNetMs = 0L;
    private long lapStartActiveMs = 0L;

    public void advanceClock(boolean paused) {
        long now = System.currentTimeMillis();
        long delta = now - lastRealMs;
        lastRealMs = now;
        if (delta > 0L && delta < MAX_TICK_DELTA_MS) {
            netMs += delta;
            if (!paused) activeMs += delta;
        }
        evict();
    }

    public void onMined(int count) {
        if (count <= 0) return;
        total += count;
        for (int i = 0; i < count; i++) recentMineActive.addLast(activeMs);
        evict();
    }

    private void evict() {
        long cutoff = activeMs - SLIDING_WINDOW_MS;
        while (!recentMineActive.isEmpty() && recentMineActive.peekFirst() < cutoff) {
            recentMineActive.pollFirst();
        }
    }

    public int getTotal() { return total; }
    public long getNetMs() { return netMs; }
    public long getActiveMs() { return activeMs; }

    public double getNetAvgPerMin() {
        return rate(total, netMs);
    }

    public double getActiveAvgPerMin() {
        return rate(total, activeMs);
    }

    public double getSlidingPerMin() {
        evict();
        return recentMineActive.size() * (60_000.0 / SLIDING_WINDOW_MS);
    }

    /** Start a new lap: moves the lap baselines and clears the sliding window; vault totals are untouched. */
    public void newLap() {
        lap++;
        lapStartTotal = total;
        lapStartNetMs = netMs;
        lapStartActiveMs = activeMs;
        recentMineActive.clear();
    }

    public int getLap() { return lap; }
    public int getLapStartTotal() { return lapStartTotal; }
    public long getLapStartNetMs() { return lapStartNetMs; }
    public long getLapStartActiveMs() { return lapStartActiveMs; }

    public int getLapTotal() { return total - lapStartTotal; }

    public double getLapNetAvgPerMin() {
        return rate(getLapTotal(), netMs - lapStartNetMs);
    }

    public double getLapActiveAvgPerMin() {
        return rate(getLapTotal(), activeMs - lapStartActiveMs);
    }

    private static double rate(int count, long elapsedMs) {
        if (count == 0) return 0.0;
        double min = elapsedMs / 60_000.0;
        if (min < 1.0 / 60.0) min = 1.0 / 60.0;
        return count / min;
    }

    public void reset() {
        total = 0;
        netMs = 0L;
        activeMs = 0L;
        lap = 1;
        lapStartTotal = 0;
        lapStartNetMs = 0L;
        lapStartActiveMs = 0L;
        lastRealMs = System.currentTimeMillis();
        recentMineActive.clear();
    }

    /** Restore accumulated state after a restart (the 1m window starts empty again). */
    public void restore(int total, long netMs, long activeMs,
                        int lap, int lapStartTotal, long lapStartNetMs, long lapStartActiveMs) {
        this.total = total;
        this.netMs = netMs;
        this.activeMs = activeMs;
        this.lap = Math.max(1, lap);
        this.lapStartTotal = lapStartTotal;
        this.lapStartNetMs = lapStartNetMs;
        this.lapStartActiveMs = lapStartActiveMs;
        this.lastRealMs = System.currentTimeMillis();
        this.recentMineActive.clear();
    }
}
