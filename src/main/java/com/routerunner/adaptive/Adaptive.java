package com.routerunner.adaptive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.routerunner.RouterunnerConfig;
import com.routerunner.RunLog;
import com.routerunner.VaultGate;
import com.routerunner.lane.LanePlanner;
import com.routerunner.lane.LegTimeModel;
import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;
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
 * The adaptive model in the game: what the lane planner plans with, what it learns from, and where that is saved.
 *
 * <p>On ({@code adaptiveLearning}): the planner's leg model is the tier-1 fit ({@link LegLearner}) scaled by the
 * tier-0 pace, its per-burst charge is the tier-0 {@code b} ({@link RunCalibration}), and the bail floor needs no
 * model-to-real conversion. Off: the bundled model at pace 1 and the bundled charge, the 0.18.0 behaviour; nothing
 * is learned and the saved state is kept untouched.
 *
 * <p>Tier 0 is kept per mining ability: Chain Miner and Vein Miner each have their own pace and per-burst cost
 * ({@code run_calibration.json}, {@code run_calibration_vein.json}), because a vein hit is a different action from a
 * chain trigger. The vein pace prior is the chain pace learned so far (movement carried over in vein run 1: realized
 * over planned room time 1.05 at the chain pace). Tier 1 (the leg model) is shared and only learns from chain rooms.
 *
 * <p>Learning happens on its own low-priority thread (rows need a path search per leg) so it never delays a solve.
 * Until the vault passes the density gate ({@link VaultGate}) every observation is staged, then applied on a pass
 * or dropped on a rejection. State lives in {@code config/routerunner/adaptive/} and is saved after each room and at
 * vault exit.
 */
public final class Adaptive {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    /** Relative change in pace or per-burst cost that earns a new {@code calib} record. */
    private static final double CALIB_LOG_STEP = 0.03;
    /** Longest the vault exit waits for the last room's learning before closing the log. */
    private static final long EXIT_WAIT_MS = 3000;
    /** Breaks and seconds a lane run needs before its timing counts. */
    private static final int RUN_MIN_BREAKS = 8;
    private static final double RUN_MIN_S = 0.5;
    /**
     * Vein Miner's prior per-hit cost (real seconds): the fit on vein run 1 (vault 2026-09-23 21:51, 125 rooms,
     * room time = 0.10 + 0.0555 x blocks + 0.236 x hits, R2 0.85).
     */
    public static final double VEIN_PRIOR_B = 0.24;

    private static final ExecutorService LEARN = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "routerunner-learn");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final Object LOCK = new Object();
    private static RunCalibration calib;
    private static RunCalibration calibVein;
    private static LegLearner legs;
    private static final List<Runnable> staged = new ArrayList<>();
    private static final double[] loggedA = {Double.NaN, Double.NaN}, loggedB = {Double.NaN, Double.NaN};

    private Adaptive() {}

    private static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("routerunner").resolve("adaptive");
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (calib != null) return;
            LegTimeModel prior = LegTimeModel.forGame(FMLPaths.CONFIGDIR.get().resolve("routerunner").resolve("legmodel.json"));
            legs = new LegLearner(prior);
            calib = new RunCalibration(new LanePlanner.Params().triggerS);
            RunCalibration.State cs = read(dir().resolve("run_calibration.json"), RunCalibration.State.class);
            if (cs != null && !calib.restore(cs)) {
                LOG.warn("[Routerunner] adaptive run calibration was saved against a different per-burst prior; starting it fresh.");
            }
            calibVein = new RunCalibration(calib.a(), VEIN_PRIOR_B);
            RunCalibration.State vs = read(dir().resolve("run_calibration_vein.json"), RunCalibration.State.class);
            if (vs != null && !calibVein.restore(vs)) {
                LOG.warn("[Routerunner] adaptive Vein Miner calibration was saved against a different per-hit prior; starting it fresh.");
            }
            LegLearner.State ls = read(dir().resolve("leg_model.json"), LegLearner.State.class);
            if (ls != null && !legs.restore(ls)) {
                LOG.warn("[Routerunner] adaptive leg model was saved against a different bundled model (or is malformed); starting it fresh from the new prior.");
            }
            LOG.info("[Routerunner] adaptive model loaded: chain pace {}, {} s per burst ({} runs); vein pace {}, {} s per hit ({} runs); {} legs learned.",
                    f3(calib.a()), f3(calib.b()), calib.n(), f3(calibVein.a()), f3(calibVein.b()), calibVein.n(), legs.n());
        }
    }

    public static boolean enabled() {
        return RouterunnerConfig.get().adaptiveLearning;
    }

    private static RunCalibration calibFor(boolean vein) {
        return vein ? calibVein : calib;
    }

    /**
     * The leg model the planner should use now: the adapted fit times the mining ability's pace when on, the
     * bundled model when off.
     */
    public static LegTimeModel planningModel(boolean vein) {
        ensureLoaded();
        if (!enabled()) return legs.prior();
        return legs.model().scaled(calibFor(vein).a());
    }

    /** Seconds the planner charges per chain trigger or vein hit. */
    public static double triggerS(boolean vein) {
        ensureLoaded();
        RunCalibration c = calibFor(vein);
        return enabled() ? c.b() : c.priorB();
    }

    /** The pace folded into {@link #planningModel(boolean)}. */
    public static double pace(boolean vein) {
        ensureLoaded();
        return enabled() ? calibFor(vein).a() : 1.0;
    }

    /**
     * A lane run finished while engaged: fold its realized time into tier 0. {@code travelS} and {@code penaltyS}
     * are the run's planned travel (at the pace it was planned with) and fixed penalties.
     */
    public static void onRunDone(double travelS, double pace, double nTrig, double penaltyS, double realizedS, int breaks,
                                 boolean vein) {
        if (!enabled() || breaks < RUN_MIN_BREAKS || realizedS < RUN_MIN_S || pace <= 0) return;
        ensureLoaded();
        final double t = travelS / pace, y = realizedS - penaltyS;
        stage(() -> {
            calibFor(vein).observe(t, nTrig, y);
            maybeLogCalib("moved", vein);
        });
    }

    /**
     * The player left a room: build its leg rows on the learning thread and fold them into tier 1. All lists must
     * be private copies; the grid is only read.
     */
    public static void onRoomExit(long cellKey, SolidGrid grid, List<P> chests, List<double[]> trail, List<double[]> breaks,
                                  List<Long> teleportMs) {
        if (!enabled() || grid == null || VaultGate.state() == VaultGate.State.FAIL) return;
        if (breaks.size() < LegRowBuilder.MIN_CHESTS) return;
        ensureLoaded();
        LEARN.submit(() -> {
            try {
                LegRowBuilder.Result res = LegRowBuilder.build(grid, chests, trail, breaks, teleportMs);
                if (res.rows.isEmpty()) return;
                stage(() -> {
                    LegLearner.Update u = legs.learn(res.rows);
                    RunLog.adaptModel(cellKey, u.rows, res.pathFails, u.n, u.r2Bundled, u.r2Adapted, u.interceptShift, u.fellBack,
                            legs.applied());
                    if (u.fellBackNow) {
                        LOG.error("[Routerunner] adaptive leg model is predicting your recent legs worse than the bundled one (R2 {} vs {} over the last {} legs); using the bundled model for the rest of this vault.",
                                f3(u.r2Adapted), f3(u.r2Bundled), LegLearner.GATE_ROWS);
                    }
                    save();
                });
            } catch (Throwable t) {
                LOG.error("[Routerunner] adaptive leg learning failed for a room; its legs were not learned.", t);
            }
        });
    }

    private static void stage(Runnable r) {
        VaultGate.State st = VaultGate.state();
        if (st == VaultGate.State.FAIL) return;
        if (st == VaultGate.State.UNDECIDED) {
            synchronized (staged) {
                if (VaultGate.state() == VaultGate.State.UNDECIDED) {
                    staged.add(r);
                    return;
                }
            }
            if (VaultGate.state() == VaultGate.State.FAIL) return;
        }
        run(r);
    }

    /** The density gate decided: apply what was staged on a pass, drop it on a rejection. */
    public static void onGateDecided(boolean pass) {
        List<Runnable> todo;
        synchronized (staged) {
            todo = new ArrayList<>(staged);
            staged.clear();
        }
        if (!pass) {
            if (!todo.isEmpty()) LOG.info("[Routerunner] adaptive model: dropped {} staged observation(s) from the rejected vault.", todo.size());
            return;
        }
        for (Runnable r : todo) run(r);
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            LOG.error("[Routerunner] adaptive update failed; that observation was skipped.", t);
        }
    }

    /** A vault started: re-judge the tier-1 fallback and log the starting calibration. */
    public static void onVaultEnter() {
        synchronized (staged) {
            staged.clear();
        }
        if (!enabled()) return;
        ensureLoaded();
        legs.newVault();
        loggedA[0] = Double.NaN;
        loggedA[1] = Double.NaN;
        maybeLogCalib("vault", false);
        maybeLogCalib("vault", true);
    }

    /** Vault exit: wait (briefly) for the last room's learning so its records land in this vault's log, then save. */
    public static void onVaultExit() {
        if (calib == null) return;
        try {
            LEARN.submit(() -> { }).get(EXIT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.warn("[Routerunner] adaptive learning was still busy {} ms after vault exit; the last room may be learned after the log closes.", EXIT_WAIT_MS);
        }
        synchronized (staged) {
            if (!staged.isEmpty()) {
                LOG.info("[Routerunner] adaptive model: {} observation(s) were still waiting on the density gate at exit and were dropped.", staged.size());
                staged.clear();
            }
        }
        if (enabled()) {
            LOG.info("[Routerunner] adaptive model after this vault: chain pace {}, {} s per burst ({} runs, {} rejected); vein pace {}, {} s per hit ({} runs, {} rejected); {} legs, intercept shift {}{}.",
                    f3(calib.a()), f3(calib.b()), calib.n(), calib.rejected(), f3(calibVein.a()), f3(calibVein.b()), calibVein.n(),
                    calibVein.rejected(), legs.n(), f3(legs.interceptShift()), legs.fellBack() ? ", FELL BACK to the bundled model" : "");
        }
        save();
    }

    /** Forget everything learned and delete the saved state. */
    public static void resetLearned() {
        ensureLoaded();
        calib.clear();
        calibVein.clear();
        legs.clear();
        try {
            Files.deleteIfExists(dir().resolve("run_calibration.json"));
            Files.deleteIfExists(dir().resolve("run_calibration_vein.json"));
            Files.deleteIfExists(dir().resolve("leg_model.json"));
            LOG.info("[Routerunner] adaptive model reset to the bundled model; saved state deleted.");
        } catch (Exception e) {
            LOG.error("[Routerunner] adaptive model reset in memory, but its saved files could not be deleted from {}.", dir(), e);
        }
    }

    /** One line for the config screen. */
    public static String statusLine() {
        ensureLoaded();
        if (!enabled()) return "Adaptive learning: Off";
        if (calib.n() == 0 && calibVein.n() == 0 && legs.n() == 0) return "Adaptive learning: On (nothing learned yet)";
        String s = String.format(Locale.ROOT, "Adaptive: On, pace x%.2f, %.2f s/burst", calib.a(), calib.b());
        if (calibVein.n() > 0) s += String.format(Locale.ROOT, "; vein x%.2f, %.2f s/hit", calibVein.a(), calibVein.b());
        return s;
    }

    /** A tooltip-length summary: runs and legs learned and whether the leg model fell back. */
    public static String detailLine() {
        ensureLoaded();
        return String.format(Locale.ROOT, "%d chain runs, %d vein runs, %d legs learned%s", calib.n(), calibVein.n(), legs.n(),
                legs.fellBack() ? ", leg model fell back" : "");
    }

    private static void maybeLogCalib(String reason, boolean vein) {
        RunCalibration c = calibFor(vein);
        int m = vein ? 1 : 0;
        double a = c.a(), b = c.b();
        boolean moved = Double.isNaN(loggedA[m]) || Math.abs(a - loggedA[m]) > CALIB_LOG_STEP * loggedA[m]
                || Math.abs(b - loggedB[m]) > CALIB_LOG_STEP * loggedB[m];
        if (!moved) return;
        loggedA[m] = a;
        loggedB[m] = b;
        RunLog.calib(reason, vein ? "vein" : "chain", a, b, c.n(), c.rejected());
    }

    private static void save() {
        if (calib == null) return;
        try {
            Files.createDirectories(dir());
            write(dir().resolve("run_calibration.json"), calib.state());
            write(dir().resolve("run_calibration_vein.json"), calibVein.state());
            write(dir().resolve("leg_model.json"), legs.state());
        } catch (Exception e) {
            LOG.error("[Routerunner] could not save the adaptive model to {}; what was learned this session is kept in memory only.", dir(), e);
        }
    }

    private static void write(Path path, Object state) throws java.io.IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) {
            GSON.toJson(state, w);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static <T> T read(Path path, Class<T> type) {
        if (!Files.exists(path)) return null;
        try (Reader r = Files.newBufferedReader(path)) {
            return GSON.fromJson(r, type);
        } catch (Exception e) {
            LOG.error("[Routerunner] adaptive state {} is unreadable; starting that part fresh.", path, e);
            return null;
        }
    }

    private static String f3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
