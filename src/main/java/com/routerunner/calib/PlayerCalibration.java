package com.routerunner.calib;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.routerunner.VaultGate;
import com.routerunner.lane.LegTimeModel;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The player's execution pace against the benchmark (the owner's play the shape model was fitted on), per mining
 * ability (research/2026-09-28_player_calib).
 *
 * <p>Each looted room is priced along the path the player actually walked, with the hits they actually made
 * ({@link com.routerunner.lane.TrailPricer}), and scaled by the benchmark's own real-over-priced ratio at the room's
 * clumpiness ({@link LegTimeModel.Shape#benchK}). The pace is the player's idle-free room time over that benchmark time:
 * 1.0 plays like the benchmark, 2.0 takes twice as long for the same moves and clicks. Route choice drops out, so a
 * player who ignores the route is not called slow for it; the route's worth shows up as the gap between their actual
 * rate and the rate their pace predicts on the route.
 *
 * <p>Also kept: the share of the planned chests collected, relative to the benchmark's coverage, on rooms where the
 * player followed the drawn route, and the mean room switch. All three are decayed sums (half-life {@link #HALF_LIFE}
 * rooms) with a prior worth {@link #PRIOR_ROOMS} benchmark rooms, so the first rooms cannot swing them. Observations
 * wait for the density gate like the adaptive model's and are applied on a pass or dropped on a rejection. Only the sums
 * are saved ({@code config/routerunner/adaptive/player_calibration.json}); nothing per room is kept.
 */
public final class PlayerCalibration {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final double HALF_LIFE = 150.0;
    static final double FORGET = Math.pow(0.5, 1.0 / HALF_LIFE);
    public static final double PRIOR_ROOMS = 10.0;
    /** Benchmark seconds of a typical room, the unit the pace prior is weighted in. */
    static final double PRIOR_ROOM_S = 15.0;
    /** Planned chests of a typical room, the unit the coverage prior is weighted in. */
    static final double PRIOR_ROOM_CHESTS = 300.0;
    /** Rooms a room-level observation needs: chests broken and idle-free seconds. */
    public static final int MIN_CHESTS = 20;
    public static final double MIN_SEC = 2.0;
    /** Room pace outside this band is a misread (a disconnect, a portal), not play. */
    static final double RATIO_MIN = 0.25, RATIO_MAX = 4.0;
    /** A room switch longer than this is a detour or a break, not a switch. */
    public static final double MAX_SWITCH_S = 30.0;
    /** Rooms before the pace is no longer shown as provisional. */
    public static final int SETTLED_ROOMS = 40;
    private static final long WAIT_MS = 3000;

    /** One looted room, as measured at room exit. */
    public record Room(boolean vein, double realS, double idleS, double pricedS, double clump, int chests,
                       double planYield, double planS, boolean followed, double switchS) {}

    /** Decayed sums for one mining ability. */
    public static final class Stats {
        public double sR, sB, sRR, sBB, sRB, w;
        public long rooms;
        public double covGot, covPlanned;
        public long covRooms;
        public double swSum, swW;
        public long rejected;
    }

    private static final class Saved {
        int version = 1;
        Stats chain, vein;
    }

    /** The calibration as it stood at vault entry, for the per-lap "Your Pace" prediction. */
    public record Snapshot(double paceChain, double paceVein, double covChain, double covVein, double swChain, double swVein) {
        public double pace(boolean vein) { return vein ? paceVein : paceChain; }
        public double coverage(boolean vein) { return vein ? covVein : covChain; }
        public double switchS(boolean vein) { return vein ? swVein : swChain; }
    }

    private static Stats chain, vein;
    private static final List<Room> pending = new ArrayList<>();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "routerunner-calib");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private PlayerCalibration() {}

    /** Run a room measurement on the calibration thread (pricing a trail and, when needed, a benchmark plan). */
    public static void submit(Runnable job) {
        WORK.submit(() -> {
            try {
                job.run();
            } catch (Throwable t) {
                LOG.error("[Routerunner] player calibration: a room measurement failed; that room is not counted.", t);
            }
        });
    }

    /** Wait (up to 3 s) for queued room measurements, so vault exit sees the last room. */
    public static void awaitIdle() {
        try {
            WORK.submit(() -> {}).get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.warn("[Routerunner] player calibration: the last room measurement did not finish within {} ms; it may be missing from this vault's laps.", WAIT_MS);
        }
    }

    private static synchronized void ensureLoaded() {
        if (chain != null) return;
        chain = new Stats();
        vein = new Stats();
        Path p = file();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            Saved s = GSON.fromJson(r, Saved.class);
            if (s != null && s.chain != null) chain = s.chain;
            if (s != null && s.vein != null) vein = s.vein;
        } catch (Exception e) {
            LOG.error("[Routerunner] player calibration at {} is unreadable; starting from the benchmark (pace 1.0).", p, e);
        }
    }

    private static Stats stats(boolean v) {
        ensureLoaded();
        return v ? vein : chain;
    }

    /**
     * Seconds the player takes per benchmark second (1.0 = the benchmark). Vein Miner starts from the Chain Miner pace
     * until it has {@link #PRIOR_ROOMS} rooms of its own.
     */
    public static synchronized double pace(boolean v) {
        Stats s = stats(v);
        double center = 1.0;
        if (v && s.rooms < PRIOR_ROOMS) center = paceOf(stats(false), 1.0);
        return paceOf(s, center);
    }

    private static double paceOf(Stats s, double center) {
        double w0 = PRIOR_ROOMS * PRIOR_ROOM_S;
        return (s.sR + w0 * center) / (s.sB + w0);
    }

    /** Relative standard error of the pace (0.05 = 5 %); NaN before three rooms. */
    public static synchronized double paceError(boolean v) {
        Stats s = stats(v);
        if (s.rooms < 3 || !(s.sB > 0)) return Double.NaN;
        double e = s.sR / s.sB;
        double sse = Math.max(0.0, s.sRR - 2 * e * s.sRB + e * e * s.sBB);
        return Math.sqrt(sse) / (e * s.sB);
    }

    /** Room-level R² of real seconds against pace x benchmark seconds; NaN before three rooms. */
    public static synchronized double r2(boolean v) {
        Stats s = stats(v);
        if (s.rooms < 3 || !(s.w > 0) || !(s.sB > 0)) return Double.NaN;
        double e = s.sR / s.sB;
        double sse = Math.max(0.0, s.sRR - 2 * e * s.sRB + e * e * s.sBB);
        double sst = s.sRR - s.sR * s.sR / s.w;
        return sst > 0 ? 1.0 - sse / sst : Double.NaN;
    }

    public static synchronized long rooms(boolean v) {
        return stats(v).rooms;
    }

    /** Chests collected per chest the benchmark collects of the same plan, on rooms the player followed (1.0 = benchmark). */
    public static synchronized double coverage(boolean v) {
        Stats s = stats(v);
        double w0 = PRIOR_ROOMS * PRIOR_ROOM_CHESTS;
        return (s.covGot + w0) / (s.covPlanned + w0);
    }

    /** The player's mean room switch in seconds (the benchmark's until measured). */
    public static synchronized double switchS(boolean v) {
        Stats s = stats(v);
        double sw = LegTimeModel.shape().switchS(v);
        return (s.swSum + PRIOR_ROOMS * sw) / (s.swW + PRIOR_ROOMS);
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(pace(false), pace(true), coverage(false), coverage(true), switchS(false), switchS(true));
    }

    /** The benchmark seconds of a priced room: the price times the benchmark's real-over-priced ratio at its clumpiness. */
    public static double benchmarkSeconds(boolean v, double pricedS, double clump) {
        return pricedS * LegTimeModel.shape().benchK(v, clump);
    }

    /** A measured room: applied now if the vault passed the density gate, held until it decides otherwise. */
    public static synchronized void observe(Room r) {
        if (r.chests() < MIN_CHESTS || !(r.realS() >= MIN_SEC) || !(r.pricedS() > 0)) return;
        VaultGate.State g = VaultGate.state();
        if (g == VaultGate.State.FAIL) return;
        if (g == VaultGate.State.PASS) apply(r);
        else pending.add(r);
    }

    /** The density gate decided: apply the held rooms on a pass, drop them on a rejection. */
    public static synchronized void onGateDecided(boolean pass) {
        if (pass) for (Room r : pending) apply(r);
        pending.clear();
        if (pass) save();
    }

    private static void apply(Room r) {
        Stats s = stats(r.vein());
        double b = benchmarkSeconds(r.vein(), r.pricedS(), r.clump());
        double ratio = r.realS() / b;
        if (!(ratio >= RATIO_MIN && ratio <= RATIO_MAX)) {
            s.rejected++;
            LOG.warn("[Routerunner] player calibration: room pace {} is outside {}-{} (real {} s, benchmark {} s); not counted.",
                    String.format(Locale.ROOT, "%.2f", ratio), RATIO_MIN, RATIO_MAX,
                    String.format(Locale.ROOT, "%.1f", r.realS()), String.format(Locale.ROOT, "%.1f", b));
            return;
        }
        s.sR = FORGET * s.sR + r.realS();
        s.sB = FORGET * s.sB + b;
        s.sRR = FORGET * s.sRR + r.realS() * r.realS();
        s.sBB = FORGET * s.sBB + b * b;
        s.sRB = FORGET * s.sRB + r.realS() * b;
        s.w = FORGET * s.w + 1.0;
        s.rooms++;
        if (r.followed() && r.planYield() > 0) {
            s.covGot = FORGET * s.covGot + r.chests();
            s.covPlanned = FORGET * s.covPlanned + r.planYield() * LegTimeModel.shape().coverage(r.vein());
            s.covRooms++;
        }
        if (r.switchS() > 0 && r.switchS() <= MAX_SWITCH_S) {
            s.swSum = FORGET * s.swSum + r.switchS();
            s.swW = FORGET * s.swW + 1.0;
        }
    }

    /** Vault exit or disconnect: save what the gate has let in. */
    public static synchronized void save() {
        ensureLoaded();
        Path p = file();
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Saved s = new Saved();
            s.chain = chain;
            s.vein = vein;
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(s, w);
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.error("[Routerunner] could not save the player calibration to {}; this session's rooms are not kept.", p, e);
        }
    }

    /** Forget everything measured: back to the benchmark. */
    public static synchronized void reset() {
        chain = new Stats();
        vein = new Stats();
        pending.clear();
        save();
        LOG.info("[Routerunner] player calibration reset to the benchmark.");
    }

    /** One line for the menu, e.g. "Vein pace x0.52 ± 3 % (129 rooms)". */
    public static synchronized String describe(boolean v) {
        long n = rooms(v);
        String who = v ? "Vein" : "Chain";
        if (n == 0) return who + ": benchmark pace (no rooms yet)";
        double p = pace(v);
        double err = paceError(v);
        String e = Double.isNaN(err) ? "" : String.format(Locale.ROOT, " ± %.0f %%", 100 * err);
        return String.format(Locale.ROOT, "%s: speed ×%.2f%s (%d rooms%s)", who, 1.0 / p, e, n, n < SETTLED_ROOMS ? ", provisional" : "");
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("routerunner").resolve("adaptive").resolve("player_calibration.json");
    }
}
