package com.routerunner;

import com.mojang.logging.LogUtils;
import com.routerunner.solver.P;
import com.routerunner.solver.RoutePlanner;
import com.routerunner.solver.SolidGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates per-room route solving and following, one room at a time. Picks the player's cell (or the
 * cell a hallway leads into), snapshots it once its chunks are loaded, plans its lanes off the main thread, then
 * follows the lane plan as chests break and feeds the run log, adaptive weights and diff scorer. A room the lane
 * planner cannot route shows no route (the waypoint solver is retired, see {@code legacy/waypoint/}).
 * Called on the client thread except where noted.
 */
public final class RouteService {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long NO_CELL = Long.MIN_VALUE;
    private static final ExecutorService SOLVER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "routerunner-solver");
        t.setDaemon(true);
        return t;
    });

    /** Minimum interval between {@code state} records (ms). */
    private static final long STATE_LOG_MIN_MS = 1000;
    /** Minimum matched planned clicks for a route-accuracy score. */
    private static final int ACC_MIN_MATCHES = 4;
    /** Max distance (blocks) for a break to match a planned click. */
    private static final double ACC_MATCH_R = 2.0;
    /** Average follow deviation (blocks) at which the spatial accuracy term reaches 0. */
    private static final double ACC_SPATIAL_SCALE = 3.0;

    /** The active route, or null. */
    private static volatile SolvedRoute current = null;
    /** Cell key of the in-flight solve, or NO_CELL. */
    private static volatile long solvingCell = NO_CELL;
    /** Cell key of the in-flight prefetch solve (the room ahead, solved while the player is still in the hallway), or NO_CELL. */
    private static volatile long prefetchingCell = NO_CELL;
    /** A finished prefetch waiting for the player to enter its cell. */
    private static volatile SolvedRoute prefetched = null;
    /** Wall clock before which no new prefetch is attempted (chunks not loaded, etc.). */
    private static long prefetchRetryMs = 0;
    /** The last native-planner status written to the log, so it is reported once per change. */
    private static volatile String nativeStatusLogged = "";
    /** Cells with at most this many target chests count as hallways for the look-ahead. */
    private static final int HALLWAY_MAX_CHESTS = 24;

    /**
     * The running realized chest rate (breaks over the last two active minutes) and the live model-to-real time
     * ratio (realized seconds on engaged runs over the model's seconds for them, with a prior), which together turn
     * the config's {@code laneBailRateFrac} into an absolute lane bail in model chests per second.
     */
    static final class RateCal {
        static final long WINDOW_MS = 120_000;
        static final int MIN_BREAKS = 50;
        static final long MIN_SPAN_MS = 20_000;
        static final double PRIOR_RATIO = 1.1, PRIOR_W = 5.0;
        private static final java.util.ArrayDeque<Long> breaks = new java.util.ArrayDeque<>();
        private static double plannedS = 0, realizedS = 0;

        static synchronized void onBreak(long activeMs) {
            breaks.addLast(activeMs);
            while (!breaks.isEmpty() && activeMs - breaks.peekFirst() > WINDOW_MS) breaks.pollFirst();
        }

        static synchronized void observe(double planned, double realized) {
            plannedS += planned;
            realizedS += realized;
        }

        static synchronized double ratio() {
            return Math.max(0.7, Math.min(4.0, (PRIOR_RATIO * PRIOR_W + realizedS) / (PRIOR_W + plannedS)));
        }

        static synchronized double rate(long activeMs) {
            if (breaks.size() < MIN_BREAKS) return 0.0;
            long span = activeMs - breaks.peekFirst();
            if (span < MIN_SPAN_MS) return 0.0;
            return breaks.size() / (span / 1000.0);
        }

        /** Rooms whose entry plans seed the rate. */
        static final int SEED_ROOMS = 6;
        /** Entry plans (shape model) of the last {@link #SEED_ROOMS} routed rooms: {chests, seconds, vein 1/0}. */
        private static final java.util.ArrayDeque<double[]> seedPlans = new java.util.ArrayDeque<>();

        /** A routed room's entry plan (shape model): its planned chests and seconds seed the rate. */
        static synchronized void notePlan(double chests, double seconds, boolean vein) {
            seedPlans.addLast(new double[]{chests, seconds, vein ? 1 : 0});
            while (seedPlans.size() > SEED_ROOMS) seedPlans.pollFirst();
        }

        /**
         * The planned-rate seed in chests per second: the last few entry plans' chests times the shape model's
         * collected share, over their seconds plus the measured room switch. 0 before any plan for this miner.
         */
        static synchronized double seedRate(boolean vein) {
            com.routerunner.lane.LegTimeModel.Shape sh = com.routerunner.lane.LegTimeModel.shape();
            double pace = com.routerunner.calib.PlayerCalibration.pace(vein);
            double sw = com.routerunner.calib.PlayerCalibration.switchS(vein);
            double y = 0, t = 0;
            for (double[] pl : seedPlans) {
                if ((pl[2] > 0.5) != vein) continue;
                y += pl[0];
                t += pace * pl[1] + sw;
            }
            return t > 0 ? sh.coverage(vein) * com.routerunner.calib.PlayerCalibration.coverage(vein) * y / t : 0.0;
        }

        /**
         * The rate that prices pruning and the bail floor. Under the shape model it is the realized rate raised to the
         * planned-rate seed, so a slow start cannot talk the planner into chasing ever smaller groups (a vault that
         * started at 19 chests/s kept planning 2-chest groups while its rooms supported 25-30; research/2026-09-28_multi).
         * Otherwise it is the realized rate.
         */
        static synchronized double lambda(boolean shape, long activeMs, boolean vein) {
            double real = rate(activeMs);
            return shape ? Math.max(real, seedRate(vein)) : real;
        }

        /** Tracked breaks at or after {@code sinceMs} inside the rate window. */
        static synchronized int breaksSince(long sinceMs) {
            int n = 0;
            for (java.util.Iterator<Long> it = breaks.descendingIterator(); it.hasNext(); ) {
                if (it.next() < sinceMs) break;
                n++;
            }
            return n;
        }

        /**
         * The bail floor in model chests per second. The shape model is in the benchmark's real seconds, so the
         * realized rate converts by the player's calibrated pace; the adaptive model is already in this player's real
         * seconds; otherwise the live ratio converts it.
         */
        static synchronized double bailFloor(boolean shape, long activeMs, boolean vein) {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            double conv = shape ? com.routerunner.calib.PlayerCalibration.pace(vein) : com.routerunner.adaptive.Adaptive.enabled() ? 1.0 : ratio();
            return Math.max(0.0, cfg.laneBailRateFrac) * lambda(shape, activeMs, vein) * conv;
        }

        static synchronized void reset() {
            breaks.clear();
            seedPlans.clear();
            plannedS = 0;
            realizedS = 0;
        }
    }
    /** Status line for the HUD. */
    private static volatile String lastState = "idle";
    private static volatile String lastLoggedState = null;
    private static long lastStateLogMs = 0;
    /** Route-accuracy score (0-100) of the last routed room, or -1. */
    private static volatile int lastAccuracyPct = -1;

    public static SolvedRoute current() { return current; }

    /**
     * Flag a position discontinuity (see {@link TeleportDetector}) on the live room so the next trail sample is
     * not treated as travel, and map the warp onto the lane route.
     */
    public static void noteTeleport(double fx, double fy, double fz, double tx, double ty, double tz) {
        SolvedRoute sr = current;
        if (sr == null) return;
        sr.tpSinceCapture = true;
        synchronized (sr.teleportMs) {
            sr.teleportMs.add(MetricsTracker.get().getActiveMs());
        }
        com.routerunner.lane.LaneRoute lr = sr.lane;
        if (lr == null) return;
        try {
            java.util.List<net.minecraft.core.BlockPos> route = new java.util.ArrayList<>();
            java.util.List<Integer> runOf = new java.util.ArrayList<>();
            for (int ri = 0; ri < lr.runs.size(); ri++) {
                for (net.minecraft.core.BlockPos p : lr.runs.get(ri).poly) {
                    route.add(p);
                    runOf.add(ri);
                }
            }
            if (route.size() < 2) return;
            double[] cum = new double[route.size()];
            for (int i = 1; i < route.size(); i++) {
                net.minecraft.core.BlockPos a = route.get(i - 1), b = route.get(i);
                cum[i] = cum[i - 1] + Math.sqrt(a.distSqr(b));
            }
            int fi = nearestIndex(route, fx, fy, fz), ti = nearestIndex(route, tx, ty, tz);
            RunLog.warp(sr.cellKey, lr.cur, lr.prog, runOf.get(fi), fi, distTo(route.get(fi), fx, fy, fz),
                    runOf.get(ti), ti, distTo(route.get(ti), tx, ty, tz), cum[ti] - cum[fi], lr.planner.P.timeModel);
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] could not map a warp onto the route in {}; this warp has no warp record.", sr.roomId, e);
        }
    }

    private static int nearestIndex(java.util.List<net.minecraft.core.BlockPos> route, double x, double y, double z) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double d = distTo(route.get(i), x, y, z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private static double distTo(net.minecraft.core.BlockPos p, double x, double y, double z) {
        double dx = p.getX() + 0.5 - x, dy = p.getY() - y, dz = p.getZ() + 0.5 - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    public static String debugState() { return lastState; }

    /** Route-accuracy score (0-100) of the last routed room scored this vault, or -1 if there isn't one. */
    public static int lastAccuracyPct() { return lastAccuracyPct; }

    /** Set the HUD status line and record a change as a {@code state} event (at most one per second). */
    private static void setState(String state) {
        lastState = state;
        if (state.equals(lastLoggedState)) return;
        long now = System.currentTimeMillis();
        if (now - lastStateLogMs < STATE_LOG_MIN_MS) return;
        lastStateLogMs = now;
        lastLoggedState = state;
        RunLog.state(state);
    }

    /** Vault entry/exit: flush the last room to the adaptive weights and diff, then clear all route and session state. */
    public static void reset() {
        if (measuredRoom != null) {
            finalizeRoomForDiff(measuredRoom);
            learnRoom(measuredRoom);
            measureRoom(measuredRoom);
        }
        lastRoomLastMs = 0;
        lastAccuracyPct = -1;
        current = null;
        solvingCell = NO_CELL;
        prefetchingCell = NO_CELL;
        prefetched = null;
        RateCal.reset();
        RoomPicker.reset();
        setState("idle");
        lastMinerLabel = null;
        hitStartMs = -1;
        pendingHallwayBlocks = 0;
        lastPlayerPos = null;
        measuredRoom = null;
        measuredCellKey = NO_CELL;
    }

    /** Per-client-tick entry point; exceptions are logged and swallowed. */
    public static void onClientTick(Level level, Player player) {
        try {
            tick(level, player);
        } catch (Throwable t) {
            LOG.error("[Routerunner] route service tick failed", t);
        }
    }

    private static void tick(Level level, Player player) {
        RouterunnerConfig cfg = RouterunnerConfig.get();

        BlockPos pp = player.blockPosition();
        int rx = Math.floorDiv(pp.getX(), RoomGeometry.CELL);
        int rz = Math.floorDiv(pp.getZ(), RoomGeometry.CELL);
        if (RoomPicker.isRoomCell(rx, rz)) RoomPicker.visit(rx, rz);

        // route the player's cell if it has target chests, else the cell they're heading into
        int chosenRx = rx, chosenRz = rz;
        int[] counts = RoomGeometry.scanCounts(level, rx, rz);
        boolean playerInRoom = counts != null && total(counts) > 0;
        if (counts != null) {
            DensityTracker.onScan(DensityTracker.cellKey(rx, rz), counts[typeIndex(resolveTargetType(cfg, counts))]);
            VaultGate.onScan(DensityTracker.cellKey(rx, rz), counts);
        }
        if (playerInRoom) {
            DensityTracker.onEnter(DensityTracker.cellKey(rx, rz));
            VaultGate.onEnter(DensityTracker.cellKey(rx, rz));
        }
        updateBailMetrics(player, playerInRoom);
        if (counts == null || total(counts) == 0) {
            int[] ahead = aheadCell(player, rx, rz);
            if (ahead[0] != rx || ahead[1] != rz) {
                int[] ac = RoomGeometry.scanCounts(level, ahead[0], ahead[1]);
                if (ac != null) {
                    DensityTracker.onScan(DensityTracker.cellKey(ahead[0], ahead[1]), ac[typeIndex(resolveTargetType(cfg, ac))]);
                    VaultGate.onScan(DensityTracker.cellKey(ahead[0], ahead[1]), ac);
                }
                if (ac != null && total(ac) > 0) {
                    chosenRx = ahead[0];
                    chosenRz = ahead[1];
                    counts = ac;
                }
            }
        } else if (total(counts) <= HALLWAY_MAX_CHESTS) {
            maybePrefetch(level, player, rx, rz, cfg);
        }

        if (counts == null) {
            setState("loading room…");
            return;
        }
        if (total(counts) == 0) {
            setState("no target chests nearby");
            return; // keep any current route showing
        }

        long cellKey = (((long) chosenRx) << 32) ^ (chosenRz & 0xFFFFFFFFL);

        String roomId = safeRoomId(chosenRx, chosenRz, player);
        if (roomId != null && isSkipped(cfg, roomId)) {
            if (RoomPicker.isRoomCell(chosenRx, chosenRz)) RoomPicker.markBanned(chosenRx, chosenRz, "skip list: " + shortRoom(roomId));
            if (current != null && current.cellKey == cellKey) current = null;
            setState("skipped (" + shortRoom(roomId) + ")");
            return;
        }

        SolvedRoute cur = current;
        if (cur != null && cur.cellKey == cellKey) {
            if (cur.roomId == null && roomId != null) {
                cur.roomId = roomId;
                RunLog.roomId(cellKey, roomId);
            }
            captureTrail(level, cur, player);
            if (cfg.adaptiveRooms) {
                maybePlanNeighbours(level, player, cur, cfg);
                maybeSwitchExit(level, player, cur, cfg);
            }
            if (cfg.routingEnabled) {
                if (cur.lane != null) {
                    laneTick(level, player, cur, cfg);
                    com.routerunner.lane.LaneRoute lr = cur.lane;
                    int nLaneRuns = lr.runs.size() - (lr.runs.get(lr.runs.size() - 1).exit ? 1 : 0);
                    String suffix = cur.exitOnly ? "challenge room, exit only" : lr.finished() ? "at exit"
                            : (lr.current().exit ? "lane → exit" : ("lane " + (lr.cur + 1) + "/" + nLaneRuns));
                    setState("route " + suffix + " [" + lr.mode + veinTag(cur) + "]" + exitTag(cur));
                } else {
                    setState("no route (" + (cur.noRouteWhy == null ? "nothing to plan" : cur.noRouteWhy) + ")" + exitTag(cur));
                }
            } else {
                setState("diff tracking [" + cur.targetType + veinTag(cur) + "]");
            }
            return;
        }

        if (solvingCell == cellKey) {
            setState("solving…");
            return;
        }
        SolvedRoute pre = prefetched;
        if (pre != null && pre.cellKey == cellKey) {
            prefetched = null;
            if (pre.targetsWorld.size() == total(counts)) {
                adoptPrefetched(pre, roomId);
                return;
            }
            LOG.info("[Routerunner] prefetched route for {} discarded: {} chests at prefetch, {} now; solving fresh.",
                    roomId == null ? "?" : shortRoom(roomId), pre.targetsWorld.size(), total(counts));
        }
        if (prefetchingCell == cellKey) {
            setState("solving…");
            return;
        }

        String targetType = resolveTargetType(cfg, counts);
        long geomStartNs = System.nanoTime();
        RoomGeometry.Snapshot snap = RoomGeometry.build(level, chosenRx, chosenRz, targetType, pp, exitChooser(level, chosenRx, chosenRz, targetType, cfg));
        final long fGeomMs = (System.nanoTime() - geomStartNs) / 1_000_000L;
        if (snap == null) {
            setState("loading room…");
            return;
        }
        if (snap.targetsLocal.isEmpty()) {
            if (current != null && current.cellKey == cellKey) current = null;
            setState("no " + targetType + " chests here");
            return;
        }
        setState("solving…");
        solvingCell = cellKey;
        logPick(snap, cellKey, "solve", cfg);
        boolean exitOnly = RoomPicker.isRoomCell(chosenRx, chosenRz) && RoomPicker.isLockingChallengeRoom(level, chosenRx, chosenRz, targetType);
        submitSolve(snap, cellKey, roomId, targetType, cfg, buildParams(true), fGeomMs, false, exitOnly);
    }

    /**
     * While the player crosses a hallway, solve the room ahead (by travel direction) in the background so its route
     * is ready the moment they step in. Skipped when the room ahead is not fully loaded yet (retried shortly),
     * has no target chests, is on the skip list, or is already current, solving or prefetched.
     */
    private static void maybePrefetch(Level level, Player player, int rx, int rz, RouterunnerConfig cfg) {
        int[] ahead = aheadCell(player, rx, rz);
        if (ahead[0] == rx && ahead[1] == rz) return;
        long key = (((long) ahead[0]) << 32) ^ (ahead[1] & 0xFFFFFFFFL);
        SolvedRoute cur = current;
        SolvedRoute pre = prefetched;
        if ((cur != null && cur.cellKey == key) || solvingCell == key || prefetchingCell == key || (pre != null && pre.cellKey == key)) return;
        long now = System.currentTimeMillis();
        if (now < prefetchRetryMs) return;
        prefetchRetryMs = now + 400;
        int[] ac = RoomGeometry.scanCounts(level, ahead[0], ahead[1]);
        if (ac == null || total(ac) <= HALLWAY_MAX_CHESTS) return;
        String roomId = safeRoomId(ahead[0], ahead[1], player);
        if (roomId != null && isSkipped(cfg, roomId)) {
            if (RoomPicker.isRoomCell(ahead[0], ahead[1])) RoomPicker.markBanned(ahead[0], ahead[1], "skip list: " + shortRoom(roomId));
            return;
        }
        String targetType = resolveTargetType(cfg, ac);
        DensityTracker.onScan(DensityTracker.cellKey(ahead[0], ahead[1]), ac[typeIndex(targetType)]);
        VaultGate.onScan(DensityTracker.cellKey(ahead[0], ahead[1]), ac);
        long geomStartNs = System.nanoTime();
        RoomGeometry.Snapshot snap = RoomGeometry.build(level, ahead[0], ahead[1], targetType, player.blockPosition(),
                exitChooser(level, ahead[0], ahead[1], targetType, cfg));
        long geomMs = (System.nanoTime() - geomStartNs) / 1_000_000L;
        if (snap == null || snap.targetsLocal.isEmpty()) return;
        prefetchingCell = key;
        logPick(snap, key, "prefetch", cfg);
        boolean exitOnly = RoomPicker.isRoomCell(ahead[0], ahead[1]) && RoomPicker.isLockingChallengeRoom(level, ahead[0], ahead[1], targetType);
        submitSolve(snap, key, roomId, targetType, cfg, buildParams(true), geomMs, true, exitOnly);
    }

    /** Make a prefetched route current now that the player has entered its cell, and log it as solved. */
    private static void adoptPrefetched(SolvedRoute pre, String roomId) {
        long now = MetricsTracker.get().getActiveMs();
        pre.prefetchLeadMs = Math.max(0, now - pre.solvedActiveMs);
        pre.startActiveMs = now;
        if (pre.roomId == null && roomId != null) pre.roomId = roomId;
        current = pre;
        notePlanned(pre);
        RunLog.roomSolve(pre);
        if (pre.lane != null) RunLog.lanePlan(pre.cellKey, pre.roomId, pre.lane.mode, "solve", pre.lane.plan.lanes.size(), pre.lane);
        LOG.info("[Routerunner] Adopted the prefetched route for {} ({} chests, ready {} ms before entry).",
                pre.roomId, pre.targetsWorld.size(), pre.prefetchLeadMs);
    }

    /** Plan a snapshot's lanes on the solver thread and publish it as current or as prefetched (a room without a lane plan shows no route). */
    private static void submitSolve(RoomGeometry.Snapshot snap, long cellKey, String roomId, String targetType, RouterunnerConfig cfg,
                                    RoutePlanner.Params params, long geomMs, boolean prefetch, boolean exitOnly) {
        final long submitNs = System.nanoTime();
        SOLVER.submit(() -> {
            try {
                long startNs = System.nanoTime();
                SolvedRoute sr = new SolvedRoute(cellKey, snap.ox, snap.oy, snap.oz, roomId, targetType, snap.targetsWorld,
                        snap.othersLocal, snap.otherIds, snap.exitLocal, snap.grid, params, snap.entranceLocal);
                sr.gateByWall = snap.gateByWall;
                sr.entranceWall = snap.entranceWall;
                sr.exitWall = snap.exitWall;
                sr.exitOnly = exitOnly;
                sr.geomMs = geomMs;
                sr.queueMs = (startNs - submitNs) / 1_000_000L;
                sr.solveMs = 0;
                long laneStart = System.nanoTime();
                sr.lane = solveLanes(sr, snap.targetsLocal, snap.entranceLocal, cfg, "solve", 0.0, !prefetch);
                sr.laneMs = (System.nanoTime() - laneStart) / 1_000_000L;
                LOG.info("[Routerunner] Lane plan for {}: {} runs in {} ms{}{}.", roomId,
                        sr.lane == null ? "no" : sr.lane.runs.size(), sr.laneMs, prefetch ? " (prefetch)" : "",
                        sr.lane != null && sr.lane.planner.isNative() ? " [rust]" : " [java]");
                long now = MetricsTracker.get().getActiveMs();
                sr.solvedActiveMs = now;
                sr.startActiveMs = now;
                if (prefetch) {
                    prefetched = sr;
                    LOG.info("[Routerunner] Prefetched {} ({} chests) in {} ms while still in the hallway.", roomId,
                            snap.targetsLocal.size(), Math.max(0, sr.laneMs));
                } else {
                    current = sr;
                    notePlanned(sr);
                    RunLog.roomSolve(sr);
                    int planned = sr.lane == null ? 0 : sr.lane.planner.regularYield(sr.lane.plan);
                    LOG.info("[Routerunner] Solved {} ({} chests, {}% planned, {} {}/{}, speed {}) in {} ms (geometry {} ms, queued {} ms){}.", roomId,
                            snap.targetsLocal.size(), snap.targetsLocal.isEmpty() ? 0 : (100 * planned / snap.targetsLocal.size()),
                            params.miner, params.chainRange, params.chainLimit, String.format(Locale.ROOT, "%.3f", params.speedAttr),
                            sr.laneMs, sr.geomMs, sr.queueMs, sr.lane == null ? "; no route shown" : "");
                }
            } catch (Throwable t) {
                LOG.error("[Routerunner] solve failed", t);
            } finally {
                if (prefetch) prefetchingCell = NO_CELL;
                else solvingCell = NO_CELL;
            }
        });
    }

    private static int countGone(Level level, SolvedRoute sr) {
        int gone = 0;
        for (BlockPos p : sr.targetsWorld) {
            if (!isTargetChestAt(level, p, sr.targetType)) gone++;
        }
        return gone;
    }

    /**
     * Solve (or re-solve) the lane plan for a room from a start cell and a chest set, on the calling thread.
     * Returns null, with an error logged, when the planner throws or finds nothing to sweep.
     */
    private static com.routerunner.lane.LaneRoute solveLanes(SolvedRoute sr, java.util.List<P> chestsLocal, P startLocal,
                                                             RouterunnerConfig cfg, String reason, double opportunityFloor, boolean log) {
        try {
            if (chestsLocal == null || chestsLocal.isEmpty() || sr.grid == null) return null;
            com.routerunner.lane.LanePlanner.Params lp = new com.routerunner.lane.LanePlanner.Params();
            lp.pointMode = true;
            lp.breakReach = sr.params.planReach;
            lp.bailAggression = cfg.laneBail;
            lp.opportunityFloor = opportunityFloor;
            lp.exitWeight = cfg.laneExitWeight;
            boolean vein = "vein".equals(sr.params.miner);
            lp.bailFloor = RateCal.bailFloor(cfg.shapeTimeModel(), MetricsTracker.get().getActiveMs(), vein);
            com.routerunner.lane.LegTimeModel model;
            if (cfg.shapeTimeModel()) {
                com.routerunner.lane.LegTimeModel.Shape sh = com.routerunner.lane.LegTimeModel.shape();
                double speed = sr.params.speedPersistent;
                if (!(speed > 0.1)) {
                    LOG.error("[Routerunner] persistent movement speed unknown for {} ({}); the shape model uses the live speed {} (sprint included).",
                            sr.roomId, speed, sr.params.speedAttr);
                    speed = sr.params.speedAttr;
                }
                model = sh.forMiner(vein, speed);
                lp.triggerS = sh.clickS(vein);
                lp.pace = 1.0;
                lp.turnaroundPenaltyS = 0.0;
                lp.timeModel = sh.name;
            } else {
                model = com.routerunner.adaptive.Adaptive.planningModel(vein);
                lp.triggerS = com.routerunner.adaptive.Adaptive.triggerS(vein);
                lp.pace = com.routerunner.adaptive.Adaptive.pace(vein);
            }
            lp.useNative = cfg.laneNative;
            if (cfg.laneNative) {
                com.routerunner.lane.NativeLane.init(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("routerunner").resolve("natives"));
                String st = com.routerunner.lane.NativeLane.status();
                if (!st.equals(nativeStatusLogged)) {
                    nativeStatusLogged = st;
                    if (com.routerunner.lane.NativeLane.ready()) LOG.info("[Routerunner] native lane planner: {}", st);
                    else LOG.warn("[Routerunner] native lane planner unavailable ({}); planning lanes in Java.", st);
                }
            }
            if (sr.exitOnly) return exitOnlyRoute(sr, chestsLocal, startLocal, lp, model, reason, log);
            Prune pr = prune(sr, chestsLocal, cfg, lp.triggerS, cfg.shapeTimeModel());
            if (pr.kept.isEmpty()) {
                LOG.info("[Routerunner] every {} chest in {} is in a group under {} (rate {}/s x {} s per break); nothing worth routing, no route shown ({}).",
                        sr.targetType, sr.roomId, String.format(Locale.ROOT, "%.1f", pr.threshold),
                        String.format(Locale.ROOT, "%.1f", pr.lambda), String.format(Locale.ROOT, "%.2f", pr.tHit), reason);
                sr.noRouteWhy = "every group too small to pay";
                return null;
            }
            java.util.List<P> planChests = pr.kept;
            SolidGrid planGrid = pr.grid;
            int[] solo = null;
            java.util.List<P> enigmas = cfg.enigmaRouting ? enigmasOf(sr) : java.util.List.of();
            if (!enigmas.isEmpty()) {
                if (cfg.enigmaValue >= pr.threshold) {
                    planChests = new java.util.ArrayList<>(pr.kept);
                    planChests.addAll(enigmas);
                    solo = new int[planChests.size()];
                    for (int i = pr.kept.size(); i < solo.length; i++) solo[i] = cfg.enigmaValue;
                    planGrid = pr.grid.withTargets(enigmas);
                    LOG.info("[Routerunner] routing {} enigma chest(s) in {} at {} chests each ({}).", enigmas.size(), sr.roomId, cfg.enigmaValue, reason);
                } else {
                    LOG.info("[Routerunner] {} enigma chest(s) in {} left out: worth {} chests, under this room's prune threshold {} ({}).",
                            enigmas.size(), sr.roomId, cfg.enigmaValue, String.format(Locale.ROOT, "%.1f", pr.threshold), reason);
                }
            }
            com.routerunner.lane.LanePlanner planner = new com.routerunner.lane.LanePlanner(
                    planGrid, planChests, solo, sr.params.chainRange, sr.params.chainLimit, lp, model);
            P start = com.routerunner.lane.Grid.snapInside(pr.grid, startLocal == null ? sr.exitLocal : startLocal);
            P exit = com.routerunner.lane.Grid.snapInside(pr.grid, sr.exitLocal);
            com.routerunner.lane.LanePlanner.Plan plan = planner.plan(start, exit);
            if (plan.lanes.isEmpty()) {
                LOG.error("[Routerunner] lane planner found nothing to sweep in {} ({}); no route shown.", sr.roomId, reason);
                sr.noRouteWhy = "nothing reachable to sweep";
                return null;
            }
            logExitFallback(plan, sr.roomId, reason);
            com.routerunner.lane.LaneRoute lr = new com.routerunner.lane.LaneRoute(planner, plan, lp.pointMode ? "point" : "corridor",
                    sr.ox, sr.oy, sr.oz, MetricsTracker.get().getActiveMs());
            lr.setPrune(pr.threshold, pr.lambda, pr.tHit, pr.groups, pr.chests);
            if (log) RunLog.lanePlan(sr.cellKey, sr.roomId, lr.mode, reason, plan.lanes.size(), lr);
            return lr;
        } catch (Throwable t) {
            LOG.error("[Routerunner] lane planner failed for {} ({}); no route shown.", sr.roomId, reason, t);
            sr.noRouteWhy = "planner error";
            return null;
        }
    }

    /**
     * A locking challenge room (its controller keeps the room unbreakable): no lanes, only the walk from the player to
     * the exit door the picker chose. Null (no route) when the planner finds no way out at all.
     */
    private static com.routerunner.lane.LaneRoute exitOnlyRoute(SolvedRoute sr, java.util.List<P> chestsLocal, P startLocal,
                                                                com.routerunner.lane.LanePlanner.Params lp,
                                                                com.routerunner.lane.LegTimeModel model, String reason, boolean log) {
        com.routerunner.lane.LanePlanner planner = new com.routerunner.lane.LanePlanner(
                sr.grid, chestsLocal, sr.params.chainRange, sr.params.chainLimit, lp, model);
        P start = com.routerunner.lane.Grid.snapInside(sr.grid, startLocal == null ? sr.exitLocal : startLocal);
        P exit = com.routerunner.lane.Grid.snapInside(sr.grid, sr.exitLocal);
        com.routerunner.lane.LanePlanner.Plan plan = planner.plan(start, exit, new boolean[chestsLocal.size()]);
        if (plan.exitPath == null || plan.exitPath.size() < 2) {
            LOG.warn("[Routerunner] {} is a locking challenge room and no way to its exit was found ({}); no route shown.", sr.roomId, reason);
            sr.noRouteWhy = "challenge room, no way out found";
            return null;
        }
        logExitFallback(plan, sr.roomId, reason);
        com.routerunner.lane.LaneRoute lr = new com.routerunner.lane.LaneRoute(planner, plan, lp.pointMode ? "point" : "corridor",
                sr.ox, sr.oy, sr.oz, MetricsTracker.get().getActiveMs());
        if (log) RunLog.lanePlan(sr.cellKey, sr.roomId, lr.mode, reason + "-exit-only", 0, lr);
        LOG.info("[Routerunner] {} is a locking challenge room; routing only the way out ({}).", sr.roomId, reason);
        return lr;
    }

    /** Warn when a plan's exit needed the take-off hop safety net or, failing that, the straight line. */
    private static void logExitFallback(com.routerunner.lane.LanePlanner.Plan plan, String roomId, String reason) {
        if (plan.exitStraight) {
            LOG.warn("[Routerunner] no walk, flight or take-off hop reaches the exit of {} ({}); the exit run is a straight line through whatever is in the way.",
                    roomId, reason);
        } else if (plan.exitHop) {
            LOG.warn("[Routerunner] the exit of {} is not walkable from the last lane ({}); exit safety net: walk to a take-off and warp out.",
                    roomId, reason);
        }
    }

    /** The room's standing enigma chests at snapshot time (room-local, inside its grid). */
    private static java.util.List<P> enigmasOf(SolvedRoute sr) {
        java.util.List<P> out = new java.util.ArrayList<>();
        if (sr.otherChestsLocal == null || sr.otherChestIds == null || sr.grid == null) return out;
        for (int i = 0; i < Math.min(sr.otherChestsLocal.size(), sr.otherChestIds.size()); i++) {
            if (!ChestScanner.ENIGMA_ID.equals(sr.otherChestIds.get(i))) continue;
            P c = sr.otherChestsLocal.get(i);
            if (c.x() >= 0 && c.y() >= 0 && c.z() >= 0 && c.x() < sr.grid.sx && c.y() < sr.grid.sy && c.z() < sr.grid.sz) out.add(c);
        }
        return out;
    }

    /** What {@link #prune} kept and dropped, and the numbers behind its threshold. */
    private record Prune(java.util.List<P> kept, SolidGrid grid, double threshold, double lambda, double tHit, int groups, int chests) {}

    /**
     * Drop the chest groups too small to repay a break: a group (the chests one unlimited break could reach, i.e.
     * touching chests for Vein Miner, chests within the chain range for Chain Miner) smaller than the running
     * realized rate (chests per second, last two minutes) times the seconds one break costs is not worth its click
     * (vein run 1: the optimum sat at that product, about 15 chests, worth +3 %). Dropped chests stay solid in the
     * returned grid, so the planner walks and sees around them. No pruning until the rate is known, below a
     * threshold of 2 (it could only drop single chests), or with {@code lanePrune} off. Under the shape model the
     * rate is seeded by recent plans ({@link RateCal#lambda}), and a Vein Miner room whose clumpiness (the size of the
     * touching group the average chest sits in) is under the model's {@code sparseClumpBelow} prices a click at least
     * {@code sparseTapS}: scattered small groups cost more to aim at than the fitted click (research/2026-09-28_multi).
     */
    private static Prune prune(SolvedRoute sr, java.util.List<P> chests, RouterunnerConfig cfg, double tHit, boolean shape) {
        boolean vein = "vein".equals(sr.params.miner);
        double lambda = RateCal.lambda(shape, MetricsTracker.get().getActiveMs(), vein);
        int[][] comps = null;
        if (shape && vein && !chests.isEmpty()) {
            comps = com.routerunner.solver.ChainModel.components(Math.max(1, sr.params.chainRange), chests);
            double sum = 0;
            for (int i = 0; i < chests.size(); i++) sum += comps[1][comps[0][i]];
            tHit = com.routerunner.lane.LegTimeModel.shape().pruneTapS(true, sum / chests.size(), tHit);
        }
        if (shape) tHit *= com.routerunner.calib.PlayerCalibration.pace(vein);
        double threshold = lambda * tHit;
        if (!cfg.lanePrune || !(threshold >= 2.0)) return new Prune(chests, sr.grid, threshold, lambda, tHit, 0, 0);
        if (comps == null) comps = com.routerunner.solver.ChainModel.components(Math.max(1, sr.params.chainRange), chests);
        java.util.List<P> kept = new java.util.ArrayList<>(chests.size());
        java.util.List<P> dropped = new java.util.ArrayList<>();
        int groups = 0;
        for (int i = 0; i < chests.size(); i++) {
            int size = comps[1][comps[0][i]];
            if (size >= threshold) {
                kept.add(chests.get(i));
            } else {
                dropped.add(chests.get(i));
                if (comps[0][i] == i) groups++;
            }
        }
        if (dropped.isEmpty()) return new Prune(chests, sr.grid, threshold, lambda, tHit, 0, 0);
        return new Prune(kept, sr.grid.withoutTargets(dropped), threshold, lambda, tHit, groups, dropped.size());
    }

    /**
     * Replan on the room's existing planner (its reach, component, landing and exit caches survive) over the chests
     * still standing, from the player's cell, keeping the first solve's opportunity as the bail anchor. A plan with
     * no lanes left is still returned when it has an exit walk, so the player is led out rather than left blank.
     */
    private static com.routerunner.lane.LaneRoute replanLanes(SolvedRoute sr, com.routerunner.lane.LaneRoute old, boolean[] mask,
                                                              P startLocal, RouterunnerConfig cfg, String reason) {
        try {
            if (sr.exitOnly) mask = new boolean[mask.length];
            com.routerunner.lane.LanePlanner planner = old.planner;
            planner.P.opportunityFloor = old.plan.opportunity;
            planner.P.bailAggression = cfg.laneBail;
            planner.P.exitWeight = cfg.laneExitWeight;
            planner.P.bailFloor = RateCal.bailFloor(planner.P.timeModel != null && planner.P.timeModel.startsWith("shape"), MetricsTracker.get().getActiveMs(), "vein".equals(sr.params.miner));
            P start = com.routerunner.lane.Grid.snapInside(planner.grid, startLocal == null ? sr.exitLocal : startLocal);
            P exit = com.routerunner.lane.Grid.snapInside(planner.grid, sr.exitLocal);
            com.routerunner.lane.LanePlanner.Plan plan = planner.plan(start, exit, mask);
            if (plan.lanes.isEmpty() && (plan.exitPath == null || plan.exitPath.isEmpty())) {
                LOG.error("[Routerunner] lane replan found nothing to sweep and no exit walk in {} ({}); keeping the old plan.", sr.roomId, reason);
                return null;
            }
            logExitFallback(plan, sr.roomId, reason);
            com.routerunner.lane.LaneRoute lr = new com.routerunner.lane.LaneRoute(planner, plan, old.mode, sr.ox, sr.oy, sr.oz,
                    MetricsTracker.get().getActiveMs());
            lr.copyPrune(old);
            RunLog.lanePlan(sr.cellKey, sr.roomId, lr.mode, reason, plan.lanes.size(), lr);
            return lr;
        } catch (Throwable t) {
            LOG.error("[Routerunner] lane replan failed for {} ({}); keeping the old plan.", sr.roomId, reason, t);
            return null;
        }
    }

    /** The lane follow rules for one client tick, plus heat refresh, tracer scheduling, and boundary/off-lane replans. */
    private static void laneTick(Level level, Player player, SolvedRoute sr, RouterunnerConfig cfg) {
        com.routerunner.lane.LaneRoute lr = sr.lane;
        if (lr == null) return;
        java.util.function.Predicate<BlockPos> alive = pos -> isTargetChestAt(level, pos, sr.targetType);
        long now = MetricsTracker.get().getActiveMs();
        if (lr.heatDue(now)) lr.recomputeHeat(alive, now);
        if (lr.finished()) return;
        com.routerunner.lane.LaneRoute.Event ev = lr.tick(player, alive, now);
        if (lr.tracerDue(now)) {
            Vec3 pp = player.position();
            P pl = new P((int) Math.floor(pp.x) - sr.ox, (int) Math.floor(pp.y) - sr.oy, (int) Math.floor(pp.z) - sr.oz);
            lr.tracerBusy = true;
            lr.tracerMs = now;
            SOLVER.submit(() -> {
                try {
                    lr.computeTracer(pl);
                } catch (Throwable t) {
                    LOG.error("[Routerunner] lane tracer failed for {}; the off-lane guide line stays off until the next attempt.", sr.roomId, t);
                } finally {
                    lr.tracerBusy = false;
                }
            });
        }
        if (ev == com.routerunner.lane.LaneRoute.Event.NONE) return;
        com.routerunner.lane.LaneRoute.Run r = lr.current();
        int ahead = r == null ? 0 : lr.aliveAhead(r, lr.prog, alive);
        if (ev == com.routerunner.lane.LaneRoute.Event.DONE) {
            RunLog.laneEvent(sr.cellKey, "done", lr.cur, lr.runs.size(), lr.lastDone, ahead, lr.prog);
            if (lr.engaged && r != null && !r.exit) {
                double realized = (now - lr.runStartMs) / 1000.0;
                if (lr.planner.P.learnedTimeModel()) {
                    if (realized > 0.5 && r.seconds > 0.05) RateCal.observe(r.seconds, realized);
                    com.routerunner.adaptive.Adaptive.onRunDone(r.travelS, lr.planner.P.pace, r.nTrig, r.penaltyS, realized,
                            RateCal.breaksSince(lr.runStartMs), "vein".equals(sr.params.miner));
                }
            }
            lr.advance(player, now);
            com.routerunner.lane.LaneRoute.Run nx = lr.current();
            if (nx == null) {
                RunLog.laneEvent(sr.cellKey, "finished", lr.cur, lr.runs.size(), "plan", 0, 0);
                return;
            }
            if (!nx.exit && lr.stale(nx, alive)) laneReplan(level, player, sr, cfg, "stale");
            else RunLog.laneEvent(sr.cellKey, "advance", lr.cur, lr.runs.size(), nx.exit ? "exit" : "next", lr.aliveTotal(nx, alive), lr.prog);
        } else {
            RunLog.laneEvent(sr.cellKey, "off", lr.cur, lr.runs.size(), "off-lane", ahead, lr.prog);
            laneReplan(level, player, sr, cfg, "off");
        }
    }

    /** Replan the lanes from the player's position over the chests still standing, rate-limited, on the solver thread. */
    private static void laneReplan(Level level, Player player, SolvedRoute sr, RouterunnerConfig cfg, String reason) {
        com.routerunner.lane.LaneRoute lr = sr.lane;
        long now = MetricsTracker.get().getActiveMs();
        if (lr == null || sr.laneSolving || now - lr.lastReplanMs < com.routerunner.lane.LaneRoute.REPLAN_MIN_MS) {
            RunLog.laneEvent(sr.cellKey, "replan-skipped", lr == null ? -1 : lr.cur, lr == null ? 0 : lr.runs.size(),
                    sr.laneSolving ? "in-flight" : "rate-limit", 0, lr == null ? 0 : lr.prog);
            return;
        }
        boolean[] mask = lr.aliveMask(pos -> isTargetChestAt(level, pos, sr.targetType));
        int liveN = 0;
        for (boolean b : mask) if (b) liveN++;
        Vec3 pp = player.position();
        P startLocal = new P((int) Math.floor(pp.x) - sr.ox, (int) Math.floor(pp.y) - sr.oy, (int) Math.floor(pp.z) - sr.oz);
        sr.laneSolving = true;
        lr.lastReplanMs = now;
        RunLog.laneEvent(sr.cellKey, "replan", lr.cur, lr.runs.size(), reason, liveN, lr.prog);
        SOLVER.submit(() -> {
            try {
                long t0 = System.nanoTime();
                com.routerunner.lane.LaneRoute fresh = replanLanes(sr, lr, mask, startLocal, cfg, reason);
                LOG.info("[Routerunner] Lane replan ({}) for {}: {} runs in {} ms.", reason, sr.roomId,
                        fresh == null ? "no" : fresh.runs.size(), (System.nanoTime() - t0) / 1_000_000L);
                if (fresh != null) {
                    fresh.lastReplanMs = now;
                    if (current == sr) sr.lane = fresh;
                }
            } finally {
                sr.laneSolving = false;
            }
        });
    }

    private static boolean isTargetChestAt(Level level, BlockPos pos, String targetType) {
        if (!level.isLoaded(pos)) return true; // unloaded counts as present
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        if (id == null) return false;
        String s = id.toString();
        return (s.contains(targetType) && !s.contains("strongbox")) || s.equals(ChestScanner.ENIGMA_ID);
    }

    private static BlockPos world(SolvedRoute sr, P local) {
        return new BlockPos(sr.ox + local.x(), sr.oy + local.y(), sr.oz + local.z());
    }

    /** Index of a chest type in {@link RoomGeometry#TYPES}, 0 when unknown. */
    private static int typeIndex(String type) {
        for (int i = 0; i < RoomGeometry.TYPES.length; i++) if (RoomGeometry.TYPES[i].equals(type)) return i;
        return 0;
    }

    private static String resolveTargetType(RouterunnerConfig cfg, int[] counts) {
        switch (cfg.trackedChest) {
            case GILDED: return "gilded";
            case ORNATE: return "ornate";
            case LIVING: return "living";
            case WOODEN: return "wooden";
            default: break;
        }
        String resolved = LootListener.get().getResolvedType();
        if (resolved != null) return resolved;
        int best = 0;
        for (int i = 1; i < counts.length; i++) if (counts[i] > counts[best]) best = i;
        return RoomGeometry.TYPES[best];
    }

    /**
     * The planner's per-room settings: the fixed weights logged with every room, the mining ability (Chain or Vein
     * Miner and its tier), the break reach and the player's speed.
     * With {@code logMiner} (solves only), the first snapshot of a vault and every one whose mining ability differs
     * from the last writes a {@code miner} record.
     */
    private static RoutePlanner.Params buildParams(boolean logMiner) {
        RoutePlanner.Params p = new RoutePlanner.Params();
        ChainMinerInfo.Miner m = ChainMinerInfo.current();
        p.chainRange = m.range();
        p.chainLimit = m.limit();
        p.miner = m.mode();
        p.minerSpec = m.spec();
        p.minerTier = m.tier();
        p.speedAttr = PlayerSpeed.attribute(Minecraft.getInstance().player);
        p.speedPersistent = PlayerSpeed.persistentAttribute(Minecraft.getInstance().player);
        double[] reach = PlayerReach.read(Minecraft.getInstance().player);
        p.reach = reach[0];
        double[] used = com.routerunner.adaptive.Adaptive.learnedReach();
        p.usedReach = used[0];
        p.reachHits = (int) used[1];
        p.planReach = PlayerReach.plan(reach[0], used[0]);
        String label = m.spec() + "|" + m.label() + "|" + String.format(Locale.ROOT, "%.2f|%.1f", p.reach, p.planReach);
        if (logMiner && !label.equals(lastMinerLabel)) {
            boolean first = lastMinerLabel == null;
            lastMinerLabel = label;
            LOG.info("[Routerunner] mining ability: {} [{}], reach {} (forge {}, capped attribute {}), your hits reach {} ({} hits), planning at {}{}",
                    m.label(), m.spec(), String.format(Locale.ROOT, "%.2f", reach[0]), String.format(Locale.ROOT, "%.2f", reach[1]),
                    String.format(Locale.ROOT, "%.2f", reach[2]), String.format(Locale.ROOT, "%.2f", used[0]), (int) used[1],
                    String.format(Locale.ROOT, "%.2f", p.planReach), first ? "" : " (changed)");
            RunLog.miner(m, first ? "first" : "changed", reach, used, p.planReach);
        }
        return p;
    }

    /** The mining ability last written to the log this vault ({@code spec|label}); null until the first solve. */
    private static volatile String lastMinerLabel = null;

    /** The parameter snapshot a solve would run with right now. */
    public static RoutePlanner.Params snapshotParams() {
        return buildParams(false);
    }

    /**
     * A tracked chest broke: count it for the running rate and the break reach and, if it is one of the current
     * room's targets, append it to {@link SolvedRoute#userBreaks}.
     */
    public static void onChestBroken(BlockPos pos) {
        RateCal.onBreak(MetricsTracker.get().getActiveMs());
        if (pos != null) DensityTracker.onBreak(pos);
        if (pos != null) noteHit(pos);
        SolvedRoute lsr = current;
        if (lsr != null && lsr.lane != null) lsr.lane.heatDirty = true;
        SolvedRoute sr = current;
        if (sr == null || pos == null) return;
        try {
            if (sr.targetSet == null) sr.targetSet = new java.util.HashSet<>(sr.targetsWorld);
            if (!sr.targetSet.contains(pos)) return;
            sr.userBreaks.add(new double[]{
                    MetricsTracker.get().getActiveMs(),
                    pos.getX() - sr.ox, pos.getY() - sr.oy, pos.getZ() - sr.oz});
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] failed to record a chest break for {}; its break count will be short.", sr.roomId, e);
        }
    }

    /** Horizontal player travel outside rooms since the last room change (blocks). */
    private static double pendingHallwayBlocks = 0;
    private static Vec3 lastPlayerPos = null;
    private static SolvedRoute measuredRoom = null;
    private static long measuredCellKey = NO_CELL;

    /** Accumulate hallway travel; on a room change, finalize the previous room for the diff and the adaptive model. */
    private static void updateBailMetrics(Player player, boolean playerInRoom) {
        Vec3 cur = player.position();
        if (lastPlayerPos != null) {
            double step = Math.hypot(cur.x - lastPlayerPos.x, cur.z - lastPlayerPos.z);
            if (step < 20.0 && !playerInRoom) pendingHallwayBlocks += step;
        }
        lastPlayerPos = cur;

        SolvedRoute crt = current;
        if (crt != null && crt.cellKey != measuredCellKey) {
            if (measuredRoom != null) {
                finalizeRoomForDiff(measuredRoom);
                learnRoom(measuredRoom);
                measureRoom(measuredRoom);
            }
            crt.hallwayIn = pendingHallwayBlocks;
            pendingHallwayBlocks = 0;
            measuredRoom = crt;
            measuredCellKey = crt.cellKey;
        }
    }

    /**
     * Sample the player into the room's trail at up to 10 Hz, skipping moves under 0.25 blocks, and track
     * chests cleared. Each sample is {@code {lx, ly, lz, activeMs, yaw, pitch, teleportFlag}}.
     */
    private static void captureTrail(Level level, SolvedRoute sr, Player player) {
        long ms = MetricsTracker.get().getActiveMs();
        if (sr.ticks.isEmpty() || sr.ticks.get(sr.ticks.size() - 1)[0] < ms) {
            sr.ticks.add(new double[]{ms, player.getX(), player.getZ(), player.getYRot(), player.getXRot(), player.isOnGround() ? 1.0 : 0.0});
        }
        if (ms - sr.lastCaptureMs < 100) return;
        sr.lastCaptureMs = ms;
        long wall = System.currentTimeMillis();
        if (sr.userFirstMs == 0) sr.userFirstMs = ms;
        if (sr.firstTs == 0) sr.firstTs = wall;
        sr.userLastMs = ms;
        sr.lastTs = wall;
        Vec3 p = player.position();
        double lx = p.x - sr.ox, ly = p.y - sr.oy, lz = p.z - sr.oz;
        if (!sr.haveTrail || Math.hypot(lx - sr.lastTrailX, lz - sr.lastTrailZ) >= 0.25) {
            sr.userTrail.add(new double[]{lx, ly, lz, ms, player.getYRot(), player.getXRot(), sr.tpSinceCapture ? 1.0 : 0.0});
            sr.tpSinceCapture = false;
            sr.lastTrailX = lx; sr.lastTrailZ = lz; sr.haveTrail = true;
        }
        int gone = countGone(level, sr);
        if (gone > sr.userChests) sr.userChests = gone;
    }

    /** {@code " vein"} for the HUD state when the room was planned for Vein Miner, else empty. */
    private static String veinTag(SolvedRoute sr) {
        return sr.params != null && "vein".equals(sr.params.miner) ? " vein" : "";
    }

    /** Breaks within this of the first break of a hit belong to that hit (one click reaches the client over a few ticks). */
    private static final long HIT_MERGE_MS = 250;
    private static long hitStartMs = -1;
    private static double hitMin = Double.MAX_VALUE;

    /**
     * Group breaks into hits and hand each finished hit's distance (the player's feet cell to the nearest chest the hit
     * broke, the planner's reach metric) to the adaptive break reach. The clicked chest is the nearest one.
     */
    private static void noteHit(BlockPos pos) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        long now = System.currentTimeMillis();
        if (hitStartMs < 0 || now - hitStartMs > HIT_MERGE_MS) {
            if (hitStartMs >= 0) com.routerunner.adaptive.Adaptive.onHit(hitMin);
            hitStartMs = now;
            hitMin = Double.MAX_VALUE;
        }
        BlockPos feet = player.blockPosition();
        double dx = pos.getX() - feet.getX(), dy = pos.getY() - feet.getY(), dz = pos.getZ() - feet.getZ();
        hitMin = Math.min(hitMin, Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /** Active time the previous measured room's trail ended (ms), for the room switch; 0 at vault start. */
    private static long lastRoomLastMs = 0;
    /** Mean trail distance from the drawn route (blocks) under which a room counts as followed, per miner (chain, vein). */
    static final double FOLLOW_CHAIN = 2.5, FOLLOW_VEIN = 5.0;

    /**
     * Hand the room the player just left to the player calibration and the lap recorder: copies are taken here, the
     * trail is priced (and, when the room had no shape plan, a benchmark shape plan made from where the player entered)
     * on the calibration thread.
     */
    private static void measureRoom(SolvedRoute room) {
        try {
            long prevLast = lastRoomLastMs;
            if (room.userLastMs > 0) lastRoomLastMs = room.userLastMs;
            if (room.grid == null || room.userBreaks.isEmpty() || room.userTrail.size() < 2 || room.userLastMs <= room.userFirstMs) return;
            boolean vein = "vein".equals(room.params.miner);
            double speed = room.params.speedPersistent > 0.1 ? room.params.speedPersistent : room.params.speedAttr;
            java.util.List<double[]> trail = new java.util.ArrayList<>(room.userTrail);
            java.util.List<double[]> breaks = new java.util.ArrayList<>(room.userBreaks);
            java.util.List<double[]> ticks = new java.util.ArrayList<>(room.ticks);
            java.util.List<P> chests = new java.util.ArrayList<>(room.targetsWorld.size());
            for (BlockPos b : room.targetsWorld) chests.add(new P(b.getX() - room.ox, b.getY() - room.oy, b.getZ() - room.oz));
            double sec = (room.userLastMs - room.userFirstMs) / 1000.0;
            double sw = prevLast > 0 && room.userFirstMs > prevLast ? (room.userFirstMs - prevLast) / 1000.0 : 0.0;
            boolean followed = RouterunnerConfig.get().routingEnabled && !Double.isNaN(room.followAvgOff)
                    && room.followAvgOff <= (vein ? FOLLOW_VEIN : FOLLOW_CHAIN);
            int lap = MetricsTracker.get().getLap();
            double entryYield = room.entryYield, entryS = room.entryS;
            com.routerunner.calib.PlayerCalibration.submit(() -> {
                double y = entryYield, t = entryS;
                if (!(y > 0)) {
                    double[] bp = benchmarkPlan(room, chests, trail.get(0), vein, speed);
                    if (bp != null) {
                        y = bp[0];
                        t = bp[1];
                    }
                }
                com.routerunner.calib.PlayerCalibration.Room m = com.routerunner.calib.RoomMeasure.measure(room.grid, vein, speed, trail, breaks,
                        ticks, chests, sec, y, t, followed, sw);
                com.routerunner.calib.LapRecorder.onRoom(lap, m);
                com.routerunner.calib.PlayerCalibration.observe(m);
                RunLog.playerCalib(room.cellKey, m, com.routerunner.calib.PlayerCalibration.benchmarkSeconds(vein, m.pricedS(), m.clump()),
                        com.routerunner.calib.PlayerCalibration.pace(vein));
            });
        } catch (Throwable t) {
            LOG.error("[Routerunner] could not measure {} for the player calibration; it is missing from the lap chart.", room.roomId, t);
        }
    }

    /**
     * The shape plan the benchmark would have been shown in a room that had none (another time model, or no route):
     * planned from the player's first trail point at their speed, pruned at their rate, on the calling thread.
     * {chests, seconds}, or null (logged) when nothing plans.
     */
    private static double[] benchmarkPlan(SolvedRoute room, java.util.List<P> chests, double[] first, boolean vein, double speed) {
        try {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            com.routerunner.lane.LegTimeModel.Shape sh = com.routerunner.lane.LegTimeModel.shape();
            com.routerunner.lane.LanePlanner.Params lp = new com.routerunner.lane.LanePlanner.Params();
            lp.pointMode = true;
            lp.breakReach = room.params.planReach;
            lp.bailAggression = cfg.laneBail;
            lp.exitWeight = cfg.laneExitWeight;
            lp.bailFloor = RateCal.bailFloor(true, MetricsTracker.get().getActiveMs(), vein);
            lp.triggerS = sh.clickS(vein);
            lp.pace = 1.0;
            lp.turnaroundPenaltyS = 0.0;
            lp.timeModel = sh.name;
            lp.useNative = cfg.laneNative;
            Prune pr = prune(room, chests, cfg, lp.triggerS, true);
            if (pr.kept.isEmpty()) return null;
            com.routerunner.lane.LanePlanner planner = new com.routerunner.lane.LanePlanner(pr.grid, pr.kept, room.params.chainRange,
                    room.params.chainLimit, lp, sh.forMiner(vein, speed));
            P start = com.routerunner.lane.Grid.snapInside(pr.grid, new P((int) Math.floor(first[0]), (int) Math.floor(first[1]), (int) Math.floor(first[2])));
            P exit = com.routerunner.lane.Grid.snapInside(pr.grid, room.exitLocal);
            com.routerunner.lane.LanePlanner.Plan plan = planner.plan(start, exit);
            if (plan.lanes.isEmpty()) return null;
            return new double[]{plan.yieldTotal, plan.tTotal};
        } catch (Throwable t) {
            LOG.error("[Routerunner] benchmark plan failed for {}; the room counts for the calibration but not for the lap predictions.", room.roomId, t);
            return null;
        }
    }

    /** Hand the room the player just left to the adaptive leg model (private copies; it learns on its own thread). */
    private static void learnRoom(SolvedRoute room) {
        try {
            if (room.grid == null || room.userBreaks.size() < 2 || room.userTrail.size() < 2) return;
            java.util.List<P> chests = new java.util.ArrayList<>(room.targetsWorld.size());
            for (BlockPos b : room.targetsWorld) chests.add(new P(b.getX() - room.ox, b.getY() - room.oy, b.getZ() - room.oz));
            java.util.List<Long> tps;
            synchronized (room.teleportMs) {
                tps = new java.util.ArrayList<>(room.teleportMs);
            }
            com.routerunner.adaptive.Adaptive.onRoomExit(room.cellKey, room.grid, chests, new java.util.ArrayList<>(room.userTrail),
                    new java.util.ArrayList<>(room.userBreaks), tps);
        } catch (Throwable t) {
            LOG.error("[Routerunner] could not hand {} to the adaptive leg model; its legs are not learned.", room.roomId, t);
        }
    }

    /**
     * Score the player's trail against the room's lane plan (its drawn path, planned chests and click order) and log
     * one diff record for the room. A room without a lane plan still gets its record, with the plan-side fields empty.
     */
    private static void finalizeRoomForDiff(SolvedRoute room) {
        try {
            if (room.grid == null || room.userTrail.size() < 3 || room.userChests <= 0) {
                LOG.info("[Routerunner] diff skipped for {} (trail {}, chests {})",
                        room.roomId, room.userTrail.size(), room.userChests);
                return;
            }
            RoutePlanner.Params pm = room.params;
            com.routerunner.lane.LaneRoute lr = room.lane;
            java.util.List<P> path = lr == null ? java.util.List.of() : lr.plannedPathLocal();
            double[] user = RoutePlanner.scoreTrajectory(room.userTrail, room.grid, pm);
            double[] solver = lr == null ? null : scoreWalk(path, room.grid, pm);
            int solverChests = lr == null ? 0 : lr.planner.regularYield(lr.plan);
            double sec = Math.max(0.001, (room.userLastMs - room.userFirstMs) / 1000.0);
            double[] follow = path.size() < 2 ? null : followDeviation(room.userTrail, path);
            if (follow != null) room.followAvgOff = follow[0];
            boolean routing = RouterunnerConfig.get().routingEnabled;
            double[] accuracy = lr == null || follow == null ? null : routeAccuracy(room, lr.plannedClicksLocal(), follow);
            if ("vein".equals(pm.miner)) room.bigGroups = bigGroups(room, com.routerunner.lane.LaneRoute.PRIORITY_MIN);
            RunLog.roomDiff(room, MetricsTracker.get().getLap(), routing, sec, user, solver, solverChests, lr == null ? 0 : walkLength(path),
                    follow, accuracy);
            if (lr == null) {
                LOG.info("[Routerunner] diff {}: you {} ch, no lane plan to compare with", shortRoom(room.roomId == null ? "?" : room.roomId), room.userChests);
                return;
            }
            if (accuracy == null) {
                LOG.info("[Routerunner] accuracy {}: not scored — fewer than 4 planned clicks matched your {} breaks",
                        shortRoom(room.roomId == null ? "?" : room.roomId), room.userBreaks.size());
            } else {
                if (routing) lastAccuracyPct = (int) Math.round(100.0 * accuracy[5]);
                LOG.info("[Routerunner] accuracy {}: n {} rho {} vs nearest-neighbour {} (lift {}), spatial {} → score {}",
                        shortRoom(room.roomId == null ? "?" : room.roomId), (int) accuracy[0],
                        String.format(Locale.ROOT, "%.3f", accuracy[1]),
                        String.format(Locale.ROOT, "%.3f", accuracy[2]),
                        String.format(Locale.ROOT, "%.3f", accuracy[3]),
                        String.format(Locale.ROOT, "%.3f", accuracy[4]),
                        String.format(Locale.ROOT, "%.3f", accuracy[5]));
            }
            LOG.info("[Routerunner] diff {}: you {} ch @ {}/chest vs solver {} ch @ {}/chest",
                    shortRoom(room.roomId == null ? "?" : room.roomId), room.userChests,
                    String.format(Locale.ROOT, "%.2f", room.userChests > 0 ? user[4] / room.userChests : 0),
                    solverChests,
                    String.format(Locale.ROOT, "%.2f", solverChests > 0 ? solver[4] / solverChests : 0));
        } catch (Throwable t) {
            LOG.error("[Routerunner] diff computation failed for {}", room.roomId, t);
        }
    }

    /**
     * Vein Miner's big touching groups in a finished room (all its target chests at solve time, groups of at least
     * {@code minSize}): {groups, hit, missed, missed chests, minSize}. A group is hit when any of its chests is among
     * the player's breaks, and missed when it was not hit although the player's eye came within their block reach
     * of one of its chests (walls not checked). What deep-blue priority breaks are meant to bring down.
     */
    private static int[] bigGroups(SolvedRoute room, int minSize) {
        java.util.List<P> chests = new java.util.ArrayList<>(room.targetsWorld.size());
        for (BlockPos b : room.targetsWorld) chests.add(new P(b.getX() - room.ox, b.getY() - room.oy, b.getZ() - room.oz));
        int[][] comps = com.routerunner.solver.ChainModel.components(1, chests);
        java.util.Set<Long> broken = new java.util.HashSet<>();
        for (double[] b : room.userBreaks) broken.add(pack((int) Math.round(b[1]), (int) Math.round(b[2]), (int) Math.round(b[3])));
        java.util.Map<Integer, java.util.List<P>> members = new java.util.HashMap<>();
        for (int i = 0; i < chests.size(); i++) {
            if (comps[1][comps[0][i]] >= minSize) members.computeIfAbsent(comps[0][i], k -> new java.util.ArrayList<>()).add(chests.get(i));
        }
        double reach = room.params.reach;
        double r2 = reach * reach;
        int hit = 0, missed = 0, missedChests = 0;
        for (java.util.List<P> g : members.values()) {
            boolean isHit = false;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (P c : g) {
                if (broken.contains(pack(c.x(), c.y(), c.z()))) isHit = true;
                x0 = Math.min(x0, c.x()); y0 = Math.min(y0, c.y()); z0 = Math.min(z0, c.z());
                x1 = Math.max(x1, c.x()); y1 = Math.max(y1, c.y()); z1 = Math.max(z1, c.z());
            }
            if (isHit) { hit++; continue; }
            boolean near = false;
            for (int t = 0; t < room.userTrail.size() && !near; t += 2) {
                double[] s = room.userTrail.get(t);
                double ex = s[0], ey = s[1] + 1.62, ez = s[2];
                double bx = Math.max(x0 - ex, Math.max(0, ex - (x1 + 1))), by = Math.max(y0 - ey, Math.max(0, ey - (y1 + 1))),
                        bz = Math.max(z0 - ez, Math.max(0, ez - (z1 + 1)));
                if (bx * bx + by * by + bz * bz > r2) continue;
                for (P c : g) {
                    double dx = c.x() + 0.5 - ex, dy = c.y() + 0.5 - ey, dz = c.z() + 0.5 - ez;
                    if (dx * dx + dy * dy + dz * dz <= r2) { near = true; break; }
                }
            }
            if (near) { missed++; missedChests += g.size(); }
        }
        return new int[]{members.size(), hit, missed, missedChests, minSize};
    }

    private static long pack(int x, int y, int z) {
        return (((long) x) << 40) | (((long) (y + 512)) << 20) | (long) (z + 512);
    }

    /** {@link RoutePlanner#scoreTrajectory} over a planned path (room-local cells). */
    private static double[] scoreWalk(java.util.List<P> path, SolidGrid grid, RoutePlanner.Params pm) {
        java.util.List<double[]> pts = new java.util.ArrayList<>(path.size());
        for (P p : path) pts.add(new double[]{p.x(), p.y(), p.z()});
        return pts.size() < 2 ? new double[5] : RoutePlanner.scoreTrajectory(pts, grid, pm);
    }

    /** Horizontal length of a planned path (blocks). */
    private static double walkLength(java.util.List<P> path) {
        double l = 0;
        for (int i = 1; i < path.size(); i++) l += Math.hypot(path.get(i).x() - path.get(i - 1).x(), path.get(i).z() - path.get(i - 1).z());
        return l;
    }

    /**
     * How well the plan order predicted the player's break order, beyond plain proximity. Planned clicks (the lane
     * plan's trigger chests, in order) are matched to breaks (exact block, else nearest within {@link #ACC_MATCH_R}); rho is the Spearman correlation
     * of planned index vs break time and rhoNN the same for a nearest-neighbour tour from the entry point.
     * score = 0.7 × normalized lift (rho − rhoNN) + 0.3 × spatial follow term.
     *
     * @return {n, rho, rhoNN, lift, spatial, score}, or null when fewer than {@link #ACC_MIN_MATCHES} matched
     */
    private static double[] routeAccuracy(SolvedRoute room, java.util.List<P> planned, double[] follow) {
        if (planned == null || planned.isEmpty() || room.userBreaks.size() < ACC_MIN_MATCHES) return null;
        boolean[] used = new boolean[room.userBreaks.size()];
        java.util.List<double[]> matched = new java.util.ArrayList<>(); // {plannedIndex, breakT, lx, ly, lz}
        for (int i = 0; i < planned.size(); i++) {
            int hit = matchBreak(room, planned.get(i), used);
            if (hit < 0) continue;
            used[hit] = true;
            double[] br = room.userBreaks.get(hit);
            matched.add(new double[]{i, br[0], br[1], br[2], br[3]});
        }
        int n = matched.size();
        if (n < ACC_MIN_MATCHES) return null;
        double[] plannedIdx = new double[n], times = new double[n];
        for (int i = 0; i < n; i++) {
            plannedIdx[i] = matched.get(i)[0];
            times[i] = matched.get(i)[1];
        }
        double rho = spearman(plannedIdx, times);
        double rhoNN = spearman(nearestNeighbourOrder(matched, room.userTrail), times);
        double lift = rho - rhoNN;
        double spatial = clamp01(1.0 - follow[0] / ACC_SPATIAL_SCALE);
        double score = 0.7 * clamp01(lift / Math.max(1.0 - rhoNN, 0.05)) + 0.3 * spatial;
        return new double[]{n, rho, rhoNN, lift, spatial, score};
    }

    /** Index of the unused break at a planned click (exact block, else nearest within {@link #ACC_MATCH_R}), or -1. */
    private static int matchBreak(SolvedRoute room, P wp, boolean[] used) {
        for (int b = 0; b < room.userBreaks.size(); b++) {
            if (used[b]) continue;
            double[] br = room.userBreaks.get(b);
            if ((int) br[1] == wp.x() && (int) br[2] == wp.y() && (int) br[3] == wp.z()) return b;
        }
        int best = -1;
        double bd = ACC_MATCH_R * ACC_MATCH_R;
        for (int b = 0; b < room.userBreaks.size(); b++) {
            if (used[b]) continue;
            double[] br = room.userBreaks.get(b);
            double dx = br[1] - wp.x(), dy = br[2] - wp.y(), dz = br[3] - wp.z();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bd) { bd = d; best = b; }
        }
        return best;
    }

    /** Each match's position in a greedy nearest-neighbour tour starting from the trail's first point. */
    private static double[] nearestNeighbourOrder(java.util.List<double[]> matched, java.util.List<double[]> trail) {
        int n = matched.size();
        double[] order = new double[n];
        boolean[] taken = new boolean[n];
        double cx, cy, cz;
        if (!trail.isEmpty()) {
            double[] t0 = trail.get(0);
            cx = t0[0]; cy = t0[1]; cz = t0[2];
        } else {
            double[] m0 = matched.get(0);
            cx = m0[2]; cy = m0[3]; cz = m0[4];
        }
        for (int step = 0; step < n; step++) {
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (taken[i]) continue;
                double[] m = matched.get(i);
                double dx = m[2] - cx, dy = m[3] - cy, dz = m[4] - cz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bd) { bd = d; best = i; }
            }
            if (best < 0) break;
            taken[best] = true;
            order[best] = step;
            double[] m = matched.get(best);
            cx = m[2]; cy = m[3]; cz = m[4];
        }
        return order;
    }

    /** Spearman rank correlation (average ranks for ties). */
    private static double spearman(double[] a, double[] b) {
        return pearson(ranks(a), ranks(b));
    }

    private static double[] ranks(double[] v) {
        int n = v.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (p, q) -> Double.compare(v[p], v[q]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++;
            double avg = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) r[idx[k]] = avg;
            i = j + 1;
        }
        return r;
    }

    private static double pearson(double[] a, double[] b) {
        int n = a.length;
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
        ma /= n;
        mb /= n;
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma, db = b[i] - mb;
            sab += da * db;
            saa += da * da;
            sbb += db * db;
        }
        if (saa <= 1.0e-12 || sbb <= 1.0e-12) {
            LOG.error("[Routerunner] accuracy: a rank series has no spread (n {}); scoring that correlation as 0.", n);
            return 0.0;
        }
        return sab / Math.sqrt(saa * sbb);
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    /** Horizontal trail deviation from the route path: {avgBlocks, maxBlocks, percent of samples >2 blocks off}. */
    private static double[] followDeviation(java.util.List<double[]> trail, java.util.List<P> path) {
        if (path == null || path.isEmpty() || trail.isEmpty()) return new double[]{0, 0, 0};
        double sum = 0, max = 0;
        int over = 0;
        for (double[] u : trail) {
            double best = Double.MAX_VALUE;
            for (P p : path) {
                double dx = u[0] - p.x(), dz = u[2] - p.z();
                double d = dx * dx + dz * dz;
                if (d < best) best = d;
            }
            best = Math.sqrt(best);
            sum += best;
            if (best > max) max = best;
            if (best > 2.0) over++;
        }
        int n = trail.size();
        return new double[]{sum / n, max, 100.0 * over / n};
    }

    private static int total(int[] c) {
        int s = 0;
        for (int x : c) s += x;
        return s;
    }

    /** The cell the player is heading into, by travel direction; {rx,rz} if not moving. */
    /** The room picker as a snapshot exit chooser: scans the rooms around (rx, rz) first. Null when adaptive rooms are off. */
    private static RoomGeometry.ExitChooser exitChooser(Level level, int rx, int rz, String targetType, RouterunnerConfig cfg) {
        if (!cfg.adaptiveRooms || !RoomPicker.isRoomCell(rx, rz)) return null;
        RoomPicker.setMiner(ChainMinerInfo.current().mode());
        RoomPicker.observeAround(level, rx, rz, targetType);
        Player player = Minecraft.getInstance().player;
        if (player != null) planDoorNeighbours(level, player, rx, rz, targetType, cfg);
        return (entranceWall, open) -> RoomPicker.choose(rx, rz, entranceWall, open);
    }

    /** Active clock of the last neighbour-planning check. */
    private static long lastNeighbourPlanMs = -60_000;

    /**
     * Twice a second while a room is routed: rescan the rooms around it and queue background plans for the rooms past
     * the doors of this room and of the room its current exit leads to, so the next room's choice is priced from real
     * plans by the time the player gets there.
     */
    private static void maybePlanNeighbours(Level level, Player player, SolvedRoute sr, RouterunnerConfig cfg) {
        long now = MetricsTracker.get().getActiveMs();
        if (now - lastNeighbourPlanMs < 500) return;
        lastNeighbourPlanMs = now;
        int rx = Math.floorDiv(sr.ox, RoomGeometry.CELL), rz = Math.floorDiv(sr.oz, RoomGeometry.CELL);
        if (!RoomPicker.isRoomCell(rx, rz)) return;
        RoomPicker.observeAround(level, rx, rz, sr.targetType);
        planDoorNeighbours(level, player, rx, rz, sr.targetType, cfg);
        if (sr.exitWall >= 0) {
            planDoorNeighbours(level, player, rx + RoomPicker.dx(sr.exitWall), rz + RoomPicker.dz(sr.exitWall), sr.targetType, cfg);
        }
    }

    /**
     * Queue a background plan for each loaded, unvisited room past a door of room (rx, rz) that has no plan for its
     * current chest count. The snapshot is taken here (main thread) with the entrance on the side facing (rx, rz);
     * the room is then planned once per other door on the room picker's thread and the plans averaged.
     */
    private static void planDoorNeighbours(Level level, Player player, int rx, int rz, String targetType, RouterunnerConfig cfg) {
        RoutePlanner.Params params = null;
        for (int w = 0; w < 4; w++) {
            int nx = rx + RoomPicker.dx(w), nz = rz + RoomPicker.dz(w);
            if (!RoomPicker.wantsPlan(nx, nz)) continue;
            int entry = RoomPicker.opposite(w);
            RoomGeometry.Snapshot snap = RoomGeometry.build(level, nx, nz, targetType, doorPos(nx, nz, entry, player.getBlockY()), null);
            if (snap == null || snap.targetsLocal.isEmpty()) continue;
            if (snap.entranceWall != entry) {
                LOG.warn("[Routerunner] Room picker: ({},{}) has no door facing ({},{}) (entrance found on {}); planning it from that door instead.",
                        nx, nz, rx, rz, snap.entranceWall < 0 ? "none" : RoomPicker.WALL_NAMES[snap.entranceWall]);
            }
            if (params == null) params = buildParams(false);
            final RoutePlanner.Params fp = params;
            final int fx = nx, fz = nz;
            RoomPicker.submitPlan(nx, nz, ep -> planNeighbour(ep, snap, fx, fz, targetType, fp, cfg));
        }
    }

    /** World position of the middle of a room's wall (the door column), at height y. */
    private static BlockPos doorPos(int rx, int rz, int wall, int y) {
        int ox = rx * RoomGeometry.CELL, oz = rz * RoomGeometry.CELL, m = RoomGeometry.CELL / 2;
        return switch (wall) {
            case 0 -> new BlockPos(ox, y, oz + m);
            case 1 -> new BlockPos(ox + RoomGeometry.CELL - 1, y, oz + m);
            case 2 -> new BlockPos(ox + m, y, oz);
            default -> new BlockPos(ox + m, y, oz + RoomGeometry.CELL - 1);
        };
    }

    /** Room picker thread: plan a neighbouring room from its entrance to each other door and hand the mean to the picker. */
    private static void planNeighbour(int epoch, RoomGeometry.Snapshot snap, int rx, int rz, String targetType,
                                      RoutePlanner.Params params, RouterunnerConfig cfg) {
        long t0 = System.nanoTime();
        double sumT = 0, sumY = 0;
        int k = 0;
        long key = (((long) rx) << 32) ^ (rz & 0xFFFFFFFFL);
        for (int x = 0; x < 4; x++) {
            if (x == snap.entranceWall || snap.gateByWall[x] == null) continue;
            SolvedRoute sr = new SolvedRoute(key, snap.ox, snap.oy, snap.oz, null, targetType, snap.targetsWorld,
                    snap.othersLocal, snap.otherIds, snap.gateByWall[x], snap.grid, params, snap.entranceLocal);
            sr.roomId = "picker(" + rx + "," + rz + ")";
            com.routerunner.lane.LaneRoute lr = solveLanes(sr, snap.targetsLocal, snap.entranceLocal, cfg, "picker", 0.0, false);
            if (lr == null || lr.plan == null) continue;
            sumT += lr.plan.tTotal;
            sumY += lr.planner.regularYield(lr.plan);
            k++;
        }
        if (k == 0) {
            LOG.warn("[Routerunner] Room picker: no lane plan for ({},{}) through any exit; it stays priced by the proxy until its chest count changes.", rx, rz);
            RoomPicker.planFailed(epoch, rx, rz, snap.targetsLocal.size());
            return;
        }
        RoomPicker.putPlan(epoch, rx, rz, sumT / k, sumY / k, snap.targetsLocal.size(), k);
        LOG.debug("[Routerunner] Room picker planned ({},{}): {} chests, {} exits, mean {} s / {} chests in {} ms.", rx, rz,
                snap.targetsLocal.size(), k, String.format(Locale.ROOT, "%.1f", sumT / k), Math.round(sumY / k),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /** Log the exit a fresh snapshot heads for, with the picker's values when it chose. */
    private static void logPick(RoomGeometry.Snapshot snap, long cellKey, String reason, RouterunnerConfig cfg) {
        if (!cfg.adaptiveRooms || snap.entranceWall < 0) return;
        int rx = Math.floorDiv(snap.ox, RoomGeometry.CELL), rz = Math.floorDiv(snap.oz, RoomGeometry.CELL);
        RunLog.roomPick(cellKey, reason, snap.entranceWall, snap.exitWall, snap.pick, neighbourCounts(rx, rz), RoomPicker.lambdaNow());
        if (snap.pick != null && snap.exitWall != (snap.entranceWall ^ 1)) {
            LOG.info("[Routerunner] Room picker: leave ({},{}) by {} not {} ({}; {} of the other exits' rooms not loaded yet).",
                    rx, rz, RoomPicker.WALL_NAMES[snap.exitWall], RoomPicker.WALL_NAMES[snap.entranceWall ^ 1],
                    RoomPicker.describe(snap.pick, snap.entranceWall), snap.pick.unknownNeighbours());
        }
    }

    /** Target chest counts of the four neighbouring rooms (W, E, N, S), -1 where not scanned yet. */
    private static int[] neighbourCounts(int rx, int rz) {
        return new int[]{RoomPicker.seenCount(rx - 2, rz), RoomPicker.seenCount(rx + 2, rz),
                RoomPicker.seenCount(rx, rz - 2), RoomPicker.seenCount(rx, rz + 2)};
    }

    /** A room's plan became current: feed its planned time and yield to the room picker's rate and proxy scale. */
    private static void notePlanned(SolvedRoute sr) {
        if (sr.lane == null || sr.lane.plan == null) return;
        String tm = sr.lane.planner.P.timeModel;
        if (!sr.exitOnly && tm != null && tm.startsWith("shape") && !sr.lane.planner.hasSolo()) {
            RateCal.notePlan(sr.lane.plan.yieldTotal, sr.lane.plan.tTotal, "vein".equals(sr.params.miner));
            if (sr.entryYield <= 0) {
                sr.entryYield = sr.lane.plan.yieldTotal;
                sr.entryS = sr.lane.plan.tTotal;
            }
        }
        if (!RoomPicker.isRoomCell(Math.floorDiv(sr.ox, RoomGeometry.CELL), Math.floorDiv(sr.oz, RoomGeometry.CELL))) return;
        RoomPicker.onPlanned(Math.floorDiv(sr.ox, RoomGeometry.CELL), Math.floorDiv(sr.oz, RoomGeometry.CELL),
                sr.lane.plan.tTotal, sr.lane.planner.regularYield(sr.lane.plan));
    }

    /**
     * Once a second while the first part of the room is being swept, ask the room picker again with whatever has
     * loaded since the solve. It may move the exit once per room, only by more than its margin and only before
     * {@code switchMaxProgress} of the lanes are done; the lanes are then replanned toward the new door. An exit that
     * leads into a banned room (the labyrinth, a skip-listed room) is always moved, at any point in the room.
     */
    private static void maybeSwitchExit(Level level, Player player, SolvedRoute sr, RouterunnerConfig cfg) {
        if (sr.gateByWall == null || sr.entranceWall < 0 || sr.exitWall < 0 || sr.lane == null || sr.laneSolving) return;
        long now = MetricsTracker.get().getActiveMs();
        if (now - sr.lastPickMs < 1000) return;
        sr.lastPickMs = now;
        com.routerunner.lane.LaneRoute lr = sr.lane;
        int rx = Math.floorDiv(sr.ox, RoomGeometry.CELL), rz = Math.floorDiv(sr.oz, RoomGeometry.CELL);
        if (!RoomPicker.isRoomCell(rx, rz)) return;
        boolean bannedAhead = RoomPicker.isBanned(rx + RoomPicker.dx(sr.exitWall), rz + RoomPicker.dz(sr.exitWall));
        int nRuns = lr.runs.isEmpty() ? 0 : lr.runs.size() - (lr.runs.get(lr.runs.size() - 1).exit ? 1 : 0);
        if (!bannedAhead) {
            if (sr.exitSwitched || lr.finished() || lr.runs.isEmpty()) return;
            com.routerunner.lane.LaneRoute.Run r = lr.current();
            if (r == null || r.exit) return;
            if (nRuns <= 0 || (double) lr.cur / nRuns >= RoomPicker.switchMaxProgress()) return;
        }
        if (now - lr.lastReplanMs < com.routerunner.lane.LaneRoute.REPLAN_MIN_MS) return;
        RoomPicker.observeAround(level, rx, rz, sr.targetType);
        boolean[] open = new boolean[4];
        for (int w = 0; w < 4; w++) open[w] = sr.gateByWall[w] != null;
        RoomPicker.Decision d = RoomPicker.choose(rx, rz, sr.entranceWall, open);
        if (d == null || d.exit() == sr.exitWall || sr.gateByWall[d.exit()] == null) {
            if (bannedAhead && sr.bannedWarned != sr.exitWall) {
                sr.bannedWarned = sr.exitWall;
                LOG.warn("[Routerunner] Room picker: the exit of ({},{}) leads into a banned room and no other door is open; keeping it.", rx, rz);
            }
            return;
        }
        double gain = d.gainOver(sr.exitWall);
        if (!bannedAhead && !(gain > RoomPicker.switchMargin())) return;
        int old = sr.exitWall;
        sr.exitWall = d.exit();
        sr.exitLocal = sr.gateByWall[d.exit()];
        sr.exitSwitched = true;
        RunLog.roomPick(sr.cellKey, "switch", sr.entranceWall, sr.exitWall, d, neighbourCounts(rx, rz), d.lambda());
        LOG.info("[Routerunner] Room picker: switching the exit of ({},{}) from {} to {} at lane {}/{} (+{} chests; {}).", rx, rz,
                RoomPicker.WALL_NAMES[old], RoomPicker.WALL_NAMES[d.exit()], lr.cur + 1, nRuns,
                String.format(Locale.ROOT, "%.1f", gain), RoomPicker.describe(d, sr.entranceWall));
        laneReplan(level, player, sr, cfg, "exit");
    }

    /** HUD suffix naming the exit door when the room picker is on, starred once it has switched. */
    private static String exitTag(SolvedRoute sr) {
        if (!RouterunnerConfig.get().adaptiveRooms || sr.exitWall < 0) return "";
        return " → " + RoomPicker.WALL_NAMES[sr.exitWall] + (sr.exitSwitched ? "*" : "");
    }

    private static int[] aheadCell(Player player, int rx, int rz) {
        Vec3 v = player.getDeltaMovement();
        if (Math.abs(v.x) >= Math.abs(v.z)) {
            if (Math.abs(v.x) > 0.02) return new int[]{rx + (v.x > 0 ? 1 : -1), rz};
        } else {
            if (Math.abs(v.z) > 0.02) return new int[]{rx, rz + (v.z > 0 ? 1 : -1)};
        }
        return new int[]{rx, rz};
    }

    private static String shortRoom(String roomId) {
        int i = roomId.lastIndexOf('/');
        return i >= 0 ? roomId.substring(i + 1) : roomId;
    }

    private static boolean isSkipped(RouterunnerConfig cfg, String roomId) {
        String id = roomId.toLowerCase(Locale.ROOT);
        for (String s : cfg.routingSkipList) {
            if (!s.isEmpty() && id.contains(s.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String safeRoomId(int rx, int rz, Player player) {
        try {
            return VaultInfo.getRoomIdAt(rx, rz, player.getUUID());
        } catch (Throwable t) {
            LOG.error("[Routerunner] room id lookup failed (routing skip-list inactive this cell)", t);
            return null;
        }
    }

    private RouteService() {}

    /** A solved route for one cell plus its live follow state; positions are local to the world origin (ox, oy, oz). */
    public static final class SolvedRoute {
        public final long cellKey;
        public final int ox, oy, oz;
        public volatile String roomId;
        public final String targetType;
        public final java.util.List<BlockPos> targetsWorld;
        /** Non-target chests and strongboxes in the cell at solve time (local), with their block ids. */
        public final java.util.List<P> otherChestsLocal;
        public final java.util.List<String> otherChestIds;
        /** Exit door (local); changes at most once, when the room picker switches the exit. */
        public volatile P exitLocal;
        /** Door cell per wall (0 = W, 1 = E, 2 = N, 3 = S), null where the wall has no door; null for old snapshots. */
        public P[] gateByWall;
        /** Walls of the entrance and the current exit, -1 when unknown. */
        public int entranceWall = -1;
        public volatile int exitWall = -1;
        /** The room picker switched this room's exit (only once per room). */
        volatile boolean exitSwitched = false;
        /** Active clock of the last room-picker re-check. */
        long lastPickMs = -60_000;
        /** Exit wall already warned about leading into a banned room with no alternative (-1 = none). */
        int bannedWarned = -1;
        /** Solidity grid the route was solved on; used by the diff scorer and adaptive weights. */
        public final SolidGrid grid;
        /** The weight snapshot this route was solved with. */
        public final RoutePlanner.Params params;
        /** Solve wall time, snapshot build time and solver-queue wait (ms); -1 until solved. */
        public volatile long solveMs = -1, geomMs = -1, queueMs = -1;
        /** Lane plan wall time (ms), and how long before the player entered the cell a prefetched route was ready (-1 if not prefetched). */
        public volatile long laneMs = -1, prefetchLeadMs = -1;
        /** Active clock when the solve finished. */
        long solvedActiveMs;
        /** The lane plan being followed, or null when the room shows no route. */
        public volatile com.routerunner.lane.LaneRoute lane = null;
        /** Why the room has no lane plan, for the HUD; null when it has one or nothing was tried. */
        volatile String noRouteWhy = null;
        /** A locking challenge room: only the way out is routed. */
        volatile boolean exitOnly = false;
        /** A lane replan is in flight on the solver thread. */
        volatile boolean laneSolving = false;
        /** Where the plan started (the snapshot's entrance), for replans and logging. */
        public final P entranceLocal;
        /** A teleport happened since the last trail sample. */
        boolean tpSinceCapture = false;
        /** Player trail, local {x, y, z, activeMs, yaw, pitch, teleportFlag}; captured while diff or adaptive is on. */
        final java.util.List<double[]> userTrail = new java.util.ArrayList<>();
        /** Target chests of this room the player broke: {activeMs, lx, ly, lz}. */
        final java.util.List<double[]> userBreaks = new java.util.ArrayList<>();
        /** Vein Miner rooms: {groups, hit, missed, missed chests, min size} of big touching groups, set when the room's diff is logged. */
        volatile int[] bigGroups = null;
        /** Active-clock times of teleports while this room was current (guarded by itself). */
        final java.util.List<Long> teleportMs = new java.util.ArrayList<>();
        /** Lazily built set over {@link #targetsWorld}. */
        java.util.Set<BlockPos> targetSet = null;
        /** Max chests gone from this room while the player was in it. */
        int userChests = 0;
        /** Active-clock bounds of the trail and the capture throttle (ms). */
        long userFirstMs = 0, userLastMs = 0, lastCaptureMs = 0;
        /** Wall-clock bounds of the trail (ms). */
        long firstTs = 0, lastTs = 0;
        double lastTrailX, lastTrailZ;
        boolean haveTrail = false;
        long startActiveMs;
        /** Hallway blocks travelled to reach this room. */
        double hallwayIn = 0;
        /** Per client tick while measured: {activeMs, x, z, yaw, pitch, onGround 1/0}, for the idle filter. */
        final java.util.List<double[]> ticks = new java.util.ArrayList<>();
        /** The first shape plan the player was shown here (chests, seconds); 0 when none. */
        double entryYield = 0, entryS = 0;
        /** Mean distance of the trail from the drawn route (blocks), NaN when there was no route to follow. */
        double followAvgOff = Double.NaN;

        SolvedRoute(long cellKey, int ox, int oy, int oz, String roomId, String targetType,
                    java.util.List<BlockPos> targetsWorld, java.util.List<P> otherChestsLocal,
                    java.util.List<String> otherChestIds, P exitLocal, SolidGrid grid, RoutePlanner.Params params, P entranceLocal) {
            this.entranceLocal = entranceLocal;
            this.cellKey = cellKey;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.roomId = roomId;
            this.targetType = targetType;
            this.targetsWorld = targetsWorld;
            this.otherChestsLocal = otherChestsLocal;
            this.otherChestIds = otherChestIds;
            this.exitLocal = exitLocal;
            this.grid = grid;
            this.params = params;
        }

        public BlockPos worldOf(P local) {
            return new BlockPos(ox + local.x(), oy + local.y(), oz + local.z());
        }
    }
}
