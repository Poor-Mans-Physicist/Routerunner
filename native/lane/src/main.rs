//! `lane_cli rooms.jsonl plans.jsonl legmodel.json [threads]` — the headless planner the replay
//! bundle builder drives, a drop-in replacement for `com.routerunner.lane.LaneCli`.

use std::collections::HashMap;
use std::io::{BufWriter, Write};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::Instant;

use base64::Engine;
use serde::Deserialize;

use routerunner_lane::export::{export, o, J};
use routerunner_lane::grid::{snap_inside, SolidGrid, P};
use routerunner_lane::jcompat::{jformat_f, pad_left, pad_right, treeify_hits};
use routerunner_lane::model::LegTimeModel;
use routerunner_lane::planner::{Params, Planner};

#[derive(Deserialize)]
struct GridIn {
    sx: i32,
    sy: i32,
    sz: i32,
    #[serde(rename = "solidZ")]
    solid_z: String,
}

fn def_chain_range() -> i32 {
    6
}

fn def_chain_limit() -> i32 {
    32
}

fn def_modes() -> Vec<String> {
    vec!["corridor".to_string(), "point".to_string()]
}

#[derive(Deserialize)]
struct RoomIn {
    key: String,
    grid: GridIn,
    chests: Vec<[i32; 3]>,
    entrance: [i32; 3],
    exit: [i32; 3],
    origin: [i32; 3],
    #[serde(rename = "tEntry", default)]
    t_entry: i64,
    #[serde(rename = "chainRange", default = "def_chain_range")]
    chain_range: i32,
    #[serde(rename = "chainLimit", default = "def_chain_limit")]
    chain_limit: i32,
    #[serde(default = "def_modes")]
    modes: Vec<String>,
    #[serde(default)]
    params: Option<HashMap<String, f64>>,
}

#[derive(Deserialize)]
struct RawModel {
    mean: Vec<f64>,
    scale: Vec<f64>,
    coef: Vec<f64>,
    intercept: f64,
    #[serde(default)]
    sigma: f64,
}

fn load_model(path: &str) -> Result<LegTimeModel, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {}", path, e))?;
    let raw: RawModel = serde_json::from_str(&text).map_err(|e| format!("{}: {}", path, e))?;
    if raw.coef.len() != 12 || raw.mean.len() != 12 || raw.scale.len() != 12 {
        return Err(format!("legmodel json at {} does not hold 12 coefficients", path));
    }
    let mut mean = [0f64; 12];
    let mut scale = [0f64; 12];
    let mut coef = [0f64; 12];
    mean.copy_from_slice(&raw.mean);
    scale.copy_from_slice(&raw.scale);
    coef.copy_from_slice(&raw.coef);
    Ok(LegTimeModel { mean, scale, coef, intercept: raw.intercept, sigma: raw.sigma })
}

/// Rebuild a grid from the run log's grid object: gzip + base64 BitSet, bit i = byte i/8 bit i%8
/// LSB first, cell index `(x*sy+y)*sz+z`, set = solid.
fn decode_grid(g: &GridIn) -> Result<SolidGrid, String> {
    let packed = base64::engine::general_purpose::STANDARD
        .decode(g.solid_z.as_bytes())
        .map_err(|e| format!("grid base64: {}", e))?;
    let mut raw = Vec::new();
    {
        use std::io::Read;
        let mut gz = flate2::read::GzDecoder::new(&packed[..]);
        gz.read_to_end(&mut raw).map_err(|e| format!("grid gzip: {}", e))?;
    }
    let mut grid = SolidGrid::new(g.sx, g.sy, g.sz);
    let n = (g.sx as i64 * g.sy as i64 * g.sz as i64) as usize;
    for i in 0..n {
        if (i >> 3) >= raw.len() {
            break;
        }
        if (raw[i >> 3] >> (i & 7)) & 1 != 0 {
            grid.solid[i] = true;
        }
    }
    grid.bake_clearance();
    Ok(grid)
}

fn plan_room(r: &RoomIn, model: &LegTimeModel) -> Result<J, String> {
    let grid = decode_grid(&r.grid)?;
    let chests: Vec<P> = r.chests.iter().map(|c| P::new(c[0], c[1], c[2])).collect();
    let entrance = snap_inside(&grid, P::new(r.entrance[0], r.entrance[1], r.entrance[2]));
    let exit = snap_inside(&grid, P::new(r.exit[0], r.exit[1], r.exit[2]));
    let mut out: Vec<(&str, J)> = vec![("key", J::S(r.key.clone()))];
    let mut log: Vec<J> = Vec::new();
    for mode in &r.modes {
        let t0 = Instant::now();
        let mut p = Params { point_mode: mode == "point", ..Default::default() };
        if let Some(m) = &r.params {
            if let Some(v) = m.get("timeScale") {
                p.time_scale = *v;
            }
            if let Some(v) = m.get("bailAggression") {
                p.bail_aggression = *v;
            }
            if let Some(v) = m.get("sweepGain") {
                p.sweep_gain = *v;
            }
            if let Some(v) = m.get("proxyTopK") {
                p.proxy_top_k = *v as i32;
            }
            if let Some(v) = m.get("beamWidth") {
                p.beam_width = *v as i32;
            }
            if let Some(v) = m.get("minLaneLen") {
                p.min_lane_len = *v as i32;
            }
            if let Some(v) = m.get("minLaneClr") {
                p.min_lane_clr = *v as i32;
            }
            if let Some(v) = m.get("turnCap") {
                p.turn_cap = *v;
            }
            if let Some(v) = m.get("breakReach") {
                p.break_reach = *v;
            }
        }
        // The grid is cheap to rebuild and the planner owns it, matching the Java CLI's
        // one-planner-per-mode construction.
        let g2 = decode_grid(&r.grid)?;
        let mut planner = Planner::new(
            g2,
            chests.clone(),
            r.chain_range,
            r.chain_limit,
            p,
            model.clone(),
        );
        let plan = planner.plan_all(entrance, exit);
        let ex = export(
            &planner.room,
            &plan,
            r.origin[0],
            r.origin[1],
            r.origin[2],
            r.t_entry,
        );
        out.push((leak_mode(mode), ex));
        // Timed exactly where LaneCli times it: planning plus the export of the plan.
        let secs = t0.elapsed().as_secs_f64();
        log.push(J::S(format!(
            "  {} {} chests {} lanes {} runs {} cover {} model {} s  ({} s)",
            pad_right(&r.key, 8),
            pad_right(mode, 9),
            pad_left(&chests.len().to_string(), 4),
            pad_left(&plan.lanes.len().to_string(), 3),
            pad_left(&plan.runs.len().to_string(), 3),
            jformat_f(plan.cover, 2),
            pad_left(&jformat_f(plan.t_total, 1), 5),
            jformat_f(secs, 2)
        )));
    }
    let _ = grid;
    out.push(("log", J::A(log)));
    Ok(o(out))
}

/// Mode names come from the room file; the JSON writer wants a `&str` that outlives the object.
fn leak_mode(m: &str) -> &'static str {
    match m {
        "point" => "point",
        "corridor" => "corridor",
        other => Box::leak(other.to_string().into_boxed_str()),
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 3 {
        eprintln!("usage: lane_cli rooms.jsonl plans.jsonl legmodel.json [threads]");
        std::process::exit(2);
    }
    let model = match load_model(&args[2]) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("[LaneCli] {}", e);
            std::process::exit(1);
        }
    };
    let threads: usize = if args.len() > 3 {
        args[3].parse().unwrap_or(1)
    } else {
        std::cmp::max(1, std::thread::available_parallelism().map(|v| v.get()).unwrap_or(2) - 1)
    };
    let text = match std::fs::read_to_string(&args[0]) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("[LaneCli] {}: {}", args[0], e);
            std::process::exit(1);
        }
    };
    let lines: Vec<&str> = text.lines().collect();
    let n_lines = lines.len();
    let t0 = Instant::now();
    let results: Mutex<Vec<Option<String>>> = Mutex::new(vec![None; n_lines]);
    let next = AtomicUsize::new(0);
    let done = AtomicUsize::new(0);
    std::thread::scope(|s| {
        for _ in 0..threads {
            s.spawn(|| loop {
                let i = next.fetch_add(1, Ordering::SeqCst);
                if i >= n_lines {
                    break;
                }
                let line = lines[i];
                if line.trim().is_empty() {
                    continue;
                }
                let result = match serde_json::from_str::<RoomIn>(line)
                    .map_err(|e| e.to_string())
                    .and_then(|r| plan_room(&r, &model))
                {
                    Ok(j) => j.to_string(),
                    Err(e) => {
                        eprintln!("[LaneCli] room failed: {}", e);
                        o(vec![("error", J::S(e))]).to_string()
                    }
                };
                results.lock().expect("results mutex").get_mut(i).map(|v| *v = Some(result));
                let k = done.fetch_add(1, Ordering::SeqCst) + 1;
                if k % 10 == 0 {
                    eprintln!(
                        "[LaneCli] {}/{} rooms, {} s",
                        k,
                        n_lines,
                        jformat_f(t0.elapsed().as_secs_f64(), 1)
                    );
                }
            });
        }
    });
    let results = results.into_inner().expect("results mutex");
    match std::fs::File::create(&args[1]) {
        Ok(f) => {
            let mut w = BufWriter::new(f);
            for r in results.into_iter().flatten() {
                let _ = w.write_all(r.as_bytes());
                let _ = w.write_all(b"\n");
            }
            let _ = w.flush();
        }
        Err(e) => {
            eprintln!("[LaneCli] {}: {}", args[1], e);
            std::process::exit(1);
        }
    }
    if treeify_hits() > 0 {
        eprintln!(
            "[LaneCli] note: {} HashMap bins crossed Java's treeify threshold; see README.",
            treeify_hits()
        );
    }
    eprintln!(
        "[LaneCli] {} rooms in {} s on {} threads",
        done.load(Ordering::SeqCst),
        jformat_f(t0.elapsed().as_secs_f64(), 1),
        threads
    );
}
