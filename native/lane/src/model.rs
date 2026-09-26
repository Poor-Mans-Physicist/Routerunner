//! Port of `com.routerunner.lane.LegTimeModel`: a ridge regression on log(seconds) over twelve
//! standardised geometry features, the simplified linear form (seconds per block of path,
//! climbed and dropped), or the shape (move) form whose legs the planner prices itself
//! (`planner::shape_cost`).

pub const SHAPE_N: usize = 24;
pub const S_RUN_OPEN: usize = 0;
pub const S_RUN_MID: usize = 1;
pub const S_RUN_TIGHT: usize = 2;
pub const S_UP1: usize = 3;
pub const S_UP_SHAFT: usize = 4;
pub const S_DROP_SMALL: usize = 5;
pub const S_DROP_BIG: usize = 6;
pub const S_T20: usize = 7;
pub const S_T60: usize = 8;
pub const S_T120: usize = 9;
pub const S_TA: usize = 10;
pub const S_TURN_CLR: usize = 11;
pub const S_TA_CLR: usize = 12;
pub const S_CLICK: usize = 13;
pub const S_SIDE: usize = 14;
pub const S_WIDE: usize = 15;
pub const S_BEHIND: usize = 16;
pub const S_ABOVE: usize = 17;
pub const S_BELOW: usize = 18;
pub const S_REACH: usize = 19;
pub const S_BURST: usize = 20;
pub const S_SIZE: usize = 21;
pub const S_ROOM_FIXED: usize = 22;

#[derive(Clone)]
pub struct LegTimeModel {
    pub mean: [f64; 12],
    pub scale: [f64; 12],
    pub coef: [f64; 12],
    pub intercept: f64,
    pub sigma: f64,
    pub linear: bool,
    pub lin_walk: f64,
    pub lin_climb: f64,
    pub lin_drop: f64,
    /// Shape form: `SHAPE_N` coefficients for one miner at one speed (see the `S_*` indices).
    pub shape: Option<Vec<f64>>,
}

impl LegTimeModel {
    /// A ridge model (the linear fields unused).
    pub fn ridge(mean: [f64; 12], scale: [f64; 12], coef: [f64; 12], intercept: f64, sigma: f64) -> LegTimeModel {
        LegTimeModel { mean, scale, coef, intercept, sigma, linear: false, lin_walk: 0.0, lin_climb: 0.0, lin_drop: 0.0, shape: None }
    }

    /// Seconds for one leg from raw (untransformed) features. The accumulation order matches the
    /// Java loop exactly: `s += coef[i] * (f[i] - mean[i]) / scale[i]`, ascending i. The linear
    /// form evaluates `walk * path + climb * climbed + drop * dropped` left to right, as Java does.
    #[allow(clippy::too_many_arguments)]
    pub fn seconds(
        &self,
        straight: f64,
        ratio: f64,
        climb: f64,
        drop: f64,
        clr_min: f64,
        clr_mean: f64,
        tight_frac: f64,
        turn_deg: f64,
        dens_line: f64,
        dens_dst: f64,
        prev_burst: f64,
        warp: f64,
    ) -> f64 {
        if let Some(c) = &self.shape {
            let walk = straight.max(0.5) * ratio.max(1.0);
            return c[S_RUN_MID] * walk + c[S_UP1] * climb + c[S_DROP_SMALL] * drop.min(3.0) + c[S_DROP_BIG] * (drop - 3.0).max(0.0);
        }
        if self.linear {
            let walk = straight.max(0.5) * ratio.max(1.0);
            return self.lin_walk * walk + self.lin_climb * climb + self.lin_drop * drop;
        }
        let f = [
            straight.max(0.5).ln(),
            ratio.max(1.0).ln(),
            climb,
            drop,
            clr_min,
            clr_mean,
            tight_frac,
            turn_deg / 90.0,
            dens_line,
            dens_dst.ln_1p(),
            prev_burst.ln_1p(),
            warp,
        ];
        let mut s = self.intercept;
        for i in 0..12 {
            s += self.coef[i] * (f[i] - self.mean[i]) / self.scale[i];
        }
        s.exp()
    }
}
