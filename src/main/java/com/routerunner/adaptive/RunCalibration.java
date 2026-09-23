package com.routerunner.adaptive;

/**
 * Tier 0 of the adaptive model: run-level calibration. Every completed lane run gives one observation
 *
 * <pre>realized_s - penalty_s = a * travel_s + b * triggers</pre>
 *
 * where {@code travel_s} is the run's planned travel at pace 1 (the leg model's walking time, trigger charge and
 * fixed penalties taken out) and {@code triggers} the chain triggers it was planned to fire. {@code a} is the
 * player's pace on travel, {@code b} their seconds per chain burst. Estimated by recursive least squares on decayed
 * sufficient statistics (forgetting {@link #FORGET} per run, a window of about 200 runs) plus a fixed Gaussian prior
 * centred on (1, the bundled trigger cost) worth {@link #PRIOR_RUNS} typical runs. MC-free so the offline replay
 * ({@link AdaptiveCli}) runs the same code the game does.
 */
public final class RunCalibration {
    public static final double A_MIN = 0.6, A_MAX = 2.5, B_MIN = 0.05, B_MAX = 1.5;
    static final double FORGET = 0.995;
    static final double PRIOR_RUNS = 20.0;
    /** Typical run travel seconds and trigger count, the units the prior weight is expressed in. */
    static final double T_REF = 2.0, K_REF = 3.0;
    /** Runs whose realized time is outside this multiple of the plan are pauses or misreads, not timing. */
    static final double RATIO_MIN = 0.2, RATIO_MAX = 5.0;

    private final double a0, b0;
    private double sTT, sTK, sKK, sTY, sKY;
    private long n;
    private long rejected;
    private double a, b;

    public RunCalibration(double b0) {
        this.a0 = 1.0;
        this.b0 = b0;
        this.a = a0;
        this.b = b0;
    }

    /**
     * Fold in one run. Returns false, and changes nothing, when the run is outside the plausible ratio band or
     * has no planned time.
     */
    public synchronized boolean observe(double travelS, double triggers, double realizedMinusPenaltyS) {
        double planned = a * travelS + b * triggers;
        if (planned <= 0.05 || !(realizedMinusPenaltyS > 0)) {
            rejected++;
            return false;
        }
        double ratio = realizedMinusPenaltyS / planned;
        if (ratio < RATIO_MIN || ratio > RATIO_MAX) {
            rejected++;
            return false;
        }
        sTT = FORGET * sTT + travelS * travelS;
        sTK = FORGET * sTK + travelS * triggers;
        sKK = FORGET * sKK + triggers * triggers;
        sTY = FORGET * sTY + travelS * realizedMinusPenaltyS;
        sKY = FORGET * sKY + triggers * realizedMinusPenaltyS;
        n++;
        solve();
        return true;
    }

    private void solve() {
        double pT = PRIOR_RUNS * T_REF * T_REF, pK = PRIOR_RUNS * K_REF * K_REF;
        double m00 = pT + sTT, m01 = sTK, m11 = pK + sKK;
        double c0 = pT * a0 + sTY, c1 = pK * b0 + sKY;
        double det = m00 * m11 - m01 * m01;
        if (!(Math.abs(det) > 1e-12)) return;
        double na = (c0 * m11 - m01 * c1) / det;
        double nb = (m00 * c1 - m01 * c0) / det;
        a = Math.max(A_MIN, Math.min(A_MAX, na));
        b = Math.max(B_MIN, Math.min(B_MAX, nb));
    }

    public synchronized double a() { return a; }
    public synchronized double b() { return b; }
    public synchronized long n() { return n; }
    public synchronized long rejected() { return rejected; }
    public double priorB() { return b0; }

    /** Plain state for persistence. */
    public synchronized State state() {
        State s = new State();
        s.sTT = sTT; s.sTK = sTK; s.sKK = sKK; s.sTY = sTY; s.sKY = sKY;
        s.n = n; s.a = a; s.b = b; s.b0 = b0;
        return s;
    }

    /** Restore saved statistics; a state saved against a different trigger prior is ignored (returns false). */
    public synchronized boolean restore(State s) {
        if (s == null || Math.abs(s.b0 - b0) > 1e-9) return false;
        sTT = s.sTT; sTK = s.sTK; sKK = s.sKK; sTY = s.sTY; sKY = s.sKY;
        n = s.n;
        solve();
        return true;
    }

    public synchronized void clear() {
        sTT = sTK = sKK = sTY = sKY = 0;
        n = 0;
        rejected = 0;
        a = a0;
        b = b0;
    }

    /** Saved form of the sufficient statistics. */
    public static final class State {
        public double sTT, sTK, sKK, sTY, sKY;
        public long n;
        public double a, b, b0;
    }
}
