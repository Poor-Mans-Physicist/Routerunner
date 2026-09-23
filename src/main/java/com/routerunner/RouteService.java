package com.routerunner;

import com.mojang.logging.LogUtils;
import com.routerunner.solver.P;
import com.routerunner.solver.RoutePlan;
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
 * cell a hallway leads into), snapshots it once its chunks are loaded, solves off the main thread, then
 * advances the follow cursor as chests break and feeds the run log, adaptive weights and diff scorer.
 * Called on the client thread except where noted.
 */
public final class RouteService {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long NO_CELL = Long.MIN_VALUE;
    /** Radius (blocks) of a waypoint's area. */
    private static final double AREA_R = 7.0;
    /** Ticks without progress in the current area before the waypoint is abandoned. */
    private static final int STUCK_TICKS = 100;
    /** Waypoints ahead inspected by the forward-progress skip. */
    private static final int FORWARD_SKIP_WINDOW = 6;
    /** Minimum waypoints ahead needed for the forward-progress skip to judge. */
    private static final int FORWARD_SKIP_MIN = 3;
    /** Fraction of waypoints ahead already clear that triggers the forward-progress skip. */
    private static final double FORWARD_SKIP_FRAC = 0.65;
    /** Fraction of a waypoint's original cluster gone at which it counts as done. */
    private static final double AREA_DONE_FRAC = 0.85;
    /** Min horizontal speed (blk/s) for the missed-waypoint rule. */
    private static final double MISSED_MIN_SPEED = 2.0;
    /** Angle off the momentum heading (degrees) beyond which a waypoint is behind the player. */
    private static final int MISSED_ANGLE_DEG = 125;
    private static final double MISSED_ANGLE_COS = Math.cos(Math.toRadians(MISSED_ANGLE_DEG));
    /** How long (active ms) the waypoint must have been receding. */
    private static final long MISSED_RECEDE_MS = 300;
    /** How long (active ms) since the last tracked break. */
    private static final long MISSED_BREAK_QUIET_MS = 300;
    /** Below this cluster-gone fraction, a waypoint whose trigger still stands is never skipped as missed. */
    private static final double MISSED_GONE_FRAC = 0.25;
    /** Distance growth (blocks) that counts as receding. */
    private static final double RECEDE_EPS = 1.0e-3;
    /** Horizontal speed (blocks/tick) below which there is no momentum heading. */
    private static final double MOMENTUM_MIN = 0.05;
    /** Trigger gone plus this cluster-gone fraction marks a waypoint as spent. */
    private static final double SPENT_GONE_FRAC = 0.5;
    /** Shared rate limit (active ms) for missed and spent skips. */
    private static final long SKIP_COOLDOWN_MS = 2000;

    private static final ExecutorService SOLVER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "routerunner-solver");
        t.setDaemon(true);
        return t;
    });

    /** Minimum interval between {@code state} records (ms). */
    private static final long STATE_LOG_MIN_MS = 1000;
    /** Minimum matched waypoints for a route-accuracy score. */
    private static final int ACC_MIN_MATCHES = 4;
    /** Max distance (blocks) for a break to match a planned waypoint. */
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
         * The bail floor in model chests per second. With the adaptive model on, the model is already calibrated to
         * this player's real seconds, so the realized rate needs no conversion; off, the live ratio converts it.
         */
        static synchronized double bailFloor(RouterunnerConfig cfg, long activeMs) {
            double conv = com.routerunner.adaptive.Adaptive.enabled() ? 1.0 : ratio();
            return Math.max(0.0, cfg.laneBailRateFrac) * rate(activeMs) * conv;
        }

        static synchronized void reset() {
            breaks.clear();
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
     * Flag a position discontinuity (see {@link TeleportDetector}) on the live room so the next trail sample
     * and the current reach leg are not treated as travel.
     */
    public static void noteTeleport() {
        SolvedRoute sr = current;
        if (sr == null) return;
        sr.tpSinceWp++;
        sr.tpSinceCapture = true;
        synchronized (sr.teleportMs) {
            sr.teleportMs.add(MetricsTracker.get().getActiveMs());
        }
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
        }
        lastAccuracyPct = -1;
        current = null;
        solvingCell = NO_CELL;
        prefetchingCell = NO_CELL;
        prefetched = null;
        RateCal.reset();
        setState("idle");
        sessionHotSpotSum = 0;
        roomsForHotSpot = 0;
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
            if (cfg.routingEnabled) {
                advance(level, player, cur);
                int wpTotal = cur.plan.waypoints.size();
                String suffix = cur.cursor >= wpTotal ? "done → EXIT" : (Math.min(cur.cursor + 1, wpTotal) + "/" + wpTotal);
                if (cur.lane != null) {
                    laneTick(level, player, cur, cfg);
                    com.routerunner.lane.LaneRoute lr = cur.lane;
                    int nLaneRuns = lr.runs.size() - (lr.runs.get(lr.runs.size() - 1).exit ? 1 : 0);
                    suffix = lr.finished() ? "at exit" : (lr.current().exit ? "lane → exit" : ("lane " + (lr.cur + 1) + "/" + nLaneRuns));
                    setState("route " + suffix + " [" + lr.mode + "]");
                } else {
                    setState("route " + suffix + " [" + cur.targetType + "]");
                }
            } else {
                setState("diff tracking [" + cur.targetType + "]");
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
        RoomGeometry.Snapshot snap = RoomGeometry.build(level, chosenRx, chosenRz, targetType, pp);
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
        submitSolve(snap, cellKey, roomId, targetType, cfg, buildParams(), fGeomMs, false);
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
        if (roomId != null && isSkipped(cfg, roomId)) return;
        String targetType = resolveTargetType(cfg, ac);
        DensityTracker.onScan(DensityTracker.cellKey(ahead[0], ahead[1]), ac[typeIndex(targetType)]);
        VaultGate.onScan(DensityTracker.cellKey(ahead[0], ahead[1]), ac);
        long geomStartNs = System.nanoTime();
        RoomGeometry.Snapshot snap = RoomGeometry.build(level, ahead[0], ahead[1], targetType, player.blockPosition());
        long geomMs = (System.nanoTime() - geomStartNs) / 1_000_000L;
        if (snap == null || snap.targetsLocal.isEmpty()) return;
        prefetchingCell = key;
        submitSolve(snap, key, roomId, targetType, cfg, buildParams(), geomMs, true);
    }

    /** Make a prefetched route current now that the player has entered its cell, and log it as solved. */
    private static void adoptPrefetched(SolvedRoute pre, String roomId) {
        long now = MetricsTracker.get().getActiveMs();
        pre.prefetchLeadMs = Math.max(0, now - pre.solvedActiveMs);
        pre.startActiveMs = now;
        pre.lastWpActiveMs = now;
        if (pre.roomId == null && roomId != null) pre.roomId = roomId;
        current = pre;
        RunLog.roomSolve(pre);
        if (pre.lane != null) RunLog.lanePlan(pre.cellKey, pre.roomId, pre.lane.mode, "solve", pre.lane.plan.lanes.size(), pre.lane);
        LOG.info("[Routerunner] Adopted the prefetched route for {} ({} chests, ready {} ms before entry).",
                pre.roomId, pre.targetsWorld.size(), pre.prefetchLeadMs);
    }

    /** Solve a snapshot on the solver thread: the waypoint route, then the lane plan; publish as current or as prefetched. */
    private static void submitSolve(RoomGeometry.Snapshot snap, long cellKey, String roomId, String targetType, RouterunnerConfig cfg,
                                    RoutePlanner.Params params, long geomMs, boolean prefetch) {
        final long submitNs = System.nanoTime();
        SOLVER.submit(() -> {
            try {
                long startNs = System.nanoTime();
                RoutePlan plan = RoutePlanner.planRoute(snap.grid, snap.targetsLocal, snap.entranceLocal, snap.exitLocal, params);
                long doneNs = System.nanoTime();
                SolvedRoute sr = new SolvedRoute(cellKey, snap.ox, snap.oy, snap.oz, roomId, targetType, plan, snap.targetsWorld,
                        snap.othersLocal, snap.otherIds, snap.exitLocal, snap.grid, params, snap.entranceLocal);
                sr.geomMs = geomMs;
                sr.queueMs = (startNs - submitNs) / 1_000_000L;
                sr.solveMs = (doneNs - startNs) / 1_000_000L;
                long laneStart = System.nanoTime();
                sr.lane = solveLanes(sr, snap.targetsLocal, snap.entranceLocal, cfg, "solve", 0.0, !prefetch);
                sr.laneMs = (System.nanoTime() - laneStart) / 1_000_000L;
                LOG.info("[Routerunner] Lane plan for {}: {} runs in {} ms{}{}.", roomId,
                        sr.lane == null ? "no" : sr.lane.runs.size(), sr.laneMs, prefetch ? " (prefetch)" : "",
                        sr.lane != null && sr.lane.planner.isNative() ? " [rust]" : " [java]");
                sr.areaTotal = areaTotals(sr);
                long now = MetricsTracker.get().getActiveMs();
                sr.solvedActiveMs = now;
                sr.startActiveMs = now;
                sr.lastWpActiveMs = now;
                if (prefetch) {
                    prefetched = sr;
                    LOG.info("[Routerunner] Prefetched {} ({} chests) in {} ms while still in the hallway.", roomId,
                            snap.targetsLocal.size(), sr.solveMs + Math.max(0, sr.laneMs));
                } else {
                    current = sr;
                    RunLog.roomSolve(sr);
                    LOG.info("[Routerunner] Solved {} ({} chests, {} waypoints, {}% planned, chain {}/{}, speed {}) in {} ms (geometry {} ms, queued {} ms).", roomId,
                            snap.targetsLocal.size(), plan.waypoints.size(),
                            snap.targetsLocal.isEmpty() ? 0 : (100 * plan.collected / snap.targetsLocal.size()),
                            params.chainRange, params.chainLimit, String.format(Locale.ROOT, "%.3f", params.speedAttr),
                            sr.solveMs, sr.geomMs, sr.queueMs);
                }
            } catch (Throwable t) {
                LOG.error("[Routerunner] solve failed", t);
            } finally {
                if (prefetch) prefetchingCell = NO_CELL;
                else solvingCell = NO_CELL;
            }
        });
    }

    /**
     * Advance the follow cursor. A waypoint is done when its area is (mostly) cleared or only a straggler
     * remains; otherwise it may be skipped as missed, forward-passed, stuck or spent. If its trigger is gone
     * but its cluster isn't, {@link SolvedRoute#retargetPos} aims at the nearest leftover. The plan is never
     * reordered; only the waypoint at the cursor can be discarded.
     */
    private static void advance(Level level, Player player, SolvedRoute sr) {
        int size = sr.plan.waypoints.size();
        if (sr.cursor >= size) { sr.retargetPos = null; return; }

        long activeMs = MetricsTracker.get().getActiveMs();
        int goneNow = countGone(level, sr);
        while (sr.cursor < size) {
            boolean cleared = areaMostlyClear(level, sr, sr.cursor);
            if (!cleared && !stragglerDone(level, sr, sr.cursor)) break;
            logReach(level, sr, sr.plan.waypoints.get(sr.cursor), activeMs, goneNow, cleared ? "chain" : "straggler");
        }

        if (sr.cursor >= size) { sr.retargetPos = null; sr.stuckTicks = 0; return; }

        RoutePlan.WP cw = sr.plan.waypoints.get(sr.cursor);

        if (maybeSkipMissed(level, player, sr, cw, activeMs, goneNow)) return;

        // forward-progress skip: most waypoints ahead are already clear
        int look = 0, aheadClear = 0;
        for (int k = sr.cursor + 1; k < size && look < FORWARD_SKIP_WINDOW; k++, look++) {
            if (areaMostlyClear(level, sr, k)) aheadClear++;
        }
        if (look >= FORWARD_SKIP_MIN && aheadClear >= FORWARD_SKIP_FRAC * look) {
            LOG.info("[Routerunner] forward-skip waypoint {} ({}/{} ahead already clear)", sr.cursor, aheadClear, look);
            logReach(level, sr, cw, activeMs, goneNow, "forward");
            sr.retargetPos = null;
            sr.stuckTicks = 0;
            return;
        }

        // stuck guard: no progress in this area for STUCK_TICKS
        int rem = countRemainingInArea(level, sr, cw.pos);
        if (sr.stuckCursor != sr.cursor || rem < sr.lastAreaRemaining) {
            sr.stuckCursor = sr.cursor;
            sr.lastAreaRemaining = rem;
            sr.stuckTicks = 0;
        } else if (++sr.stuckTicks > STUCK_TICKS) {
            logReach(level, sr, cw, activeMs, goneNow, "stuck");
            sr.retargetPos = null;
            sr.stuckTicks = 0;
            return;
        }

        if (isTargetChestAt(level, world(sr, cw.pos), sr.targetType)) {
            sr.retargetPos = null;
            return;
        }
        double gone = goneFrac(sr, sr.cursor, rem);
        boolean spent = rem <= STRAGGLER_SKIP || gone >= SPENT_GONE_FRAC;
        if (spent && activeMs - sr.lastSkipMs >= SKIP_COOLDOWN_MS) {
            LOG.info("[Routerunner] spent waypoint {} ({} chest(s) left, {}% of its cluster gone) — continuing to the next",
                    sr.cursor, rem, (int) Math.round(100.0 * gone));
            sr.lastSkipMs = activeMs;
            skipWaypoint(sr, cw, "spent", activeMs, goneNow);
            return;
        }
        sr.retargetPos = nearestRemainingNear(level, sr, cw.pos);
        if (sr.retargetPos != null && sr.retargetLoggedCursor != sr.cursor) {
            sr.retargetLoggedCursor = sr.cursor;
            RunLog.retarget(sr.cellKey, sr.cursor, rem, gone);
        }
    }

    /**
     * Skip the cursor's waypoint if the player has passed it: it was ahead of the momentum heading at some point
     * this leg, is now well behind it and receding, the player is moving fast, nothing broke recently, and the
     * cluster isn't a full untouched one. Heading is from velocity, not look direction.
     *
     * @return true if the waypoint was discarded and the cursor has moved on
     */
    private static boolean maybeSkipMissed(Level level, Player player, SolvedRoute sr, RoutePlan.WP cw,
                                           long activeMs, int goneNow) {
        BlockPos w = world(sr, cw.pos);
        double dx = w.getX() + 0.5 - player.getX(), dz = w.getZ() + 0.5 - player.getZ();
        double dist = Math.hypot(dx, dz);
        if (sr.missCursor != sr.cursor) {
            sr.missCursor = sr.cursor;
            sr.lastWpDist = dist;
            sr.recedingSinceMs = -1;
            sr.wpWasAhead = false;
        }
        if (dist > sr.lastWpDist + RECEDE_EPS) {
            if (sr.recedingSinceMs < 0) sr.recedingSinceMs = activeMs;
        } else {
            sr.recedingSinceMs = -1;
        }
        sr.lastWpDist = dist;

        Vec3 v = player.getDeltaMovement();
        double vlen = Math.hypot(v.x, v.z);
        if (vlen < MOMENTUM_MIN || dist < RECEDE_EPS) return false;
        double cos = (v.x * dx + v.z * dz) / (vlen * dist);
        if (cos > 0) sr.wpWasAhead = true;

        if (!sr.wpWasAhead) return false;
        if (vlen * 20.0 < MISSED_MIN_SPEED) return false;
        if (cos >= MISSED_ANGLE_COS) return false;
        if (sr.recedingSinceMs < 0 || activeMs - sr.recedingSinceMs < MISSED_RECEDE_MS) return false;
        if (activeMs - sr.lastBreakMs < MISSED_BREAK_QUIET_MS) return false;
        if (activeMs - sr.lastSkipMs < SKIP_COOLDOWN_MS) return false;
        int rem = countRemainingInArea(level, sr, cw.pos);
        double gone = goneFrac(sr, sr.cursor, rem);
        if (gone < MISSED_GONE_FRAC && isTargetChestAt(level, w, sr.targetType)) return false;

        LOG.info("[Routerunner] missed waypoint {} ({} blocks back, {}% of its cluster gone) — continuing to the next",
                sr.cursor, String.format(Locale.ROOT, "%.1f", dist), (int) Math.round(100.0 * gone));
        sr.lastSkipMs = activeMs;
        skipWaypoint(sr, cw, "missed", activeMs, goneNow);
        return true;
    }

    /**
     * Discard the cursor's waypoint as a {@code skip} and move to the next one, rolling the leg bookkeeping
     * forward as a reach would.
     */
    private static void skipWaypoint(SolvedRoute sr, RoutePlan.WP wp, String reason, long activeMs, int goneNow) {
        logSkip(sr, world(sr, wp.pos), reason);
        sr.goneAtLastWp = goneNow;
        sr.lastWpActiveMs = activeMs;
        sr.prevCumDist = wp.cumDist;
        sr.cursor++;
        sr.retargetPos = null;
        sr.stuckTicks = 0;
    }

    /** Fraction of a waypoint's original cluster already gone; 1.0 when there is no baseline for it. */
    private static double goneFrac(SolvedRoute sr, int cursor, int remaining) {
        int total = (sr.areaTotal != null && cursor < sr.areaTotal.length) ? sr.areaTotal[cursor] : 0;
        if (total <= 0) return 1.0;
        return clamp01((total - remaining) / (double) total);
    }

    private static void logSkip(SolvedRoute sr, BlockPos pos, String reason) {
        RunLog.skip(sr.cellKey, sr.roomId, sr.targetType, sr.cursor, pos, reason);
    }

    /**
     * Log the cursor's waypoint and advance past it: a {@code reach} (and an adaptive leg sample if no teleport
     * occurred), or a {@code skip} with {@code skipReason} if its trigger was already gone and this leg cleared nothing.
     */
    private static void logReach(Level level, SolvedRoute sr, RoutePlan.WP wp, long activeMs, int goneNow,
                                 String skipReason) {
        int actualCleared = Math.max(0, goneNow - sr.goneAtLastWp);
        long dt = activeMs - sr.lastWpActiveMs;
        double plannedDist = wp.cumDist - sr.prevCumDist;
        BlockPos wpos = world(sr, wp.pos);
        if (actualCleared == 0 && !isTargetChestAt(level, wpos, sr.targetType)) {
            logSkip(sr, wpos, skipReason);
        } else {
            RunLog.reach(sr.cellKey, sr.roomId, sr.targetType, sr.cursor, wpos, wp.segMode,
                    plannedDist, actualCleared, wp.plannedCleared, dt, sr.tpSinceWp);
        }
        sr.goneAtLastWp = goneNow;
        sr.lastWpActiveMs = activeMs;
        sr.prevCumDist = wp.cumDist;
        sr.tpSinceWp = 0;
        sr.cursor++;
    }

    /** Once a waypoint's trigger chest is gone it counts as done when at most this many chests remain in its area. */
    private static final int STRAGGLER_SKIP = 3;

    /** True if the waypoint's trigger is gone and at most {@link #STRAGGLER_SKIP} chests remain in its area. */
    private static boolean stragglerDone(Level level, SolvedRoute sr, int cursor) {
        int skip = STRAGGLER_SKIP;
        P wp = sr.plan.waypoints.get(cursor).pos;
        if (isTargetChestAt(level, world(sr, wp), sr.targetType)) return false;
        return countRemainingInArea(level, sr, wp) <= skip;
    }

    /** True if no target chest remains within {@link #AREA_R} of a waypoint. */
    private static boolean areaClear(Level level, SolvedRoute sr, P wpLocal) {
        BlockPos c = world(sr, wpLocal);
        for (BlockPos p : sr.targetsWorld) {
            if (near(p, c, AREA_R) && isTargetChestAt(level, p, sr.targetType)) return false;
        }
        return true;
    }

    /** Count of still-present target chests within {@link #AREA_R} of a waypoint. */
    private static int countRemainingInArea(Level level, SolvedRoute sr, P wpLocal) {
        BlockPos c = world(sr, wpLocal);
        int n = 0;
        for (BlockPos p : sr.targetsWorld) {
            if (near(p, c, AREA_R) && isTargetChestAt(level, p, sr.targetType)) n++;
        }
        return n;
    }

    /** Per-waypoint count of target chests within {@link #AREA_R} at solve time. */
    private static int[] areaTotals(SolvedRoute sr) {
        int n = sr.plan.waypoints.size();
        int[] tot = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos c = world(sr, sr.plan.waypoints.get(i).pos);
            int cnt = 0;
            for (BlockPos p : sr.targetsWorld) if (near(p, c, AREA_R)) cnt++;
            tot[i] = cnt;
        }
        return tot;
    }

    /** True if the waypoint's area is clear or at least {@link #AREA_DONE_FRAC} of its original chests are gone. */
    private static boolean areaMostlyClear(Level level, SolvedRoute sr, int cursor) {
        P wp = sr.plan.waypoints.get(cursor).pos;
        if (areaClear(level, sr, wp)) return true;
        int total = (sr.areaTotal != null && cursor < sr.areaTotal.length) ? sr.areaTotal[cursor] : 0;
        if (total <= 0) return false;
        return countRemainingInArea(level, sr, wp) <= Math.ceil((1.0 - AREA_DONE_FRAC) * total);
    }

    /** Nearest still-present target chest within {@link #AREA_R} of the waypoint (local coords), or null. */
    private static P nearestRemainingNear(Level level, SolvedRoute sr, P wpLocal) {
        BlockPos c = world(sr, wpLocal);
        BlockPos best = null;
        long bd = Long.MAX_VALUE;
        for (BlockPos p : sr.targetsWorld) {
            if (!near(p, c, AREA_R) || !isTargetChestAt(level, p, sr.targetType)) continue;
            long dx = p.getX() - c.getX(), dy = p.getY() - c.getY(), dz = p.getZ() - c.getZ();
            long d = dx * dx + dy * dy + dz * dz;
            if (d < bd) { bd = d; best = p; }
        }
        return best == null ? null : new P(best.getX() - sr.ox, best.getY() - sr.oy, best.getZ() - sr.oz);
    }

    private static boolean near(BlockPos a, BlockPos b, double r) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz <= r * r;
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
            lp.bailAggression = cfg.laneBail;
            lp.opportunityFloor = opportunityFloor;
            lp.exitWeight = cfg.laneExitWeight;
            lp.bailFloor = RateCal.bailFloor(cfg, MetricsTracker.get().getActiveMs());
            lp.triggerS = com.routerunner.adaptive.Adaptive.triggerS();
            lp.pace = com.routerunner.adaptive.Adaptive.pace();
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
            com.routerunner.lane.LegTimeModel model = com.routerunner.adaptive.Adaptive.planningModel();
            com.routerunner.lane.LanePlanner planner = new com.routerunner.lane.LanePlanner(
                    sr.grid, chestsLocal, sr.params.chainRange, sr.params.chainLimit, lp, model);
            P start = com.routerunner.lane.Grid.snapInside(sr.grid, startLocal == null ? sr.exitLocal : startLocal);
            P exit = com.routerunner.lane.Grid.snapInside(sr.grid, sr.exitLocal);
            com.routerunner.lane.LanePlanner.Plan plan = planner.plan(start, exit);
            if (plan.lanes.isEmpty()) {
                LOG.error("[Routerunner] lane planner found nothing to sweep in {} ({}); falling back to the waypoint route.", sr.roomId, reason);
                return null;
            }
            if (plan.exitStraight) {
                LOG.warn("[Routerunner] no ground path and no flight within 3 hops to the exit of {} from the last lane ({}); the exit run is a straight line through whatever is in the way.",
                        sr.roomId, reason);
            }
            com.routerunner.lane.LaneRoute lr = new com.routerunner.lane.LaneRoute(planner, plan, lp.pointMode ? "point" : "corridor",
                    sr.ox, sr.oy, sr.oz, MetricsTracker.get().getActiveMs());
            if (log) RunLog.lanePlan(sr.cellKey, sr.roomId, lr.mode, reason, plan.lanes.size(), lr);
            return lr;
        } catch (Throwable t) {
            LOG.error("[Routerunner] lane planner failed for {} ({}); falling back to the waypoint route.", sr.roomId, reason, t);
            return null;
        }
    }

    /**
     * Replan on the room's existing planner (its reach, component, landing and exit caches survive) over the chests
     * still standing, from the player's cell, keeping the first solve's opportunity as the bail anchor. A plan with
     * no lanes left is still returned when it has an exit walk, so the player is led out rather than left blank.
     */
    private static com.routerunner.lane.LaneRoute replanLanes(SolvedRoute sr, com.routerunner.lane.LaneRoute old, boolean[] mask,
                                                              P startLocal, RouterunnerConfig cfg, String reason) {
        try {
            com.routerunner.lane.LanePlanner planner = old.planner;
            planner.P.opportunityFloor = old.plan.opportunity;
            planner.P.bailAggression = cfg.laneBail;
            planner.P.exitWeight = cfg.laneExitWeight;
            planner.P.bailFloor = RateCal.bailFloor(cfg, MetricsTracker.get().getActiveMs());
            P start = com.routerunner.lane.Grid.snapInside(sr.grid, startLocal == null ? sr.exitLocal : startLocal);
            P exit = com.routerunner.lane.Grid.snapInside(sr.grid, sr.exitLocal);
            com.routerunner.lane.LanePlanner.Plan plan = planner.plan(start, exit, mask);
            if (plan.lanes.isEmpty() && (plan.exitPath == null || plan.exitPath.isEmpty())) {
                LOG.error("[Routerunner] lane replan found nothing to sweep and no exit walk in {} ({}); keeping the old plan.", sr.roomId, reason);
                return null;
            }
            if (plan.exitStraight) {
                LOG.warn("[Routerunner] no ground path and no flight within 3 hops to the exit of {} from the last lane ({}); the exit run is a straight line through whatever is in the way.",
                        sr.roomId, reason);
            }
            com.routerunner.lane.LaneRoute lr = new com.routerunner.lane.LaneRoute(planner, plan, old.mode, sr.ox, sr.oy, sr.oz,
                    MetricsTracker.get().getActiveMs());
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
                if (realized > 0.5 && r.seconds > 0.05) RateCal.observe(r.seconds, realized);
                com.routerunner.adaptive.Adaptive.onRunDone(r.travelS, lr.planner.P.pace, r.nTrig, r.penaltyS, realized,
                        RateCal.breaksSince(lr.runStartMs));
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
        return s.contains(targetType) && !s.contains("strongbox");
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
     * The reference solver's parameters: its built-in tuned weights (fixed; the lane planner is what the player
     * follows), the session bail, the Chain Miner tier and the player's speed.
     */
    private static RoutePlanner.Params buildParams() {
        RoutePlanner.Params p = new RoutePlanner.Params();
        p.bail = roomsForHotSpot > 0 ? p.bailAggression * sessionHotSpotSum / roomsForHotSpot : 0.0;
        int[] chain = ChainMinerInfo.rangeAndLimit();
        p.chainRange = chain[0];
        p.chainLimit = chain[1];
        p.speedAttr = PlayerSpeed.attribute(Minecraft.getInstance().player);
        return p;
    }

    /** The parameter snapshot a solve would run with right now. */
    public static RoutePlanner.Params snapshotParams() {
        return buildParams();
    }

    /**
     * A tracked chest broke: stamp {@link SolvedRoute#lastBreakMs} and, if it is one of the current room's
     * targets, append it to {@link SolvedRoute#userBreaks}.
     */
    public static void onChestBroken(BlockPos pos) {
        RateCal.onBreak(MetricsTracker.get().getActiveMs());
        if (pos != null) DensityTracker.onBreak(pos);
        SolvedRoute lsr = current;
        if (lsr != null && lsr.lane != null) lsr.lane.heatDirty = true;
        SolvedRoute sr = current;
        if (sr == null || pos == null) return;
        sr.lastBreakMs = MetricsTracker.get().getActiveMs();
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

    /** Sum of per-room hot-spot rates this vault. */
    private static double sessionHotSpotSum = 0;
    private static int roomsForHotSpot = 0;
    /** Horizontal player travel outside rooms since the last room change (blocks). */
    private static double pendingHallwayBlocks = 0;
    private static Vec3 lastPlayerPos = null;
    private static SolvedRoute measuredRoom = null;
    private static long measuredCellKey = NO_CELL;

    /** Accumulate hallway travel; on a room change, finalize the previous room for bail and the diff. */
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
                finalizeRoomForBail(measuredRoom);
                finalizeRoomForDiff(measuredRoom);
                learnRoom(measuredRoom);
            }
            crt.hallwayIn = pendingHallwayBlocks;
            pendingHallwayBlocks = 0;
            measuredRoom = crt;
            measuredCellKey = crt.cellKey;
        }
    }

    /** Fold the room's hot-spot rate into the session average and log a room sample. */
    private static void finalizeRoomForBail(SolvedRoute room) {
        double hs = room.plan.hotSpotRate;
        if (hs > 0) {
            sessionHotSpotSum += hs;
            roomsForHotSpot++;
        }
        double chests = room.goneAtLastWp;
        double blocks = room.plan.walkBlocks + room.plan.flyBlocks + room.hallwayIn;
        LOG.info("[Routerunner] room sample: {} chests / {} blocks; hot-spot {} (avg {} over {} rooms)",
                (int) chests, String.format(Locale.ROOT, "%.0f", blocks),
                String.format(Locale.ROOT, "%.3f", hs),
                String.format(Locale.ROOT, "%.3f", roomsForHotSpot > 0 ? sessionHotSpotSum / roomsForHotSpot : 0.0),
                roomsForHotSpot);
    }

    /**
     * Sample the player into the room's trail at up to 10 Hz, skipping moves under 0.25 blocks, and track
     * chests cleared. Each sample is {@code {lx, ly, lz, activeMs, yaw, pitch, teleportFlag}}.
     */
    private static void captureTrail(Level level, SolvedRoute sr, Player player) {
        long ms = MetricsTracker.get().getActiveMs();
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

    /** Score the player's trail against the solver's ground route and log one diff record for the room. */
    private static void finalizeRoomForDiff(SolvedRoute room) {
        try {
            if (room.grid == null || room.userTrail.size() < 3 || room.userChests <= 0) {
                LOG.info("[Routerunner] diff skipped for {} (trail {}, chests {})",
                        room.roomId, room.userTrail.size(), room.userChests);
                return;
            }
            RoutePlanner.Params pm = room.params;
            double[] user = RoutePlanner.scoreTrajectory(room.userTrail, room.grid, pm);
            double[] solver = scoreSolverWalk(room.plan, room.grid, pm);
            double sec = Math.max(0.001, (room.userLastMs - room.userFirstMs) / 1000.0);
            double[] follow = followDeviation(room.userTrail, room.plan.path);
            boolean routing = RouterunnerConfig.get().routingEnabled;
            double[] accuracy = routeAccuracy(room, follow);
            RunLog.roomDiff(room, MetricsTracker.get().getLap(), routing, sec, user, solver, follow, accuracy);
            if (accuracy == null) {
                LOG.info("[Routerunner] accuracy {}: not scored — fewer than 4 planned waypoints matched your {} breaks",
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
                    room.plan.collected,
                    String.format(Locale.ROOT, "%.2f", room.plan.collected > 0 ? solver[4] / room.plan.collected : 0));
        } catch (Throwable t) {
            LOG.error("[Routerunner] diff computation failed for {}", room.roomId, t);
        }
    }

    /** Sum {@link RoutePlanner#scoreTrajectory} over the plan's walk and drop runs, skipping trident/sprint/gap. */
    private static double[] scoreSolverWalk(RoutePlan plan, SolidGrid grid, RoutePlanner.Params pm) {
        double[] sum = new double[5];
        if (plan.path == null || plan.pathMode == null) return sum;
        java.util.List<double[]> run = new java.util.ArrayList<>();
        for (int i = 0; i < plan.path.size(); i++) {
            char m = i < plan.pathMode.length ? plan.pathMode[i] : 'x';
            if (m == 'w' || m == 'd') {
                P p = plan.path.get(i);
                run.add(new double[]{p.x(), p.y(), p.z()});
            } else {
                addScore(sum, run, grid, pm);
                run.clear();
            }
        }
        addScore(sum, run, grid, pm);
        return sum;
    }

    private static void addScore(double[] sum, java.util.List<double[]> run, SolidGrid grid, RoutePlanner.Params pm) {
        if (run.size() < 2) return;
        double[] s = RoutePlanner.scoreTrajectory(run, grid, pm);
        for (int i = 0; i < 5; i++) sum[i] += s[i];
    }

    /**
     * How well the plan order predicted the player's break order, beyond plain proximity. Planned waypoints are
     * matched to breaks (exact block, else nearest within {@link #ACC_MATCH_R}); rho is the Spearman correlation
     * of planned index vs break time and rhoNN the same for a nearest-neighbour tour from the entry point.
     * score = 0.7 × normalized lift (rho − rhoNN) + 0.3 × spatial follow term.
     *
     * @return {n, rho, rhoNN, lift, spatial, score}, or null when fewer than {@link #ACC_MIN_MATCHES} matched
     */
    private static double[] routeAccuracy(SolvedRoute room, double[] follow) {
        java.util.List<RoutePlan.WP> planned = room.plannedOrder;
        if (planned == null || planned.isEmpty() || room.userBreaks.size() < ACC_MIN_MATCHES) return null;
        boolean[] used = new boolean[room.userBreaks.size()];
        java.util.List<double[]> matched = new java.util.ArrayList<>(); // {plannedIndex, breakT, lx, ly, lz}
        for (int i = 0; i < planned.size(); i++) {
            int hit = matchBreak(room, planned.get(i).pos, used);
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

    /** Index of the unused break at a planned waypoint (exact block, else nearest within {@link #ACC_MATCH_R}), or -1. */
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
        public final RoutePlan plan;
        /** Immutable copy of the plan's waypoint order at solve time, for the accuracy scorer. */
        public final java.util.List<RoutePlan.WP> plannedOrder;
        public final java.util.List<BlockPos> targetsWorld;
        /** Non-target chests and strongboxes in the cell at solve time (local), with their block ids. */
        public final java.util.List<P> otherChestsLocal;
        public final java.util.List<String> otherChestIds;
        public final P exitLocal;
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
        /** The lane plan being followed, or null for the waypoint route only. */
        public volatile com.routerunner.lane.LaneRoute lane = null;
        /** A lane replan is in flight on the solver thread. */
        volatile boolean laneSolving = false;
        /** Where the plan started (the waypoint solver's entrance), for replans and logging. */
        public final P entranceLocal;
        /** Teleports since the last reached waypoint. */
        int tpSinceWp = 0;
        /** A teleport happened since the last trail sample. */
        boolean tpSinceCapture = false;
        /** Player trail, local {x, y, z, activeMs, yaw, pitch, teleportFlag}; captured while diff or adaptive is on. */
        final java.util.List<double[]> userTrail = new java.util.ArrayList<>();
        /** Target chests of this room the player broke: {activeMs, lx, ly, lz}. */
        final java.util.List<double[]> userBreaks = new java.util.ArrayList<>();
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
        public volatile int cursor = 0;
        /** When the cursor's trigger is gone, the nearest remaining chest in its area to aim at instead; else null. */
        public volatile P retargetPos = null;
        /** Per-waypoint target chests within AREA_R at solve time. */
        int[] areaTotal;
        int stuckCursor = -1;
        int stuckTicks = 0;
        int lastAreaRemaining = 0;
        /** Waypoint index the missed-rule state below belongs to. */
        int missCursor = -1;
        double lastWpDist = 0;
        /** Active clock when the distance to the waypoint started growing; -1 if not receding. */
        long recedingSinceMs = -1;
        boolean wpWasAhead = false;
        /** Active clock of the last tracked chest break. */
        long lastBreakMs = -60_000;
        /** Active clock of the last missed/spent skip. */
        long lastSkipMs = -60_000;
        int retargetLoggedCursor = -1;
        /** Cache key of {@link #connDijk} (render thread only). */
        public int connCursor = -1;
        /** Cached Dijkstra {dist, prev} from the connector target node (render thread only). */
        public int[][] connDijk = null;
        long startActiveMs;
        long lastWpActiveMs;
        double prevCumDist = 0;
        int goneAtLastWp = 0;
        /** Hallway blocks travelled to reach this room. */
        double hallwayIn = 0;

        SolvedRoute(long cellKey, int ox, int oy, int oz, String roomId, String targetType,
                    RoutePlan plan, java.util.List<BlockPos> targetsWorld, java.util.List<P> otherChestsLocal,
                    java.util.List<String> otherChestIds, P exitLocal, SolidGrid grid, RoutePlanner.Params params) {
            this(cellKey, ox, oy, oz, roomId, targetType, plan, targetsWorld, otherChestsLocal, otherChestIds, exitLocal, grid, params, null);
        }

        SolvedRoute(long cellKey, int ox, int oy, int oz, String roomId, String targetType,
                    RoutePlan plan, java.util.List<BlockPos> targetsWorld, java.util.List<P> otherChestsLocal,
                    java.util.List<String> otherChestIds, P exitLocal, SolidGrid grid, RoutePlanner.Params params, P entranceLocal) {
            this.entranceLocal = entranceLocal;
            this.cellKey = cellKey;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.roomId = roomId;
            this.targetType = targetType;
            this.plan = plan;
            this.plannedOrder = plan.waypoints == null ? java.util.List.of() : java.util.List.copyOf(plan.waypoints);
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
