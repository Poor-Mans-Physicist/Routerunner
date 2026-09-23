//! Port of `com.routerunner.lane.LanePlanner`: a room is planned as a sequence of lanes joined by
//! grounded transitions, ordered greedily by chests per second under the learned leg-time model
//! with a one-step lookahead.
//!
//! The immutable room data lives in [`Room`] and every cache in [`Cache`], so each function can
//! take `(&Room, &mut Cache)` and the per-cell caches stay flat arrays rather than hash maps.

use std::collections::HashMap;
use std::rc::Rc;

use crate::chain::{floor_div, Buckets, ChainModel, ChainScratch};
use crate::grid::{
    self, astar, bit, dist, flight, hypot2, los_clear, search, standable, standable_p, walk_length,
    FlightScratch, Scratch, SolidGrid, DISCOUNT_FLOOR, P,
};
use crate::jcompat::{dcmp, java_hashmap_order, jround};
use crate::model::LegTimeModel;

#[derive(Clone)]
pub struct Params {
    pub break_reach: f64,
    pub headings: i32,
    pub seed_spacing: i32,
    pub min_lane_len: i32,
    pub min_lane_clr: i32,
    pub max_step_up: i32,
    pub max_drop: i32,
    pub entry_spacing: i32,
    pub proxy_top_k: i32,
    pub beam_width: i32,
    pub lookahead: bool,
    pub turn_cap: f64,
    pub merge_trans: f64,
    pub max_run_len: f64,
    pub strand_penalty_s: f64,
    pub opportunity_floor: f64,
    pub exit_weight: f64,
    pub max_trans_len: f64,
    pub bail_floor: f64,
    pub far_tries: i32,
    pub far_ok: i32,
    pub bail_aggression: f64,
    pub turnaround_deg: f64,
    pub turnaround_penalty_s: f64,
    pub allow_fly: bool,
    pub fly_penalty_s: f64,
    pub sweep_gain: f64,
    pub time_scale: f64,
    pub point_mode: bool,
    pub ghost_noise: f64,
    pub seed: i64,
    pub max_lanes: i32,
    pub trigger_s: f64,
}

impl Default for Params {
    fn default() -> Params {
        Params {
            break_reach: 4.5,
            headings: 16,
            seed_spacing: 2,
            min_lane_len: 8,
            min_lane_clr: 2,
            max_step_up: 1,
            max_drop: 3,
            entry_spacing: 3,
            proxy_top_k: 12,
            beam_width: 4,
            lookahead: true,
            turn_cap: 80.0,
            merge_trans: 4.0,
            max_run_len: 40.0,
            strand_penalty_s: 1.5,
            opportunity_floor: 0.0,
            exit_weight: 1.0,
            max_trans_len: 60.0,
            bail_floor: 0.0,
            far_tries: 4,
            far_ok: 2,
            bail_aggression: 0.12,
            turnaround_deg: 120.0,
            turnaround_penalty_s: 0.4,
            allow_fly: true,
            fly_penalty_s: 1.0,
            sweep_gain: 0.4,
            time_scale: 1.39,
            point_mode: false,
            ghost_noise: 0.0,
            seed: 0,
            max_lanes: 60,
            trigger_s: 0.3,
        }
    }
}

#[derive(Clone)]
pub struct Trigger {
    pub cell: P,
    pub chest: u32,
    pub cleared: Vec<u32>,
}

#[derive(Clone)]
pub struct Lane {
    pub cells: Vec<P>,
    pub dir: [f64; 2],
    pub trans: Vec<P>,
    pub yield_: i32,
    pub yield_trans: i32,
    pub t_trans: f64,
    pub t_lane: f64,
    pub rate: f64,
    pub align: f64,
    pub rate_x: f64,
    pub d_exit: f64,
    pub end: P,
    pub remaining: Vec<bool>,
    pub triggers: Vec<Trigger>,
    pub trans_triggers: Vec<Trigger>,
    pub end_heading: [f64; 2],
    pub end_burst: i32,
    pub t_start: f64,
    pub run: usize,
    pub t_pen: f64,
}

#[derive(Default)]
pub struct Plan {
    pub lanes: Vec<Lane>,
    pub runs: Vec<Vec<usize>>,
    pub heat: Vec<Vec<(u32, usize)>>,
    pub t_total: f64,
    pub t_exit: f64,
    pub bail: f64,
    pub opportunity: f64,
    pub yield_total: i32,
    pub cover: f64,
    pub exit_path: Option<Vec<P>>,
    pub exit_straight: bool,
    pub exit_triggers: Vec<Trigger>,
    pub ghost: Vec<[f64; 6]>,
    pub clears: Vec<(f64, u32, Vec<u32>)>,
    pub lane_t: Vec<f64>,
    pub n_corridors: usize,
}

struct State {
    pos: P,
    heading: Option<[f64; 2]>,
    prev_burst: i32,
}

#[derive(Clone)]
struct Cand {
    seq: Vec<P>,
    entry: usize,
    score: f64,
    far: bool,
}

struct Corridor {
    axis_idx: usize,
    cells: Vec<P>,
}

pub struct ReachCache {
    off: Vec<u32>,
    len: Vec<u32>,
    arena: Vec<u32>,
}

pub struct Room {
    pub g: SolidGrid,
    pub chests: Vec<P>,
    pub chain: ChainModel,
    pub model: LegTimeModel,
    pub p: Params,
    corridors: Vec<Corridor>,
    chest_buckets: Buckets,
    succ: grid::SuccTable,
}

pub struct Cache {
    rc: ReachCache,
    sc: Scratch,
    fsc: FlightScratch,
    cs: ChainScratch,
    disc_gen: u32,
    cand_stamp: Vec<u32>,
    cand_gen: u32,
    chest_stamp: Vec<u32>,
    chest_gen: u32,
    component: HashMap<u32, Rc<Vec<u64>>>,
    landing: HashMap<u32, Rc<Vec<P>>>,
    exit_time: Vec<f64>,
    exit_field: Option<Vec<f64>>,
    exit_field_key: i64,
    exit_cell: P,
}

pub struct Planner {
    pub room: Room,
    pub cache: Cache,
    /// Which mode the corridor list was built for; `None` until it has been built at all.
    corridor_mode: Option<bool>,
}

#[inline]
fn ckey(p: P) -> i64 {
    ((p.x as i64) << 40) | (((p.y + 512) as i64) << 20) | (p.z + 512) as i64
}

#[inline]
/// Seconds lost turning back at a junction: nothing up to 60 degrees between the arrival heading and the
/// transition's first steps, the full turnaround penalty from `turnaround_deg`, linear between.
fn reversal_penalty(p: &Params, turn_deg: f64) -> f64 {
    let onset = 60.0;
    let span = (p.turnaround_deg - onset).max(1.0);
    let f = 0.0f64.max(1.0f64.min((turn_deg - onset) / span));
    p.turnaround_penalty_s * f
}

fn angle2(u: [f64; 2], v: [f64; 2]) -> f64 {
    let nu = hypot2(u[0], u[1]);
    let nv = hypot2(v[0], v[1]);
    if nu < 1e-9 || nv < 1e-9 {
        return 0.0;
    }
    let c = (u[0] * v[0] + u[1] * v[1]) / (nu * nv);
    (-1.0f64).max(1.0f64.min(c)).acos().to_degrees()
}

#[inline]
fn hdir(a: P, b: P) -> [f64; 2] {
    [(b.x - a.x) as f64, (b.z - a.z) as f64]
}

fn seg_dist(p: P, a: P, b: P) -> f64 {
    let vx = (b.x - a.x) as f64;
    let vy = (b.y - a.y) as f64;
    let vz = (b.z - a.z) as f64;
    let l2 = vx * vx + vy * vy + vz * vz;
    let t = if l2 < 1e-9 {
        0.0
    } else {
        ((p.x - a.x) as f64 * vx + (p.y - a.y) as f64 * vy + (p.z - a.z) as f64 * vz) / l2
    };
    let t = t.max(0.0).min(1.0);
    let qx = a.x as f64 + t * vx - p.x as f64;
    let qy = a.y as f64 + t * vy - p.y as f64;
    let qz = a.z as f64 + t * vz - p.z as f64;
    (qx * qx + qy * qy + qz * qz).sqrt()
}

impl Planner {
    pub fn new(
        g: SolidGrid,
        chests: Vec<P>,
        chain_range: i32,
        chain_limit: i32,
        p: Params,
        model: LegTimeModel,
    ) -> Planner {
        let n = g.len();
        let nc = chests.len();
        let chain = ChainModel::new(chain_range, chain_limit, &chests);
        let chest_buckets = Buckets::build(&chests, 5, true);
        let succ = grid::SuccTable::build(&g);
        let mut room = Room {
            g,
            chests,
            chain,
            model,
            p,
            corridors: Vec::new(),
            chest_buckets,
            succ,
        };
        let cache = Cache {
            rc: ReachCache {
                off: vec![0; n],
                len: vec![u32::MAX; n],
                arena: Vec::new(),
            },
            sc: Scratch::new(n),
            fsc: FlightScratch::new(n),
            cs: ChainScratch::new(nc),
            disc_gen: 0,
            cand_stamp: vec![0; n],
            cand_gen: 0,
            chest_stamp: vec![0; nc],
            chest_gen: 0,
            component: HashMap::new(),
            landing: HashMap::new(),
            exit_time: vec![f64::NAN; n],
            exit_field: None,
            exit_field_key: i64::MIN,
            exit_cell: P::new(0, 0, 0),
        };
        let point = room.p.point_mode;
        if !point {
            let cors = corridors(&room);
            room.corridors = cors;
        }
        Planner { room, cache, corridor_mode: Some(point) }
    }

    /// Build (or drop) the corridor list to match the current params. The JNI surface takes its
    /// params on `plan`, not on `create`, so the corridors cannot be built up front there.
    pub fn ensure_corridors(&mut self) {
        if self.corridor_mode == Some(self.room.p.point_mode) {
            return;
        }
        if self.room.p.point_mode {
            self.room.corridors.clear();
        } else {
            let cors = corridors(&self.room);
            self.room.corridors = cors;
        }
        self.corridor_mode = Some(self.room.p.point_mode);
    }

    pub fn plan_all(&mut self, entrance: P, exit: P) -> Plan {
        let all = vec![true; self.room.chests.len()];
        self.plan(entrance, exit, all)
    }

    pub fn plan(&mut self, entrance: P, exit: P, remaining_in: Vec<bool>) -> Plan {
        plan_impl(&self.room, &mut self.cache, entrance, exit, remaining_in)
    }

    /// Grounded A* between two cells, else a two-hop flight, else nothing. The off-lane tracer.
    pub fn path(&mut self, a: P, b: P) -> Option<Vec<P>> {
        let r = &self.room;
        let c = &mut self.cache;
        if let Some(p) = astar_plain(r, c, a, b) {
            return Some(p);
        }
        flight_from(r, c, a, b, 2)
    }
}

// ---- reach: chests triggerable from a cell (within breakReach, head line of sight), nearest first ----

fn ensure_reach(r: &Room, rc: &mut ReachCache, cell: P) -> (u32, u32) {
    let g = &r.g;
    let k = g.idx(cell.x, cell.y, cell.z);
    if rc.len[k] != u32::MAX {
        return (rc.off[k], rc.len[k]);
    }
    let head = P::new(cell.x, cell.y + 1, cell.z);
    let rr = r.p.break_reach;
    let mut out: Vec<(f64, u32)> = Vec::new();
    let bx = floor_div(cell.x, 5);
    let by = floor_div(cell.y, 5);
    let bz = floor_div(cell.z, 5);
    for dx in -1..=1 {
        for dy in -1..=1 {
            for dz in -1..=1 {
                for &i in r.chest_buckets.at(bx + dx, by + dy, bz + dz) {
                    let c = r.chests[i as usize];
                    let d = dist(cell, c);
                    if d <= rr && los_clear(g, head, c) {
                        out.push((d, i));
                    }
                }
            }
        }
    }
    out.sort_by(|a, b| dcmp(a.0, b.0));
    let off = rc.arena.len() as u32;
    for (_, i) in &out {
        rc.arena.push(*i);
    }
    rc.off[k] = off;
    rc.len[k] = out.len() as u32;
    (off, out.len() as u32)
}

#[inline]
fn live_reach(r: &Room, rc: &mut ReachCache, cell: P, remaining: &[bool]) -> i32 {
    let (o, l) = ensure_reach(r, rc, cell);
    let mut n = 0;
    for k in o..o + l {
        if remaining[rc.arena[k as usize] as usize] {
            n += 1;
        }
    }
    n
}

/// Walk the cells in order, firing the nearest live chest in reach until none is left at each cell.
fn sweep(r: &Room, c: &mut Cache, cells: &[P], remaining: &mut [bool], triggers: &mut Vec<Trigger>) -> i32 {
    let Cache { rc, cs, .. } = c;
    let mut total = 0;
    for &cell in cells {
        loop {
            let (o, l) = ensure_reach(r, rc, cell);
            let mut best: i64 = -1;
            for k in o..o + l {
                let i = rc.arena[k as usize];
                if remaining[i as usize] {
                    best = i as i64;
                    break;
                }
            }
            if best < 0 {
                break;
            }
            let cl = r.chain.clear_from(best as u32, remaining, cs);
            for &j in &cl {
                remaining[j as usize] = false;
            }
            total += cl.len() as i32;
            triggers.push(Trigger { cell, chest: best as u32, cleared: cl });
        }
    }
    total
}

// ---- the learned time of a leg ----

fn leg_time(
    r: &Room,
    a: P,
    b: P,
    path: Option<&[P]>,
    remaining: &[bool],
    prev_burst: i32,
    turn: f64,
) -> f64 {
    let g = &r.g;
    let straight = dist(a, b).max(0.5);
    let (mut walk, climb, drop, clr_min, clr_mean, tight);
    let usable = match path {
        Some(p) if p.len() >= 2 => Some(p),
        _ => None,
    };
    match usable {
        None => {
            walk = straight;
            climb = std::cmp::max(0, b.y - a.y) as f64;
            drop = std::cmp::max(0, a.y - b.y) as f64;
            let ca = g.clearance_fly_at(a.x, a.y, a.z);
            let cb = g.clearance_fly_at(b.x, b.y, b.z);
            clr_min = std::cmp::min(ca, cb) as f64;
            clr_mean = (ca + cb) as f64 / 2.0;
            tight = ((if ca <= 1 { 1 } else { 0 }) + (if cb <= 1 { 1 } else { 0 })) as f64 / 2.0;
        }
        Some(p) => {
            walk = walk_length(p);
            let mut cl_ = 0.0f64;
            let mut dr = 0.0f64;
            for i in 1..p.len() {
                let dy = p[i].y - p[i - 1].y;
                if dy > 0 {
                    cl_ += dy as f64;
                } else {
                    dr -= dy as f64;
                }
            }
            climb = cl_;
            drop = dr;
            let mut mn = i32::MAX;
            let mut sum = 0i32;
            let mut nt = 0i32;
            for c in p {
                let cl = g.clearance_fly_at(c.x, c.y, c.z);
                mn = mn.min(cl);
                sum += cl;
                if cl <= 1 {
                    nt += 1;
                }
            }
            clr_min = mn as f64;
            clr_mean = sum as f64 / p.len() as f64;
            tight = nt as f64 / p.len() as f64;
        }
    }
    walk = walk.max(straight);
    let mut n_line = 0i32;
    let mut n_dst = 0i32;
    for i in 0..r.chests.len() {
        if !remaining[i] {
            continue;
        }
        let c = r.chests[i];
        if seg_dist(c, a, b) <= 6.0 {
            n_line += 1;
        }
        if dist(c, b) <= 8.0 {
            n_dst += 1;
        }
    }
    r.model.seconds(
        straight,
        walk / straight,
        climb,
        drop,
        clr_min,
        clr_mean,
        tight,
        turn,
        n_line as f64 / straight,
        n_dst as f64,
        prev_burst as f64,
        0.0,
    )
}

// ---- corridors (corridor mode) ----

fn corridors(r: &Room) -> Vec<Corridor> {
    let g = &r.g;
    let n_ax = r.p.headings / 2;
    let mut axes: Vec<[f64; 2]> = Vec::new();
    for k in 0..n_ax {
        let a = k as f64 * std::f64::consts::PI / n_ax as f64;
        axes.push([a.cos(), a.sin()]);
    }
    let mut out: Vec<Corridor> = Vec::new();
    let mut seen: std::collections::HashSet<(i64, i64, i32, i32, i32, i32, i32, i32)> =
        std::collections::HashSet::new();
    let mut x = 0;
    while x < g.sx {
        let mut z = 0;
        while z < g.sz {
            for y in 1..g.sy - 1 {
                if !standable(g, x, y, z) || g.clearance_fly_at(x, y, z) < r.p.min_lane_clr {
                    continue;
                }
                let seed = P::new(x, y, z);
                for (ai, ax) in axes.iter().enumerate() {
                    let mut back = march(r, seed, -ax[0], -ax[1]);
                    back.reverse();
                    let fwd = march(r, seed, ax[0], ax[1]);
                    let mut cells = back;
                    cells.push(seed);
                    cells.extend_from_slice(&fwd);
                    if (cells.len() as i32) < r.p.min_lane_len {
                        continue;
                    }
                    let f = cells[0];
                    let l = cells[cells.len() - 1];
                    let key = (
                        jround(ax[0] * 1000.0),
                        jround(ax[1] * 1000.0),
                        f.x,
                        f.y,
                        f.z,
                        l.x,
                        l.y,
                        l.z,
                    );
                    if !seen.insert(key) {
                        continue;
                    }
                    out.push(Corridor { axis_idx: ai, cells });
                }
            }
            z += r.p.seed_spacing;
        }
        x += r.p.seed_spacing;
    }
    out.sort_by(|u, v| v.cells.len().cmp(&u.cells.len()));
    let mut kept: Vec<Corridor> = Vec::new();
    let mut kept_sets: Vec<std::collections::HashSet<i64>> = Vec::new();
    for c in out {
        let mut cs: std::collections::HashSet<i64> = std::collections::HashSet::new();
        for p in &c.cells {
            cs.insert(((p.x as i64) << 20) | p.z as i64);
        }
        let mut contained = false;
        for i in 0..kept.len() {
            if kept[i].axis_idx == c.axis_idx && cs.iter().all(|k| kept_sets[i].contains(k)) {
                contained = true;
                break;
            }
        }
        if !contained {
            kept.push(c);
            kept_sets.push(cs);
        }
    }
    kept
}

fn march(r: &Room, seed: P, dx: f64, dz: f64) -> Vec<P> {
    let g = &r.g;
    let mut cells: Vec<P> = Vec::new();
    let mut y = seed.y;
    let mut last = seed;
    let mut s = 0.5f64;
    loop {
        let cx = (seed.x as f64 + dx * s + 0.5).floor() as i32;
        let cz = (seed.z as f64 + dz * s + 0.5).floor() as i32;
        s += 0.5;
        if cx == last.x && cz == last.z {
            continue;
        }
        if cx < 0 || cz < 0 || cx >= g.sx || cz >= g.sz {
            break;
        }
        let ny = match grid::floor_at(g, cx, y, cz, r.p.max_step_up, r.p.max_drop) {
            Some(v) => v,
            None => break,
        };
        if g.clearance_fly_at(cx, ny, cz) < r.p.min_lane_clr {
            break;
        }
        if cx != last.x
            && cz != last.z
            && g.is_solid_fly(last.x, ny, cz)
            && g.is_solid_fly(cx, ny, last.z)
        {
            break;
        }
        y = ny;
        last = P::new(cx, y, cz);
        cells.push(last);
        if cells.len() > 60 {
            break;
        }
    }
    cells
}

// ---- searches that need the planner's caches ----

fn astar_plain(r: &Room, c: &mut Cache, a: P, b: P) -> Option<Vec<P>> {
    astar(&r.g, &r.succ, &mut c.sc, a, b, None::<grid::SweepDisc<fn(P) -> usize>>)
}

/// The sweep-aware transition search: cells with live chests in reach are cheaper to walk through.
fn astar_sweep(r: &Room, c: &mut Cache, a: P, b: P, remaining: &[bool]) -> Option<Vec<P>> {
    if r.p.sweep_gain <= 0.0 {
        return astar_plain(r, c, a, b);
    }
    let Cache { sc, rc, disc_gen, .. } = c;
    let gen = *disc_gen;
    let g = &r.g;
    let gain = r.p.sweep_gain;
    let a2 = grid::snap_near(g, a)?;
    let mut vals = [0f64; 7];
    for (n, v) in vals.iter_mut().enumerate() {
        *v = DISCOUNT_FLOOR.max(1.0 - gain * n as f64 / 6.0);
    }
    let mut level = |q: P| -> usize { std::cmp::min(6, live_reach(r, rc, q, remaining)) as usize };
    let d = grid::SweepDisc { gen, vals, level: &mut level };
    search(g, &r.succ, sc, &[a2], None, b, Some(d))
}

fn flight_from(r: &Room, c: &mut Cache, a: P, b: P, max_hops: i32) -> Option<Vec<P>> {
    let Cache { sc, fsc, landing, .. } = c;
    let g = &r.g;
    let mut lof = |p: P| -> Rc<Vec<P>> {
        let k = g.idx(p.x, p.y, p.z) as u32;
        landing
            .entry(k)
            .or_insert_with(|| Rc::new(grid::landings(g, p)))
            .clone()
    };
    flight(g, &r.succ, sc, fsc, &mut lof, a, b, max_hops)
}

/// The forward walkable component from a position, cached per cell for the plan's lifetime.
fn reachable_from(r: &Room, c: &mut Cache, pos: P) -> Rc<Vec<u64>> {
    let k = r.g.idx(pos.x, pos.y, pos.z) as u32;
    c.component
        .entry(k)
        .or_insert_with(|| Rc::new(grid::reachable(&r.g, &r.succ, pos)))
        .clone()
}

// ---- candidates ----

fn trans_estimate(r: &Room, c: &mut Cache, pos: P, cell: P, remaining: &[bool]) -> i32 {
    let l = dist(pos, cell);
    let n = std::cmp::max(1, (l / 3.0) as i32);
    c.chest_gen = c.chest_gen.wrapping_add(1);
    let gen = c.chest_gen;
    let mut count = 0;
    for k in 1..n {
        let t = k as f64 / n as f64;
        let q = P::new(
            jround(pos.x as f64 + (cell.x - pos.x) as f64 * t) as i32,
            jround(pos.y as f64 + (cell.y - pos.y) as f64 * t) as i32,
            jround(pos.z as f64 + (cell.z - pos.z) as f64 * t) as i32,
        );
        if !standable_p(&r.g, q) {
            continue;
        }
        let (o, ln) = ensure_reach(r, &mut c.rc, q);
        for j in o..o + ln {
            let i = c.rc.arena[j as usize] as usize;
            if remaining[i] && c.chest_stamp[i] != gen {
                c.chest_stamp[i] = gen;
                count += 1;
            }
        }
    }
    count
}

/// Live chests in reach of `cells`, counted once each.
fn live_set_size(r: &Room, c: &mut Cache, cells: &[P], remaining: &[bool]) -> i32 {
    c.chest_gen = c.chest_gen.wrapping_add(1);
    let gen = c.chest_gen;
    let mut count = 0;
    for &cell in cells {
        let (o, ln) = ensure_reach(r, &mut c.rc, cell);
        for j in o..o + ln {
            let i = c.rc.arena[j as usize] as usize;
            if remaining[i] && c.chest_stamp[i] != gen {
                c.chest_stamp[i] = gen;
                count += 1;
            }
        }
    }
    count
}

fn candidates(r: &Room, c: &mut Cache, st: &State, remaining: &[bool]) -> Vec<Cand> {
    let pos = st.pos;
    let g = &r.g;
    let mut scored: Vec<Cand> = Vec::new();
    if r.p.point_mode {
        c.cand_gen = c.cand_gen.wrapping_add(1);
        let gen = c.cand_gen;
        let dys = [-1i32, 0, 1, 2, -2, 3, -3];
        let mut keys: Vec<i64> = Vec::new();
        let mut cellv: Vec<P> = Vec::new();
        let mut livev: Vec<i32> = Vec::new();
        for i in 0..r.chests.len() {
            if !remaining[i] {
                continue;
            }
            let ch = r.chests[i];
            for dx in -4..=4 {
                let qx = ch.x + dx;
                if qx < 0 || qx >= g.sx {
                    continue;
                }
                for dz in -4..=4 {
                    let qz = ch.z + dz;
                    if qz < 0 || qz >= g.sz {
                        continue;
                    }
                    let kcol = (qx * g.sy) * g.sz + qz;
                    for dy in dys {
                        let qy = ch.y + dy;
                        if qy < 1 || qy >= g.sy - 1 {
                            continue;
                        }
                        let k = (kcol + qy * g.sz) as usize;
                        if !g.stand_bit(k) || c.cand_stamp[k] == gen {
                            continue;
                        }
                        let q = P::new(qx, qy, qz);
                        let live = live_reach(r, &mut c.rc, q, remaining);
                        if live > 0 {
                            c.cand_stamp[k] = gen;
                            keys.push(ckey(q));
                            cellv.push(q);
                            livev.push(live);
                        }
                    }
                }
            }
        }
        for oi in java_hashmap_order(&keys) {
            let q = cellv[oi as usize];
            let d = dist(pos, q);
            scored.push(Cand {
                seq: vec![q],
                entry: 0,
                score: livev[oi as usize] as f64 / (1.0 + d / 15.0),
                far: false,
            });
        }
    } else {
        for ci in 0..r.corridors.len() {
            for direction in 0..2 {
                let mut seq = r.corridors[ci].cells.clone();
                if direction == 1 {
                    seq.reverse();
                }
                let span = std::cmp::max(1, seq.len() as i32 - r.p.min_lane_len + 1);
                let mut entry = 0i32;
                while entry < span {
                    let live = live_set_size(r, c, &seq[entry as usize..], remaining);
                    if live == 0 {
                        entry += r.p.entry_spacing;
                        continue;
                    }
                    let d = dist(pos, seq[entry as usize]);
                    scored.push(Cand {
                        seq: seq.clone(),
                        entry: entry as usize,
                        score: live as f64 / (1.0 + d / 15.0),
                        far: false,
                    });
                    entry += r.p.entry_spacing;
                }
            }
        }
    }
    scored.sort_by(|u, v| dcmp(v.score, u.score));
    let comp = reachable_from(r, c, pos);
    for cd in scored.iter_mut() {
        let s = cd.seq[cd.entry];
        cd.far = !bit(&comp, g.idx(s.x, s.y, s.z));
    }
    let k = r.p.proxy_top_k;
    let ntop = std::cmp::min(scored.len(), (3 * k).max(0) as usize);
    for i in 0..ntop {
        let start = scored[i].seq[scored[i].entry];
        let d = dist(pos, start);
        let live = {
            let seq: Vec<P> = scored[i].seq[scored[i].entry..].to_vec();
            live_set_size(r, c, &seq, remaining)
        };
        let te = trans_estimate(r, c, pos, start, remaining);
        scored[i].score = (live + te) as f64 / (1.0 + d / 15.0);
    }
    let tail = scored.split_off(ntop);
    scored.sort_by(|u, v| dcmp(v.score, u.score));
    scored.extend(tail);
    scored
}

// ---- evaluate one candidate from a state ----

/// Model seconds from a cell to the exit: the exit field's walk cost through the leg model, or,
/// for a cell with no ground route out, the straight distance plus the flight and stranding
/// penalties. Cached per cell.
fn exit_time(r: &Room, c: &mut Cache, cell: P) -> f64 {
    let k = r.g.idx(cell.x, cell.y, cell.z);
    if !c.exit_time[k].is_nan() {
        return c.exit_time[k];
    }
    let w = match &c.exit_field {
        None => f64::NAN,
        Some(f) => f[k],
    };
    let straight = dist(cell, c.exit_cell).max(0.5);
    let climb = std::cmp::max(0, c.exit_cell.y - cell.y) as f64;
    let drop = std::cmp::max(0, cell.y - c.exit_cell.y) as f64;
    let cl = r.g.clearance_fly_at(cell.x, cell.y, cell.z) as f64;
    let secs = if w.is_nan() {
        r.model.seconds(
            straight,
            1.0,
            climb,
            drop,
            cl,
            cl,
            if cl <= 1.0 { 1.0 } else { 0.0 },
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
        ) + r.p.fly_penalty_s
            + r.p.strand_penalty_s
    } else {
        r.model.seconds(
            straight,
            w.max(straight) / straight,
            climb,
            drop,
            cl,
            cl,
            if cl <= 1.0 { 1.0 } else { 0.0 },
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
        )
    };
    c.exit_time[k] = secs;
    secs
}

#[inline]
fn exit_aware_rate(yield_: i32, leg_time: f64, d_exit: f64) -> f64 {
    yield_ as f64 / (leg_time + d_exit).max(0.3 * leg_time).max(0.05)
}

#[allow(clippy::too_many_arguments)]
fn evaluate(
    r: &Room,
    c: &mut Cache,
    st: &State,
    seq: &[P],
    entry: usize,
    remaining_in: &[bool],
    far: bool,
    allow_long: bool,
) -> Option<Lane> {
    let mut cells: Vec<P> = seq[entry..].to_vec();
    let mut last: i64 = -1;
    for k in 0..cells.len() {
        if live_reach(r, &mut c.rc, cells[k], remaining_in) > 0 {
            last = k as i64;
        }
    }
    if last < 0 {
        return None;
    }
    cells.truncate(last as usize + 1);
    if cells.len() < 2 && !r.p.point_mode {
        return None;
    }
    let mut remaining: Vec<bool> = remaining_in.to_vec();
    let start = cells[0];
    let pos = st.pos;
    let mut path = if far {
        None
    } else {
        astar_sweep(r, c, pos, start, remaining_in)
    };
    let mut fly_penalty = 0.0;
    if path.is_none() {
        if !r.p.allow_fly {
            return None;
        }
        path = flight_from(r, c, pos, start, 2);
        path.as_ref()?;
        fly_penalty = r.p.fly_penalty_s;
    }
    let path = path.expect("checked above");
    if !allow_long && !far && walk_length(&path) > r.p.max_trans_len {
        return None;
    }
    let d0 = if cells.len() == 1 {
        hdir(pos, start)
    } else {
        hdir(cells[0], cells[cells.len() - 1])
    };
    let out = if path.len() >= 2 {
        hdir(path[0], path[3.min(path.len() - 1)])
    } else {
        hdir(pos, start)
    };
    let turn_in = match st.heading {
        None => 0.0,
        Some(h) => angle2(h, out),
    };
    let tail = if path.len() >= 2 {
        hdir(path[path.len() - 2], path[path.len() - 1])
    } else {
        hdir(pos, start)
    };
    let align = angle2(tail, d0);
    let mut t_trans = if dist(pos, start) > 0.75 {
        leg_time(r, pos, start, Some(&path), &remaining, st.prev_burst, turn_in)
    } else {
        0.0
    };
    let penalty = (if align > r.p.turnaround_deg {
        r.p.turnaround_penalty_s
    } else {
        0.0
    }) + reversal_penalty(&r.p, turn_in)
        + fly_penalty;
    let mut trans_triggers: Vec<Trigger> = Vec::new();
    let y_trans = if path.len() > 1 {
        sweep(r, c, &path[..path.len() - 1], &mut remaining, &mut trans_triggers)
    } else {
        0
    };
    t_trans += r.p.trigger_s * trans_triggers.len() as f64;
    let mut triggers: Vec<Trigger> = Vec::new();
    let y_lane = sweep(r, c, &cells, &mut remaining, &mut triggers);
    let y_total = y_trans + y_lane;
    if y_total == 0 {
        return None;
    }
    let mut t_lane = 0.0;
    let mut cur = start;
    let mut pb = st.prev_burst;
    for t in &triggers {
        if dist(cur, t.cell) > 0.5 {
            t_lane += leg_time(r, cur, t.cell, None, &remaining, pb, 0.0);
        }
        t_lane += r.p.trigger_s;
        pb = t.cleared.len() as i32;
        cur = t.cell;
    }
    let end = cells[cells.len() - 1];
    if dist(cur, end) > 0.5 {
        t_lane += leg_time(r, cur, end, None, &remaining, pb, 0.0);
    }
    let t_trans_final = t_trans + penalty;
    let rate = y_total as f64 / (t_trans_final + t_lane).max(0.05);
    let d_exit = r.p.exit_weight * (exit_time(r, c, end) - exit_time(r, c, pos));
    let rate_x = exit_aware_rate(y_total, t_trans_final + t_lane, d_exit);
    let single = cells.len() == 1;
    Some(Lane {
        cells,
        dir: d0,
        trans: path,
        yield_: y_total,
        yield_trans: y_trans,
        t_trans: t_trans_final,
        t_lane,
        rate,
        align,
        rate_x,
        d_exit,
        end,
        remaining,
        triggers,
        trans_triggers,
        end_heading: if single { tail } else { d0 },
        end_burst: pb,
        t_start: 0.0,
        run: 0,
        t_pen: penalty,
    })
}

/// Evaluate walkable candidates in rank order until `k` valid lanes are in hand (at most 4k tries),
/// first without long transitions and, only if that finds nothing, with them. Flights are a
/// fallback: candidates needing one are tried only when no walkable lane beats the bail.
fn eval_top(r: &Room, c: &mut Cache, st: &State, remaining: &[bool], bail: f64, k: i32) -> Vec<Lane> {
    // Every candidate in this call is evaluated against the same `remaining`, and the sweep
    // discount is a pure function of (cell, remaining), so one memo generation serves them all.
    // Java rebuilds the memo per candidate; the values are identical either way.
    c.disc_gen = c.disc_gen.wrapping_add(1);
    let cands = candidates(r, c, st, remaining);
    let mut evals: Vec<Lane> = Vec::new();
    for allow_long in [false, true] {
        let mut ok = 0;
        let mut tried = 0;
        for cd in &cands {
            if cd.far {
                continue;
            }
            if ok >= k || tried >= 4 * k {
                break;
            }
            tried += 1;
            if let Some(e) = evaluate(r, c, st, &cd.seq, cd.entry, remaining, false, allow_long) {
                evals.push(e);
                ok += 1;
            }
        }
        if !evals.is_empty() {
            break;
        }
    }
    let mut best_near = 0.0f64;
    for e in &evals {
        best_near = best_near.max(e.rate_x);
    }
    if (evals.is_empty() || best_near < bail) && r.p.allow_fly {
        let mut ok = 0;
        let mut tried = 0;
        for cd in &cands {
            if !cd.far {
                continue;
            }
            if ok >= r.p.far_ok || tried >= r.p.far_tries {
                break;
            }
            tried += 1;
            if let Some(e) = evaluate(r, c, st, &cd.seq, cd.entry, remaining, true, true) {
                evals.push(e);
                ok += 1;
            }
        }
    }
    evals
}

fn best_lane(r: &Room, c: &mut Cache, st: &State, remaining: &[bool], depth: i32, bail: f64) -> Option<Lane> {
    let k = if depth > 0 {
        r.p.proxy_top_k
    } else {
        std::cmp::max(3, r.p.proxy_top_k / 2)
    };
    let mut evals = eval_top(r, c, st, remaining, bail, k);
    if evals.is_empty() {
        return None;
    }
    evals.sort_by(|u, v| dcmp(v.rate_x, u.rate_x));
    if depth <= 0 || !r.p.lookahead {
        return Some(evals.swap_remove(0));
    }
    let mut best: i64 = -1;
    let mut best_score = -1.0f64;
    let n = std::cmp::min(evals.len(), r.p.beam_width.max(0) as usize);
    for i in 0..n {
        let st2 = State {
            pos: evals[i].end,
            heading: Some(evals[i].end_heading),
            prev_burst: evals[i].end_burst,
        };
        let f = {
            let rem = evals[i].remaining.clone();
            best_lane(r, c, &st2, &rem, depth - 1, bail)
        };
        let score = match f {
            None => evals[i].rate_x,
            Some(f) => {
                let tt = evals[i].t_trans + evals[i].t_lane + f.t_trans + f.t_lane;
                let de = r.p.exit_weight * (exit_time(r, c, f.end) - exit_time(r, c, st.pos));
                exit_aware_rate(evals[i].yield_ + f.yield_, tt, de)
            }
        };
        if score > best_score {
            best_score = score;
            best = i as i64;
        }
    }
    if best < 0 {
        return None;
    }
    Some(evals.swap_remove(best as usize))
}

// ---- the plan ----

fn heat(r: &Room, c: &mut Cache, remaining: &[bool], cells: &[P]) -> Vec<(u32, usize)> {
    let mut out: Vec<(u32, usize)> = Vec::new();
    c.chest_gen = c.chest_gen.wrapping_add(1);
    let gen = c.chest_gen;
    for &cell in cells {
        let (o, ln) = ensure_reach(r, &mut c.rc, cell);
        for j in o..o + ln {
            let i = c.rc.arena[j as usize];
            if remaining[i as usize] && c.chest_stamp[i as usize] != gen {
                c.chest_stamp[i as usize] = gen;
                let n = r.chain.clear_from(i, remaining, &mut c.cs).len();
                out.push((i, n));
            }
        }
    }
    out
}

fn merge_runs(r: &Room, plan: &mut Plan) {
    let mut tmp: Vec<Vec<usize>> = Vec::new();
    let mut run_len = 0.0f64;
    for k in 0..plan.lanes.len() {
        let trans_len = walk_length(&plan.lanes[k].trans);
        let lane_len = trans_len + walk_length(&plan.lanes[k].cells);
        let same = k > 0
            && (r.p.point_mode || trans_len <= r.p.merge_trans)
            && angle2(plan.lanes[k - 1].dir, plan.lanes[k].dir) <= r.p.turn_cap
            && run_len + lane_len <= r.p.max_run_len;
        if !tmp.is_empty() && same {
            tmp.last_mut().expect("non-empty").push(k);
            run_len += lane_len;
        } else {
            tmp.push(vec![k]);
            run_len = lane_len;
        }
    }
    for (ri, arr) in tmp.iter().enumerate() {
        for &k in arr {
            plan.lanes[k].run = ri;
        }
    }
    plan.runs = tmp;
}

fn plan_impl(r: &Room, c: &mut Cache, entrance: P, exit: P, remaining_in: Vec<bool>) -> Plan {
    let ek = ckey(exit);
    if c.exit_field.is_none() || c.exit_field_key != ek {
        c.exit_field = Some(grid::exit_field(&r.g, &r.succ, exit));
        c.exit_field_key = ek;
        c.exit_cell = exit;
        for v in c.exit_time.iter_mut() {
            *v = f64::NAN;
        }
    }
    let live = remaining_in.iter().filter(|b| **b).count() as i32;
    let mut remaining = remaining_in.clone();
    let mut st = State { pos: entrance, heading: None, prev_burst: 0 };
    let mut plan = Plan { n_corridors: r.corridors.len(), ..Default::default() };
    let mut first: Vec<f64> = eval_top(r, c, &st, &remaining, 0.0, r.p.proxy_top_k)
        .iter()
        .map(|e| e.rate)
        .collect();
    if !first.is_empty() {
        first.sort_by(|a, b| dcmp(*a, *b));
        plan.opportunity = first[(0.75 * (first.len() - 1) as f64).floor() as usize];
    }
    plan.opportunity = plan.opportunity.max(r.p.opportunity_floor);
    plan.bail = (r.p.bail_aggression * plan.opportunity).max(r.p.bail_floor);
    let mut t = 0.0f64;
    while (plan.lanes.len() as i32) < r.p.max_lanes {
        let e = best_lane(r, c, &st, &remaining, 1, plan.bail);
        let first_exempt = plan.lanes.is_empty() && r.p.opportunity_floor <= 0.0;
        let mut e = match e {
            None => break,
            Some(e) => e,
        };
        if !first_exempt && e.rate_x < plan.bail {
            break;
        }
        let mut heat_cells = e.trans.clone();
        heat_cells.extend_from_slice(&e.cells);
        let h = heat(r, c, &remaining, &heat_cells);
        plan.heat.push(h);
        e.t_start = t;
        t += e.t_trans + e.t_lane;
        plan.yield_total += e.yield_;
        remaining = e.remaining.clone();
        st = State {
            pos: e.end,
            heading: Some(e.end_heading),
            prev_burst: e.end_burst,
        };
        plan.lanes.push(e);
    }
    let mut exit_path = astar_plain(r, c, st.pos, exit);
    if exit_path.is_none() && r.p.allow_fly {
        exit_path = flight_from(r, c, st.pos, exit, 3);
        if exit_path.is_none() {
            exit_path = Some(vec![st.pos, exit]);
            plan.exit_straight = true;
        }
    }
    let mut t_exit = match &exit_path {
        Some(p) => leg_time(r, st.pos, exit, Some(p), &remaining, st.prev_burst, 0.0),
        None => 0.0,
    };
    if let Some(p) = &exit_path {
        if p.len() > 1 {
            let tailcells: Vec<P> = p[1..].to_vec();
            let mut trg: Vec<Trigger> = Vec::new();
            let y = sweep(r, c, &tailcells, &mut remaining, &mut trg);
            t_exit += r.p.trigger_s * trg.len() as f64;
            plan.yield_total += y;
            plan.exit_triggers = trg;
        }
    }
    plan.exit_path = exit_path;
    plan.t_exit = t_exit;
    plan.t_total = t + t_exit;
    plan.cover = if live == 0 {
        0.0
    } else {
        plan.yield_total as f64 / live as f64
    };
    merge_runs(r, &mut plan);
    ghost(r, &mut plan, entrance);
    plan
}

// ---- the ghost: the plan executed at the model's leg times x timeScale ----

struct Ghost {
    t: f64,
    pos: P,
    yaw: f64,
    rnd: JavaRandom,
}

fn ghost(r: &Room, plan: &mut Plan, entrance: P) {
    let mut gh = Ghost {
        t: 0.0,
        pos: entrance,
        yaw: 0.0,
        rnd: JavaRandom::new(r.p.seed),
    };
    for li in 0..plan.lanes.len() {
        plan.lane_t.push(gh.t * 1000.0);
        let mut pts = vec![gh.pos];
        pts.extend_from_slice(&plan.lanes[li].trans);
        let secs = plan.lanes[li].t_trans;
        let trans_triggers = plan.lanes[li].trans_triggers.clone();
        walk(r, plan, &mut gh, &pts, secs, &trans_triggers);
        let cells = plan.lanes[li].cells.clone();
        let mut cur = cells[0];
        let mut pb = 0;
        let mut idx: HashMap<i64, usize> = HashMap::new();
        for (k, cell) in cells.iter().enumerate() {
            idx.insert(ckey(*cell), k);
        }
        let triggers = plan.lanes[li].triggers.clone();
        let rem = plan.lanes[li].remaining.clone();
        for tr in &triggers {
            let i0 = *idx.get(&ckey(cur)).unwrap_or(&0);
            let i1 = *idx.get(&ckey(tr.cell)).unwrap_or(&0);
            let seg: Vec<P> = cells[std::cmp::min(i0, i1)..i1 + 1].to_vec();
            if seg.len() >= 2 {
                let s = leg_time(r, cur, tr.cell, None, &rem, pb, 0.0);
                walk(r, plan, &mut gh, &seg, s, &[]);
            }
            fire(r, plan, &mut gh, tr.cell, tr.chest, &tr.cleared);
            pb = tr.cleared.len() as i32;
            cur = tr.cell;
            gh.pos = cur;
        }
        let start = *idx.get(&ckey(cur)).unwrap_or(&0);
        let tail: Vec<P> = cells[start..].to_vec();
        if tail.len() >= 2 {
            let s = leg_time(r, cur, cells[cells.len() - 1], None, &rem, pb, 0.0);
            walk(r, plan, &mut gh, &tail, s, &[]);
        }
    }
    if let Some(ep) = plan.exit_path.clone() {
        let mut pts = vec![gh.pos];
        pts.extend_from_slice(&ep);
        let secs = plan.t_exit;
        let trg = plan.exit_triggers.clone();
        walk(r, plan, &mut gh, &pts, secs, &trg);
    }
}

fn fire(r: &Room, plan: &mut Plan, gh: &mut Ghost, cell: P, chest: u32, cleared: &[u32]) {
    let c = r.chests[chest as usize];
    let yw = ((-(c.x - cell.x)) as f64)
        .atan2((c.z - cell.z) as f64)
        .to_degrees();
    let h = hypot2((c.x - cell.x) as f64, (c.z - cell.z) as f64);
    let pitch = -((c.y as f64 - (cell.y as f64 + 1.62))
        .atan2(if h == 0.0 { 0.1 } else { h })
        .to_degrees());
    plan.ghost.push([
        gh.t * 1000.0,
        cell.x as f64 + 0.5,
        cell.y as f64,
        cell.z as f64 + 0.5,
        yw,
        pitch,
    ]);
    plan.clears.push((gh.t * 1000.0, chest, cleared.to_vec()));
    gh.t += r.p.trigger_s * r.p.time_scale;
}

fn walk(r: &Room, plan: &mut Plan, gh: &mut Ghost, pts: &[P], secs: f64, triggers: &[Trigger]) {
    let mut keys: Vec<i64> = Vec::new();
    let mut groups: Vec<Vec<Trigger>> = Vec::new();
    let mut alive: Vec<bool> = Vec::new();
    for tr in triggers {
        let k = ckey(tr.cell);
        match keys.iter().position(|x| *x == k) {
            Some(i) => groups[i].push(tr.clone()),
            None => {
                keys.push(k);
                groups.push(vec![tr.clone()]);
                alive.push(true);
            }
        }
    }
    if pts.len() < 2 {
        for oi in java_hashmap_order(&keys) {
            for tr in groups[oi as usize].clone() {
                fire(r, plan, gh, tr.cell, tr.chest, &tr.cleared);
            }
        }
        return;
    }
    let mut l = 0.0f64;
    for i in 1..pts.len() {
        l += dist(pts[i - 1], pts[i]);
    }
    let noise = if r.p.ghost_noise > 0.0 {
        (gh.rnd.next_gaussian() * r.p.ghost_noise * r.model.sigma).exp()
    } else {
        1.0
    };
    let secs = secs * r.p.time_scale * noise;
    let v = if l > 0.0 { l / secs.max(0.05) } else { 1.0 };
    for i in 1..pts.len() {
        let a = pts[i - 1];
        let b = pts[i];
        let d = dist(a, b);
        gh.yaw = ((-(b.x - a.x)) as f64)
            .atan2((b.z - a.z) as f64)
            .to_degrees();
        plan.ghost.push([
            gh.t * 1000.0,
            a.x as f64 + 0.5,
            a.y as f64,
            a.z as f64 + 0.5,
            gh.yaw,
            0.0,
        ]);
        let ka = ckey(a);
        if let Some(gi) = keys.iter().position(|x| *x == ka) {
            if alive[gi] {
                alive[gi] = false;
                for tr in groups[gi].clone() {
                    fire(r, plan, gh, a, tr.chest, &tr.cleared);
                }
            }
        }
        gh.t += d / v;
        gh.pos = b;
    }
    for oi in java_hashmap_order(&keys) {
        if alive[oi as usize] {
            let pos = gh.pos;
            for tr in groups[oi as usize].clone() {
                fire(r, plan, gh, pos, tr.chest, &tr.cleared);
            }
        }
    }
    let gp = gh.pos;
    plan.ghost.push([
        gh.t * 1000.0,
        gp.x as f64 + 0.5,
        gp.y as f64,
        gp.z as f64 + 0.5,
        gh.yaw,
        0.0,
    ]);
}

/// `java.util.Random`, needed only for the optional ghost noise.
struct JavaRandom {
    seed: i64,
    next_gaussian: Option<f64>,
}

impl JavaRandom {
    fn new(seed: i64) -> JavaRandom {
        JavaRandom {
            seed: (seed ^ 0x5DEECE66D) & ((1i64 << 48) - 1),
            next_gaussian: None,
        }
    }

    fn next(&mut self, bits: i32) -> i32 {
        self.seed = self
            .seed
            .wrapping_mul(0x5DEECE66D)
            .wrapping_add(0xB)
            & ((1i64 << 48) - 1);
        (self.seed >> (48 - bits)) as i32
    }

    fn next_double(&mut self) -> f64 {
        let hi = (self.next(26) as i64) << 27;
        let lo = self.next(27) as i64;
        (hi + lo) as f64 * (1.0 / (1i64 << 53) as f64)
    }

    fn next_gaussian(&mut self) -> f64 {
        if let Some(g) = self.next_gaussian.take() {
            return g;
        }
        loop {
            let v1 = 2.0 * self.next_double() - 1.0;
            let v2 = 2.0 * self.next_double() - 1.0;
            let s = v1 * v1 + v2 * v2;
            if s < 1.0 && s != 0.0 {
                let multiplier = (-2.0 * s.ln() / s).sqrt();
                self.next_gaussian = Some(v2 * multiplier);
                return v1 * multiplier;
            }
        }
    }
}
