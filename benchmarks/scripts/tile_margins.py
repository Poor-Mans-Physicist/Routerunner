"""Error margins for every tile of the v2.2 chain/vein panel.

A tile's rate is  cpm = 60 x coverage x sum(planned chests) / sum(planned seconds + room switch)  over its 64 simulated
rooms, times the room picker's flat gain (1.053 chain, 1.070 vein). Three independent error sources, combined in
quadrature into a 95 % margin on the tile's expected rate:

  sampling   the 64 rooms are a sample: 2,000-fold room bootstrap of the ratio (per tile and miner);
  model      how far real play lands from the model on the SAME rooms (runs.json, segment eff = real / expected on the
             rooms actually looted): the spread of log(eff) over the owner's shape-routed segments, per miner family,
             excluding laps flagged as planner bugs since fixed;
  picker     the picker's gain was fitted flat over 8 smoke-test crystals; the spread of those per-crystal gains.

Separately, 'single run': the range one ~10-minute run of that crystal lands in, adding the vault-to-vault room luck
measured on the same segments (raw / sheet divided by eff).

python tile_margins.py -> tile_margins.json, validation_summary.json
"""
import collections
import json
import math
import os
import statistics as st

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
P22 = r'C:\Users\river\routerunner\research\2026-09-27_panel_v22'
COV = {'chain': 0.900, 'vein': 0.924}
SW = {'chain': 1.03, 'vein': 1.18}
PF = json.load(open(os.path.join(P22, 'picker_factor.json')))
BUGGED = {('20260927_235948', 2)}
rng = np.random.default_rng(7)
B = 2000


def pick(miner):
    return math.exp(PF[miner]['a'])


def main():
    runs = json.load(open(os.path.join(HERE, 'runs.json')))['segments']
    val = [s for s in runs if s['mode'] == 'shape' and (s['vault'], s['lap']) not in BUGGED]
    sig = {}
    for fam in ('chain', 'vein'):
        e = [math.log(s['eff']) for s in val if s['miner'] == fam]
        sig[fam] = dict(n=len(e), bias=math.exp(st.mean(e)) - 1, sd=st.pstdev(e) if len(e) > 1 else 0.0)
    pooled = [math.log(s['eff']) for s in val]
    sig_pool = st.pstdev(pooled)
    for fam in sig:
        sig[fam]['used'] = max(sig[fam]['sd'], sig_pool)
    luck = [math.log(s['raw_ratio'] / s['eff']) for s in val if s['raw_ratio']]
    sig_luck = st.pstdev(luck)
    pick_sd = {m: st.pstdev([c[3] / 100 for c in PF[m]['cells']]) for m in ('chain', 'vein')}
    low = [s for s in val if s['miner'] == 'vein' and s['clump'] < 80]
    high = [s for s in val if s['miner'] == 'vein' and s['clump'] >= 80]
    regime = dict(vein_low=dict(n=len(low), eff=math.exp(st.mean(math.log(s['eff']) for s in low)) if low else None),
                  vein_high=dict(n=len(high), eff=math.exp(st.mean(math.log(s['eff']) for s in high)) if high else None))
    print('model sigma', {k: {kk: round(vv, 4) if isinstance(vv, float) else vv for kk, vv in v.items()} for k, v in sig.items()},
          'pooled', round(sig_pool, 4), 'luck', round(sig_luck, 4), 'picker', {k: round(v, 4) for k, v in pick_sd.items()}, regime)

    out = {}
    for line in open(os.path.join(P22, 'results.jsonl')):
        d = json.loads(line)
        m = d['miner']
        R = d['rooms']
        y = np.array([r['y'] for r in R], float)
        t = np.array([r['t'] + SW[m] for r in R], float)
        if t.sum() <= 0:
            continue
        base = 60 * COV[m] * y.sum() / t.sum()
        idx = rng.integers(0, len(R), (B, len(R)))
        bs = 60 * COV[m] * y[idx].sum(1) / t[idx].sum(1)
        samp = float(np.std(bs) / base) if base > 0 else 0.0
        tot = math.sqrt(samp ** 2 + sig[m]['used'] ** 2 + pick_sd[m] ** 2)
        run = math.sqrt(tot ** 2 + sig_luck ** 2)
        k = f"{d['b']},{d['c']}"
        o = out.setdefault(k, {})
        o[m] = dict(cpm_check=round(base * pick(m)), cpm_file=round(d['cpm'] * pick(m)), samp=round(100 * 1.96 * samp, 1),
                    total=round(100 * 1.96 * tot, 1), run=round(100 * 1.96 * run, 1))
    bad = [(k, m) for k, v in out.items() for m, x in v.items() if abs(x['cpm_check'] - x['cpm_file']) > 1 + 0.002 * x['cpm_file']]
    print(f'{len(out)} tiles; rate recomputed from rooms differs from the file in {len(bad)} (first: {bad[:3]})')
    for m in ('chain', 'vein'):
        s = [v[m]['samp'] for v in out.values() if m in v]
        tt = [v[m]['total'] for v in out.values() if m in v]
        print(f'{m}: sampling 95 % median {np.median(s):.1f} % (p90 {np.percentile(s, 90):.1f}); total median {np.median(tt):.1f} % (p90 {np.percentile(tt, 90):.1f})')
    json.dump(out, open(os.path.join(HERE, 'tile_margins.json'), 'w'))
    json.dump(dict(model=sig, pooled_sd=sig_pool, luck_sd=sig_luck, picker_sd=pick_sd, regime=regime, n_segments=len(val),
                   bugged=[list(x) for x in BUGGED]), open(os.path.join(HERE, 'validation_summary.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
