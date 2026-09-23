//! The JNI surface for `com.routerunner.lane.NativeLane`.
//!
//! One handle owns a [`Planner`] and every cache it builds, so replans on the same room reuse the
//! reach, component, landing and exit-time caches exactly like the Java planner does. A panic must
//! never unwind into the JVM, so every entry point catches it and returns null / 0.

use std::panic::{catch_unwind, AssertUnwindSafe};

use ::jni::objects::{JByteArray, JClass, JDoubleArray, JIntArray};
use ::jni::sys::{jint, jlong, jstring};
use ::jni::JNIEnv;

use crate::export::{path_json, plan_json};
use crate::grid::{SolidGrid, P};
use crate::model::LegTimeModel;
use crate::planner::{Params, Planner};

fn read_ints(env: &mut JNIEnv, a: &JIntArray) -> Option<Vec<i32>> {
    let len = env.get_array_length(a).ok()?;
    let mut buf = vec![0i32; len as usize];
    env.get_int_array_region(a, 0, &mut buf).ok()?;
    Some(buf)
}

fn read_doubles(env: &mut JNIEnv, a: &JDoubleArray) -> Option<Vec<f64>> {
    let len = env.get_array_length(a).ok()?;
    let mut buf = vec![0f64; len as usize];
    env.get_double_array_region(a, 0, &mut buf).ok()?;
    Some(buf)
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Build a planner over a room. `solid_bits` is the same BitSet encoding the CLI grid uses
/// (bit i of byte i/8, LSB first, cell index `(x*sy+y)*sz+z`, set = solid); `model` is
/// mean\[12\], scale\[12\], coef\[12\], intercept, and optionally sigma. Returns 0 on failure.
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_routerunner_lane_NativeLane_create(
    mut env: JNIEnv,
    _class: JClass,
    sx: jint,
    sy: jint,
    sz: jint,
    solid_bits: JByteArray,
    chests: JIntArray,
    chain_range: jint,
    chain_limit: jint,
    model: JDoubleArray,
) -> jlong {
    let r = catch_unwind(AssertUnwindSafe(|| -> Option<jlong> {
        if sx <= 0 || sy <= 0 || sz <= 0 {
            return None;
        }
        let bits = env.convert_byte_array(&solid_bits).ok()?;
        let cv = read_ints(&mut env, &chests)?;
        let mv = read_doubles(&mut env, &model)?;
        if mv.len() < 37 || cv.len() % 3 != 0 {
            return None;
        }
        let mut g = SolidGrid::new(sx, sy, sz);
        let n = (sx as i64 * sy as i64 * sz as i64) as usize;
        for i in 0..n {
            if (i >> 3) >= bits.len() {
                break;
            }
            if (bits[i >> 3] >> (i & 7)) & 1 != 0 {
                g.solid[i] = true;
            }
        }
        g.bake_clearance();
        let mut pts: Vec<P> = Vec::with_capacity(cv.len() / 3);
        for c in cv.chunks_exact(3) {
            pts.push(P::new(c[0], c[1], c[2]));
        }
        let mut mean = [0f64; 12];
        let mut scale = [0f64; 12];
        let mut coef = [0f64; 12];
        mean.copy_from_slice(&mv[0..12]);
        scale.copy_from_slice(&mv[12..24]);
        coef.copy_from_slice(&mv[24..36]);
        let lm = LegTimeModel {
            mean,
            scale,
            coef,
            intercept: mv[36],
            sigma: if mv.len() > 37 { mv[37] } else { 0.0 },
        };
        let boot = Params { point_mode: true, ..Params::default() };
        let p = Planner::new(g, pts, chain_range, chain_limit, boot, lm);
        Some(Box::into_raw(Box::new(p)) as jlong)
    }));
    match r {
        Ok(Some(h)) => h,
        _ => 0,
    }
}

/// Plan from `(ex,ey,ez)` to `(xx,xy,xz)` over the chests whose mask byte is non-zero.
/// Returns the plan as JSON, or null on failure.
#[no_mangle]
#[allow(non_snake_case, clippy::too_many_arguments)]
pub extern "system" fn Java_com_routerunner_lane_NativeLane_plan(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    ex: jint,
    ey: jint,
    ez: jint,
    xx: jint,
    xy: jint,
    xz: jint,
    mask: JByteArray,
    params: JDoubleArray,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| -> Option<String> {
        if handle == 0 {
            return None;
        }
        let planner = unsafe { &mut *(handle as *mut Planner) };
        let m = env.convert_byte_array(&mask).ok()?;
        let pv = read_doubles(&mut env, &params)?;
        if pv.len() < 33 {
            return None;
        }
        let n = planner.room.chests.len();
        if m.len() < n {
            return None;
        }
        let mut remaining = vec![false; n];
        for i in 0..n {
            remaining[i] = m[i] != 0;
        }
        planner.room.p = params_from(&pv);
        planner.ensure_corridors();
        let plan = planner.plan(P::new(ex, ey, ez), P::new(xx, xy, xz), remaining);
        Some(plan_json(&plan).to_string())
    }));
    match r {
        Ok(Some(s)) => to_jstring(&mut env, &s),
        _ => std::ptr::null_mut(),
    }
}

/// Grounded A* between two cells, else a two-hop flight; JSON `[[x,y,z],...]` or null.
#[no_mangle]
#[allow(non_snake_case, clippy::too_many_arguments)]
pub extern "system" fn Java_com_routerunner_lane_NativeLane_path(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    ax: jint,
    ay: jint,
    az: jint,
    bx: jint,
    by: jint,
    bz: jint,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| -> Option<String> {
        if handle == 0 {
            return None;
        }
        let planner = unsafe { &mut *(handle as *mut Planner) };
        let p = planner.path(P::new(ax, ay, az), P::new(bx, by, bz))?;
        Some(path_json(&p).to_string())
    }));
    match r {
        Ok(Some(s)) => to_jstring(&mut env, &s),
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_routerunner_lane_NativeLane_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut Planner));
            }
        }
    }));
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_routerunner_lane_NativeLane_version(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| to_jstring(&mut env, "rust-1")));
    match r {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// The 33 planner parameters, in the order the Java side packs them.
fn params_from(v: &[f64]) -> Params {
    Params {
        break_reach: v[0],
        headings: v[1] as i32,
        seed_spacing: v[2] as i32,
        min_lane_len: v[3] as i32,
        min_lane_clr: v[4] as i32,
        max_step_up: v[5] as i32,
        max_drop: v[6] as i32,
        entry_spacing: v[7] as i32,
        proxy_top_k: v[8] as i32,
        beam_width: v[9] as i32,
        lookahead: v[10] != 0.0,
        turn_cap: v[11],
        merge_trans: v[12],
        max_run_len: v[13],
        bail_aggression: v[14],
        turnaround_deg: v[15],
        turnaround_penalty_s: v[16],
        allow_fly: v[17] != 0.0,
        fly_penalty_s: v[18],
        sweep_gain: v[19],
        time_scale: v[20],
        point_mode: v[21] != 0.0,
        ghost_noise: v[22],
        seed: v[23] as i64,
        max_lanes: v[24] as i32,
        trigger_s: v[25],
        strand_penalty_s: v[26],
        opportunity_floor: v[27],
        exit_weight: v[28],
        max_trans_len: v[29],
        far_tries: v[30] as i32,
        far_ok: v[31] as i32,
        bail_floor: v[32],
    }
}
