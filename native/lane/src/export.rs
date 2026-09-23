//! JSON output: a minimal writer that formats doubles exactly like `Double.toString` (so the
//! bytes line up with Gson's), plus the two documents the callers want — `LanePlanner.export`
//! for the replay viewer and the compact plan the JNI surface returns.

use crate::grid::{standable_p, P};
use crate::jcompat::jdouble;
use crate::jcompat::jround;
use crate::planner::{Plan, Room};

pub enum J {
    Null,
    Bool(bool),
    I(i64),
    D(f64),
    S(String),
    A(Vec<J>),
    O(Vec<(String, J)>),
}

impl J {
    pub fn write(&self, out: &mut String) {
        match self {
            J::Null => out.push_str("null"),
            J::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
            J::I(v) => out.push_str(&v.to_string()),
            J::D(v) => out.push_str(&jdouble(*v)),
            J::S(s) => {
                out.push('"');
                for ch in s.chars() {
                    match ch {
                        '"' => out.push_str("\\\""),
                        '\\' => out.push_str("\\\\"),
                        '\n' => out.push_str("\\n"),
                        '\r' => out.push_str("\\r"),
                        '\t' => out.push_str("\\t"),
                        c if (c as u32) < 0x20 => {
                            out.push_str(&format!("\\u{:04x}", c as u32));
                        }
                        c => out.push(c),
                    }
                }
                out.push('"');
            }
            J::A(v) => {
                out.push('[');
                for (i, e) in v.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    e.write(out);
                }
                out.push(']');
            }
            J::O(v) => {
                out.push('{');
                for (i, (k, e)) in v.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    J::S(k.clone()).write(out);
                    out.push(':');
                    e.write(out);
                }
                out.push('}');
            }
        }
    }

    pub fn to_string(&self) -> String {
        let mut s = String::new();
        self.write(&mut s);
        s
    }
}

pub fn o(v: Vec<(&str, J)>) -> J {
    J::O(v.into_iter().map(|(k, e)| (k.to_string(), e)).collect())
}

fn cell(p: P, ox: i32, oy: i32, oz: i32) -> J {
    J::A(vec![
        J::I((p.x + ox) as i64),
        J::I((p.y + oy) as i64),
        J::I((p.z + oz) as i64),
    ])
}

fn r2(v: f64) -> f64 {
    jround(v * 100.0) as f64 / 100.0
}

fn r1(v: f64) -> f64 {
    jround(v * 10.0) as f64 / 10.0
}

/// Port of `LanePlanner.export`: world coordinates, absolute active-clock milliseconds.
pub fn export(r: &Room, plan: &Plan, ox: i32, oy: i32, oz: i32, t_entry: i64) -> J {
    let rr = r.p.break_reach;
    let ri = rr as i32;
    let mut runs: Vec<J> = Vec::new();
    for ks in plan.runs.iter() {
        let mut poly: Vec<P> = Vec::new();
        let mut yield_ = 0i64;
        for &k in ks {
            let e = &plan.lanes[k];
            yield_ += e.yield_ as i64;
            let mut pts: Vec<P> = e.trans.clone();
            if e.cells.len() > 1 {
                pts.extend_from_slice(&e.cells);
            }
            for c in pts {
                if poly.is_empty() || poly[poly.len() - 1] != c {
                    poly.push(c);
                }
            }
        }
        let mut carpet_seen: std::collections::HashSet<i64> = std::collections::HashSet::new();
        let mut carpet: Vec<J> = Vec::new();
        for c in poly.iter() {
            for dx in -ri..=ri {
                for dz in -ri..=ri {
                    if (dx * dx + dz * dz) as f64 > rr * rr {
                        continue;
                    }
                    for dy in [0, 1, -1] {
                        let q = P::new(c.x + dx, c.y + dy, c.z + dz);
                        if standable_p(&r.g, q) {
                            let key = ((q.x as i64) << 40)
                                | (((q.y + 512) as i64) << 20)
                                | (q.z + 512) as i64;
                            if carpet_seen.insert(key) {
                                carpet.push(cell(q, ox, oy, oz));
                            }
                            break;
                        }
                    }
                }
            }
        }
        let poly_w: Vec<J> = poly.iter().map(|c| cell(*c, ox, oy, oz)).collect();
        let t0 = t_entry as f64 + plan.lane_t[ks[0]];
        let last_k = ks[ks.len() - 1];
        let t1 = t_entry as f64
            + if last_k + 1 < plan.lane_t.len() {
                plan.lane_t[last_k + 1]
            } else if plan.ghost.is_empty() {
                plan.lane_t[last_k]
            } else {
                plan.ghost[plan.ghost.len() - 1][0]
            };
        runs.push(o(vec![
            ("poly", J::A(poly_w)),
            ("carpet", J::A(carpet)),
            ("tStart", J::D(t0)),
            ("tEnd", J::D(t1)),
            ("lanes", J::A(ks.iter().map(|k| J::I(*k as i64)).collect())),
            ("yield_", J::I(yield_)),
        ]));
    }
    let ghost: Vec<J> = plan
        .ghost
        .iter()
        .map(|s| {
            J::A(vec![
                J::D(jround(t_entry as f64 + s[0]) as f64),
                J::D(r2(s[1] + ox as f64)),
                J::D(r2(s[2] + oy as f64)),
                J::D(r2(s[3] + oz as f64)),
                J::D(r1(s[4])),
                J::D(r1(s[5])),
            ])
        })
        .collect();
    let mut clears: Vec<J> = Vec::new();
    for (t, _chest, cleared) in plan.clears.iter() {
        let tm = jround(t_entry as f64 + *t);
        for j in cleared {
            let ch = r.chests[*j as usize];
            clears.push(J::A(vec![
                J::I(tm),
                J::I((ch.x + ox) as i64),
                J::I((ch.y + oy) as i64),
                J::I((ch.z + oz) as i64),
            ]));
        }
    }
    let mut heat: Vec<J> = Vec::new();
    for (k, hk) in plan.heat.iter().enumerate() {
        let items: Vec<J> = hk
            .iter()
            .map(|(i, v)| {
                let ch = r.chests[*i as usize];
                J::A(vec![
                    J::I((ch.x + ox) as i64),
                    J::I((ch.y + oy) as i64),
                    J::I((ch.z + oz) as i64),
                    J::I(*v as i64),
                ])
            })
            .collect();
        heat.push(o(vec![
            ("t", J::I(jround(t_entry as f64 + plan.lane_t[k]))),
            ("items", J::A(items)),
        ]));
    }
    let exit_w: Vec<J> = match &plan.exit_path {
        Some(p) => p.iter().map(|c| cell(*c, ox, oy, oz)).collect(),
        None => Vec::new(),
    };
    let t_end = if plan.ghost.is_empty() {
        t_entry as f64
    } else {
        jround(t_entry as f64 + plan.ghost[plan.ghost.len() - 1][0]) as f64
    };
    o(vec![
        ("exitPath", J::A(exit_w)),
        ("exitStraight", J::Bool(plan.exit_straight)),
        ("runs", J::A(runs)),
        ("ghost", J::A(ghost)),
        ("clears", J::A(clears)),
        ("heat", J::A(heat)),
        ("tTotal", J::D(plan.t_total)),
        ("yieldTotal", J::I(plan.yield_total as i64)),
        ("cover", J::D(plan.cover)),
        ("nLanes", J::I(plan.lanes.len() as i64)),
        ("nRuns", J::I(plan.runs.len() as i64)),
        ("tEntry", J::I(t_entry)),
        ("tEnd", J::D(t_end)),
    ])
}

/// The compact room-local plan the JNI `plan` call returns.
pub fn plan_json(plan: &Plan) -> J {
    let lanes: Vec<J> = plan
        .lanes
        .iter()
        .map(|e| {
            o(vec![
                ("trans", J::A(e.trans.iter().map(|c| cell(*c, 0, 0, 0)).collect())),
                ("cells", J::A(e.cells.iter().map(|c| cell(*c, 0, 0, 0)).collect())),
                ("yield", J::I(e.yield_ as i64)),
                ("yieldTrans", J::I(e.yield_trans as i64)),
                ("tTrans", J::D(e.t_trans)),
                ("tLane", J::D(e.t_lane)),
                ("rate", J::D(e.rate)),
                ("rateX", J::D(e.rate_x)),
                ("dExit", J::D(e.d_exit)),
                ("end", cell(e.end, 0, 0, 0)),
                ("dir", J::A(vec![J::D(e.dir[0]), J::D(e.dir[1])])),
                ("endBurst", J::I(e.end_burst as i64)),
                ("align", J::D(e.align)),
                ("tStart", J::D(e.t_start)),
            ])
        })
        .collect();
    let runs: Vec<J> = plan
        .runs
        .iter()
        .map(|r| J::A(r.iter().map(|k| J::I(*k as i64)).collect()))
        .collect();
    let exit = match &plan.exit_path {
        Some(p) => J::A(p.iter().map(|c| cell(*c, 0, 0, 0)).collect()),
        None => J::Null,
    };
    o(vec![
        ("lanes", J::A(lanes)),
        ("runs", J::A(runs)),
        ("exitPath", exit),
        ("exitStraight", J::Bool(plan.exit_straight)),
        ("tTotal", J::D(plan.t_total)),
        ("tExit", J::D(plan.t_exit)),
        ("bail", J::D(plan.bail)),
        ("opportunity", J::D(plan.opportunity)),
        ("yieldTotal", J::I(plan.yield_total as i64)),
        ("cover", J::D(plan.cover)),
        ("nCorridors", J::I(plan.n_corridors as i64)),
    ])
}

/// A bare `[[x,y,z],...]` list, for the JNI `path` call.
pub fn path_json(path: &[P]) -> J {
    J::A(path.iter().map(|c| cell(*c, 0, 0, 0)).collect())
}
