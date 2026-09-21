package com.routerunner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.routerunner.solver.RoutePlanner;
import com.routerunner.solver.SolidGrid;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Per-profile adaptive weights: six solver weights (tight/narrow/mid clearance multipliers, waypoint overhead,
 * sprint per-block cost, drop height cost) re-measured from the player's own trail and applied as clamped
 * multipliers on top of the sliders. A quantity is applied only once its accumulator has enough samples.
 *
 * <p>State lives in {@code config/routerunner/adaptive/<profileName>.json} and accumulates across vaults with
 * exponential forgetting (past its cap, an accumulator is scaled back to the cap). Public entry points are
 * synchronized.
 */
public final class AdaptiveWeights {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Baseline values each adaptive quantity is expressed relative to (the slider defaults). */
    public static final double BASE_TIGHT = 3.9;
    public static final double BASE_NARROW = 1.6;
    public static final double BASE_MID = 1.2;
    public static final double BASE_OVERHEAD = 8.0;
    public static final double BASE_SPRINT = 0.55;
    public static final double BASE_DROP = 4.0;

    /** Clamp range for a measured/baseline multiplier. */
    private static final double MULT_MIN = 0.5;
    private static final double MULT_MAX = 2.0;

    /** Forgetting caps: effective sample counts each accumulator is scaled back to once exceeded. */
    private static final double BIN_CAP = 6000;
    private static final double LEG_CAP = 400;
    private static final double SPRINT_CAP = 2000;
    private static final double DROP_CAP = 200;

    /** Clearance-bin histogram layout: 0-40 blk/s; faster samples land in the top bucket. */
    private static final double BIN_LO = 0.0, BIN_WIDTH = 0.25;
    private static final int BIN_BUCKETS = 160;
    /** Sprint histogram layout: 25-80 blk/s. */
    private static final double SPRINT_LO = 25.0, SPRINT_WIDTH = 0.5;
    private static final int SPRINT_BUCKETS = 110;

    /** Minimum effective samples before each quantity is trusted. */
    private static final double BIN_MIN_N = 600;
    private static final double LEG_MIN_N = 40;
    private static final double SPRINT_MIN_N = 300;
    private static final double DROP_MIN_N = 15;

    /** Trail sample pairs outside this gap (ms) are skipped: same instant, or a pause/teleport. */
    private static final double DT_MIN_MS = 40;
    private static final double DT_MAX_MS = 300;
    /** Samples slower than this (blk/s) are ignored as standing still. */
    private static final double SPEED_MIN = 0.5;
    /** Samples at or above this (blk/s) feed the sprint accumulator. */
    private static final double SPRINT_SPEED = 25.0;
    /** Vertical speed (blk/s) at or below which a sample counts as falling. */
    private static final double FALL_SPEED = -8.0;
    private static final double LEG_MIN_DIST = 1.0, LEG_MAX_DIST = 60.0;
    private static final double LEG_MIN_SEC = 0.1, LEG_MAX_SEC = 15.0;

    private static final int SAVE_EVERY_ROOMS = 10;
    /** Relative change a multiplier needs to raise an {@code adapt} record. */
    private static final double LOG_MULT_EPS = 0.02;

    private static volatile AdaptiveWeights instance;

    private final String profile;
    private final State st;
    private double[] lastLoggedMult = null;
    private int roomsSinceSave = 0;

    private AdaptiveWeights(String profile) {
        this.profile = profile;
        this.st = load(profile);
    }

    /** The accumulator set for the ACTIVE profile, loading (or reloading, after a profile change) as needed. */
    public static synchronized AdaptiveWeights get() {
        String profile = safeProfile();
        AdaptiveWeights a = instance;
        if (a == null || !a.profile.equals(profile)) {
            a = new AdaptiveWeights(profile);
            instance = a;
        }
        return a;
    }

    /**
     * Multiply the six adaptive weights on a freshly-built {@link RoutePlanner.Params} by their measured
     * ratios. Called after every slider value has been copied in; a non-confident quantity leaves its
     * slider value untouched.
     */
    public synchronized void apply(RoutePlanner.Params p, RouterunnerConfig cfg) {
        if (p == null || cfg == null || !cfg.adaptiveWeights) return;
        double[] m = multipliers();
        p.adaptiveOn = true;
        p.tightMult *= m[0];
        p.narrowMult *= m[1];
        p.midMult *= m[2];
        p.waypointOverhead *= m[3];
        p.openSprintWeight *= m[4];
        p.dropHeightWeight *= m[5];
    }

    /** The " | adapt t×1.02 …" tail for the HUD's RR line; empty when nothing is confident yet. */
    public synchronized String hudSuffix() {
        if (!RouterunnerConfig.get().adaptiveWeights) return "";
        double[] m = multipliers();
        double[] meas = measurements();
        StringBuilder sb = new StringBuilder(48);
        appendMult(sb, "t", meas[1], m[0]);
        appendMult(sb, "n", meas[2], m[1]);
        appendMult(sb, "m", meas[3], m[2]);
        appendMult(sb, "o", meas[5], m[3]);
        appendMult(sb, "s", meas[6], m[4]);
        appendMult(sb, "d", meas[7], m[5]);
        return sb.length() == 0 ? "" : " | adapt" + sb;
    }

    private static void appendMult(StringBuilder sb, String tag, double measured, double mult) {
        if (Double.isNaN(measured)) return;
        sb.append(String.format(Locale.ROOT, " %s×%.2f", tag, mult));
    }

    /**
     * Fold one finished room's trail (routed or freehand) into the clearance, sprint and drop accumulators.
     * Raises an {@code adapt} record when a multiplier moved, and saves every
     * {@link #SAVE_EVERY_ROOMS} rooms.
     */
    public synchronized void observeRoom(RouteService.SolvedRoute room) {
        if (room == null) return;
        try {
            List<double[]> trail = room.userTrail;
            if (trail == null || trail.size() < 2) return;
            SolidGrid g = room.grid;
            if (g == null) {
                LOG.error("[Routerunner] adaptive: room {} kept no grid; its clearance bins are skipped this room.",
                        room.roomId);
            }
            double fallHeight = 0, fallSec = 0;
            boolean falling = false;
            for (int i = 1; i < trail.size(); i++) {
                double[] a = trail.get(i - 1), b = trail.get(i);
                double dtMs = b[3] - a[3];
                boolean teleported = b.length > 6 && b[6] > 0;
                if (teleported || dtMs < DT_MIN_MS || dtMs > DT_MAX_MS) {
                    if (falling) commitFall(fallHeight, fallSec);
                    falling = false;
                    fallHeight = 0;
                    fallSec = 0;
                    continue;
                }
                double sec = dtMs / 1000.0;
                double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
                double speed = Math.sqrt(dx * dx + dy * dy + dz * dz) / sec;
                if (dy / sec <= FALL_SPEED) {
                    falling = true;
                    fallHeight += Math.abs(dy);
                    fallSec += sec;
                } else if (falling) {
                    commitFall(fallHeight, fallSec);
                    falling = false;
                    fallHeight = 0;
                    fallSec = 0;
                }
                if (speed < SPEED_MIN) continue;
                if (speed >= SPRINT_SPEED) addSpeed(st.sprint, speed, SPRINT_CAP);
                if (g == null) continue;
                int clr = clearanceAt(g, b);
                if (clr < 0) continue;
                Hist bin = clr <= 1 ? st.tight : (clr <= 3 ? st.narrow : (clr <= 6 ? st.mid : st.open));
                addSpeed(bin, speed, BIN_CAP);
            }
            if (falling) commitFall(fallHeight, fallSec);
            st.rooms++;
            roomsSinceSave++;
            maybeLogAdapt();
            if (roomsSinceSave >= SAVE_EVERY_ROOMS) save();
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] adaptive: failed to fold room {} into the accumulators; it is not counted.",
                    room.roomId, e);
        }
    }

    /**
     * Fold one followed route leg into the walk regression (planned blocks vs seconds taken). Only walk legs
     * inside the distance/duration window count.
     */
    public synchronized void observeLeg(char mode, double plannedDist, long dtMs) {
        if (mode != 'w') return;
        double x = plannedDist;
        double y = dtMs / 1000.0;
        if (x < LEG_MIN_DIST || x > LEG_MAX_DIST) return;
        if (y < LEG_MIN_SEC || y > LEG_MAX_SEC) return;
        st.legs.n += 1;
        st.legs.sx += x;
        st.legs.sy += y;
        st.legs.sxx += x * x;
        st.legs.sxy += x * y;
        forgetLegs();
    }

    private void commitFall(double height, double sec) {
        if (height <= 0 || sec <= 0) return;
        st.drops.n += 1;
        st.drops.sumSqrtH += Math.sqrt(height);
        st.drops.sumSec += sec;
        forgetDrops();
    }

    /** Walls-only clearance at a trail sample, or -1 when the sample rounds outside the room grid. */
    private static int clearanceAt(SolidGrid g, double[] sample) {
        int x = (int) Math.round(sample[0]), y = (int) Math.round(sample[1]), z = (int) Math.round(sample[2]);
        if (x < 0 || y < 0 || z < 0 || x >= g.sx || y >= g.sy || z >= g.sz) return -1;
        return g.clearanceFlyAt(x, y, z);
    }

    /**
     * Every measured quantity, in the order
     * {@code {vOpen, tight, narrow, mid, overheadS, overheadBlocks, sprint, dropW}}. A slot is NaN when its
     * accumulator is below the confidence threshold; everything but {@code vOpen} additionally needs
     * {@code vOpen} itself, because the ratios are all expressed against open-ground speed.
     */
    private double[] measurements() {
        double[] out = {Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        double vOpen = binMedian(st.open);
        out[0] = vOpen;
        if (Double.isNaN(vOpen) || vOpen <= 0) return out;
        out[1] = binRatio(st.tight, vOpen);
        out[2] = binRatio(st.narrow, vOpen);
        out[3] = binRatio(st.mid, vOpen);
        double intercept = legIntercept();
        if (!Double.isNaN(intercept) && intercept > 0) {
            out[4] = intercept;
            out[5] = intercept * vOpen;
        }
        if (total(st.sprint) >= SPRINT_MIN_N) {
            double med = median(st.sprint);
            if (med > 0) out[6] = vOpen / med;
        }
        if (st.drops.n >= DROP_MIN_N && st.drops.sumSqrtH > 0) {
            out[7] = (st.drops.sumSec / st.drops.sumSqrtH) * vOpen;
        }
        return out;
    }

    /** MEDIAN speed in a clearance bin, or NaN below {@link #BIN_MIN_N} samples. */
    private static double binMedian(Hist b) {
        if (b == null || total(b) < BIN_MIN_N) return Double.NaN;
        double med = median(b);
        return med > 0 ? med : Double.NaN;
    }

    /** How many open-ground blocks one block in this bin costs = open speed ÷ the bin's own median speed. */
    private static double binRatio(Hist b, double vOpen) {
        double med = binMedian(b);
        return Double.isNaN(med) ? Double.NaN : vOpen / med;
    }

    /** Add one speed sample, clamping into the end buckets, then forget down to the cap if we passed it. */
    private static void addSpeed(Hist hist, double speed, double cap) {
        int i = (int) Math.floor((speed - hist.lo) / hist.width);
        if (i < 0) i = 0;
        else if (i >= hist.h.length) i = hist.h.length - 1;
        hist.h[i] += 1.0;
        double t = total(hist);
        if (t <= cap) return;
        double k = cap / t;
        for (int b = 0; b < hist.h.length; b++) hist.h[b] *= k;
    }

    private static double total(Hist hist) {
        if (hist == null || hist.h == null) return 0.0;
        double t = 0;
        for (double c : hist.h) t += c;
        return t;
    }

    /**
     * The histogram's median, linearly interpolated inside the bucket holding the half-mass point. NaN when
     * empty.
     */
    private static double median(Hist hist) {
        double t = total(hist);
        if (t <= 0) return Double.NaN;
        double target = t / 2.0, cum = 0;
        for (int i = 0; i < hist.h.length; i++) {
            double c = hist.h[i];
            if (cum + c >= target) {
                double frac = c > 0 ? (target - cum) / c : 0.5;
                return hist.lo + (i + frac) * hist.width;
            }
            cum += c;
        }
        return hist.lo + hist.h.length * hist.width;
    }

    /**
     * Least-squares intercept (seconds) of leg duration against planned distance — the fixed stop/aim/break
     * cost of visiting a waypoint at all, with travel time regressed out. NaN below {@link #LEG_MIN_N} legs
     * or when the distances carry no variance.
     */
    private double legIntercept() {
        double n = st.legs.n;
        if (n < LEG_MIN_N) return Double.NaN;
        double denom = n * st.legs.sxx - st.legs.sx * st.legs.sx;
        if (Math.abs(denom) < 1.0e-9) {
            LOG.error("[Routerunner] adaptive: the walk-leg regression is degenerate ({} legs, no distance spread); waypoint overhead stays on the slider value.", (long) n);
            return Double.NaN;
        }
        double slope = (n * st.legs.sxy - st.legs.sx * st.legs.sy) / denom;
        return (st.legs.sy - slope * st.legs.sx) / n;
    }

    /** The six effective multipliers, in the order {tight, narrow, mid, overhead, sprint, drop}. */
    private double[] multipliers() {
        double[] m = measurements();
        return new double[]{
                mult(m[1], BASE_TIGHT),
                mult(m[2], BASE_NARROW),
                mult(m[3], BASE_MID),
                mult(m[5], BASE_OVERHEAD),
                mult(m[6], BASE_SPRINT),
                mult(m[7], BASE_DROP)};
    }

    private static double mult(double measured, double baseline) {
        if (Double.isNaN(measured) || baseline <= 0) return 1.0;
        double r = measured / baseline;
        return r < MULT_MIN ? MULT_MIN : (r > MULT_MAX ? MULT_MAX : r);
    }

    private void maybeLogAdapt() {
        double[] m = multipliers();
        if (lastLoggedMult != null && !moved(m, lastLoggedMult)) return;
        lastLoggedMult = m.clone();
        RunLog.adapt(profile, st.rooms, counts(), measurements(), m);
    }

    private static boolean moved(double[] now, double[] prev) {
        for (int i = 0; i < now.length; i++) {
            double ref = Math.max(1.0e-9, Math.abs(prev[i]));
            if (Math.abs(now[i] - prev[i]) > LOG_MULT_EPS * ref) return true;
        }
        return false;
    }

    /** Effective sample counts, in the order {tight, narrow, mid, open, legs, sprint, drops}. */
    private long[] counts() {
        return new long[]{Math.round(total(st.tight)), Math.round(total(st.narrow)), Math.round(total(st.mid)),
                Math.round(total(st.open)), Math.round(st.legs.n), Math.round(total(st.sprint)),
                Math.round(st.drops.n)};
    }

    private void forgetDrops() {
        if (st.drops.n <= DROP_CAP) return;
        double k = DROP_CAP / st.drops.n;
        st.drops.n *= k;
        st.drops.sumSqrtH *= k;
        st.drops.sumSec *= k;
    }

    private void forgetLegs() {
        if (st.legs.n <= LEG_CAP) return;
        double k = LEG_CAP / st.legs.n;
        st.legs.n *= k;
        st.legs.sx *= k;
        st.legs.sy *= k;
        st.legs.sxx *= k;
        st.legs.sxy *= k;
    }

    /** Folder holding one accumulator file per movement profile. */
    public static Path adaptiveDir() {
        return FMLPaths.CONFIGDIR.get().resolve("routerunner").resolve("adaptive");
    }

    private static Path fileFor(String profile) {
        return adaptiveDir().resolve(sanitize(profile) + ".json");
    }

    private Path file() {
        return fileFor(profile);
    }

    /** Write the accumulators out (every {@link #SAVE_EVERY_ROOMS} rooms and at vault exit / suspend). */
    public synchronized void save() {
        Path p = file();
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(st, w);
            }
            roomsSinceSave = 0;
        } catch (IOException | RuntimeException e) {
            LOG.error("[Routerunner] failed to write the adaptive weights at {}; this session's measurements are lost.", p, e);
        }
    }

    /** Forget everything measured for this profile and delete its file — the weights screen's "Reset adaptive". */
    public synchronized void reset() {
        Path p = file();
        try {
            Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            LOG.error("[Routerunner] failed to delete the adaptive weights file {}; it will be overwritten on the next save.", p, e);
        }
        st.clear();
        lastLoggedMult = null;
        roomsSinceSave = 0;
        LOG.info("[Routerunner] adaptive weights reset for profile {}; every weight is back on its slider value.", profile);
    }

    private static State load(String profile) {
        Path p = fileFor(profile);
        if (!Files.exists(p)) return new State(profile);
        try (Reader r = Files.newBufferedReader(p)) {
            State s = GSON.fromJson(r, State.class);
            if (s == null) throw new IOException("adaptive file parsed to null");
            s.fill(profile);
            return s;
        } catch (Exception e) {
            LOG.error("[Routerunner] failed to read the adaptive weights at {}; starting this profile from the baseline defaults.", p, e);
            return new State(profile);
        }
    }

    private static String safeProfile() {
        try {
            String n = RouterunnerConfig.get().profileName;
            return n == null || n.isEmpty() ? "default" : n;
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] could not read the profile name for the adaptive weights; using \"default\".", e);
            return "default";
        }
    }

    private static String sanitize(String s) {
        String out = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        return out.isEmpty() ? "default" : (out.length() > 48 ? out.substring(0, 48) : out);
    }

    /** A fixed-bucket speed histogram (blk/s); counts are doubles because forgetting scales them. */
    static final class Hist {
        double lo;
        double width;
        double[] h;

        Hist() {}

        Hist(double lo, double width, int buckets) {
            this.lo = lo;
            this.width = width;
            this.h = new double[buckets];
        }
    }

    /** Least-squares sums for leg duration (s) against planned distance (blocks). */
    static final class Legs {
        double n, sx, sy, sxx, sxy;
    }

    /** Fall time against sqrt(height), one entry per committed fall. */
    static final class Drops {
        double n, sumSqrtH, sumSec;
    }

    /** The whole persisted accumulator set for one profile. */
    static final class State {
        String profile;
        int rooms;
        Hist tight = bin();
        Hist narrow = bin();
        Hist mid = bin();
        Hist open = bin();
        Legs legs = new Legs();
        Hist sprint = sprintHist();
        Drops drops = new Drops();

        State() {}

        State(String profile) {
            this.profile = profile;
        }

        /**
         * Re-create anything a partial or malformed file left out. Histograms with the wrong shape start over;
         * the leg and drop accumulators are kept.
         */
        void fill(String profileName) {
            if (profile == null) profile = profileName;
            tight = fixBin(tight, "tight");
            narrow = fixBin(narrow, "narrow");
            mid = fixBin(mid, "mid");
            open = fixBin(open, "open");
            sprint = fixSprint(sprint);
            if (legs == null) legs = new Legs();
            if (drops == null) drops = new Drops();
        }

        void clear() {
            rooms = 0;
            tight = bin();
            narrow = bin();
            mid = bin();
            open = bin();
            legs = new Legs();
            sprint = sprintHist();
            drops = new Drops();
        }

        private static Hist fixBin(Hist hist, String name) {
            if (hist != null && hist.h != null && hist.h.length == BIN_BUCKETS) {
                hist.lo = BIN_LO;
                hist.width = BIN_WIDTH;
                return hist;
            }
            LOG.error("[Routerunner] adaptive: the {} clearance bin has no usable histogram (pre-median file or a bad edit); it starts over from empty.", name);
            return bin();
        }

        private static Hist fixSprint(Hist hist) {
            if (hist != null && hist.h != null && hist.h.length == SPRINT_BUCKETS) {
                hist.lo = SPRINT_LO;
                hist.width = SPRINT_WIDTH;
                return hist;
            }
            LOG.error("[Routerunner] adaptive: the sprint accumulator has no usable histogram (pre-median file or a bad edit); it starts over from empty.");
            return sprintHist();
        }

        private static Hist bin() {
            return new Hist(BIN_LO, BIN_WIDTH, BIN_BUCKETS);
        }

        private static Hist sprintHist() {
            return new Hist(SPRINT_LO, SPRINT_WIDTH, SPRINT_BUCKETS);
        }
    }
}
