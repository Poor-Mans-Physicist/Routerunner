//! Port of `com.routerunner.solver.SolidGrid` and the walls-only geometry of
//! `com.routerunner.lane.Grid`: standable cells, line of sight, the grounded A* and its
//! multi-source variant, the reverse exit field, flight landings and the hop search.
//!
//! Every per-cell map the Java code keys by a packed cell key is a flat array here, indexed by
//! `(x * sy + y) * sz + z` — the same index `SolidGrid` uses internally.

use crate::jcompat::{jround, JPq, QEntry};

pub const DROP_MAX: i32 = 40;
pub const ASTAR_CAP: i32 = 80_000;
pub const DISCOUNT_FLOOR: f64 = 0.7;
pub const TURN_COST: f64 = 0.3;
pub const TIGHT_MULT: f64 = 2.0;
pub const MAX_CLEARANCE: u8 = 12;

pub const FLIGHT_RADIUS: i32 = 22;
pub const FLIGHT_ABOVE: i32 = 30;
pub const FLIGHT_COST_PER_BLOCK: f64 = 0.5;
pub const HOP_COST: f64 = 3.0;
pub const HOP_EXPAND: usize = 16;

/// Compass index of a horizontal step `(dx + 1, dz + 1)`, -1 for no move.
const DIR_INDEX: [[i8; 3]; 3] = [[0, 1, 2], [7, -1, 3], [6, 5, 4]];

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct P {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl P {
    #[inline]
    pub fn new(x: i32, y: i32, z: i32) -> P {
        P { x, y, z }
    }
}

pub struct SolidGrid {
    pub sx: i32,
    pub sy: i32,
    pub sz: i32,
    pub solid: Vec<bool>,
    /// Horizontal blocks to the nearest wall in the same y layer, capped; empty until baked.
    clearance: Vec<u8>,
    /// Bitsets baked once with the clearance field: `standable` and `columnFree` per cell. Both
    /// are pure functions of the walls, and the hot loops test them millions of times.
    stand: Vec<u64>,
    colfree: Vec<u64>,
}

impl SolidGrid {
    pub fn new(sx: i32, sy: i32, sz: i32) -> SolidGrid {
        SolidGrid {
            sx,
            sy,
            sz,
            solid: vec![false; (sx * sy * sz) as usize],
            clearance: Vec::new(),
            stand: Vec::new(),
            colfree: Vec::new(),
        }
    }

    #[inline]
    pub fn idx(&self, x: i32, y: i32, z: i32) -> usize {
        ((x * self.sy + y) * self.sz + z) as usize
    }

    #[inline]
    pub fn len(&self) -> usize {
        (self.sx * self.sy * self.sz) as usize
    }

    #[inline]
    pub fn in_bounds(&self, x: i32, y: i32, z: i32) -> bool {
        x >= 0 && y >= 0 && z >= 0 && x < self.sx && y < self.sy && z < self.sz
    }

    pub fn set_solid(&mut self, x: i32, y: i32, z: i32, v: bool) {
        if self.in_bounds(x, y, z) {
            let i = self.idx(x, y, z);
            self.solid[i] = v;
        }
    }

    /// Out of bounds reads solid. No cell is ever a chest target in this port (nothing calls
    /// `setTarget`), so `isSolidFly` and `isSolid` coincide.
    #[inline]
    pub fn is_solid_fly(&self, x: i32, y: i32, z: i32) -> bool {
        if !self.in_bounds(x, y, z) {
            return true;
        }
        self.solid[self.idx(x, y, z)]
    }

    /// Per-y-layer multi-source BFS from wall cells, capped at `MAX_CLEARANCE`.
    pub fn bake_clearance(&mut self) {
        let mut field = vec![MAX_CLEARANCE; self.len()];
        let dirs = [(1i32, 0i32), (-1, 0), (0, 1), (0, -1)];
        let mut q: Vec<(i32, i32)> = Vec::with_capacity((self.sx * self.sz) as usize);
        for y in 0..self.sy {
            q.clear();
            let mut head = 0usize;
            for x in 0..self.sx {
                for z in 0..self.sz {
                    let i = self.idx(x, y, z);
                    if self.solid[i] {
                        field[i] = 0;
                        q.push((x, z));
                    }
                }
            }
            while head < q.len() {
                let (cx, cz) = q[head];
                head += 1;
                let d = field[self.idx(cx, y, cz)];
                if d >= MAX_CLEARANCE {
                    continue;
                }
                for (ddx, ddz) in dirs.iter() {
                    let nx = cx + ddx;
                    let nz = cz + ddz;
                    if nx < 0 || nz < 0 || nx >= self.sx || nz >= self.sz {
                        continue;
                    }
                    let ni = self.idx(nx, y, nz);
                    if field[ni] > d + 1 {
                        field[ni] = d + 1;
                        q.push((nx, nz));
                    }
                }
            }
        }
        self.clearance = field;
        self.bake_masks();
    }

    fn bake_masks(&mut self) {
        let n = self.len();
        let mut stand = vec![0u64; (n + 63) / 64];
        let mut colfree = vec![0u64; (n + 63) / 64];
        for x in 0..self.sx {
            for y in 0..self.sy {
                for z in 0..self.sz {
                    let i = self.idx(x, y, z);
                    let f = !self.solid[i];
                    let up = y + 1 < self.sy && !self.solid[self.idx(x, y + 1, z)];
                    if f && up {
                        colfree[i >> 6] |= 1u64 << (i & 63);
                        if y >= 1 && y < self.sy - 1 && self.solid[self.idx(x, y - 1, z)] {
                            stand[i >> 6] |= 1u64 << (i & 63);
                        }
                    }
                }
            }
        }
        self.stand = stand;
        self.colfree = colfree;
    }

    #[inline]
    pub fn stand_bit(&self, i: usize) -> bool {
        self.stand[i >> 6] & (1u64 << (i & 63)) != 0
    }

    #[inline]
    pub fn colfree_bit(&self, i: usize) -> bool {
        self.colfree[i >> 6] & (1u64 << (i & 63)) != 0
    }

    /// Wall clearance for a standing body: the min of the feet and head layers, 0 out of bounds.
    #[inline]
    pub fn clearance_fly_at(&self, x: i32, y: i32, z: i32) -> i32 {
        if self.clearance.is_empty() || !self.in_bounds(x, y, z) {
            return 0;
        }
        let mut c = self.clearance[self.idx(x, y, z)];
        if self.in_bounds(x, y + 1, z) {
            let h = self.clearance[self.idx(x, y + 1, z)];
            if h < c {
                c = h;
            }
        }
        c as i32
    }
}

#[inline]
pub fn free(g: &SolidGrid, x: i32, y: i32, z: i32) -> bool {
    !g.is_solid_fly(x, y, z)
}

#[inline]
pub fn standable(g: &SolidGrid, x: i32, y: i32, z: i32) -> bool {
    if x < 0 || z < 0 || x >= g.sx || z >= g.sz || y < 1 || y >= g.sy - 1 {
        return false;
    }
    g.stand_bit(g.idx(x, y, z))
}

#[inline]
pub fn standable_p(g: &SolidGrid, p: P) -> bool {
    standable(g, p.x, p.y, p.z)
}

#[inline]
pub fn dist(a: P, b: P) -> f64 {
    let dx = (a.x - b.x) as f64;
    let dy = (a.y - b.y) as f64;
    let dz = (a.z - b.z) as f64;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

/// `Math.hypot(dx, dz)` over the integer coordinate differences. Verified against the JDK:
/// for every integer pair in range `Math.hypot` returns exactly `Math.sqrt(dx*dx + dz*dz)`.
#[inline]
pub fn hdist(a: P, b: P) -> f64 {
    let dx = (a.x - b.x) as f64;
    let dz = (a.z - b.z) as f64;
    (dx * dx + dz * dz).sqrt()
}

#[inline]
pub fn hypot2(x: f64, z: f64) -> f64 {
    (x * x + z * z).sqrt()
}

pub fn los_clear(g: &SolidGrid, a: P, b: P) -> bool {
    let len = dist(a, b);
    let n = std::cmp::max(1, (len * 3.0).ceil() as i32);
    for i in 1..n {
        let t = i as f64 / n as f64;
        let x = jround(a.x as f64 + (b.x - a.x) as f64 * t) as i32;
        let y = jround(a.y as f64 + (b.y - a.y) as f64 * t) as i32;
        let z = jround(a.z as f64 + (b.z - a.z) as f64 * t) as i32;
        if g.is_solid_fly(x, y, z) {
            return false;
        }
    }
    true
}

pub fn los_clear_body(g: &SolidGrid, a: P, b: P) -> bool {
    los_clear(g, a, b) && los_clear(g, P::new(a.x, a.y + 1, a.z), P::new(b.x, b.y + 1, b.z))
}

#[inline]
pub fn column_free(g: &SolidGrid, x: i32, y: i32, z: i32) -> bool {
    if x < 0 || y < 0 || z < 0 || x >= g.sx || y >= g.sy || z >= g.sz {
        return false;
    }
    g.colfree_bit(g.idx(x, y, z))
}

/// How many of a diagonal step's two orthogonal corner cells are blocked; 2 drops the step,
/// 1 makes it a dogleg costing 2.0.
pub fn corner_blocked(g: &SolidGrid, p: P, dx: i32, dz: i32, qy: i32) -> i32 {
    let mut bx = false;
    let mut bz = false;
    let levels: [i32; 2] = [p.y, qy];
    let n = if qy > p.y { 2 } else { 1 };
    for &y in levels.iter().take(n) {
        if !column_free(g, p.x + dx, y, p.z) {
            bx = true;
        }
        if !column_free(g, p.x, y, p.z + dz) {
            bz = true;
        }
    }
    bx as i32 + bz as i32
}

/// Insert the open corner cell into diagonal steps that clip exactly one solid corner.
pub fn dogleg(g: &SolidGrid, path: Vec<P>) -> Vec<P> {
    let mut out: Vec<P> = Vec::with_capacity(path.len() + 8);
    for i in 0..path.len() {
        let q = path[i];
        if i > 0 {
            let p = path[i - 1];
            let dx = q.x - p.x;
            let dz = q.z - p.z;
            if dx.abs() == 1 && dz.abs() == 1 {
                let bx = !column_free(g, p.x + dx, p.y, p.z);
                let bz = !column_free(g, p.x, p.y, p.z + dz);
                if bx != bz {
                    let mx = if bx { p.x } else { p.x + dx };
                    let mz = if bx { p.z + dz } else { p.z };
                    let my = if standable(g, mx, p.y, mz) {
                        Some(p.y)
                    } else if standable(g, mx, q.y, mz) {
                        Some(q.y)
                    } else {
                        None
                    };
                    if let Some(my) = my {
                        out.push(P::new(mx, my, mz));
                    }
                }
            }
        }
        out.push(q);
    }
    out
}

/// Standable y near (x,y,z): level, then up, then down; `None` if there is none.
pub fn floor_at(g: &SolidGrid, x: i32, y: i32, z: i32, up: i32, down: i32) -> Option<i32> {
    if standable(g, x, y, z) {
        return Some(y);
    }
    for d in 1..=up {
        if standable(g, x, y + d, z) {
            return Some(y + d);
        }
    }
    for d in 1..=down {
        if standable(g, x, y - d, z) {
            return Some(y - d);
        }
    }
    None
}

/// Nearest standable cell to p: whole column first, then widening rings.
pub fn snap_inside(g: &SolidGrid, p: P) -> P {
    let x = p.x.max(0).min(g.sx - 1);
    let z = p.z.max(0).min(g.sz - 1);
    for rad in 0i32..6 {
        let mut best: Option<P> = None;
        let mut bd = i32::MAX;
        for dx in -rad..=rad {
            for dz in -rad..=rad {
                if dx.abs().max(dz.abs()) != rad {
                    continue;
                }
                for y in 1..g.sy - 1 {
                    if !standable(g, x + dx, y, z + dz) {
                        continue;
                    }
                    let dd = (y - p.y).abs() + 3 * rad;
                    if dd < bd {
                        bd = dd;
                        best = Some(P::new(x + dx, y, z + dz));
                    }
                }
            }
        }
        if let Some(b) = best {
            return b;
        }
    }
    P::new(x, p.y.max(1).min(g.sy - 2), z)
}

pub fn snap_near(g: &SolidGrid, c: P) -> Option<P> {
    for rad in 0..4 {
        for dy in -rad..=rad {
            for dx in -rad..=rad {
                for dz in -rad..=rad {
                    if standable(g, c.x + dx, c.y + dy, c.z + dz) {
                        return Some(P::new(c.x + dx, c.y + dy, c.z + dz));
                    }
                }
            }
        }
    }
    None
}

/// The cells one walk step reaches from p, with the step cost the A* charges (no discount).
pub fn successors_cost<F: FnMut(P, f64, bool)>(g: &SolidGrid, p: P, out: &mut F) {
    for dx in -1..=1 {
        for dz in -1..=1 {
            if dx == 0 && dz == 0 {
                continue;
            }
            let hd = ((dx * dx + dz * dz) as f64).sqrt();
            let mut found = false;
            for dy in [1, 0, -1, -2, -3] {
                let qx = p.x + dx;
                let qy = p.y + dy;
                let qz = p.z + dz;
                if !standable(g, qx, qy, qz) {
                    continue;
                }
                if dy > 0 && !free(g, p.x, p.y + 2, p.z) {
                    continue;
                }
                let corners = if dx != 0 && dz != 0 {
                    corner_blocked(g, p, dx, dz, qy)
                } else {
                    0
                };
                if corners == 2 {
                    continue;
                }
                out(
                    P::new(qx, qy, qz),
                    (if corners == 1 { 2.0 } else { hd }) + (if dy > 0 { 0.3 } else { 0.0 }),
                    false,
                );
                found = true;
                break;
            }
            if found || dx * dz != 0 {
                continue;
            }
            let qx = p.x + dx;
            let qz = p.z + dz;
            if !(free(g, qx, p.y, qz) && free(g, qx, p.y + 1, qz)) {
                continue;
            }
            for fall in 4..=DROP_MAX {
                let qy = p.y - fall;
                if qy < 1 {
                    break;
                }
                if !free(g, qx, qy + 1, qz) {
                    break;
                }
                if standable(g, qx, qy, qz) {
                    out(P::new(qx, qy, qz), hd + 1.0 + 0.5 * (fall as f64).sqrt(), true);
                    break;
                }
            }
        }
    }
}

/// One outgoing walk step, precomputed. Which steps a cell has, where they land and what they
/// cost is a pure function of the walls (the head-room test, the corner test and the drop scan all
/// read only the grid), so the whole walk graph is baked once per room and every search walks a
/// table instead of re-deriving it millions of times. Entries keep the `dx`/`dz` order the Java
/// loop emits them in, which is what the A* ties break on.
#[derive(Clone, Copy)]
pub struct Succ {
    pub cost: f64,
    pub x: i16,
    pub y: i16,
    pub z: i16,
    pub dout: i8,
    /// A DROP edge. The sweep discount scales walk steps only, never a fall.
    pub drop: bool,
}

pub struct SuccTable {
    off: Vec<u32>,
    ent: Vec<Succ>,
}

impl SuccTable {
    pub fn build(g: &SolidGrid) -> SuccTable {
        let n = g.len();
        let mut off = vec![0u32; n + 1];
        let mut ent: Vec<Succ> = Vec::new();
        for x in 0..g.sx {
            for y in 0..g.sy {
                for z in 0..g.sz {
                    let i = g.idx(x, y, z);
                    off[i] = ent.len() as u32;
                    if !standable(g, x, y, z) {
                        continue;
                    }
                    let p = P::new(x, y, z);
                    let mut push = |c: P, w: f64, drop: bool| {
                        let dout = DIR_INDEX[(c.x - p.x + 1) as usize][(c.z - p.z + 1) as usize];
                        ent.push(Succ {
                            cost: w,
                            x: c.x as i16,
                            y: c.y as i16,
                            z: c.z as i16,
                            dout,
                            drop,
                        });
                    };
                    successors_cost(g, p, &mut push);
                }
            }
        }
        off[n] = ent.len() as u32;
        SuccTable { off, ent }
    }

    #[inline]
    pub fn at(&self, i: usize) -> &[Succ] {
        &self.ent[self.off[i] as usize..self.off[i + 1] as usize]
    }
}

/// Every standable cell reachable on foot from `a0` (drops are one-way, so this is the forward
/// component). Returned as a flat bitset over cell indices.
pub fn reachable(g: &SolidGrid, tab: &SuccTable, a0: P) -> Vec<u64> {
    let n = g.len();
    let mut seen = vec![0u64; (n + 63) / 64];
    let a = match snap_near(g, a0) {
        Some(a) => a,
        None => return seen,
    };
    let mut q: Vec<usize> = Vec::with_capacity(1024);
    let ai = g.idx(a.x, a.y, a.z);
    seen[ai >> 6] |= 1u64 << (ai & 63);
    q.push(ai);
    let mut head = 0usize;
    while head < q.len() {
        let p = q[head];
        head += 1;
        for e in tab.at(p) {
            let i = g.idx(e.x as i32, e.y as i32, e.z as i32);
            let w = i >> 6;
            let b = 1u64 << (i & 63);
            if seen[w] & b == 0 {
                seen[w] |= b;
                q.push(i);
            }
        }
    }
    seen
}

#[inline]
pub fn bit(set: &[u64], i: usize) -> bool {
    set[i >> 6] & (1u64 << (i & 63)) != 0
}

/// Walk cost from every standable cell to `target0`: a Dijkstra backwards over the forward graph,
/// with the same millisecond-rounded weights the Java version uses. NaN means unreachable.
pub fn exit_field(g: &SolidGrid, tab: &SuccTable, target0: P) -> Vec<f64> {
    let n = g.len();
    let mut dist = vec![f64::NAN; n];
    let target = match snap_near(g, target0) {
        Some(t) => t,
        None => return dist,
    };
    let mut count = vec![0u32; n + 1];
    let mut edges = 0usize;
    for p in 0..n {
        for e in tab.at(p) {
            count[g.idx(e.x as i32, e.y as i32, e.z as i32)] += 1;
            edges += 1;
        }
    }
    let mut start = vec![0u32; n + 1];
    let mut acc = 0u32;
    for i in 0..n {
        start[i] = acc;
        acc += count[i];
    }
    start[n] = acc;
    let mut fill = start.clone();
    let mut pred_cell = vec![0u32; edges];
    let mut pred_w = vec![0i64; edges];
    for p in 0..n {
        for e in tab.at(p) {
            let ci = g.idx(e.x as i32, e.y as i32, e.z as i32);
            let slot = fill[ci] as usize;
            fill[ci] += 1;
            pred_cell[slot] = p as u32;
            pred_w[slot] = jround(e.cost * 1000.0);
        }
    }
    let mut milli = vec![i64::MAX; n];
    let kt = g.idx(target.x, target.y, target.z);
    milli[kt] = 0;
    let mut pq: std::collections::BinaryHeap<std::cmp::Reverse<(i64, u32)>> =
        std::collections::BinaryHeap::new();
    pq.push(std::cmp::Reverse((0, kt as u32)));
    while let Some(std::cmp::Reverse((d, c))) = pq.pop() {
        let ci = c as usize;
        if d > milli[ci] {
            continue;
        }
        for e in start[ci] as usize..start[ci + 1] as usize {
            let p = pred_cell[e] as usize;
            let nd = d + pred_w[e];
            if nd < milli[p] {
                milli[p] = nd;
                pq.push(std::cmp::Reverse((nd, p as u32)));
            }
        }
    }
    for i in 0..n {
        if milli[i] != i64::MAX {
            dist[i] = milli[i] as f64 / 1000.0;
        }
    }
    dist
}

/// Every standable cell in line of sight of `a` within the flight box.
pub fn landings(g: &SolidGrid, a: P) -> Vec<P> {
    let mut out = Vec::new();
    let y0 = std::cmp::max(1, a.y - FLIGHT_RADIUS);
    let y1 = std::cmp::min(g.sy - 1, a.y + FLIGHT_ABOVE);
    for dx in -FLIGHT_RADIUS..=FLIGHT_RADIUS {
        for dz in -FLIGHT_RADIUS..=FLIGHT_RADIUS {
            for y in y0..y1 {
                let x = a.x + dx;
                let z = a.z + dz;
                if !standable(g, x, y, z) {
                    continue;
                }
                let c = P::new(x, y, z);
                if dist(a, c) >= 2.0 && los_clear_body(g, a, c) {
                    out.push(c);
                }
            }
        }
    }
    out
}

pub fn walk_length(path: &[P]) -> f64 {
    let mut l = 0.0;
    for i in 1..path.len() {
        l += hdist(path[i - 1], path[i]);
    }
    l
}

/// Reusable flat scratch for the A*. `gcost`, `parent` and `seen` share one struct per cell so a
/// relax touches a single cache line, and a generation stamp makes a search allocation- and
/// clear-free.
#[derive(Clone, Copy)]
struct GCell {
    cost: f64,
    gstamp: u32,
    parent: i32,
    /// The sweep-discount memo lives in the same cell as the A* state so a successor costs one
    /// random memory access instead of two; it carries its own generation because it stays valid
    /// across every search that shares a `remaining` set.
    dgen: u32,
    dlev: u32,
}

/// The sweep discount as `search` consumes it: a generation, the seven possible values indexed by
/// live-reach level, and the function that computes a level on a memo miss.
pub struct SweepDisc<'a, F: FnMut(P) -> usize> {
    pub gen: u32,
    pub vals: [f64; 7],
    pub level: &'a mut F,
}

pub struct Scratch {
    gen: u32,
    cells: Vec<GCell>,
    /// Closed set as a bitset: small enough to stay in L1 and cheap to clear per search, which
    /// keeps `GCell` at 16 bytes so four cells share a cache line.
    seen: Vec<u64>,
    pq: JPq,
}

impl Scratch {
    pub fn new(n: usize) -> Scratch {
        Scratch {
            gen: 0,
            cells: vec![GCell { cost: 0.0, gstamp: 0, parent: -1, dgen: 0, dlev: 0 }; n],
            seen: vec![0; (n + 63) / 64],
            pq: JPq::new(),
        }
    }
}

/// Multi-source grounded A*: the cheapest path to `b0` from any start, each entered at its own
/// initial cost. `discount` scales the step cost and its floor scales the heuristic.
pub fn search<F: FnMut(P) -> usize>(
    g: &SolidGrid,
    tab: &SuccTable,
    sc: &mut Scratch,
    starts: &[P],
    cost0: Option<&[f64]>,
    b0: P,
    mut discount: Option<SweepDisc<F>>,
) -> Option<Vec<P>> {
    let b = snap_near(g, b0)?;
    if starts.is_empty() {
        return None;
    }
    let hscale = if discount.is_none() { 1.0 } else { DISCOUNT_FLOOR };
    // Lower bound on the step-cost multiplier, for the relax early-out below.
    let hscale_step = hscale;
    sc.gen = sc.gen.wrapping_add(1);
    let gen = sc.gen;
    sc.pq.clear();
    for w in sc.seen.iter_mut() {
        *w = 0;
    }
    let syz = g.sy * g.sz;
    let kb = g.idx(b.x, b.y, b.z);
    // Per-search lookup tables for the values the successor loop would otherwise recompute,
    // including two floating-point divisions per successor. The expressions are the Java ones
    // evaluated in the same order, so the results are bit-identical.
    let mut turn_tab = [0f64; 5];
    let mut pen_tab = [0f64; 5];
    for k in 0..5 {
        turn_tab[k] = 45.0 * k as f64;
        pen_tab[k] = TURN_COST * turn_tab[k] / 45.0;
    }
    let mut hd_tab = [0f64; 9];
    for dx in -1..=1i32 {
        for dz in -1..=1i32 {
            hd_tab[((dx + 1) * 3 + (dz + 1)) as usize] = ((dx * dx + dz * dz) as f64).sqrt();
        }
    }
    for (i, a) in starts.iter().enumerate() {
        let ka = g.idx(a.x, a.y, a.z);
        let c0 = match cost0 {
            Some(c) => c[i],
            None => 0.0,
        };
        let e = &mut sc.cells[ka];
        if e.gstamp == gen && e.cost <= c0 {
            continue;
        }
        e.gstamp = gen;
        e.cost = c0;
        e.parent = -1;
        sc.pq.push(QEntry {
            f: c0 + hscale * hdist(*a, b),
            g: c0,
            x: a.x as i16,
            y: a.y as i16,
            z: a.z as i16,
            dir: -1,
            turned: false,
        });
    }
    let mut n = 0;
    while let Some(top) = sc.pq.pop() {
        let p = P::new(top.x as i32, top.y as i32, top.z as i32);
        let ki = ((p.x * g.sy + p.y) * g.sz + p.z) as usize;
        if ki == kb {
            let mut out: Vec<P> = Vec::new();
            let mut c = ki as i32;
            while c >= 0 {
                let ci = c as usize;
                let x = ci as i32 / syz;
                let rem = ci as i32 % syz;
                out.push(P::new(x, rem / g.sz, rem % g.sz));
                c = sc.cells[ci].parent;
            }
            out.reverse();
            return Some(dogleg(g, out));
        }
        let sw = ki >> 6;
        let sb = 1u64 << (ki & 63);
        if sc.seen[sw] & sb != 0 {
            continue;
        }
        sc.seen[sw] |= sb;
        n += 1;
        if n > ASTAR_CAP {
            return None;
        }
        let gc = top.g;
        let dir_in = top.dir;
        let turned = top.turned;
        for su in tab.at(ki) {
            let d_out = su.dout;
            let tk = if dir_in < 0 {
                0
            } else {
                let d = (dir_in - d_out).abs() as usize;
                d.min(8 - d)
            };
            let turn = turn_tab[tk];
            let pen = if turn < 1.0 {
                0.0
            } else {
                pen_tab[tk] * (if turned { TIGHT_MULT } else { 1.0 })
            };
            let q = P::new(su.x as i32, su.y as i32, su.z as i32);
            let kq = ((q.x * g.sy + q.y) * g.sz + q.z) as usize;
            let base = su.cost;
            let e = sc.cells[kq];
            // The discount never drops below DISCOUNT_FLOOR, so a cell already cheaper than the
            // floor-discounted step cannot be improved whatever the discount turns out to be, and
            // the memo lookup can be skipped entirely.
            let floor = if su.drop { 1.0 } else { hscale_step };
            if e.gstamp == gen && e.cost <= gc + base * floor + pen {
                continue;
            }
            let dsc = match discount.as_mut() {
                None => 1.0,
                Some(_) if su.drop => 1.0,
                Some(d) => {
                    if e.dgen == d.gen {
                        d.vals[e.dlev as usize]
                    } else {
                        let l = (d.level)(q);
                        sc.cells[kq].dgen = d.gen;
                        sc.cells[kq].dlev = l as u32;
                        d.vals[l]
                    }
                }
            };
            relax(
                sc,
                gen,
                ki as i32,
                kq,
                e,
                q,
                b,
                hscale,
                gc + base * dsc + pen,
                d_out,
                turn >= 1.0,
            );
        }
    }
    None
}

#[allow(clippy::too_many_arguments)]
#[inline]
fn relax(
    sc: &mut Scratch,
    gen: u32,
    from_key: i32,
    kq: usize,
    e: GCell,
    q: P,
    b: P,
    hscale: f64,
    ng: f64,
    dir_out: i8,
    turned_now: bool,
) {
    if e.gstamp == gen && e.cost <= ng {
        return;
    }
    let c = &mut sc.cells[kq];
    c.cost = ng;
    c.gstamp = gen;
    c.parent = from_key;
    sc.pq.push(QEntry {
        f: ng + hscale * hdist(q, b),
        g: ng,
        x: q.x as i16,
        y: q.y as i16,
        z: q.z as i16,
        dir: dir_out,
        turned: turned_now,
    });
}

/// Single-source grounded A* from a snapped start.
pub fn astar<F: FnMut(P) -> usize>(
    g: &SolidGrid,
    tab: &SuccTable,
    sc: &mut Scratch,
    a0: P,
    b0: P,
    discount: Option<SweepDisc<F>>,
) -> Option<Vec<P>> {
    let a = snap_near(g, a0)?;
    search(g, tab, sc, &[a], None, b0, discount)
}

/// Flat scratch for `flight`: the hop cost and predecessor maps, stamped per flight.
pub struct FlightScratch {
    gen: u32,
    cstamp: Vec<u32>,
    cost: Vec<f64>,
    pstamp: Vec<u32>,
    pred: Vec<u32>,
}

impl FlightScratch {
    pub fn new(n: usize) -> FlightScratch {
        FlightScratch {
            gen: 0,
            cstamp: vec![0; n],
            cost: vec![0.0; n],
            pstamp: vec![0; n],
            pred: vec![0; n],
        }
    }
}

/// The straight line when it is clear for a standing body, else up to `max_hops` trident hops
/// between landings in line of sight, followed by the ground walk to the target.
pub fn flight<L: FnMut(P) -> std::rc::Rc<Vec<P>>>(
    g: &SolidGrid,
    tab: &SuccTable,
    sc: &mut Scratch,
    fsc: &mut FlightScratch,
    landings_of: &mut L,
    a: P,
    b: P,
    max_hops: i32,
) -> Option<Vec<P>> {
    if los_clear_body(g, a, b) {
        return Some(vec![a, b]);
    }
    fsc.gen = fsc.gen.wrapping_add(1);
    let gen = fsc.gen;
    let ka = g.idx(a.x, a.y, a.z);
    fsc.cstamp[ka] = gen;
    fsc.cost[ka] = 0.0;
    let mut frontier: Vec<P> = Vec::new();
    extend(g, fsc, gen, landings_of, a, &mut frontier);
    for hop in 1..=max_hops {
        if frontier.is_empty() {
            return None;
        }
        let c0: Vec<f64> = frontier
            .iter()
            .map(|f| fsc.cost[g.idx(f.x, f.y, f.z)])
            .collect();
        let rest = search(g, tab, sc, &frontier, Some(&c0), b, None::<SweepDisc<fn(P) -> usize>>);
        if let Some(rest) = rest {
            let mut chain: Vec<P> = Vec::new();
            let mut cur = rest[0];
            loop {
                let kc = g.idx(cur.x, cur.y, cur.z);
                if fsc.pstamp[kc] != gen {
                    break;
                }
                let pi = fsc.pred[kc] as usize;
                let x = pi as i32 / (g.sy * g.sz);
                let rem = pi as i32 % (g.sy * g.sz);
                let p = P::new(x, rem / g.sz, rem % g.sz);
                chain.push(p);
                cur = p;
            }
            chain.reverse();
            chain.extend_from_slice(&rest);
            return Some(chain);
        }
        if hop == max_hops {
            return None;
        }
        let mut expand = frontier.clone();
        expand.sort_by(|u, v| crate::jcompat::dcmp(dist(*u, b), dist(*v, b)));
        if expand.len() > HOP_EXPAND {
            expand.truncate(HOP_EXPAND);
        }
        frontier = Vec::new();
        for l in expand {
            extend(g, fsc, gen, landings_of, l, &mut frontier);
        }
    }
    None
}

fn extend<L: FnMut(P) -> std::rc::Rc<Vec<P>>>(
    g: &SolidGrid,
    fsc: &mut FlightScratch,
    gen: u32,
    landings_of: &mut L,
    from: P,
    frontier: &mut Vec<P>,
) {
    let kf = g.idx(from.x, from.y, from.z);
    let base = fsc.cost[kf] + if fsc.pstamp[kf] == gen { HOP_COST } else { 0.0 };
    let ls = landings_of(from);
    for m in ls.iter() {
        let k = g.idx(m.x, m.y, m.z);
        let c = base + FLIGHT_COST_PER_BLOCK * dist(from, *m);
        let known = fsc.cstamp[k] == gen;
        if known && fsc.cost[k] <= c {
            continue;
        }
        fsc.cstamp[k] = gen;
        fsc.cost[k] = c;
        fsc.pstamp[k] = gen;
        fsc.pred[k] = kf as u32;
        if !known {
            frontier.push(*m);
        }
    }
}
