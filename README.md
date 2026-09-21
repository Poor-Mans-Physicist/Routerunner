# Routerunner

A client-side Forge mod for [Wold's Vaults](https://www.curseforge.com/minecraft/modpacks/wolds-vaults) (Minecraft 1.18.2)
that helps you loot more chests per minute in vaults.

- **Live metrics HUD.** Chests broken, average chests/min (overall and with pauses excluded), and a 30-second rolling rate.
- **Loot rates.** Per-minute rates for the loot of one chest type (gilded, ornate, living or wooden). By default it
  watches your first 100 chests and picks the type you break most.
- **Route overlay.** For each room it solves a looting route and draws it in the world: where to walk, sprint-jump,
  drop or trident-dash, and which chest to break next. It reads your Chain Miner tier so it knows how many chests a
  single break clears.
- **Vault history.** Every vault you finish is saved with its chest count, rates, modifiers and loot totals.
- **Run logs.** Each vault writes a `vault_*.jsonl` file with the planned routes, the path you actually took and your
  chest breaks, for anyone who wants to analyse their runs.

It only runs inside the vault dimension and needs nothing on the server.

## Installing

1. Download `Routerunner-<version>.jar` from the [Releases page](https://github.com/Poor-Mans-Physicist/Routerunner/releases)
   (under **Assets** on the latest release).
2. Put it in the `mods` folder of your Wold's Vaults instance. In the CurseForge app: right-click the instance, pick
   **Open Folder**, then open `mods`.
3. Start the game. Only you need it; the server and other players don't.

To uninstall, delete the jar. Settings and logs live in `config/routerunner/` inside the instance folder.

## Using the config menu

Press **`[`** to open the menu. You can rebind it under Options → Controls → Routerunner.

**Left column (toggles)**

| Button | What it does |
| --- | --- |
| Routerunner | Turns the whole mod on or off. |
| Routing | Turns the route overlay on or off. With it off you just get the metrics and loot HUD. |
| Track loot | Which chest type's loot to rate: `AUTO` (picks after 100 chests), `GILDED`, `ORNATE`, `LIVING`, `WOODEN`, or `ALL` (hides the loot panel). Changing it clears the loot counters. |
| Diff Route | Keeps solving routes in the background while Routing is off, and logs how your own path compared with the solver's for each room. |
| Hunter boxes | Hides or shows the Hunter ability's chest outlines so they don't clutter the route. |
| Adaptive Weights | Adjusts the route to how fast you actually move, learned over your runs. Turn it off to use the sliders exactly as set. |
| Missed-waypoint skip | If you've already passed a waypoint, or most of its chests are gone, the route moves on instead of sending you back. |
| Target arrow | Shows an arrow at the edge of the screen pointing to the next target when it's out of view. |

**Right column (actions)**

| Button | What it does |
| --- | --- |
| Edit HUD Layout | Drag the readouts and the loot panel wherever you want. Toggles along the bottom show or hide each one. |
| View Past Vaults | Scrollable list of your finished vaults, newest first. |
| New Lap | Resets the HUD counters without touching the vault's log. Handy for comparing attempts in the same vault. |
| Open Log Folder | Opens the folder with the `vault_*.jsonl` run logs. |
| Adjust Weights | Sliders for the route solver, grouped into tabs (Bail, Move, Open, Cluster, Shafts, Sprint). Changes apply from the next room. Hover a slider to see what it does. **Reset all to defaults** is there if you get lost. |
| Help | In-game guide to the route colours and markers. |
| Lookahead | How many upcoming waypoints the overlay shows. |

**Reading the route**

- Line colours: **green** = walk, **cyan** = sprint-jump, **amber** = drop down, **purple** = trident dash,
  **white** = you'll double back here, **red** = the stretch you're on now.
- Markers: **pink (1)** = break this chest next, **blue (2)** = the one after, **dark blue** = further ahead,
  **orange** = the exit.
- The route skips awkward, walled-in chests on purpose, because breaking a neighbour chains them anyway. Don't go back
  for them.
- If it's too jumpy or skips too much, start with **Bail aggression** and **Break reach** in Adjust Weights.

## Building from source

You need JDK 17. The mod compiles against Vault Hunters (`the_vault`) and `vhapi`. Those jars can't be
redistributed, so you have to supply them yourself:

1. Copy `the_vault-*.jar` and `vhapi-*.jar` from your Wold's Vaults instance's `mods` folder into `libs/`.
2. Set `vault_jar` and `vhapi_jar` in `gradle.properties` to their exact file names.
3. Run `./gradlew build` (or `gradlew.bat build` on Windows). The jar ends up in `build/libs/`.

## Contributing

Issues and pull requests are welcome. Every PR is reviewed and merged by the maintainer, so please:

- keep each PR to one change,
- say how you tested it (in-game, and on which pack version),
- open an issue first for anything big so we can agree on the approach.

## License

[GPL-3.0](LICENSE). Routerunner is a fan-made mod, not affiliated with Vault Hunters or the Wold's Vaults team.
