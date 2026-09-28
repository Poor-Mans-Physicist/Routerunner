# Routerunner

A client-side Forge mod for [Wold's Vaults](https://www.curseforge.com/minecraft/modpacks/wolds-vaults) (Minecraft 1.18.2)
that helps you loot more chests per minute in vaults. It is designed to be fully game legal, using only information that 
one gets from using treasure goggles and high AoE to compute the best way to loot a chest vault. 

- **Live metrics HUD.** Allows the player to see their real time chests/minute metrics on their HUD for chest vaults, 
including active averages, total averages, and the time elapsed in vault. It also shows the average loot/minute you get for a few
key items per chest type, like vault diamonds and knowledge essence.

- **Lane route.** The main feature of the mod – by using an internal solver, Routerunner is able to compute the supposedly
optimal path to loot a room, targeting the maximum chests/minute number possible. This route is displayed to the player via colorful
lanes and chest highlights on the screen, and has several different routing models possible to choose from if you don't like the way one plays.
For best results, try to follow the route to the best of your ability – the route is smart, and even though it looks like it's passing a juicy
group of chests that you really want to mine, the planner knows best, and if it doesn't want you to loot that group it generally has a good reason.
Has QoL settings to change the brightness/what is rendered for the route, and the route can be disabled or enabled at any time. 

- **Next room.** An adaptive room picker looks at the hunter data from surrounding rooms, combined with research into how the vault generates chests,
to show you what rooms to visit next. It doesn't double back on itself and tries to avoid other players, and while following the rooms it wants you to
go to, you'll see on average 10-15% higher chest densities than you would otherwise.

- **Your speed.** The router is able to automatically sense and adapt to your looting speed, providing projections on chests/minute and routes
based on your movement speed, which it constantly samples and updates as a vault progresses. 

- **Vault history.** After every vault, you have the option to view your past vaults in detail, including the modifiers on them, the total chests you mined, 
moving chest/minute averages across the vault, and your general performance compared to what the router projected you would get, and the internal benchmark 
set from training data on what is considered a cutting edge looting speed. 

- **Run logs.** Each vault writes a `vault_*.jsonl` file with the planned routes, your path, every chest break and the
  per-room comparison of your run with the reference solver's, which is what the planner's timing model is fitted on.

It only runs inside the vault dimension and is fully clientside.

## Installing

1. Download `Routerunner-<version>.jar` from the [Releases page](https://github.com/Poor-Mans-Physicist/Routerunner/releases)
   (under **Assets** on the latest release).
2. Put it in the `mods` folder of your Wold's Vaults instance. In the CurseForge app: right-click the instance, pick
   **Open Folder**, then open `mods`.
3. Start the game. You can join regular WV servers with it, and it should remain stable across WV updates.

To uninstall, delete the jar. Settings and logs live in `config/routerunner/` inside the instance folder.

## Using the config menu

Press **`[`** to open the menu. You can rebind it under Options → Controls → Routerunner.

**Left column**

| Button | What it does |
| --- | --- |
| Routerunner | Turns the whole mod on or off. |
| Route overlay | Shows or hides the drawn route. Rooms are still solved and logged with it hidden. |
| Track loot | Which chest type's loot to rate: `AUTO` (picks after 100 chests), `GILDED`, `ORNATE`, `LIVING`, `WOODEN`, or `ALL` (hides the loot panel). Changing it clears the loot counters. |
| Time model | `Shape` (default): routes priced move by move and scaled to your measured speed. `Learned`: the older adaptive leg model. |
| New Lap | Resets the HUD counters and starts a new lap in the vault's history, without touching the vault's log. Handy for comparing attempts in the same vault. |
| Help | In-game guide to the route colours and markers. |

**Right column (menus)**

| Button | What it does |
| --- | --- |
| Routing... | Next room (Adaptive / Straight), the target arrow, enigma chest routing (off by default) with its value slider (1 to 100 chests, default 10), your measured speed per miner with its reset, and the Learned model's adaptive learning with its reset. |
| Visuals and QoL... | Opacity sliders for everything Routerunner draws (a master slider plus one per element; 0 % hides it), the Vault Mapper axis lines, the Hunter box toggle and the enigma chest outlines (red and purple, on by default). |
| HUD Layout | Drag the readouts and the loot panel wherever you want. Toggles along the bottom show or hide each one. |
| Past Vaults | Your finished vaults, newest first. Click one for its laps; click a lap for its charts: **Actual** (what you got over the rooms you looted, idle removed), **Your Pace** (what the same room plans predict at your measured speed) and **Benchmark** (the author on the same plans), plus room density and clumpiness. |
| Data and Logs... | Run log: Gated (only vaults with a 150+ chest room keep their log) or Always, and the log folder. |

**Reading the route**

- The floor carpet: **orange** = the next 14 blocks ahead of you, the part to follow; **cyan** = the rest of the current
  run; **dim** = already walked; **grey** = the run after this one; **green line** = the walk out to the exit.
- **Purple** segments, with a large floating purple arrow, are where the route climbs, drops or trident-dashes.
- A thin **red** line appears when you have strayed and leads back to the route.
- Chest boxes: **pink to red** = every chest the current run will clear, redder = more chests fall with it; **green
  wireframes** = the next cluster on the run, the thing to head for.
- Hold the mine button and keep moving: chain breaking takes the chests around you. When a run's chests are gone or you
  pass its end, the route moves on by itself. Run ends can sit behind you; the planner has already priced the turn.
- The planner stops adding runs once the ones left would loot slower than about half your current rate and leads you
  out. Leaving then is the point, not a bug.
- Wander more than 6 blocks from the whole run for 5 seconds and the room is replanned from where you stand. Dash warps
  are fine.

The bail and exit weights (`laneBail`, `laneExitWeight`, `laneBailRateFrac`) can be edited in
`config/routerunner/config.json`; the defaults are what the timing model was tuned with.

## Benchmarks

[`benchmarks/`](benchmarks/) holds the chain vs vein benchmark panel (best chests per minute for every Bonus × Cascade
crystal, with per-tile error margins) and a validation report comparing real runs on the recent builds against it.

## Building

The lane planner lives in `native/lane` (Rust). Build it first, then the jar; the jar packs the library it finds:

```
cd native/lane && cargo build --release
./gradlew build
```

Without the library the mod still builds and runs on its Java planner (same plans, about eight times slower). Linux and macOS libraries come from the `natives` GitHub workflow; see `native/lane/README.md`.

## Contributing

Feel free to contribute anything you'd like to the mod, but keep your changes reasonable and clearly explained. AI is
allowed, but please try not to make it "AI slop", including removing excessive comments and the like.

## License

[GPL-3.0](LICENSE). Routerunner is a fan-made mod, not affiliated with Vault Hunters or the Wold's Vaults team.
