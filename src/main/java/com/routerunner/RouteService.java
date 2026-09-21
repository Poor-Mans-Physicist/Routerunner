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
            AdaptiveWeights.get().observeRoom(measuredRoom);
            finalizeRoomForDiff(measuredRoom);
        }
        lastAccuracyPct = -1;
        current = null;
        solvingCell = NO_CELL;
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
        // diff mode and adaptive weights need a solved route even when routing is hidden
        if (!cfg.routingEnabled && !cfg.diffRoute && !cfg.adaptiveWeights) {
            if (current != null) reset();
            return;
        }

        BlockPos pp = player.blockPosition();
        int rx = Math.floorDiv(pp.getX(), RoomGeometry.CELL);
        int rz = Math.floorDiv(pp.getZ(), RoomGeometry.CELL);

        // route the player's cell if it has target chests, else the cell they're heading into
        int chosenRx = rx, chosenRz = rz;
        int[] counts = RoomGeometry.scanCounts(level, rx, rz);
        boolean playerInRoom = counts != null && total(counts) > 0;
        updateBailMetrics(player, playerInRoom);
        if (counts == null || total(counts) == 0) {
            int[] ahead = aheadCell(player, rx, rz);
            if (ahead[0] != rx || ahead[1] != rz) {
                int[] ac = RoomGeometry.scanCounts(level, ahead[0], ahead[1]);
                if (ac != null && total(ac) > 0) {
                    chosenRx = ahead[0];
                    chosenRz = ahead[1];
                    counts = ac;
                }
            }
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
            if (cfg.diffRoute || cfg.adaptiveWeights) captureTrail(level, cur, player);
            if (cfg.routingEnabled) {
                advance(level, player, cur);
                int wpTotal = cur.plan.waypoints.size();
                String suffix = cur.cursor >= wpTotal ? "done → EXIT" : (Math.min(cur.cursor + 1, wpTotal) + "/" + wpTotal);
                setState("route " + suffix + " [" + cur.targetType + "]");
            } else {
                setState("diff tracking [" + cur.targetType + "]");
            }
            return;
        }

        if (solvingCell == cellKey) {
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

        final String fRoomId = roomId;
        final String fType = targetType;
        final RoutePlanner.Params params = buildParams(cfg);
        solvingCell = cellKey;
        final long submitNs = System.nanoTime();
        SOLVER.submit(() -> {
            try {
                long startNs = System.nanoTime();
                RoutePlan plan = RoutePlanner.planRoute(snap.grid, snap.targetsLocal, snap.entranceLocal, snap.exitLocal, params);
                long doneNs = System.nanoTime();
                SolvedRoute sr = new SolvedRoute(cellKey, snap.ox, snap.oy, snap.oz, fRoomId, fType, plan, snap.targetsWorld,
                        snap.othersLocal, snap.otherIds, snap.exitLocal, snap.grid, params);
                sr.geomMs = fGeomMs;
                sr.queueMs = (startNs - submitNs) / 1_000_000L;
                sr.solveMs = (doneNs - startNs) / 1_000_000L;
                sr.areaTotal = areaTotals(sr);
                sr.startActiveMs = MetricsTracker.get().getActiveMs();
                sr.lastWpActiveMs = sr.startActiveMs;
                current = sr;
                RunLog.roomSolve(sr);
                LOG.info("[Routerunner] Solved {} ({} chests, {} waypoints, {}% planned, chain {}/{}, speed {}) in {} ms (geometry {} ms, queued {} ms).", fRoomId,
                        snap.targetsLocal.size(), plan.waypoints.size(),
                        snap.targetsLocal.isEmpty() ? 0 : (100 * plan.collected / snap.targetsLocal.size()),
                        params.chainRange, params.chainLimit, String.format(Locale.ROOT, "%.3f", params.speedAttr),
                        sr.solveMs, sr.geomMs, sr.queueMs);
            } catch (Throwable t) {
                LOG.error("[Routerunner] solve failed", t);
            } finally {
                solvingCell = NO_CELL;
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

        if (RouterunnerConfig.get().missedSkip && maybeSkipMissed(level, player, sr, cw, activeMs, goneNow)) return;

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
        boolean spent = rem <= Math.max(0, RouterunnerConfig.get().stragglerSkip) || gone >= SPENT_GONE_FRAC;
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
     * forward as a reach would. Does not feed {@link AdaptiveWeights}.
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
            if (sr.tpSinceWp == 0) AdaptiveWeights.get().observeLeg(wp.segMode, plannedDist, dt);
        }
        sr.goneAtLastWp = goneNow;
        sr.lastWpActiveMs = activeMs;
        sr.prevCumDist = wp.cumDist;
        sr.tpSinceWp = 0;
        sr.cursor++;
    }

    /** True if the waypoint's trigger is gone and at most {@code stragglerSkip} chests remain in its area. */
    private static boolean stragglerDone(Level level, SolvedRoute sr, int cursor) {
        int skip = Math.max(0, RouterunnerConfig.get().stragglerSkip);
        if (skip <= 0) return false;
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

    /** Solver weights from config, session bail, Chain Miner tier and player speed, with adaptive scaling applied. */
    private static RoutePlanner.Params buildParams(RouterunnerConfig cfg) {
        RoutePlanner.Params p = new RoutePlanner.Params();
        p.bail = dynamicBail(cfg);
        p.bailAggression = cfg.bailAggression;
        p.tightMult = cfg.tightMult;
        p.narrowMult = cfg.narrowMult;
        p.midMult = cfg.midMult;
        p.clearanceMin = cfg.clearanceMin;
        p.openSatClearance = cfg.openSatClearance;
        p.corePenaltyWeight = cfg.corePenaltyWeight;
        p.proximityBonus = cfg.proximityBonus;
        p.proximityRadius = cfg.proximityRadius;
        p.proximityRadiusOpen = cfg.proximityRadiusOpen;
        p.abovePathWeight = cfg.abovePathWeight;
        p.enclosureWeight = cfg.enclosureWeight;
        p.upCost = cfg.upCost;
        p.downCost = cfg.downCost;
        p.turnWeight = cfg.turnWeight;
        p.turnOpenFactor = cfg.turnOpenFactor;
        p.pathTurnWeight = cfg.pathTurnWeight;
        p.losRequired = cfg.losRequired;
        p.breakReach = cfg.breakReach;
        p.waypointOverhead = cfg.waypointOverhead;
        p.tridentActionCost = cfg.tridentActionCost;
        p.tridentDistWeight = cfg.tridentDistWeight;
        p.tridentMinDist = cfg.tridentMinDist;
        p.shaftMinVertical = cfg.shaftMinVertical;
        p.shaftMaxLen = cfg.shaftMaxLen;
        p.shaftMinSaving = cfg.shaftMinSaving;
        p.shaftMinSavingHoriz = cfg.shaftMinSavingHoriz;
        p.shaftCapVertical = cfg.shaftCapVertical;
        p.shaftCapHoriz = cfg.shaftCapHoriz;
        p.shaftMaxDijkstra = cfg.shaftMaxDijkstra;
        p.dropActionCost = cfg.dropActionCost;
        p.dropHeightWeight = cfg.dropHeightWeight;
        p.dropMaxHeight = cfg.dropMaxHeight;
        p.openSprintWeight = cfg.openSprintWeight;
        p.openSprintMinDist = cfg.openSprintMinDist;
        p.openSprintMinClear = cfg.openSprintMinClear;
        p.openSprintMaxRise = cfg.openSprintMaxRise;
        p.turnaroundDeg = cfg.turnaroundDeg;
        int[] chain = ChainMinerInfo.rangeAndLimit();
        p.chainRange = chain[0];
        p.chainLimit = chain[1];
        p.speedAttr = PlayerSpeed.attribute(Minecraft.getInstance().player);
        AdaptiveWeights.get().apply(p, cfg);
        return p;
    }

    /** The weight snapshot a solve would run with right now. */
    public static RoutePlanner.Params snapshotParams() {
        return buildParams(RouterunnerConfig.get());
    }

    /**
     * A tracked chest broke: stamp {@link SolvedRoute#lastBreakMs} and, if it is one of the current room's
     * targets, append it to {@link SolvedRoute#userBreaks}.
     */
    public static void onChestBroken(BlockPos pos) {
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

    /**
     * Absolute bail for the next solve: the config override if set, else bailAggression × the vault's average
     * hot-spot rate; 0 before any room is measured (the planner then uses the room's own hot-spot rate).
     */
    private static double dynamicBail(RouterunnerConfig cfg) {
        if (cfg.bail > 0.0) return cfg.bail;
        if (roomsForHotSpot > 0) {
            double avgHot = sessionHotSpotSum / roomsForHotSpot;
            return cfg.bailAggression * avgHot;
        }
        return 0.0;
    }

    /** Accumulate hallway travel; on a room change, finalize the previous room for bail, adaptive weights and diff. */
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
                AdaptiveWeights.get().observeRoom(measuredRoom);
                finalizeRoomForDiff(measuredRoom);
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

    /** Score the player's trail against the solver's ground route and log one diff record for the room. */
    private static void finalizeRoomForDiff(SolvedRoute room) {
        if (!RouterunnerConfig.get().diffRoute) return;
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
        /** Teleports since the last reached waypoint. */
        int tpSinceWp = 0;
        /** A teleport happened since the last trail sample. */
        boolean tpSinceCapture = false;
        /** Player trail, local {x, y, z, activeMs, yaw, pitch, teleportFlag}; captured while diff or adaptive is on. */
        final java.util.List<double[]> userTrail = new java.util.ArrayList<>();
        /** Target chests of this room the player broke: {activeMs, lx, ly, lz}. */
        final java.util.List<double[]> userBreaks = new java.util.ArrayList<>();
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
