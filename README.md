# Routerunner

A client-side Forge mod for [Wold's Vaults](https://www.curseforge.com/minecraft/modpacks/wolds-vaults) (Minecraft 1.18.2)
that helps you loot more chests per minute in vaults.

- **Live metrics HUD.** Displays total chests broken, average chests/min (overall and with pauses excluded), and a sliding 1 minute average. Also has the ability to disable hunter boxes.
- **Route overlay.** Toggleable route overlay that displays an optimized path and target chest waypoints to break, calculated in real time by a solver room by room, speced to your speed and chain miner level (currently ONLY supports chain miner, will not function well if you're not using it). The solver is very light on performance, and a help guide exists in the config menu to let you know how to follow the route. 
- **Vault history.** Every vault you finish is saved with its chest count, rates, modifiers and loot totals.
- **Run logs.** Each vault writes a `vault_*.jsonl` file with the planned routes and the exact route you ended up taking, which can be used to analyze your runs, and will have the future use of letting the solver adapt to your playstyle and get better over time. 

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
| Routing | Turns the route overlay on or off. With it off you just get the metrics and loot HUD. |
| Track loot | Which chest type's loot to rate: `AUTO` (picks after 100 chests), `GILDED`, `ORNATE`, `LIVING`, `WOODEN`, or `ALL` (hides the loot panel). Changing it clears the loot counters. |
| Diff Route | Keeps solving routes in the background while Routing is off, and logs how your own path compared with the solver's for each room (no performance impact, it's recommended you keep this on). |
| Hunter boxes | Hides or shows Hunter boxes (includes chests, doors, etc.) so they don't clutter the route. |
| Adaptive Weights | Adjusts the route to how fast you actually move, learned over your runs. Turn it off to use the default weights and stop learning. |
| Missed-waypoint skip | If you've already passed a waypoint, or most of its chests are gone, the route moves on instead of sending you back. |
| Target arrow | Shows an arrow at the edge of the screen pointing to the next target when it's out of view. |

**Right column (actions)**

| Button | What it does |
| --- | --- |
| Edit HUD Layout | Drag the readouts and the loot panel wherever you want. Toggles along the bottom show or hide each one. |
| View Past Vaults | Scrollable list of your finished vaults, newest first. |
| New Lap | Resets the HUD counters without touching the vault's log. Handy for comparing attempts in the same vault. |
| Open Log Folder | Opens the folder with the `vault_*.jsonl` run logs. |
| Adjust Weights | Sliders for the route solver, grouped into tabs (Bail, Move, Open, Cluster, Shafts, Sprint). Changes apply from the next room. Hover over a slider to see what it does. **Reset all to defaults** is there if you get lost. |
| Help | In-game guide to the route colours and markers. |
| Lookahead | How many upcoming waypoints the overlay shows. |

**Reading the route**

- Line colours: **green** = walk, **cyan** = sprint-jump, **amber** = drop down, **purple** = trident dash,
  **white** = you'll double back here, **red** = the stretch you're on now.
- Markers: **pink (1)** = break this chest next, **blue (2)** = the one after, **dark blue** = further ahead,
  **orange** = the exit.
- The route skips awkward, walled-in chests on purpose, because breaking nearby chests will reach them anyway. Don't go back
  for them.
- If it's too jumpy or skips too much, start with **Bail aggression** and **Break reach** in Adjust Weights.

## Contributing

Feel free to contribute anything you'd like to the mod, but keep your changes reasonable and clearly explained. AI is allowed, but please try not to make it "AI slop", including removing excessive comments and the like. 

## License

[GPL-3.0](LICENSE). Routerunner is a fan-made mod, not affiliated with Vault Hunters or the Wold's Vaults team.
