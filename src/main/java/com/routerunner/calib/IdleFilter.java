package com.routerunner.calib;

import java.util.List;

/**
 * Idle time inside a room visit, by the owner's "still" rule (research/2026-09-27_yosho): a stretch of at least 2 s
 * standing on the ground, moving under 0.5 blocks/s, with yaw and pitch within 2 degrees of the stretch's start and no
 * break, is removed whole. Mirrors {@code idle_pos} in research/2026-09-28_player_calib/trail_price.py.
 */
public final class IdleFilter {
    static final long MIN_STILL_MS = 2000;
    static final double MAX_SPEED = 0.5, MAX_VIEW_DEG = 2.0;

    private IdleFilter() {}

    /**
     * Idle seconds in a tick-sample list. Samples are {activeMs, x, z, yaw, pitch, onGround 1/0} in time order;
     * {@code breakMs} are the break times (any order).
     */
    public static double idleSeconds(List<double[]> samples, List<Double> breakMs) {
        double[] bt = new double[breakMs.size()];
        for (int i = 0; i < bt.length; i++) bt[i] = breakMs.get(i);
        java.util.Arrays.sort(bt);
        double idle = 0.0;
        int n = samples.size(), i = 0;
        while (i < n) {
            double[] s = samples.get(i);
            int j = i;
            while (j + 1 < n) {
                double[] q = samples.get(j + 1), p = samples.get(j);
                double dt = (q[0] - p[0]) / 1000.0;
                double h = Math.hypot(q[1] - p[1], q[2] - p[2]);
                double dyaw = Math.abs(((q[3] - s[3] + 180.0) % 360.0 + 360.0) % 360.0 - 180.0);
                boolean ok = q[5] > 0.5 && (dt <= 0 || h / dt < MAX_SPEED) && dyaw <= MAX_VIEW_DEG && Math.abs(q[4] - s[4]) <= MAX_VIEW_DEG;
                if (!ok || countIn(bt, s[0], q[0]) > 0) break;
                j++;
            }
            if (j > i && samples.get(j)[0] - samples.get(i)[0] >= MIN_STILL_MS) {
                idle += (samples.get(j)[0] - samples.get(i)[0]) / 1000.0;
                i = j + 1;
            } else {
                i++;
            }
        }
        return idle;
    }

    /** Breaks in (a, b]. */
    private static int countIn(double[] bt, double a, double b) {
        return upper(bt, b) - upper(bt, a);
    }

    private static int upper(double[] v, double key) {
        int lo = 0, hi = v.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (v[mid] <= key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}
