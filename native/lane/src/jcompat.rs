//! Java runtime behaviours the port has to reproduce bit for bit: `Math.round`, `Double.toString`,
//! `String.format("%.Nf")`, `Double.compare`, `java.util.PriorityQueue`'s heap order and
//! `java.util.HashMap`'s iteration order. Plan output depends on all of them.

use std::cmp::Ordering;
use std::sync::atomic::{AtomicBool, Ordering as AOrd};

/// `Double.compare`: total order with -0.0 < 0.0 and NaN largest.
#[inline]
pub fn dcmp(a: f64, b: f64) -> Ordering {
    if a < b {
        Ordering::Less
    } else if a > b {
        Ordering::Greater
    } else {
        let t1 = canon_bits(a);
        let t2 = canon_bits(b);
        t1.cmp(&t2)
    }
}

#[inline]
fn canon_bits(d: f64) -> i64 {
    if d.is_nan() {
        0x7ff8_0000_0000_0000u64 as i64
    } else {
        d.to_bits() as i64
    }
}

/// `Math.round(double)` — the exact JDK bit algorithm, which is *not* `floor(x + 0.5)`
/// (`Math.round(0.49999999999999994)` is 0).
#[inline]
pub fn jround(a: f64) -> i64 {
    const SIGNIFICAND_WIDTH: i64 = 53;
    const EXP_BIAS: i64 = 1023;
    const EXP_BIT_MASK: i64 = 0x7FF0_0000_0000_0000u64 as i64;
    const SIGNIF_BIT_MASK: i64 = 0x000F_FFFF_FFFF_FFFF;
    let bits = a.to_bits() as i64;
    let biased_exp = (bits & EXP_BIT_MASK) >> (SIGNIFICAND_WIDTH - 1);
    let shift = (SIGNIFICAND_WIDTH - 2 + EXP_BIAS) - biased_exp;
    if (shift & -64) == 0 {
        let mut r = (bits & SIGNIF_BIT_MASK) | (SIGNIF_BIT_MASK + 1);
        if bits < 0 {
            r = -r;
        }
        ((r >> shift) + 1) >> 1
    } else {
        a as i64
    }
}

/// `Double.toString`: shortest round-tripping digits, plain decimal in [1e-3, 1e7) and
/// `d.dddEn` outside it, always with at least one fraction digit.
pub fn jdouble(d: f64) -> String {
    if d.is_nan() {
        return "NaN".to_string();
    }
    if d.is_infinite() {
        return if d > 0.0 { "Infinity" } else { "-Infinity" }.to_string();
    }
    if d == 0.0 {
        return if d.is_sign_negative() { "-0.0" } else { "0.0" }.to_string();
    }
    let neg = d < 0.0;
    let a = d.abs();
    let s = format!("{:e}", a);
    let (mant, exps) = s.split_once('e').expect("Rust LowerExp always emits 'e'");
    let exp: i32 = exps.parse().expect("exponent is an integer");
    let digits: String = mant.chars().filter(|c| *c != '.').collect();
    let dg = digits.as_bytes();
    let mut out = String::with_capacity(digits.len() + 8);
    if neg {
        out.push('-');
    }
    if exp >= -3 && exp < 7 {
        if exp >= 0 {
            let ip = (exp + 1) as usize;
            for i in 0..ip {
                out.push(if i < dg.len() { dg[i] as char } else { '0' });
            }
            out.push('.');
            if dg.len() > ip {
                for i in ip..dg.len() {
                    out.push(dg[i] as char);
                }
            } else {
                out.push('0');
            }
        } else {
            out.push_str("0.");
            for _ in 0..(-exp - 1) {
                out.push('0');
            }
            out.push_str(&digits);
        }
    } else {
        out.push(dg[0] as char);
        out.push('.');
        if dg.len() > 1 {
            for i in 1..dg.len() {
                out.push(dg[i] as char);
            }
        } else {
            out.push('0');
        }
        out.push('E');
        out.push_str(&exp.to_string());
    }
    out
}

/// `String.format("%.<prec>f")`: HALF_UP on the exact binary value, where Rust's own
/// formatter rounds half to even. Ties are detected from a 30-digit exact expansion.
pub fn jformat_f(v: f64, prec: usize) -> String {
    if v.is_nan() {
        return "NaN".to_string();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity" } else { "-Infinity" }.to_string();
    }
    let neg = v.is_sign_negative();
    let a = v.abs();
    let wide = format!("{:.*}", prec + 30, a);
    let (ip, fp) = wide.split_once('.').expect("precision > 0 always yields a point");
    let keep = &fp[..prec];
    let rest = &fp[prec..];
    let round_up = match rest.as_bytes()[0] {
        b'0'..=b'4' => false,
        b'6'..=b'9' => true,
        _ => true, // exactly 5: HALF_UP rounds away from zero whatever follows
    };
    let mut digits: Vec<u8> = ip.bytes().chain(keep.bytes()).collect();
    if round_up {
        let mut i = digits.len();
        loop {
            if i == 0 {
                digits.insert(0, b'1');
                break;
            }
            i -= 1;
            if digits[i] == b'9' {
                digits[i] = b'0';
            } else {
                digits[i] += 1;
                break;
            }
        }
    }
    let split = digits.len() - prec;
    let mut out = String::with_capacity(digits.len() + 2);
    if neg {
        out.push('-');
    }
    out.push_str(std::str::from_utf8(&digits[..split]).expect("ascii"));
    if prec > 0 {
        out.push('.');
        out.push_str(std::str::from_utf8(&digits[split..]).expect("ascii"));
    }
    out
}

/// Left- or right-pad to a field width, as `%-Ns` / `%Nd` do.
pub fn pad_left(s: &str, w: usize) -> String {
    if s.len() >= w {
        s.to_string()
    } else {
        format!("{}{}", " ".repeat(w - s.len()), s)
    }
}

pub fn pad_right(s: &str, w: usize) -> String {
    if s.len() >= w {
        s.to_string()
    } else {
        format!("{}{}", s, " ".repeat(w - s.len()))
    }
}

// ---- java.util.PriorityQueue ----

/// One A* queue entry; ordered on `f` alone, exactly like the Java comparator over `double[]`,
/// so ties fall out of the heap in the same order.
#[derive(Clone, Copy)]
pub struct QEntry {
    pub f: f64,
    pub g: f64,
    pub x: i16,
    pub y: i16,
    pub z: i16,
    pub dir: i8,
    pub turned: bool,
}

/// A binary heap with `java.util.PriorityQueue`'s exact sift-up/sift-down, so equal-`f` entries
/// pop in the same order as Java's and A* returns the same path among equal-cost ones.
pub struct JPq {
    q: Vec<QEntry>,
}

impl JPq {
    pub fn new() -> Self {
        JPq { q: Vec::with_capacity(1024) }
    }

    pub fn clear(&mut self) {
        self.q.clear();
    }

    pub fn is_empty(&self) -> bool {
        self.q.is_empty()
    }

    #[inline]
    pub fn push(&mut self, x: QEntry) {
        let i = self.q.len();
        self.q.push(x);
        if i > 0 {
            self.sift_up(i, x);
        }
    }

    // `f` is always a finite non-negative sum, so `Double.compare` reduces to `<` / `>` here.
    #[inline]
    fn sift_up(&mut self, mut k: usize, x: QEntry) {
        while k > 0 {
            let parent = (k - 1) >> 1;
            let e = self.q[parent];
            if !(x.f < e.f) {
                break;
            }
            self.q[k] = e;
            k = parent;
        }
        self.q[k] = x;
    }

    #[inline]
    pub fn pop(&mut self) -> Option<QEntry> {
        if self.q.is_empty() {
            return None;
        }
        let result = self.q[0];
        let x = self.q.pop().expect("non-empty");
        if !self.q.is_empty() {
            self.sift_down(0, x);
        }
        Some(result)
    }

    #[inline]
    fn sift_down(&mut self, mut k: usize, x: QEntry) {
        let size = self.q.len();
        let half = size >> 1;
        while k < half {
            let mut child = (k << 1) + 1;
            let mut c = self.q[child];
            let right = child + 1;
            if right < size && c.f > self.q[right].f {
                child = right;
                c = self.q[child];
            }
            if !(x.f > c.f) {
                break;
            }
            self.q[k] = c;
            k = child;
        }
        self.q[k] = x;
    }
}

// ---- java.util.HashMap iteration order ----

static TREEIFY_WARNED: AtomicBool = AtomicBool::new(false);
static TREEIFY_HITS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// How many times a bin grew past Java's treeify threshold, i.e. how many maps this process
/// ordered on an assumption Java would have broken. 0 means the emulation was exact throughout.
pub fn treeify_hits() -> usize {
    TREEIFY_HITS.load(AOrd::Relaxed)
}

#[inline]
fn hm_bin(key: i64, cap: i32) -> usize {
    let h = (key ^ ((key as u64) >> 32) as i64) as i32;
    let h = h ^ ((h as u32) >> 16) as i32;
    ((cap - 1) & h) as usize
}

/// The order a `HashMap<Long, ?>` filled with `keys` (in that order, all distinct) iterates in:
/// table bucket first, insertion order within a bucket. Buckets never reorder on resize, so this
/// is exact as long as no bin treeifies — which is detected and logged if it ever happens.
pub fn java_hashmap_order(keys: &[i64]) -> Vec<u32> {
    let n = keys.len();
    if n == 0 {
        return Vec::new();
    }
    let mut cap: i32 = 16;
    let mut thr: i32 = 12;
    let mut counts: Vec<u16> = vec![0; cap as usize];
    for (i, &k) in keys.iter().enumerate() {
        let b = hm_bin(k, cap);
        counts[b] += 1;
        if counts[b] == 9 && cap >= 64 {
            TREEIFY_HITS.fetch_add(1, AOrd::Relaxed);
            if !TREEIFY_WARNED.swap(true, AOrd::Relaxed) {
                eprintln!(
                    "[routerunner_lane] WARNING: a HashMap bin reached 9 entries (cap {}), so Java \
                     would treeify it and move that bin's tree root to the front of the bin; the \
                     emulated iteration order may diverge for that bin. See README.",
                    cap
                );
            }
        }
        if (i as i32 + 1) > thr {
            cap <<= 1;
            thr <<= 1;
            counts.clear();
            counts.resize(cap as usize, 0);
            for &k2 in &keys[..=i] {
                counts[hm_bin(k2, cap)] += 1;
            }
        }
    }
    let mut order: Vec<u32> = (0..n as u32).collect();
    order.sort_by_key(|&i| hm_bin(keys[i as usize], cap));
    order
}
