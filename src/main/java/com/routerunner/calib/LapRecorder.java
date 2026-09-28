package com.routerunner.calib;

import com.routerunner.DensityTracker;
import com.routerunner.MetricsTracker;
import com.routerunner.lane.LegTimeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-lap history for the Past Vaults detail view: every {@link #SAMPLE_MS} of a lap's active time a sample of the
 * lap's rates, and at the lap's end its totals. Three chests-per-minute figures are kept over the rooms that had a
 * shape plan, all idle-free and with the room switches included:
 * <ul>
 *   <li><b>actual</b>: the chests the player broke over their room time;</li>
 *   <li><b>benchmark</b>: what the benchmark player collects on the same plans (the shape model with its coverage and
 *   switch);</li>
 *   <li><b>your pace</b>: the same plans at the player's calibrated pace, coverage and switch as they stood at vault
 *   entry, so it is a prediction, not a fit to the lap.</li>
 * </ul>
 * Actual below your pace is route adherence: moves and clicks the route did not ask for.
 */
public final class LapRecorder {
    public static final long SAMPLE_MS = 30_000;
    /** Sample layout: minutes into the lap, HUD active average, 1-minute rate, actual, benchmark, your pace, density, clumpiness. */
    public static final int S_T = 0, S_HUD = 1, S_1M = 2, S_ACTUAL = 3, S_BENCH = 4, S_PACE = 5, S_DENSITY = 6, S_CLUMP = 7;

    /** One finished lap, as stored in the history. */
    public static final class Lap {
        public int lap;
        public double activeMin, idleMin, activeAvg;
        public int chests, rooms, plannedRooms;
        public double actualCpm = Double.NaN, benchCpm = Double.NaN, paceCpm = Double.NaN, density = Double.NaN, clump = Double.NaN;
        /** The player's speed (1 / pace) the prediction used, per miner, and the miner of most rooms. */
        public double speedChain = 1.0, speedVein = 1.0;
        public String miner = "chain";
        public List<float[]> samples = new ArrayList<>();
    }

    /** Running sums of one lap. */
    public static final class Acc {
        public double chests, realS, idleS, clumpSum;
        public int rooms, veinRooms, planned;
        public double pChests, pRealS, pBenchY, pBenchT, pPaceY, pPaceT;
        public long lastSampleMs = -1;
        public List<float[]> samples = new ArrayList<>();
    }

    /** Everything a reconnect must carry over. */
    public static final class State {
        public List<Lap> laps = new ArrayList<>();
        public Map<Integer, Acc> accs = new TreeMap<>();
        public double[] snap;
    }

    private static State state = new State();

    private LapRecorder() {}

    /** Vault entry (not a reconnect): no laps yet, and the calibration frozen for this vault's predictions. */
    public static synchronized void start() {
        state = new State();
        PlayerCalibration.Snapshot s = PlayerCalibration.snapshot();
        state.snap = new double[]{s.paceChain(), s.paceVein(), s.covChain(), s.covVein(), s.swChain(), s.swVein()};
    }

    public static synchronized State state() {
        return state;
    }

    /** A reconnect resumed this vault: carry on from the saved laps. */
    public static synchronized void restore(State s) {
        if (s == null) return;
        state = s;
        if (state.snap == null) {
            PlayerCalibration.Snapshot p = PlayerCalibration.snapshot();
            state.snap = new double[]{p.paceChain(), p.paceVein(), p.covChain(), p.covVein(), p.swChain(), p.swVein()};
        }
    }

    private static Acc acc(int lap) {
        return state.accs.computeIfAbsent(lap, k -> new Acc());
    }

    /** A measured room of lap {@code lap}. */
    public static synchronized void onRoom(int lap, PlayerCalibration.Room m) {
        if (state.snap == null) start();
        Acc a = acc(lap);
        boolean v = m.vein();
        LegTimeModel.Shape sh = LegTimeModel.shape();
        double swB = sh.switchS(v), cov = sh.coverage(v);
        double pace = state.snap[v ? 1 : 0], covRel = state.snap[v ? 3 : 2], swP = state.snap[v ? 5 : 4];
        double sw = m.switchS() > 0 && m.switchS() <= PlayerCalibration.MAX_SWITCH_S ? m.switchS() : swB;
        a.chests += m.chests();
        a.realS += m.realS();
        a.idleS += m.idleS();
        a.clumpSum += m.clump();
        a.rooms++;
        if (v) a.veinRooms++;
        if (m.planYield() > 0 && m.planS() > 0) {
            a.planned++;
            a.pChests += m.chests();
            a.pRealS += m.realS() + sw;
            a.pBenchY += cov * m.planYield();
            a.pBenchT += m.planS() + swB;
            a.pPaceY += cov * covRel * m.planYield();
            a.pPaceT += pace * m.planS() + swP;
        }
    }

    /** Client tick in a vault: a sample every {@link #SAMPLE_MS} of the lap's active time. */
    public static synchronized void tick() {
        MetricsTracker mt = MetricsTracker.get();
        long t = mt.getActiveMs() - mt.getLapStartActiveMs();
        Acc a = acc(mt.getLap());
        if (a.lastSampleMs >= 0 && t - a.lastSampleMs < SAMPLE_MS) return;
        if (a.lastSampleMs < 0 && t < SAMPLE_MS) return;
        a.lastSampleMs = t;
        a.samples.add(sample(a, t, mt));
    }

    private static float[] sample(Acc a, long t, MetricsTracker mt) {
        float[] s = new float[8];
        s[S_T] = (float) (t / 60_000.0);
        s[S_HUD] = (float) mt.getLapActiveAvgPerMin();
        s[S_1M] = (float) mt.getSlidingPerMin();
        s[S_ACTUAL] = a.pRealS > 0 ? (float) (60.0 * a.pChests / a.pRealS) : Float.NaN;
        s[S_BENCH] = a.pBenchT > 0 ? (float) (60.0 * a.pBenchY / a.pBenchT) : Float.NaN;
        s[S_PACE] = a.pPaceT > 0 ? (float) (60.0 * a.pPaceY / a.pPaceT) : Float.NaN;
        double d = DensityTracker.average();
        s[S_DENSITY] = d < 0 ? Float.NaN : (float) d;
        s[S_CLUMP] = a.rooms > 0 ? (float) (a.clumpSum / a.rooms) : Float.NaN;
        return s;
    }

    /** The current lap ends (New Lap or vault exit): a last sample and its totals. Call before the tracker moves on. */
    public static synchronized void closeLap() {
        MetricsTracker mt = MetricsTracker.get();
        int n = mt.getLap();
        Acc a = acc(n);
        long t = mt.getActiveMs() - mt.getLapStartActiveMs();
        if (t > 0) a.samples.add(sample(a, t, mt));
        Lap lap = new Lap();
        lap.lap = n;
        lap.activeMin = t / 60_000.0;
        lap.idleMin = a.idleS / 60.0;
        lap.chests = mt.getLapTotal();
        lap.activeAvg = mt.getLapActiveAvgPerMin();
        lap.rooms = a.rooms;
        lap.plannedRooms = a.planned;
        if (a.pRealS > 0) lap.actualCpm = 60.0 * a.pChests / a.pRealS;
        if (a.pBenchT > 0) lap.benchCpm = 60.0 * a.pBenchY / a.pBenchT;
        if (a.pPaceT > 0) lap.paceCpm = 60.0 * a.pPaceY / a.pPaceT;
        double d = DensityTracker.average();
        lap.density = d < 0 ? Double.NaN : d;
        lap.clump = a.rooms > 0 ? a.clumpSum / a.rooms : Double.NaN;
        if (state.snap != null) {
            lap.speedChain = 1.0 / state.snap[0];
            lap.speedVein = 1.0 / state.snap[1];
        }
        lap.miner = a.veinRooms * 2 > a.rooms ? "vein" : "chain";
        lap.samples = a.samples;
        state.laps.add(lap);
        state.accs.remove(n);
    }

    /** Vault exit: the finished laps with chests in them (the current one closed), and a fresh state. */
    public static synchronized List<Lap> finish() {
        closeLap();
        List<Lap> out = new ArrayList<>();
        for (Lap l : state.laps) if (l.chests > 0) out.add(l);
        state = new State();
        return out;
    }
}
