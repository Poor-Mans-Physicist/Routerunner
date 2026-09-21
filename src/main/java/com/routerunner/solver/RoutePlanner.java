package com.routerunner.solver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The waypoint solver: greedy break SELECTION with bail → hybrid walk/trident NN+2-opt ORDER
 * (entrance→exit) → densify the route and follow it, triggering every reachable chest in order (the
 * chain clears its group). All in the grid's LOCAL coordinate space; the caller transforms back.
 *
 * Costs model human EXECUTION difficulty, not just distance ({@link Params}): openness/clearance
 * (avoid tight corridors), asymmetric verticality (up hurts, down is cheap), turn/smoothness (avoid
 * hard aim/trajectory changes), and LOS-gated coverage (only route to chests you can actually see;
 * the chain still clears the rest). Legs are walk (sprint-jump the floor path, loot as you go), a DROP
 * (step off a ledge and fall — ground travel, cheap, but you only loot again on landing), or a
 * trident/dash burst (a ~5x reposition where distance is ~free but you can't loot mid-burst and can't
 * thread tight space), the last two baked into the walk graph as drop and vertical-shaft edges.
 */
public final class RoutePlanner {
    private static final System.Logger LOG = System.getLogger("Routerunner.RoutePlanner");
    public static final double REACH = 6.0;
    public static final double DENSIFY_STEP = 2.0;
    public static final int ACCESS_RADIUS = 7;
    private static final int TURN_WINDOW = 4;                              // path points each side used to measure a turn
    private static final int TURN_WHITE_MAX = 8;                           // cap on how far back the white approach runs

    private RoutePlanner() {}

    /** Tunable execution-difficulty cost weights (all in "blocks"), sourced from config. */
    public static final class Params {
        /** Absolute leave-threshold (chests per travel/aim cost). 0 = auto: bailAggression × the room's hot-spot rate. */
        public double bail = 0.0;
        /** Loot down to this fraction of the (policy-independent) hot-spot marginal — the throughput knob (higher = skim more). */
        public double bailAggression = 0.35;
        // MEASURED clearance cost multipliers (model blocks per block travelled; open walk = 1.0). From the
        // 2026-09-19 data run's median realized speed per wall-clearance band: 4.3 blk/s at 0-1, 10.2 at 2-3,
        // 14.2 at 4-6, 16.7 at >=7 — so tight space costs ~3.9x an open block, not the ~13.8x the old
        // clearanceWeight/tightPenalty/openSpeedBonus stack charged.
        public double tightMult = 3.9;  // clearance <= 1
        public double narrowMult = 1.6; // clearance 2-3
        public double midMult = 1.2;    // clearance 4-6 (>= 7 is open = 1.0, implicit)
        /** Openness BLEND anchor only (turnFactor / opennessFrac / proximity radius) — no cost term reads it. */
        public int clearanceMin = 4;
        /** Clearance at which the openness blend saturates — blend anchor only, no cost term reads it. */
        public int openSatClearance = 7;
        /** Edge-chest preference. MEASURED 0: preferring easy edge chests makes each trigger under-fill its
         *  chain, so dense rooms cost chests per stop; freehand runs hit cluster CENTRES. Slider kept for tuning. */
        public double corePenaltyWeight = 0.0;
        public double proximityBonus = 0.9;
        public double proximityRadius = 4.0;
        public double proximityRadiusOpen = 6.0;
        // accessibility penalties (blocks) — make awkward corner/ceiling chests expensive so the bail drops them
        public double abovePathWeight = 2.0; // per block a chest sits >=3 above the nearest standable spot (aim-up)
        public double enclosureWeight = 4.0; // per solid face beyond 4 (walls AND unmined chests) — near-buried chests
        /** Cost per block climbed. Measured near-free: a 1-block step-up costs no measurable time and the walk
         *  graph only ever has +1 edges, so this is a tie-breaker, not a real penalty. */
        public double upCost = 0.5;
        public double downCost = 0.3;
        public double turnWeight = 7.0;
        public double turnOpenFactor = 0.7; // turns still cost most of full even in open rooms — stops zig-zag tours
        // LIGHT per-turn cost on the WALK-GRAPH path itself (blocks per radian of heading change). Distinct from
        // turnWeight (which orders the selected breaks): this straightens the actual floor ribbon so it stops
        // zig-zagging between equal-cost grid steps. Small on purpose; 0 disables it. See NORTH_STAR / WalkGraph.
        public double pathTurnWeight = 0.2;
        public boolean losRequired = true;
        // Trigger reach for the SECOND pass (blocks): a chest is only broken as you follow the path once you come
        // within this distance. Lower = you break chests closer/more head-on (fewer wide cursor arcs, more human);
        // higher = you reach out to grab distant chests (log data: freehand aims at ~1.2-1.8 blk, routing was ~2.4).
        // Kept BELOW the geometry REACH (6.0) used for selection so the tour still plans around 6-blk clusters.
        public double breakReach = 4.5;
        /** Fixed cost of stopping at one waypoint at all (model blocks). MEASURED 0.45-0.55 s of aim/stop/break
         *  overhead per waypoint = ~8 open-walk blocks. SELECTION only — it is constant per waypoint, so it
         *  cannot change the tour ORDER, but it does set the scale of the marginals the bail cuts against. */
        public double waypointOverhead = 8.0;
        // DROP edges (step off a ledge and fall) — see WalkGraph#addDropEdges.
        /** Flat cost of committing to a fall (model blocks). Near-free: you keep full speed either side of it. */
        public double dropActionCost = 1.0;
        /** Cost per sqrt(block) of fall height. A fall of h takes ~sqrt(2h/32) s, or ~4.2·sqrt(h) open-walk blocks. */
        public double dropHeightWeight = 4.0;
        /** Tallest fall a drop edge will span (blocks). Below 4 no drop edges exist (walk edges cover 1-3 down). */
        public int dropMaxHeight = 40;
        // trident/dash burst: a single ~5x reposition in any direction; distance ~free, can't loot mid-burst,
        // hard to aim in tight space, needs a backstop to stop against. Cost = the action itself, measured.
        /** Measured: one trident shaft leg takes ~2.4 s regardless of length = ~40 open-walk blocks of travel.
         *  30.0 prices the action a little under that so shafts still win where they save a genuine climb. */
        public double tridentActionCost = 30.0;
        public double tridentDistWeight = 0.04;
        public double tridentMinDist = 6.0;
        // trident shafts (vertical/diagonal dash edges in the walk graph), selected by travel saved
        public int shaftMinVertical = 6;
        public double shaftMaxLen = 24.0;
        /** Walk blocks a vertical shaft must save (walkDist − tcost) to be kept. With the measured tcost (~30)
         *  this only passes for real vertical necessity — a long climb or a dash-only landing — which is intended. */
        public double shaftMinSaving = 6.0;
        /** Same bar for shallow/horizontal shafts, higher still; inert while shaftCapHoriz = 0. */
        public double shaftMinSavingHoriz = 10.0;
        public int shaftCapVertical = 24;
        public int shaftCapHoriz = 0; // horizontal shafts made the tour teleport sideways — vertical shafts only
        public int shaftMaxDijkstra = 300;
        // open-space sprint straight-shots (sprint-jump between visible chests, loots along)
        /** Measured: a sprint/dash line realizes ~31.6 blk/s against ~16.7 for an open walk = 0.53 of the
         *  open-walk cost per block. 0.55 keeps a hair of margin so marginal sprint lines don't fire. */
        public double openSprintWeight = 0.55;
        public double openSprintMinDist = 3.0;
        public int openSprintMinClear = 2;
        public int openSprintMaxRise = 3;
        // chain-miner tier actually equipped (read from the ability tree at solve time; see ChainMinerInfo)
        public int chainRange = 6;
        public int chainLimit = 32;
        /** Player MOVEMENT_SPEED attribute at solve time. INFORMATIONAL ONLY — no cost term reads it yet. */
        public double speedAttr = 0.1;
        /** Angle (degrees) past which a bend in the route counts as a TURNAROUND. DISPLAY ONLY: it marks the
         *  waypoints you double back from and whitens their approach; no cost term reads it. */
        public int turnaroundDeg = 120;
        /** True when {@code AdaptiveWeights} scaled the measured weights in this snapshot by the profile's own
         *  measured speeds. The effective numbers ARE the fields above; this only records that they are not
         *  the raw slider values. */
        public boolean adaptiveOn = false;

        /** Compact single-line JSON of every field — the exact weights a solve ran with, for the logs. */
        public String toJson() {
            return new com.google.gson.Gson().toJson(this);
        }
    }

    // ---- geometry helpers ----

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
     * Score an arbitrary trajectory (LOCAL float points {x,y,z}) under the SAME move-cost terms the walk graph
     * uses, so a human's actual path and the solver's route are comparable apples-to-apples (the diff-route tool).
     * Returns {distance, turn, vertical, clearance, total}, all in blocks. Every term is DISTANCE-weighted (not
     * per-sample) so a densely-sampled human trail isn't penalised vs the solver's coarse grid path, and the turn
     * heading is measured over a ~1.5-block window so grid/step quantisation doesn't inflate either side's turning.
     * (Walk-cost model only — trident/sprint legs should be excluded by the caller; report those separately.)
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
            int clrAt = solid.clearanceFlyAt((int) Math.round(b[0]), (int) Math.round(b[1]), (int) Math.round(b[2])); // wall-only
            dist += horiz;                                                         // raw blocks travelled
            clr += horiz * (clearanceMult(clrAt, pm) - 1.0);                       // measured tight-space surcharge
            if (dy > 0) vert += pm.upCost * dy; else if (dy < 0) vert += pm.downCost * (-dy);
            if (!haveAnchor) { ax = a[0]; az = a[2]; haveAnchor = true; }
            double wdx = b[0] - ax, wdz = b[2] - az;
            if (Math.hypot(wdx, wdz) >= HEAD_WIN) {                                // windowed heading → real turns only
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

    /** True if the straight segment a→b is clear of walls (target chests don't block; you see/break through). */
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
     * A corner-safe polyline for one walk edge. Step-UPS are drawn straight (a walkable ≤45° line) and never
     * doglegged — a vertical dogleg would look like scaling a wall. Level moves and drops dogleg through
     * whichever corner is open (a level L, or a natural drop-then-fall) so a straight ribbon never clips.
     */
    private static List<P> walkRender(P a, P b, SolidGrid solid) {
        List<P> out = new ArrayList<>();
        out.add(a);
        if (b.y() <= a.y() && clipsBody(a, b, solid)) {
            P c1 = new P(b.x(), a.y(), b.z()); // move flat, then drop
            P c2 = new P(a.x(), b.y(), a.z()); // drop, then move flat
            if (bodyClear(c1, solid) && !clipsBody(a, c1, solid) && !clipsBody(c1, b, solid)) out.add(c1);
            else if (bodyClear(c2, solid) && !clipsBody(a, c2, solid) && !clipsBody(c2, b, solid)) out.add(c2);
            // else: neither corner is clean — leave it straight (best effort)
        }
        out.add(b);
        return out;
    }

    /**
     * Cost of an open-space SPRINT straight-shot a→b, or +INF if it doesn't apply. Unlike a trident, you
     * sprint-jump the straight line and loot along it, so it only makes sense between two visible chests in
     * open space over a mostly-flat run — and it's much cheaper per block than walking. LOS uses isSolidFly
     * so intervening TARGET chests never block (you're assumed to clear them); walls do.
     */
    private static double sprintCost(P a, P b, SolidGrid solid, Params pm) {
        if (pm.openSprintWeight <= 0) return Double.POSITIVE_INFINITY;
        double dist = euclid(a, b);
        if (dist < pm.openSprintMinDist) return Double.POSITIVE_INFINITY;          // short hops: just walk
        if (Math.abs(b.y() - a.y()) > pm.openSprintMaxRise) return Double.POSITIVE_INFINITY; // it's a jump, not a climb
        // Openness = distance to the nearest WALL (chests excluded) — the endpoints ARE chest cells, so
        // a chest-inclusive clearance would read 0 there; clearanceFlyAt measures the space you sprint through.
        if (solid.clearanceFlyAt(a.x(), a.y(), a.z()) < pm.openSprintMinClear
                || solid.clearanceFlyAt(b.x(), b.y(), b.z()) < pm.openSprintMinClear) return Double.POSITIVE_INFINITY; // open-only
        if (!losClear(a, b, solid)) return Double.POSITIVE_INFINITY;               // a wall crosses the line
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

    /**
     * MEASURED cost multiplier for one block travelled at this wall-clearance, in model blocks (one block
     * walked in the open = 1.0). The bins are the median realized speeds of the 2026-09-19 data run —
     * 4.3 blk/s at clearance 0-1, 10.2 at 2-3, 14.2 at 4-6, 16.7 at >=7 — inverted against the open band.
     * This is the ONLY clearance term in the cost model; there is no separate gradient or tight step.
     */
    static double clearanceMult(int clearance, Params pm) {
        if (clearance <= 1) return pm.tightMult;
        if (clearance <= 3) return pm.narrowMult;
        if (clearance <= 6) return pm.midMult;
        return 1.0;
    }

    /**
     * Openness fraction 0..1: 0 at/below clearanceMin, ramping to 1 at openSatClearance. Used ONLY to blend
     * the non-cost shape terms (turn factor, proximity radius) — no travel cost reads it since v13.
     */
    static double opennessFrac(int clearance, Params pm) {
        int sat = Math.max(pm.clearanceMin + 1, pm.openSatClearance);
        double frac = (double) (clearance - pm.clearanceMin) / (sat - pm.clearanceMin);
        return frac < 0 ? 0.0 : (frac > 1 ? 1.0 : frac);
    }

    /** Count of not-yet-broken chests immediately around a candidate (Chebyshev 1) — high = a walled-in "core" chest. */
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

    /** Reach-cost of a candidate break: distance (scaled by the measured clearance multiplier), verticality, AIM deviation. */
    private static double selCost(P a, P b, SolidGrid solid, Params pm, double[] heading) {
        int clrAt = solid.clearanceFlyAt(b.x(), b.y(), b.z()); // wall-only: reaching into a dense cluster is fast, not "tight"
        double base = Math.max(euclid(a, b) - REACH, 0.5) * clearanceMult(clrAt, pm); // measured: tight space is slower
        double vert = Math.max(0, b.y() - a.y()) * pm.upCost + Math.max(0, a.y() - b.y()) * pm.downCost;
        // Aim cost, split: a horizontal (yaw) flick is the hard part and is penalised fully (no openness discount).
        // Looking UP at a chest above is also hard; looking DOWN at one you pass over is easy, so it's free.
        double turn = 0.0;
        if (heading != null) {
            double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
            turn = pm.turnWeight * angle(heading[0], 0, heading[2], dx, 0, dz); // yaw flick
            if (dy > 0) {
                double flat = Math.max(1.0, Math.hypot(dx, dz));
                turn += pm.turnWeight * Math.atan2(dy, flat); // extra for aiming upward only
            }
        }
        // Dense-cluster proximity discount: a break packed right up against the previous one is nearly free
        // ("5 close breaks ≈ 1 break"). Radius widens in open space where you can see/hit further.
        double move = base + vert + turn;
        if (pm.proximityBonus > 0.0) {
            double d = euclid(a, b);
            double proxR = pm.proximityRadius + (pm.proximityRadiusOpen - pm.proximityRadius) * opennessFrac(clrAt, pm);
            if (proxR > 1e-6 && d < proxR) {
                double t = d / proxR;                          // 0 on top of the last break .. 1 at the radius
                move *= 1.0 - pm.proximityBonus * (1.0 - t);   // full discount at d=0, none at d=proxR
            }
        }
        return move;
    }

    /**
     * Accessibility penalty (blocks) for a chest, added to its reach cost so awkward chests fall below the bail:
     *   - sits >= 3 blocks ABOVE the nearest standable spot within reach (you'd have to aim up / climb), and/or
     *   - is near-fully ENCLOSED (>=5 of 6 faces solid — walls or other unmined chests), i.e. a buried corner chest.
     * Static per chest (computed on the full grid once), so it's cheap to precompute before selection.
     */
    private static double accessPenalty(P c, SolidGrid solid, Params pm) {
        double pen = 0.0;
        int highest = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = c.y() + 2; y >= c.y() - 6; y--) { // topmost standable cell in this near column
                    int x = c.x() + dx, z = c.z() + dz;
                    if (!solid.isSolid(x, y, z) && !solid.isSolid(x, y + 1, z) && solid.isSolid(x, y - 1, z)) {
                        if (y > highest) highest = y;
                        break;
                    }
                }
            }
        }
        int gap = highest == Integer.MIN_VALUE ? 6 : (c.y() - highest);
        if (gap >= 3) pen += pm.abovePathWeight * (gap - 2); // 3 up -> 1x, 4 -> 2x, ...
        int covered = 0;
        if (solid.isSolid(c.x() + 1, c.y(), c.z())) covered++;
        if (solid.isSolid(c.x() - 1, c.y(), c.z())) covered++;
        if (solid.isSolid(c.x(), c.y() + 1, c.z())) covered++;
        if (solid.isSolid(c.x(), c.y() - 1, c.z())) covered++;
        if (solid.isSolid(c.x(), c.y(), c.z() + 1)) covered++;
        if (solid.isSolid(c.x(), c.y(), c.z() - 1)) covered++;
        if (covered >= 5) pen += pm.enclosureWeight * (covered - 4); // 5 faces -> 1x, 6 -> 2x
        return pen;
    }

    // ---- break selection (shapes the route) ----

    /** Full greedy break order (run to completion) + each break's marginal value — the caller cuts at the bail. */
    public static final class Selection {
        public final int[] breaks;             // greedy order, ALL clusters (bail is applied later, not here)
        public final double[] marginals;       // WITH the awkward-access penalty — used for the bail CUT
        public final double[] cleanMarginals;  // WITHOUT access penalty — used for the hot-spot RATE (stable bail level)
        Selection(int[] breaks, double[] marginals, double[] cleanMarginals) {
            this.breaks = breaks; this.marginals = marginals; this.cleanMarginals = cleanMarginals;
        }
    }

    static Selection select(List<P> pts, ChainModel chain, P entrance, SolidGrid solid, Params pm, double[] access) {
        boolean[] remaining = new boolean[pts.size()];
        java.util.Arrays.fill(remaining, true);
        List<Integer> breaks = new ArrayList<>();
        List<Double> marg = new ArrayList<>();
        List<Double> margClean = new ArrayList<>();
        P cur = entrance;
        double[] heading = null;
        // Run the greedy to COMPLETION (no bail here) so the full marginal curve is known — the hot-spot rate is
        // read off the TOP of the CLEAN curve (so awkward-chest penalties don't move the bail level), and planRoute
        // cuts the prefix using the access-penalised curve (so awkward chests fall below the bail and are dropped).
        while (true) {
            int best = -1;
            double bs = Double.NEGATIVE_INFINITY;
            double bestValue = 0.0, bestClean = 0.0;
            for (int idx = 0; idx < pts.size(); idx++) {
                if (!remaining[idx]) continue;
                // Prefer easy EDGE chests: penalise a candidate walled in by not-yet-broken chests (hard to aim at).
                // Computed against `remaining`, so a core chest becomes an edge once the ones before it are gone (peeling).
                double proxy = chain.clearProxy(idx, remaining);
                double selClean = selCost(cur, pts.get(idx), solid, pm, heading);
                // ORDER + hot-spot + prefix are all CLEAN (access must not reorder the greedy or move the bail level).
                // waypointOverhead is the measured fixed cost of stopping anywhere at all (~0.30 s ≈ 5 blocks);
                // it rides in BOTH marginals so the hot-spot rate and the bail cut read the same scale.
                double sc = proxy / (selClean + pm.waypointOverhead + pm.corePenaltyWeight * coreNeighbors(idx, pts, chain, remaining));
                if (sc > bs) {
                    bs = sc;
                    best = idx;
                    bestClean = proxy / Math.max(selClean + pm.waypointOverhead, 0.5);              // hot-spot rate + prefix extent
                    bestValue = proxy / Math.max(selClean + pm.waypointOverhead + access[idx], 0.5); // access-deflated → awkward-chest DROP filter
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

    /**
     * The room's hot-spot marginal rate: the top-quartile (75th percentile) of the break marginals. This is the
     * OPPORTUNITY signal — what a fresh room's good clusters give — computed from the full greedy curve so it's
     * independent of where we chose to stop (unlike the old achieved chests/block average, which self-reinforced).
     */
    static double hotSpotRate(double[] marginals) {
        if (marginals.length == 0) return 0.0;
        double[] s = marginals.clone();
        java.util.Arrays.sort(s); // ascending
        int idx = (int) Math.floor(0.75 * (s.length - 1));
        return s[idx];
    }

    // ---- full plan ----

    public static RoutePlan planRoute(SolidGrid solid, List<P> pts, P entrance, P exit, Params pm) {
        ChainModel chain = new ChainModel(pm.chainRange, pm.chainLimit, pts);
        double[] accessPen = new double[pts.size()]; // static per-chest accessibility penalty (above-path / enclosed), shared by both passes
        for (int i = 0; i < pts.size(); i++) accessPen[i] = accessPenalty(pts.get(i), solid, pm);
        Selection sel = select(pts, chain, entrance, solid, pm, accessPen);
        // Leave-threshold = a fraction of the room's hot-spot rate (opportunity cost), NOT an achieved average.
        // pm.bail > 0 is an absolute override / session-wide level; else fall back to this room's own hot-spot.
        double hotSpot = hotSpotRate(sel.cleanMarginals); // clean → awkward-access penalties don't move the bail level
        double effBail = pm.bail > 0.0 ? pm.bail : pm.bailAggression * hotSpot;
        List<Integer> breaks = new ArrayList<>();
        for (int i = 0; i < sel.breaks.length; i++) {
            if (i > 0 && sel.cleanMarginals[i] < effBail) break;       // prefix cut = the good-cluster tail (coverage level)
            if (i == 0 || sel.marginals[i] >= effBail) breaks.add(sel.breaks[i]); // within it, drop awkward (access-deflated) chests
        }

        WalkGraph graph = WalkGraph.build(solid, pm);

        // Drop UNREACHABLE chests entirely: keep only breaks walk-connected to the entrance. No routing to chests
        // you can't actually path to (kills grey dead-end legs), and it guarantees every tour leg is walkable.
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

        // Tour cost = cheapest of WALK (graph shortest path, which already includes VERTICAL trident shafts) or an
        // open-space SPRINT. No direct horizontal trident here — a flat-cost dash made distance ~free, so the
        // "cheapest" tour teleported across the room instead of staying local. Walk cost scales with distance, so
        // the tour now has a real incentive to visit nearby chests consecutively (a spatial sweep).
        double[][] cost = new double[n2][n2];
        for (int i = 0; i < n2; i++) {
            for (int j = i + 1; j < n2; j++) {
                double w = Math.min(walkBlocks(i, j, access, dijk), walkBlocks(j, i, access, dijk));
                double sp = (isChest(i, n2) && isChest(j, n2))
                        ? sprintCost(nodes.get(i), nodes.get(j), solid, pm) : Double.POSITIVE_INFINITY;
                double c = Math.min(w, sp);
                if (Double.isInfinite(c)) c = euclid(nodes.get(i), nodes.get(j)) * 50.0; // keep the tour computable
                cost[i][j] = c;
                cost[j][i] = c;
            }
        }

        // open-path TSP, entrance fixed first / exit fixed last: turn-aware NN then turn-aware 2-opt.
        List<Integer> tour = new ArrayList<>();
        tour.add(0);
        Set<Integer> unvis = new HashSet<>();
        for (int k = 1; k < n2 - 1; k++) unvis.add(k);
        int cur = 0;
        double[] hd = null;
        while (!unvis.isEmpty()) {
            int nx = -1;
            double bestc = Double.POSITIVE_INFINITY;
            int curClr = solid.clearanceFlyAt(nodes.get(cur).x(), nodes.get(cur).y(), nodes.get(cur).z()); // wall-only openness
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
                            Collections.reverse(tour.subList(i, k + 1)); // revert
                        }
                    }
                }
            }
        }

        // render segments + the densified follow-path (with per-point mode + cumulative distance)
        List<RoutePlan.Seg> segments = new ArrayList<>();
        double walkB = 0, flyB = 0;
        for (int ti = 0; ti + 1 < tour.size(); ti++) {
            int i = tour.get(ti), j = tour.get(ti + 1);
            P ni = nodes.get(i), nj = nodes.get(j);
            double walk = walkBlocks(i, j, access, dijk); // directional i→j; the graph path includes VERTICAL trident shafts
            double sprint = (isChest(i, n2) && isChest(j, n2)) ? sprintCost(ni, nj, solid, pm) : Double.POSITIVE_INFINITY;
            boolean walkOk = access[i] >= 0 && access[j] >= 0 && !Double.isInfinite(walk);
            if (walkOk && walk <= sprint) {
                // reconstruct the i→j graph path (walk + vertical trident-shaft edges) and split into mode runs
                int[] statePath = graph.statePath(dijk[i], access[j]);
                double[] wf = emitModeSegments(graph, statePath, segments);
                walkB += wf[0];
                flyB += wf[1];
            } else if (!Double.isInfinite(sprint)) { // open-space sprint straight-shot (loots along the line)
                List<P> straight = new ArrayList<>();
                straight.add(ni);
                straight.add(nj);
                walkB += euclid(ni, nj); // sprint-jump = fast floor travel
                segments.add(new RoutePlan.Seg('s', straight));
            } else { // nothing connects (rare now that unreachable breaks are dropped) — draw nothing; boxes still guide
                List<P> straight = new ArrayList<>();
                straight.add(ni);
                straight.add(nj);
                segments.add(new RoutePlan.Seg('x', straight));
            }
        }

        List<P> pathPts = new ArrayList<>();
        List<Character> pathMode = new ArrayList<>();
        List<Double> pathCum = new ArrayList<>();
        List<Boolean> pathTrig = new ArrayList<>(); // loot here? walk = always; trident/gap = only on landing (no loot mid-burst)
        double cum = 0;
        P last = null;
        for (RoutePlan.Seg seg : segments) {
            // walk legs get corner-safe doglegs so a straight ribbon never clips a block; bursts/gaps stay straight.
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
                    boolean landing = (wi + 2 == poly.size()) && (s == steps); // final point of the segment
                    pathPts.add(p);
                    pathMode.add(seg.mode);
                    pathCum.add(cum);
                    pathTrig.add(seg.mode == 'w' || seg.mode == 's' || landing); // walk & sprint loot along; burst only lands
                    last = p;
                }
            }
        }

        boolean[] remaining = new boolean[pts.size()];
        java.util.Arrays.fill(remaining, true);
        boolean[] triggered = new boolean[pts.size()];
        List<RoutePlan.WP> waypoints = new ArrayList<>();
        for (int pi = 0; pi < pathPts.size(); pi++) {
            if (!pathTrig.get(pi)) continue; // mid-burst: can't loot, just travel through
            P p = pathPts.get(pi);
            P head = new P(p.x(), p.y() + 1, p.z()); // LOS is cast from the player's head — where they actually mine from
            int bx = chain.bucketCoord(p.x()), by = chain.bucketCoord(p.y()), bz = chain.bucketCoord(p.z());
            int br = chain.bucketRadius(pm.breakReach); // a low chain tier makes buckets smaller than the reach
            while (true) {
                int best = -1;
                double bestd = pm.breakReach; // trigger closer than the 6-blk geometry REACH → break head-on, less craning
                for (int dx = -br; dx <= br; dx++) {
                    for (int dy = -br; dy <= br; dy++) {
                        for (int dz = -br; dz <= br; dz++) {
                            List<Integer> v = chain.buckets().get(chain.packBucket(bx + dx, by + dy, bz + dz));
                            if (v == null) continue;
                            for (int ci : v) {
                                if (!remaining[ci]) continue;
                                if (accessPen[ci] > 0.0) continue; // don't TRIGGER awkward (above-path/near-buried) chests here —
                                                                // a neighbour's chain still clears them; we just won't route to them
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

        // Turnaround approaches. Primary: at each WAYPOINT, compare the run in against the run out — that is where
        // the player actually stops, so it is the reversal they must see coming. Secondary: the same test swept
        // along the path, to catch reversals between waypoints. Both whiten the final approach into the turn.
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
            // whiten the approach regardless of mode boundaries — the warning matters more than the mode colour
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
            if ((ix * ox + iz * oz) / (in * on) < turnCos) { // near-reversal (openness-independent: you always need the warning)
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
        plan.exitLeg = segments.isEmpty() ? null : segments.get(segments.size() - 1); // last leg = approach to the exit
        plan.path = pathPts;
        plan.pathMode = pmArr;
        plan.pathWhite = pathWhite;
        plan.graph = graph; // retained for the runtime from-player connector
        plan.state = state;
        plan.collected = collected;
        plan.walkBlocks = walkB;
        plan.flyBlocks = flyB;
        plan.hotSpotRate = hotSpot; // this room's opportunity rate — folded into the session estimate for the next room's bail
        return plan;
    }

    /**
     * Keep a hand-edited turnaround angle inside [1,180]. Outside it the cosine test degenerates — every bend
     * (or no bend at all) reads as a turnaround — so a bad value is clamped and logged rather than drawn.
     */
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
            int bClr = solid.clearanceFlyAt(b.x(), b.y(), b.z()); // wall-only openness (turns are free inside dense clusters)
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
     * Split a graph STATE path into consecutive same-mode runs, appending a Seg each; returns
     * {walkBlocks, tridentBlocks}. Mode/node of each state are decoded via WalkGraph.edgeMode/stateNode, so a
     * run of DROP edges becomes its own 'd' segment. Only the trident burst counts as fly distance — a drop is
     * part of the ground path, so its length rides in walkBlocks.
     */
    private static double[] emitModeSegments(WalkGraph graph, int[] statePath, List<RoutePlan.Seg> segments) {
        double wB = 0, fB = 0;
        int t = 0;
        while (t + 1 < statePath.length) {
            int mode = graph.edgeMode(statePath[t], statePath[t + 1]); // mode of the edge statePath[t] → statePath[t+1]
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
