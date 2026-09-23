package com.routerunner.lane;

import com.routerunner.solver.P;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The in-game state of one solved lane plan: its runs in world space (polyline, floor carpet, the chests in the
 * brush, the vertical shafts along it), the exit walk as a final run, a progress pointer along the run being
 * followed, the follow rules, the off-lane tracer, and the strike heat over the current run's chests.
 * <p>
 * Follow rules (2026-09-22 rewrite after the first in-game test): the pointer {@link #prog} only ever moves
 * forward. Each tick it is advanced to the nearest polyline point within {@link #WINDOW_BLOCKS} ahead of it, but
 * only when the player is within {@link #ON_LANE_DIST} of that point; standing near a point further along for
 * {@link #REJOIN_MS} lets it jump forward to there. A run is done when its brush is empty, or, once the player has
 * actually been on it, when the pointer passes its end or fewer than a few chests remain ahead of the pointer.
 * Leaving the lane never advances it; being further than {@link #OFF_DIST} from every point of the run for
 * {@link #OFF_MS} raises {@link Event#OFF}, which the service answers with a replan from where the player stands.
 * While the player is off the window, a grounded tracer path from the player to the pointer is kept fresh on the
 * solver thread for the renderer.
 */
public final class LaneRoute {
    public static final int DONE_AHEAD = 3;
    public static final double END_RADIUS = 1.5;
    public static final double ON_LANE_DIST = 4.0;
    public static final double WINDOW_BLOCKS = 10.0;
    public static final double HIGHLIGHT_BLOCKS = 14.0;
    public static final long REJOIN_MS = 1500;
    public static final double OFF_DIST = 6.0;
    public static final long OFF_MS = 5000;
    public static final double TRACER_DIST = 4.0;
    public static final long TRACER_MIN_MS = 600;
    public static final long REPLAN_MIN_MS = 1500;
    public static final double STALE_FRAC = 0.25;
    /** Engaged on a run with no pointer progress and no brush chest broken for this long: the run is done. */
    public static final long STALL_MS = 2500;
    /** A smoothed heading change sharper than this splits a run into two display runs. */
    public static final double SPLIT_TURN_DEG = 100.0;

    public static final int SEG_FLAT = 0;
    public static final int SEG_STEEP = 1;
    public static final int SEG_SHAFT = 2;

    public enum Event { NONE, DONE, OFF }

    /** A vertical action on a polyline: a drop, a climb by trident or warp, or a flight between two cells. */
    public static final class Shaft {
        public final BlockPos a, b;
        public final boolean fly;

        Shaft(BlockPos a, BlockPos b, boolean fly) {
            this.a = a;
            this.b = b;
            this.fly = fly;
        }

        public boolean up() {
            return b.getY() > a.getY();
        }
    }

    /** One run: a polyline the player sprints while holding attack, its carpet, the chests it can reach, its shafts. */
    public static final class Run {
        public final List<BlockPos> poly = new ArrayList<>();
        final List<P> polyLocal = new ArrayList<>();
        public final List<BlockPos> carpet = new ArrayList<>();
        /** For each carpet cell, the polyline index that generated it (so the renderer can colour the ground near the pointer). */
        public final List<Integer> carpetIdx = new ArrayList<>();
        public final List<BlockPos> brush = new ArrayList<>();
        public final List<Integer> brushIdx = new ArrayList<>();
        public final List<Integer> brushNearest = new ArrayList<>();
        public final List<Shaft> shafts = new ArrayList<>();
        /** Polyline index where the run's own sweep begins (everything before it is the transition walk). */
        public int laneStart;
        /** The exit walk appended after the last lane. */
        public boolean exit;
        public int yield;
        public double seconds;
        /** Chain triggers this run is planned to fire (a share of its lanes' count when a sharp turn split it). */
        public double nTrig;
        /** Planned travel seconds: {@link #seconds} minus the trigger charge and the fixed turn/flight penalties. */
        public double travelS;
        /** Fixed penalty seconds (turnaround, reversal, flight) inside {@link #seconds}. */
        public double penaltyS;
    }

    public final LanePlanner planner;
    public final LanePlanner.Plan plan;
    public final String mode;
    public final int ox, oy, oz;
    public final List<Run> runs = new ArrayList<>();
    public volatile int cur = 0;
    /** Progress pointer into the current run's polyline; never moves backward. */
    public volatile int prog = 0;
    /** The player has been on the current run at least once. */
    public volatile boolean engaged = false;
    /** The player is not within {@link #ON_LANE_DIST} of the pointer window. */
    public volatile boolean offLane = false;
    /** Distance from the player to the pointer window on the last tick. */
    public volatile double offDist = 0.0;
    public volatile boolean heatDirty = true;
    public volatile Map<BlockPos, Float> heat = Map.of();
    public volatile Map<BlockPos, Float> heatNext = Map.of();
    private long heatMs = -60_000;
    public static final long HEAT_MIN_MS = 80;
    /** Live chests within the chain range that count as "the thick of it" (about the median in dense rooms). */
    public static final double HEAT_DENS_REF = 40.0;
    public volatile List<BlockPos> tracer = null;
    public volatile List<Shaft> tracerShafts = List.of();
    public volatile boolean tracerBusy = false;
    public volatile long tracerMs = -60_000;
    public volatile long lastReplanMs = -60_000;
    public final long createdMs;
    /** Active clock when the current run became current. */
    public volatile long runStartMs;
    /**
     * The next cluster on the current run: live brush chests within {@link #HIGHLIGHT_BLOCKS} of the first live
     * brush chest ahead of the pointer. Anchored at the chest, not the pointer, so it marks where to go from the
     * moment the run becomes current, however far away the player still is.
     */
    public volatile Set<BlockPos> targets = Set.of();
    /** Polyline index the target window is anchored at (the first live brush chest ahead of the pointer), -1 when none. */
    public volatile int targetAnchor = -1;
    /** Why the last {@link Event#DONE} fired: {@code end}, {@code swept}, {@code empty} or {@code stall}. */
    public String lastDone = "";
    private long offSinceMs = -1;
    private long rejoinSinceMs = -1;
    private long lastProgressMs = -1;
    private int lastAliveSeen = -1;

    public LaneRoute(LanePlanner planner, LanePlanner.Plan plan, String mode, int ox, int oy, int oz, long nowMs) {
        this.planner = planner;
        this.plan = plan;
        this.mode = mode;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.createdMs = nowMs;
        this.runStartMs = nowMs;
        for (int[] ks : plan.runs) {
            List<P> polyLocal = new ArrayList<>();
            int yield = 0;
            double seconds = 0.0;
            int nTrig = 0;
            double pen = 0.0;
            int laneStart = 0;
            boolean first = true;
            for (int k : ks) {
                LanePlanner.Lane e = plan.lanes.get(k);
                yield += e.yield;
                seconds += e.tTrans + e.tLane;
                nTrig += e.nTrig;
                pen += e.tPen;
                appendDedup(polyLocal, e.trans);
                if (first) {
                    laneStart = Math.max(0, polyLocal.size() - 1);
                    first = false;
                }
                if (e.cells.size() > 1) appendDedup(polyLocal, e.cells);
            }
            addRun(polyLocal, laneStart, yield, seconds, nTrig, pen, false);
        }
        if (plan.exitPath != null && !plan.exitPath.isEmpty()) {
            List<P> polyLocal = new ArrayList<>();
            appendDedup(polyLocal, plan.exitPath);
            addRun(polyLocal, Math.max(0, polyLocal.size() - 1), 0, plan.tExit, plan.exitTriggers.size(), 0.0, true);
        }
    }

    /** Split a polyline at sharp turns into display runs, each with its share of the yield and time. */
    private void addRun(List<P> polyLocal, int laneStart, int yield, double seconds, int nTrig, double pen, boolean exit) {
        List<List<P>> parts = splitSharp(polyLocal);
        int total = Math.max(1, polyLocal.size());
        int offset = 0;
        for (List<P> part : parts) {
            Run r = new Run();
            r.exit = exit;
            r.polyLocal.addAll(part);
            double frac = parts.size() == 1 ? 1.0 : (double) part.size() / total;
            r.yield = (int) Math.round(yield * frac);
            r.seconds = seconds * frac;
            r.nTrig = nTrig * frac;
            r.penaltyS = pen * frac;
            r.travelS = Math.max(0.0, r.seconds - r.penaltyS - planner.P.triggerS * r.nTrig);
            int ls = laneStart - offset;
            r.laneStart = ls < 0 ? 0 : Math.min(ls, part.size() - 1);
            finish(r);
            offset += part.size() - 1;
        }
    }

    /**
     * Cut a polyline wherever the heading, smoothed over two points either side, changes by more than
     * {@link #SPLIT_TURN_DEG}; the cut point ends one part and starts the next, so a sharp turn is always a run
     * boundary the renderer can show.
     */
    static List<List<P>> splitSharp(List<P> poly) {
        List<List<P>> out = new ArrayList<>();
        if (poly.size() < 5) {
            out.add(new ArrayList<>(poly));
            return out;
        }
        List<P> cur = new ArrayList<>();
        cur.add(poly.get(0));
        for (int i = 1; i < poly.size(); i++) {
            P m = poly.get(i);
            cur.add(m);
            if (i < 2 || i + 2 >= poly.size() || cur.size() < 3) continue;
            P a = poly.get(i - 2), c = poly.get(i + 2);
            double ux = m.x() - a.x(), uz = m.z() - a.z(), vx = c.x() - m.x(), vz = c.z() - m.z();
            double nu = Math.hypot(ux, uz), nv = Math.hypot(vx, vz);
            if (nu < 1e-6 || nv < 1e-6) continue;
            double ang = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, (ux * vx + uz * vz) / (nu * nv)))));
            if (ang > SPLIT_TURN_DEG) {
                out.add(cur);
                cur = new ArrayList<>();
                cur.add(m);
            }
        }
        if (cur.size() >= 2 || out.isEmpty()) out.add(cur);
        else out.get(out.size() - 1).addAll(cur.subList(1, cur.size()));
        return out;
    }

    /** The last polyline index still inside the highlight window ahead of the pointer. */
    public static int hotEndIndex(List<BlockPos> poly, int prog) {
        double acc = 0;
        int end = prog;
        for (int i = prog + 1; i < poly.size(); i++) {
            BlockPos p = poly.get(i - 1), q = poly.get(i);
            acc += Math.hypot(q.getX() - p.getX(), q.getZ() - p.getZ());
            if (acc > HIGHLIGHT_BLOCKS) break;
            end = i;
        }
        return end;
    }

    private static void appendDedup(List<P> poly, List<P> pts) {
        for (P c : pts) if (poly.isEmpty() || !poly.get(poly.size() - 1).equals(c)) poly.add(c);
    }

    /** World polyline, carpet, brush (with the nearest polyline index per chest) and shafts for a run. */
    private void finish(Run r) {
        Set<Long> carpetKeys = new HashSet<>();
        Set<Integer> brushSet = new HashSet<>();
        double R = planner.P.breakReach;
        int Ri = (int) R;
        for (int j = 0; j < r.polyLocal.size(); j++) {
            P c = r.polyLocal.get(j);
            r.poly.add(world(c));
            for (int i : planner.reach(c)) brushSet.add(i);
            for (int dx = -Ri; dx <= Ri; dx++) {
                for (int dz = -Ri; dz <= Ri; dz++) {
                    if (dx * dx + dz * dz > R * R) continue;
                    for (int dy : new int[]{0, 1, -1}) {
                        P q = new P(c.x() + dx, c.y() + dy, c.z() + dz);
                        if (Grid.standable(planner.grid, q)) {
                            if (carpetKeys.add(key(q))) {
                                r.carpet.add(world(q));
                                r.carpetIdx.add(j);
                            }
                            break;
                        }
                    }
                }
            }
        }
        for (int i : brushSet) {
            P c = planner.chests.get(i);
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int j = 0; j < r.polyLocal.size(); j++) {
                double d = Grid.dist(r.polyLocal.get(j), c);
                if (d < bd) { bd = d; best = j; }
            }
            r.brush.add(world(c));
            r.brushIdx.add(i);
            r.brushNearest.add(best);
        }
        r.shafts.addAll(shafts(r.poly));
        runs.add(r);
    }

    private static long key(P p) {
        return (((long) p.x()) << 40) | (((long) (p.y() + 512)) << 20) | (long) (p.z() + 512);
    }

    private BlockPos world(P c) {
        return new BlockPos(ox + c.x(), oy + c.y(), oz + c.z());
    }

    /** Flat, steep (2-3 blocks of height per block walked) or shaft (4+ blocks of height, or a flight). */
    public static int segKind(BlockPos a, BlockPos b) {
        int dy = Math.abs(b.getY() - a.getY());
        double hd = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        if (hd > 1.5 || dy >= 4) return SEG_SHAFT;
        return dy >= 2 ? SEG_STEEP : SEG_FLAT;
    }

    /** Every shaft segment, plus each chain of consecutive steep segments that climbs or drops 4 or more in total. */
    public static List<Shaft> shafts(List<BlockPos> poly) {
        List<Shaft> out = new ArrayList<>();
        int chainStart = -1, chainEnd = -1, chainDy = 0;
        for (int i = 1; i < poly.size(); i++) {
            BlockPos a = poly.get(i - 1), b = poly.get(i);
            int kind = segKind(a, b);
            int dy = b.getY() - a.getY();
            if (kind == SEG_STEEP && (chainStart < 0 || Integer.signum(dy) == Integer.signum(chainDy))) {
                if (chainStart < 0) chainStart = i - 1;
                chainEnd = i;
                chainDy += dy;
                continue;
            }
            if (chainStart >= 0 && Math.abs(chainDy) >= 4) out.add(new Shaft(poly.get(chainStart), poly.get(chainEnd), false));
            chainStart = -1;
            chainDy = 0;
            if (kind == SEG_SHAFT) {
                out.add(new Shaft(a, b, Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()) > 1.5));
            } else if (kind == SEG_STEEP) {
                chainStart = i - 1;
                chainEnd = i;
                chainDy = dy;
            }
        }
        if (chainStart >= 0 && Math.abs(chainDy) >= 4) out.add(new Shaft(poly.get(chainStart), poly.get(chainEnd), false));
        return out;
    }

    public Run current() {
        return cur >= 0 && cur < runs.size() ? runs.get(cur) : null;
    }

    public Run next() {
        return cur + 1 < runs.size() ? runs.get(cur + 1) : null;
    }

    public boolean finished() {
        return cur >= runs.size();
    }

    /**
     * Where the screen-edge arrow points: the polyline point the target window is anchored at, else the current
     * run's first lane point (or its end for the exit walk), null when there is no run to follow.
     */
    public BlockPos indicatorTarget() {
        Run r = shown();
        if (r == null || r.poly.isEmpty()) return null;
        int a = targetAnchor;
        if (r == current() && a >= 0 && a < r.poly.size()) return r.poly.get(a);
        if (r.exit) return r.poly.get(r.poly.size() - 1);
        return r.poly.get(Math.min(Math.max(r.laneStart, prog), r.poly.size() - 1));
    }

    /** The run to draw: the current one, or the exit walk once everything is done. */
    public Run shown() {
        Run r = current();
        if (r == null && !runs.isEmpty()) r = runs.get(runs.size() - 1);
        return r;
    }

    private static double wdist(Vec3 p, BlockPos q) {
        double dx = p.x - (q.getX() + 0.5), dy = p.y - q.getY(), dz = p.z - (q.getZ() + 0.5);
        return Math.sqrt(dx * dx + 0.25 * dy * dy + dz * dz);
    }

    private static double hdist(BlockPos a, BlockPos b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** Chests a run must keep ahead of the pointer to stay worth following: 3, less for a tiny brush. */
    public static int doneAhead(Run r) {
        return Math.min(DONE_AHEAD, Math.max(1, r.brush.size() / 4));
    }

    /** Unbroken chests in the run's brush at or beyond polyline index j. */
    public int aliveAhead(Run r, int j, Predicate<BlockPos> alive) {
        int n = 0;
        for (int i = 0; i < r.brush.size(); i++) if (r.brushNearest.get(i) >= j && alive.test(r.brush.get(i))) n++;
        return n;
    }

    /** Unbroken chests anywhere in the run's brush. */
    public int aliveTotal(Run r, Predicate<BlockPos> alive) {
        int n = 0;
        for (BlockPos b : r.brush) if (alive.test(b)) n++;
        return n;
    }

    /** True when the run's brush has lost more than (1 - STALE_FRAC) of its chests, so it is not worth walking to. */
    public boolean stale(Run r, Predicate<BlockPos> alive) {
        return r.brush.isEmpty() || aliveTotal(r, alive) < STALE_FRAC * r.brush.size();
    }

    /** One client tick of the follow rules for the current run. */
    public Event tick(Player player, Predicate<BlockPos> alive, long nowMs) {
        Run r = current();
        if (r == null || r.poly.isEmpty()) return Event.NONE;
        Vec3 p = player.position();
        int progBefore = prog;
        boolean engagedBefore = engaged;
        int best = prog;
        double bd = Double.MAX_VALUE;
        double acc = 0.0;
        for (int j = prog; j < r.poly.size(); j++) {
            if (j > prog) {
                acc += hdist(r.poly.get(j - 1), r.poly.get(j));
                if (acc > WINDOW_BLOCKS) break;
            }
            double d = wdist(p, r.poly.get(j));
            if (d < bd) { bd = d; best = j; }
        }
        boolean near = bd <= ON_LANE_DIST;
        if (near) {
            prog = Math.max(prog, best);
            engaged = true;
            rejoinSinceMs = -1;
        }
        double dAny = bd;
        int anyBest = best;
        for (int j = 0; j < r.poly.size(); j++) {
            double d = wdist(p, r.poly.get(j));
            if (d < dAny) { dAny = d; anyBest = j; }
        }
        if (!near && dAny <= ON_LANE_DIST && anyBest > prog) {
            if (rejoinSinceMs < 0) rejoinSinceMs = nowMs;
            else if (nowMs - rejoinSinceMs >= REJOIN_MS) {
                prog = anyBest;
                engaged = true;
                rejoinSinceMs = -1;
                near = true;
                bd = dAny;
            }
        } else if (!near) {
            rejoinSinceMs = -1;
        }
        offLane = !near;
        offDist = bd;
        if (near) tracer = null;
        int need = doneAhead(r);
        int aliveNow = aliveTotal(r, alive);
        int anchor = -1;
        for (int i = 0; i < r.brush.size(); i++) {
            int j = r.brushNearest.get(i);
            if (j >= prog && (anchor < 0 || j < anchor) && alive.test(r.brush.get(i))) anchor = j;
        }
        Set<BlockPos> tg = new HashSet<>();
        if (anchor >= 0) {
            int tgEnd = hotEndIndex(r.poly, anchor);
            for (int i = 0; i < r.brush.size(); i++) {
                int j = r.brushNearest.get(i);
                if (j >= anchor && j <= tgEnd && alive.test(r.brush.get(i))) tg.add(r.brush.get(i));
            }
        }
        targets = tg;
        targetAnchor = anchor;
        if (!r.exit && aliveNow < need) {
            lastDone = "empty";
            offSinceMs = -1;
            lastProgressMs = -1;
            return Event.DONE;
        }
        if (engaged) {
            boolean progressed = !engagedBefore || prog > progBefore || (lastAliveSeen >= 0 && aliveNow < lastAliveSeen);
            if (lastProgressMs < 0 || progressed) lastProgressMs = nowMs;
            else if (nowMs - lastProgressMs >= STALL_MS) {
                lastDone = "stall";
                offSinceMs = -1;
                lastProgressMs = -1;
                return Event.DONE;
            }
        }
        lastAliveSeen = aliveNow;
        if (engaged) {
            BlockPos end = r.poly.get(r.poly.size() - 1);
            boolean atEnd = prog >= r.poly.size() - 1
                    || (Math.hypot(p.x - (end.getX() + 0.5), p.z - (end.getZ() + 0.5)) <= END_RADIUS && Math.abs(p.y - end.getY()) <= 2.5);
            if (atEnd) {
                lastDone = "end";
                offSinceMs = -1;
                return Event.DONE;
            }
            if (!r.exit && aliveAhead(r, prog, alive) < need) {
                lastDone = "swept";
                offSinceMs = -1;
                return Event.DONE;
            }
        }
        if (dAny > OFF_DIST) {
            if (offSinceMs < 0) offSinceMs = nowMs;
            else if (nowMs - offSinceMs >= OFF_MS) {
                offSinceMs = -1;
                return Event.OFF;
            }
        } else {
            offSinceMs = -1;
        }
        return Event.NONE;
    }

    /** Move to the next run: the pointer starts at the transition point nearest the player, never further along. */
    public void advance(Player player, long nowMs) {
        cur++;
        engaged = false;
        offSinceMs = -1;
        rejoinSinceMs = -1;
        lastProgressMs = -1;
        lastAliveSeen = -1;
        runStartMs = nowMs;
        targets = Set.of();
        targetAnchor = -1;
        tracer = null;
        tracerShafts = List.of();
        heatDirty = true;
        Run r = current();
        if (r == null || r.poly.isEmpty()) {
            prog = 0;
            offLane = false;
            return;
        }
        Vec3 p = player.position();
        int best = 0;
        double bd = Double.MAX_VALUE;
        int last = Math.min(r.laneStart, r.poly.size() - 1);
        for (int j = 0; j <= last; j++) {
            double d = wdist(p, r.poly.get(j));
            if (d < bd) { bd = d; best = j; }
        }
        prog = best;
        engaged = bd <= ON_LANE_DIST;
        offLane = !engaged;
        offDist = bd;
    }

    /** A fresh tracer is wanted: off the window by more than {@link #TRACER_DIST}, none in flight, not too soon. */
    public boolean tracerDue(long nowMs) {
        return offLane && offDist > TRACER_DIST && !finished() && !tracerBusy && nowMs - tracerMs >= TRACER_MIN_MS;
    }

    /**
     * Compute the tracer from the player's room-local cell to the pointer: grounded and corner-safe, else a straight
     * flight drawn as a shaft. Runs on the solver thread; publishes through the volatile fields.
     */
    public void computeTracer(P playerLocal) {
        Run r = current();
        if (r == null || r.polyLocal.isEmpty()) {
            tracer = null;
            return;
        }
        int j = Math.min(Math.max(prog, 0), r.polyLocal.size() - 1);
        P target = r.polyLocal.get(j);
        P start = Grid.snapInside(planner.grid, playerLocal);
        List<P> path = planner.isNative() ? planner.nativePath(start, target) : null;
        if (path == null && !planner.isNative()) path = Grid.astar(planner.grid, start, target, null);
        if (path == null && !planner.isNative()) path = Grid.flight(planner.grid, start, target, c -> Grid.landings(planner.grid, c), 2);
        if (path == null) {
            path = new ArrayList<>();
            path.add(start);
            path.add(target);
        }
        List<BlockPos> w = new ArrayList<>(path.size());
        for (P c : path) w.add(world(c));
        tracerShafts = shafts(w);
        tracer = w;
    }

    /** Heat wants recomputing: something changed and the last pass is at least {@link #HEAT_MIN_MS} old. */
    public boolean heatDue(long nowMs) {
        return heatDirty && nowMs - heatMs >= HEAT_MIN_MS;
    }

    /**
     * Recompute the heatmap over every unbroken chest in the brush of the run being shown (and of the next run):
     * each chest scores a blend of what a chain from it would fell right now (capped) and how many live chests sit
     * within the chain range of it against a fixed reference, then the drawn value is the geometric mean of that
     * absolute score and its rank within the run, so the best chest of a rich run is red while the best chest of
     * a spent one stays pink.
     */
    public void recomputeHeat(Predicate<BlockPos> alive, long nowMs) {
        heatDirty = false;
        heatMs = nowMs;
        boolean[] remaining = new boolean[planner.chests.size()];
        for (int i = 0; i < remaining.length; i++) remaining[i] = alive.test(world(planner.chests.get(i)));
        heat = heatFor(shown(), remaining);
        heatNext = heatFor(next(), remaining);
    }

    private Map<BlockPos, Float> heatFor(Run r, boolean[] remaining) {
        if (r == null || r.brush.isEmpty()) return Map.of();
        List<Integer> live = new ArrayList<>();
        for (int k = 0; k < r.brush.size(); k++) if (remaining[r.brushIdx.get(k)]) live.add(k);
        if (live.isEmpty()) return Map.of();
        int n = live.size();
        double cap = Math.max(1, planner.chain.limit);
        double[] score = new double[n];
        Integer[] order = new Integer[n];
        for (int j = 0; j < n; j++) {
            int i = r.brushIdx.get(live.get(j));
            double chain = Math.min(cap, planner.chain.clearFrom(i, remaining).size()) / cap;
            double dens = Math.min(1.0, planner.chain.neighbors(i, remaining).size() / HEAT_DENS_REF);
            score[j] = 0.5 * chain + 0.5 * dens;
            order[j] = j;
        }
        java.util.Arrays.sort(order, (u, v) -> Double.compare(score[u], score[v]));
        Map<BlockPos, Float> out = new HashMap<>(n * 2);
        int groupStart = 0;
        for (int pos = 0; pos < n; pos++) {
            if (pos > 0 && score[order[pos]] != score[order[pos - 1]]) groupStart = pos;
            double rank = n == 1 ? 1.0 : (double) groupStart / (n - 1);
            out.put(r.brush.get(live.get(order[pos])), (float) Math.sqrt(rank * score[order[pos]]));
        }
        return out;
    }

    /** Which of the planner's chests still stand, for a replan on the same planner. */
    public boolean[] aliveMask(Predicate<BlockPos> alive) {
        boolean[] mask = new boolean[planner.chests.size()];
        for (int i = 0; i < mask.length; i++) mask[i] = alive.test(world(planner.chests.get(i)));
        return mask;
    }
}
