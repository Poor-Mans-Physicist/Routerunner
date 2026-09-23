package com.routerunner.lane;

import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Walls-only geometry over a {@link SolidGrid} for the lane planner: standable cells, head line-of-sight,
 * and a grounded A* (step up 1, drop 1-3, DROP edges to the first floor below up to {@link #DROP_MAX},
 * optional per-cell step discount for sweep-aware paths). Ports of {@code tools/lanes.py} and
 * {@code tools/legmodel.py walk_astar_path}; target chests are never solid here (the player breaks through).
 */
public final class Grid {
    static final int DROP_MAX = 40;
    static final int ASTAR_CAP = 80_000;
    /** Lowest sweep discount a step can get; the heuristic is scaled by it so the search stays admissible. */
    static final double DISCOUNT_FLOOR = 0.7;
    /** Blocks charged per 45 degrees of heading change at a cell. */
    static final double TURN_COST = 0.3;
    /** Multiplier when the previous step also turned: tight curves cost more than the same turn spread out. */
    static final double TIGHT_MULT = 2.0;
    /** Compass index of a horizontal step (dx+1, dz+1), -1 for no move. */
    private static final int[][] DIR_INDEX = {{0, 1, 2}, {7, -1, 3}, {6, 5, 4}};
    /** Heading change in degrees between two compass indices. */
    private static final double[][] TURN_DEG = new double[8][8];

    static {
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                int d = Math.abs(i - j);
                TURN_DEG[i][j] = 45.0 * Math.min(d, 8 - d);
            }
        }
    }

    private Grid() {}

    static boolean free(SolidGrid g, int x, int y, int z) {
        return !g.isSolidFly(x, y, z);
    }

    /** Feet and head clear, solid floor below, and not on the slab's top or bottom layer. */
    static boolean standable(SolidGrid g, int x, int y, int z) {
        if (x < 0 || z < 0 || x >= g.sx || z >= g.sz || y < 1 || y >= g.sy - 1) return false;
        return free(g, x, y, z) && free(g, x, y + 1, z) && !free(g, x, y - 1, z);
    }

    static boolean standable(SolidGrid g, P p) {
        return standable(g, p.x(), p.y(), p.z());
    }

    static double dist(P a, P b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double dist(double[] a, P b) {
        double dx = a[0] - b.x(), dy = a[1] - b.y(), dz = a[2] - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double hdist(P a, P b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** Straight line a→b clear of walls, sampled 3 per block (matches RoutePlanner.losClear). */
    static boolean losClear(SolidGrid g, P a, P b) {
        double len = dist(a, b);
        int n = Math.max(1, (int) Math.ceil(len * 3.0));
        for (int i = 1; i < n; i++) {
            double t = (double) i / n;
            int x = (int) Math.round(a.x() + (b.x() - a.x()) * t);
            int y = (int) Math.round(a.y() + (b.y() - a.y()) * t);
            int z = (int) Math.round(a.z() + (b.z() - a.z()) * t);
            if (g.isSolidFly(x, y, z)) return false;
        }
        return true;
    }

    /** Straight line clear for a standing body: feet and head rows both unobstructed. */
    static boolean losClearBody(SolidGrid g, P a, P b) {
        return losClear(g, a, b) && losClear(g, new P(a.x(), a.y() + 1, a.z()), new P(b.x(), b.y() + 1, b.z()));
    }

    static boolean columnFree(SolidGrid g, int x, int y, int z) {
        return free(g, x, y, z) && free(g, x, y + 1, z);
    }

    /**
     * How many of a diagonal step's two orthogonal corner cells are solid (feet or head) where the player starts,
     * and also at the landing height when the step goes up. Two means the squeeze is impassable and the step is
     * dropped; one means the step is allowed at dogleg cost and {@link #dogleg} routes the ribbon through the open
     * corner, matching the old walk graph's rule so rooms stay as connected as they were.
     */
    static int cornerBlocked(SolidGrid g, P p, int dx, int dz, int qy) {
        int[] levels = qy > p.y() ? new int[]{p.y(), qy} : new int[]{p.y()};
        boolean bx = false, bz = false;
        for (int y : levels) {
            if (!columnFree(g, p.x() + dx, y, p.z())) bx = true;
            if (!columnFree(g, p.x(), y, p.z() + dz)) bz = true;
        }
        return (bx ? 1 : 0) + (bz ? 1 : 0);
    }

    /**
     * Insert the open corner cell into every diagonal step that clips one solid corner, so the drawn ribbon never
     * cuts through a block. A step whose open corner has no floor at either height (a diagonal drop off a ledge)
     * is left as it is: nothing snags while falling.
     */
    static List<P> dogleg(SolidGrid g, List<P> path) {
        List<P> out = new ArrayList<>(path.size() + 8);
        for (int i = 0; i < path.size(); i++) {
            P q = path.get(i);
            if (i > 0) {
                P p = path.get(i - 1);
                int dx = q.x() - p.x(), dz = q.z() - p.z();
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
                    boolean bx = !columnFree(g, p.x() + dx, p.y(), p.z()), bz = !columnFree(g, p.x(), p.y(), p.z() + dz);
                    if (bx != bz) {
                        int mx = bx ? p.x() : p.x() + dx, mz = bx ? p.z() + dz : p.z();
                        int my = standable(g, mx, p.y(), mz) ? p.y() : (standable(g, mx, q.y(), mz) ? q.y() : Integer.MIN_VALUE);
                        if (my != Integer.MIN_VALUE) out.add(new P(mx, my, mz));
                    }
                }
            }
            out.add(q);
        }
        return out;
    }

    static final int FLIGHT_RADIUS = 22;
    static final int FLIGHT_ABOVE = 30;
    static final double FLIGHT_COST_PER_BLOCK = 0.5;
    static final double HOP_COST = 3.0;
    static final int HOP_EXPAND = 16;

    /** {@link #flight(SolidGrid, P, P, Function, int)} with uncached landings and two hops. */
    static List<P> flight(SolidGrid g, P a, P b) {
        return flight(g, a, b, c -> landings(g, c), 2);
    }

    /**
     * A flight for the fly fallback: the straight line when it is clear for a standing body, else up to
     * {@code maxHops} trident or warp hops between standable landings in line of sight of each other, followed by
     * the ground walk from the last landing to the target. Each hop level is one multi-source ground search from
     * every landing reached so far; when it fails, the {@link #HOP_EXPAND} landings nearest the target are expanded
     * for the next level. Null when nothing within the hop budget reaches the target.
     */
    static List<P> flight(SolidGrid g, P a, P b, Function<P, List<P>> landingsOf, int maxHops) {
        if (losClearBody(g, a, b)) {
            List<P> out = new ArrayList<>(2);
            out.add(a);
            out.add(b);
            return out;
        }
        Map<Long, P> pred = new HashMap<>();
        Map<Long, Double> cost = new HashMap<>();
        cost.put(cellKey(a), 0.0);
        List<P> frontier = new ArrayList<>();
        extend(a, landingsOf, pred, cost, frontier);
        for (int hop = 1; hop <= maxHops; hop++) {
            if (frontier.isEmpty()) return null;
            double[] c0 = new double[frontier.size()];
            for (int i = 0; i < c0.length; i++) c0[i] = cost.get(cellKey(frontier.get(i)));
            List<P> rest = search(g, frontier, c0, b, null);
            if (rest != null) {
                List<P> chain = new ArrayList<>();
                P cur = rest.get(0);
                while (true) {
                    P p = pred.get(cellKey(cur));
                    if (p == null) break;
                    chain.add(p);
                    cur = p;
                }
                Collections.reverse(chain);
                chain.addAll(rest);
                return chain;
            }
            if (hop == maxHops) return null;
            List<P> expand = new ArrayList<>(frontier);
            expand.sort((u, v) -> Double.compare(dist(u, b), dist(v, b)));
            if (expand.size() > HOP_EXPAND) expand = new ArrayList<>(expand.subList(0, HOP_EXPAND));
            frontier = new ArrayList<>();
            for (P l : expand) extend(l, landingsOf, pred, cost, frontier);
        }
        return null;
    }

    /** Add the landings visible from {@code from} to the frontier, keeping the cheapest way to reach each one. */
    private static void extend(P from, Function<P, List<P>> landingsOf, Map<Long, P> pred, Map<Long, Double> cost, List<P> frontier) {
        long kf = cellKey(from);
        double base = cost.get(kf) + (pred.containsKey(kf) ? HOP_COST : 0.0);
        for (P m : landingsOf.apply(from)) {
            long k = cellKey(m);
            double c = base + FLIGHT_COST_PER_BLOCK * dist(from, m);
            Double old = cost.get(k);
            if (old != null && old <= c) continue;
            cost.put(k, c);
            pred.put(k, from);
            if (old == null) frontier.add(m);
        }
    }

    /** Every standable cell in line of sight of a within the flight box: where a trident or warp from a can land. */
    static List<P> landings(SolidGrid g, P a) {
        List<P> landings = new ArrayList<>();
        int y0 = Math.max(1, a.y() - FLIGHT_RADIUS), y1 = Math.min(g.sy - 1, a.y() + FLIGHT_ABOVE);
        for (int dx = -FLIGHT_RADIUS; dx <= FLIGHT_RADIUS; dx++) {
            for (int dz = -FLIGHT_RADIUS; dz <= FLIGHT_RADIUS; dz++) {
                for (int y = y0; y < y1; y++) {
                    int x = a.x() + dx, z = a.z() + dz;
                    if (!standable(g, x, y, z)) continue;
                    P c = new P(x, y, z);
                    if (dist(a, c) >= 2.0 && losClearBody(g, a, c)) landings.add(c);
                }
            }
        }
        return landings;
    }


    /** Standable y near (x,y,z): level, then up to {@code up}, then down to {@code down}; MIN_VALUE if none. */
    static int floorAt(SolidGrid g, int x, int y, int z, int up, int down) {
        if (standable(g, x, y, z)) return y;
        for (int d = 1; d <= up; d++) if (standable(g, x, y + d, z)) return y + d;
        for (int d = 1; d <= down; d++) if (standable(g, x, y - d, z)) return y - d;
        return Integer.MIN_VALUE;
    }

    /** Nearest standable cell to p: whole column first, then widening rings; p clamped into the grid. */
    public static P snapInside(SolidGrid g, P p) {
        int x = Math.min(Math.max(p.x(), 0), g.sx - 1), z = Math.min(Math.max(p.z(), 0), g.sz - 1);
        for (int rad = 0; rad < 6; rad++) {
            P best = null;
            int bd = Integer.MAX_VALUE;
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != rad) continue;
                    for (int y = 1; y < g.sy - 1; y++) {
                        if (!standable(g, x + dx, y, z + dz)) continue;
                        int dd = Math.abs(y - p.y()) + 3 * rad;
                        if (dd < bd) { bd = dd; best = new P(x + dx, y, z + dz); }
                    }
                }
            }
            if (best != null) return best;
        }
        return new P(x, Math.min(Math.max(p.y(), 1), g.sy - 2), z);
    }

    private static long key(int x, int y, int z) {
        return (((long) x) << 40) | (((long) (y + 512)) << 20) | (long) (z + 512);
    }

    static long cellKey(P p) {
        return key(p.x(), p.y(), p.z());
    }

    /**
     * Every standable cell reachable on foot from a under the walk rules of {@link #astar} (step up 1, down 1-3,
     * drops to the first floor below, impassable double-corner squeezes excluded). Drops are one-way, so this is
     * the forward component, which is what a candidate needs to be in to be walkable from a.
     */
    static Set<Long> reachable(SolidGrid g, P a0) {
        Set<Long> seen = new HashSet<>();
        P a = snapNear(g, a0);
        if (a == null) return seen;
        java.util.ArrayDeque<P> q = new java.util.ArrayDeque<>();
        seen.add(cellKey(a));
        q.add(a);
        while (!q.isEmpty()) {
            P p = q.poll();
            successors(g, p, c -> {
                if (seen.add(cellKey(c))) q.add(c);
            });
        }
        return seen;
    }

    /**
     * Walk cost from every standable cell to {@code target} under the rules and step costs of {@link #search}:
     * the forward graph over all standable cells is built once and a Dijkstra runs backwards from the target.
     * Cells absent from the map cannot reach the target on foot (a one-way drop lies between). The planner turns
     * these costs into exit seconds so every lane is charged, or credited, for how it moves the player relative
     * to the door.
     */
    static Map<Long, Double> exitField(SolidGrid g, P target0) {
        Map<Long, Double> dist = new HashMap<>();
        P target = snapNear(g, target0);
        if (target == null) return dist;
        Map<Long, List<long[]>> pred = new HashMap<>();
        for (int x = 0; x < g.sx; x++) {
            for (int z = 0; z < g.sz; z++) {
                for (int y = 1; y < g.sy - 1; y++) {
                    if (!standable(g, x, y, z)) continue;
                    P p = new P(x, y, z);
                    long kp = cellKey(p);
                    successorsCost(g, p, (c, w) -> pred.computeIfAbsent(cellKey(c), k -> new ArrayList<>(4)).add(new long[]{kp, Math.round(w * 1000.0)}));
                }
            }
        }
        PriorityQueue<long[]> pq = new PriorityQueue<>((u, v) -> Long.compare(u[0], v[0]));
        Map<Long, Long> milli = new HashMap<>();
        long kt = cellKey(target);
        milli.put(kt, 0L);
        pq.add(new long[]{0L, kt});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            if (top[0] > milli.getOrDefault(top[1], Long.MAX_VALUE)) continue;
            List<long[]> ps = pred.get(top[1]);
            if (ps == null) continue;
            for (long[] e : ps) {
                long nd = top[0] + e[1];
                Long old = milli.get(e[0]);
                if (old == null || nd < old) {
                    milli.put(e[0], nd);
                    pq.add(new long[]{nd, e[0]});
                }
            }
        }
        for (Map.Entry<Long, Long> e : milli.entrySet()) dist.put(e.getKey(), e.getValue() / 1000.0);
        return dist;
    }

    /** The cells one walk step can reach from p: the neighbour rules of {@link #search} without costs. */
    static void successors(SolidGrid g, P p, Consumer<P> out) {
        successorsCost(g, p, (c, w) -> out.accept(c));
    }

    /** The cells one walk step can reach from p with the step cost {@link #search} would charge (no discount). */
    static void successorsCost(SolidGrid g, P p, BiConsumer<P, Double> out) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                double hd = Math.sqrt(dx * dx + dz * dz);
                boolean found = false;
                for (int dy : new int[]{1, 0, -1, -2, -3}) {
                    int qx = p.x() + dx, qy = p.y() + dy, qz = p.z() + dz;
                    if (!standable(g, qx, qy, qz)) continue;
                    if (dy > 0 && !free(g, p.x(), p.y() + 2, p.z())) continue;
                    int corners = dx != 0 && dz != 0 ? cornerBlocked(g, p, dx, dz, qy) : 0;
                    if (corners == 2) continue;
                    out.accept(new P(qx, qy, qz), (corners == 1 ? 2.0 : hd) + (dy > 0 ? 0.3 : 0.0));
                    found = true;
                    break;
                }
                if (found || dx * dz != 0) continue;
                int qx = p.x() + dx, qz = p.z() + dz;
                if (!(free(g, qx, p.y(), qz) && free(g, qx, p.y() + 1, qz))) continue;
                for (int fall = 4; fall <= DROP_MAX; fall++) {
                    int qy = p.y() - fall;
                    if (qy < 1) break;
                    if (!free(g, qx, qy + 1, qz)) break;
                    if (standable(g, qx, qy, qz)) {
                        out.accept(new P(qx, qy, qz), hd + 1.0 + 0.5 * Math.sqrt(fall));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Grounded A* between two cells (snapped up to 3 away), or null when no route exists. Step cost is horizontal
     * distance (+0.3 per block climbed, drops free) times {@code discount(cell)} when given, whose minimum 0.5
     * scales the heuristic so the search stays admissible.
     */
    static List<P> astar(SolidGrid g, P a0, P b0, ToDoubleFunction<P> discount) {
        P a = snapNear(g, a0);
        if (a == null) return null;
        List<P> starts = new ArrayList<>(1);
        starts.add(a);
        return search(g, starts, null, b0, discount);
    }

    /**
     * Multi-source variant of {@link #astar}: the cheapest path to b from any of the starts (exact cells, not
     * snapped), each entered at its own initial cost; used to pick a flight landing and its walk in one search.
     */
    static List<P> search(SolidGrid g, List<P> starts, double[] cost0, P b0, ToDoubleFunction<P> discount) {
        P b = snapNear(g, b0);
        if (b == null || starts.isEmpty()) return null;
        double hscale = discount == null ? 1.0 : DISCOUNT_FLOOR;
        Map<Long, Double> gcost = new HashMap<>();
        Map<Long, P> parent = new HashMap<>();
        Map<Long, P> cellOf = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>((u, v) -> Double.compare(u[0], v[0]));
        long kb = key(b.x(), b.y(), b.z());
        for (int i = 0; i < starts.size(); i++) {
            P a = starts.get(i);
            long ka = key(a.x(), a.y(), a.z());
            double c0 = cost0 == null ? 0.0 : cost0[i];
            Double old = gcost.get(ka);
            if (old != null && old <= c0) continue;
            gcost.put(ka, c0);
            parent.put(ka, null);
            cellOf.put(ka, a);
            pq.add(new double[]{c0 + hscale * hdist(a, b), c0, ka, -1, 0});
        }
        Set<Long> seen = new HashSet<>();
        int n = 0;
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            long k = (long) top[2];
            if (k == kb) {
                List<P> out = new ArrayList<>();
                for (Long c = k; c != null; ) {
                    out.add(cellOf.get(c));
                    P pp = parent.get(c);
                    c = pp == null ? null : key(pp.x(), pp.y(), pp.z());
                }
                Collections.reverse(out);
                return dogleg(g, out);
            }
            if (!seen.add(k)) continue;
            if (++n > ASTAR_CAP) return null;
            P p = cellOf.get(k);
            double gc = top[1];
            int dirIn = (int) top[3];
            boolean turned = top[4] > 0.5;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    double hd = Math.sqrt(dx * dx + dz * dz);
                    int dOut = DIR_INDEX[dx + 1][dz + 1];
                    double turn = dirIn < 0 ? 0.0 : TURN_DEG[dirIn][dOut];
                    double pen = turn < 1.0 ? 0.0 : TURN_COST * turn / 45.0 * (turned ? TIGHT_MULT : 1.0);
                    boolean found = false;
                    for (int dy : new int[]{1, 0, -1, -2, -3}) {
                        int qx = p.x() + dx, qy = p.y() + dy, qz = p.z() + dz;
                        if (!standable(g, qx, qy, qz)) continue;
                        if (dy > 0 && !free(g, p.x(), p.y() + 2, p.z())) continue;
                        int corners = dx != 0 && dz != 0 ? cornerBlocked(g, p, dx, dz, qy) : 0;
                        if (corners == 2) continue;
                        P q = new P(qx, qy, qz);
                        double step = ((corners == 1 ? 2.0 : hd) + (dy > 0 ? 0.3 : 0.0)) * (discount == null ? 1.0 : discount.applyAsDouble(q));
                        relax(gcost, parent, cellOf, pq, k, p, q, gc + step + pen, hscale * hdist(q, b), dOut, turn >= 1.0);
                        found = true;
                        break;
                    }
                    if (found || dx * dz != 0) continue;
                    int qx = p.x() + dx, qz = p.z() + dz;
                    if (!(free(g, qx, p.y(), qz) && free(g, qx, p.y() + 1, qz))) continue;
                    for (int fall = 4; fall <= DROP_MAX; fall++) {
                        int qy = p.y() - fall;
                        if (qy < 1) break;
                        if (!free(g, qx, qy + 1, qz)) break;
                        if (standable(g, qx, qy, qz)) {
                            P q = new P(qx, qy, qz);
                            double step = hd + 1.0 + 0.5 * Math.sqrt(fall);
                            relax(gcost, parent, cellOf, pq, k, p, q, gc + step + pen, hscale * hdist(q, b), dOut, turn >= 1.0);
                            break;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Relax q from {@code from}. Queue entries carry the compass direction of the step that reached the cell and
     * whether that step was itself a turn, so the turn-rate penalty ({@link #TURN_COST} per 45 degrees, doubled
     * when the previous step also turned) is a table lookup: the search prefers wide, readable curves over the
     * zigzags that equal-cost grid moves otherwise produce, at no extra cost per expansion.
     */
    private static void relax(Map<Long, Double> gcost, Map<Long, P> parent, Map<Long, P> cellOf,
                              PriorityQueue<double[]> pq, long fromKey, P from, P q, double ng, double h, int dirOut, boolean turnedNow) {
        long kq = key(q.x(), q.y(), q.z());
        Double old = gcost.get(kq);
        if (old != null && old <= ng) return;
        gcost.put(kq, ng);
        parent.put(kq, from);
        cellOf.put(kq, q);
        pq.add(new double[]{ng + h, ng, kq, dirOut, turnedNow ? 1 : 0});
    }

    private static P snapNear(SolidGrid g, P c) {
        for (int rad = 0; rad < 4; rad++) {
            for (int dy = -rad; dy <= rad; dy++) {
                for (int dx = -rad; dx <= rad; dx++) {
                    for (int dz = -rad; dz <= rad; dz++) {
                        if (standable(g, c.x() + dx, c.y() + dy, c.z() + dz)) return new P(c.x() + dx, c.y() + dy, c.z() + dz);
                    }
                }
            }
        }
        return null;
    }

    /** Grounded A* with no sweep discount, the path a logged leg is priced on; null when there is none. */
    public static List<P> groundPath(SolidGrid g, P a, P b) {
        return astar(g, a, b, null);
    }

    /** Horizontal path length in blocks. */
    static double walkLength(List<P> path) {
        double L = 0;
        for (int i = 1; i < path.size(); i++) L += hdist(path.get(i - 1), path.get(i));
        return L;
    }
}
