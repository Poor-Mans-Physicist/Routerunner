package com.routerunner;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adaptive room picker: chooses which door to leave a room by, so the route heads for the best unvisited rooms
 * instead of the one straight ahead. Rooms sit on even grid regions (one 47-block tunnel cell between neighbours).
 * <p>
 * Every loaded room around the player is scanned for its target chest count and clumpiness, and rescanned until it
 * is entered, so chests that arrive with late chunks or Bonus/cascade rounds are counted. The rooms past the doors of
 * the current room and of the room its exit leads to are planned for real on a low-priority background thread (lane
 * planner, one plan per exit, averaged). A room's value is its expected surplus, planned chests minus the running
 * planned rate times its planned time: from those plans where they exist, else from a proxy fitted to the lane
 * planner on synthetic rooms and scaled to this vault's actual plans. Rooms not loaded yet are
 * valued from the vault's alignment-corrected mean times the chunk-alignment factor of their position (the extra
 * Bonus/cascade rounds rooms get where they share chunks with the later-placed east/south tunnels). The exit is the
 * first step of the best path over {@code depth} rooms, where tunnels and already visited rooms cost walking time.
 * Each path ends with a rollout: the best discounted straight run of {@code tailK} more rooms from its last room, in
 * any direction, priced the same way, so paths that lead into looted pockets lose to paths that open onto fresh
 * (preferably dense-line) rooms. After {@code fallbackPasses} walk-throughs in a row the picker stops scoring and
 * takes the shortest way to the nearest fresh room, so it can never circle in a corner. Parameters come from
 * {@code assets/routerunner/roompicker.json} (research/2026-09-26_adaptive, research/2026-09-27_picker_v2).
 */
public final class RoomPicker {
    private static final Logger LOG = LogUtils.getLogger();

    /** Wall indices as in {@link RoomGeometry}: 0 = west, 1 = east, 2 = north, 3 = south. */
    public static final String[] WALL_NAMES = {"W", "E", "N", "S"};
    private static final int[] DX = {-2, 2, 0, 0};
    private static final int[] DZ = {0, 0, -2, 2};
    private static final int[] OPP = {1, 0, 3, 2};
    private static final int ESCAPE_MAX = 6;
    /**
     * Block entities of the_vault challenges whose rooms are no-modify zones from generation or attach until the
     * challenge ends. X-mark is left out: it only locks when its trap rolls after activation.
     */
    private static final Set<String> LOCKING_CHALLENGES = Set.of(
            "RaidControllerBlockEntity", "RaidControllerProxyBlockEntity",
            "TempleControllerTileEntity", "TempleControllerProxyBlockEntity",
            "WildWestControllerTileEntity", "WildWestControllerProxyBlockEntity",
            "EliteControllerBlockEntity", "EliteControllerProxyBlockEntity",
            "MemoryRoomControllerTileEntity",
            "HordeRushControllerBlockEntity", "HordeRushControllerPurchaseBlockEntity",
            "DragonRoomControllerTileEntity", "DragonPathNodeBlockEntity",
            "ProtectVillagerControllerBlockEntity");
    private static final String CHALLENGE_PACKAGE = "iskallia.vault.block.entity.challenge.";
    /**
     * Blocks found only in the labyrinth challenge room (research/2026-09-28_multi/BUGS.md): a room holding at least
     * {@link #LABYRINTH_MIN} of them is banned, since it has no quick way in and out.
     */
    private static final Set<String> LABYRINTH_BLOCKS = Set.of("the_vault:modifier_discovery", "the_vault:alchemy_archive",
            "copycats:copycat_iron_door");
    private static final int LABYRINTH_MIN = 2;
    /** Vault rooms span y 9..55 (VaultGridLayout places 47-block rooms 9 above the region origin). */
    private static final int ROOM_Y0 = 9;
    private static final int WATER_STEP = 3;

    /**
     * One scanned room: target chests, clumpiness (sum of squared touching-group sizes over N), pass-only flag,
     * whether it holds a locking challenge controller (blocks cannot be broken there until the challenge is done; always
     * pass-only), and the sampled fraction of its volume that is water.
     */
    private record Seen(int n, double clump, boolean pass, boolean challenge, double water, boolean banned) {}

    /** A room planned in the background: mean planned time and yield over its exits, for the chest count it had then. */
    private record Planned(double t, double y, int n, int exits) {}

    /** Background lane planning of neighbouring rooms, kept off the route solver's thread. */
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Routerunner-room-picker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** The bundled parameters. */
    private static final class Params {
        int depth = 4;
        double wAlign = 1.0;
        double tunnelS = 2.7;
        double passS = 3.5;
        double overheadS = 2.0;
        double switchMarginS = 5.0;
        double switchMaxProgress = 0.5;
        double aquariumWater = 0.30;
        double floodedMedianWater = 0.15;
        int minWaterRooms = 3;
        int tailK = 8;
        double tailG = 0.8;
        int fallbackPasses = 3;
        double[] chainT, chainY, veinT, veinY;
        double[][] alignN, alignClump;
    }

    /** A decision: the chosen wall, each wall's path value (NaN = closed or the entrance), and what it was based on. */
    public record Decision(int exit, double[] values, int unknownNeighbours, int plannedNeighbours, double lambda, int seenRooms,
                           boolean fallback) {
        public double gainOver(int wall) {
            if (wall < 0 || wall > 3 || Double.isNaN(values[wall])) return Double.POSITIVE_INFINITY;
            return values[exit] - values[wall];
        }
    }

    private static Params params;
    private static final Map<Long, Seen> seen = new HashMap<>();
    private static final Set<Long> visited = new HashSet<>();
    private static final Map<Long, Planned> plans = new HashMap<>();
    /** Rooms queued or being planned, and rooms whose planning failed (by the chest count it failed at). */
    private static final Set<Long> pending = new HashSet<>();
    private static final Map<Long, Integer> failed = new HashMap<>();
    /** Bumped on every reset so plans finishing after a vault change are dropped. */
    private static int epoch = 0;
    private static double planYield = 0, planTime = 0;
    /** Plan and proxy totals over the planned rooms that were scanned first (the proxy's scale to this vault). */
    private static double matchPlanT = 0, matchPlanY = 0, proxyTime = 0, proxyYield = 0;
    private static int planned = 0;
    private static String miner = "chain";
    /** Aquarium detection state, refreshed after every scan: off in water-themed vaults where most rooms are flooded. */
    private static boolean aquariaActive = false;
    private static boolean floodWarned = false;
    private static final Set<Long> aquariaLogged = new HashSet<>();
    /** The room cell the player last stood in, whether entering it was a walk-through, and how many in a row. */
    private static long lastCell = Long.MIN_VALUE;
    private static boolean hereIsPass = false;
    private static int consecPass = 0;
    /** Longest walk searched for a fresh room by the fallback, in rooms. */
    private static final int FALLBACK_MAX = 30;

    private RoomPicker() {}

    private static Params params() {
        Params p = params;
        if (p != null) return p;
        try (Reader r = new java.io.InputStreamReader(
                java.util.Objects.requireNonNull(RoomPicker.class.getResourceAsStream("/assets/routerunner/roompicker.json"),
                        "bundled roompicker.json missing"), java.nio.charset.StandardCharsets.UTF_8)) {
            p = new Gson().fromJson(r, Params.class);
        } catch (Exception e) {
            throw new IllegalStateException("bundled room picker parameters unreadable", e);
        }
        params = p;
        return p;
    }

    /** Vault entry/exit: forget every room. */
    public static synchronized void reset() {
        seen.clear();
        visited.clear();
        plans.clear();
        pending.clear();
        failed.clear();
        epoch++;
        planYield = planTime = matchPlanT = matchPlanY = proxyTime = proxyYield = 0;
        planned = 0;
        aquariaActive = false;
        floodWarned = false;
        aquariaLogged.clear();
        lastCell = Long.MIN_VALUE;
        hereIsPass = false;
        consecPass = 0;
    }

    /** The miner the proxy prices rooms for ({@code vein} or anything else = chain). */
    public static synchronized void setMiner(String m) {
        miner = "vein".equals(m) ? "vein" : "chain";
    }

    /**
     * The player stood in this room cell (entered it, looted or not). Entering a room already visited, pass-only or
     * without target chests counts as a walk-through; a fresh room with chests resets the run of walk-throughs.
     */
    public static synchronized void visit(int rx, int rz) {
        long key = DensityTracker.cellKey(rx, rz);
        if (key != lastCell) {
            lastCell = key;
            Seen s = seen.get(key);
            hereIsPass = visited.contains(key) || (s != null && (blocked(s) || s.n <= 0));
            consecPass = hereIsPass ? consecPass + 1 : 0;
        }
        visited.add(key);
    }

    public static synchronized boolean isVisited(int rx, int rz) {
        return visited.contains(DensityTracker.cellKey(rx, rz));
    }

    /** True for a room cell (even region on both axes); odd cells are tunnels. */
    public static boolean isRoomCell(int rx, int rz) {
        return (rx & 1) == 0 && (rz & 1) == 0;
    }

    /**
     * Scan the fully loaded rooms of the 5 x 5 room block around (rx, rz), rescanning each until the player has entered
     * it: a late chunk or a late Bonus/cascade round changes the count, and a changed count makes the room's background
     * plan stale. Clumpiness is only recomputed when the count changed. Main thread.
     */
    public static void observeAround(Level level, int rx, int rz, String targetType) {
        for (int di = -2; di <= 2; di++) {
            for (int dj = -2; dj <= 2; dj++) {
                int x = rx + 2 * di, z = rz + 2 * dj;
                long key = DensityTracker.cellKey(x, z);
                Seen prior;
                synchronized (RoomPicker.class) {
                    if (visited.contains(key)) continue;
                    prior = seen.get(key);
                }
                Seen s = scan(level, x, z, targetType, prior);
                if (s == null || s == prior) continue;
                synchronized (RoomPicker.class) {
                    seen.put(key, s);
                }
                if (s.challenge && (prior == null || !prior.challenge)) {
                    LOG.info("[Routerunner] Room picker: ({},{}) is a challenge room; priced as pass-only ({} target chests ignored).", x, z, s.n);
                } else if (prior != null) {
                    LOG.debug("[Routerunner] Room picker: ({},{}) now has {} target chests (was {}).", x, z, s.n, prior.n);
                }
            }
        }
        refreshAquaria();
    }

    /**
     * Aquarium rooms (two thirds water) are priced as pass-only. Detection needs {@code minWaterRooms} measured rooms and
     * is switched off while the median measured room is at least {@code floodedMedianWater} water (undersea theme).
     */
    private static synchronized void refreshAquaria() {
        Params p = params();
        ArrayList<Double> w = new ArrayList<>();
        for (Seen s : seen.values()) if (!Double.isNaN(s.water)) w.add(s.water);
        if (w.size() < p.minWaterRooms) {
            aquariaActive = false;
            return;
        }
        w.sort(null);
        double median = w.get(w.size() / 2);
        boolean flooded = median >= p.floodedMedianWater;
        if (flooded && !floodWarned) {
            floodWarned = true;
            LOG.warn("[Routerunner] Room picker: median room is {} % water over {} rooms; flooded vault, aquarium rooms are not skipped.",
                    String.format(Locale.ROOT, "%.0f", median * 100), w.size());
        }
        aquariaActive = !flooded;
        if (!aquariaActive) return;
        for (Map.Entry<Long, Seen> e : seen.entrySet()) {
            if (isAquarium(e.getValue()) && aquariaLogged.add(e.getKey())) {
                LOG.info("[Routerunner] Room picker: ({},{}) is an aquarium room ({} % water); priced as pass-only ({} target chests ignored).",
                        (int) (e.getKey() >> 32), (int) (long) e.getKey(), String.format(Locale.ROOT, "%.0f", e.getValue().water * 100), e.getValue().n);
            }
        }
    }

    private static boolean isAquarium(Seen s) {
        return aquariaActive && !Double.isNaN(s.water) && s.water >= params().aquariumWater;
    }

    /** Pass-only: skip-listed, no route, locking challenge, or aquarium. */
    private static boolean blocked(Seen s) {
        return s.pass || s.banned || isAquarium(s);
    }

    private static boolean bannedKey(long key) {
        Seen s = seen.get(key);
        return s != null && s.banned;
    }

    public static int dx(int wall) {
        return DX[wall];
    }

    public static int dz(int wall) {
        return DZ[wall];
    }

    public static int opposite(int wall) {
        return OPP[wall];
    }

    /** True when room (x, z) is scanned, has target chests, is unvisited and has no plan for its current count, queued or done. */
    public static synchronized boolean wantsPlan(int x, int z) {
        long key = DensityTracker.cellKey(x, z);
        Seen s = seen.get(key);
        if (s == null || blocked(s) || s.n <= 0 || visited.contains(key) || pending.contains(key)) return false;
        Integer f = failed.get(key);
        if (f != null && f == s.n) return false;
        Planned pl = plans.get(key);
        return pl == null || pl.n != s.n;
    }

    /** Queue a background plan of room (x, z); {@code job} receives the vault epoch to hand back to {@link #putPlan}. */
    public static void submitPlan(int x, int z, java.util.function.IntConsumer job) {
        int ep;
        synchronized (RoomPicker.class) {
            pending.add(DensityTracker.cellKey(x, z));
            ep = epoch;
        }
        final int fEp = ep;
        PLANNER.submit(() -> {
            try {
                job.accept(fEp);
            } catch (Throwable t) {
                LOG.error("[Routerunner] Room picker: background plan of ({},{}) failed; that room stays priced by the proxy.", x, z, t);
                planFailed(fEp, x, z, -1);
            }
        });
    }

    /** A background plan finished: mean planned time and yield over {@code exits} exits, for a room of {@code n} chests. */
    public static synchronized void putPlan(int ep, int x, int z, double t, double y, int n, int exits) {
        if (ep != epoch) return;
        long key = DensityTracker.cellKey(x, z);
        pending.remove(key);
        plans.put(key, new Planned(t, y, n, exits));
    }

    /** A background plan produced nothing; the room is not retried until its chest count changes. */
    public static synchronized void planFailed(int ep, int x, int z, int n) {
        if (ep != epoch) return;
        long key = DensityTracker.cellKey(x, z);
        pending.remove(key);
        failed.put(key, n);
    }

    /** Number of neighbouring rooms (W, E, N, S of (rx, rz)) with a background plan matching their current count. */
    public static synchronized int plannedNeighbours(int rx, int rz) {
        int k = 0;
        for (int w = 0; w < 4; w++) if (currentPlan(DensityTracker.cellKey(rx + DX[w], rz + DZ[w])) != null) k++;
        return k;
    }

    private static Planned currentPlan(long key) {
        Planned pl = plans.get(key);
        if (pl == null) return null;
        Seen s = seen.get(key);
        return s != null && s.n == pl.n ? pl : null;
    }

    /** Mark a room pass-only (skip-listed or no route): it costs walking time and yields nothing. */
    public static synchronized void markPass(int rx, int rz) {
        long key = DensityTracker.cellKey(rx, rz);
        Seen s = seen.get(key);
        seen.put(key, new Seen(s == null ? 0 : s.n, s == null ? 0 : s.clump, true, s != null && s.challenge, s == null ? Double.NaN : s.water,
                s != null && s.banned));
    }

    /**
     * Ban a room (a skip-listed room id, or the labyrinth): the picker never leaves a room toward it and never plans a
     * path through it. Logged once per room.
     */
    public static synchronized void markBanned(int rx, int rz, String why) {
        long key = DensityTracker.cellKey(rx, rz);
        Seen s = seen.get(key);
        if (s != null && s.banned) return;
        seen.put(key, new Seen(s == null ? 0 : s.n, s == null ? 0 : s.clump, true, s != null && s.challenge, s == null ? Double.NaN : s.water, true));
        LOG.info("[Routerunner] Room picker: ({},{}) is banned ({}); it will not be routed into or through.", rx, rz, why);
    }

    /** True when room (rx, rz) is banned for the picker (labyrinth or skip-listed). */
    public static synchronized boolean isBanned(int rx, int rz) {
        Seen s = seen.get(DensityTracker.cellKey(rx, rz));
        return s != null && s.banned;
    }

    /**
     * True when room (rx, rz) holds a locking challenge (its controller makes the room unbreakable), scanning it now if
     * the picker has not seen it yet. Routing then only shows the way out. Main thread; false when it is not loaded.
     */
    public static boolean isLockingChallengeRoom(Level level, int rx, int rz, String targetType) {
        long key = DensityTracker.cellKey(rx, rz);
        Seen s;
        synchronized (RoomPicker.class) {
            s = seen.get(key);
        }
        if (s == null || !s.challenge) {
            Seen fresh = scan(level, rx, rz, targetType, s);
            if (fresh != null && fresh != s) {
                synchronized (RoomPicker.class) {
                    seen.put(key, fresh);
                }
                s = fresh;
            }
        }
        return s != null && s.challenge;
    }

    /** A room was planned: its plan feeds the running planned rate and the proxy's scale to this vault's plans. */
    public static synchronized void onPlanned(int rx, int rz, double tTotal, int yieldTotal) {
        if (!(tTotal > 0) || yieldTotal <= 0) return;
        Seen s = seen.get(DensityTracker.cellKey(rx, rz));
        Params p = params();
        planYield += yieldTotal;
        planTime += tTotal + p.overheadS + p.tunnelS;
        planned++;
        if (s != null && s.n > 0) {
            double[] pr = rawProxy(s.n, s.clump);
            matchPlanT += tTotal;
            matchPlanY += yieldTotal;
            proxyTime += pr[0];
            proxyYield += pr[1];
        }
    }

    /**
     * Choose the exit of room (rx, rz) entered through {@code entryWall}; {@code open[w]} says whether wall w has a
     * door. Returns null when there is no choice to make (fewer than two open exits) or no rate to price with yet.
     */
    public static synchronized Decision choose(int rx, int rz, int entryWall, boolean[] open) {
        Params p = params();
        double lam = lambda();
        if (!(lam > 0)) {
            LOG.warn("[Routerunner] Room picker has no chest rate to price rooms with yet ({} rooms scanned); leaving ({},{}) by the opposite door.",
                    seen.size(), rx, rz);
            return null;
        }
        double[] base = baseMeans();
        long here = DensityTracker.cellKey(rx, rz);
        if (p.fallbackPasses > 0 && here == lastCell && hereIsPass && consecPass >= p.fallbackPasses) {
            Decision fb = fallback(rx, rz, entryWall, open, lam, p);
            if (fb != null) return fb;
        }
        double[] values = new double[4];
        int best = -1;
        int choices = 0;
        ArrayList<Long> path = new ArrayList<>();
        path.add(here);
        boolean anyBanned = false;
        for (int w = 0; w < 4; w++) {
            values[w] = Double.NaN;
            if (w == entryWall || !open[w]) continue;
            if (bannedKey(DensityTracker.cellKey(rx + DX[w], rz + DZ[w]))) {
                anyBanned = true;
                continue;
            }
            choices++;
            double v = future(rx + DX[w], rz + DZ[w], OPP[w], p.depth - 1, path, lam, base, p);
            values[w] = v;
            boolean straight = entryWall >= 0 && w == OPP[entryWall];
            if (best < 0 || v > values[best] + 1e-9 || (Math.abs(v - values[best]) <= 1e-9 && straight)) best = w;
        }
        if (best < 0 || (choices < 2 && !anyBanned)) return null;
        int unknown = 0;
        for (int w = 0; w < 4; w++) {
            if (w == entryWall || !open[w]) continue;
            if (!seen.containsKey(DensityTracker.cellKey(rx + DX[w], rz + DZ[w]))) unknown++;
        }
        return new Decision(best, values, unknown, plannedNeighbours(rx, rz), lam, seen.size(), false);
    }

    /**
     * After {@code fallbackPasses} walk-throughs in a row: leave by the open door (not the entrance) with the shortest
     * walk to a fresh room (unvisited and not known to be pass-only or empty; unknown rooms count as fresh), ties going
     * straight on. Each other door's value is minus the extra walk. Null when no open door reaches a fresh room within
     * {@link #FALLBACK_MAX} rooms.
     */
    private static Decision fallback(int rx, int rz, int entryWall, boolean[] open, double lam, Params p) {
        int[] dist = new int[4];
        int best = -1;
        for (int w = 0; w < 4; w++) {
            dist[w] = Integer.MAX_VALUE;
            if (w == entryWall || !open[w] || bannedKey(DensityTracker.cellKey(rx + DX[w], rz + DZ[w]))) continue;
            dist[w] = walkToFresh(rx + DX[w], rz + DZ[w], DensityTracker.cellKey(rx, rz));
            boolean straight = entryWall >= 0 && w == OPP[entryWall];
            if (dist[w] == Integer.MAX_VALUE) continue;
            if (best < 0 || dist[w] < dist[best] || (dist[w] == dist[best] && straight)) best = w;
        }
        if (best < 0) {
            LOG.warn("[Routerunner] Room picker: {} walk-throughs in a row at ({},{}) but no door leads to a fresh room within {} rooms; scoring as usual.",
                    consecPass, rx, rz, FALLBACK_MAX);
            return null;
        }
        double[] values = new double[4];
        for (int w = 0; w < 4; w++) {
            values[w] = dist[w] == Integer.MAX_VALUE ? Double.NaN : -lam * (dist[w] - dist[best]) * (p.passS + p.tunnelS);
        }
        LOG.info("[Routerunner] Room picker: {} walk-throughs in a row; heading {} from ({},{}), {} room(s) to the nearest fresh one.",
                consecPass, WALL_NAMES[best], rx, rz, dist[best] + 1);
        return new Decision(best, values, 0, plannedNeighbours(rx, rz), lam, seen.size(), true);
    }

    /** Rooms walked from (x, z) (0 = it is fresh) to the nearest fresh room, never re-entering {@code from}; MAX_VALUE if none. */
    private static int walkToFresh(int x, int z, long from) {
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Long> done = new HashSet<>();
        q.add(new int[]{x, z, 0});
        done.add(DensityTracker.cellKey(x, z));
        done.add(from);
        while (!q.isEmpty()) {
            int[] c = q.poll();
            long k = DensityTracker.cellKey(c[0], c[1]);
            Seen s = seen.get(k);
            if (!visited.contains(k) && !(s != null && (blocked(s) || s.n <= 0))) return c[2];
            if (c[2] >= FALLBACK_MAX) continue;
            for (int w = 0; w < 4; w++) {
                int nx = c[0] + DX[w], nz = c[1] + DZ[w];
                long nk = DensityTracker.cellKey(nx, nz);
                if (!bannedKey(nk) && done.add(nk)) q.add(new int[]{nx, nz, c[2] + 1});
            }
        }
        return Integer.MAX_VALUE;
    }

    /** Chests-per-second equivalent of the switch margin: a new exit must beat the current one by this many chests. */
    public static synchronized double switchMargin() {
        return lambda() * params().switchMarginS;
    }

    /** Fraction of a room's lane runs after which the exit is fixed. */
    public static double switchMaxProgress() {
        return params().switchMaxProgress;
    }

    /** Target chest count of a scanned room, or -1. */
    public static synchronized int seenCount(int rx, int rz) {
        Seen s = seen.get(DensityTracker.cellKey(rx, rz));
        return s == null ? -1 : s.n;
    }

    public static synchronized double lambdaNow() {
        return lambda();
    }

    private static double lambda() {
        Params p = params();
        if (planned > 0 && planTime > 0) return planYield / planTime;
        double[] base = baseMeans();
        if (base == null) return Double.NaN;
        double[] pr = proxy(base[0], base[1]);
        return pr[1] / (pr[0] + p.overheadS + p.tunnelS);
    }

    /** Alignment-corrected mean chest count and clumpiness of the scanned rooms, or null before any. */
    private static double[] baseMeans() {
        double sn = 0, sc = 0;
        int k = 0;
        for (Map.Entry<Long, Seen> e : seen.entrySet()) {
            Seen s = e.getValue();
            if (blocked(s) || s.n <= 0) continue;
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            sn += s.n / alignN(x, z);
            sc += Math.max(1.0, s.clump) / alignClump(x, z);
            k++;
        }
        if (k == 0) return null;
        return new double[]{sn / k, sc / k};
    }

    /** Proxy {time, yield} scaled to this vault's actual plans (prior: two 30 s, 1000-chest rooms at scale 1). */
    private static double[] proxy(double n, double clump) {
        double[] r = rawProxy(n, clump);
        double ts = (matchPlanT + 60.0) / (proxyTime + 60.0);
        double ys = (matchPlanY + 2000.0) / (proxyYield + 2000.0);
        return new double[]{r[0] * ts, r[1] * ys};
    }

    private static double[] rawProxy(double n, double clump) {
        if (n < 1) return new double[]{0, 0};
        Params p = params();
        boolean vein = "vein".equals(miner);
        double[] ct = vein ? p.veinT : p.chainT;
        double[] cy = vein ? p.veinY : p.chainY;
        double ln = Math.log(n), lc = Math.log(Math.max(1.0, clump));
        return new double[]{Math.exp(ct[0] + ct[1] * ln + ct[2] * lc), Math.exp(cy[0] + cy[1] * ln + cy[2] * lc)};
    }

    /** Estimated surplus of looting room (x, z); NaN for a pass-only room. */
    private static double estimate(int x, int z, double lam, double[] base, Params p) {
        long key = DensityTracker.cellKey(x, z);
        Seen s = seen.get(key);
        Planned pl = currentPlan(key);
        if (pl != null && !blocked(s)) return pl.y - lam * (pl.t + p.overheadS);
        double n, c;
        if (s != null) {
            if (blocked(s) || s.n <= 0) return Double.NaN;
            n = s.n;
            c = s.clump;
        } else {
            if (base == null) return 0.0;
            n = base[0] * Math.pow(alignN(x, z), p.wAlign);
            c = base[1] * Math.pow(alignClump(x, z), p.wAlign);
        }
        double[] pr = proxy(n, c);
        return pr[1] - lam * (pr[0] + p.overheadS);
    }

    private static double future(int x, int z, int entry, int d, List<Long> path, double lam, double[] base, Params p) {
        long key = DensityTracker.cellKey(x, z);
        if (bannedKey(key)) return Double.NEGATIVE_INFINITY;
        double v = -lam * p.tunnelS;
        boolean been = visited.contains(key) || path.contains(key);
        double here;
        if (been) {
            here = -lam * p.passS;
        } else {
            double e = estimate(x, z, lam, base, p);
            here = Double.isNaN(e) ? -lam * p.passS : e;
        }
        path.add(key);
        try {
            if (d <= 0) return v + here + (p.tailK > 0 ? tail(x, z, path, lam, base, p) : escape(x, z, path, lam, p));
            double best = Double.NEGATIVE_INFINITY;
            for (int w = 0; w < 4; w++) {
                if (w == entry) continue;
                best = Math.max(best, future(x + DX[w], z + DZ[w], OPP[w], d - 1, path, lam, base, p));
            }
            return v + here + best;
        } finally {
            path.remove(path.size() - 1);
        }
    }

    /**
     * Rollout value of a path ending at (x, z): the best, over the four directions, of a straight run of
     * {@code tailK} more rooms, each room's surplus (or the pass cost for visited, on-path or pass-only rooms) minus a
     * tunnel, discounted by {@code tailG} per room.
     */
    private static double tail(int x, int z, List<Long> path, double lam, double[] base, Params p) {
        double best = Double.NEGATIVE_INFINITY;
        for (int w = 0; w < 4; w++) {
            double val = 0, disc = 1;
            for (int k = 1; k <= p.tailK; k++) {
                disc *= p.tailG;
                int nx = x + DX[w] * k, nz = z + DZ[w] * k;
                long key = DensityTracker.cellKey(nx, nz);
                if (bannedKey(key)) break;
                double here;
                if (visited.contains(key) || path.contains(key)) {
                    here = -lam * p.passS;
                } else {
                    double e = estimate(nx, nz, lam, base, p);
                    here = Double.isNaN(e) ? -lam * p.passS : e;
                }
                val += disc * (here - lam * p.tunnelS);
            }
            best = Math.max(best, val);
        }
        return best;
    }

    /** Walking cost from (x, z) to the nearest room that is neither visited, on the path, nor pass-only (BFS, capped). */
    private static double escape(int x, int z, List<Long> path, double lam, Params p) {
        ArrayDeque<long[]> q = new ArrayDeque<>();
        Set<Long> done = new HashSet<>();
        q.add(new long[]{x, z, 0});
        done.add(DensityTracker.cellKey(x, z));
        while (!q.isEmpty()) {
            long[] c = q.poll();
            if (c[2] >= ESCAPE_MAX) break;
            for (int w = 0; w < 4; w++) {
                int nx = (int) c[0] + DX[w], nz = (int) c[1] + DZ[w];
                long k = DensityTracker.cellKey(nx, nz);
                if (!done.add(k)) continue;
                Seen s = seen.get(k);
                boolean dead = visited.contains(k) || path.contains(k) || (s != null && (blocked(s) || s.n <= 0));
                if (!dead) return -lam * c[2] * (p.passS + p.tunnelS);
                q.add(new long[]{nx, nz, c[2] + 1});
            }
        }
        return -lam * ESCAPE_MAX * (p.passS + p.tunnelS);
    }

    private static int alignIdx(int region) {
        return Math.floorMod(region * RoomGeometry.CELL, 16) / 2;
    }

    private static double alignN(int x, int z) {
        return params().alignN[alignIdx(x)][alignIdx(z)];
    }

    private static double alignClump(int x, int z) {
        return params().alignClump[alignIdx(x)][alignIdx(z)];
    }

    /**
     * Count a fully loaded room's target chests and their clumpiness (26-neighbour touching groups), look for a
     * locking the_vault challenge controller, and sample its water on first sight; null if not loaded. Returns {@code prior} itself when nothing changed.
     */
    private static Seen scan(Level level, int rx, int rz, String targetType, Seen prior) {
        int ox = rx * RoomGeometry.CELL, oz = rz * RoomGeometry.CELL;
        int cx0 = ox >> 4, cx1 = (ox + RoomGeometry.CELL - 1) >> 4, cz0 = oz >> 4, cz1 = (oz + RoomGeometry.CELL - 1) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) return null;
            }
        }
        Set<Long> pts = new HashSet<>();
        boolean challenge = prior != null && prior.challenge;
        Set<String> labyrinth = new HashSet<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = e.getKey();
                    if (pos.getX() < ox || pos.getX() >= ox + RoomGeometry.CELL || pos.getZ() < oz || pos.getZ() >= oz + RoomGeometry.CELL) continue;
                    if (isLockingChallenge(e.getValue())) challenge = true;
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(e.getValue().getBlockState().getBlock());
                    if (id == null) continue;
                    String s = id.toString();
                    if (LABYRINTH_BLOCKS.contains(s)) labyrinth.add(s);
                    if (s.contains(targetType) && !s.contains("strongbox")) pts.add(pos.asLong());
                }
            }
        }
        int n = pts.size();
        boolean banned = (prior != null && prior.banned) || labyrinth.size() >= LABYRINTH_MIN;
        if (banned && (prior == null || !prior.banned)) {
            LOG.info("[Routerunner] Room picker: ({},{}) is the labyrinth ({}); banned, it will not be routed into or through.", rx, rz, labyrinth);
        }
        if (prior != null && prior.n == n && prior.challenge == challenge && prior.banned == banned && !Double.isNaN(prior.water)) return prior;
        boolean pass = challenge || banned || (prior != null && prior.pass);
        double water = prior != null && !Double.isNaN(prior.water) ? prior.water : waterFraction(level, ox, oz);
        if (n == 0) return new Seen(0, 0, pass, challenge, water, banned);
        Set<Long> done = new HashSet<>();
        long sumSq = 0;
        ArrayDeque<Long> q = new ArrayDeque<>();
        for (long start : pts) {
            if (!done.add(start)) continue;
            q.add(start);
            long size = 0;
            while (!q.isEmpty()) {
                BlockPos c = BlockPos.of(q.poll());
                size++;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long k = BlockPos.asLong(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                            if (pts.contains(k) && done.add(k)) q.add(k);
                        }
                    }
                }
            }
            sumSq += size * size;
        }
        return new Seen(n, (double) sumSq / n, pass, challenge, water, banned);
    }

    /** Fraction of a room's volume holding water (source, flowing or waterlogged), sampled every {@link #WATER_STEP} blocks. */
    private static double waterFraction(Level level, int ox, int oz) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int wet = 0, all = 0;
        for (int x = ox + 1; x < ox + RoomGeometry.CELL; x += WATER_STEP) {
            for (int z = oz + 1; z < oz + RoomGeometry.CELL; z += WATER_STEP) {
                for (int y = ROOM_Y0 + 1; y < ROOM_Y0 + RoomGeometry.CELL; y += WATER_STEP) {
                    all++;
                    if (level.getFluidState(m.set(x, y, z)).is(FluidTags.WATER)) wet++;
                }
            }
        }
        return (double) wet / all;
    }

    private static boolean isLockingChallenge(BlockEntity be) {
        String name = be.getClass().getName();
        return name.startsWith(CHALLENGE_PACKAGE) && LOCKING_CHALLENGES.contains(name.substring(CHALLENGE_PACKAGE.length()));
    }

    /** Compact text of a decision's wall values, e.g. {@code W:-12.3 E:40.1 N:- S:entry}. */
    public static String describe(Decision d, int entryWall) {
        StringBuilder sb = new StringBuilder();
        for (int w = 0; w < 4; w++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(WALL_NAMES[w]).append(':');
            if (w == entryWall) sb.append("entry");
            else if (Double.isNaN(d.values()[w])) sb.append('-');
            else sb.append(String.format(Locale.ROOT, "%.1f", d.values()[w]));
        }
        return sb.toString();
    }
}
