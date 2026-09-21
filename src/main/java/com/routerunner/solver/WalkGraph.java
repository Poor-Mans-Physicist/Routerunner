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
 * Movement graph for the hybrid model. Nodes are standable cells (feet+head clear, solid floor below).
 * Three edge kinds, each tagged with a mode:
 *   - WALK ('w'): step up 1 / level / drop up to 3, cost = distance x the measured clearance multiplier,
 *     plus verticality (centi-blocks).
 *   - DROP ('d'): step off a ledge and FALL 4+ blocks to the first floor below. Players simply drop where
 *     the solver used to route them down a staircase or price a 30-block trident shaft, so a fall is its
 *     own cheap edge: cost = dropActionCost + dropHeightWeight x sqrt(h), with no speed lost either side.
 *   - TRIDENT ('t'): a SHAFT dash between two standable cells across a clear line — a real vertical or
 *     slanted run, not per-cell spam. Shafts are found by ray-marching each cell along vertical / 45° /
 *     2:1 / 3:1 slopes (up and down) to the farthest standable landing that ends against a block, then
 *     KEPT only when they save travel (ground-distance minus dash cost); a landing reachable only by dash
 *     is always kept. Dijkstra runs over (node × last-edge-mode) states so two trident edges can't chain
 *     back-to-back (you must walk between dashes); a DROP is exempt — it may follow anything. Horizontal
 *     open-space repositioning is sprint-lines, handled per-leg in RoutePlanner.
 */
public final class WalkGraph {
    public static final int CARD = 100;
    public static final int DIAG = 141;

    // Dijkstra runs over (node × arrival-info) STATES. arrival-info encodes HOW you reached the node, so the
    // path can be charged for TURNING (heading changes) and forbidden from chaining tridents:
    //   0..7 = arrived by a WALK edge with that compass heading (E,NE,N,NW,W,SW,S,SE — 45° apart, circular)
    //   8    = arrived by a TRIDENT dash (no heading; can't dash again on the next edge)
    //   9    = the START node (no heading → no turn cost on the first move; a dash is allowed)
    public static final int STATES_PER_NODE = 10;
    private static final int TRIDENT_INFO = 8;
    private static final int START_INFO = 9;
    /** Shortest fall that becomes a DROP edge; 1..3 down is already a walk edge, and it doubles as the
     *  geometric signature that tells a drop apart from a walk when a state path is split into segments. */
    static final int MIN_DROP = 4;
    // (dx+1)*3 + (dz+1)  ->  circular compass heading 0..7 (center/no-move = -1); dx,dz in {-1,0,1}.
    private static final int[] HEADING_OF = {5, 4, 3, 6, -1, 2, 7, 0, 1};

    // Ray-march directions for shafts: primitive (dx,dy,dz) vectors with a vertical component. Vertical,
    // 45°, steep 2:1/3:1, and shallow 1:2/1:3, on each axis (diagonals only at 45° to bound the count).
    private static final int[][] SHAFT_DIRS = buildShaftDirs();

    private static final System.Logger LOG = System.getLogger("Routerunner.WalkGraph");

    public final List<P> nodes = new ArrayList<>();
    private final Map<Long, Integer> index = new HashMap<>();
    private final List<int[]> adjTo = new ArrayList<>();
    private final List<int[]> adjW = new ArrayList<>();
    private final List<int[]> adjMode = new ArrayList<>(); // per edge: 0 = walk, 1 = trident, 2 = drop
    private final List<int[]> adjDir = new ArrayList<>();  // per edge: walk/diagonal-drop heading 0..7; trident and straight-down drop = -1
    private int[] turnCenti = new int[64];                 // [fromHeading*8 + toHeading] turn penalty (centi-blocks)

    private static long packNode(int x, int y, int z) {
        return (((long) (x + 512)) << 40) | (((long) (y + 512)) << 20) | (long) (z + 512);
    }

    public static WalkGraph build(SolidGrid g, RoutePlanner.Params pm) {
        WalkGraph wg = new WalkGraph();
        wg.turnCenti = buildTurnTable(pm.pathTurnWeight); // per-turn cost baked in at build; 0 disables (see dijkstra)
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
        List<List<int[]>> adj = new ArrayList<>(n); // each entry {toIndex, weight, mode}
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

        // ---- walk edges ----
        int[][] dirs = {
            {1, 0, CARD}, {-1, 0, CARD}, {0, 1, CARD}, {0, -1, CARD},
            {1, 1, DIAG}, {1, -1, DIAG}, {-1, 1, DIAG}, {-1, -1, DIAG}
        };
        int[] dys = {1, 0, -1, -2, -3};
        for (int i = 0; i < n; i++) {
            P p = wg.nodes.get(i);
            for (int[] d : dirs) {
                // A diagonal step is blocked only if BOTH orthogonal cells are walls (a true diagonal gap).
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
                        int clrAt = g.clearanceFlyAt(nx, ny, nz); // WALL-only: a dense chest cluster isn't "tight" — you break through it
                        int vert = dy > 0 ? (int) Math.round(pm.upCost * 100.0 * dy)
                                          : (int) Math.round(pm.downCost * 100.0 * (-dy));
                        int baseMove = (int) Math.round(d[2] * RoutePlanner.clearanceMult(clrAt, pm)); // measured per-band speed
                        adj.get(i).add(new int[]{j, baseMove + vert, 0, headingIndex(d[0], d[1])});
                        break;
                    }
                }
            }
        }

        // ---- DROP edges: step off a ledge and fall to the first floor below ----
        Set<Long> dropPairs = addDropEdges(wg, g, pm, adj);

        // ---- trident/dash SHAFT edges: ray-marched, selected by travel saved, bidirectional ----
        addShaftEdges(wg, g, pm, adj, dropPairs);

        // ---- pack ----
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
     * DROP edges — stepping off a ledge and falling. From every standable node, each of the nine landing
     * columns (dx,dz in {-1,0,1}) is traced straight down: each column cell AND its head cell must be
     * non-solid, where TARGET CHESTS COUNT AS SOLID (isSolid, not isSolidFly) because you cannot fall
     * through a chest. Scanning stops at the FIRST standable landing node found 4+ blocks down — a fall
     * ends at the first floor — and a solid cell reached before any landing means no drop edge at all.
     * Heights 1..3 are left to the walk edges, which already step down that far.
     * <p>Cost = dropActionCost + dropHeightWeight x sqrt(h): a fall of h blocks takes ~sqrt(2h/32) s, which
     * at the measured ~16.7 blk/s open-walk speed is ~4.2·sqrt(h) blocks of equivalent travel, and a drop
     * costs no speed before or after it (unlike a staircase or a dash).
     * <p>Returns the ordered (from,to) node pairs a drop now covers, so {@link #addShaftEdges} can suppress
     * the redundant DOWNWARD trident dash over the same pair (shafts are added bidirectionally, so simply
     * dropping the straight-down ray-march direction would not have removed it).
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
                        if (g.isSolid(nx, cy, nz) || g.isSolid(nx, cy + 1, nz)) break; // the fall is blocked here
                        if (h < MIN_DROP) continue;                                    // 1..3 down is a walk edge
                        Integer cand = wg.index.get(packNode(nx, p.y() - h, nz));
                        if (cand != null) { land = cand; fell = h; break; }            // first floor = where you land
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

    /** Ordered (from,to) node-pair key, for "is this exact dash already covered by a drop?". */
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
                if (diag && !(h == 1 && v == 1)) continue; // 3D diagonals only at 45° (bound the direction count)
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
     * Precompute the 8×8 heading-change penalty (centi-blocks) from a blocks-per-radian weight. Headings are
     * 45° apart and indexed circularly, so the angle between i and j is min(|i-j|, 8-|i-j|) × 45°. A weight of
     * 0 yields an all-zero table (turn cost disabled — the pathfinder then behaves like the old 2-state one).
     */
    private static int[] buildTurnTable(double weightPerRad) {
        int[] t = new int[64];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                int diff = Math.abs(i - j);
                int steps = Math.min(diff, 8 - diff);      // 0..4, in units of 45°
                t[i * 8 + j] = (int) Math.round(weightPerRad * 100.0 * (steps * (Math.PI / 4.0)));
            }
        }
        return t;
    }

    /**
     * Ray-march shafts from every standable cell, dedupe, then keep the ones that SAVE travel.
     * A candidate is the farthest standable landing along a direction whose straight body-line is clear
     * (walls only; target chests are pass-through) and that ends against a block (a wall it stops at, or the
     * landing's own floor). Selection is by ground-distance saved (ground cost − dash cost) on the
     * walk+drop graph, split into vertical (>= shaftMinVertical rise) and horizontal/shallow, each capped.
     * Any direction of a kept shaft that a DROP edge already covers is skipped: falling is far cheaper than
     * a 30-block dash, so the dash would be dead weight in the queue.
     */
    private static void addShaftEdges(WalkGraph wg, SolidGrid g, RoutePlanner.Params pm, List<List<int[]>> adj,
                                      Set<Long> dropPairs) {
        int n = wg.nodes.size();
        HashMap<Long, int[]> dedup = new HashMap<>(); // endpoint-bucket key -> {aIdx, bIdx, lenCenti} (keep longest)
        int cap = (int) Math.ceil(pm.shaftMaxLen) + 4;
        int[] cx = new int[cap * 2], cy = new int[cap * 2], cz = new int[cap * 2];
        for (int i = 0; i < n; i++) {
            P a = wg.nodes.get(i);
            for (int[] d : SHAFT_DIRS) {
                double dl = Math.sqrt((double) d[0] * d[0] + (double) d[1] * d[1] + (double) d[2] * d[2]);
                double ux = d[0] / dl, uy = d[1] / dl, uz = d[2] / dl;
                // march the body-line until it hits a wall/ceiling, recording distinct clear cells
                int cnt = 0, lastX = a.x(), lastY = a.y(), lastZ = a.z();
                for (double t = 1.0; t <= pm.shaftMaxLen && cnt < cx.length; t += 0.5) {
                    int nx = (int) Math.round(a.x() + ux * t), ny = (int) Math.round(a.y() + uy * t), nz = (int) Math.round(a.z() + uz * t);
                    if (nx == lastX && ny == lastY && nz == lastZ) continue;
                    lastX = nx; lastY = ny; lastZ = nz;
                    if (g.isSolidFly(nx, ny, nz) || g.isSolidFly(nx, ny + 1, nz)) break; // tube ends against a block
                    cx[cnt] = nx; cy[cnt] = ny; cz[cnt] = nz; cnt++;
                }
                // farthest clear cell that has a standable landing node right beside it
                int bIdx = -1;
                double bLen = 0;
                for (int s = cnt - 1; s >= 0; s--) {
                    double len = Math.sqrt(sq(cx[s] - a.x()) + sq(cy[s] - a.y()) + sq(cz[s] - a.z()));
                    if (len < pm.tridentMinDist) break; // nothing closer can qualify either
                    int b = wg.nearestNode(new P(cx[s], cy[s], cz[s]), 1);
                    if (b < 0 || b == i) continue;
                    P bp = wg.nodes.get(b);
                    if (d[1] > 0 && bp.y() <= a.y()) continue;   // up shaft must land higher
                    if (d[1] < 0 && bp.y() >= a.y()) continue;   // down shaft must land lower
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

        // score each distinct candidate by how much walking it saves (walk-only Dijkstra per source, memoized)
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
                saving = len; // fallback: proxy saving by raw length (logged above)
            } else {
                int wd = dist[b];
                saving = (wd == Integer.MAX_VALUE) ? 1e9 : wd / 100.0 - tcost; // MAX = dash-only access → always keep
            }
            P pa = wg.nodes.get(a), pb = wg.nodes.get(b);
            double rise = Math.abs(pb.y() - pa.y());
            double horizExt = Math.hypot(pb.x() - pa.x(), pb.z() - pa.z());
            double[] row = {saving, a, b, Math.max(1, Math.round(tcost * 100))};
            // Keep only STEEP shafts (rise ≥ horizontal AND ≥ shaftMinVertical). A shallow diagonal (e.g. up 6
            // over 19 across) reads as a sideways teleport and makes the tour bounce — exclude it. Shallow/
            // horizontal shafts go in `horiz` (capped, default 0).
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
            if (!dropPairs.contains(pairKey(a, b))) adj.get(a).add(new int[]{b, cost, 1, -1}); // dash up (no heading)
            if (!dropPairs.contains(pairKey(b, a))) adj.get(b).add(new int[]{a, cost, 1, -1}); // back down (bidirectional)
        }
    }

    /** Dijkstra over the GROUND edges (walk + drop; trident not added yet) from src, in centi-blocks. */
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
                if (e[2] == 1) continue; // ground edges only (walk + drop)
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

    /** Coarse (÷3) endpoint-pair key, order-independent, so near-duplicate/parallel shafts collapse to one. */
    private static long shaftKey(P a, P b) {
        long ka = cellKey3(a), kb = cellKey3(b);
        long lo = Math.min(ka, kb), hi = Math.max(ka, kb);
        return (lo << 30) ^ hi;
    }

    private static long cellKey3(P p) {
        // local coords are >= 0 and small; ÷3 buckets fit in 10 bits each.
        return (((long) (p.x() / 3)) << 20) | (((long) (p.y() / 3)) << 10) | (long) (p.z() / 3);
    }

    public int nodeCount() {
        return nodes.size();
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
     * Dijkstra from src over (node × arrival-info) STATES → {dist, prev}, each length STATES_PER_NODE*n
     * (state = node*STATES_PER_NODE + info; info 0..7 = last WALK heading, 8 = trident arrival, 9 = start).
     * Two things ride on the arrival-info:
     *   - a TRIDENT edge can't be taken from a trident-arrival state → no back-to-back dashes (walk between);
     *   - a WALK edge onto a walk-arrival state pays a turn cost for the heading change (straightens the path).
     * The first move (from the start state) and the first walk after a dash pay no turn cost (no prior heading).
     * A DROP behaves like a walk edge and may follow anything (including a dash): a diagonal drop carries the
     * heading of its (dx,dz) and pays the normal walk→walk turn table, while a straight-down drop is
     * heading-NEUTRAL — it carries the prior heading through untouched, so falling costs no turn on either side.
     * Unreachable dist = MAX_VALUE, prev = -1.
     */
    public int[][] dijkstra(int src) {
        int states = STATES_PER_NODE * nodes.size();
        int[] dist = new int[states];
        int[] prev = new int[states];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(prev, -1);
        int start = src * STATES_PER_NODE + START_INFO; // no prior heading; first edge may be anything, turn-free
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
            int lastHeading = info <= 7 ? info : -1; // -1 = none (start or trident arrival) → no turn cost
            int[] to = adjTo.get(u);
            int[] w = adjW.get(u);
            int[] md = adjMode.get(u);
            int[] dr = adjDir.get(u);
            for (int k = 0; k < to.length; k++) {
                int m = md[k];
                int add = w[k];
                int newInfo;
                if (m == 1) {
                    if (lastTrident) continue;          // no trident right after a trident
                    newInfo = TRIDENT_INFO;
                } else if (m == 2 && dr[k] < 0) {
                    newInfo = lastHeading >= 0 ? lastHeading : START_INFO; // straight-down drop: heading-neutral
                } else {
                    newInfo = dr[k];                    // 0..7 heading of this walk / diagonal-drop edge
                    if (lastHeading >= 0) add += turnCenti[lastHeading * 8 + newInfo]; // walk→walk turn cost
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

    /** Mode of the edge that ARRIVED at this state: 1 = trident, 0 = walk/drop (or start). */
    public static int stateMode(int state) {
        return (state % STATES_PER_NODE) == TRIDENT_INFO ? 1 : 0;
    }

    /**
     * Mode of the edge that carried {@code fromState} → {@code toState}: 1 = trident, 2 = drop, 0 = walk.
     * A trident arrival is flagged in the state itself; a DROP is recognised GEOMETRICALLY rather than by
     * widening the state space, because an extra arrival bit would double every Dijkstra array and those are
     * retained one per tour node. The test is exact: only a drop edge falls {@link #MIN_DROP}+ blocks in a
     * single step inside a 1-block horizontal footprint — walk edges descend at most 3, and any longer fall
     * that was a dash already reports itself as a trident arrival.
     */
    public int edgeMode(int fromState, int toState) {
        if (stateMode(toState) == 1) return 1;
        P a = nodes.get(stateNode(fromState)), b = nodes.get(stateNode(toState));
        boolean column = Math.abs(b.x() - a.x()) <= 1 && Math.abs(b.z() - a.z()) <= 1;
        return (column && a.y() - b.y() >= MIN_DROP) ? 2 : 0;
    }

    /** Best distance to a NODE over all its arrival states (centi-blocks), or Integer.MAX_VALUE. */
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

    /** Node path (local coords) src → targetNode via states, for the from-player connector; empty if none. */
    public List<P> pathToNode(int[][] dijk, int targetNode) {
        int[] sp = statePath(dijk, targetNode);
        List<P> out = new ArrayList<>(sp.length);
        for (int s : sp) out.add(nodes.get(stateNode(s)));
        return out;
    }
}
