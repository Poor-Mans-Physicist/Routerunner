package com.routerunner.adaptive;

import com.routerunner.lane.Grid;
import com.routerunner.lane.LegTimeModel;
import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Burst-to-burst leg rows for the adaptive leg model, built from one room exactly as {@code tools/legmodel.py
 * build_rows} builds the bundled model's training rows: bursts are the distinct break timestamps, a leg joins two
 * consecutive bursts 0.3 to 15 s apart whose trail positions are at least 2 blocks apart, and its features come from
 * the grounded A* path on the room grid (walls only), the chests still standing at the leg start, the momentum
 * heading 0.4 s before it, the size of the burst it starts from, and any teleport inside it. The one deliberate
 * difference is the path: {@link Grid#groundPath} (the planner's corner rules and turn-rate cost) instead of the
 * Python search, so the model learns on the same paths the planner prices.
 */
public final class LegRowBuilder {
    static final long GAP_MIN_MS = 300, GAP_MAX_MS = 15_000;
    static final int MIN_CHESTS = 50;
    static final double MIN_STRAIGHT = 2.0;
    static final double BRUSH = 6.0, DST_R = 8.0;
    static final long TURN_LOOKBACK_MS = 400;
    static final double TURN_MIN_MOVE = 0.5;

    private LegRowBuilder() {}

    /** Rows plus the count of legs dropped because no ground path joined their ends. */
    public static final class Result {
        public final List<double[]> rows = new ArrayList<>();
        /** Active-clock start of each row's leg (for matching against offline rows). */
        public final List<Long> startMs = new ArrayList<>();
        public int pathFails;
    }

    /**
     * @param grid       the room's solidity grid, clearance baked
     * @param chests     the room's target chests, room-local
     * @param trail      samples {lx, ly, lz, activeMs, ...} in time order
     * @param breaks     target breaks in the room {activeMs, lx, ly, lz}
     * @param teleportMs active-clock times of detected teleports
     * @return rows of the twelve transformed features ({@link LegTimeModel#features}) then log(seconds)
     */
    public static Result build(SolidGrid grid, List<P> chests, List<double[]> trail, List<double[]> breaks, List<Long> teleportMs) {
        Result out = new Result();
        if (grid == null || trail == null || trail.size() < 2 || breaks == null || breaks.size() < MIN_CHESTS) return out;
        TreeMap<Long, Integer> burstSize = new TreeMap<>();
        Map<Long, Long> breakTime = new HashMap<>();
        for (double[] b : breaks) {
            long t = (long) b[0];
            burstSize.merge(t, 1, Integer::sum);
            long key = key((int) b[1], (int) b[2], (int) b[3]);
            Long old = breakTime.get(key);
            if (old == null || t < old) breakTime.put(key, t);
        }
        long[] chestBroken = new long[chests.size()];
        for (int i = 0; i < chests.size(); i++) {
            P c = chests.get(i);
            Long t = breakTime.get(key(c.x(), c.y(), c.z()));
            chestBroken[i] = t == null ? Long.MAX_VALUE : t;
        }
        long[] tps = new long[teleportMs == null ? 0 : teleportMs.size()];
        for (int i = 0; i < tps.length; i++) tps[i] = teleportMs.get(i);
        java.util.Arrays.sort(tps);
        double[] ts = new double[trail.size()];
        for (int i = 0; i < ts.length; i++) ts[i] = trail.get(i)[3];

        Long prev = null;
        for (long t1 : burstSize.keySet()) {
            if (prev == null) { prev = t1; continue; }
            long t0 = prev;
            prev = t1;
            long gap = t1 - t0;
            if (gap < GAP_MIN_MS || gap > GAP_MAX_MS) continue;
            double[] a = trailAt(trail, ts, t0), b = trailAt(trail, ts, t1);
            double straight = dist(a, b);
            if (straight < MIN_STRAIGHT) continue;
            P ca = new P((int) Math.round(a[0]), (int) Math.round(a[1]), (int) Math.round(a[2]));
            P cb = new P((int) Math.round(b[0]), (int) Math.round(b[1]), (int) Math.round(b[2]));
            List<P> path = Grid.groundPath(grid, ca, cb);
            if (path == null || path.isEmpty()) {
                out.pathFails++;
                continue;
            }
            double walk = 0, climb = 0, drop = 0;
            for (int k = 1; k < path.size(); k++) {
                P p = path.get(k - 1), q = path.get(k);
                walk += Math.hypot(q.x() - p.x(), q.z() - p.z());
                int dy = q.y() - p.y();
                if (dy > 0) climb += dy; else drop -= dy;
            }
            walk = Math.max(walk, straight * 0.999);
            int mn = Integer.MAX_VALUE, sum = 0, tight = 0;
            for (P c : path) {
                int cl = grid.clearanceFlyAt(c.x(), c.y(), c.z());
                mn = Math.min(mn, cl);
                sum += cl;
                if (cl <= 1) tight++;
            }
            double[] m0 = trailAt(trail, ts, t0 - TURN_LOOKBACK_MS);
            double mx = a[0] - m0[0], mz = a[2] - m0[2], dx = b[0] - a[0], dz = b[2] - a[2];
            double n1 = Math.hypot(mx, mz), n2 = Math.hypot(dx, dz);
            double turn = 0.0;
            if (n1 >= TURN_MIN_MOVE && n2 > 1e-6) {
                double c = (mx * dx + mz * dz) / (n1 * n2);
                turn = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, c))));
            }
            int nLine = 0, nDst = 0;
            for (int i = 0; i < chests.size(); i++) {
                if (chestBroken[i] <= t0) continue;
                P c = chests.get(i);
                double[] cp = {c.x(), c.y(), c.z()};
                if (segDist(cp, a, b) <= BRUSH) nLine++;
                if (dist(cp, b) <= DST_R) nDst++;
            }
            int lo = lowerBound(tps, t0), hi = lowerBound(tps, t1);
            double warp = hi - lo > 0 ? 1 : 0;
            double[] f = LegTimeModel.features(straight, walk / straight, climb, drop, mn, (double) sum / path.size(),
                    (double) tight / path.size(), turn, nLine / straight, nDst, burstSize.get(t0), warp);
            double[] row = java.util.Arrays.copyOf(f, 13);
            row[12] = Math.log(gap / 1000.0);
            out.rows.add(row);
            out.startMs.add(t0);
        }
        return out;
    }

    /** Interpolated position on the trail at active time t (clamped to its ends), as tools/ceiling_analysis.py trail_at. */
    static double[] trailAt(List<double[]> trail, double[] ts, double t) {
        int i = lowerBound(ts, t);
        if (i <= 0) return copy3(trail.get(0));
        if (i >= trail.size()) return copy3(trail.get(trail.size() - 1));
        double[] a = trail.get(i - 1), b = trail.get(i);
        if (b[3] == a[3]) return copy3(a);
        double f = (t - a[3]) / (b[3] - a[3]);
        return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
    }

    private static double[] copy3(double[] p) {
        return new double[]{p[0], p[1], p[2]};
    }

    private static int lowerBound(double[] v, double t) {
        int lo = 0, hi = v.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (v[mid] < t) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static int lowerBound(long[] v, long t) {
        int lo = 0, hi = v.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (v[mid] < t) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static double dist(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double segDist(double[] p, double[] a, double[] b) {
        double vx = b[0] - a[0], vy = b[1] - a[1], vz = b[2] - a[2];
        double L2 = vx * vx + vy * vy + vz * vz;
        if (L2 < 1e-9) return dist(p, a);
        double t = ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy + (p[2] - a[2]) * vz) / L2;
        t = Math.max(0.0, Math.min(1.0, t));
        return dist(p, new double[]{a[0] + t * vx, a[1] + t * vy, a[2] + t * vz});
    }

    private static long key(int x, int y, int z) {
        return (((long) x & 0x1FFFFF) << 42) | (((long) y & 0x1FFFFF) << 21) | ((long) z & 0x1FFFFF);
    }
}
