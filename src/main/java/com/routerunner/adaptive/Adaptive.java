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

    private static final ExecutorService LEARN = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "routerunner-learn");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final Object LOCK = new Object();
    private static RunCalibration calib;
    private static LegLearner legs;
    private static final List<Runnable> staged = new ArrayList<>();
    private static double loggedA = Double.NaN, loggedB = Double.NaN;

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
            LegLearner.State ls = read(dir().resolve("leg_model.json"), LegLearner.State.class);
            if (ls != null && !legs.restore(ls)) {
                LOG.warn("[Routerunner] adaptive leg model was saved against a different bundled model (or is malformed); starting it fresh from the new prior.");
            }
            LOG.info("[Routerunner] adaptive model loaded: pace {} , {} s per burst ({} runs), {} legs learned.",
                    f3(calib.a()), f3(calib.b()), calib.n(), legs.n());
        }
    }

    public static boolean enabled() {
        return RouterunnerConfig.get().adaptiveLearning;
    }

    /** The leg model the planner should use now: the adapted fit times the pace when on, the bundled model when off. */
    public static LegTimeModel planningModel() {
        ensureLoaded();
        if (!enabled()) return legs.prior();
        return legs.model().scaled(calib.a());
    }

    /** Seconds the planner charges per chain trigger. */
    public static double triggerS() {
        ensureLoaded();
        return enabled() ? calib.b() : calib.priorB();
    }

    /** The pace folded into {@link #planningModel()}. */
    public static double pace() {
        ensureLoaded();
        return enabled() ? calib.a() : 1.0;
    }

    /**
     * A lane run finished while engaged: fold its realized time into tier 0. {@code travelS} and {@code penaltyS}
     * are the run's planned travel (at the pace it was planned with) and fixed penalties.
     */
    public static void onRunDone(double travelS, double pace, double nTrig, double penaltyS, double realizedS, int breaks) {
        if (!enabled() || breaks < RUN_MIN_BREAKS || realizedS < RUN_MIN_S || pace <= 0) return;
        ensureLoaded();
        final double t = travelS / pace, y = realizedS - penaltyS;
        stage(() -> {
            calib.observe(t, nTrig, y);
            maybeLogCalib("moved");
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
        loggedA = Double.NaN;
        maybeLogCalib("vault");
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
            LOG.info("[Routerunner] adaptive model after this vault: pace {}, {} s per burst ({} runs, {} rejected), {} legs, intercept shift {}{}.",
                    f3(calib.a()), f3(calib.b()), calib.n(), calib.rejected(), legs.n(), f3(legs.interceptShift()),
                    legs.fellBack() ? ", FELL BACK to the bundled model" : "");
        }
        save();
    }

    /** Forget everything learned and delete the saved state. */
    public static void resetLearned() {
        ensureLoaded();
        calib.clear();
        legs.clear();
        try {
            Files.deleteIfExists(dir().resolve("run_calibration.json"));
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
        if (calib.n() == 0 && legs.n() == 0) return "Adaptive learning: On (nothing learned yet)";
        return String.format(Locale.ROOT, "Adaptive: On, pace x%.2f, %.2f s/burst", calib.a(), calib.b());
    }

    /** A tooltip-length summary: runs and legs learned and whether the leg model fell back. */
    public static String detailLine() {
        ensureLoaded();
        return String.format(Locale.ROOT, "%d runs, %d legs learned%s", calib.n(), legs.n(), legs.fellBack() ? ", leg model fell back" : "");
    }

    private static void maybeLogCalib(String reason) {
        double a = calib.a(), b = calib.b();
        boolean moved = Double.isNaN(loggedA) || Math.abs(a - loggedA) > CALIB_LOG_STEP * loggedA
                || Math.abs(b - loggedB) > CALIB_LOG_STEP * loggedB;
        if (!moved) return;
        loggedA = a;
        loggedB = b;
        RunLog.calib(reason, a, b, calib.n(), calib.rejected());
    }

    private static void save() {
        if (calib == null) return;
        try {
            Files.createDirectories(dir());
            write(dir().resolve("run_calibration.json"), calib.state());
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
