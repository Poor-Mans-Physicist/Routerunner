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
 * Orchestrates per-room route solving and following. Detects the player's grid cell (and the room a
 * hallway leads to) via the verified checkerboard layout, solves once the cell's chunks are fully
 * loaded (so a partial/misleading route is never shown), runs the solver off the main thread, then
 * advances a follow-cursor and feeds the logger as the player mines. One room at a time.
 */
public final class RouteService {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long NO_CELL = Long.MIN_VALUE;
    private static final double AREA_R = 7.0; // radius (blocks) of a waypoint's "area"; cleared here = advance past it
    private static final int STUCK_TICKS = 100; // ~5s of no local progress → give up on an unreachable leftover
    private static final int FORWARD_SKIP_WINDOW = 6;   // how many waypoints ahead we look for forward progress
    private static final int FORWARD_SKIP_MIN = 3;      // need at least this many ahead to judge
    private static final double FORWARD_SKIP_FRAC = 0.65; // if this fraction ahead is already clear, skip the straggler
    private static final double AREA_DONE_FRAC = 0.85;  // a waypoint whose original cluster is >=85% gone is "done" — kill it
    // ---- path continuer (see #maybeSkipMissed): when a waypoint has been FLOWN PAST, drop it and carry on to the
    // plan's next one. Every constant here exists to make it fire rarely — it must never trigger on an approach.
    private static final double MISSED_MIN_SPEED = 2.0;      // blk/s: below this you're manoeuvring, not flying past
    private static final int MISSED_ANGLE_DEG = 125;         // how far off the momentum heading counts as "behind you"
    private static final double MISSED_ANGLE_COS = Math.cos(Math.toRadians(MISSED_ANGLE_DEG));
    private static final long MISSED_RECEDE_MS = 300;        // ...and it must have been getting FURTHER away this long
    private static final long MISSED_BREAK_QUIET_MS = 300;   // ...with no chest broken since (you're still working it)
    private static final double MISSED_GONE_FRAC = 0.25;     // a full, untouched cluster is never "missed" — go back for it
    private static final double RECEDE_EPS = 1.0e-3;         // distance growth that counts as receding (blocks)
    private static final double MOMENTUM_MIN = 0.05;         // blocks/tick below which there is no momentum heading
    private static final double SPENT_GONE_FRAC = 0.5;       // trigger gone + this much of the cluster gone = not worth the detour
    private static final long SKIP_COOLDOWN_MS = 2000;       // shared rate limit: one missed/spent skip per this window

    private static final ExecutorService SOLVER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "routerunner-solver");
        t.setDaemon(true);
        return t;
    });

    private static final long STATE_LOG_MIN_MS = 1000; // at most one `state` record a second
    private static final int ACC_MIN_MATCHES = 4;      // fewer matched waypoints than this and accuracy is meaningless
    private static final double ACC_MATCH_R = 2.0;     // a break this close to a planned waypoint counts as that waypoint
    private static final double ACC_SPATIAL_SCALE = 3.0; // avg deviation (blocks) at which the spatial term hits 0

    private static volatile SolvedRoute current = null; // the displayed route, or null
    private static volatile long solvingCell = NO_CELL;  // cell currently being solved (in-flight)
    private static volatile boolean awaitingChunks = false; // near a cell but it isn't fully loaded yet
    private static volatile String lastState = "idle"; // human-readable status for the HUD readout
    private static volatile String lastLoggedState = null; // the state the run log last recorded
    private static long lastStateLogMs = 0;
    private static volatile int lastAccuracyPct = -1; // route-accuracy score of the last ROUTED room, -1 = none yet

    public static SolvedRoute current() { return current; }
    public static boolean isSolving() { return solvingCell != NO_CELL; }

    /**
     * A position discontinuity this tick (see {@link TeleportDetector}): flag it on the live room so the next
     * trail sample, the current reach leg and the adaptive accumulators all know this movement was not travel.
     */
    public static void noteTeleport() {
        SolvedRoute sr = current;
        if (sr == null) return;
        sr.tpSinceWp++;
        sr.tpSinceCapture = true;
    }
    public static boolean isAwaitingChunks() { return awaitingChunks; }
    public static String debugState() { return lastState; }

    /** Route-accuracy score (0-100) of the last routed room scored this vault, or -1 if there isn't one. */
    public static int lastAccuracyPct() { return lastAccuracyPct; }

    /**
     * Set the HUD status line and record the change as a {@code state} event. Throttled to one record a
     * second: the routing states carry the cursor position, so they change every waypoint.
     */
    private static void setState(String state) {
        lastState = state;
        if (state.equals(lastLoggedState)) return;
        long now = System.currentTimeMillis();
        if (now - lastStateLogMs < STATE_LOG_MIN_MS) return;
        lastStateLogMs = now;
        lastLoggedState = state;
        RunLog.state(state);
    }

    public static void reset() {
        if (measuredRoom != null) {
            AdaptiveWeights.get().observeRoom(measuredRoom); // the last room still counts towards the measurements
            finalizeRoomForDiff(measuredRoom);               // don't lose the last room's diff on vault exit
        }
        lastAccuracyPct = -1;
        current = null;
        solvingCell = NO_CELL;
        awaitingChunks = false;
        setState("idle");
        // new vault = new session: forget the dynamic-bail learning
        sessionChests = 0;
        sessionBlocks = 0;
        roomsMeasured = 0;
        sessionHotSpotSum = 0;
        roomsForHotSpot = 0;
        pendingHallwayBlocks = 0;
        lastPlayerPos = null;
        measuredRoom = null;
        measuredCellKey = NO_CELL;
    }

    public static void onClientTick(Level level, Player player) {
        try {
            tick(level, player);
        } catch (Throwable t) {
            LOG.error("[Routerunner] route service tick failed", t);
        }
    }

    private static void tick(Level level, Player player) {
        RouterunnerConfig cfg = RouterunnerConfig.get();
        // Solve when routing is shown, diff mode is on, or the adaptive weights are learning (the last two
        // compute the route but keep it hidden — the renderer gates on routingEnabled alone). All off = no compute.
        if (!cfg.routingEnabled && !cfg.diffRoute && !cfg.adaptiveWeights) {
            if (current != null) reset();
            return;
        }

        BlockPos pp = player.blockPosition();
        int rx = Math.floorDiv(pp.getX(), RoomGeometry.CELL);
        int rz = Math.floorDiv(pp.getZ(), RoomGeometry.CELL);

        // Choose the cell to route: the one you're standing in if it has target chests, otherwise the
        // cell you're heading into (hallway pre-solve). No grid-parity assumption — a "room" is just a
        // cell that contains target chests; tunnels/gaps have none and yield no route.
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
            awaitingChunks = true;
            setState("loading room…");
            return;
        }
        if (total(counts) == 0) {
            awaitingChunks = false;
            setState("no target chests nearby");
            return; // keep any current route showing (e.g. while leaving through a tunnel)
        }
        awaitingChunks = false;

        long cellKey = (((long) chosenRx) << 32) ^ (chosenRz & 0xFFFFFFFFL);

        // Skip-list (labyrinth, no-mine rooms): never route these.
        String roomId = safeRoomId(chosenRx, chosenRz, player);
        if (roomId != null && isSkipped(cfg, roomId)) {
            if (current != null && current.cellKey == cellKey) current = null;
            setState("skipped (" + shortRoom(roomId) + ")");
            return;
        }

        SolvedRoute cur = current;
        if (cur != null && cur.cellKey == cellKey) {
            if (cur.roomId == null && roomId != null) {
                cur.roomId = roomId;            // label logs once the room resolves
                RunLog.roomId(cellKey, roomId); // ...and record it: room_solve already went out with a null id
            }
            // Record YOUR path (even if the route is hidden): the diff scores it, and the adaptive weights
            // measure their speeds off it, so either consumer being on is reason enough to capture.
            if (cfg.diffRoute || cfg.adaptiveWeights) captureTrail(level, cur, player);
            if (cfg.routingEnabled) {
                advance(level, player, cur);
                int wpTotal = cur.plan.waypoints.size();
                String suffix = cur.cursor >= wpTotal ? "done → EXIT" : (Math.min(cur.cursor + 1, wpTotal) + "/" + wpTotal);
                setState("route " + suffix + " [" + cur.targetType + "]");
            } else {
                setState("diff tracking [" + cur.targetType + "]"); // diff-only: solved but hidden, tracking you
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
            awaitingChunks = true;
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
                sr.areaTotal = areaTotals(sr); // baseline cluster size per waypoint (all chests present at solve time)
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
     * Advance the follow-cursor with COVERAGE awareness. A waypoint is "done" only once its whole AREA is
     * cleared (not merely its trigger chest), so one bad mine that consumes a few clustered triggers can't
     * make us skip a section that still has chests. When the cursor lands on a waypoint whose trigger was
     * consumed but whose area isn't clear, we aim at the nearest remaining chest there
     * ({@link SolvedRoute#retargetPos}) so nothing gets abandoned — unless that cluster is already SPENT, in
     * which case the waypoint is dropped and the cursor continues to the plan's next one. The plan itself is
     * never reordered: every rule here can only discard the waypoint in front of you.
     */
    private static void advance(Level level, Player player, SolvedRoute sr) {
        int size = sr.plan.waypoints.size();
        if (sr.cursor >= size) { sr.retargetPos = null; return; }

        long activeMs = MetricsTracker.get().getActiveMs();
        int goneNow = countGone(level, sr);
        // Advance past any waypoint that's DONE — fully clear, >=85% of its original cluster already gone, or down
        // to a mop-up straggler (kills dead/near-dead waypoints so we don't backtrack for one or two leftovers).
        while (sr.cursor < size) {
            boolean cleared = areaMostlyClear(level, sr, sr.cursor);
            if (!cleared && !stragglerDone(level, sr, sr.cursor)) break;
            logReach(level, sr, sr.plan.waypoints.get(sr.cursor), activeMs, goneNow, cleared ? "chain" : "straggler");
        }

        if (sr.cursor >= size) { sr.retargetPos = null; sr.stuckTicks = 0; return; }

        RoutePlan.WP cw = sr.plan.waypoints.get(sr.cursor);

        // Path continuer: you flew past this one and it is receding behind you — drop it, take the next.
        if (RouterunnerConfig.get().missedSkip && maybeSkipMissed(level, player, sr, cw, activeMs, goneNow)) return;

        // Forward-progress skip: if most of the waypoints AHEAD of this straggler are already done, we ran
        // past it (a chain-clear or a run-ahead consumed its neighbours) — skip it instead of doubling back.
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

        // Stuck guard: if this area won't clear (an unreachable leftover chest) and we've made no progress for a
        // while, give up on this waypoint and move on — a single stubborn chest can't freeze the route.
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
            sr.retargetPos = null; // trigger still there — aim at it
            return;
        }
        // Trigger consumed. A cluster that is down to a mop-up, or already half gone, isn't worth the detour:
        // drop the waypoint and continue. Otherwise aim at the nearest leftover so nothing is abandoned.
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
            sr.retargetLoggedCursor = sr.cursor; // the aim point is recomputed every tick; the log wants it once
            RunLog.retarget(sr.cellKey, sr.cursor, rem, gone);
        }
    }

    /**
     * The MISSED-and-receding rule, the heart of the path continuer: you flew past this waypoint, it is behind
     * you and getting further away, so it is discarded and the cursor moves to the plan's NEXT waypoint (never
     * to a different one). Every condition below exists to keep it rare — it must not fire on an approach, on a
     * strafe around a cluster you are still mining, or on a waypoint that was behind you from the start (tour
     * geometry). The heading is MOMENTUM only: look yaw swings constantly and means nothing about travel.
     *
     * @return true if the waypoint was discarded and the cursor has already moved on
     */
    private static boolean maybeSkipMissed(Level level, Player player, SolvedRoute sr, RoutePlan.WP cw,
                                           long activeMs, int goneNow) {
        BlockPos w = world(sr, cw.pos);
        double dx = w.getX() + 0.5 - player.getX(), dz = w.getZ() + 0.5 - player.getZ();
        double dist = Math.hypot(dx, dz);
        if (sr.missCursor != sr.cursor) { // the cursor moved: nothing is known about this waypoint yet
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
        if (vlen < MOMENTUM_MIN || dist < RECEDE_EPS) return false; // no momentum heading = no judgement this tick
        double cos = (v.x * dx + v.z * dz) / (vlen * dist);
        if (cos > 0) sr.wpWasAhead = true; // it was in FRONT of you at some point in this leg

        if (!sr.wpWasAhead) return false;                                  // started behind you — the tour meant that
        if (vlen * 20.0 < MISSED_MIN_SPEED) return false;                  // manoeuvring, not flying past
        if (cos >= MISSED_ANGLE_COS) return false;                         // not far enough behind the heading
        if (sr.recedingSinceMs < 0 || activeMs - sr.recedingSinceMs < MISSED_RECEDE_MS) return false;
        if (activeMs - sr.lastBreakMs < MISSED_BREAK_QUIET_MS) return false; // still mining here
        if (activeMs - sr.lastSkipMs < SKIP_COOLDOWN_MS) return false;       // shared rate limit
        int rem = countRemainingInArea(level, sr, cw.pos);
        double gone = goneFrac(sr, sr.cursor, rem);
        // a FULL, untouched cluster is worth turning around for; a partly-eaten one is not
        if (gone < MISSED_GONE_FRAC && isTargetChestAt(level, w, sr.targetType)) return false;

        LOG.info("[Routerunner] missed waypoint {} ({} blocks back, {}% of its cluster gone) — continuing to the next",
                sr.cursor, String.format(Locale.ROOT, "%.1f", dist), (int) Math.round(100.0 * gone));
        sr.lastSkipMs = activeMs;
        skipWaypoint(sr, cw, "missed", activeMs, goneNow);
        return true;
    }

    /**
     * Discard the cursor's waypoint without crediting a reach and continue to the plan's next one. The leg
     * bookkeeping rolls forward exactly as it does on a reach, so the following leg's distance and cleared
     * count still measure from here; {@link AdaptiveWeights} is deliberately not fed — a skipped leg never
     * ended in a stop, so it carries no stop overhead to measure.
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

    /** Fraction of a waypoint's ORIGINAL cluster already gone; 1.0 when the solve recorded no baseline for it. */
    private static double goneFrac(SolvedRoute sr, int cursor, int remaining) {
        int total = (sr.areaTotal != null && cursor < sr.areaTotal.length) ? sr.areaTotal[cursor] : 0;
        if (total <= 0) return 1.0;
        return clamp01((total - remaining) / (double) total);
    }

    private static void logSkip(SolvedRoute sr, BlockPos pos, String reason) {
        RunLog.skip(sr.cellKey, sr.roomId, sr.targetType, sr.cursor, pos, reason);
    }

    /**
     * Log the current cursor's waypoint and advance the cursor past it. It counts as REACHED unless the player
     * never cleared it themselves — its trigger chest was already gone AND this leg cleared nothing — in which
     * case it was a straggler/run-past and goes out as {@code skip} with {@code skipReason} instead.
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
            // Only a REACHED leg ended in a real stop; a skipped waypoint has no stop overhead to measure.
            // A leg with a teleport in it is not a travel measurement either — its dt says nothing about walking.
            if (sr.tpSinceWp == 0) AdaptiveWeights.get().observeLeg(wp.segMode, plannedDist, dt);
        }
        sr.goneAtLastWp = goneNow;
        sr.lastWpActiveMs = activeMs;
        sr.prevCumDist = wp.cumDist;
        sr.tpSinceWp = 0;
        sr.cursor++;
    }

    /**
     * The STRAGGLER half of the DONE test: the waypoint's trigger chest is already gone and only a
     * {@code stragglerSkip}-sized mop-up is left in its area. This is what stops the cursor sending the player
     * back across a dense room for one to three leftovers a neighbour's chain missed. (The other half is
     * {@link #areaMostlyClear} — cleared, or {@link #AREA_DONE_FRAC} of the original cluster gone.)
     */
    private static boolean stragglerDone(Level level, SolvedRoute sr, int cursor) {
        int skip = Math.max(0, RouterunnerConfig.get().stragglerSkip);
        if (skip <= 0) return false;
        P wp = sr.plan.waypoints.get(cursor).pos;
        if (isTargetChestAt(level, world(sr, wp), sr.targetType)) return false; // trigger still standing — go break it
        return countRemainingInArea(level, sr, wp) <= skip;
    }

    /** True if no target chest remains within {@link #AREA_R} of a waypoint (its cluster is cleared → advance past it). */
    private static boolean areaClear(Level level, SolvedRoute sr, P wpLocal) {
        BlockPos c = world(sr, wpLocal);
        for (BlockPos p : sr.targetsWorld) {
            if (near(p, c, AREA_R) && isTargetChestAt(level, p, sr.targetType)) return false;
        }
        return true;
    }

    /** Count of still-present target chests within {@link #AREA_R} of a waypoint (progress signal for the stuck guard). */
    private static int countRemainingInArea(Level level, SolvedRoute sr, P wpLocal) {
        BlockPos c = world(sr, wpLocal);
        int n = 0;
        for (BlockPos p : sr.targetsWorld) {
            if (near(p, c, AREA_R) && isTargetChestAt(level, p, sr.targetType)) n++;
        }
        return n;
    }

    /** Baseline count of target chests within AREA_R of each waypoint at solve time (all chests present). */
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

    /** A waypoint is "done" if its cluster is fully clear OR >= AREA_DONE_FRAC of its ORIGINAL chests are gone. */
    private static boolean areaMostlyClear(Level level, SolvedRoute sr, int cursor) {
        P wp = sr.plan.waypoints.get(cursor).pos;
        if (areaClear(level, sr, wp)) return true;
        int total = (sr.areaTotal != null && cursor < sr.areaTotal.length) ? sr.areaTotal[cursor] : 0;
        if (total <= 0) return false; // no baseline → fall back to requiring fully clear (handled above)
        return countRemainingInArea(level, sr, wp) <= Math.ceil((1.0 - AREA_DONE_FRAC) * total);
    }

    /** Nearest still-present target chest within AREA_R of the waypoint (returned in local coords), or null. */
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
        if (!level.isLoaded(pos)) return true; // unloaded -> treat as present (don't falsely count cleared)
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
            default: break; // AUTO / ALL
        }
        String resolved = LootListener.get().getResolvedType();
        if (resolved != null) return resolved;
        int best = 0;
        for (int i = 1; i < counts.length; i++) if (counts[i] > counts[best]) best = i;
        return RoomGeometry.TYPES[best];
    }

    private static RoutePlanner.Params buildParams(RouterunnerConfig cfg) {
        RoutePlanner.Params p = new RoutePlanner.Params();
        p.bail = dynamicBail(cfg);
        p.bailAggression = cfg.bailAggression; // fallback (warmup) self-references the room's own hot-spot rate
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
        AdaptiveWeights.get().apply(p, cfg); // measured per-profile scaling on top of the sliders
        return p;
    }

    /** The weight snapshot a solve would run with right now — what {@code vault_enter} stamps into the run log. */
    public static RoutePlanner.Params snapshotParams() {
        return buildParams(RouterunnerConfig.get());
    }

    /**
     * A tracked chest broke: stamp the active clock (the "you are still mining here" guard the missed-waypoint
     * rule waits on) and, if it was one of the current room's targets, record it in that room's
     * {@link SolvedRoute#userBreaks} (active-clock + ROOM-LOCAL position). Kept in memory for the adaptive
     * pass and counted into the room's diff record; nothing is written here.
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

    // ---- dynamic bail (MVT, throughput-first — see NORTH_STAR.md): leave a room when its marginal drops below
    // bailAggression × the OPPORTUNITY rate = the typical hot-spot marginal across rooms (top-quartile of each
    // room's break values). Policy-INDEPENDENT (read off the full greedy curve, not the achieved average), so it
    // doesn't self-reinforce. Loots dense clusters, drops low-value tails, leaves poor rooms fast. Warmup = the
    // room self-references its own hot-spot rate. (sessionChests/Blocks kept only for the info log now.)
    private static double sessionChests = 0;
    private static double sessionBlocks = 0;
    private static int roomsMeasured = 0;
    private static double sessionHotSpotSum = 0; // running sum of per-room hot-spot rates (the opportunity signal for bail)
    private static int roomsForHotSpot = 0;      // rooms folded into the hot-spot average
    private static double pendingHallwayBlocks = 0; // player travel accumulated between rooms
    private static Vec3 lastPlayerPos = null;
    private static SolvedRoute measuredRoom = null;
    private static long measuredCellKey = NO_CELL;

    private static double dynamicBail(RouterunnerConfig cfg) {
        if (cfg.bail > 0.0) return cfg.bail; // manual absolute override
        if (roomsForHotSpot > 0) {
            double avgHot = sessionHotSpotSum / roomsForHotSpot; // opportunity cost of staying = typical hot-spot rate
            return cfg.bailAggression * avgHot;
        }
        return 0.0; // warmup: planRoute falls back to bailAggression × this room's own hot-spot rate
    }

    /** Accumulate hallway travel and roll room totals into the session rate as rooms change. */
    private static void updateBailMetrics(Player player, boolean playerInRoom) {
        Vec3 cur = player.position();
        if (lastPlayerPos != null) {
            double step = Math.hypot(cur.x - lastPlayerPos.x, cur.z - lastPlayerPos.z);
            if (step < 20.0 && !playerInRoom) pendingHallwayBlocks += step; // count only between-room travel
        }
        lastPlayerPos = cur;

        SolvedRoute crt = current;
        if (crt != null && crt.cellKey != measuredCellKey) {
            if (measuredRoom != null) {
                finalizeRoomForBail(measuredRoom);
                AdaptiveWeights.get().observeRoom(measuredRoom); // measure the trail BEFORE the room is scored
                finalizeRoomForDiff(measuredRoom); // log the just-left room's you-vs-solver comparison
            }
            crt.hallwayIn = pendingHallwayBlocks; // the travel that got us to this room
            pendingHallwayBlocks = 0;
            measuredRoom = crt;
            measuredCellKey = crt.cellKey;
        }
    }

    private static void finalizeRoomForBail(SolvedRoute room) {
        double hs = room.plan.hotSpotRate; // this room's opportunity rate → the bail level for subsequent rooms
        if (hs > 0) {
            sessionHotSpotSum += hs;
            roomsForHotSpot++;
        }
        double chests = room.goneAtLastWp; // info only (chests actually cleared while we were here)
        double blocks = room.plan.walkBlocks + room.plan.flyBlocks + room.hallwayIn;
        if (chests > 0 && blocks > 0.5) { sessionChests += chests; sessionBlocks += blocks; roomsMeasured++; }
        LOG.info("[Routerunner] room sample: {} chests / {} blocks; hot-spot {} (avg {} over {} rooms)",
                (int) chests, String.format(Locale.ROOT, "%.0f", blocks),
                String.format(Locale.ROOT, "%.3f", hs),
                String.format(Locale.ROOT, "%.3f", roomsForHotSpot > 0 ? sessionHotSpotSum / roomsForHotSpot : 0.0),
                roomsForHotSpot);
    }

    // ---- diff-route: record YOUR path through the room, then score it vs the solver's route on room exit ----

    /**
     * Sample the player's position (local float) + chests-cleared into the current room's diff capture (~10 Hz).
     * Each sample is {@code {lx, ly, lz, activeMs, yaw, pitch}} — the scorer reads only 0..2, the rest is there so
     * a room's trail can be replayed against the {@code pos} stream and its ts/t bounds.
     */
    private static void captureTrail(Level level, SolvedRoute sr, Player player) {
        long ms = MetricsTracker.get().getActiveMs();
        if (ms - sr.lastCaptureMs < 100) return; // ~10 Hz is plenty to score a path
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
        if (gone > sr.userChests) sr.userChests = gone; // chests only clear, so max = cleared while you were here
    }

    /** Score your path vs the solver's WALK route (same cost model) and log one diff record for the room. */
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
            // How far your actual path strayed from the drawn line. With routing ON this is followability (execution
            // wobble/overshoot); with routing OFF it's how spatially different your free route was from the solver's.
            double[] follow = followDeviation(room.userTrail, room.plan.path);
            boolean routing = RouterunnerConfig.get().routingEnabled; // were you FOLLOWING the route, or freehanding?
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

    /**
     * Sum {@link RoutePlanner#scoreTrajectory} over the solver route's GROUND legs — walk and drop — and skip
     * trident/sprint/gap. A drop is part of the floor path a human would actually run, so it scores as walk.
     */
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
     * How well the room's ORIGINAL plan order predicted the order you actually broke chests in, measured
     * against the order plain proximity would have produced. Each planned waypoint is matched to one of your
     * breaks (exact block first, else the nearest unused break within {@link #ACC_MATCH_R} blocks); ρ is the
     * Spearman correlation between planned index and break time, ρ_NN the same for a greedy nearest-neighbour
     * tour from your entry point. The LIFT (ρ − ρ_NN) is what the solver actually contributed — a route that
     * merely reproduces "go to the closest chest" scores zero however well you followed it — and is combined
     * with how closely you hugged the drawn line.
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

    /** The unused break at a planned waypoint: the exact block if you broke it, else the nearest within 2 blocks. */
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

    /**
     * Greedy nearest-neighbour tour over the matched waypoints, starting from where you entered the room —
     * the naive baseline the plan has to beat. Returns each match's position in that tour.
     */
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
            if (best < 0) break; // unreachable: taken[] can't be full before step n
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

    /** How far your path strayed from the drawn route: {avgBlocks, maxBlocks, pct of trail >2 blocks off} (horizontal). */
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

    /** A solved, locked route for one cell (positions in LOCAL coords + the world origin to transform). */
    public static final class SolvedRoute {
        public final long cellKey;
        public final int ox, oy, oz;
        public volatile String roomId;
        public final String targetType;
        public final RoutePlan plan;
        /** The plan's waypoint order frozen at solve time. The follow-cursor only ever discards the waypoint in
         *  front of it, so this matches the live list; it is kept so the accuracy scorer can never be read
         *  against a mutated plan. */
        public final java.util.List<RoutePlan.WP> plannedOrder;
        public final java.util.List<BlockPos> targetsWorld;
        /** Non-target chests and strongboxes in the cell at solve time, LOCAL, with their block ids (log only). */
        public final java.util.List<P> otherChestsLocal;
        public final java.util.List<String> otherChestIds;
        public final P exitLocal;
        public final SolidGrid grid; // retained for the diff-route path scorer (the real room can't be rebuilt offline)
        public final RoutePlanner.Params params; // the exact weight snapshot this route was solved with
        public volatile long solveMs = -1, geomMs = -1, queueMs = -1; // planRoute wall time, snapshot build time, solver-queue wait
        int tpSinceWp = 0;              // teleports since the last reached waypoint (a leg containing one is not a travel measurement)
        boolean tpSinceCapture = false; // a teleport happened since the last trail sample; the next sample carries the flag
        // diff-route capture (populated only while diff mode is on) — YOUR path through this room + real time/chests
        final java.util.List<double[]> userTrail = new java.util.ArrayList<>(); // local {x,y,z,activeMs,yaw,pitch}
        /** Chests of THIS room you actually broke: {activeMs, lx, ly, lz}. Kept for the adaptive pass. */
        final java.util.List<double[]> userBreaks = new java.util.ArrayList<>();
        java.util.Set<BlockPos> targetSet = null; // lazily-built membership index over targetsWorld
        int userChests = 0;            // max chests gone from this room while you were in it
        long userFirstMs = 0, userLastMs = 0, lastCaptureMs = 0; // active-time bounds + capture throttle
        long firstTs = 0, lastTs = 0;  // wall-clock bounds of your time in the room (slices the pos/break streams)
        double lastTrailX, lastTrailZ; // last recorded point (for move-distance dedupe)
        boolean haveTrail = false;
        public volatile int cursor = 0;
        public volatile P retargetPos = null; // if the cursor's trigger was consumed, aim here (nearest remaining) instead
        int[] areaTotal;               // per-waypoint: target chests originally within AREA_R (baseline for the %-done skip)
        int stuckCursor = -1;          // cursor value the stuck-guard is tracking
        int stuckTicks = 0;            // ticks with no local progress on the current area
        int lastAreaRemaining = 0;     // remaining chests in the current area at last progress
        int missCursor = -1;           // waypoint index the missed-rule tracking below belongs to
        double lastWpDist = 0;         // last horizontal distance to it (the receding test)
        long recedingSinceMs = -1;     // active clock when that distance started growing; -1 = not receding
        boolean wpWasAhead = false;    // it was in FRONT of the player's momentum at some point in this leg
        long lastBreakMs = -60_000;    // active clock of the last tracked chest break (starts "long ago")
        long lastSkipMs = -60_000;     // active clock of the last missed/spent skip (their shared rate limit)
        int retargetLoggedCursor = -1; // waypoint whose retarget has already gone into the run log
        public int connCursor = -1;    // cursor the connector dijkstra was computed for (render-thread only)
        public int[][] connDijk = null; // cached {dist,prev} from the current target node, for the from-player connector
        long startActiveMs;
        long lastWpActiveMs;
        double prevCumDist = 0;
        int goneAtLastWp = 0;
        double hallwayIn = 0; // hallway blocks travelled to reach this room (for dynamic bail)

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
