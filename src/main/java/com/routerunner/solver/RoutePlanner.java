package com.routerunner.solver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The waypoint solver: greedy break selection with a bail cut, then a turn-aware NN + 2-opt tour
 * (entrance to exit) over walk/drop/trident/sprint legs, then a densified follow path that triggers every
 * reachable chest in order. Costs model execution difficulty (clearance, verticality, turns, LOS), in blocks.
 * All positions are in the grid's local coordinate space.
 */
public final class RoutePlanner {
    private static final System.Logger LOG = System.getLogger("Routerunner.RoutePlanner");
    /** Geometry reach used by selection (blocks). */
    public static final double REACH = 6.0;
    /** Max spacing between densified path points (blocks). */
    public static final double DENSIFY_STEP = 2.0;
    /** Search radius when snapping a position to the nearest walk-graph node (blocks). */
    public static final int ACCESS_RADIUS = 7;
    /** Path points each side used to measure a turn. */
    private static final int TURN_WINDOW = 4;
    /** Cap on how many path points back a turnaround approach is whitened. */
    private static final int TURN_WHITE_MAX = 8;

    private RoutePlanner() {}

    /** Tunable execution-difficulty cost weights (in model blocks unless noted), sourced from config; Gson-serialized for logs. */
    public static final class Params {
        /** Absolute leave-threshold (chests per travel/aim cost). 0 = auto: bailAggression × the room's hot-spot rate. */
        public double bail = 0.0;
        /** Bail at this fraction of the room's hot-spot marginal (higher = skim more). */
        public double bailAggression = 0.35;
        /** Travel cost multiplier at wall clearance <= 1 (open walk = 1.0). */
        public double tightMult = 3.9;
        /** Travel cost multiplier at clearance 2-3. */
        public double narrowMult = 1.6;
        /** Travel cost multiplier at clearance 4-6; >= 7 is 1.0. */
        public double midMult = 1.2;
        /** Clearance where the openness blend (turn factor, proximity radius) starts. */
        public int clearanceMin = 4;
        /** Clearance at which the openness blend saturates. */
        public int openSatClearance = 7;
        /** Selection penalty per not-yet-broken chest adjacent to a candidate (prefers edge chests). */
        public double corePenaltyWeight = 0.0;
        public double proximityBonus = 0.9;
        public double proximityRadius = 4.0;
        public double proximityRadiusOpen = 6.0;
        /** Access penalty per block a chest sits 3+ above the nearest standable spot. */
        public double abovePathWeight = 2.0;
        /** Access penalty per solid face beyond 4 (walls and unmined chests). */
        public double enclosureWeight = 4.0;
        /** Cost per block climbed. */
        public double upCost = 0.5;
        public double downCost = 0.3;
        public double turnWeight = 7.0;
        /** Turn multiplier in fully open space (1.0 in tight space). */
        public double turnOpenFactor = 0.7;
        /** Walk-graph per-turn cost (blocks per radian of heading change); straightens the floor path. 0 disables. */
        public double pathTurnWeight = 0.2;
        public boolean losRequired = true;
        /** Follow-pass trigger reach (blocks): a chest is broken once the path comes this close. */
        public double breakReach = 4.5;
        /** Fixed selection cost of stopping at a waypoint; sets the scale of the marginals the bail cuts against. */
        public double waypointOverhead = 8.0;
        /** Flat cost of a drop edge. */
        public double dropActionCost = 1.0;
        /** Drop cost per sqrt(block) of fall height. */
        public double dropHeightWeight = 4.0;
        /** Tallest fall a drop edge will span (blocks); below {@link WalkGraph#MIN_DROP} no drop edges exist. */
        public int dropMaxHeight = 40;
        /** Flat cost of one trident shaft dash. */
        public double tridentActionCost = 30.0;
        public double tridentDistWeight = 0.04;
        public double tridentMinDist = 6.0;
        public int shaftMinVertical = 6;
        public double shaftMaxLen = 24.0;
        /** Ground blocks a steep shaft must save (walk distance minus dash cost) to be kept. */
        public double shaftMinSaving = 6.0;
        /** Same bar for shallow/horizontal shafts; inert while shaftCapHoriz = 0. */
        public double shaftMinSavingHoriz = 10.0;
        public int shaftCapVertical = 24;
        public int shaftCapHoriz = 0;
        public int shaftMaxDijkstra = 300;
        /** Cost per block of an open-space sprint straight-shot between chests (open walk = 1.0). */
        public double openSprintWeight = 0.55;
        public double openSprintMinDist = 3.0;
        public int openSprintMinClear = 2;
        public int openSprintMaxRise = 3;
        /** Equipped Chain Miner range/limit, read at solve time (see ChainMinerInfo). */
        public int chainRange = 6;
        public int chainLimit = 32;
        /** Player MOVEMENT_SPEED attribute at solve time; logged only, no cost term reads it. */
        public double speedAttr = 0.1;
        /** Angle (degrees) past which a route bend counts as a turnaround; display only. */
        public int turnaroundDeg = 120;

        /** Single-line JSON of every field, for the logs. */
        public String toJson() {
            return new com.google.gson.Gson().toJson(this);
        }
    }

    public static double euclid(P a, P b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Angle (radians) between two vectors; 0 if either is ~zero-length. */
    private static double angle(double ux, double uy, double uz, double vx, double vy, double vz) {
        double un = Math.sqrt(ux * ux + uy * uy + uz * uz), vn = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (un < 1e-6 || vn < 1e-6) return 0.0;
        double c = (ux * vx + uy * vy + uz * vz) / (un * vn);
        if (c > 1) c = 1; else if (c < -1) c = -1;
        return Math.acos(c);
    }

    /**
     * Score a trajectory (local float points {x,y,z}) under the walk-graph move-cost terms. Returns
     * {distance, turn, vertical, clearance, total} in blocks. Terms are distance-weighted and turns are measured
     * over a 1.5-block heading window, so sampling density doesn't bias the score. Walk-cost model only.
     */
    public static double[] scoreTrajectory(java.util.List<double[]> pts, SolidGrid solid, Params pm) {
        double dist = 0, turn = 0, vert = 0, clr = 0;
        double ax = 0, az = 0, prevHeading = 0;
        boolean haveAnchor = false, havePrevH = false;
        final double HEAD_WIN = 1.5;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double horiz = Math.hypot(dx, dz);
            if (horiz < 1e-9 && Math.abs(dy) < 1e-9) continue;
            int clrAt = solid.clearanceFlyAt((int) Math.round(b[0]), (int) Math.round(b[1]), (int) Math.round(b[2]));
            dist += horiz;
            clr += horiz * (clearanceMult(clrAt, pm) - 1.0);
            if (dy > 0) vert += pm.upCost * dy; else if (dy < 0) vert += pm.downCost * (-dy);
            if (!haveAnchor) { ax = a[0]; az = a[2]; haveAnchor = true; }
            double wdx = b[0] - ax, wdz = b[2] - az;
            if (Math.hypot(wdx, wdz) >= HEAD_WIN) {
                double h = Math.atan2(wdz, wdx);
                if (havePrevH) {
                    double diff = Math.abs(h - prevHeading);
                    if (diff > Math.PI) diff = 2 * Math.PI - diff;
                    turn += pm.pathTurnWeight * diff;
                }
                prevHeading = h; havePrevH = true; ax = b[0]; az = b[2];
            }
        }
        return new double[]{dist, turn, vert, clr, dist + turn + vert + clr};
    }

    private static P lerpRound(P a, P b, double t) {
        return new P(
            (int) Math.round(a.x() + (b.x() - a.x()) * t),
            (int) Math.round(a.y() + (b.y() - a.y()) * t),
            (int) Math.round(a.z() + (b.z() - a.z()) * t));
    }

    /** True if the straight segment a→b is clear of walls (target chests don't block). */
    static boolean losClear(P a, P b, SolidGrid solid) {
        double len = euclid(a, b);
        int dense = Math.max(1, (int) Math.ceil(len * 3.0));
        for (int i = 1; i < dense; i++) {
            if (solid.isSolidFly(lerpRound(a, b, (double) i / dense))) return false;
        }
        return true;
    }

    /** True if the straight body-line a→b passes through any solid at feet or head level. */
    private static boolean clipsBody(P a, P b, SolidGrid solid) {
        double len = euclid(a, b);
        int dense = Math.max(1, (int) Math.ceil(len * 3.0));
        for (int i = 1; i < dense; i++) {
            P s = lerpRound(a, b, (double) i / dense);
            if (solid.isSolid(s.x(), s.y(), s.z()) || solid.isSolid(s.x(), s.y() + 1, s.z())) return true;
        }
        return false;
    }

    private static boolean bodyClear(P p, SolidGrid solid) {
        return !solid.isSolid(p.x(), p.y(), p.z()) && !solid.isSolid(p.x(), p.y() + 1, p.z());
    }

    /**
     * Corner-safe polyline for one walk edge. Step-ups stay straight; level moves and drops that would clip
     * a block dogleg through whichever corner is open, else stay straight.
     */
    private static List<P> walkRender(P a, P b, SolidGrid solid) {
        List<P> out = new ArrayList<>();
        out.add(a);
        if (b.y() <= a.y() && clipsBody(a, b, solid)) {
            P c1 = new P(b.x(), a.y(), b.z());
            P c2 = new P(a.x(), b.y(), a.z());
            if (bodyClear(c1, solid) && !clipsBody(a, c1, solid) && !clipsBody(c1, b, solid)) out.add(c1);
            else if (bodyClear(c2, solid) && !clipsBody(a, c2, solid) && !clipsBody(c2, b, solid)) out.add(c2);
        }
        out.add(b);
        return out;
    }

    /**
     * Cost of an open-space sprint straight-shot a→b, or +INF if it doesn't apply: needs min distance, limited
     * rise, wall clearance at both ends, and wall LOS (target chests don't block).
     */
    private static double sprintCost(P a, P b, SolidGrid solid, Params pm) {
        if (pm.openSprintWeight <= 0) return Double.POSITIVE_INFINITY;
        double dist = euclid(a, b);
        if (dist < pm.openSprintMinDist) return Double.POSITIVE_INFINITY;
        if (Math.abs(b.y() - a.y()) > pm.openSprintMaxRise) return Double.POSITIVE_INFINITY;
        // endpoints are chest cells, so only wall-only clearance is meaningful here
        if (solid.clearanceFlyAt(a.x(), a.y(), a.z()) < pm.openSprintMinClear
                || solid.clearanceFlyAt(b.x(), b.y(), b.z()) < pm.openSprintMinClear) return Double.POSITIVE_INFINITY;
        if (!losClear(a, b, solid)) return Double.POSITIVE_INFINITY;
        return pm.openSprintWeight * dist;
    }

    /** True if node index i is a target-chest waypoint (not the fixed entrance 0 or exit n2-1). */
    private static boolean isChest(int i, int n2) {
        return i >= 1 && i <= n2 - 2;
    }

    /** Openness → turn multiplier: full penalty in tight space (clearance<=1), down to turnOpenFactor when open. */
    private static double turnFactor(int clearance, Params pm) {
        if (clearance <= 1) return 1.0;
        if (clearance >= pm.clearanceMin) return pm.turnOpenFactor;
        double t = (double) (clearance - 1) / Math.max(1, pm.clearanceMin - 1);
        return 1.0 + (pm.turnOpenFactor - 1.0) * t;
    }

    /** Travel cost multiplier for one block at this wall clearance (open = 1.0); the only clearance cost term. */
    static double clearanceMult(int clearance, Params pm) {
        if (clearance <= 1) return pm.tightMult;
        if (clearance <= 3) return pm.narrowMult;
        if (clearance <= 6) return pm.midMult;
        return 1.0;
    }

    /** Openness 0..1: 0 at/below clearanceMin, 1 at openSatClearance. Blends turn factor and proximity radius only. */
    static double opennessFrac(int clearance, Params pm) {
        int sat = Math.max(pm.clearanceMin + 1, pm.openSatClearance);
        double frac = (double) (clearance - pm.clearanceMin) / (sat - pm.clearanceMin);
        return frac < 0 ? 0.0 : (frac > 1 ? 1.0 : frac);
    }

    /** Count of not-yet-broken chests within Chebyshev 1 of a candidate. */
    private static int coreNeighbors(int idx, List<P> pts, ChainModel chain, boolean[] remaining) {
        P p = pts.get(idx);
        int bx = chain.bucketCoord(p.x()), by = chain.bucketCoord(p.y()), bz = chain.bucketCoord(p.z());
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> v = chain.buckets().get(chain.packBucket(bx + dx, by + dy, bz + dz));
                    if (v == null) continue;
                    for (int ci : v) {
                        if (ci == idx || !remaining[ci]) continue;
                        P q = pts.get(ci);
                        if (Math.abs(q.x() - p.x()) <= 1 && Math.abs(q.y() - p.y()) <= 1 && Math.abs(q.z() - p.z()) <= 1) n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * Reach cost of a candidate break: clearance-scaled distance beyond REACH, verticality, and aim (full yaw
     * change plus upward pitch), discounted by proximity to the previous break.
     */
    private static double selCost(P a, P b, SolidGrid solid, Params pm, double[] heading) {
        int clrAt = solid.clearanceFlyAt(b.x(), b.y(), b.z());
        double base = Math.max(euclid(a, b) - REACH, 0.5) * clearanceMult(clrAt, pm);
        double vert = Math.max(0, b.y() - a.y()) * pm.upCost + Math.max(0, a.y() - b.y()) * pm.downCost;
        double turn = 0.0;
        if (heading != null) {
            double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
            turn = pm.turnWeight * angle(heading[0], 0, heading[2], dx, 0, dz);
            if (dy > 0) {
                double flat = Math.max(1.0, Math.hypot(dx, dz));
                turn += pm.turnWeight * Math.atan2(dy, flat);
            }
        }
        double move = base + vert + turn;
        if (pm.proximityBonus > 0.0) {
            double d = euclid(a, b);
            double proxR = pm.proximityRadius + (pm.proximityRadiusOpen - pm.proximityRadius) * opennessFrac(clrAt, pm);
            if (proxR > 1e-6 && d < proxR) {
                double t = d / proxR;
                move *= 1.0 - pm.proximityBonus * (1.0 - t);
            }
        }
        return move;
    }

    /**
     * Static accessibility penalty (blocks) for a chest: sitting 3+ above the nearest standable spot within
     * reach, and/or having 5+ of 6 faces solid.
     */
    private static double accessPenalty(P c, SolidGrid solid, Params pm) {
        double pen = 0.0;
        int highest = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = c.y() + 2; y >= c.y() - 6; y--) {
                    int x = c.x() + dx, z = c.z() + dz;
                    if (!solid.isSolid(x, y, z) && !solid.isSolid(x, y + 1, z) && solid.isSolid(x, y - 1, z)) {
                        if (y > highest) highest = y;
                        break;
                    }
                }
            }
        }
        int gap = highest == Integer.MIN_VALUE ? 6 : (c.y() - highest);
        if (gap >= 3) pen += pm.abovePathWeight * (gap - 2);
        int covered = 0;
        if (solid.isSolid(c.x() + 1, c.y(), c.z())) covered++;
        if (solid.isSolid(c.x() - 1, c.y(), c.z())) covered++;
        if (solid.isSolid(c.x(), c.y() + 1, c.z())) covered++;
        if (solid.isSolid(c.x(), c.y() - 1, c.z())) covered++;
        if (solid.isSolid(c.x(), c.y(), c.z() + 1)) covered++;
        if (solid.isSolid(c.x(), c.y(), c.z() - 1)) covered++;
        if (covered >= 5) pen += pm.enclosureWeight * (covered - 4);
        return pen;
    }

    /** Full greedy break order (run to completion) and each break's marginal value; the caller applies the bail. */
    public static final class Selection {
        /** Greedy break order over all clusters. */
        public final int[] breaks;
        /** Marginals including the access penalty; used to drop awkward chests. */
        public final double[] marginals;
        /** Marginals without the access penalty; used for the hot-spot rate and the prefix cut. */
        public final double[] cleanMarginals;
        Selection(int[] breaks, double[] marginals, double[] cleanMarginals) {
            this.breaks = breaks; this.marginals = marginals; this.cleanMarginals = cleanMarginals;
        }
    }

    /** Greedy selection: repeatedly break the chest with the best chain-clear / reach-cost ratio until none remain. */
    static Selection select(List<P> pts, ChainModel chain, P entrance, SolidGrid solid, Params pm, double[] access) {
        boolean[] remaining = new boolean[pts.size()];
        java.util.Arrays.fill(remaining, true);
        List<Integer> breaks = new ArrayList<>();
        List<Double> marg = new ArrayList<>();
        List<Double> margClean = new ArrayList<>();
        P cur = entrance;
        double[] heading = null;
        while (true) {
            int best = -1;
            double bs = Double.NEGATIVE_INFINITY;
            double bestValue = 0.0, bestClean = 0.0;
            for (int idx = 0; idx < pts.size(); idx++) {
                if (!remaining[idx]) continue;
                double proxy = chain.clearProxy(idx, remaining);
                double selClean = selCost(cur, pts.get(idx), solid, pm, heading);
                double sc = proxy / (selClean + pm.waypointOverhead + pm.corePenaltyWeight * coreNeighbors(idx, pts, chain, remaining));
                if (sc > bs) {
                    bs = sc;
                    best = idx;
                    bestClean = proxy / Math.max(selClean + pm.waypointOverhead, 0.5);
                    bestValue = proxy / Math.max(selClean + pm.waypointOverhead + access[idx], 0.5);
                }
            }
            if (best == -1) break;
            for (int j : chain.clearFrom(best, remaining)) remaining[j] = false;
            P bp = pts.get(best);
            heading = new double[]{bp.x() - cur.x(), bp.y() - cur.y(), bp.z() - cur.z()};
            breaks.add(best);
            marg.add(bestValue);
            margClean.add(bestClean);
            cur = bp;
        }
        int[] b = new int[breaks.size()];
        double[] m = new double[breaks.size()];
        double[] mc = new double[breaks.size()];
        for (int i = 0; i < b.length; i++) { b[i] = breaks.get(i); m[i] = marg.get(i); mc[i] = margClean.get(i); }
        return new Selection(b, m, mc);
    }

    /** The room's hot-spot rate: the 75th percentile of the break marginals. */
    static double hotSpotRate(double[] marginals) {
        if (marginals.length == 0) return 0.0;
        double[] s = marginals.clone();
        java.util.Arrays.sort(s);
        int idx = (int) Math.floor(0.75 * (s.length - 1));
        return s[idx];
    }

    /** Plan the full route for one room; all inputs and outputs are in local coordinates. */
    public static RoutePlan planRoute(SolidGrid solid, List<P> pts, P entrance, P exit, Params pm) {
        ChainModel chain = new ChainModel(pm.chainRange, pm.chainLimit, pts);
        double[] accessPen = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) accessPen[i] = accessPenalty(pts.get(i), solid, pm);
        Selection sel = select(pts, chain, entrance, solid, pm, accessPen);
        double hotSpot = hotSpotRate(sel.cleanMarginals);
        double effBail = pm.bail > 0.0 ? pm.bail : pm.bailAggression * hotSpot;
        List<Integer> breaks = new ArrayList<>();
        for (int i = 0; i < sel.breaks.length; i++) {
            if (i > 0 && sel.cleanMarginals[i] < effBail) break;
            if (i == 0 || sel.marginals[i] >= effBail) breaks.add(sel.breaks[i]);
        }

        WalkGraph graph = WalkGraph.build(solid, pm);

        // keep only breaks graph-connected to the entrance
        int entAccess = graph.nearestNode(entrance, ACCESS_RADIUS);
        int[][] entDijk = entAccess >= 0 ? graph.dijkstra(entAccess) : null;
        if (entDijk != null) {
            List<Integer> reachable = new ArrayList<>();
            for (int i : breaks) {
                int a = graph.nearestNode(pts.get(i), ACCESS_RADIUS);
                if (a >= 0 && WalkGraph.nodeDist(entDijk, a) != Integer.MAX_VALUE) reachable.add(i);
            }
            if (reachable.size() < breaks.size())
                LOG.log(System.Logger.Level.INFO, "dropped " + (breaks.size() - reachable.size())
                        + " unreachable chest(s) from the route (of " + breaks.size() + ")");
            breaks = reachable;
        }

        List<P> nodes = new ArrayList<>();
        nodes.add(entrance);
        for (int i : breaks) nodes.add(pts.get(i));
        nodes.add(exit);
        int n2 = nodes.size();

        int[] access = new int[n2];
        int[][][] dijk = new int[n2][][];
        for (int i = 0; i < n2; i++) {
            access[i] = graph.nearestNode(nodes.get(i), ACCESS_RADIUS);
            dijk[i] = access[i] >= 0 ? graph.dijkstra(access[i]) : null;
        }

        // leg cost = cheaper of the graph path (walk/drop/shaft) and an open-space sprint
        double[][] cost = new double[n2][n2];
        for (int i = 0; i < n2; i++) {
            for (int j = i + 1; j < n2; j++) {
                double w = Math.min(walkBlocks(i, j, access, dijk), walkBlocks(j, i, access, dijk));
                double sp = (isChest(i, n2) && isChest(j, n2))
                        ? sprintCost(nodes.get(i), nodes.get(j), solid, pm) : Double.POSITIVE_INFINITY;
                double c = Math.min(w, sp);
                if (Double.isInfinite(c)) c = euclid(nodes.get(i), nodes.get(j)) * 50.0;
                cost[i][j] = c;
                cost[j][i] = c;
            }
        }

        // open-path TSP with fixed endpoints: turn-aware NN, then turn-aware 2-opt
        List<Integer> tour = new ArrayList<>();
        tour.add(0);
        Set<Integer> unvis = new HashSet<>();
        for (int k = 1; k < n2 - 1; k++) unvis.add(k);
        int cur = 0;
        double[] hd = null;
        while (!unvis.isEmpty()) {
            int nx = -1;
            double bestc = Double.POSITIVE_INFINITY;
            int curClr = solid.clearanceFlyAt(nodes.get(cur).x(), nodes.get(cur).y(), nodes.get(cur).z());
            for (int u : unvis) {
                double c = cost[cur][u];
                if (hd != null) {
                    P a = nodes.get(cur), b = nodes.get(u);
                    c += pm.turnWeight * turnFactor(curClr, pm) * angle(hd[0], hd[1], hd[2], b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
                }
                if (c < bestc) { bestc = c; nx = u; }
            }
            P a = nodes.get(cur), b = nodes.get(nx);
            hd = new double[]{b.x() - a.x(), b.y() - a.y(), b.z() - a.z()};
            tour.add(nx);
            unvis.remove(nx);
            cur = nx;
        }
        tour.add(n2 - 1);
        if (tour.size() > 3) {
            double best = tourCost(tour, nodes, cost, pm, solid);
            boolean improved = true;
            while (improved) {
                improved = false;
                for (int i = 1; i < tour.size() - 2; i++) {
                    for (int k = i + 1; k < tour.size() - 1; k++) {
                        Collections.reverse(tour.subList(i, k + 1));
                        double c = tourCost(tour, nodes, cost, pm, solid);
                        if (c + 1e-6 < best) {
                            best = c;
                            improved = true;
                        } else {
                            Collections.reverse(tour.subList(i, k + 1));
                        }
                    }
                }
            }
        }

        List<RoutePlan.Seg> segments = new ArrayList<>();
        double walkB = 0, flyB = 0;
        for (int ti = 0; ti + 1 < tour.size(); ti++) {
            int i = tour.get(ti), j = tour.get(ti + 1);
            P ni = nodes.get(i), nj = nodes.get(j);
            double walk = walkBlocks(i, j, access, dijk);
            double sprint = (isChest(i, n2) && isChest(j, n2)) ? sprintCost(ni, nj, solid, pm) : Double.POSITIVE_INFINITY;
            boolean walkOk = access[i] >= 0 && access[j] >= 0 && !Double.isInfinite(walk);
            if (walkOk && walk <= sprint) {
                int[] statePath = graph.statePath(dijk[i], access[j]);
                double[] wf = emitModeSegments(graph, statePath, segments);
                walkB += wf[0];
                flyB += wf[1];
            } else if (!Double.isInfinite(sprint)) {
                List<P> straight = new ArrayList<>();
                straight.add(ni);
                straight.add(nj);
                walkB += euclid(ni, nj);
                segments.add(new RoutePlan.Seg('s', straight));
            } else {
                List<P> straight = new ArrayList<>();
                straight.add(ni);
                straight.add(nj);
                segments.add(new RoutePlan.Seg('x', straight));
            }
        }

        List<P> pathPts = new ArrayList<>();
        List<Character> pathMode = new ArrayList<>();
        List<Double> pathCum = new ArrayList<>();
        List<Boolean> pathTrig = new ArrayList<>(); // can loot at this point: walk/sprint always, bursts only on landing
        double cum = 0;
        P last = null;
        for (RoutePlan.Seg seg : segments) {
            List<P> poly;
            if (seg.mode == 'w') {
                poly = new ArrayList<>();
                poly.add(seg.poly.get(0));
                for (int wi = 0; wi + 1 < seg.poly.size(); wi++) {
                    List<P> dl = walkRender(seg.poly.get(wi), seg.poly.get(wi + 1), solid);
                    for (int di = 1; di < dl.size(); di++) poly.add(dl.get(di));
                }
            } else {
                poly = seg.poly;
            }
            for (int wi = 0; wi + 1 < poly.size(); wi++) {
                P w0 = poly.get(wi), w1 = poly.get(wi + 1);
                int steps = (int) Math.max(1, Math.ceil(euclid(w0, w1) / DENSIFY_STEP));
                for (int s = 0; s <= steps; s++) {
                    P p = lerpRound(w0, w1, (double) s / steps);
                    if (last != null) cum += euclid(last, p);
                    boolean landing = (wi + 2 == poly.size()) && (s == steps);
                    pathPts.add(p);
                    pathMode.add(seg.mode);
                    pathCum.add(cum);
                    pathTrig.add(seg.mode == 'w' || seg.mode == 's' || landing);
                    last = p;
                }
            }
        }

        boolean[] remaining = new boolean[pts.size()];
        java.util.Arrays.fill(remaining, true);
        boolean[] triggered = new boolean[pts.size()];
        List<RoutePlan.WP> waypoints = new ArrayList<>();
        for (int pi = 0; pi < pathPts.size(); pi++) {
            if (!pathTrig.get(pi)) continue;
            P p = pathPts.get(pi);
            P head = new P(p.x(), p.y() + 1, p.z()); // LOS is cast from head height
            int bx = chain.bucketCoord(p.x()), by = chain.bucketCoord(p.y()), bz = chain.bucketCoord(p.z());
            int br = chain.bucketRadius(pm.breakReach);
            while (true) {
                int best = -1;
                double bestd = pm.breakReach;
                for (int dx = -br; dx <= br; dx++) {
                    for (int dy = -br; dy <= br; dy++) {
                        for (int dz = -br; dz <= br; dz++) {
                            List<Integer> v = chain.buckets().get(chain.packBucket(bx + dx, by + dy, bz + dz));
                            if (v == null) continue;
                            for (int ci : v) {
                                if (!remaining[ci]) continue;
                                if (accessPen[ci] > 0.0) continue; // awkward chests are never triggered, only chain-cleared
                                double d = euclid(p, pts.get(ci));
                                if (d <= bestd && (!pm.losRequired || losClear(head, pts.get(ci), solid))) {
                                    bestd = d;
                                    best = ci;
                                }
                            }
                        }
                    }
                }
                if (best == -1) break;
                List<Integer> cleared = chain.clearFrom(best, remaining);
                waypoints.add(new RoutePlan.WP(pts.get(best), cleared.size(), pathMode.get(pi), pathCum.get(pi), pi));
                triggered[best] = true;
                for (int j : cleared) remaining[j] = false;
            }
        }

        // turnarounds: test each waypoint's run in vs out, then sweep the path; both whiten the approach
        boolean[] pathWhite = new boolean[pathPts.size()];
        double turnCos = Math.cos(Math.toRadians(clampDeg(pm.turnaroundDeg)));
        for (RoutePlan.WP wp : waypoints) {
            int pi = wp.pathIndex;
            if (pi < 0 || pi >= pathPts.size()) continue;
            P before = pathPts.get(Math.max(pi - TURN_WINDOW, 0));
            P here = pathPts.get(pi);
            P after = pathPts.get(Math.min(pi + TURN_WINDOW, pathPts.size() - 1));
            double ix = here.x() - before.x(), iz = here.z() - before.z();
            double ox = after.x() - here.x(), oz = after.z() - here.z();
            double in = Math.hypot(ix, iz), on = Math.hypot(ox, oz);
            if (in <= 0.5 || on <= 0.5) continue;
            if ((ix * ox + iz * oz) / (in * on) >= turnCos) continue;
            wp.turnaround = true;
            wp.outDirX = ox / on;
            wp.outDirZ = oz / on;
            for (int j = pi - 1; j >= Math.max(pi - TURN_WHITE_MAX, 0); j--) {
                if (pathMode.get(j) == 'x') continue;
                pathWhite[j] = true;
            }
        }
        for (int i = TURN_WINDOW; i + TURN_WINDOW < pathPts.size(); i++) {
            P a = pathPts.get(i - TURN_WINDOW), b = pathPts.get(i), c = pathPts.get(i + TURN_WINDOW);
            double ix = b.x() - a.x(), iz = b.z() - a.z(), ox = c.x() - b.x(), oz = c.z() - b.z();
            double in = Math.hypot(ix, iz), on = Math.hypot(ox, oz);
            if (in < 1e-6 || on < 1e-6) continue;
            if ((ix * ox + iz * oz) / (in * on) < turnCos) {
                char m = pathMode.get(i - 1);
                if (m == 'x') continue;
                for (int j = i - 1, n = 0; j >= 0 && n < TURN_WHITE_MAX && pathMode.get(j) == m; j--, n++) pathWhite[j] = true;
            }
        }

        char[] pmArr = new char[pathMode.size()];
        for (int i = 0; i < pmArr.length; i++) pmArr[i] = pathMode.get(i);

        int collected = 0;
        char[] state = new char[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            if (triggered[i]) state[i] = 'b';
            else if (!remaining[i]) state[i] = 'c';
            else state[i] = 's';
            if (!remaining[i]) collected++;
        }

        RoutePlan plan = new RoutePlan();
        plan.waypoints = waypoints;
        plan.segments = segments;
        plan.exitLeg = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        plan.path = pathPts;
        plan.pathMode = pmArr;
        plan.pathWhite = pathWhite;
        plan.graph = graph;
        plan.state = state;
        plan.collected = collected;
        plan.walkBlocks = walkB;
        plan.flyBlocks = flyB;
        plan.hotSpotRate = hotSpot;
        return plan;
    }

    /** Clamp the turnaround angle to [1,180], logging when a configured value is out of range. */
    private static int clampDeg(int deg) {
        if (deg >= 1 && deg <= 180) return deg;
        int clamped = deg < 1 ? 1 : 180;
        LOG.log(System.Logger.Level.ERROR,
                "[Routerunner] turnaroundDeg " + deg + " is outside [1,180]; using " + clamped + " for this solve.");
        return clamped;
    }

    private static double tourCost(List<Integer> tour, List<P> nodes, double[][] cost, Params pm, SolidGrid solid) {
        double sum = 0;
        for (int t = 0; t + 1 < tour.size(); t++) sum += cost[tour.get(t)][tour.get(t + 1)];
        for (int t = 1; t + 1 < tour.size(); t++) {
            P a = nodes.get(tour.get(t - 1)), b = nodes.get(tour.get(t)), c = nodes.get(tour.get(t + 1));
            int bClr = solid.clearanceFlyAt(b.x(), b.y(), b.z());
            sum += pm.turnWeight * turnFactor(bClr, pm)
                * angle(b.x() - a.x(), b.y() - a.y(), b.z() - a.z(), c.x() - b.x(), c.y() - b.y(), c.z() - b.z());
        }
        return sum;
    }

    private static double walkBlocks(int i, int j, int[] access, int[][][] dijk) {
        if (access[j] >= 0 && dijk[i] != null) {
            int d = WalkGraph.nodeDist(dijk[i], access[j]);
            if (d != Integer.MAX_VALUE) return d / 100.0;
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * Split a graph state path into consecutive same-mode runs, appending a Seg each; returns
     * {walkBlocks, tridentBlocks}. Drop length counts as walk distance.
     */
    private static double[] emitModeSegments(WalkGraph graph, int[] statePath, List<RoutePlan.Seg> segments) {
        double wB = 0, fB = 0;
        int t = 0;
        while (t + 1 < statePath.length) {
            int mode = graph.edgeMode(statePath[t], statePath[t + 1]);
            int start = t;
            while (t + 1 < statePath.length && graph.edgeMode(statePath[t], statePath[t + 1]) == mode) t++;
            List<P> poly = new ArrayList<>();
            double len = 0;
            for (int u = start; u <= t; u++) {
                P q = graph.nodes.get(WalkGraph.stateNode(statePath[u]));
                if (!poly.isEmpty()) len += euclid(poly.get(poly.size() - 1), q);
                poly.add(q);
            }
            segments.add(new RoutePlan.Seg(mode == 1 ? 't' : (mode == 2 ? 'd' : 'w'), poly));
            if (mode == 1) fB += len; else wB += len;
        }
        return new double[]{wB, fB};
    }
}
