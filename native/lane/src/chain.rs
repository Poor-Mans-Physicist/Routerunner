//! Port of `com.routerunner.solver.ChainModel`: breaking one chest clears up to `limit` chests,
//! each within `range` (Chebyshev) of a cleared one, FIFO BFS, nearest-first by Manhattan distance
//! with the neighbour-scan order as the tiebreak.

use crate::grid::P;

/// A spatial hash over the chest list, flattened: bucket (bx,by,bz) holds chest indices in
/// ascending index order, exactly as Java's `computeIfAbsent(...).add(i)` leaves them.
pub struct Buckets {
    size: i32,
    ox: i32,
    oy: i32,
    oz: i32,
    nx: i32,
    ny: i32,
    nz: i32,
    start: Vec<u32>,
    items: Vec<u32>,
}

#[inline]
pub fn floor_div(a: i32, b: i32) -> i32 {
    let mut q = a / b;
    if (a % b != 0) && ((a < 0) != (b < 0)) {
        q -= 1;
    }
    q
}

impl Buckets {
    /// `trunc_insert` mirrors `LanePlanner`'s constructor, which buckets chests with Java's
    /// truncating `/` while `reach` looks them up with `Math.floorDiv` (identical for the
    /// non-negative room-local coordinates the planner ever sees).
    pub fn build(pts: &[P], size: i32, trunc_insert: bool) -> Buckets {
        let div = |v: i32| if trunc_insert { v / size } else { floor_div(v, size) };
        let (mut lox, mut loy, mut loz) = (i32::MAX, i32::MAX, i32::MAX);
        let (mut hix, mut hiy, mut hiz) = (i32::MIN, i32::MIN, i32::MIN);
        for p in pts {
            lox = lox.min(div(p.x));
            loy = loy.min(div(p.y));
            loz = loz.min(div(p.z));
            hix = hix.max(div(p.x));
            hiy = hiy.max(div(p.y));
            hiz = hiz.max(div(p.z));
        }
        if pts.is_empty() {
            lox = 0;
            loy = 0;
            loz = 0;
            hix = -1;
            hiy = -1;
            hiz = -1;
        }
        let nx = (hix - lox + 1).max(0);
        let ny = (hiy - loy + 1).max(0);
        let nz = (hiz - loz + 1).max(0);
        let n = (nx * ny * nz).max(0) as usize;
        let mut count = vec![0u32; n + 1];
        let cell = |p: &P| -> usize {
            (((div(p.x) - lox) * ny + (div(p.y) - loy)) * nz + (div(p.z) - loz)) as usize
        };
        for p in pts {
            count[cell(p)] += 1;
        }
        let mut start = vec![0u32; n + 1];
        let mut acc = 0u32;
        for i in 0..n {
            start[i] = acc;
            acc += count[i];
        }
        start[n] = acc;
        let mut fill = start.clone();
        let mut items = vec![0u32; pts.len()];
        for (i, p) in pts.iter().enumerate() {
            let c = cell(p);
            items[fill[c] as usize] = i as u32;
            fill[c] += 1;
        }
        Buckets { size, ox: lox, oy: loy, oz: loz, nx, ny, nz, start, items }
    }

    #[inline]
    pub fn coord(&self, v: i32) -> i32 {
        floor_div(v, self.size)
    }

    /// Chest indices in bucket (bx,by,bz), ascending; empty when the bucket is out of range.
    #[inline]
    pub fn at(&self, bx: i32, by: i32, bz: i32) -> &[u32] {
        let (ix, iy, iz) = (bx - self.ox, by - self.oy, bz - self.oz);
        if ix < 0 || iy < 0 || iz < 0 || ix >= self.nx || iy >= self.ny || iz >= self.nz {
            return &[];
        }
        let c = ((ix * self.ny + iy) * self.nz + iz) as usize;
        &self.items[self.start[c] as usize..self.start[c + 1] as usize]
    }
}

pub struct ChainModel {
    pub range: i32,
    pub limit: usize,
    pub bucket: i32,
    pts: Vec<P>,
    buckets: Buckets,
}

/// Stamped scratch for `clear_from`'s membership set.
pub struct ChainScratch {
    gen: u32,
    stamp: Vec<u32>,
    queue: Vec<u32>,
    cand: Vec<u32>,
}

impl ChainScratch {
    pub fn new(n: usize) -> ChainScratch {
        ChainScratch { gen: 0, stamp: vec![0; n], queue: Vec::new(), cand: Vec::new() }
    }
}

impl ChainModel {
    pub fn new(range: i32, limit: i32, pts: &[P]) -> ChainModel {
        let range = range.max(0);
        let limit = limit.max(1) as usize;
        let bucket = (range + 1).max(1);
        ChainModel {
            range,
            limit,
            bucket,
            pts: pts.to_vec(),
            buckets: Buckets::build(pts, bucket, false),
        }
    }

    #[inline]
    fn cheb(a: P, b: P) -> i32 {
        (a.x - b.x).abs().max((a.y - b.y).abs().max((a.z - b.z).abs()))
    }

    #[inline]
    fn manh(a: P, b: P) -> i32 {
        (a.x - b.x).abs() + (a.y - b.y).abs() + (a.z - b.z).abs()
    }

    /// Live chests other than `idx` within `range` (Chebyshev), in the bucket-scan order Java
    /// produces (dx, dy, dz nested, ascending index within a bucket).
    pub fn neighbors(&self, idx: u32, remaining: &[bool], out: &mut Vec<u32>) {
        out.clear();
        if self.limit <= 1 {
            return;
        }
        let c = self.pts[idx as usize];
        let bx = self.buckets.coord(c.x);
        let by = self.buckets.coord(c.y);
        let bz = self.buckets.coord(c.z);
        for dx in -1..=1 {
            for dy in -1..=1 {
                for dz in -1..=1 {
                    for &j in self.buckets.at(bx + dx, by + dy, bz + dz) {
                        if j != idx
                            && remaining[j as usize]
                            && Self::cheb(c, self.pts[j as usize]) <= self.range
                        {
                            out.push(j);
                        }
                    }
                }
            }
        }
    }

    /// The exact set of chests one trigger at `start` removes; `remaining` is not mutated.
    pub fn clear_from(&self, start: u32, remaining: &[bool], sc: &mut ChainScratch) -> Vec<u32> {
        if self.limit <= 1 {
            return vec![start];
        }
        sc.gen = sc.gen.wrapping_add(1);
        let gen = sc.gen;
        let mut trav: Vec<u32> = Vec::new();
        sc.queue.clear();
        sc.queue.push(start);
        let mut head = 0usize;
        let mut cand = std::mem::take(&mut sc.cand);
        while head < sc.queue.len() {
            let h = sc.queue[head];
            head += 1;
            self.neighbors(h, remaining, &mut cand);
            cand.push(h);
            let hp = self.pts[h as usize];
            cand.sort_by_key(|&c| Self::manh(self.pts[c as usize], hp));
            let mut capped = false;
            for &c in cand.iter() {
                if trav.len() >= self.limit {
                    capped = true;
                    break;
                }
                if sc.stamp[c as usize] == gen || !remaining[c as usize] {
                    continue;
                }
                trav.push(c);
                sc.stamp[c as usize] = gen;
                sc.queue.push(c);
            }
            if capped {
                break;
            }
        }
        sc.cand = cand;
        trav
    }
}
