package com.routerunner.adaptive;

import com.routerunner.lane.LegTimeModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier 1 of the adaptive model: the leg-time model's twelve coefficients and intercept, re-estimated from the
 * player's own burst-to-burst legs. Ridge regression toward the bundled fit, kept incrementally:
 *
 * <pre>theta = argmin sum_k w_k (y_k - theta . z_k)^2 + LAMBDA |theta - theta0|^2</pre>
 *
 * with z the bundled standardisation plus a constant, y = log(seconds), and w a forgetting factor of
 * {@link #FORGET} per leg (a window of about 1000 legs). The applied model is blended in by leg count, each
 * coefficient is clamped to the bundled value plus or minus {@link #CLAMP_SPREADS} between-vault spreads, and the
 * intercept shift to {@link #INTERCEPT_SHIFT_MAX}. Every leg is scored by both models BEFORE it is learned; when
 * the adapted model's R2 on the last {@link #GATE_ROWS} legs falls more than {@link #GATE_MARGIN} below the
 * bundled model's, the bundled model is used for the rest of the vault. MC-free (see {@link AdaptiveCli}).
 */
public final class LegLearner {
    static final int DIM = 13;
    static final double LAMBDA = 200.0;
    static final double FORGET = 0.999;
    static final double BLEND_N = 150.0;
    static final double CLAMP_SPREADS = 3.0;
    static final double SPREAD_FLOOR = 0.01;
    static final double INTERCEPT_SHIFT_MAX = 0.7;
    static final int GATE_ROWS = 200;
    static final int GATE_MIN = 100;
    static final double GATE_MARGIN = 0.05;

    private final LegTimeModel prior;
    private final double[] theta0 = new double[DIM];
    private double[][] D = new double[DIM][DIM];
    private double[] d = new double[DIM];
    private long n;
    private double[] applied = new double[DIM];
    private final ArrayDeque<double[]> recent = new ArrayDeque<>();
    private boolean fellBack = false;
    private volatile LegTimeModel model;

    public LegLearner(LegTimeModel prior) {
        this.prior = prior;
        System.arraycopy(prior.coef, 0, theta0, 0, 12);
        theta0[12] = prior.intercept;
        applied = theta0.clone();
        model = prior;
    }

    /** The model the planner should use right now: the adapted fit, or the bundled one after a fallback. */
    public LegTimeModel model() {
        return model;
    }

    public LegTimeModel prior() {
        return prior;
    }

    /** Result of one {@link #learn} call. */
    public static final class Update {
        public int rows;
        public long n;
        public double r2Bundled = Double.NaN, r2Adapted = Double.NaN;
        public double interceptShift;
        public boolean fellBackNow;
        public boolean fellBack;
    }

    /**
     * Score then learn a batch of legs (one room). Each row is the twelve transformed features
     * ({@link LegTimeModel#features}) followed by log(seconds).
     */
    public synchronized Update learn(List<double[]> rows) {
        Update u = new Update();
        for (double[] r : rows) {
            double[] z = zc(r);
            double y = r[12];
            double y0 = dot(theta0, z), yh = dot(applied, z);
            recent.addLast(new double[]{y, y0, yh});
            while (recent.size() > GATE_ROWS) recent.pollFirst();
            for (int i = 0; i < DIM; i++) {
                d[i] = FORGET * d[i] + y * z[i];
                for (int j = 0; j < DIM; j++) D[i][j] = FORGET * D[i][j] + z[i] * z[j];
            }
            n++;
            u.rows++;
        }
        if (u.rows > 0) resolve();
        boolean was = fellBack;
        double[] r2 = gateR2();
        u.r2Bundled = r2[0];
        u.r2Adapted = r2[1];
        if (!fellBack && recent.size() >= GATE_MIN && r2[1] < r2[0] - GATE_MARGIN) fellBack = true;
        u.fellBackNow = fellBack && !was;
        u.fellBack = fellBack;
        u.n = n;
        u.interceptShift = applied[12] - theta0[12];
        model = fellBack ? prior : prior.with(java.util.Arrays.copyOf(applied, 12), applied[12]);
        return u;
    }

    /** Start of a vault: the fallback is re-judged from the saved recent legs, so one bad vault does not stick. */
    public synchronized void newVault() {
        double[] r2 = gateR2();
        fellBack = recent.size() >= GATE_MIN && r2[1] < r2[0] - GATE_MARGIN;
        model = fellBack ? prior : prior.with(java.util.Arrays.copyOf(applied, 12), applied[12]);
    }

    /** Bundled and adapted R2(log) on the recent legs, NaN when there are fewer than two. */
    private synchronized double[] gateR2() {
        if (recent.size() < 2) return new double[]{Double.NaN, Double.NaN};
        double mean = 0;
        for (double[] t : recent) mean += t[0];
        mean /= recent.size();
        double ss = 0, e0 = 0, e1 = 0;
        for (double[] t : recent) {
            ss += (t[0] - mean) * (t[0] - mean);
            e0 += (t[0] - t[1]) * (t[0] - t[1]);
            e1 += (t[0] - t[2]) * (t[0] - t[2]);
        }
        if (ss <= 0) return new double[]{Double.NaN, Double.NaN};
        return new double[]{1 - e0 / ss, 1 - e1 / ss};
    }

    public synchronized long n() { return n; }
    public synchronized boolean fellBack() { return fellBack; }
    public synchronized double interceptShift() { return applied[12] - theta0[12]; }

    /** The applied coefficients (twelve, then the intercept). */
    public synchronized double[] applied() { return applied.clone(); }

    private double[] zc(double[] r) {
        double[] f = java.util.Arrays.copyOf(r, 12);
        double[] z = prior.standardize(f);
        double[] out = new double[DIM];
        System.arraycopy(z, 0, out, 0, 12);
        out[12] = 1.0;
        return out;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private void resolve() {
        double[][] A = new double[DIM][DIM];
        double[] c = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            for (int j = 0; j < DIM; j++) A[i][j] = D[i][j];
            A[i][i] += LAMBDA;
            c[i] = d[i] + LAMBDA * theta0[i];
        }
        double[] theta = solve(A, c);
        if (theta == null) return;
        double w = n / (n + BLEND_N);
        double[] out = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            double v = theta0[i] + w * (theta[i] - theta0[i]);
            double lim = i < 12 ? CLAMP_SPREADS * Math.max(prior.spread[i], SPREAD_FLOOR) : INTERCEPT_SHIFT_MAX;
            out[i] = Math.max(theta0[i] - lim, Math.min(theta0[i] + lim, v));
        }
        applied = out;
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    static double[] solve(double[][] A, double[] b) {
        int m = b.length;
        double[][] M = new double[m][m + 1];
        for (int i = 0; i < m; i++) {
            System.arraycopy(A[i], 0, M[i], 0, m);
            M[i][m] = b[i];
        }
        for (int col = 0; col < m; col++) {
            int piv = col;
            for (int r = col + 1; r < m; r++) if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
            if (Math.abs(M[piv][col]) < 1e-12) return null;
            double[] tmp = M[col]; M[col] = M[piv]; M[piv] = tmp;
            for (int r = col + 1; r < m; r++) {
                double f = M[r][col] / M[col][col];
                if (f == 0) continue;
                for (int k = col; k <= m; k++) M[r][k] -= f * M[col][k];
            }
        }
        double[] x = new double[m];
        for (int i = m - 1; i >= 0; i--) {
            double s = M[i][m];
            for (int k = i + 1; k < m; k++) s -= M[i][k] * x[k];
            x[i] = s / M[i][i];
        }
        return x;
    }

    /** Plain state for persistence. */
    public synchronized State state() {
        State s = new State();
        s.D = new double[DIM][];
        for (int i = 0; i < DIM; i++) s.D[i] = D[i].clone();
        s.d = d.clone();
        s.n = n;
        s.applied = applied.clone();
        s.recent = new ArrayList<>(recent);
        s.prior = prior.fingerprint();
        return s;
    }

    /** Restore saved statistics; state saved against a different bundled model is ignored (returns false). */
    public synchronized boolean restore(State s) {
        if (s == null || s.D == null || s.d == null || s.applied == null || s.D.length != DIM || s.d.length != DIM
                || s.applied.length != DIM || !prior.fingerprint().equals(s.prior)) return false;
        for (int i = 0; i < DIM; i++) {
            if (s.D[i] == null || s.D[i].length != DIM) return false;
        }
        for (int i = 0; i < DIM; i++) D[i] = s.D[i].clone();
        d = s.d.clone();
        n = s.n;
        applied = s.applied.clone();
        recent.clear();
        if (s.recent != null) for (double[] t : s.recent) if (t != null && t.length == 3) recent.addLast(t.clone());
        while (recent.size() > GATE_ROWS) recent.pollFirst();
        fellBack = false;
        model = prior.with(java.util.Arrays.copyOf(applied, 12), applied[12]);
        return true;
    }

    public synchronized void clear() {
        D = new double[DIM][DIM];
        d = new double[DIM];
        n = 0;
        applied = theta0.clone();
        recent.clear();
        fellBack = false;
        model = prior;
    }

    /** Saved form. */
    public static final class State {
        public double[][] D;
        public double[] d;
        public long n;
        public double[] applied;
        public List<double[]> recent;
        public String prior;
    }
}
