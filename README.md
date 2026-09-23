# Routerunner

A client-side Forge mod for [Wold's Vaults](https://www.curseforge.com/minecraft/modpacks/wolds-vaults) (Minecraft 1.18.2)
that helps you loot more chests per minute in vaults.

- **Live metrics HUD.** Total chests broken, average chests/min (overall and with pauses excluded), a sliding 1 minute
  average that turns green or red when it runs more than 25 chests/min above or below the lap's active average, the
  lap's average room density (target chests per room, read at each room's first scan), and per-chest-type loot rates.
  Can also hide Hunter boxes.
- **Lane route.** A planner solves each room as you enter it and draws the route on the floor: a chain of short runs
  through the densest chests, a heat map over the chests each run will clear, green markers on the next cluster, purple
  arrows wherever the route climbs, drops or dashes, and a green walk out when the room stops being worth it. The planner
  is a bundled Rust library (about 0.2 s per room on Windows x64; a Java planner runs elsewhere) fitted to logged runs, and
  it re-plans from where you stand if you leave the route. It assumes Chain Miner; without it the routes will be wrong.
- **Vault history.** Every vault you finish is saved with its chest count, rates, modifiers and loot totals.
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

**Left column (toggles)**

| Button | What it does |
| --- | --- |
| Routerunner | Turns the whole mod on or off. |
| Route overlay | Shows or hides the drawn route. Rooms are still solved and logged with it hidden. |
| Track loot | Which chest type's loot to rate: `AUTO` (picks after 100 chests), `GILDED`, `ORNATE`, `LIVING`, `WOODEN`, or `ALL` (hides the loot panel). Changing it clears the loot counters. |
| Hunter boxes | Hides or shows Hunter boxes (chests, doors and the rest) so they don't clutter the route. |
| Target arrow | A screen-edge arrow toward the next target chests while they are out of view. |

**Right column (actions)**

| Button | What it does |
| --- | --- |
| Edit HUD Layout | Drag the readouts and the loot panel wherever you want. Toggles along the bottom show or hide each one. |
| View Past Vaults | Scrollable list of your finished vaults, newest first. |
| New Lap | Resets the HUD counters without touching the vault's log. Handy for comparing attempts in the same vault. |
| Open Log Folder | Opens the folder with the `vault_*.jsonl` run logs. |
| Help | In-game guide to the route colours and markers. |

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

## Building

The lane planner lives in `native/lane` (Rust). Build it first, then the jar; the jar packs the library it finds:

```
cd native/lane && cargo build --release
./gradlew build
```

Without the library the mod still builds and runs on its Java planner (same plans, about eight times slower).

## Contributing

Feel free to contribute anything you'd like to the mod, but keep your changes reasonable and clearly explained. AI is
allowed, but please try not to make it "AI slop", including removing excessive comments and the like.

## License

[GPL-3.0](LICENSE). Routerunner is a fan-made mod, not affiliated with Vault Hunters or the Wold's Vaults team.
