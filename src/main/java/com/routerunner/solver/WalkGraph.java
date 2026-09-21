package com.routerunner.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Movement graph over standable cells (feet and head clear, solid floor below). Edge modes:
 * <ul>
 *   <li>walk ('w'): step up 1 / level / down up to 3; cost = distance × clearance multiplier + verticality;</li>
 *   <li>drop ('d'): fall {@link #MIN_DROP}+ blocks to the first floor below; cost = dropActionCost + dropHeightWeight·√h;</li>
 *   <li>trident ('t'): ray-marched vertical/sloped shaft dash between standable cells, kept only when it saves
 *       travel (or is the only access). Two tridents can't chain back-to-back.</li>
 * </ul>
 * All costs are in centi-blocks. Horizontal sprint lines are handled per-leg in RoutePlanner.
 */
public final class WalkGraph {
    public static final int CARD = 100;
    public static final int DIAG = 141;

    /**
     * Dijkstra state = node × arrival info: 0..7 = arrived walking with that compass heading (45° apart,
     * circular), 8 = arrived by trident, 9 = start node.
     */
    public static final int STATES_PER_NODE = 10;
    private static final int TRIDENT_INFO = 8;
    private static final int START_INFO = 9;
    /** Shortest fall that becomes a drop edge (1..3 down is a walk edge); also how {@link #edgeMode} recognises a drop. */
    static final int MIN_DROP = 4;
    /** Index (dx+1)*3 + (dz+1) → compass heading 0..7, or -1 for no move. */
    private static final int[] HEADING_OF = {5, 4, 3, 6, -1, 2, 7, 0, 1};

    /** Shaft ray-march directions: vertical, plus 45°, 2:1, 3:1, 1:2, 1:3 slopes per axis (diagonals only at 45°). */
    private static final int[][] SHAFT_DIRS = buildShaftDirs();

    private static final System.Logger LOG = System.getLogger("Routerunner.WalkGraph");

    public final List<P> nodes = new ArrayList<>();
    private final Map<Long, Integer> index = new HashMap<>();
    private final List<int[]> adjTo = new ArrayList<>();
    private final List<int[]> adjW = new ArrayList<>();
    /** Per edge: 0 = walk, 1 = trident, 2 = drop. */
    private final List<int[]> adjMode = new ArrayList<>();
    /** Per edge: walk/diagonal-drop heading 0..7; trident and straight-down drop = -1. */
    private final List<int[]> adjDir = new ArrayList<>();
    /** Turn penalty indexed [fromHeading*8 + toHeading] (centi-blocks). */
    private int[] turnCenti = new int[64];

    private static long packNode(int x, int y, int z) {
        return (((long) (x + 512)) << 40) | (((long) (y + 512)) << 20) | (long) (z + 512);
    }

    /** Build the graph (nodes, walk, drop and shaft edges) for a solidity grid. */
    public static WalkGraph build(SolidGrid g, RoutePlanner.Params pm) {
        WalkGraph wg = new WalkGraph();
        wg.turnCenti = buildTurnTable(pm.pathTurnWeight);
        for (int x = 0; x < g.sx; x++) {
            for (int z = 0; z < g.sz; z++) {
                for (int y = 1; y < g.sy; y++) {
                    if (!g.isSolid(x, y, z) && !g.isSolid(x, y + 1, z) && g.isSolid(x, y - 1, z)) {
                        wg.index.put(packNode(x, y, z), wg.nodes.size());
                        wg.nodes.add(new P(x, y, z));
                    }
                }
            }
        }
        int n = wg.nodes.size();
        List<List<int[]>> adj = new ArrayList<>(n); // each entry {toIndex, weight, mode, heading}
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

        int[][] dirs = {
            {1, 0, CARD}, {-1, 0, CARD}, {0, 1, CARD}, {0, -1, CARD},
            {1, 1, DIAG}, {1, -1, DIAG}, {-1, 1, DIAG}, {-1, -1, DIAG}
        };
        int[] dys = {1, 0, -1, -2, -3};
        for (int i = 0; i < n; i++) {
            P p = wg.nodes.get(i);
            for (int[] d : dirs) {
                // a diagonal is blocked only if both orthogonal cells are walls
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag) {
                    boolean sideX = g.isSolid(p.x() + d[0], p.y(), p.z()) || g.isSolid(p.x() + d[0], p.y() + 1, p.z());
                    boolean sideZ = g.isSolid(p.x(), p.y(), p.z() + d[1]) || g.isSolid(p.x(), p.y() + 1, p.z() + d[1]);
                    if (sideX && sideZ) continue;
                }
                for (int dy : dys) {
                    Integer j = wg.index.get(packNode(p.x() + d[0], p.y() + dy, p.z() + d[1]));
                    if (j != null) {
                        int nx = p.x() + d[0], ny = p.y() + dy, nz = p.z() + d[1];
                        int clrAt = g.clearanceFlyAt(nx, ny, nz);
                        int vert = dy > 0 ? (int) Math.round(pm.upCost * 100.0 * dy)
                                          : (int) Math.round(pm.downCost * 100.0 * (-dy));
                        int baseMove = (int) Math.round(d[2] * RoutePlanner.clearanceMult(clrAt, pm));
                        adj.get(i).add(new int[]{j, baseMove + vert, 0, headingIndex(d[0], d[1])});
                        break;
                    }
                }
            }
        }

        Set<Long> dropPairs = addDropEdges(wg, g, pm, adj);
        addShaftEdges(wg, g, pm, adj, dropPairs);

        for (int i = 0; i < n; i++) {
            List<int[]> e = adj.get(i);
            int[] ta = new int[e.size()], wa = new int[e.size()], ma = new int[e.size()], da = new int[e.size()];
            for (int k = 0; k < e.size(); k++) {
                ta[k] = e.get(k)[0];
                wa[k] = e.get(k)[1];
                ma[k] = e.get(k)[2];
                da[k] = e.get(k)[3];
            }
            wg.adjTo.add(ta);
            wg.adjW.add(wa);
            wg.adjMode.add(ma);
            wg.adjDir.add(da);
        }
        return wg;
    }

    /**
     * Add drop edges: from each node, trace each of the nine neighbouring columns straight down (target chests
     * count as solid) to the first standable landing {@link #MIN_DROP}+ blocks below; a solid cell first means
     * no edge. Returns the (from,to) pairs covered, so {@link #addShaftEdges} skips the redundant dash.
     */
    private static Set<Long> addDropEdges(WalkGraph wg, SolidGrid g, RoutePlanner.Params pm, List<List<int[]>> adj) {
        Set<Long> pairs = new HashSet<>();
        int maxH = pm.dropMaxHeight;
        if (maxH < MIN_DROP) return pairs;
        int[] offs = {-1, 0, 1};
        for (int i = 0; i < wg.nodes.size(); i++) {
            P p = wg.nodes.get(i);
            for (int dx : offs) {
                for (int dz : offs) {
                    int nx = p.x() + dx, nz = p.z() + dz;
                    int land = -1, fell = 0;
                    for (int h = 1; h <= maxH; h++) {
                        int cy = p.y() - (h - 1);
                        if (g.isSolid(nx, cy, nz) || g.isSolid(nx, cy + 1, nz)) break;
                        if (h < MIN_DROP) continue;
                        Integer cand = wg.index.get(packNode(nx, p.y() - h, nz));
                        if (cand != null) { land = cand; fell = h; break; }
                    }
                    if (land < 0 || land == i) continue;
                    int cost = (int) Math.round(100.0 * (pm.dropActionCost + pm.dropHeightWeight * Math.sqrt(fell)));
                    int heading = (dx == 0 && dz == 0) ? -1 : headingIndex(dx, dz);
                    adj.get(i).add(new int[]{land, Math.max(1, cost), 2, heading});
                    pairs.add(pairKey(i, land));
                }
            }
        }
        return pairs;
    }

    /** Ordered (from,to) node-pair key. */
    private static long pairKey(int from, int to) {
        return (((long) from) << 32) | (to & 0xFFFFFFFFL);
    }

    /** Build the fixed set of shaft ray-march directions (primitive vectors with dy != 0). */
    private static int[][] buildShaftDirs() {
        List<int[]> ds = new ArrayList<>();
        ds.add(new int[]{0, 1, 0});
        ds.add(new int[]{0, -1, 0});
        int[][] slopes = {{1, 1}, {1, 2}, {1, 3}, {2, 1}, {3, 1}}; // (horizontal, vertical)
        int[][] hdirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] sl : slopes) {
            int h = sl[0], v = sl[1];
            for (int[] hd : hdirs) {
                boolean diag = hd[0] != 0 && hd[1] != 0;
                if (diag && !(h == 1 && v == 1)) continue;
                for (int vs = 1; vs >= -1; vs -= 2) {
                    ds.add(new int[]{hd[0] * h, v * vs, hd[1] * h});
                }
            }
        }
        return ds.toArray(new int[0][]);
    }

    /** Circular compass heading (0..7, 45° apart) for a unit horizontal step; dx,dz in {-1,0,1}, not both 0. */
    static int headingIndex(int dx, int dz) {
        return HEADING_OF[(dx + 1) * 3 + (dz + 1)];
    }

    /**
     * 8×8 heading-change penalty (centi-blocks) from a blocks-per-radian weight; the angle between headings
     * i and j is min(|i-j|, 8-|i-j|) × 45°. A weight of 0 disables turn cost.
     */
    private static int[] buildTurnTable(double weightPerRad) {
        int[] t = new int[64];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                int diff = Math.abs(i - j);
                int steps = Math.min(diff, 8 - diff);
                t[i * 8 + j] = (int) Math.round(weightPerRad * 100.0 * (steps * (Math.PI / 4.0)));
            }
        }
        return t;
    }

    /**
     * Add trident shaft edges. From each node, ray-march each {@link #SHAFT_DIRS} direction (target chests are
     * pass-through) to the farthest standable landing, dedupe near-parallel shafts, then score each by ground
     * distance saved (walk+drop Dijkstra minus dash cost). Steep shafts (rise >= horizontal extent and
     * >= shaftMinVertical) and the rest are ranked and capped separately; directions already covered by a drop
     * edge are skipped. Edges are bidirectional.
     */
    private static void addShaftEdges(WalkGraph wg, SolidGrid g, RoutePlanner.Params pm, List<List<int[]>> adj,
                                      Set<Long> dropPairs) {
        int n = wg.nodes.size();
        HashMap<Long, int[]> dedup = new HashMap<>(); // shaftKey -> {aIdx, bIdx, lenCenti}, longest kept
        int cap = (int) Math.ceil(pm.shaftMaxLen) + 4;
        int[] cx = new int[cap * 2], cy = new int[cap * 2], cz = new int[cap * 2];
        for (int i = 0; i < n; i++) {
            P a = wg.nodes.get(i);
            for (int[] d : SHAFT_DIRS) {
                double dl = Math.sqrt((double) d[0] * d[0] + (double) d[1] * d[1] + (double) d[2] * d[2]);
                double ux = d[0] / dl, uy = d[1] / dl, uz = d[2] / dl;
                int cnt = 0, lastX = a.x(), lastY = a.y(), lastZ = a.z();
                for (double t = 1.0; t <= pm.shaftMaxLen && cnt < cx.length; t += 0.5) {
                    int nx = (int) Math.round(a.x() + ux * t), ny = (int) Math.round(a.y() + uy * t), nz = (int) Math.round(a.z() + uz * t);
                    if (nx == lastX && ny == lastY && nz == lastZ) continue;
                    lastX = nx; lastY = ny; lastZ = nz;
                    if (g.isSolidFly(nx, ny, nz) || g.isSolidFly(nx, ny + 1, nz)) break;
                    cx[cnt] = nx; cy[cnt] = ny; cz[cnt] = nz; cnt++;
                }
                int bIdx = -1;
                double bLen = 0;
                for (int s = cnt - 1; s >= 0; s--) {
                    double len = Math.sqrt(sq(cx[s] - a.x()) + sq(cy[s] - a.y()) + sq(cz[s] - a.z()));
                    if (len < pm.tridentMinDist) break;
                    int b = wg.nearestNode(new P(cx[s], cy[s], cz[s]), 1);
                    if (b < 0 || b == i) continue;
                    P bp = wg.nodes.get(b);
                    if (d[1] > 0 && bp.y() <= a.y()) continue;
                    if (d[1] < 0 && bp.y() >= a.y()) continue;
                    double el = RoutePlanner.euclid(a, bp);
                    if (el < pm.tridentMinDist) continue;
                    bIdx = b; bLen = el; break;
                }
                if (bIdx < 0) continue;
                long key = shaftKey(a, wg.nodes.get(bIdx));
                int lenC = (int) Math.round(bLen * 100);
                int[] prev = dedup.get(key);
                if (prev == null || lenC > prev[2]) dedup.put(key, new int[]{i, bIdx, lenC});
            }
        }
        if (dedup.isEmpty()) return;

        HashMap<Integer, int[]> distCache = new HashMap<>();
        int dijkstraRuns = 0;
        boolean warned = false;
        List<double[]> vert = new ArrayList<>();  // {saving, aIdx, bIdx, costCenti}
        List<double[]> horiz = new ArrayList<>();
        for (int[] c : dedup.values()) {
            int a = c[0], b = c[1];
            double len = c[2] / 100.0;
            double tcost = pm.tridentActionCost + pm.tridentDistWeight * len;
            int[] dist = distCache.get(a);
            if (dist == null && dijkstraRuns < pm.shaftMaxDijkstra) {
                dist = walkOnlyDist(adj, a, n);
                distCache.put(a, dist);
                dijkstraRuns++;
            }
            double saving;
            if (dist == null) {
                if (!warned) {
                    LOG.log(System.Logger.Level.WARNING,
                            "shaft scoring hit the Dijkstra cap (" + pm.shaftMaxDijkstra + "); ranking the rest by length");
                    warned = true;
                }
                saving = len;
            } else {
                int wd = dist[b];
                saving = (wd == Integer.MAX_VALUE) ? 1e9 : wd / 100.0 - tcost; // dash-only access: always keep
            }
            P pa = wg.nodes.get(a), pb = wg.nodes.get(b);
            double rise = Math.abs(pb.y() - pa.y());
            double horizExt = Math.hypot(pb.x() - pa.x(), pb.z() - pa.z());
            double[] row = {saving, a, b, Math.max(1, Math.round(tcost * 100))};
            if (rise >= pm.shaftMinVertical && rise >= horizExt) {
                if (saving >= pm.shaftMinSaving) vert.add(row);
            } else if (saving >= pm.shaftMinSavingHoriz) {
                horiz.add(row);
            }
        }
        vert.sort((x, y) -> Double.compare(y[0], x[0]));
        horiz.sort((x, y) -> Double.compare(y[0], x[0]));
        addTop(vert, pm.shaftCapVertical, adj, dropPairs);
        addTop(horiz, pm.shaftCapHoriz, adj, dropPairs);
    }

    private static void addTop(List<double[]> ranked, int cap, List<List<int[]>> adj, Set<Long> dropPairs) {
        int m = Math.min(cap, ranked.size());
        for (int k = 0; k < m; k++) {
            double[] s = ranked.get(k);
            int a = (int) s[1], b = (int) s[2], cost = (int) s[3];
            if (!dropPairs.contains(pairKey(a, b))) adj.get(a).add(new int[]{b, cost, 1, -1});
            if (!dropPairs.contains(pairKey(b, a))) adj.get(b).add(new int[]{a, cost, 1, -1});
        }
    }

    /** Dijkstra over ground edges (walk + drop) from src, in centi-blocks. */
    private static int[] walkOnlyDist(List<List<int[]>> adj, int src, int n) {
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[src] = 0;
        PriorityQueue<long[]> heap = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        heap.add(new long[]{0, src});
        while (!heap.isEmpty()) {
            long[] top = heap.poll();
            int u = (int) top[1];
            if (top[0] > dist[u]) continue;
            for (int[] e : adj.get(u)) {
                if (e[2] == 1) continue;
                int v = e[0];
                long nd = (long) dist[u] + e[1];
                if (nd < dist[v]) {
                    dist[v] = (int) nd;
                    heap.add(new long[]{nd, v});
                }
            }
        }
        return dist;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Coarse (÷3) order-independent endpoint-pair key, so near-parallel shafts collapse to one. */
    private static long shaftKey(P a, P b) {
        long ka = cellKey3(a), kb = cellKey3(b);
        long lo = Math.min(ka, kb), hi = Math.max(ka, kb);
        return (lo << 30) ^ hi;
    }

    /** ÷3 cell key; local coords are non-negative and small, so each field fits in 10 bits. */
    private static long cellKey3(P p) {
        return (((long) (p.x() / 3)) << 20) | (((long) (p.y() / 3)) << 10) | (long) (p.z() / 3);
    }

    /** Index of the walkable node minimising squared-Euclidean distance to c within c ± radius, or -1. */
    public int nearestNode(P c, int radius) {
        int best = -1;
        long bestd = Long.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Integer j = index.get(packNode(c.x() + dx, c.y() + dy, c.z() + dz));
                    if (j != null) {
                        long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                        if (d < bestd) {
                            bestd = d;
                            best = j;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Dijkstra from src over (node × arrival-info) states; returns {dist, prev}, each of length
     * STATES_PER_NODE × nodes (unreachable: MAX_VALUE / -1). A trident can't follow a trident; a walk after a
     * walk pays the heading-change turn cost; a straight-down drop keeps the prior heading (no turn either side).
     */
    public int[][] dijkstra(int src) {
        int states = STATES_PER_NODE * nodes.size();
        int[] dist = new int[states];
        int[] prev = new int[states];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(prev, -1);
        int start = src * STATES_PER_NODE + START_INFO;
        dist[start] = 0;
        PriorityQueue<long[]> heap = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        heap.add(new long[]{0, start});
        while (!heap.isEmpty()) {
            long[] top = heap.poll();
            int s = (int) top[1];
            if (top[0] > dist[s]) continue;
            int u = s / STATES_PER_NODE;
            int info = s % STATES_PER_NODE;
            boolean lastTrident = info == TRIDENT_INFO;
            int lastHeading = info <= 7 ? info : -1;
            int[] to = adjTo.get(u);
            int[] w = adjW.get(u);
            int[] md = adjMode.get(u);
            int[] dr = adjDir.get(u);
            for (int k = 0; k < to.length; k++) {
                int m = md[k];
                int add = w[k];
                int newInfo;
                if (m == 1) {
                    if (lastTrident) continue;
                    newInfo = TRIDENT_INFO;
                } else if (m == 2 && dr[k] < 0) {
                    newInfo = lastHeading >= 0 ? lastHeading : START_INFO;
                } else {
                    newInfo = dr[k];
                    if (lastHeading >= 0) add += turnCenti[lastHeading * 8 + newInfo];
                }
                int vs = to[k] * STATES_PER_NODE + newInfo;
                long nd = (long) dist[s] + add;
                if (nd < dist[vs]) {
                    dist[vs] = (int) nd;
                    prev[vs] = s;
                    heap.add(new long[]{nd, vs});
                }
            }
        }
        return new int[][]{dist, prev};
    }

    /** Node index carried by a state id. */
    public static int stateNode(int state) {
        return state / STATES_PER_NODE;
    }

    /** Mode of the edge that arrived at this state: 1 = trident, 0 = walk/drop (or start). */
    public static int stateMode(int state) {
        return (state % STATES_PER_NODE) == TRIDENT_INFO ? 1 : 0;
    }

    /**
     * Mode of the edge {@code fromState} → {@code toState}: 1 = trident, 2 = drop, 0 = walk. A drop is
     * recognised geometrically: a fall of {@link #MIN_DROP}+ blocks within a 1-block horizontal footprint.
     */
    public int edgeMode(int fromState, int toState) {
        if (stateMode(toState) == 1) return 1;
        P a = nodes.get(stateNode(fromState)), b = nodes.get(stateNode(toState));
        boolean column = Math.abs(b.x() - a.x()) <= 1 && Math.abs(b.z() - a.z()) <= 1;
        return (column && a.y() - b.y() >= MIN_DROP) ? 2 : 0;
    }

    /** Best distance to a node over all its arrival states (centi-blocks), or Integer.MAX_VALUE. */
    public static int nodeDist(int[][] dijk, int node) {
        int base = node * STATES_PER_NODE, best = Integer.MAX_VALUE;
        for (int i = 0; i < STATES_PER_NODE; i++) best = Math.min(best, dijk[0][base + i]);
        return best;
    }

    /** State path (src → best-arrival state at targetNode) as state ids; empty if unreachable. */
    public int[] statePath(int[][] dijk, int targetNode) {
        int[] dist = dijk[0], prev = dijk[1];
        int base = targetNode * STATES_PER_NODE, end = -1, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < STATES_PER_NODE; i++) {
            if (dist[base + i] < bestD) { bestD = dist[base + i]; end = base + i; }
        }
        if (end < 0 || bestD == Integer.MAX_VALUE) return new int[0];
        List<Integer> out = new ArrayList<>();
        for (int cur = end; cur != -1; cur = prev[cur]) out.add(cur);
        Collections.reverse(out);
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** Node path (local coords) src → targetNode; empty if unreachable. */
    public List<P> pathToNode(int[][] dijk, int targetNode) {
        int[] sp = statePath(dijk, targetNode);
        List<P> out = new ArrayList<>(sp.length);
        for (int s : sp) out.add(nodes.get(stateNode(s)));
        return out;
    }
}
