# Benchmarks

What Routerunner predicts, and how it holds up on real runs.

## Chain vs Vein panel v2.2 (`panel-v2.2/`)

Chests per minute for every Bonus × Cascade living-chest crystal a real crystal can reach, for Chain Miner and Vein Miner. Each tile is a **benchmark**: the rate the benchmark player (the author) gets with Routerunner, on the shape time model at movement speed 0.400. Players scale it by the speed Routerunner shows under **Routing...**.

- `poster_best_rates.png`, `poster_chain_vs_vein.png`: the panel at a glance.
- `chain_vs_vein_panel.html`: the interactive panel. Hover or click a tile for its rates and three 95 % margins:
  - sampling;
  - the expected rate, all error sources;
  - the range one ~10-minute run lands in.

  A crystal-cost chooser finds the best tile for your stack prices.
- `diagnostics.html`: how the panel was computed, the benchmark player's stats, and per-tile margins with their sources.
- `cells_v22.json`, `tile_margins.json`: the data.

Typical 95 % margins on a tile's expected rate: about ±11 % for chain and ±15 % for vein. Most of that is the model's run-to-run error; sampling adds ±5–6 % in the median tile.

## Validation (`validation/`)

`benchmark_validation.html` compares every lap the author ran on Routerunner 1.2.0-test9 through 1.2.0 with the panel. For each lap it shows:

- the raw chests per minute;
- the lap's rooms re-planned the panel's way, to remove room luck;
- route adherence, execution speed and movement speed;
- the adjusted chests per minute.

Five of the tested crystals (90/90, 36/150, 75/30, 61/12, 30/42) were not in the time model's fit data. `runs.json` and `runs.csv` hold the numbers.

## Viewing the HTML

GitHub shows `.html` files as source. Download the folder and open the pages in a browser, or view them through a raw-HTML proxy.

## Scripts (`scripts/`)

These are the analysis scripts that produced the data and pages. They read the author's local run logs and research outputs, so they document the method rather than run as-is.
