package com.routerunner.solver;

/**
 * Route parameters and trajectory scoring. The waypoint solver that used to live here (greedy break selection,
 * NN + 2-opt tour, densified follow path) was retired in 1.2.0: every room is routed by the lane planner, and a
 * room the lane planner cannot route shows no route. Its code is kept, unbuilt, under {@code legacy/waypoint/}.
 * {@link Params} stays because it carries the planner's per-vault settings and is logged as {@code weights};
 * {@link #scoreTrajectory} scores the player's and the planned walk for the room diff.
 */
public final class RoutePlanner {
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
        /** Tallest fall a drop edge will span (blocks); below the walk graph's minimum drop no drop edges exist. */
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
        /** Equipped mining ability's range/limit, read at solve time (see ChainMinerInfo); range 1 is Vein Miner. */
        public int chainRange = 6;
        public int chainLimit = 32;
        /** {@code chain}, {@code vein} or {@code default} (tree unreadable), the selected Vein Miner specialization id and its actual tier. */
        public String miner = "default";
        public String minerSpec = null;
        public int minerTier = -1;
        /**
         * Game block reach (see PlayerReach), the reach the player's hits use (learned quantile, or the prior) and the
         * hits behind it, and the lane planner's break reach derived from them.
         */
        public double reach = 5.0;
        public double usedReach = 6.0;
        public int reachHits = 0;
        public double planReach = 4.5;
        /** Player MOVEMENT_SPEED attribute at solve time, every modifier included (sprint too); logged only. */
        public double speedAttr = 0.1;
        /**
         * MOVEMENT_SPEED over the non-transient modifiers only (no sprint, effects or ParCool FastRun), as the shape
         * time model was fitted on; the shape model's run-cost speed.
         */
        public double speedPersistent = 0.0;
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

    /** Travel cost multiplier for one block at this wall clearance (open = 1.0); the only clearance cost term. */
    static double clearanceMult(int clearance, Params pm) {
        if (clearance <= 1) return pm.tightMult;
        if (clearance <= 3) return pm.narrowMult;
        if (clearance <= 6) return pm.midMult;
        return 1.0;
    }
}
