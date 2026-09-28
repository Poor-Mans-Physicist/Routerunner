package com.routerunner.lane;

import com.google.gson.Gson;
import com.routerunner.solver.ChainModel;
import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * The lane planner (Java port of {@code tools/lanes.py}, the only implementation from here on). A room is
 * planned as a sequence of LANES joined by grounded transitions, ordered greedily by chests per second
 * under the learned {@link LegTimeModel}, with a one-step lookahead. Two candidate primitives:
 * <ul>
 *   <li><b>point mode</b> — a candidate is one standable trigger cell (a rate-greedy chest tour); runs
 *       for display are derived afterwards by merging consecutive legs whose turn is under the cap;</li>
 *   <li><b>corridor mode</b> — a candidate is a straight sprintable floor corridor swept end to end.</li>
 * </ul>
 * Attack is held the whole way, so transitions and the exit walk are swept too and their yield is credited
 * to the leg; the transition path search is sweep-aware (cheaper through cells with chests in reach).
 * Everything is walls-only ({@link SolidGrid#isSolidFly}); target chests are never obstacles.
 */
public final class LanePlanner {

    /** Tunables, mirrored from {@code tools/lanes.py DEFAULTS}. */
    public static final class Params {
        public double breakReach = 4.5;
        public int headings = 16;
        public int seedSpacing = 2;
        public int minLaneLen = 8;
        public int minLaneClr = 2;
        public int maxStepUp = 1;
        public int maxDrop = 3;
        public int entrySpacing = 3;
        public int proxyTopK = 12;
        public int beamWidth = 4;
        public boolean lookahead = true;
        public double turnCap = 80.0;
        public double mergeTrans = 4.0;
        public double maxRunLen = 40.0;
        public double strandPenaltyS = 1.5;
        public double opportunityFloor = 0.0;
        public double exitWeight = 1.0;
        public double maxTransLen = 60.0;
        /** Absolute leave threshold in model chests per second (the running realized rate anchored bail); 0 = none. */
        public double bailFloor = 0.0;
        /** Plan on the Rust library when it is loaded ({@link NativeLane}); the Java planner is the fallback. */
        public boolean useNative = true;
        public int farTries = 4;
        public int farOk = 2;
        public double bailAggression = 0.12;
        public double turnaroundDeg = 120.0;
        /** Junction reversal cost on top of the leg model's own turn term: model ~0.45 s + this = the ~0.85 s measured. */
        public double turnaroundPenaltyS = 0.4;
        public boolean allowFly = true;
        public double flyPenaltyS = 1.0;
        public double sweepGain = 0.4;
        public double timeScale = 1.39;
        public boolean pointMode = false;
        public double ghostNoise = 0.0;
        public long seed = 0;
        public int maxLanes = 60;
        /** Seconds a chain trigger costs on top of the walking: fitted on 878 logged lane runs (2026-09-22). */
        public double triggerS = 0.3;
        /** Pace factor already folded into the leg model's intercept (record only; the native planner never reads it). */
        public double pace = 1.0;
        /** Which time model priced this plan: {@code learned}, or the simplified model's name (record only). */
        public String timeModel = "learned";

        /** True when the plan was priced with the learned (adaptive) model, whose runs may calibrate it. */
        public boolean learnedTimeModel() {
            return "learned".equals(timeModel);
        }
    }

    /** One chain firing: where the ghost stood, which chest it hit, what fell. */
    public static final class Trigger {
        public final P cell;
        public final int chest;
        public final int[] cleared;
        Trigger(P cell, int chest, int[] cleared) { this.cell = cell; this.chest = chest; this.cleared = cleared; }
    }

    /** A committed lane: its transition path, its cells, what fell, the model's times. */
    public static final class Lane {
        public List<P> cells;
        public double[] dir;
        public List<P> trans;
        public int yield;
        public int yieldTrans;
        public double tTrans, tLane, rate, align;
        /** Rate with the change in exit time charged (or credited): the number the planner ranks and stops on. */
        public double rateX, dExit;
        public P end;
        public boolean[] remaining;
        public List<Trigger> triggers, transTriggers;
        public double[] endHeading;
        public int endBurst;
        public double tStart;
        public int run;
        /** Chain triggers fired on the transition and the lane, and the fixed penalty seconds inside tTrans. */
        public int nTrig;
        public double tPen;
        /** Shape model: the route's last cells before {@link #end} (up to five) and the blocks walked since the last click. */
        public List<P> endBack = List.of();
        public double endSinceTrig = SHAPE_NO_TRIG;
    }

    /** The finished plan. Times are model seconds (unscaled); the ghost timeline applies timeScale. */
    public static final class Plan {
        public List<Lane> lanes = new ArrayList<>();
        public List<int[]> runs = new ArrayList<>();
        public List<Map<Integer, Integer>> heat = new ArrayList<>();
        public double tTotal, tExit, bail, opportunity;
        public int yieldTotal;
        public double cover;
        public List<P> exitPath;
        public boolean exitStraight;
        /** The exit was only reachable by a warp: a walk to a take-off cell and a hop out (or a wide flight). */
        public boolean exitHop;
        public List<Trigger> exitTriggers = new ArrayList<>();
        public List<double[]> ghost = new ArrayList<>();
        public List<Object[]> clears = new ArrayList<>();
        public List<Double> laneT = new ArrayList<>();
        public int nCorridors;
    }

    private static final class State {
        final P pos;
        final double[] heading;
        final int prevBurst;
        /** The route's last cells before {@code pos}, oldest first (up to five): the shape model's turn and heading window. */
        final List<P> back;
        /** Horizontal blocks walked since the last planned click ({@link #SHAPE_NO_TRIG} before the first). */
        final double sinceTrig;
        State(P pos, double[] heading, int prevBurst) { this(pos, heading, prevBurst, List.of(), SHAPE_NO_TRIG); }
        State(P pos, double[] heading, int prevBurst, List<P> back, double sinceTrig) {
            this.pos = pos; this.heading = heading; this.prevBurst = prevBurst; this.back = back; this.sinceTrig = sinceTrig;
        }
    }

    /** "No click yet" for the shape model's burst distance. */
    static final double SHAPE_NO_TRIG = 1e9;

    private static final class Cand {
        final List<P> seq;
        final int entry;
        double score;
        boolean far;
        Cand(List<P> seq, int entry, double score) { this.seq = seq; this.entry = entry; this.score = score; }
    }

    private static final class Corridor {
        final double[] axis;
        final List<P> cells;
        Corridor(double[] axis, List<P> cells) { this.axis = axis; this.cells = cells; }
    }

    public final Params P;
    public final SolidGrid grid;
    final List<P> chests;
    final ChainModel chain;
    final LegTimeModel model;
    private final List<Corridor> corridors;
    private final Map<Long, int[]> reachCache = new HashMap<>();
    private final Map<Long, Set<Long>> componentCache = new HashMap<>();
    private final Map<Long, List<P>> landingCache = new HashMap<>();
    private final Map<Long, Double> exitTimeCache = new HashMap<>();
    private Map<Long, Double> exitField = null;
    private long exitFieldKey = Long.MIN_VALUE;
    private P exitCell = null;
    private final Map<Long, List<Integer>> chestBuckets = new HashMap<>();

    /** Chest {@code i} of this planner's chest list (room-local). */
    public P chestAt(int i) {
        return chests.get(i);
    }

    /** The chain (or, at range 1, vein) model this planner clears chests with. */
    public ChainModel chainModel() {
        return chain;
    }

    public LanePlanner(SolidGrid grid, List<P> chests, int chainRange, int chainLimit, Params params, LegTimeModel model) {
        this(grid, chests, null, chainRange, chainLimit, params, model);
    }

    /**
     * A planner where {@code soloValue[i] > 0} marks chest i as a solo target (an enigma chest): never chained to or
     * from, worth {@code soloValue[i]} chests in every yield and rate. Null: ordinary targets only.
     */
    public LanePlanner(SolidGrid grid, List<P> chests, int[] soloValue, int chainRange, int chainLimit, Params params, LegTimeModel model) {
        this.soloValue = soloValue;
        this.P = params;
        this.grid = grid;
        this.chests = chests;
        this.chain = new ChainModel(chainRange, chainLimit, chests, soloValue);
        this.model = model;
        for (int i = 0; i < chests.size(); i++) {
            P c = chests.get(i);
            chestBuckets.computeIfAbsent(bkey(c.x() / 5, c.y() / 5, c.z() / 5), k -> new ArrayList<>()).add(i);
        }
        this.corridors = params.pointMode ? new ArrayList<>() : corridors();
        long h = 0;
        if (params.useNative && NativeLane.ready()) {
            try {
                h = soloValue == null
                        ? NativeLane.create(grid.sx, grid.sy, grid.sz, solidBits(grid), chestArray(chests), chainRange, chainLimit, modelArray(model))
                        : NativeLane.createSolo(grid.sx, grid.sy, grid.sz, solidBits(grid), chestArray(chests), chainRange, chainLimit, modelArray(model), soloValue);
                if (h == 0) LOGGER.log(System.Logger.Level.ERROR, "[Routerunner] native lane planner refused the room; planning in Java instead.");
            } catch (Throwable t) {
                LOGGER.log(System.Logger.Level.ERROR, "[Routerunner] native lane planner failed to start; planning in Java instead.", t);
                h = 0;
            }
        }
        this.nativeHandle = h;
        this.cleanable = h == 0 ? null : CLEANER.register(this, new NativeRelease(h));
    }

    private static final System.Logger LOGGER = System.getLogger("Routerunner");
    private static final Cleaner CLEANER = Cleaner.create();
    private final int[] soloValue;

    /** True when some chest is a solo target (an enigma chest). */
    public boolean hasSolo() {
        if (soloValue == null) return false;
        for (int v : soloValue) if (v > 0) return true;
        return false;
    }

    /** Solo targets (enigma chests) in this planner's chest list, and the value of the first one (0 when none). */
    public int[] soloSummary() {
        int n = 0, v = 0;
        if (soloValue != null) for (int s : soloValue) if (s > 0) { n++; if (v == 0) v = s; }
        return new int[]{n, v};
    }
    private final long nativeHandle;
    private final Cleaner.Cleanable cleanable;

    /** Frees the Rust planner when this one is garbage collected; holds only the handle, never the planner. */
    private static final class NativeRelease implements Runnable {
        private final long handle;

        NativeRelease(long handle) { this.handle = handle; }

        @Override
        public void run() {
            try {
                NativeLane.destroy(handle);
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isNative() {
        return nativeHandle != 0;
    }

    private static byte[] solidBits(SolidGrid g) {
        java.util.BitSet bits = new java.util.BitSet(g.sx * g.sy * g.sz);
        for (int x = 0; x < g.sx; x++) {
            for (int y = 0; y < g.sy; y++) {
                for (int z = 0; z < g.sz; z++) {
                    if (g.isSolidFly(x, y, z)) bits.set((x * g.sy + y) * g.sz + z);
                }
            }
        }
        byte[] raw = bits.toByteArray();
        int need = (g.sx * g.sy * g.sz + 7) / 8;
        return raw.length >= need ? raw : Arrays.copyOf(raw, need);
    }

    private static int[] chestArray(List<P> chests) {
        int[] out = new int[chests.size() * 3];
        for (int i = 0; i < chests.size(); i++) {
            P c = chests.get(i);
            out[i * 3] = c.x();
            out[i * 3 + 1] = c.y();
            out[i * 3 + 2] = c.z();
        }
        return out;
    }

    private static double[] modelArray(LegTimeModel m) {
        double[] out = new double[43 + LegTimeModel.Shape.N];
        System.arraycopy(m.mean, 0, out, 0, 12);
        System.arraycopy(m.scale, 0, out, 12, 12);
        System.arraycopy(m.coef, 0, out, 24, 12);
        out[36] = m.intercept;
        out[37] = m.sigma;
        out[38] = m.linear ? 1 : 0;
        out[39] = m.linWalk;
        out[40] = m.linClimb;
        out[41] = m.linDrop;
        out[42] = m.shape != null ? 1 : 0;
        if (m.shape != null) System.arraycopy(m.shape, 0, out, 43, LegTimeModel.Shape.N);
        return out;
    }

    private double[] paramArray() {
        return new double[]{
                P.breakReach, P.headings, P.seedSpacing, P.minLaneLen, P.minLaneClr, P.maxStepUp, P.maxDrop, P.entrySpacing,
                P.proxyTopK, P.beamWidth, P.lookahead ? 1 : 0, P.turnCap, P.mergeTrans, P.maxRunLen, P.bailAggression,
                P.turnaroundDeg, P.turnaroundPenaltyS, P.allowFly ? 1 : 0, P.flyPenaltyS, P.sweepGain, P.timeScale,
                P.pointMode ? 1 : 0, P.ghostNoise, P.seed, P.maxLanes, P.triggerS, P.strandPenaltyS, P.opportunityFloor,
                P.exitWeight, P.maxTransLen, P.farTries, P.farOk, P.bailFloor};
    }

    /** The native plan JSON, as the Rust side writes it. */
    private static final class NativePlan {
        List<NativeLaneRec> lanes;
        List<int[]> runs;
        List<int[]> exitPath;
        boolean exitStraight;
        boolean exitHop;
        double tTotal, tExit, bail, opportunity, cover;
        int yieldTotal, nCorridors;
        com.google.gson.JsonArray exitTrig;
    }

    private static final class NativeLaneRec {
        List<int[]> trans, cells;
        int yield, yieldTrans, endBurst;
        double tTrans, tLane, rate, rateX, dExit, align, tStart, tPen;
        int nTrig;
        int[] end;
        double[] dir;
        com.google.gson.JsonArray trig, transTrig;
    }

    /** Native triggers {@code [[x, y, z, chest, [cleared...]], ...]} back to {@link Trigger}s (empty for an older library). */
    private static List<Trigger> triggerList(com.google.gson.JsonArray arr) {
        List<Trigger> out = new ArrayList<>(arr == null ? 0 : arr.size());
        if (arr == null) return out;
        for (com.google.gson.JsonElement el : arr) {
            com.google.gson.JsonArray t = el.getAsJsonArray();
            com.google.gson.JsonArray cl = t.get(4).getAsJsonArray();
            int[] cleared = new int[cl.size()];
            for (int i = 0; i < cleared.length; i++) cleared[i] = cl.get(i).getAsInt();
            out.add(new Trigger(new P(t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt()), t.get(3).getAsInt(), cleared));
        }
        return out;
    }

    private static List<P> cellList(List<int[]> pts) {
        List<P> out = new ArrayList<>(pts == null ? 0 : pts.size());
        if (pts != null) for (int[] c : pts) out.add(new P(c[0], c[1], c[2]));
        return out;
    }

    /** Plan on the Rust library; null (with the reason logged) when it fails, so the caller can fall back to Java. */
    private Plan planNative(P entrance, P exit, boolean[] remainingIn) {
        byte[] mask = new byte[remainingIn.length];
        for (int i = 0; i < mask.length; i++) mask[i] = (byte) (remainingIn[i] ? 1 : 0);
        String json;
        try {
            json = NativeLane.plan(nativeHandle, entrance.x(), entrance.y(), entrance.z(), exit.x(), exit.y(), exit.z(), mask, paramArray());
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.ERROR, "[Routerunner] native lane plan threw; planning this room in Java instead.", t);
            return null;
        }
        if (json == null) {
            LOGGER.log(System.Logger.Level.ERROR, "[Routerunner] native lane plan returned nothing; planning this room in Java instead.");
            return null;
        }
        NativePlan np = new Gson().fromJson(json, NativePlan.class);
        Plan plan = new Plan();
        if (np.lanes != null) {
            for (NativeLaneRec r : np.lanes) {
                Lane e = new Lane();
                e.trans = cellList(r.trans);
                e.cells = cellList(r.cells);
                e.yield = r.yield;
                e.yieldTrans = r.yieldTrans;
                e.tTrans = r.tTrans;
                e.tLane = r.tLane;
                e.rate = r.rate;
                e.rateX = r.rateX;
                e.dExit = r.dExit;
                e.end = r.end == null ? (e.cells.isEmpty() ? entrance : e.cells.get(e.cells.size() - 1)) : new P(r.end[0], r.end[1], r.end[2]);
                e.dir = r.dir == null ? new double[]{0, 0} : r.dir;
                e.endHeading = e.dir;
                e.endBurst = r.endBurst;
                e.align = r.align;
                e.tStart = r.tStart;
                e.nTrig = r.nTrig;
                e.tPen = r.tPen;
                e.triggers = triggerList(r.trig);
                e.transTriggers = triggerList(r.transTrig);
                plan.lanes.add(e);
                plan.laneT.add(r.tStart);
            }
        }
        if (np.runs != null) plan.runs.addAll(np.runs);
        for (int ri = 0; ri < plan.runs.size(); ri++) for (int k : plan.runs.get(ri)) if (k >= 0 && k < plan.lanes.size()) plan.lanes.get(k).run = ri;
        plan.exitPath = np.exitPath == null ? null : cellList(np.exitPath);
        plan.exitStraight = np.exitStraight;
        plan.exitHop = np.exitHop;
        plan.tTotal = np.tTotal;
        plan.tExit = np.tExit;
        plan.bail = np.bail;
        plan.opportunity = np.opportunity;
        plan.yieldTotal = np.yieldTotal;
        plan.cover = np.cover;
        plan.nCorridors = np.nCorridors;
        plan.exitTriggers = triggerList(np.exitTrig);
        return plan;
    }

    /** Grounded path, else a two-hop flight, from the Rust library; null when it is not loaded or finds nothing. */
    public List<P> nativePath(P a, P b) {
        if (nativeHandle == 0) return null;
        try {
            String json = NativeLane.path(nativeHandle, a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
            if (json == null || json.equals("null")) return null;
            return cellList(new Gson().fromJson(json, new com.google.gson.reflect.TypeToken<List<int[]>>() {}.getType()));
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.ERROR, "[Routerunner] native tracer path threw; using the Java search for this one.", t);
            return null;
        }
    }

    private static long bkey(int x, int y, int z) {
        return (((long) (x + 64)) << 40) | (((long) (y + 64)) << 20) | (long) (z + 64);
    }

    private static long ckey(P p) {
        return (((long) p.x()) << 40) | (((long) (p.y() + 512)) << 20) | (long) (p.z() + 512);
    }

    static double angle(double[] u, double[] v) {
        if (u == null || v == null) return 0.0;
        double nu = Math.hypot(u[0], u[1]), nv = Math.hypot(v[0], v[1]);
        if (nu < 1e-9 || nv < 1e-9) return 0.0;
        double c = (u[0] * v[0] + u[1] * v[1]) / (nu * nv);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, c))));
    }

    private static double[] hdir(P a, P b) {
        return new double[]{b.x() - a.x(), b.z() - a.z()};
    }

    // ---- reach: chests triggerable from a cell (within breakReach, head line of sight), nearest first ----

    int[] reach(P cell) {
        long k = ckey(cell);
        int[] r = reachCache.get(k);
        if (r != null) return r;
        P head = new P(cell.x(), cell.y() + 1, cell.z());
        double R = P.breakReach;
        List<double[]> out = new ArrayList<>();
        int bx = Math.floorDiv(cell.x(), 5), by = Math.floorDiv(cell.y(), 5), bz = Math.floorDiv(cell.z(), 5);
        int br = reachBuckets(R);
        for (int dx = -br; dx <= br; dx++) {
            for (int dy = -br; dy <= br; dy++) {
                for (int dz = -br; dz <= br; dz++) {
                    List<Integer> v = chestBuckets.get(bkey(bx + dx, by + dy, bz + dz));
                    if (v == null) continue;
                    for (int i : v) {
                        P c = chests.get(i);
                        double d = Grid.dist(cell, c);
                        if (d <= R && Grid.losClear(grid, head, c)) out.add(new double[]{d, i});
                    }
                }
            }
        }
        out.sort((u, v) -> Double.compare(u[0], v[0]));
        r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = (int) out.get(i)[1];
        reachCache.put(k, r);
        return r;
    }

    /** Buckets (of 5 blocks) the reach scan spans each way: 1 up to a 5-block reach, more beyond it. */
    static int reachBuckets(double reach) {
        return Math.max(1, (int) Math.ceil(reach / 5.0));
    }

    /** Half-width of the point-mode candidate window around a chest: 4 up to a 4-block reach, the reach beyond it. */
    static int candSpan(double reach) {
        return Math.max(4, (int) Math.floor(reach));
    }

    private int liveReach(P cell, boolean[] remaining) {
        int n = 0;
        for (int i : reach(cell)) if (remaining[i]) n += chain.value(i);
        return n;
    }

    /** The candidate pre-filter's value of a cell: live chests in reach, or at range 1 the live components they belong to. */
    private int liveReachPre(P cell, boolean[] remaining) {
        if (!chain.hasComponents()) return liveReach(cell, remaining);
        Set<Integer> seen = new HashSet<>();
        int n = 0;
        for (int i : reach(cell)) if (remaining[i] && seen.add(chain.preKey(i))) n += chain.preValue(i);
        return n;
    }

    /** Live chests in reach of {@code cells} from index {@code from}, counted once each (once per component at range 1). */
    private int liveSetValue(List<P> cells, int from, boolean[] remaining) {
        Set<Integer> seen = new HashSet<>();
        int n = 0;
        for (int k = from; k < cells.size(); k++) {
            for (int i : reach(cells.get(k))) if (remaining[i] && seen.add(chain.preKey(i))) n += chain.preValue(i);
        }
        return n;
    }

    /** Walk the cells in order, firing the nearest live chest in reach until none is left at each cell. */
    int sweep(List<P> cells, boolean[] remaining, List<Trigger> triggers) {
        int total = 0;
        for (P cell : cells) {
            while (true) {
                int best = -1;
                for (int i : reach(cell)) if (remaining[i]) { best = i; break; }
                if (best < 0) break;
                List<Integer> cl = chain.clearFrom(best, remaining);
                int[] arr = new int[cl.size()];
                for (int j = 0; j < arr.length; j++) { arr[j] = cl.get(j); remaining[arr[j]] = false; total += chain.value(arr[j]); }
                triggers.add(new Trigger(cell, best, arr));
            }
        }
        return total;
    }

    // ---- the learned time of a leg ----

    double legTime(P a, P b, List<P> path, boolean[] remaining, int prevBurst, double turn) {
        double straight = Math.max(Grid.dist(a, b), 0.5);
        double walk, climb, drop, clrMin, clrMean, tight;
        if (path == null || path.size() < 2) {
            walk = straight;
            climb = Math.max(0, b.y() - a.y());
            drop = Math.max(0, a.y() - b.y());
            int ca = grid.clearanceFlyAt(a.x(), a.y(), a.z()), cb = grid.clearanceFlyAt(b.x(), b.y(), b.z());
            clrMin = Math.min(ca, cb);
            clrMean = (ca + cb) / 2.0;
            tight = ((ca <= 1 ? 1 : 0) + (cb <= 1 ? 1 : 0)) / 2.0;
        } else {
            walk = Grid.walkLength(path);
            climb = 0; drop = 0;
            for (int i = 1; i < path.size(); i++) {
                int dy = path.get(i).y() - path.get(i - 1).y();
                if (dy > 0) climb += dy; else drop -= dy;
            }
            int mn = Integer.MAX_VALUE, sum = 0, nt = 0;
            for (P c : path) {
                int cl = grid.clearanceFlyAt(c.x(), c.y(), c.z());
                mn = Math.min(mn, cl); sum += cl; if (cl <= 1) nt++;
            }
            clrMin = mn; clrMean = (double) sum / path.size(); tight = (double) nt / path.size();
        }
        walk = Math.max(walk, straight);
        int nLine = 0, nDst = 0;
        for (int i = 0; i < chests.size(); i++) {
            if (!remaining[i]) continue;
            P c = chests.get(i);
            if (segDist(c, a, b) <= 6.0) nLine++;
            if (Grid.dist(c, b) <= 8.0) nDst++;
        }
        return model.seconds(straight, walk / straight, climb, drop, clrMin, clrMean, tight, turn,
                nLine / straight, nDst, prevBurst, 0);
    }

    private static double segDist(P p, P a, P b) {
        double vx = b.x() - a.x(), vy = b.y() - a.y(), vz = b.z() - a.z();
        double L2 = vx * vx + vy * vy + vz * vz;
        double t = L2 < 1e-9 ? 0 : ((p.x() - a.x()) * vx + (p.y() - a.y()) * vy + (p.z() - a.z()) * vz) / L2;
        t = Math.max(0, Math.min(1, t));
        double qx = a.x() + t * vx - p.x(), qy = a.y() + t * vy - p.y(), qz = a.z() + t * vz - p.z();
        return Math.sqrt(qx * qx + qy * qy + qz * qz);
    }

    // ---- corridors (corridor mode) ----

    private List<Corridor> corridors() {
        int nAx = P.headings / 2;
        List<double[]> axes = new ArrayList<>();
        for (int k = 0; k < nAx; k++) axes.add(new double[]{Math.cos(k * Math.PI / nAx), Math.sin(k * Math.PI / nAx)});
        List<Corridor> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int x = 0; x < grid.sx; x += P.seedSpacing) {
            for (int z = 0; z < grid.sz; z += P.seedSpacing) {
                for (int y = 1; y < grid.sy - 1; y++) {
                    if (!Grid.standable(grid, x, y, z) || grid.clearanceFlyAt(x, y, z) < P.minLaneClr) continue;
                    P seed = new P(x, y, z);
                    for (double[] ax : axes) {
                        List<P> back = march(seed, -ax[0], -ax[1]);
                        java.util.Collections.reverse(back);
                        List<P> fwd = march(seed, ax[0], ax[1]);
                        List<P> cells = new ArrayList<>(back);
                        cells.add(seed);
                        cells.addAll(fwd);
                        if (cells.size() < P.minLaneLen) continue;
                        String key = Math.round(ax[0] * 1000) + "," + Math.round(ax[1] * 1000) + "|" + cells.get(0) + "|" + cells.get(cells.size() - 1);
                        if (!seen.add(key)) continue;
                        out.add(new Corridor(ax, cells));
                    }
                }
            }
        }
        out.sort((u, v) -> v.cells.size() - u.cells.size());
        List<Corridor> kept = new ArrayList<>();
        List<Set<Long>> keptSets = new ArrayList<>();
        for (Corridor c : out) {
            Set<Long> cs = new HashSet<>();
            for (P p : c.cells) cs.add(((long) p.x() << 20) | p.z());
            boolean contained = false;
            for (int i = 0; i < kept.size(); i++) {
                if (kept.get(i).axis == c.axis && keptSets.get(i).containsAll(cs)) { contained = true; break; }
            }
            if (!contained) { kept.add(c); keptSets.add(cs); }
        }
        return kept;
    }

    private List<P> march(P seed, double dx, double dz) {
        List<P> cells = new ArrayList<>();
        int y = seed.y();
        P last = seed;
        for (double s = 0.5; ; s += 0.5) {
            int cx = (int) Math.floor(seed.x() + dx * s + 0.5), cz = (int) Math.floor(seed.z() + dz * s + 0.5);
            if (cx == last.x() && cz == last.z()) continue;
            if (cx < 0 || cz < 0 || cx >= grid.sx || cz >= grid.sz) break;
            int ny = Grid.floorAt(grid, cx, y, cz, P.maxStepUp, P.maxDrop);
            if (ny == Integer.MIN_VALUE || grid.clearanceFlyAt(cx, ny, cz) < P.minLaneClr) break;
            if (cx != last.x() && cz != last.z() && grid.isSolidFly(last.x(), ny, cz) && grid.isSolidFly(cx, ny, last.z())) break;
            y = ny;
            last = new P(cx, y, cz);
            cells.add(last);
            if (cells.size() > 60) break;
        }
        return cells;
    }

    // ---- candidates ----

    private ToDoubleFunction<P> sweepDiscount(boolean[] remaining) {
        if (P.sweepGain <= 0) return null;
        Map<Long, Double> memo = new HashMap<>();
        return cell -> {
            long k = ckey(cell);
            Double v = memo.get(k);
            if (v == null) {
                int n = Math.min(6, liveReach(cell, remaining));
                v = Math.max(Grid.DISCOUNT_FLOOR, 1.0 - P.sweepGain * n / 6.0);
                memo.put(k, v);
            }
            return v;
        };
    }

    private int transEstimate(P pos, P cell, boolean[] remaining) {
        double L = Grid.dist(pos, cell);
        int n = Math.max(1, (int) (L / 3.0));
        Set<Integer> seen = new HashSet<>();
        int n2 = 0;
        for (int k = 1; k < n; k++) {
            double t = (double) k / n;
            P q = new P((int) Math.round(pos.x() + (cell.x() - pos.x()) * t), (int) Math.round(pos.y() + (cell.y() - pos.y()) * t),
                    (int) Math.round(pos.z() + (cell.z() - pos.z()) * t));
            if (!Grid.standable(grid, q)) continue;
            for (int i : reach(q)) if (remaining[i] && seen.add(chain.preKey(i))) n2 += chain.preValue(i);
        }
        return n2;
    }

    private List<Cand> candidates(State st, boolean[] remaining) {
        P pos = st.pos;
        List<Cand> scored = new ArrayList<>();
        if (P.pointMode) {
            Map<Long, P> cells = new HashMap<>();
            Map<Long, Integer> lives = new HashMap<>();
            int[] dys = {-1, 0, 1, 2, -2, 3, -3};
            int span = candSpan(P.breakReach);
            for (int i = 0; i < chests.size(); i++) {
                if (!remaining[i]) continue;
                P c = chests.get(i);
                for (int dx = -span; dx <= span; dx++) {
                    for (int dz = -span; dz <= span; dz++) {
                        for (int dy : dys) {
                            P q = new P(c.x() + dx, c.y() + dy, c.z() + dz);
                            long k = ckey(q);
                            if (cells.containsKey(k) || !Grid.standable(grid, q)) continue;
                            int live = liveReachPre(q, remaining);
                            if (live > 0) { cells.put(k, q); lives.put(k, live); }
                        }
                    }
                }
            }
            for (Map.Entry<Long, P> e : cells.entrySet()) {
                double d = Grid.dist(pos, e.getValue());
                List<P> one = new ArrayList<>(1);
                one.add(e.getValue());
                scored.add(new Cand(one, 0, lives.get(e.getKey()) / (1.0 + d / 15.0)));
            }
        } else {
            for (Corridor c : corridors) {
                for (int direction = 0; direction < 2; direction++) {
                    List<P> seq = new ArrayList<>(c.cells);
                    if (direction == 1) java.util.Collections.reverse(seq);
                    for (int entry = 0; entry < Math.max(1, seq.size() - P.minLaneLen + 1); entry += P.entrySpacing) {
                        int live = liveSetValue(seq, entry, remaining);
                        if (live == 0) continue;
                        double d = Grid.dist(pos, seq.get(entry));
                        scored.add(new Cand(seq, entry, live / (1.0 + d / 15.0)));
                    }
                }
            }
        }
        scored.sort((u, v) -> Double.compare(v.score, u.score));
        Set<Long> reach = reachableFrom(pos);
        for (Cand cd : scored) cd.far = !reach.contains(Grid.cellKey(cd.seq.get(cd.entry)));
        int K = P.proxyTopK;
        List<Cand> top = scored.subList(0, Math.min(scored.size(), 3 * K));
        for (Cand cd : top) {
            P start = cd.seq.get(cd.entry);
            double d = Grid.dist(pos, start);
            cd.score = (liveSetValue(cd.seq, cd.entry, remaining) + transEstimate(pos, start, remaining)) / (1.0 + d / 15.0);
        }
        List<Cand> rer = new ArrayList<>(top);
        rer.sort((u, v) -> Double.compare(v.score, u.score));
        if (scored.size() > top.size()) rer.addAll(scored.subList(top.size(), scored.size()));
        return rer;
    }

    /** The forward walkable component from a position, cached per cell for the plan's lifetime. */
    private Set<Long> reachableFrom(P pos) {
        return componentCache.computeIfAbsent(ckey(pos), k -> Grid.reachable(grid, pos));
    }

    /** Take-off cells tried by the exit safety net, nearest the exit first. */
    static final int EXIT_TAKEOFFS = 48;
    /** Take-off cells sampled evenly from the rest of the walkable component when the nearest ones find no hop. */
    static final int EXIT_WIDE_TAKEOFFS = 1024;
    /** Hops of the wide flight tried when no single take-off hop reaches the exit. */
    static final int EXIT_WIDE_HOPS = 5;

    /**
     * The exit safety net, used only when neither a walk nor a 3-hop flight reaches the exit (a sunken or roofed part
     * of the room with no way up on foot): walk to one of the {@link #EXIT_TAKEOFFS} reachable cells nearest the exit
     * (ties: higher, then lower x, then lower z), warp to a landing in its line of sight from which the exit can be
     * walked, then walk out. When none of those has such a landing (the cells nearest the exit can sit under the very
     * ledge that blocks it), up to {@link #EXIT_WIDE_TAKEOFFS} of the remaining reachable cells, evenly spaced in that
     * order, are tried. The cheapest take-off / landing pair wins (straight distance to the take-off, the hop, the
     * hop's length and the walk out). Null when no pair works. Mirrors the native {@code take_off_exit}.
     */
    private List<P> takeOffExit(P a, P b) {
        if (exitField == null) return null;
        java.util.Set<Long> reach = Grid.reachable(grid, a);
        List<P> cand = new ArrayList<>();
        for (int x = 0; x < grid.sx; x++) {
            for (int y = 0; y < grid.sy; y++) {
                for (int z = 0; z < grid.sz; z++) {
                    P c = new P(x, y, z);
                    if (reach.contains(Grid.cellKey(c))) cand.add(c);
                }
            }
        }
        cand.sort((u, v) -> {
            int k = Double.compare(Grid.dist(u, b), Grid.dist(v, b));
            if (k != 0) return k;
            if (u.y() != v.y()) return Integer.compare(v.y(), u.y());
            if (u.x() != v.x()) return Integer.compare(u.x(), v.x());
            return Integer.compare(u.z(), v.z());
        });
        double bestC = Double.POSITIVE_INFINITY;
        P bestT = null, bestL = null;
        int near = Math.min(EXIT_TAKEOFFS, cand.size());
        int rest = cand.size() - near;
        int stride = Math.max(1, (rest + EXIT_WIDE_TAKEOFFS - 1) / EXIT_WIDE_TAKEOFFS);
        for (int pass = 0; pass < 2 && bestT == null; pass++) {
            int from = pass == 0 ? 0 : near, to = pass == 0 ? near : cand.size(), step = pass == 0 ? 1 : stride;
            for (int i = from; i < to; i += step) {
                P t = cand.get(i);
                double base = Grid.dist(a, t) + Grid.HOP_COST;
                for (P l : pass == 0 ? landingsFrom(t) : Grid.landings(grid, t)) {
                    Double w = exitField.get(Grid.cellKey(l));
                    if (w == null || w.isNaN()) continue;
                    double cost = base + Grid.FLIGHT_COST_PER_BLOCK * Grid.dist(t, l) + w;
                    if (bestT == null || cost < bestC) {
                        bestC = cost;
                        bestT = t;
                        bestL = l;
                    }
                }
            }
        }
        if (bestT == null) return null;
        List<P> toT = Grid.astar(grid, a, bestT, null);
        if (toT == null) return null;
        List<P> out = Grid.astar(grid, bestL, b, null);
        if (out == null) return null;
        List<P> all = new ArrayList<>(toT);
        all.addAll(out);
        return all;
    }

    /** The flight landings visible from a position, cached per cell for the plan's lifetime. */
    private List<P> landingsFrom(P pos) {
        return landingCache.computeIfAbsent(ckey(pos), k -> Grid.landings(grid, pos));
    }

    /**
     * Evaluate walkable candidates in rank order until {@code K} valid lanes are in hand (at most 4K tries), first
     * without long transitions and, only if that finds nothing, with them. Flights are a fallback: candidates
     * needing one are evaluated (a few tries) only when no walkable lane beats the bail.
     */
    private List<Lane> evalTop(State st, boolean[] remaining, double bail, int K) {
        List<Cand> cands = candidates(st, remaining);
        List<Lane> evals = new ArrayList<>();
        for (boolean allowLong : new boolean[]{false, true}) {
            int ok = 0, tried = 0;
            for (Cand cd : cands) {
                if (cd.far) continue;
                if (ok >= K || tried >= 4 * K) break;
                tried++;
                Lane e = evaluate(st, cd.seq, cd.entry, remaining, false, allowLong);
                if (e != null) {
                    evals.add(e);
                    ok++;
                }
            }
            if (!evals.isEmpty()) break;
        }
        double bestNear = 0.0;
        for (Lane e : evals) bestNear = Math.max(bestNear, e.rateX);
        if ((evals.isEmpty() || bestNear < bail) && P.allowFly) {
            int ok = 0, tried = 0;
            for (Cand cd : cands) {
                if (!cd.far) continue;
                if (ok >= P.farOk || tried >= P.farTries) break;
                tried++;
                Lane e = evaluate(st, cd.seq, cd.entry, remaining, true, true);
                if (e != null) {
                    evals.add(e);
                    ok++;
                }
            }
        }
        return evals;
    }

    /**
     * Model seconds from a cell to the exit: the exit field's walk cost through the leg model, or, for a cell with no
     * ground route out, the straight distance plus the flight and stranding penalties. Cached per cell.
     */
    private double exitTime(P c) {
        long k = ckey(c);
        Double t = exitTimeCache.get(k);
        if (t != null) return t;
        Double w = exitField == null ? null : exitField.get(Grid.cellKey(c));
        double straight = Math.max(Grid.dist(c, exitCell), 0.5);
        double climb = Math.max(0, exitCell.y() - c.y()), drop = Math.max(0, c.y() - exitCell.y());
        int cl = grid.clearanceFlyAt(c.x(), c.y(), c.z());
        double secs;
        if (w == null) {
            secs = model.seconds(straight, 1.0, climb, drop, cl, cl, cl <= 1 ? 1 : 0, 0, 0, 0, 0, 0) + P.flyPenaltyS + P.strandPenaltyS;
        } else {
            secs = model.seconds(straight, Math.max(w, straight) / straight, climb, drop, cl, cl, cl <= 1 ? 1 : 0, 0, 0, 0, 0, 0);
        }
        exitTimeCache.put(k, secs);
        return secs;
    }

    /** Rate over a leg time with the signed change in exit time added; the denominator never drops below 30% of the leg. */
    private double exitAwareRate(int yield, double legTime, double dExit) {
        return yield / Math.max(Math.max(legTime + dExit, 0.3 * legTime), 0.05);
    }

    // ---- evaluate one candidate from a state ----

    /**
     * Seconds lost turning back at a junction: nothing up to 60 degrees between the arrival heading and the
     * transition's first steps, the full turnaround penalty from {@code turnaroundDeg}, linear between.
     */
    private double reversalPenalty(double turnDeg) {
        double onset = 60.0;
        double span = Math.max(P.turnaroundDeg - onset, 1.0);
        double f = Math.max(0.0, Math.min(1.0, (turnDeg - onset) / span));
        return P.turnaroundPenaltyS * f;
    }

    private Lane evaluate(State st, List<P> seq, int entry, boolean[] remainingIn, boolean far, boolean allowLong) {
        List<P> cells = new ArrayList<>(seq.subList(entry, seq.size()));
        int last = -1;
        for (int k = 0; k < cells.size(); k++) if (liveReach(cells.get(k), remainingIn) > 0) last = k;
        if (last < 0) return null;
        cells = new ArrayList<>(cells.subList(0, last + 1));
        if (cells.size() < 2 && !P.pointMode) return null;
        boolean[] remaining = remainingIn.clone();
        P start = cells.get(0), pos = st.pos;
        List<P> path = far ? null : Grid.astar(grid, pos, start, sweepDiscount(remainingIn));
        double flyPenalty = 0.0;
        if (path == null) {
            if (!P.allowFly) return null;
            path = Grid.flight(grid, pos, start, this::landingsFrom, 2);
            if (path == null) return null;
            flyPenalty = P.flyPenaltyS;
        }
        if (!allowLong && !far && Grid.walkLength(path) > P.maxTransLen) return null;
        double[] d0 = cells.size() == 1 ? hdir(pos, start) : hdir(cells.get(0), cells.get(cells.size() - 1));
        double[] out = path.size() >= 2 ? hdir(path.get(0), path.get(Math.min(3, path.size() - 1))) : hdir(pos, start);
        double turnIn = st.heading == null ? 0.0 : angle(st.heading, out);
        double[] tail = path.size() >= 2 ? hdir(path.get(path.size() - 2), path.get(path.size() - 1)) : hdir(pos, start);
        double align = angle(tail, d0);
        if (model.shape != null) return evaluateShape(st, cells, path, remaining, flyPenalty, d0, tail, align);
        double tTrans = Grid.dist(pos, start) > 0.75 ? legTime(pos, start, path, remaining, st.prevBurst, turnIn) : 0.0;
        double penalty = (align > P.turnaroundDeg ? P.turnaroundPenaltyS : 0.0) + reversalPenalty(turnIn) + flyPenalty;
        List<Trigger> transTriggers = new ArrayList<>();
        int yTrans = path.size() > 1 ? sweep(path.subList(0, path.size() - 1), remaining, transTriggers) : 0;
        tTrans += P.triggerS * transTriggers.size();
        List<Trigger> triggers = new ArrayList<>();
        int yLane = sweep(cells, remaining, triggers);
        int yTotal = yTrans + yLane;
        if (yTotal == 0) return null;
        double tLane = 0.0;
        P cur = start;
        int pb = st.prevBurst;
        for (Trigger t : triggers) {
            if (Grid.dist(cur, t.cell) > 0.5) tLane += legTime(cur, t.cell, null, remaining, pb, 0.0);
            tLane += P.triggerS;
            pb = t.cleared.length;
            cur = t.cell;
        }
        P end = cells.get(cells.size() - 1);
        if (Grid.dist(cur, end) > 0.5) tLane += legTime(cur, end, null, remaining, pb, 0.0);
        Lane e = new Lane();
        e.cells = cells; e.dir = d0; e.trans = path; e.yield = yTotal; e.yieldTrans = yTrans;
        e.tTrans = tTrans + penalty; e.tLane = tLane;
        e.rate = yTotal / Math.max(e.tTrans + tLane, 0.05);
        e.end = end;
        e.dExit = P.exitWeight * (exitTime(end) - exitTime(pos));
        e.rateX = exitAwareRate(yTotal, e.tTrans + tLane, e.dExit);
        e.remaining = remaining; e.triggers = triggers; e.transTriggers = transTriggers;
        e.nTrig = triggers.size() + transTriggers.size(); e.tPen = penalty;
        e.endHeading = cells.size() == 1 ? tail : d0; e.endBurst = pb; e.align = align;
        return e;
    }

    // ---- the shape (move) model: a leg priced move by move from its cells ----

    /**
     * {@link #evaluate} for the shape model: the same path, sweeps and yields, with the transition and lane priced by
     * {@link #shapeCost}. Junction turns and turnarounds are priced as moves, so only the flight penalty stays fixed.
     */
    private Lane evaluateShape(State st, List<P> cells, List<P> path, boolean[] remaining, double flyPenalty, double[] d0,
                               double[] tail, double align) {
        List<Trigger> transTriggers = new ArrayList<>();
        int yTrans = path.size() > 1 ? sweep(path.subList(0, path.size() - 1), remaining, transTriggers) : 0;
        List<Trigger> triggers = new ArrayList<>();
        int yLane = sweep(cells, remaining, triggers);
        int yTotal = yTrans + yLane;
        if (yTotal == 0) return null;
        List<P> seq = new ArrayList<>(st.back.size() + path.size() + cells.size());
        seq.addAll(st.back);
        int i0 = seq.size();
        seq.addAll(path);
        int split = seq.size() - 1;
        seq.addAll(cells.subList(1, cells.size()));
        List<Trigger> all = new ArrayList<>(transTriggers.size() + triggers.size());
        all.addAll(transTriggers);
        all.addAll(triggers);
        int[] idx = new int[all.size()];
        boolean[] inA = new boolean[all.size()];
        int ptr = i0;
        for (int i = 0; i < transTriggers.size(); i++) {
            idx[i] = seqIndex(seq, ptr, transTriggers.get(i).cell);
            ptr = idx[i];
            inA[i] = true;
        }
        ptr = split;
        for (int i = transTriggers.size(); i < all.size(); i++) {
            idx[i] = seqIndex(seq, ptr, all.get(i).cell);
            ptr = idx[i];
        }
        double[] sc = shapeCost(seq, i0, split, all, idx, inA, st.sinceTrig);
        int pb = st.prevBurst;
        for (Trigger t : triggers) pb = t.cleared.length;
        P end = cells.get(cells.size() - 1);
        Lane e = new Lane();
        e.cells = cells; e.dir = d0; e.trans = path; e.yield = yTotal; e.yieldTrans = yTrans;
        e.tTrans = sc[0] + flyPenalty; e.tLane = sc[1];
        e.rate = yTotal / Math.max(e.tTrans + e.tLane, 0.05);
        e.end = end;
        e.dExit = P.exitWeight * (exitTime(end) - exitTime(st.pos));
        e.rateX = exitAwareRate(yTotal, e.tTrans + e.tLane, e.dExit);
        e.remaining = remaining; e.triggers = triggers; e.transTriggers = transTriggers;
        e.nTrig = all.size(); e.tPen = flyPenalty;
        e.endHeading = cells.size() == 1 ? tail : d0; e.endBurst = pb; e.align = align;
        e.endBack = new ArrayList<>(seq.subList(Math.max(0, seq.size() - 6), seq.size() - 1));
        e.endSinceTrig = sc[2];
        return e;
    }

    /** First index at or after {@code from} whose cell is {@code cell}; {@code from} (with an error logged) when none is. */
    private static int seqIndex(List<P> seq, int from, P cell) {
        for (int j = from; j < seq.size(); j++) if (seq.get(j).equals(cell)) return j;
        LOGGER.log(System.Logger.Level.ERROR, "[Routerunner] shape model: planned click cell " + cell + " is not on its leg; pricing it at leg index " + from + ".");
        return from;
    }

    /** Degrees rounded to 1e-6, so Java and Rust agree on every bucket edge despite 1-ulp acos differences. */
    static double roundDeg(double deg) {
        return Math.round(deg * 1e6) / 1e6;
    }

    /** log1p(n) rounded to 1e-9, for the same reason. */
    static double log1pRound(int n) {
        return Math.round(Math.log1p(n) * 1e9) / 1e9;
    }

    /** Unsigned horizontal angle between two vectors, degrees (rounded); 0 when either is shorter than {@code minLen}. */
    static double hAngle(double ax, double az, double bx, double bz, double minLen) {
        double la = Math.sqrt(ax * ax + az * az), lb = Math.sqrt(bx * bx + bz * bz);
        if (la < minLen || lb < minLen || la < 1e-9 || lb < 1e-9) return 0.0;
        double c = (ax * bx + az * bz) / (la * lb);
        return roundDeg(Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, c)))));
    }

    /**
     * Shape-model seconds for a stretch of route. {@code seq} is the route's previous cells followed by the stretch,
     * which starts at index {@code i0}; steps, turns and clicks at or before cell {@code split} count as part A (the
     * transition), the rest as part B (the lane). {@code trig} are the planned clicks in firing order, {@code idx} their
     * cells' indices in {@code seq}, {@code inA} which part each belongs to. {@code sinceTrig0} is the horizontal walk
     * since the last click before the stretch. Returns {A, B, horizontal walk since the last click at the stretch's end}.
     *
     * <p>Moves, as in the research fit: a step costs its run cost (open, mid or tight by the lower clearance of its two
     * cells, times its horizontal length) plus a one-block step up, a shaft block, a small drop or a fall block; a turn is
     * a local maximum of the heading change between the three cells before and after a cell (at least 20 degrees, one
     * per seven cells), bucketed at 60 and 120 degrees with a clearance add-on, or a turnaround when it is at least 135
     * degrees and the next three cells retrace the last four; a click costs its base plus its angle off the heading
     * (from two cells back), target height, reach beyond three blocks, a burst discount within three blocks of the last
     * click and its group size, floored at zero.
     */
    double[] shapeCost(List<P> seq, int i0, int split, List<Trigger> trig, int[] idx, boolean[] inA, double sinceTrig0) {
        double[] c = model.shape;
        int K = seq.size();
        double[] harc = new double[K];
        for (int j = 1; j < K; j++) harc[j] = harc[j - 1] + Grid.hdist(seq.get(j - 1), seq.get(j));
        double a = 0.0, b = 0.0;
        for (int j = Math.max(i0, 0); j < K - 1; j++) {
            P p = seq.get(j), q = seq.get(j + 1);
            double h = harc[j + 1] - harc[j];
            int cl = Math.min(grid.clearanceFlyAt(p.x(), p.y(), p.z()), grid.clearanceFlyAt(q.x(), q.y(), q.z()));
            double s = (cl >= 3 ? c[LegTimeModel.S_RUN_OPEN] : cl == 2 ? c[LegTimeModel.S_RUN_MID] : c[LegTimeModel.S_RUN_TIGHT]) * h;
            int dy = q.y() - p.y();
            if (dy == 1) s += c[LegTimeModel.S_UP1];
            else if (dy >= 2) s += c[LegTimeModel.S_UP_SHAFT] * dy;
            else if (dy <= -1 && dy >= -3) s += c[LegTimeModel.S_DROP_SMALL];
            else if (dy < -3) s += c[LegTimeModel.S_DROP_BIG] * -dy;
            if (j + 1 <= split) a += s; else b += s;
        }
        List<double[]> cand = new ArrayList<>();
        for (int k = Math.max(2, i0 - 1); k <= K - 3; k++) {
            int wi = Math.min(3, k), wo = Math.min(3, K - 1 - k);
            P pk = seq.get(k), pa = seq.get(k - wi), pb = seq.get(k + wo);
            double ax = pk.x() - pa.x(), az = pk.z() - pa.z(), bx = pb.x() - pk.x(), bz = pb.z() - pk.z();
            double ang = hAngle(ax, az, bx, bz, 1.0);
            if (ang >= 20.0) cand.add(new double[]{ang, k});
        }
        cand.sort((u, v) -> u[0] != v[0] ? Double.compare(v[0], u[0]) : Double.compare(v[1], u[1]));
        boolean[] taken = new boolean[K];
        List<double[]> turns = new ArrayList<>();
        for (double[] cd : cand) {
            int k = (int) cd[1];
            boolean near = false;
            for (int j = Math.max(0, k - 3); j <= Math.min(K - 1, k + 3); j++) if (taken[j]) { near = true; break; }
            if (near) continue;
            taken[k] = true;
            turns.add(cd);
        }
        turns.sort((u, v) -> Double.compare(u[1], v[1]));
        for (double[] cd : turns) {
            double ang = cd[0];
            int k = (int) cd[1];
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
            double s = retrace ? c[LegTimeModel.S_TA] + c[LegTimeModel.S_TA_CLR] * tt
                    : (ang < 60.0 ? c[LegTimeModel.S_T20] : ang < 120.0 ? c[LegTimeModel.S_T60] : c[LegTimeModel.S_T120])
                    + c[LegTimeModel.S_TURN_CLR] * (tt * ang / 90.0);
            if (k <= split) a += s; else b += s;
        }
        boolean hasPrev = false;
        double prevArc = 0.0;
        for (int i = 0; i < trig.size(); i++) {
            Trigger t = trig.get(i);
            int k = idx[i];
            double gap = hasPrev ? harc[k] - prevArc : sinceTrig0 + (harc[k] - harc[i0]);
            P cell = seq.get(k), back2 = seq.get(Math.max(0, k - 2));
            double hx = cell.x() - back2.x(), hz = cell.z() - back2.z();
            if (Math.sqrt(hx * hx + hz * hz) < 0.5) {
                P fwd = seq.get(Math.min(K - 1, k + 2));
                hx = fwd.x() - cell.x();
                hz = fwd.z() - cell.z();
            }
            P ch = chests.get(t.chest);
            double vx = ch.x() - cell.x(), vy = (ch.y() + 0.5) - (cell.y() + 1.62), vz = ch.z() - cell.z();
            double ang = hAngle(hx, hz, vx, vz, 0.5);
            double s = c[LegTimeModel.S_CLICK];
            if (ang >= 30.0 && ang < 75.0) s += c[LegTimeModel.S_SIDE];
            else if (ang >= 75.0 && ang < 120.0) s += c[LegTimeModel.S_WIDE];
            else if (ang >= 120.0) s += c[LegTimeModel.S_BEHIND];
            if (vy > 0.5) s += c[LegTimeModel.S_ABOVE];
            else if (vy < -2.5) s += c[LegTimeModel.S_BELOW];
            s += c[LegTimeModel.S_REACH] * Math.max(0.0, Math.sqrt(vx * vx + vy * vy + vz * vz) - 3.0);
            if (gap <= 3.0) s += c[LegTimeModel.S_BURST];
            s += c[LegTimeModel.S_SIZE] * log1pRound(t.cleared.length);
            s = Math.max(0.0, s);
            if (inA[i]) a += s; else b += s;
            hasPrev = true;
            prevArc = harc[k];
        }
        double since = hasPrev ? harc[K - 1] - prevArc : sinceTrig0 + (harc[K - 1] - harc[Math.min(i0, K - 1)]);
        return new double[]{a, b, since};
    }

    private Lane bestLane(State st, boolean[] remaining, int depth, double bail) {
        int K = depth > 0 ? P.proxyTopK : Math.max(3, P.proxyTopK / 2);
        List<Lane> evals = evalTop(st, remaining, bail, K);
        if (evals.isEmpty()) return null;
        evals.sort((u, v) -> Double.compare(v.rateX, u.rateX));
        if (depth <= 0 || !P.lookahead) return evals.get(0);
        Lane best = null;
        double bestScore = -1;
        for (Lane e : evals.subList(0, Math.min(evals.size(), P.beamWidth))) {
            State st2 = new State(e.end, e.endHeading, e.endBurst, e.endBack, e.endSinceTrig);
            Lane f = bestLane(st2, e.remaining, depth - 1, bail);
            double score;
            if (f == null) {
                score = e.rateX;
            } else {
                double tt = e.tTrans + e.tLane + f.tTrans + f.tLane;
                score = exitAwareRate(e.yield + f.yield, tt, P.exitWeight * (exitTime(f.end) - exitTime(st.pos)));
            }
            if (score > bestScore) { bestScore = score; best = e; }
        }
        return best;
    }

    // ---- the plan ----

    public Plan plan(P entrance, P exit) {
        boolean[] all = new boolean[chests.size()];
        Arrays.fill(all, true);
        return plan(entrance, exit, all);
    }

    /**
     * Plan over the chests flagged in {@code remainingIn} from {@code entrance} to {@code exit}. Reusable across
     * replans on one planner: the reach, component, landing and exit-field caches persist; only the exit field is
     * rebuilt, and only when the exit cell changes.
     */
    public Plan plan(P entrance, P exit, boolean[] remainingIn) {
        if (nativeHandle != 0) {
            Plan np = planNative(entrance, exit, remainingIn);
            if (np != null) return np;
        }
        long ek = ckey(exit);
        if (exitField == null || exitFieldKey != ek) {
            exitField = Grid.exitField(grid, exit);
            exitFieldKey = ek;
            exitCell = exit;
            exitTimeCache.clear();
        }
        int live = 0;
        for (int i = 0; i < remainingIn.length; i++) if (remainingIn[i]) live += chain.value(i);
        boolean[] remaining = remainingIn.clone();
        State st = new State(entrance, null, 0);
        List<Double> first = new ArrayList<>();
        for (Lane e : evalTop(st, remaining, 0.0, P.proxyTopK)) first.add(e.rate);
        Plan plan = new Plan();
        plan.nCorridors = corridors.size();
        if (!first.isEmpty()) {
            java.util.Collections.sort(first);
            plan.opportunity = first.get((int) Math.floor(0.75 * (first.size() - 1)));
        }
        plan.opportunity = Math.max(plan.opportunity, P.opportunityFloor);
        plan.bail = Math.max(P.bailAggression * plan.opportunity, P.bailFloor);
        double t = 0.0;
        while (plan.lanes.size() < P.maxLanes) {
            Lane e = bestLane(st, remaining, 1, plan.bail);
            boolean firstExempt = plan.lanes.isEmpty() && P.opportunityFloor <= 0.0;
            if (e == null || (!firstExempt && e.rateX < plan.bail)) break;
            List<P> heatCells = new ArrayList<>(e.trans);
            heatCells.addAll(e.cells);
            plan.heat.add(heat(remaining, heatCells));
            e.tStart = t;
            t += e.tTrans + e.tLane;
            plan.yieldTotal += e.yield;
            plan.lanes.add(e);
            remaining = e.remaining;
            st = new State(e.end, e.endHeading, e.endBurst, e.endBack, e.endSinceTrig);
        }
        List<P> exitPath = Grid.astar(grid, st.pos, exit, null);
        if (exitPath == null && P.allowFly) {
            exitPath = Grid.flight(grid, st.pos, exit, this::landingsFrom, 3);
            if (exitPath == null) {
                exitPath = takeOffExit(st.pos, exit);
                if (exitPath == null) exitPath = Grid.flight(grid, st.pos, exit, this::landingsFrom, EXIT_WIDE_HOPS);
                if (exitPath != null) plan.exitHop = true;
            }
            if (exitPath == null) {
                exitPath = new ArrayList<>();
                exitPath.add(st.pos);
                exitPath.add(exit);
                plan.exitStraight = true;
            }
        }
        double tExit;
        if (model.shape == null) {
            tExit = exitPath != null ? legTime(st.pos, exit, exitPath, remaining, st.prevBurst, 0.0) : 0.0;
            if (exitPath != null && exitPath.size() > 1) {
                int y = sweep(exitPath.subList(1, exitPath.size()), remaining, plan.exitTriggers);
                tExit += P.triggerS * plan.exitTriggers.size();
                plan.yieldTotal += y;
            }
        } else {
            tExit = 0.0;
            if (exitPath != null && exitPath.size() > 1) plan.yieldTotal += sweep(exitPath.subList(1, exitPath.size()), remaining, plan.exitTriggers);
            if (exitPath != null) {
                List<P> seq = new ArrayList<>(st.back.size() + exitPath.size());
                seq.addAll(st.back);
                int i0 = seq.size();
                seq.addAll(exitPath);
                int[] idx = new int[plan.exitTriggers.size()];
                boolean[] inA = new boolean[idx.length];
                int ptr = Math.min(i0 + 1, seq.size() - 1);
                for (int i = 0; i < idx.length; i++) {
                    idx[i] = seqIndex(seq, ptr, plan.exitTriggers.get(i).cell);
                    ptr = idx[i];
                    inA[i] = true;
                }
                double[] sc = shapeCost(seq, i0, seq.size() - 1, plan.exitTriggers, idx, inA, st.sinceTrig);
                tExit = sc[0] + sc[1];
            }
        }
        plan.exitPath = exitPath;
        plan.tExit = tExit;
        plan.tTotal = t + tExit + (model.shape != null ? model.shape[LegTimeModel.S_ROOM_FIXED] : 0.0);
        plan.cover = live == 0 ? 0 : (double) plan.yieldTotal / live;
        mergeRuns(plan);
        ghost(plan, entrance);
        return plan;
    }

    /** clearFrom size for every live chest reachable from the given cells (collateral included). */
    private Map<Integer, Integer> heat(boolean[] remaining, List<P> cells) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (P c : cells) {
            for (int i : reach(c)) {
                if (remaining[i] && !out.containsKey(i)) out.put(i, chain.clearFrom(i, remaining).size());
            }
        }
        return out;
    }

    private void mergeRuns(Plan plan) {
        List<int[]> runs = new ArrayList<>();
        List<List<Integer>> tmp = new ArrayList<>();
        double runLen = 0.0;
        for (int k = 0; k < plan.lanes.size(); k++) {
            Lane e = plan.lanes.get(k);
            double transLen = Grid.walkLength(e.trans);
            double laneLen = transLen + Grid.walkLength(e.cells);
            boolean same = k > 0 && (P.pointMode || transLen <= P.mergeTrans)
                    && angle(plan.lanes.get(k - 1).dir, e.dir) <= P.turnCap
                    && runLen + laneLen <= P.maxRunLen;
            if (!tmp.isEmpty() && same) {
                tmp.get(tmp.size() - 1).add(k);
                runLen += laneLen;
            } else {
                List<Integer> r = new ArrayList<>();
                r.add(k);
                tmp.add(r);
                runLen = laneLen;
            }
        }
        for (int ri = 0; ri < tmp.size(); ri++) {
            int[] arr = tmp.get(ri).stream().mapToInt(Integer::intValue).toArray();
            for (int k : arr) plan.lanes.get(k).run = ri;
            runs.add(arr);
        }
        plan.runs = runs;
    }

    // ---- the ghost: the plan executed at the model's leg times × timeScale ----

    private double gt;
    private P gpos;
    private double gyaw;
    private Random grnd;

    private void ghost(Plan plan, P entrance) {
        grnd = new Random(P.seed);
        gt = 0.0;
        gpos = entrance;
        gyaw = 0.0;
        for (Lane e : plan.lanes) {
            plan.laneT.add(gt * 1000);
            List<P> pts = new ArrayList<>();
            pts.add(gpos);
            pts.addAll(e.trans);
            walk(plan, pts, e.tTrans, e.transTriggers);
            P cur = e.cells.get(0);
            int pb = 0;
            Map<Long, Integer> idx = new HashMap<>();
            for (int k = 0; k < e.cells.size(); k++) idx.put(ckey(e.cells.get(k)), k);
            for (Trigger tr : e.triggers) {
                int i0 = idx.getOrDefault(ckey(cur), 0), i1 = idx.getOrDefault(ckey(tr.cell), 0);
                List<P> seg = e.cells.subList(Math.min(i0, i1), i1 + 1);
                if (seg.size() >= 2) walk(plan, seg, legTime(cur, tr.cell, null, e.remaining, pb, 0.0), List.of());
                fire(plan, tr.cell, tr.chest, tr.cleared);
                pb = tr.cleared.length;
                cur = tr.cell;
                gpos = cur;
            }
            List<P> tail = e.cells.subList(idx.getOrDefault(ckey(cur), 0), e.cells.size());
            if (tail.size() >= 2) walk(plan, tail, legTime(cur, e.cells.get(e.cells.size() - 1), null, e.remaining, pb, 0.0), List.of());
        }
        if (plan.exitPath != null) {
            List<P> pts = new ArrayList<>();
            pts.add(gpos);
            pts.addAll(plan.exitPath);
            walk(plan, pts, plan.tExit, plan.exitTriggers);
        }
    }

    private void fire(Plan plan, P cell, int chest, int[] cleared) {
        P c = chests.get(chest);
        double yw = Math.toDegrees(Math.atan2(-(c.x() - cell.x()), c.z() - cell.z()));
        double h = Math.hypot(c.x() - cell.x(), c.z() - cell.z());
        double pitch = -Math.toDegrees(Math.atan2(c.y() - (cell.y() + 1.62), h == 0 ? 0.1 : h));
        plan.ghost.add(new double[]{gt * 1000, cell.x() + 0.5, cell.y(), cell.z() + 0.5, yw, pitch});
        plan.clears.add(new Object[]{gt * 1000, chest, cleared});
        gt += P.triggerS * P.timeScale;
    }

    private void walk(Plan plan, List<P> pts, double secs, List<Trigger> triggers) {
        Map<Long, List<Trigger>> tset = new HashMap<>();
        for (Trigger tr : triggers) tset.computeIfAbsent(ckey(tr.cell), k -> new ArrayList<>()).add(tr);
        if (pts.size() < 2) {
            for (List<Trigger> l : tset.values()) for (Trigger tr : l) fire(plan, tr.cell, tr.chest, tr.cleared);
            return;
        }
        double L = 0;
        for (int i = 1; i < pts.size(); i++) L += Grid.dist(pts.get(i - 1), pts.get(i));
        double noise = P.ghostNoise > 0 ? Math.exp(grnd.nextGaussian() * P.ghostNoise * model.sigma) : 1.0;
        secs = secs * P.timeScale * noise;
        double v = L > 0 ? L / Math.max(secs, 0.05) : 1.0;
        for (int i = 1; i < pts.size(); i++) {
            P a = pts.get(i - 1), b = pts.get(i);
            double d = Grid.dist(a, b);
            gyaw = Math.toDegrees(Math.atan2(-(b.x() - a.x()), b.z() - a.z()));
            plan.ghost.add(new double[]{gt * 1000, a.x() + 0.5, a.y(), a.z() + 0.5, gyaw, 0.0});
            List<Trigger> here = tset.remove(ckey(a));
            if (here != null) for (Trigger tr : here) fire(plan, a, tr.chest, tr.cleared);
            gt += d / v;
            gpos = b;
        }
        for (List<Trigger> l : tset.values()) for (Trigger tr : l) fire(plan, gpos, tr.chest, tr.cleared);
        plan.ghost.add(new double[]{gt * 1000, gpos.x() + 0.5, gpos.y(), gpos.z() + 0.5, gyaw, 0.0});
    }

    // ---- export for the replay viewer (world coords, absolute active-clock ms) ----

    public Map<String, Object> export(Plan plan, int ox, int oy, int oz, long tEntry) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> runs = new ArrayList<>();
        double R = P.breakReach;
        int Ri = (int) R;
        for (int ri = 0; ri < plan.runs.size(); ri++) {
            int[] ks = plan.runs.get(ri);
            List<P> poly = new ArrayList<>();
            int yield = 0;
            for (int k : ks) {
                Lane e = plan.lanes.get(k);
                yield += e.yield;
                List<P> pts = new ArrayList<>(e.trans);
                if (e.cells.size() > 1) pts.addAll(e.cells);
                for (P c : pts) if (poly.isEmpty() || !poly.get(poly.size() - 1).equals(c)) poly.add(c);
            }
            Set<Long> carpetKeys = new HashSet<>();
            List<int[]> carpet = new ArrayList<>();
            for (P c : poly) {
                for (int dx = -Ri; dx <= Ri; dx++) {
                    for (int dz = -Ri; dz <= Ri; dz++) {
                        if (dx * dx + dz * dz > R * R) continue;
                        for (int dy : new int[]{0, 1, -1}) {
                            P q = new P(c.x() + dx, c.y() + dy, c.z() + dz);
                            if (Grid.standable(grid, q)) {
                                if (carpetKeys.add(ckey(q))) carpet.add(new int[]{q.x() + ox, q.y() + oy, q.z() + oz});
                                break;
                            }
                        }
                    }
                }
            }
            List<int[]> polyW = new ArrayList<>();
            for (P c : poly) polyW.add(new int[]{c.x() + ox, c.y() + oy, c.z() + oz});
            double t0 = tEntry + plan.laneT.get(ks[0]);
            int lastK = ks[ks.length - 1];
            double t1 = tEntry + (lastK + 1 < plan.laneT.size() ? plan.laneT.get(lastK + 1)
                    : (plan.ghost.isEmpty() ? plan.laneT.get(lastK) : plan.ghost.get(plan.ghost.size() - 1)[0]));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("poly", polyW);
            r.put("carpet", carpet);
            r.put("tStart", t0);
            r.put("tEnd", t1);
            r.put("lanes", ks);
            r.put("yield_", yield);
            runs.add(r);
        }
        List<double[]> ghost = new ArrayList<>();
        for (double[] s : plan.ghost) {
            ghost.add(new double[]{Math.round(tEntry + s[0]), r2(s[1] + ox), r2(s[2] + oy), r2(s[3] + oz), r1(s[4]), r1(s[5])});
        }
        List<long[]> clears = new ArrayList<>();
        for (Object[] c : plan.clears) {
            long t = Math.round(tEntry + (double) c[0]);
            for (int j : (int[]) c[2]) {
                P ch = chests.get(j);
                clears.add(new long[]{t, ch.x() + ox, ch.y() + oy, ch.z() + oz});
            }
        }
        List<Object> heat = new ArrayList<>();
        for (int k = 0; k < plan.heat.size(); k++) {
            List<int[]> items = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : plan.heat.get(k).entrySet()) {
                P ch = chests.get(e.getKey());
                items.add(new int[]{ch.x() + ox, ch.y() + oy, ch.z() + oz, e.getValue()});
            }
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("t", Math.round(tEntry + plan.laneT.get(k)));
            h.put("items", items);
            heat.add(h);
        }
        List<int[]> exitW = new ArrayList<>();
        if (plan.exitPath != null) for (P c : plan.exitPath) exitW.add(new int[]{c.x() + ox, c.y() + oy, c.z() + oz});
        out.put("exitPath", exitW);
        out.put("exitStraight", plan.exitStraight);
        out.put("exitHop", plan.exitHop);
        out.put("runs", runs);
        out.put("ghost", ghost);
        out.put("clears", clears);
        out.put("heat", heat);
        out.put("tTotal", plan.tTotal);
        out.put("yieldTotal", plan.yieldTotal);
        out.put("cover", plan.cover);
        out.put("nLanes", plan.lanes.size());
        out.put("nRuns", plan.runs.size());
        out.put("tEntry", tEntry);
        out.put("tEnd", ghost.isEmpty() ? (double) tEntry : ghost.get(ghost.size() - 1)[0]);
        return out;
    }

    private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double r1(double v) { return Math.round(v * 10.0) / 10.0; }
}
