"""Every lap the owner ran on Routerunner 1.2.0-test9 .. 1.2.0 (instance 4, 2026-09-27/28), against the v2.2 benchmark sheet.

Per segment (vault x lap x miner x routing mode, 8+ looted rooms):
  raw        chests over room time + room switches (switch > 30 s dropped), the rate the sheet predicts;
  sheet      the v2.2 tile for the crystal, converted from speed 0.400 to the played movement speed; freehand segments
             get the sheet without the room picker's gain;
  eff        real / expected on the SAME rooms: every looted room re-planned the way the sheet was made (shape model,
             played speed, sheet-rate pruning, the player's real entry point; research/2026-09-28_multi/reprice.py).
             It removes room luck: eff 1.00 = the sheet's method predicts this run exactly on these rooms;
  adj        sheet x eff: the rate this play would reach on the sheet's average vault of that crystal;
  onroute    share of the player's in-room trail within ON_ROUTE blocks (3 chain, 6 vein: vein play sits ~3.5 blocks off the
             line by design) of the lane route drawn at that moment (the latest lane_plan at or before each sample), over
             routed rooms. Computed the same way for every build (the logged room_diff.accuracy / follow compared against the
             waypoint path before 1.2.0-test12 and the lane plan after, so they are not comparable across builds);
  plancov    chests broken per chest the room's first plan counted;
  speed      movement speed attribute (persistent, median);
  exec       execution speed against the benchmark (research/2026-09-28_player_calib: the player's own trail priced by the
             shape model, over real idle-free time, with the benchmark curve), 1.00 = the benchmark.

python runs_data.py -> runs.json
"""
import collections
import json
import math
import os
import pickle
import subprocess
import sys

import numpy as np

sys.path.insert(0, r'C:\Users\river\routerunner\research\2026-09-28_multi')
sys.path.insert(0, r'C:\Users\river\routerunner\research\2026-09-27_panel_v22')
import common as C

HERE = os.path.dirname(os.path.abspath(__file__))
V22 = {(d['b'], d['c']): d for d in json.load(open(r'C:\Users\river\routerunner\research\2026-09-27_panel_v22\cells_v22.json'))}
PICK = {'chain': 1.053, 'vein': 1.070}
ELAST = {'chain': 0.254, 'vein': 0.194}
COV = {'chain': 0.900, 'vein': 0.924}
SW = {'chain': 1.03, 'vein': 1.18}
ON_ROUTE = {'chain': 3.0, 'vein': 6.0}
BENCH = {'chain': (0.8365, 0.0072), 'vein': (0.9784, -0.0331)}
NEW = '20260928_135147'
CRYSTAL = {'20260927_221406': (45, 45), '20260927_225357': (90, 90), '20260927_230557': (36, 150), '20260927_231713': (90, 90),
           '20260927_232319': (75, 30), '20260927_233552': (61, 12), '20260927_234639': (30, 42), '20260927_235948': (30, 42),
           '20260928_001647': (30, 42), NEW: (21, 56)}
BUILD = {'20260927_221406': '1.2.0-test9', NEW: '1.2.0'}
rng = np.random.default_rng(11)


def sheet(b, c, miner, speed, routed):
    cell = V22.get((3 * round(b / 3), 3 * round(c / 3)))
    if not cell or cell[miner] is None:
        return None
    v = cell[miner] * (speed / 0.4) ** ELAST[miner]
    return v if routed else v / PICK[miner]


def rooms():
    out = []
    for v in CRYSTAL:
        recs, ev = C.load(v)
        enter = ev['vault_enter'][0]
        miners = [(r['t'], r['mode']) for r in ev.get('miner', [])]
        rr = C.rooms_of(ev)
        for r in rr:
            r['vault'] = v
            r['build'] = enter.get('modVersion')
            r['miner'] = next((m[1] for m in reversed(miners) if m[0] <= r['firstT']), miners[0][1] if miners else None)
        plans = collections.defaultdict(list)
        for p in ev.get('lane_plan', []):
            if p.get('runList'):
                pts = np.array([q for run in p['runList'] for q in run['poly']], float)
                plans[p['cellKey']].append((p['t'], pts))
        for r in rr:
            r['onroute'] = on_route(r, plans.get(f"{r['cell'][0]},{r['cell'][1]}", []))
        calib = {(p['cellKey'], ): p for p in ev.get('player_calib', [])}
        for r in rr:
            p = calib.get((f"{r['cell'][0]},{r['cell'][1]}",))
            r['calib'] = p if p and abs(p['t'] - r['lastT']) < 120000 else None
        out += rr
    return out


def on_route(r, plans):
    """(samples within ON_ROUTE, samples) of a routed room's trail against the plan current at each sample; None when
    the room was not routed or has no plan or trail."""
    tr = r['diff'].get('trail') or []
    if not r['routing'] or not plans or len(tr) < 3:
        return None
    ok = [(t, pts) for t, pts in sorted(plans, key=lambda x: x[0]) if t <= r['lastT']]
    if not ok:
        return None
    o = r['entry']['origin'] if r.get('entry') and r['entry'].get('origin') else None
    if o is None:
        return None
    lim = ON_ROUTE.get(r['miner'], 3.0)
    near = 0
    for smp in tr:
        ts = smp[3]
        cur = ok[0][1]
        for t, pts in ok:
            if t <= ts + 500:
                cur = pts
            else:
                break
        w = np.array([smp[0] + o[0], smp[1] + o[1], smp[2] + o[2]])
        d = np.sqrt(((cur[:, 0] + 0.5 - w[0]) ** 2 + (cur[:, 1] - w[1]) ** 2 + (cur[:, 2] + 0.5 - w[2]) ** 2).min())
        near += d <= lim
    return near, len(tr)


def reprice_new(R, known):
    """Re-plan (the sheet's way) every room of the vaults not in the multi-session reprice.json."""
    import reprice
    import panel
    want = [r for r in R if f"cell|0|0|{r['vault']}|{r['cell'][0]},{r['cell'][1]}|{r['firstT']}" not in known]
    vaults = sorted({r['vault'] for r in want})
    if not vaults:
        return {}
    C.VAULTS[:] = vaults
    for v in vaults:
        reprice.CRYSTAL[v] = CRYSTAL[v]
    todo = reprice.rooms()
    print(f'repricing {len(todo)} rooms of {vaults}', flush=True)
    res = collections.defaultdict(dict)
    from multiprocessing import Pool
    with Pool(20) as pool:
        for miner in ('chain', 'vein'):
            args = []
            for r in todo:
                b, c = CRYSTAL[r['vault']]
                sh = sheet(b, c, miner, r['speed'], True)
                args.append((r, miner, (sh or 1500) / 60, r['speed']))
            inp, outp = os.path.join(HERE, '_in.jsonl'), os.path.join(HERE, '_out.jsonl')
            with open(inp, 'w') as f:
                for line, _ in pool.imap(reprice.prep_at, args, chunksize=4):
                    f.write(line + '\n')
            p = subprocess.run([panel.LANE, inp, outp, panel.SHAPE, panel.THREADS], capture_output=True, text=True)
            if p.returncode != 0:
                sys.exit('ERROR lane_cli: ' + p.stderr[-1500:])
            for line in open(outp):
                d = json.loads(line)
                if 'point' in d:
                    res[d['key'].rsplit('|', 1)[0]][miner] = [d['point']['yieldTotal'], d['point']['tTotal'], d['point']['nTrig']]
            os.remove(inp)
            os.remove(outp)
    return res


def bench_k(miner, clump):
    a, b = BENCH[miner]
    return a + b * math.log(min(1000.0, max(5.0, clump)))


def main():
    R = rooms()
    rep = json.load(open(r'C:\Users\river\routerunner\research\2026-09-28_multi\reprice.json'))
    new = reprice_new(R, rep)
    print(f'repriced {len(new)} new rooms')
    rep.update(new)
    trail = {(t['vault'], t['cell'], t['firstT']): t for t in json.load(open(r'C:\Users\river\routerunner\research\2026-09-28_player_calib\trail_rooms.json'))
             if t['who'] == 'owner'}
    segs = collections.defaultdict(list)
    for r in R:
        mode = 'freehand' if not r['routing'] else (r['tm'] or '?')
        segs[(r['vault'], r['lap'], r['miner'], mode)].append(r)
    out, scatter = [], []
    for (v, lap, miner, mode), S in sorted(segs.items(), key=lambda kv: (kv[0][0], kv[0][1] or 0)):
        if len(S) < 8 or miner not in COV:
            continue
        b, c = CRYSTAL[v]
        gap = lambda r: r['gap'] if r['gap'] is not None and r['gap'] < 30 else SW[miner]
        t = sum(r['sec'] + gap(r) for r in S)
        ch = sum(r['chests'] for r in S)
        speed = float(np.median([r['speed'] for r in S if r['speed']]))
        sh = sheet(b, c, miner, speed, mode != 'freehand')
        A = []
        for r in S:
            e = rep.get(f"cell|0|0|{v}|{r['cell'][0]},{r['cell'][1]}|{r['firstT']}", {}).get(miner)
            if e and e[0] > 0:
                A.append((r['chests'], r['sec'] + gap(r), COV[miner] * e[0], e[1] + SW[miner]))
        if len(A) < 8:
            print(f'SKIP {v} lap {lap} {miner} {mode}: only {len(A)} of {len(S)} rooms re-priced')
            continue
        A = np.array(A)
        ratio = lambda a: (a[:, 0].sum() / a[:, 1].sum()) / (a[:, 2].sum() / a[:, 3].sum())
        eff = ratio(A)
        boots = [ratio(A[rng.integers(0, len(A), len(A))]) for _ in range(2000)]
        lo, hi = np.percentile(boots, [5, 95])
        exp_rooms = 60 * A[:, 2].sum() / A[:, 3].sum()
        routed = [r for r in S if r['routing']]
        orr = [r['onroute'] for r in routed if r.get('onroute')]
        pc = [r for r in routed if r.get('pyield')]
        num = den = 0.0
        for r in S:
            tr = trail.get((v, f"{r['cell'][0]},{r['cell'][1]}", r['firstT']))
            if tr and tr['real'] > 2:
                num += tr['pred'] * bench_k(miner, r['clump'])
                den += tr['real']
            elif r.get('calib') and r['calib']['realS'] > 2:
                num += r['calib']['benchS']
                den += r['calib']['realS']
        seg = dict(vault=v, build=S[0]['build'], lap=lap, miner=miner, mode=mode, crystal=f'{b}/{c}', rooms=len(S), minutes=t / 60,
                   raw=60 * ch / t, sheet=sh, raw_ratio=(60 * ch / t) / sh if sh else None, exp_rooms=exp_rooms, eff=eff, lo=lo, hi=hi,
                   adj=eff * sh if sh else None, density=float(np.mean([r['n'] for r in S])),
                   clump=float(np.mean([r['clump'] for r in S if r['n'] > 0])),
                   sheet_density=V22[(3 * round(b / 3), 3 * round(c / 3))]['n'], sheet_clump=V22[(3 * round(b / 3), 3 * round(c / 3))]['clump'],
                   speed=speed, onroute=sum(a for a, _ in orr) / sum(n for _, n in orr) if orr else None, onroute_rooms=len(orr),
                   plancov=sum(r['chests'] for r in pc) / sum(r['pyield'] for r in pc) if pc else None,
                   replans=float(np.mean([r['replans'] for r in routed])) if routed else None,
                   exec=num / den if den > 0 else None)
        out.append(seg)
        for row in A:
            scatter.append(dict(seg=len(out) - 1, real=60 * row[0] / row[1], exp=60 * row[2] / row[3], sec=row[1]))
        print(f"{v} lap {lap} {miner:5s} {mode:8s} {b}/{c}: raw {seg['raw']:6.0f} sheet {sh or float('nan'):6.0f} eff {eff:.3f} [{lo:.2f},{hi:.2f}] "
              f"adj {seg['adj'] or float('nan'):6.0f} onroute {seg['onroute'] if seg['onroute'] is not None else float('nan'):.2f} "
              f"plancov {seg['plancov'] if seg['plancov'] is not None else float('nan'):.2f} exec {seg['exec'] or float('nan'):.2f} speed {speed:.3f}")
    json.dump(dict(segments=out, rooms=scatter), open(os.path.join(HERE, 'runs.json'), 'w'), indent=1, default=float)


if __name__ == '__main__':
    main()
