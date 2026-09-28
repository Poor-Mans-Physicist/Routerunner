package com.routerunner.lane;

import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * Prices the path a player actually took through a room, and the hits they actually made, with the shape time model:
 * the same moves, turns, turnarounds and clicks the planner charges a drawn route (research/2026-09-28_player_calib,
 * {@code trail_price.py}). Real room time over this price is the player's execution pace, whatever route they chose.
 *
 * <p>The trail becomes feet cells: a sample is grounded when its feet y is within 0.02 of a whole or half block (slabs),
 * and an airborne sample keeps the last grounded y, so a sprint-jump is not a climb. A new cell needs the player 0.8
 * blocks (Chebyshev, horizontal) from the current cell's centre, which stops border flicker, and gaps of more than one
 * cell are filled 8-connected. A teleport splits the path into segments priced separately, and costs {@link #WARP_S}.
 * Breaks within {@link #HIT_MERGE_MS} of the previous one form one hit; a hit is priced at the cell the player stood in,
 * aimed at its chest nearest the eye, with its chests as the group.
 */
public final class TrailPricer {
    /** Seconds charged per teleport (research/2026-09-26_shape, warps addendum: about 1 s per on-route warp). */
    public static final double WARP_S = 1.0;
    static final long HIT_MERGE_MS = 250;
    static final double CELL_HYSTERESIS = 0.8;

    private TrailPricer() {}

    /** The price and what went into it. */
    public record Price(double seconds, int hits, int warps, int cells) {}

    /**
     * Price one room visit. {@code trail} samples are {lx, ly, lz, activeMs, yaw, pitch, teleport 1/0} room-local,
     * {@code breaks} are {activeMs, lx, ly, lz}; {@code model} is the shape model for the miner at the player's speed.
     */
    public static Price price(SolidGrid grid, LegTimeModel model, List<double[]> trail, List<double[]> breaks) {
        double[] c = model.shape;
        List<List<double[]>> hits = hits(breaks);
        List<Segment> segs = segments(trail);
        double total = 0.0;
        int cells = 0;
        for (int s = 0; s < segs.size(); s++) {
            Segment seg = segs.get(s);
            double end = Double.POSITIVE_INFINITY;
            for (Segment o : segs) if (o.times.get(0) > seg.times.get(0)) end = Math.min(end, o.times.get(0));
            List<int[]> clickIdx = new ArrayList<>();
            List<P> targets = new ArrayList<>();
            List<Integer> sizes = new ArrayList<>();
            for (List<double[]> h : hits) {
                double th = h.get(0)[0];
                if (th < seg.times.get(0) - 150 || th >= end) continue;
                int k = Math.max(0, upperBound(seg.times, th) - 1);
                P cell = seg.cells.get(k);
                double ex = cell.x() + 0.5, ey = cell.y() + 1.62, ez = cell.z() + 0.5;
                double[] best = null;
                double bd = Double.POSITIVE_INFINITY;
                for (double[] b : h) {
                    double dx = b[1] + 0.5 - ex, dy = b[2] + 0.5 - ey, dz = b[3] + 0.5 - ez;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bd) { bd = d; best = b; }
                }
                clickIdx.add(new int[]{k});
                targets.add(new P((int) Math.round(best[1]), (int) Math.round(best[2]), (int) Math.round(best[3])));
                sizes.add(h.size());
            }
            List<P> seq = seg.cells.size() > 1 ? seg.cells : List.of(seg.cells.get(0), seg.cells.get(0));
            total += price(grid, c, seq, clickIdx, targets, sizes);
            cells += seg.cells.size();
        }
        int warps = Math.max(0, segs.size() - 1);
        total += c[LegTimeModel.S_ROOM_FIXED] + WARP_S * warps;
        return new Price(total, hits.size(), warps, cells);
    }

    /** Breaks sorted by time, grouped into hits: a break joins the hit when it follows the hit's last break within 250 ms. */
    static List<List<double[]>> hits(List<double[]> breaks) {
        List<double[]> sorted = new ArrayList<>(breaks);
        sorted.sort((a, b) -> {
            for (int i = 0; i < 4; i++) {
                int k = Double.compare(a[i], b[i]);
                if (k != 0) return k;
            }
            return 0;
        });
        List<List<double[]>> out = new ArrayList<>();
        for (double[] b : sorted) {
            if (!out.isEmpty()) {
                List<double[]> last = out.get(out.size() - 1);
                if (b[0] - last.get(last.size() - 1)[0] <= HIT_MERGE_MS) {
                    last.add(b);
                    continue;
                }
            }
            List<double[]> h = new ArrayList<>();
            h.add(b);
            out.add(h);
        }
        return out;
    }

    /** A teleport-free stretch of the trail as feet cells, each with the active time it was reached. */
    static final class Segment {
        final List<P> cells = new ArrayList<>();
        final List<Double> times = new ArrayList<>();
    }

    static boolean grounded(double y) {
        double f = y - Math.floor(y);
        return f < 0.02 || f > 0.98 || Math.abs(f - 0.5) < 0.02;
    }

    static List<Segment> segments(List<double[]> trail) {
        List<Segment> out = new ArrayList<>();
        Segment cur = new Segment();
        Integer gy = null;
        for (double[] s : trail) {
            double x = s[0], y = s[1], z = s[2], t = s[3];
            boolean tp = s.length > 6 && s[6] > 0.5;
            if (tp && !cur.cells.isEmpty()) {
                out.add(cur);
                cur = new Segment();
                gy = null;
            }
            if (gy == null || grounded(y)) gy = (int) Math.floor(y + 0.02);
            P c = new P((int) Math.floor(x), gy, (int) Math.floor(z));
            if (cur.cells.isEmpty()) {
                cur.cells.add(c);
                cur.times.add(t);
                continue;
            }
            P last = cur.cells.get(cur.cells.size() - 1);
            boolean far = Math.max(Math.abs(x - (last.x() + 0.5)), Math.abs(z - (last.z() + 0.5))) > CELL_HYSTERESIS;
            if ((c.x() != last.x() || c.z() != last.z()) && far) {
                int dx = c.x() - last.x(), dz = c.z() - last.z(), dy = c.y() - last.y();
                int steps = Math.max(Math.abs(dx), Math.abs(dz));
                for (int k = 1; k <= steps; k++) {
                    cur.cells.add(new P(last.x() + (int) Math.rint((double) dx * k / steps), last.y() + (int) Math.rint((double) dy * k / steps),
                            last.z() + (int) Math.rint((double) dz * k / steps)));
                    cur.times.add(t);
                }
            } else if (c.y() != last.y() && c.x() == last.x() && c.z() == last.z()) {
                cur.cells.add(c);
                cur.times.add(t);
            }
        }
        if (!cur.cells.isEmpty()) out.add(cur);
        return out;
    }

    private static int upperBound(List<Double> v, double key) {
        int lo = 0, hi = v.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (v.get(mid) <= key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * Shape seconds of a cell path with clicks at the given path indices: {@link LanePlanner#shapeCost}'s moves, turns
     * and click terms, except that the first click of a segment gets no burst discount (there is no earlier click).
     */
    static double price(SolidGrid grid, double[] c, List<P> seq, List<int[]> clickIdx, List<P> targets, List<Integer> sizes) {
        int K = seq.size();
        double[] harc = new double[K];
        for (int j = 1; j < K; j++) harc[j] = harc[j - 1] + Grid.hdist(seq.get(j - 1), seq.get(j));
        double total = 0.0;
        for (int j = 0; j < K - 1; j++) {
            P p = seq.get(j), q = seq.get(j + 1);
            double h = harc[j + 1] - harc[j];
            int cl = Math.min(grid.clearanceFlyAt(p.x(), p.y(), p.z()), grid.clearanceFlyAt(q.x(), q.y(), q.z()));
            double s = (cl >= 3 ? c[LegTimeModel.S_RUN_OPEN] : cl == 2 ? c[LegTimeModel.S_RUN_MID] : c[LegTimeModel.S_RUN_TIGHT]) * h;
            int dy = q.y() - p.y();
            if (dy == 1) s += c[LegTimeModel.S_UP1];
            else if (dy >= 2) s += c[LegTimeModel.S_UP_SHAFT] * dy;
            else if (dy <= -1 && dy >= -3) s += c[LegTimeModel.S_DROP_SMALL];
            else if (dy < -3) s += c[LegTimeModel.S_DROP_BIG] * -dy;
            total += s;
        }
        List<double[]> cand = new ArrayList<>();
        for (int k = 2; k <= K - 3; k++) {
            int wi = Math.min(3, k), wo = Math.min(3, K - 1 - k);
            P pk = seq.get(k), pa = seq.get(k - wi), pb = seq.get(k + wo);
            double ang = LanePlanner.hAngle(pk.x() - pa.x(), pk.z() - pa.z(), pb.x() - pk.x(), pb.z() - pk.z(), 1.0);
            if (ang >= 20.0) cand.add(new double[]{ang, k});
        }
        cand.sort((u, v) -> u[0] != v[0] ? Double.compare(v[0], u[0]) : Double.compare(v[1], u[1]));
        boolean[] taken = new boolean[K];
        for (double[] cd : cand) {
            int k = (int) cd[1];
            boolean near = false;
            for (int j = Math.max(0, k - 3); j <= Math.min(K - 1, k + 3); j++) if (taken[j]) { near = true; break; }
            if (near) continue;
            taken[k] = true;
            double ang = cd[0];
            P pk = seq.get(k);
            int cl = grid.clearanceFlyAt(pk.x(), pk.y(), pk.z());
            double tt = cl <= 1 ? 1.0 : cl == 2 ? 0.5 : 0.0;
            boolean retrace = false;
            if (ang >= 135.0) {
                int b0 = Math.max(0, k - 4), f1 = Math.min(K, k + 4);
                retrace = k > b0 && f1 > k + 1;
                for (int f = k + 1; f < f1 && retrace; f++) {
                    boolean close = false;
                    for (int g = b0; g < k; g++) if (Grid.hdist(seq.get(f), seq.get(g)) <= 1.5) { close = true; break; }
                    retrace = close;
                }
            }
            total += retrace ? c[LegTimeModel.S_TA] + c[LegTimeModel.S_TA_CLR] * tt
                    : (ang < 60.0 ? c[LegTimeModel.S_T20] : ang < 120.0 ? c[LegTimeModel.S_T60] : c[LegTimeModel.S_T120])
                    + c[LegTimeModel.S_TURN_CLR] * (tt * ang / 90.0);
        }
        Integer[] order = new Integer[clickIdx.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (u, v) -> Integer.compare(clickIdx.get(u)[0], clickIdx.get(v)[0]));
        boolean hasPrev = false;
        double prevArc = 0.0;
        for (int oi : order) {
            int k = Math.min(Math.max(clickIdx.get(oi)[0], 0), K - 1);
            P cell = seq.get(k), back2 = seq.get(Math.max(0, k - 2));
            double hx = cell.x() - back2.x(), hz = cell.z() - back2.z();
            if (Math.sqrt(hx * hx + hz * hz) < 0.5) {
                P fwd = seq.get(Math.min(K - 1, k + 2));
                hx = fwd.x() - cell.x();
                hz = fwd.z() - cell.z();
            }
            P ch = targets.get(oi);
            double vx = ch.x() - cell.x(), vy = (ch.y() + 0.5) - (cell.y() + 1.62), vz = ch.z() - cell.z();
            double ang = LanePlanner.hAngle(hx, hz, vx, vz, 0.5);
            double s = c[LegTimeModel.S_CLICK];
            if (ang >= 30.0 && ang < 75.0) s += c[LegTimeModel.S_SIDE];
            else if (ang >= 75.0 && ang < 120.0) s += c[LegTimeModel.S_WIDE];
            else if (ang >= 120.0) s += c[LegTimeModel.S_BEHIND];
            if (vy > 0.5) s += c[LegTimeModel.S_ABOVE];
            else if (vy < -2.5) s += c[LegTimeModel.S_BELOW];
            s += c[LegTimeModel.S_REACH] * Math.max(0.0, Math.sqrt(vx * vx + vy * vy + vz * vz) - 3.0);
            if (hasPrev && harc[k] - prevArc <= 3.0) s += c[LegTimeModel.S_BURST];
            s += c[LegTimeModel.S_SIZE] * LanePlanner.log1pRound(sizes.get(oi));
            total += Math.max(0.0, s);
            hasPrev = true;
            prevArc = harc[k];
        }
        return total;
    }
}
