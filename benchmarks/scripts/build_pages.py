"""Assemble the published benchmark folder (routerunner/benchmarks/) from the research outputs:

  panel-v2.2/chain_vs_vein_panel.html   the v2.2 panel page, each tile's details extended with its 95 % margins
  panel-v2.2/poster_*.png               the dark posters
  panel-v2.2/diagnostics.html           how the panel was computed, the benchmark player's stats, per-tile margins
  panel-v2.2/cells_v22.json, tile_margins.json
  validation/benchmark_validation.html  every run on the recent builds against the panel
  validation/runs.json, runs.csv
  scripts/                              the analysis scripts

python build_pages.py
"""
import csv
import datetime
import json
import math
import os
import shutil
import statistics as st

HERE = os.path.dirname(os.path.abspath(__file__))
P22 = r'C:\Users\river\routerunner\research\2026-09-27_panel_v22'
OUT = r'C:\Users\river\routerunner\benchmarks'
PANEL = os.path.join(OUT, 'panel-v2.2')
VAL = os.path.join(OUT, 'validation')
IN_FIT = {'45/45', '30/30', '51/74', '21/56'}
BUGGED = {('20260927_235948', 2)}


def fmt_date(v):
    d = datetime.datetime.strptime(v, '%Y%m%d_%H%M%S')
    return d.strftime('%b %d %H:%M')


CSS = """
:root{color-scheme:light;--bg:#f6f5f2;--surface:#fcfcfb;--ink:#0b0b0b;--ink2:#52514e;--muted:#7a7974;--rule:#e2e0da;--grid:#ecebe7;
--s1:#2a78d6;--s2:#eb6834;--ok:#1baf7a;--warn:#c98500;--seq0:#cde2fb;--seq1:#86b6ef;--seq2:#3987e5;--seq3:#1c5cab;--seq4:#0d366b;--ref:#9a9892}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){color-scheme:dark;--bg:#121211;--surface:#1a1a19;--ink:#ffffff;--ink2:#c3c2b7;
--muted:#8f8e86;--rule:#33332f;--grid:#262624;--s1:#3987e5;--s2:#d95926;--ok:#199e70;--warn:#eda100;--seq0:#184f95;--seq1:#1c5cab;--seq2:#2a78d6;--seq3:#6da7ec;--seq4:#cde2fb;--ref:#6d6c66}}
:root[data-theme="dark"]{color-scheme:dark;--bg:#121211;--surface:#1a1a19;--ink:#ffffff;--ink2:#c3c2b7;--muted:#8f8e86;--rule:#33332f;--grid:#262624;
--s1:#3987e5;--s2:#d95926;--ok:#199e70;--warn:#eda100;--seq0:#184f95;--seq1:#1c5cab;--seq2:#2a78d6;--seq3:#6da7ec;--seq4:#cde2fb;--ref:#6d6c66}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:1080px;margin:0 auto;padding:32px 16px 64px}h1{font-size:28px;margin:0 0 6px}h2{font-size:19px;margin:36px 0 10px}
h3{font-size:15px;margin:22px 0 6px}p,li{color:var(--ink2);max-width:78ch}.lede{font-size:16px}a{color:var(--s1)}
.card{background:var(--surface);border:1px solid var(--rule);border-radius:10px;padding:16px 18px;margin:14px 0}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin:18px 0}
.tile{background:var(--surface);border:1px solid var(--rule);border-radius:10px;padding:14px 16px}
.tile .v{font-size:26px;font-weight:650;color:var(--ink);font-variant-numeric:tabular-nums}.tile .l{font-size:13px;color:var(--muted)}
table{border-collapse:collapse;width:100%;font-size:13px;font-variant-numeric:tabular-nums}
th,td{padding:6px 8px;border-bottom:1px solid var(--rule);text-align:right;white-space:nowrap}th{color:var(--muted);font-weight:600}
td.l,th.l{text-align:left}.scroll{overflow-x:auto}.tag{display:inline-block;padding:0 6px;border-radius:4px;font-size:12px;border:1px solid var(--rule);color:var(--ink2)}
.dim{color:var(--muted)}svg text{fill:var(--ink2);font:12px system-ui,sans-serif}.legend{display:flex;gap:16px;flex-wrap:wrap;font-size:13px;color:var(--ink2);margin:6px 0}
.sw{display:inline-block;width:12px;height:12px;border-radius:3px;vertical-align:-1px;margin-right:6px}
#tip{position:fixed;pointer-events:none;background:var(--surface);color:var(--ink);border:1px solid var(--rule);border-radius:8px;padding:8px 10px;font-size:12.5px;
box-shadow:0 4px 18px rgba(0,0,0,.18);display:none;max-width:320px;z-index:10}
.kv{display:grid;grid-template-columns:auto 1fr;gap:4px 18px;font-size:14px}.kv span:nth-child(odd){color:var(--muted)}
.theme{position:fixed;top:12px;right:12px;font-size:12px;background:var(--surface);color:var(--ink2);border:1px solid var(--rule);border-radius:6px;padding:4px 8px;cursor:pointer}
"""

THEME_JS = """
<button class="theme" id="theme" aria-label="Toggle light or dark">Theme</button>
<script>
(function(){const b=document.getElementById('theme');let t=null;try{t=localStorage.getItem('rr-theme')}catch(e){}
if(t)document.documentElement.dataset.theme=t;b.onclick=()=>{const cur=document.documentElement.dataset.theme||(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');
const n=cur==='dark'?'light':'dark';document.documentElement.dataset.theme=n;try{localStorage.setItem('rr-theme',n)}catch(e){}};})();
const tip=document.getElementById('tip');function showTip(e,html){tip.innerHTML=html;tip.style.display='block';const x=Math.min(e.clientX+14,innerWidth-340);tip.style.left=x+'px';tip.style.top=(e.clientY+14)+'px'}
function hideTip(){tip.style.display='none'}
</script>
"""


def page(title, desc, body):
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{title}</title><meta name="description" content="{desc}"><style>{CSS}</style></head><body><div id="tip" role="tooltip"></div>{THEME_JS}<main>{body}</main></body></html>"""


def panel_page(margins):
    cells = json.load(open(os.path.join(P22, 'cells_v22.json')))
    html = open(os.path.join(P22, 'vein_chain_grid_v22.html'), encoding='utf-8').read()
    old = json.dumps(cells, separators=(',', ':'))
    assert html.count(old) == 1, 'cell data not found verbatim in the built page'
    for d in cells:
        m = margins.get(f"{d['b']},{d['c']}", {})
        for mn, k in (('chain', 'C'), ('vein', 'V')):
            if mn in m:
                d['m' + k], d['s' + k], d['r' + k] = m[mn]['total'], m[mn]['samp'], m[mn]['run']
    html = html.replace(old, json.dumps(cells, separators=(',', ':')))
    reps = [
        ("<div><span>Chain cpm</span><span>${has(d.chain) ? d.chain : dash}</span></div>",
         "<div><span>Chain cpm</span><span>${has(d.chain) ? d.chain + (d.mC ? ' ± ' + d.mC + ' %' : '') : dash}</span></div>"),
        ("<div><span>Vein cpm</span><span>${has(d.vein) ? d.vein : dash}</span></div>",
         "<div><span>Vein cpm</span><span>${has(d.vein) ? d.vein + (d.mV ? ' ± ' + d.mV + ' %' : '') : dash}</span></div>"),
        ("<div><span>Chests per room</span><span>${d.n}</span></div>",
         "<div><span>95 % margin: sampling / expected rate / one run</span><span>${has(d.chain) ? 'chain ' + d.sC + ' / ' + d.mC + ' / ' + d.rC + ' %' : ''}"
         "${has(d.chain) && has(d.vein) ? ' · ' : ''}${has(d.vein) ? 'vein ' + d.sV + ' / ' + d.mV + ' / ' + d.rV + ' %' : ''}</span></div>"
         "\n       <div><span>Chests per room</span><span>${d.n}</span></div>"),
    ]
    for a, b in reps:
        assert html.count(a) == 1, a[:50]
        html = html.replace(a, b)
    a = '<p class="lede">'
    assert html.count(a) == 1
    html = html.replace(a, '<p class="lede"><b>Margins and method:</b> <a href="diagnostics.html">diagnostics</a> · '
                           '<b>Checked against real runs:</b> <a href="../validation/benchmark_validation.html">validation</a>.<br>', 1)
    if '<meta charset' not in html[:500].lower():
        html = '<!doctype html>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width,initial-scale=1">\n' + html
    return html


def heat_js():
    return r"""
<script>
function heat(id, key, data, label){
  const el=document.getElementById(id), cs=getComputedStyle(document.documentElement);
  const cell=9, gap=1, bs=[...new Set(data.map(d=>d.b))].sort((a,b)=>a-b), cc=[...new Set(data.map(d=>d.c))].sort((a,b)=>a-b);
  const L=44, T=22, W=L+cc.length*(cell+gap)+10, H=T+bs.length*(cell+gap)+34;
  const steps=[[0,2],[2,4],[4,6],[6,10],[10,1e9]], cols=['--seq0','--seq1','--seq2','--seq3','--seq4'].map(v=>cs.getPropertyValue(v).trim());
  let s=`<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="${label}">`;
  for(const d of data){ const v=d[key]; if(v==null) continue;
    const x=L+cc.indexOf(d.c)*(cell+gap), y=T+(bs.length-1-bs.indexOf(d.b))*(cell+gap);
    const i=steps.findIndex(r=>v>=r[0]&&v<r[1]);
    s+=`<rect x="${x}" y="${y}" width="${cell}" height="${cell}" rx="1.5" fill="${cols[i]}" data-b="${d.b}" data-c="${d.c}" data-v="${v}" data-s="${d[key.replace('s','m')]}" data-r="${d[key.replace('s','r')]}" data-cpm="${d[key==='sC'?'chain':'vein']}"/>`; }
  for(const b of bs) if(b%30===0) s+=`<text x="${L-6}" y="${T+(bs.length-1-bs.indexOf(b))*(cell+gap)+8}" text-anchor="end">${b}</text>`;
  for(const c of cc) if(c%30===0) s+=`<text x="${L+cc.indexOf(c)*(cell+gap)+4}" y="${T+bs.length*(cell+gap)+14}" text-anchor="middle">${c}</text>`;
  s+=`<text x="${L}" y="${H-2}">cascade →</text><text x="2" y="12">bonus ↑</text></svg>`;
  el.innerHTML=s;
  el.querySelectorAll('rect').forEach(r=>{r.addEventListener('mousemove',e=>showTip(e,`<b>Bonus ${r.dataset.b} · Cascade ${r.dataset.c}</b><br>${label}: ${r.dataset.cpm} cpm<br>sampling (64 rooms): ± ${r.dataset.v} %<br>expected rate, all sources: ± ${r.dataset.s} %<br>one ~10-minute run lands within ± ${r.dataset.r} %`));r.addEventListener('mouseleave',hideTip)});
}
</script>"""


def diagnostics(margins, summ, runs):
    cells = json.load(open(os.path.join(P22, 'cells_v22.json')))
    shape = json.load(open(r'C:\Users\river\routerunner\src\main\resources\assets\routerunner\timemodel_shape.json'))
    data = []
    for d in cells:
        m = margins.get(f"{d['b']},{d['c']}", {})
        row = dict(b=d['b'], c=d['c'], chain=d['chain'], vein=d['vein'])
        for mn, k in (('chain', 'C'), ('vein', 'V')):
            if mn in m and d[mn] is not None:
                row['m' + k], row['s' + k], row['r' + k] = m[mn]['total'], m[mn]['samp'], m[mn]['run']
        data.append(row)
    med = lambda mn, f: st.median(v[mn][f] for v in margins.values() if mn in v)
    p90 = lambda mn, f: sorted(v[mn][f] for v in margins.values() if mn in v)[int(0.9 * sum(1 for v in margins.values() if mn in v))]
    mo = summ['model']
    val = [s for s in runs if s['mode'] == 'shape' and (s['vault'], s['lap']) not in BUGGED]
    effs = [s['eff'] for s in val]
    rows = ''.join(
        f"<tr><td class=l>{s['crystal']}</td><td class=l>{s['miner']}</td><td>{s['rooms']}</td><td>{s['clump']:.0f}</td><td>{s['eff']:.3f}</td>"
        f"<td>{s['lo']:.2f}–{s['hi']:.2f}</td><td>{s['raw_ratio']:.3f}</td><td class=l>{'in fit' if s['crystal'] in IN_FIT else 'outside fit'}</td></tr>" for s in val)
    body = f"""
<h1>Chain vs Vein panel v2.2 — diagnostics</h1>
<p class="lede">How the <a href="chain_vs_vein_panel.html">panel</a> was computed, who it benchmarks, and how far each tile can be trusted.
Every tile is a <b>benchmark</b>: the chests per minute the benchmark player gets with Routerunner on that crystal. Players scale it by the speed shown in
Routerunner's Routing menu. Real runs on the recent builds are compared against it in <a href="../validation/benchmark_validation.html">the validation report</a>.</p>
<div class="tiles">
<div class="tile"><div class="v">±{med('chain','total'):.0f} %</div><div class="l">median 95 % margin, chain tile (p90 ±{p90('chain','total'):.0f} %)</div></div>
<div class="tile"><div class="v">±{med('vein','total'):.0f} %</div><div class="l">median 95 % margin, vein tile (p90 ±{p90('vein','total'):.0f} %)</div></div>
<div class="tile"><div class="v">{st.median(effs):.2f}</div><div class="l">median real ÷ expected over {len(val)} shape-routed segments (range {min(effs):.2f}–{max(effs):.2f})</div></div>
<div class="tile"><div class="v">2,011</div><div class="l">tiles × 64 simulated rooms per miner</div></div>
</div>

<h2>The benchmark player</h2>
<div class="card kv">
<span>Player</span><span>the author (RiverJack12); the shape time model was fitted on their play, so they are the benchmark by construction (execution speed 1.00)</span>
<span>Movement speed</span><span>0.400 attribute (panel); runs are converted to other speeds with elasticity 0.254 chain / 0.194 vein (simulated at 45/45)</span>
<span>Break reach</span><span>5.0 blocks planning reach</span>
<span>Miners</span><span>Chain Miner range 6, limit 32 · Vein Miner touching groups (range 1), limit 896</span>
<span>Chests collected per planned chest</span><span>{shape['miners']['chain']['coverage']} chain · {shape['miners']['vein']['coverage']} vein (measured)</span>
<span>Room switch</span><span>{shape['miners']['chain']['switchS']} s chain · {shape['miners']['vein']['switchS']} s vein (measured mean, last break to next room's first)</span>
<span>Time model</span><span>{shape['name']}: runs by clearance, steps, drops, turns, turnarounds and per-click aim/height/reach/burst/group-size costs; fitted on 12 vaults (~980 rooms), leave-one-vault-out room R² 0.62 chain / 0.72 vein; crystals in the fit: 45/45, 30/30, 51/74, 21/56 and two gilded vaults</span>
<span>Room picker</span><span>adaptive picker, simulated gain ×1.053 chain / ×1.070 vein over a straight run (8 smoke-test crystals)</span>
</div>

<h2>How each tile was computed</h2>
<ul>
<li><b>Rooms.</b> 64 synthetic living-chest rooms per tile from the wv-chest-sim generator (level 475, random map themes: beach, cave, desert, nether, void; strongboxes rolled). The same 64 base rooms in every tile, so tiles differ only by their stacks. Rooms sit on a straight run along the vault's dense chunk-alignment axis. Grid: bonus 0–90 × cascade 0–90 in steps of 3, plus cascade up to 240 for bonus 0–60.</li>
<li><b>Planning.</b> Routerunner's own native lane planner, shape time model at speed 0.400, with the in-game pruning (groups under rate × seconds per click are skipped) and bail floor (0.6 × rate). The rate is iterated to a fixed point, as the game converges: up to 5 rounds, stopping below a 0.5 % change.</li>
<li><b>Rate.</b> 60 × coverage × planned chests ÷ (planned seconds + room switch), summed over the 64 rooms, times the picker gain. Where one ability was clearly ahead, only that ability was planned (the other shows “–”).</li>
<li><b>Not included.</b> The 1.2.0 sparse-room prune floor, the per-player speed calibration and the walk-then-warp exit postdate this panel. Idle time, hallway time beyond the mean switch, and mob fights are not modelled.</li>
</ul>

<h2>Error margins</h2>
<p>Each tile's details on the panel page now show three 95 % margins: sampling, the expected rate (all sources) and one run. The model and picker parts are the same for every tile of a miner (±{100 * 1.96 * math.hypot(mo['chain']['used'], summ['picker_sd']['chain']):.0f} % chain, ±{100 * 1.96 * math.hypot(mo['vein']['used'], summ['picker_sd']['vein']):.0f} % vein), so the maps show the part that varies: the sampling margin of each tile's 64 rooms. Hover a tile for all three.</p>
<div class="card"><h3>Chain Miner — sampling margin (95 %)</h3><div class="scroll" id="hmC"></div>
<h3>Vein Miner — sampling margin (95 %)</h3><div class="scroll" id="hmV"></div>
<div class="legend"><span><span class="sw" style="background:var(--seq0)"></span>&lt; 2 %</span><span><span class="sw" style="background:var(--seq1)"></span>2–4 %</span>
<span><span class="sw" style="background:var(--seq2)"></span>4–6 %</span><span><span class="sw" style="background:var(--seq3)"></span>6–10 %</span><span><span class="sw" style="background:var(--seq4)"></span>≥ 10 %</span></div></div>
<div class="card scroll"><table>
<tr><th class=l>Component (1 σ)</th><th>Chain</th><th>Vein</th><th class=l>From</th></tr>
<tr><td class=l>Sampling: which 64 rooms (median tile)</td><td>{med('chain','samp') / 1.96:.1f} %</td><td>{med('vein','samp') / 1.96:.1f} %</td><td class=l>2,000-fold room bootstrap per tile</td></tr>
<tr><td class=l>Model: real vs expected on the same rooms</td><td>{100 * mo['chain']['used']:.1f} %</td><td>{100 * mo['vein']['used']:.1f} %</td><td class=l>{mo['chain']['n']} chain + {mo['vein']['n']} vein shape-routed segments (chain uses the pooled spread: only {mo['chain']['n']} segments)</td></tr>
<tr><td class=l>Picker gain</td><td>{100 * summ['picker_sd']['chain']:.1f} %</td><td>{100 * summ['picker_sd']['vein']:.1f} %</td><td class=l>spread of the per-crystal gains behind the flat factor</td></tr>
<tr><td class=l>Room luck, one run</td><td colspan=2>{100 * summ['luck_sd']:.1f} %</td><td class=l>raw ÷ sheet over real ÷ expected, same segments</td></tr>
</table></div>
<ul>
<li><b>Sampling</b> is the tile's own noise; it is small wherever rooms hold many chests and largest in the sparse corner.</li>
<li><b>Expected rate</b> (the “±” after each cpm) combines sampling, model and picker in quadrature: where the benchmark player's long-run average on that crystal should sit.</li>
<li><b>One run</b> adds room luck: a single ~10-minute run of that crystal should land inside this range 95 % of the time.</li>
<li><b>Known bias:</b> Vein Miner rooms with clumpiness under 80 (scattered small groups) came in {100 * (1 - summ['regime']['vein_low']['eff']):.0f} % under the model on average ({summ['regime']['vein_low']['n']} segments; ×{summ['regime']['vein_high']['eff']:.2f} above 80). 1.2.0's sparse-room floor targets this; low-density vein tiles are the least certain part of the panel until more 1.2.0 runs are in.</li>
<li>One lap (30/42 vein, Sep 27 lap 2) is left out of the model spread: it ran into a planner bug (the rate estimate started at zero and pruned nothing) that was fixed before 1.2.0. With it the vein spread would be {100 * st.pstdev([math.log(s['eff']) for s in runs if s['mode'] == 'shape' and s['miner'] == 'vein']):.1f} %.</li>
</ul>

<h2>Validation segments used</h2>
<div class="card scroll"><table><tr><th class=l>Crystal</th><th class=l>Miner</th><th>Rooms</th><th>Clump</th><th>Real ÷ expected</th><th>90 % CI</th><th>Raw ÷ sheet</th><th class=l>Crystal in model fit</th></tr>{rows}</table></div>
<p class="dim">Full per-lap detail, including learned-model and freehand laps, is in <a href="../validation/benchmark_validation.html">the validation report</a>. Data: <a href="tile_margins.json">tile_margins.json</a> (per tile: samp / total / run, 95 %), <a href="cells_v22.json">cells_v22.json</a>.</p>
<script>const DATA={json.dumps(data, separators=(',', ':'))};</script>{heat_js()}
<script>heat('hmC','sC',DATA,'Chain');heat('hmV','sV',DATA,'Vein');</script>
"""
    return page('Panel v2.2 Diagnostics', 'How the chain vs vein benchmark panel was computed and its per-tile error margins.', body)


def validation(runs, rooms, summ):
    segs = runs
    val = [s for s in segs if s['mode'] == 'shape' and (s['vault'], s['lap']) not in BUGGED]
    effs = [s['eff'] for s in val]
    within5 = sum(1 for e in effs if abs(e - 1) <= 0.05)
    within10 = sum(1 for e in effs if abs(e - 1) <= 0.10)
    out_fit = [s for s in val if s['crystal'] not in IN_FIT]
    rows = []
    for i, s in enumerate(segs):
        flag = ' <span class="tag">planner bug, fixed</span>' if (s['vault'], s['lap']) in BUGGED else ''
        ex = '–' if s['exec'] is None else f"{s['exec']:.2f}"
        onr = '–' if s['onroute'] is None else f"{100 * s['onroute']:.0f} %"
        pc = '–' if s['plancov'] is None else f"{s['plancov']:.2f}"
        fit = 'in fit' if s['crystal'] in IN_FIT else 'outside'
        rows.append(
            f"<tr><td class=l>{fmt_date(s['vault'])} · lap {s['lap']}</td><td class=l>{s['build']}</td><td class=l>{s['crystal']}</td>"
            f"<td class=l>{fit}</td><td class=l>{s['miner']}</td><td class=l>{s['mode']}{flag}</td>"
            f"<td>{s['rooms']}</td><td>{s['minutes']:.1f}</td><td>{s['speed']:.3f}</td><td>{ex}</td><td>{onr}</td><td>{pc}</td>"
            f"<td>{s['density']:.0f} <span class=dim>/ {s['sheet_density']}</span></td><td>{s['raw']:,.0f}</td><td>{s['sheet']:,.0f}</td><td>{s['raw_ratio']:.2f}</td>"
            f"<td>{s['exp_rooms']:,.0f}</td><td><b>{s['eff']:.2f}</b> <span class=dim>{s['lo']:.2f}–{s['hi']:.2f}</span></td><td>{s['adj']:,.0f}</td></tr>")
    pts = [dict(i=i, lab=f"{s['crystal']} {s['miner']} {s['mode']} ({fmt_date(s['vault'])} lap {s['lap']})", eff=s['eff'], lo=s['lo'], hi=s['hi'],
                raw=s['raw_ratio'], bug=(s['vault'], s['lap']) in BUGGED) for i, s in enumerate(segs)]
    body = f"""
<h1>Routerunner benchmark validation</h1>
<p class="lede">Every lap the author ran on Routerunner 1.2.0-test9 through 1.2.0 (Sep 27–28, 2026), compared with the <a href="../panel-v2.2/chain_vs_vein_panel.html">v2.2 chain vs vein panel</a>.
The question: does the panel predict real play, including on crystals the time model never saw?</p>
<div class="tiles">
<div class="tile"><div class="v">{st.median(effs):.2f}</div><div class="l">median real ÷ expected on the same rooms, {len(val)} shape-routed laps</div></div>
<div class="tile"><div class="v">{within10} / {len(val)}</div><div class="l">shape-routed laps within ±10 % of the model ({within5} within ±5 %)</div></div>
<div class="tile"><div class="v">{st.median([s['eff'] for s in out_fit]):.2f}</div><div class="l">median on {len(out_fit)} laps with crystals outside the model's fit data</div></div>
<div class="tile"><div class="v">{st.median([s['raw_ratio'] for s in val]):.2f}</div><div class="l">median raw ÷ panel (room luck included)</div></div>
</div>

<h2>How to read it</h2>
<ul>
<li><b>Raw</b> is chests broken over time in rooms plus the switches between them (what the panel predicts; hallway detours over 30 s are left out). <b>Panel</b> is the tile for the crystal, converted from speed 0.400 to the speed played; freehand laps get it without the room picker's gain.</li>
<li><b>Raw ÷ panel</b> mixes two things: how well the run was played against the model, and whether the vault's rooms were richer or poorer than the panel's average rooms.</li>
<li><b>Real ÷ expected</b> removes the room luck. Every room of the lap is re-planned the way the panel was made (same planner, time model, pruning and speed, from where the player actually entered), and the lap's real rate is divided by that expected rate. 1.00 means the panel's method predicts this lap exactly on these rooms. The 90 % interval is a room bootstrap.</li>
<li><b>Adjusted</b> = panel × real ÷ expected: the rate this play would reach on the panel's average vault for that crystal.</li>
<li><b>On route</b> is the share of the trail within 3 blocks (chain) or 6 blocks (vein: vein play sits about 3.5 blocks off the line by design) of the route drawn at that moment, computed the same way for every build. <b>Plan coverage</b> is chests broken per chest the room's first plan counted.</li>
<li><b>Execution</b> is the player's speed at the moves they actually made against the benchmark (1.00 = benchmark; the author is the benchmark, so this is a consistency check).</li>
</ul>

<h2>Real ÷ expected, lap by lap</h2>
<div class="legend"><span><span class="sw" style="background:var(--s1)"></span>Real ÷ expected on the same rooms (90 % interval)</span><span><span class="sw" style="background:var(--s2)"></span>Raw ÷ panel</span></div>
<div class="card scroll" id="forest"></div>

<h2>Room by room</h2>
<p>Each dot is one looted room of a routed or freehand lap: its real rate against the rate the panel's method expects for that room. Rooms vary a lot individually; what the panel promises is the average over many rooms (the laps above). Rooms outside 200–30,000 cpm are drawn on the edge.</p>
<div class="legend"><span><span class="sw" style="background:var(--s1)"></span>Chain Miner</span><span><span class="sw" style="background:var(--s2)"></span>Vein Miner</span><span class="dim">diagonal = exactly as expected</span></div>
<div class="card scroll" id="scatter"></div>

<h2>Every lap</h2>
<div class="card scroll"><table>
<tr><th class=l>Lap</th><th class=l>Build</th><th class=l>Crystal</th><th class=l>Crystal in model fit</th><th class=l>Miner</th><th class=l>Routing</th><th>Rooms</th><th>Min</th><th>Move speed</th><th>Execution</th><th>On route</th><th>Plan cov.</th><th>Chests / room (panel)</th><th>Raw cpm</th><th>Panel cpm</th><th>Raw ÷ panel</th><th>Expected cpm</th><th>Real ÷ expected</th><th>Adjusted cpm</th></tr>
{''.join(rows)}</table></div>

<h2>What this shows</h2>
<ul>
<li>On shape-routed laps the model is unbiased: median {st.median(effs):.2f}, spread {100 * summ['model']['vein']['used']:.1f} % per lap, including 90/90, 36/150, 75/30, 61/12 and 30/42, none of which were in the time model's fit data.</li>
<li>Learned-model laps land a little under (0.96–0.99) and freehand laps lower still or level (0.89 chain, 1.02 vein at 90/90, where large groups make routing matter less). The lead is the route's work, not faster execution: execution speed was 0.96–1.08 on every lap.</li>
<li>Scattered, low-clumpiness vein rooms are the weak spot (30/42 vein laps at 0.92, and 0.76 on the lap hit by a since-fixed planner bug); 1.2.0's sparse-room floor targets this.</li>
<li>Raw ÷ panel is within ±5 % on most laps; the rest is room luck (the 75/30 vault's rooms were sparser than the panel's: raw 0.88, but 1.01 on its own rooms).</li>
<li>All laps are one player at movement speed 0.320. Other players' rates scale with their execution speed; the author's is the benchmark.</li>
</ul>
<p class="dim">Crystals are read from the vault's modifier list as N× Bonus Living and N× Living (the +25 % crystal cascade). Map vaults' +1 % cascades share that name in logs from these builds; from 1.2.0 on they are logged as “1% Living (Map)”. Data: <a href="runs.json">runs.json</a>, <a href="runs.csv">runs.csv</a>. Scripts: <a href="../scripts/">scripts/</a>.</p>
<script>const P={json.dumps(pts, separators=(',', ':'))};const R={json.dumps(rooms, separators=(',', ':'))};const SEG={json.dumps([dict(m=s['miner']) for s in segs])};</script>
<script>
(function(){{
 const cs=getComputedStyle(document.documentElement), c1=cs.getPropertyValue('--s1').trim(), c2=cs.getPropertyValue('--s2').trim(), ref=cs.getPropertyValue('--ref').trim(), grid=cs.getPropertyValue('--grid').trim();
 const W=900, rowH=26, L=330, T=24, H=T+P.length*rowH+30, x0=0.7, x1=1.2, X=v=>L+(Math.min(x1,Math.max(x0,v))-x0)/(x1-x0)*(W-L-20);
 let s=`<svg viewBox="0 0 ${{W}} ${{H}}" width="100%" style="max-width:${{W}}px" role="img" aria-label="Real over expected per lap">`;
 for(let v=0.7;v<=1.2001;v+=0.05){{s+=`<line x1="${{X(v)}}" x2="${{X(v)}}" y1="${{T-8}}" y2="${{H-24}}" stroke="${{Math.abs(v-1)<1e-9?ref:grid}}" stroke-width="${{Math.abs(v-1)<1e-9?1.5:1}}"/><text x="${{X(v)}}" y="${{H-8}}" text-anchor="middle">${{v.toFixed(2)}}</text>`}}
 P.forEach((p,i)=>{{const y=T+i*rowH+rowH/2;
  s+=`<text x="${{L-10}}" y="${{y+4}}" text-anchor="end">${{p.lab}}${{p.bug?' *':''}}</text>`;
  s+=`<line x1="${{X(p.lo)}}" x2="${{X(p.hi)}}" y1="${{y}}" y2="${{y}}" stroke="${{c1}}" stroke-width="2" stroke-linecap="round"/>`;
  if(p.raw!=null) s+=`<circle cx="${{X(p.raw)}}" cy="${{y}}" r="4.5" fill="none" stroke="${{c2}}" stroke-width="2"/>`;
  s+=`<circle cx="${{X(p.eff)}}" cy="${{y}}" r="5" fill="${{c1}}" stroke="var(--surface)" stroke-width="2"/>`;
  s+=`<rect x="0" y="${{y-rowH/2}}" width="${{W}}" height="${{rowH}}" fill="transparent" data-i="${{i}}"/>`;}});
 s+='</svg>'; const f=document.getElementById('forest'); f.innerHTML=s;
 f.querySelectorAll('rect[data-i]').forEach(r=>{{const p=P[+r.dataset.i];r.addEventListener('mousemove',e=>showTip(e,`<b>${{p.lab}}</b><br>Real ÷ expected ${{p.eff.toFixed(3)}} (90 %: ${{p.lo.toFixed(2)}}–${{p.hi.toFixed(2)}})<br>Raw ÷ panel ${{p.raw==null?'–':p.raw.toFixed(3)}}${{p.bug?'<br>* planner bug since fixed':''}}`));r.addEventListener('mouseleave',hideTip)}});
 const SW=620, SH=520, sl=56, sb=44, lo=Math.log(200), hi=Math.log(30000), SX=v=>sl+(Math.log(Math.max(200,Math.min(30000,v)))-lo)/(hi-lo)*(SW-sl-14), SY=v=>SH-sb-(Math.log(Math.max(200,Math.min(30000,v)))-lo)/(hi-lo)*(SH-sb-14);
 let t=`<svg viewBox="0 0 ${{SW}} ${{SH}}" width="100%" style="max-width:${{SW}}px" role="img" aria-label="Room real rate against expected">`;
 for(const v of [300,1000,3000,10000,30000]){{t+=`<line x1="${{SX(v)}}" x2="${{SX(v)}}" y1="14" y2="${{SH-sb}}" stroke="${{grid}}"/><line x1="${{sl}}" x2="${{SW-14}}" y1="${{SY(v)}}" y2="${{SY(v)}}" stroke="${{grid}}"/><text x="${{SX(v)}}" y="${{SH-sb+16}}" text-anchor="middle">${{v>=1000?v/1000+'k':v}}</text><text x="${{sl-6}}" y="${{SY(v)+4}}" text-anchor="end">${{v>=1000?v/1000+'k':v}}</text>`}}
 t+=`<line x1="${{SX(200)}}" y1="${{SY(200)}}" x2="${{SX(30000)}}" y2="${{SY(30000)}}" stroke="${{ref}}" stroke-width="1.5"/>`;
 t+=`<text x="${{(SW+sl)/2}}" y="${{SH-6}}" text-anchor="middle">expected cpm for the room</text><text x="14" y="${{SH/2}}" transform="rotate(-90 14 ${{SH/2}})" text-anchor="middle">real cpm</text>`;
 R.forEach((r,i)=>{{const m=SEG[r.seg].m; t+=`<circle cx="${{SX(r.exp).toFixed(1)}}" cy="${{SY(r.real).toFixed(1)}}" r="3" fill="${{m==='chain'?c1:c2}}" fill-opacity="0.55"/>`}});
 t+='</svg>'; document.getElementById('scatter').innerHTML=t;
}})();
</script>
"""
    return page('Benchmark Validation', 'Every recent Routerunner run compared against the v2.2 chain vs vein benchmark panel.', body)


def main():
    for d in (PANEL, VAL, os.path.join(OUT, 'scripts')):
        os.makedirs(d, exist_ok=True)
    margins = json.load(open(os.path.join(HERE, 'tile_margins.json')))
    summ = json.load(open(os.path.join(HERE, 'validation_summary.json')))
    R = json.load(open(os.path.join(HERE, 'runs.json')))
    runs, rooms = R['segments'], R['rooms']
    open(os.path.join(PANEL, 'chain_vs_vein_panel.html'), 'w', encoding='utf-8', newline='\n').write(panel_page(margins))
    open(os.path.join(PANEL, 'diagnostics.html'), 'w', encoding='utf-8', newline='\n').write(diagnostics(margins, summ, runs))
    open(os.path.join(VAL, 'benchmark_validation.html'), 'w', encoding='utf-8', newline='\n').write(validation(runs, rooms, summ))
    shutil.copy(os.path.join(P22, 'poster_best_v22_dark.png'), os.path.join(PANEL, 'poster_best_rates.png'))
    shutil.copy(os.path.join(P22, 'poster_cmp_v22_dark.png'), os.path.join(PANEL, 'poster_chain_vs_vein.png'))
    shutil.copy(os.path.join(P22, 'cells_v22.json'), os.path.join(PANEL, 'cells_v22.json'))
    json.dump(margins, open(os.path.join(PANEL, 'tile_margins.json'), 'w'), separators=(',', ':'))
    json.dump(dict(segments=runs, validation=summ), open(os.path.join(VAL, 'runs.json'), 'w'), indent=1)
    cols = ['vault', 'build', 'lap', 'crystal', 'miner', 'mode', 'rooms', 'minutes', 'speed', 'exec', 'onroute', 'plancov', 'replans', 'density',
            'sheet_density', 'clump', 'sheet_clump', 'raw', 'sheet', 'raw_ratio', 'exp_rooms', 'eff', 'lo', 'hi', 'adj']
    with open(os.path.join(VAL, 'runs.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(cols)
        for s in runs:
            w.writerow([round(s[c], 4) if isinstance(s[c], float) else s[c] for c in cols])
    for sc in ('runs_data.py', 'tile_margins.py', 'build_pages.py'):
        shutil.copy(os.path.join(HERE, sc), os.path.join(OUT, 'scripts', sc))
    print('written', OUT)


if __name__ == '__main__':
    main()
