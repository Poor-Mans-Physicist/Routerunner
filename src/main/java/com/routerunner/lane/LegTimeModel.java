package com.routerunner.lane;

import com.google.gson.Gson;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The tier-one learned leg-time model: a ridge regression on log(seconds) over twelve planner-computable
 * geometry features, standardised. Coefficients come from {@code research/legmodel_ridge.json} (fitted by
 * {@code tools/legmodel.py}); the feature order and transforms match {@code tools/lanes.py LegTimeModel.vec}.
 */
public final class LegTimeModel {
    public final double[] mean;
    public final double[] scale;
    public final double[] coef;
    public final double intercept;
    public final double sigma;
    /** Between-vault standard deviation of each coefficient (zeros when the JSON has none): the adaptive clamp width. */
    public final double[] spread;

    private LegTimeModel(double[] mean, double[] scale, double[] coef, double intercept, double sigma,
                         double[] spread) {
        this.mean = mean;
        this.scale = scale;
        this.coef = coef;
        this.intercept = intercept;
        this.sigma = sigma;
        this.spread = spread == null || spread.length != 12 ? new double[12] : spread;
    }

    /** The same standardisation with other coefficients (an adapted fit), or a pace factor folded into the intercept. */
    public LegTimeModel with(double[] newCoef, double newIntercept) {
        return new LegTimeModel(mean, scale, newCoef.clone(), newIntercept, sigma, spread);
    }

    /** Every leg time multiplied by {@code pace}. */
    public LegTimeModel scaled(double pace) {
        return with(coef, intercept + Math.log(pace));
    }

    /** A short fingerprint of the standardisation and coefficients, so saved adaptive state can tell its prior changed. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (double[] a : new double[][]{mean, scale, coef}) for (double v : a) sb.append(String.format(java.util.Locale.ROOT, "%.6f,", v));
        sb.append(String.format(java.util.Locale.ROOT, "%.6f", intercept));
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

    /** Seconds for one leg from raw (untransformed) features. */
    public double seconds(double straight, double ratio, double climb, double drop, double clrMin, double clrMean,
                          double tightFrac, double turnDeg, double densLine, double densDst, double prevBurst, double warp) {
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

    private static final class Raw {
        double[] mean;
        double[] scale;
        double[] coef;
        double intercept;
        double sigma;
        double[] spread;
    }
}
