package com.routerunner;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tier-0 looting telemetry: total mined, plus three rate readouts.
 *
 *  - Net avg:    chests / total wall-clock minutes in the vault (INCLUDES pauses/breaks).
 *  - Active avg: chests / unpaused minutes (real looting efficiency).
 *  - 1m:         chests mined in the last 60s of active time.
 *
 * Clocks accumulate from vault ENTRY (not first mine) as durations, so they serialize cleanly
 * across a server restart. {@link #advanceClock(boolean)} is called every in-vault client tick;
 * net advances always, active only while not paused. Per-call deltas >1s (loading/freezes) are
 * dropped. "Paused" includes the Escape/pause screen being open (handled by the caller).
 *
 * <p>LAPS: the vault totals above are monotonic for the whole vault and only ever cleared by
 * {@link #reset()} (vault entry). "New Lap" ({@link #newLap()}) just moves the lap baselines, so the
 * HUD counters restart from zero while the vault clocks, the run log and the history summary keep
 * counting the whole vault.
 */
public class MetricsTracker {
    private static final MetricsTracker INSTANCE = new MetricsTracker();
    public static MetricsTracker get() { return INSTANCE; }

    private static final long SLIDING_WINDOW_MS = 60_000L;
    private static final long MAX_TICK_DELTA_MS = 1_000L;

    private int total = 0;
    private long netMs = 0L;      // accumulated wall-clock time in vault (incl. pauses)
    private long activeMs = 0L;   // accumulated unpaused time in vault
    private long lastRealMs = System.currentTimeMillis();
    private final Deque<Long> recentMineActive = new ArrayDeque<>(); // active-clock stamp per recent mine

    private int lap = 1;                // current lap index (1-based); the vault clocks never restart with it
    private int lapStartTotal = 0;      // chest total when this lap began
    private long lapStartNetMs = 0L;    // net clock when this lap began
    private long lapStartActiveMs = 0L; // active clock when this lap began

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

    /**
     * Start a new lap: the HUD counters restart from this moment. The vault totals, both clocks and the
     * run log are untouched — only the lap baselines move (and the 1m window is cleared so it doesn't
     * carry the previous lap's mines).
     */
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
        if (min < 1.0 / 60.0) min = 1.0 / 60.0; // floor at 1s to avoid an early spike
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
