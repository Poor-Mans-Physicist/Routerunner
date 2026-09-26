package com.routerunner.lane;

import com.google.gson.Gson;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The leg-time model. Two forms share one call signature:
 * <ul>
 *   <li>the tier-one learned ridge regression on log(seconds) over twelve planner-computable geometry features,
 *   standardised; coefficients from {@code research/legmodel_ridge.json} (fitted by {@code tools/legmodel.py}), feature
 *   order and transforms as in {@code tools/lanes.py LegTimeModel.vec};</li>
 *   <li>the simplified linear model ({@link #simple()}): seconds = walk x path length + climb x climbed + drop x
 *   dropped, fitted on drawn routes against real room times. Its per-click and corner charges ride on the planner's
 *   {@code triggerS} and turnaround parameters, see {@link Simple};</li>
 *   <li>the shape (move) model ({@link #shape()}): the planner prices every leg move by move from its cells (runs by
 *   clearance, steps, drops, turns, turnarounds) and every planned click from its geometry, see {@link Shape} and
 *   {@code LanePlanner.shapeCost}. {@link #shape} then holds the per-miner coefficients.</li>
 * </ul>
 */
public final class LegTimeModel {
    public final double[] mean;
    public final double[] scale;
    public final double[] coef;
    public final double intercept;
    public final double sigma;
    /** Between-vault standard deviation of each coefficient (zeros when the JSON has none): the adaptive clamp width. */
    public final double[] spread;
    /** True for the simplified linear form; the ridge fields are then inert (zero coefficients, unit scale). */
    public final boolean linear;
    /** Linear form: seconds per block of path, per block climbed and per block dropped. */
    public final double linWalk, linClimb, linDrop;
    /**
     * Shape form: {@link Shape#N} coefficients for one miner at one movement speed (null for the other forms), in
     * seconds, already multiplied by the miner's move-time scale; see the {@code S_*} indices.
     */
    public final double[] shape;

    public static final int S_RUN_OPEN = 0, S_RUN_MID = 1, S_RUN_TIGHT = 2, S_UP1 = 3, S_UP_SHAFT = 4, S_DROP_SMALL = 5,
            S_DROP_BIG = 6, S_T20 = 7, S_T60 = 8, S_T120 = 9, S_TA = 10, S_TURN_CLR = 11, S_TA_CLR = 12, S_CLICK = 13,
            S_SIDE = 14, S_WIDE = 15, S_BEHIND = 16, S_ABOVE = 17, S_BELOW = 18, S_REACH = 19, S_BURST = 20, S_SIZE = 21,
            S_ROOM_FIXED = 22;

    private LegTimeModel(double[] mean, double[] scale, double[] coef, double intercept, double sigma,
                         double[] spread) {
        this(mean, scale, coef, intercept, sigma, spread, false, 0, 0, 0, null);
    }

    private LegTimeModel(double[] mean, double[] scale, double[] coef, double intercept, double sigma,
                         double[] spread, boolean linear, double linWalk, double linClimb, double linDrop) {
        this(mean, scale, coef, intercept, sigma, spread, linear, linWalk, linClimb, linDrop, null);
    }

    private LegTimeModel(double[] mean, double[] scale, double[] coef, double intercept, double sigma,
                         double[] spread, boolean linear, double linWalk, double linClimb, double linDrop, double[] shape) {
        this.mean = mean;
        this.scale = scale;
        this.coef = coef;
        this.intercept = intercept;
        this.sigma = sigma;
        this.spread = spread == null || spread.length != 12 ? new double[12] : spread;
        this.linear = linear;
        this.linWalk = linWalk;
        this.linClimb = linClimb;
        this.linDrop = linDrop;
        this.shape = shape;
    }

    /** The same standardisation with other coefficients (an adapted fit), or a pace factor folded into the intercept. */
    public LegTimeModel with(double[] newCoef, double newIntercept) {
        if (linear || shape != null) return this;
        return new LegTimeModel(mean, scale, newCoef.clone(), newIntercept, sigma, spread);
    }

    /** Every leg time multiplied by {@code pace}. */
    public LegTimeModel scaled(double pace) {
        if (shape != null) return this;
        if (linear) {
            return new LegTimeModel(mean, scale, coef, intercept, sigma, spread, true, linWalk * pace, linClimb * pace, linDrop * pace);
        }
        return with(coef, intercept + Math.log(pace));
    }

    /** A short fingerprint of the standardisation and coefficients, so saved adaptive state can tell its prior changed. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (double[] a : new double[][]{mean, scale, coef}) for (double v : a) sb.append(String.format(java.util.Locale.ROOT, "%.6f,", v));
        sb.append(String.format(java.util.Locale.ROOT, "%.6f", intercept));
        if (linear) sb.append(String.format(java.util.Locale.ROOT, ",linear,%.6f,%.6f,%.6f", linWalk, linClimb, linDrop));
        if (shape != null) for (double v : shape) sb.append(String.format(java.util.Locale.ROOT, ",s%.6f", v));
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static volatile LegTimeModel bundled;

    /**
     * The model the mod runs with: {@code config/routerunner/legmodel.json} when present (a per-profile fit or a
     * newer export), else the copy bundled in the jar. Cached after the first call; an unreadable override is
     * logged and the bundled model used.
     */
    public static LegTimeModel forGame(Path configOverride) {
        LegTimeModel m = bundled;
        if (m != null) return m;
        if (configOverride != null && Files.exists(configOverride)) {
            try {
                m = load(configOverride);
                bundled = m;
                return m;
            } catch (Exception e) {
                System.getLogger("Routerunner").log(System.Logger.Level.ERROR,
                        "[Routerunner] legmodel override at " + configOverride + " is unreadable; using the bundled model.", e);
            }
        }
        try (Reader r = new java.io.InputStreamReader(
                java.util.Objects.requireNonNull(LegTimeModel.class.getResourceAsStream("/assets/routerunner/legmodel_ridge.json"),
                        "bundled legmodel_ridge.json missing"), java.nio.charset.StandardCharsets.UTF_8)) {
            Raw raw = new Gson().fromJson(r, Raw.class);
            m = new LegTimeModel(raw.mean, raw.scale, raw.coef, raw.intercept, raw.sigma, raw.spread);
        } catch (Exception e) {
            throw new IllegalStateException("bundled leg-time model unreadable", e);
        }
        bundled = m;
        return m;
    }

    /** Load the exported ridge; throws on a malformed file (the planner cannot run without it). */
    public static LegTimeModel load(Path json) throws java.io.IOException {
        try (Reader r = Files.newBufferedReader(json)) {
            Raw raw = new Gson().fromJson(r, Raw.class);
            if (raw == null || raw.coef == null || raw.coef.length != 12 || raw.mean.length != 12 || raw.scale.length != 12) {
                throw new java.io.IOException("legmodel json at " + json + " does not hold 12 coefficients");
            }
            return new LegTimeModel(raw.mean, raw.scale, raw.coef, raw.intercept, raw.sigma, raw.spread);
        }
    }

    /**
     * Seconds for one leg from raw (untransformed) features. In the shape form the planner prices legs itself
     * ({@code LanePlanner.shapeCost}); this is only the summary estimate its exit field and ghost use: the path at the
     * mid-clearance run cost, one-block steps for the climb, small drops for the first three blocks of a drop and the
     * fall cost beyond.
     */
    public double seconds(double straight, double ratio, double climb, double drop, double clrMin, double clrMean,
                          double tightFrac, double turnDeg, double densLine, double densDst, double prevBurst, double warp) {
        if (shape != null) {
            double walk = Math.max(straight, 0.5) * Math.max(ratio, 1.0);
            return shape[S_RUN_MID] * walk + shape[S_UP1] * climb + shape[S_DROP_SMALL] * Math.min(drop, 3.0)
                    + shape[S_DROP_BIG] * Math.max(drop - 3.0, 0.0);
        }
        if (linear) {
            double walk = Math.max(straight, 0.5) * Math.max(ratio, 1.0);
            return linWalk * walk + linClimb * climb + linDrop * drop;
        }
        double[] f = features(straight, ratio, climb, drop, clrMin, clrMean, tightFrac, turnDeg, densLine, densDst, prevBurst, warp);
        double s = intercept;
        for (int i = 0; i < 12; i++) s += coef[i] * (f[i] - mean[i]) / scale[i];
        return Math.exp(s);
    }

    /** The twelve transformed features, in the model's order. */
    public static double[] features(double straight, double ratio, double climb, double drop, double clrMin, double clrMean,
                                    double tightFrac, double turnDeg, double densLine, double densDst, double prevBurst, double warp) {
        return new double[]{
                Math.log(Math.max(straight, 0.5)), Math.log(Math.max(ratio, 1.0)), climb, drop, clrMin, clrMean,
                tightFrac, turnDeg / 90.0, densLine, Math.log1p(densDst), Math.log1p(prevBurst), warp};
    }

    /** Transformed features to the model's standardised coordinates. */
    public double[] standardize(double[] f) {
        double[] z = new double[12];
        for (int i = 0; i < 12; i++) z[i] = (f[i] - mean[i]) / scale[i];
        return z;
    }

    /** Predicted log(seconds) at standardised features z. */
    public double logSeconds(double[] z) {
        double s = intercept;
        for (int i = 0; i < 12; i++) s += coef[i] * z[i];
        return s;
    }

    /**
     * The simplified time model and the planner charges that go with it: {@link #triggerS} per planned click, and
     * {@link #cornerS} for a junction turn of at least {@link #cornerDeg}. The corner charge goes through the planner's
     * turnaround parameters: full past {@code cornerDeg} at a lane start, ramped in from 60 degrees for a transition's
     * first turn.
     */
    public static final class Simple {
        public final LegTimeModel model;
        public final double triggerS, cornerDeg, cornerS;
        public final String name;

        Simple(LegTimeModel model, double triggerS, double cornerDeg, double cornerS, String name) {
            this.model = model;
            this.triggerS = triggerS;
            this.cornerDeg = cornerDeg;
            this.cornerS = cornerS;
            this.name = name;
        }
    }

    private static volatile Simple simple;

    /** The simplified time model bundled in the jar ({@code assets/routerunner/timemodel_simple.json}). Cached. */
    public static Simple simple() {
        Simple s = simple;
        if (s != null) return s;
        try (Reader r = new java.io.InputStreamReader(
                java.util.Objects.requireNonNull(LegTimeModel.class.getResourceAsStream("/assets/routerunner/timemodel_simple.json"),
                        "bundled timemodel_simple.json missing"), java.nio.charset.StandardCharsets.UTF_8)) {
            s = fromRawSimple(new Gson().fromJson(r, RawSimple.class));
        } catch (Exception e) {
            throw new IllegalStateException("bundled simplified time model unreadable", e);
        }
        simple = s;
        return s;
    }

    /** Load a simplified time model from a JSON file (the CLI's {@code --simple} option). */
    public static Simple loadSimple(Path json) throws java.io.IOException {
        try (Reader r = Files.newBufferedReader(json)) {
            RawSimple raw = new Gson().fromJson(r, RawSimple.class);
            if (raw == null || !(raw.walk > 0)) throw new java.io.IOException("simplified time model at " + json + " has no positive walk cost");
            return fromRawSimple(raw);
        }
    }

    private static Simple fromRawSimple(RawSimple raw) {
        double[] one = new double[12];
        java.util.Arrays.fill(one, 1.0);
        LegTimeModel m = new LegTimeModel(new double[12], one, new double[12], 0.0, 0.0, null, true, raw.walk, raw.climb, raw.drop);
        return new Simple(m, raw.triggerS, raw.cornerDeg, raw.cornerS, raw.name == null ? "simple" : raw.name);
    }

    /**
     * The shape (move) time model, fitted on drawn routes against how long this player took over each stretch
     * ({@code research/2026-09-26_shape}). Move costs are shared by both miners; click costs, the move-time scale and
     * the fixed room entry and exit seconds are per miner. Runs scale with {@code vRef / movement speed}.
     */
    public static final class Shape {
        public static final int N = 24;
        static final String[] MOVE = {"run_open", "run_mid", "run_tight", "up1", "up_shaft", "drop_small", "drop_big",
                "t20", "t60", "t120", "ta", "turn_clr", "ta_clr"};
        static final String[] CLICK = {"click", "side", "wide", "behind", "above", "below", "reach", "burst", "size"};
        public final String name;
        public final double vRef;
        final double[] move;
        final double[][] click;
        final double[] moveScale, roomEntryS, roomExitS, coverage;

        Shape(String name, double vRef, double[] move, double[][] click, double[] moveScale, double[] roomEntryS,
              double[] roomExitS, double[] coverage) {
            this.name = name;
            this.vRef = vRef;
            this.move = move;
            this.click = click;
            this.moveScale = moveScale;
            this.roomEntryS = roomEntryS;
            this.roomExitS = roomExitS;
            this.coverage = coverage;
        }

        /**
         * The planner model for one miner at one movement speed. A speed at or under 0.1 (unknown) plans at the
         * reference speed, with an error logged.
         */
        public LegTimeModel forMiner(boolean vein, double speedAttr) {
            int m = vein ? 1 : 0;
            double sp = speedAttr;
            if (!(sp > 0.1)) {
                System.getLogger("Routerunner").log(System.Logger.Level.ERROR,
                        "[Routerunner] shape time model got movement speed " + speedAttr + "; pricing runs at the reference speed " + vRef + ".");
                sp = vRef;
            }
            double[] c = new double[N];
            for (int i = 0; i < MOVE.length; i++) c[i] = move[i] * moveScale[m];
            double vs = vRef / sp;
            c[S_RUN_OPEN] *= vs;
            c[S_RUN_MID] *= vs;
            c[S_RUN_TIGHT] *= vs;
            for (int i = 0; i < CLICK.length; i++) c[S_CLICK + i] = click[m][i] * moveScale[m];
            c[S_ROOM_FIXED] = roomEntryS[m] + roomExitS[m];
            double[] one = new double[12];
            java.util.Arrays.fill(one, 1.0);
            return new LegTimeModel(new double[12], one, new double[12], 0.0, 0.0, null, false, 0, 0, 0, c);
        }

        /** Seconds one click on a small (8-chest) group costs this miner: the prune threshold's time per break. */
        public double clickS(boolean vein) {
            int m = vein ? 1 : 0;
            return Math.max(0.0, moveScale[m] * (click[m][0] + click[m][8] * LanePlanner.log1pRound(8)));
        }

        /** Chests the player collects per chest the plan counts (projections only). */
        public double coverage(boolean vein) {
            return coverage[vein ? 1 : 0];
        }
    }

    private static volatile Shape shapeModel;

    /** The shape time model bundled in the jar ({@code assets/routerunner/timemodel_shape.json}). Cached. */
    public static Shape shape() {
        Shape s = shapeModel;
        if (s != null) return s;
        try (Reader r = new java.io.InputStreamReader(
                java.util.Objects.requireNonNull(LegTimeModel.class.getResourceAsStream("/assets/routerunner/timemodel_shape.json"),
                        "bundled timemodel_shape.json missing"), java.nio.charset.StandardCharsets.UTF_8)) {
            s = fromRawShape(new Gson().fromJson(r, RawShape.class), "bundled timemodel_shape.json");
        } catch (Exception e) {
            throw new IllegalStateException("bundled shape time model unreadable", e);
        }
        shapeModel = s;
        return s;
    }

    /** Load a shape time model from a JSON file (the CLIs). */
    public static Shape loadShape(Path json) throws java.io.IOException {
        try (Reader r = Files.newBufferedReader(json)) {
            return fromRawShape(new Gson().fromJson(r, RawShape.class), json.toString());
        }
    }

    private static Shape fromRawShape(RawShape raw, String where) throws java.io.IOException {
        if (raw == null || raw.move == null || raw.miners == null || raw.miners.get("chain") == null || raw.miners.get("vein") == null) {
            throw new java.io.IOException("shape time model at " + where + " lacks move costs or a chain/vein miner block");
        }
        double[] move = new double[Shape.MOVE.length];
        for (int i = 0; i < move.length; i++) {
            Double v = raw.move.get(Shape.MOVE[i]);
            if (v == null) throw new java.io.IOException("shape time model at " + where + " lacks move cost " + Shape.MOVE[i]);
            move[i] = v;
        }
        double[][] click = new double[2][Shape.CLICK.length];
        double[] scale = new double[2], entry = new double[2], exit = new double[2], cov = new double[2];
        String[] miners = {"chain", "vein"};
        for (int m = 0; m < 2; m++) {
            java.util.Map<String, Double> b = raw.miners.get(miners[m]);
            for (int i = 0; i < Shape.CLICK.length; i++) {
                Double v = b.get(Shape.CLICK[i]);
                if (v == null) throw new java.io.IOException("shape time model at " + where + " lacks " + miners[m] + " click cost " + Shape.CLICK[i]);
                click[m][i] = v;
            }
            scale[m] = b.getOrDefault("moveScale", 1.0);
            entry[m] = b.getOrDefault("roomEntryS", 0.0);
            exit[m] = b.getOrDefault("roomExitS", 0.0);
            cov[m] = b.getOrDefault("coverage", 1.0);
        }
        return new Shape(raw.name == null ? "shape" : raw.name, raw.vRef > 0 ? raw.vRef : 0.2828, move, click, scale, entry, exit, cov);
    }

    private static final class RawShape {
        String name;
        double vRef;
        java.util.Map<String, Double> move;
        java.util.Map<String, java.util.Map<String, Double>> miners;
    }

    private static final class RawSimple {
        String name;
        double walk;
        double climb;
        double drop;
        double triggerS;
        double cornerDeg;
        double cornerS;
    }

    private static final class Raw {
        double[] mean;
        double[] scale;
        double[] coef;
        double intercept;
        double sigma;
        double[] spread;
    }
}
