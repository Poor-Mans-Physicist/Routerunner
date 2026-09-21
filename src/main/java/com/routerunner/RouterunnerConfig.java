package com.routerunner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted client config (JSON at config/routerunner/config.json). Holds the master
 * enable flag and per-HUD-element placement/visibility. Gson-backed; falls back to
 * defaults (with an error log) if the file is missing or corrupt.
 */
public class RouterunnerConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static RouterunnerConfig INSTANCE;

    // ---- persisted fields ----
    public boolean enabled = true;
    public Map<HudElementId, ElementConfig> hud = new EnumMap<>(HudElementId.class);

    /** Which vault chest's loot the loot-listener panel tracks (AUTO decides from the first 100 mined chests). */
    public TrackedChest trackedChest = TrackedChest.AUTO;
    /** Loot panel position (moved as one unit in the HUD editor) + visibility. */
    public int lootPanelX = 5;
    public int lootPanelY = 64;
    public boolean lootPanelVisible = true;

    // ---- routing (beta) ----
    /** Master toggle for the waypoint solver + route overlay. Off = behaves like the loot tracker. */
    public boolean routingEnabled = true;
    /** Manual absolute leave-threshold override (chests per travel/aim cost). 0 = auto (bailAggression × hot-spot rate). */
    public double bail = 0.0;
    /** Movement-profile name stamped on every logged record (single profile for the beta). */
    public String profileName = "default";
    /** Room ids whose substring disables routing (e.g. the labyrinth, no-mine challenge rooms). */
    public List<String> routingSkipList = new ArrayList<>(List.of("labyrinth"));
    /** How many upcoming waypoints the overlay shows ahead (telegraph depth). */
    public int lookahead = 5;
    /** Path continuer: when the current waypoint has been flown past (it is behind you and receding) or its
     *  cluster is already mostly spent, discard it and continue to the plan's NEXT waypoint. The plan is never
     *  reordered. Off = the cursor sends you back for every waypoint. */
    public boolean missedSkip = true;
    /** Once a waypoint's trigger chest is gone, don't send the player back for a mop-up of this many or fewer
     *  leftovers in its area — treat the waypoint as done and log a {@code skip}. 0 = never skip stragglers. */
    public int stragglerSkip = 3;
    /** Bend angle (degrees) past which the route is marked as DOUBLING BACK at a waypoint: its approach is drawn
     *  white and it gets a white exit arrow. Display only — no cost term reads it, so it never moves the route. */
    public int turnaroundDeg = 120;
    /** Draw a screen-edge arrow pointing at the current route target while it is outside the view. */
    public boolean offscreenIndicator = true;
    /** Scale the measured weights (clearance multipliers, waypoint overhead, sprint, drop height) by how fast
     *  THIS profile actually moves, from its own trail. Off = the sliders below are used exactly as set.
     *  See {@link AdaptiveWeights}; the accumulators live in config/routerunner/adaptive/&lt;profile&gt;.json. */
    public boolean adaptiveWeights = true;

    // ---- trident / dash burst model (a single high-speed reposition, not per-block travel) ----
    // Trident is ~5x walk speed in ANY direction, distance is nearly free, but you can't loot mid-burst.
    // So its cost is about the action itself, not distance. Priced into the walk graph's SHAFT edges.
    /** Flat cost (blocks-equiv) of taking a trident. MEASURED: one shaft leg takes ~2.4s regardless of length,
     *  which is ~40 open-walk blocks of travel; 30.0 sits just under that, so shafts only fire for a real
     *  vertical save, not to shave a few blocks in a way no player would actually follow. */
    public double tridentActionCost = 30.0;
    /** Tiny per-block term so bursts don't fire across the whole map for no reason (distance is otherwise free). */
    public double tridentDistWeight = 0.04;
    /** Don't burst/shaft for hops shorter than this (blocks) — just walk them. */
    public double tridentMinDist = 6.0;

    // ---- trident SHAFTS (vertical/diagonal dash edges baked into the walk graph) ----
    // Real shafts, not per-node spam: ray-march each standable cell along vertical / 45° / 2:1 / 3:1
    // slopes (up and down) to the farthest standable landing that ends against a block, then keep the
    // ones that actually SAVE travel (walk-distance minus dash cost). Longest-first is not enough once
    // shallow diagonals exist, so selection is by saving; a chest reachable only by dash is always kept.
    /** A shaft must clear at least this many VERTICAL blocks to count as a (generously-capped) vertical shaft. */
    public int shaftMinVertical = 6;
    /** Longest dash a single shaft edge will span (blocks) — bounds the ray-march. */
    public double shaftMaxLen = 24.0;
    /** Minimum travel a vertical shaft must SAVE (walk blocks − dash cost) to be kept. Against the measured
     *  dash cost (~30) this passes only for a long climb or a dash-only landing, which is the intent. */
    public double shaftMinSaving = 6.0;
    /** Minimum travel a horizontal/shallow shaft must save — a higher bar so few garbage horizontals fire. */
    public double shaftMinSavingHoriz = 10.0;
    /** Cap on kept vertical shafts per room (ranked by saving). */
    public int shaftCapVertical = 24;
    /** Cap on kept horizontal/shallow shafts per room. 0 = none (horizontal dashes made the tour teleport sideways). */
    public int shaftCapHoriz = 0;
    /** Safety cap on walk-distance Dijkstra runs during shaft scoring; beyond it, remaining shafts rank by length. */
    public int shaftMaxDijkstra = 300;

    // ---- DROP edges (step off a ledge and fall) ----
    // Players don't walk down a long staircase and they don't trident down a shaft — they walk off the edge
    // and fall, losing no speed before or after. Without a drop edge the walk graph could only descend 3
    // blocks a step, so anything taller had to be a staircase or a 30-cost dash, and the route showed it.
    /** Flat cost (blocks-equiv) of committing to a fall. Near-free — it's just walking off an edge. */
    public double dropActionCost = 1.0;
    /** Cost per sqrt(block) of fall height. Fall time is ~sqrt(2h/32)s, which at ~16.7 blk/s is ~4.2·sqrt(h) blocks. */
    public double dropHeightWeight = 4.0;
    /** Tallest fall the solver will route you off (blocks). Below 4 there are no drop edges (walk covers 1-3 down). */
    public int dropMaxHeight = 40;

    // ---- open-space sprint straight-shots (sprint-jump between two visible chests, loots along the line) ----
    // In big open rooms, two chests with clear line-of-sight get linked by a cheap straight line you
    // sprint-jump along (looting as you go) — distinct from a trident (no loot mid-burst). Never player-based.
    /** Cost per block of a sprint straight-shot; keep below the open-walk per-block cost (1.0) so it wins in the
     *  open. MEASURED: a sprint/dash line realizes ~31.6 blk/s vs ~16.7 for an open walk = 0.53. */
    public double openSprintWeight = 0.55;
    /** Don't sprint-line hops shorter than this (blocks) — normal walk handles them. */
    public double openSprintMinDist = 3.0;
    /** Both ends need this wall-clearance (blocks, walls-only) for a sprint line. Low so sprint fires through dense fields. */
    public int openSprintMinClear = 2;
    /** Max vertical change a sprint line may span (blocks) — it's a sprint-jump, not a climb. */
    public int openSprintMaxRise = 3;

    /** Throughput knob (see NORTH_STAR.md): loot down to this fraction of the vault's typical HOT-SPOT rate.
     *  Higher = skim to the best clusters only (more chests/min if hallways are cheap); lower = loot fuller. */
    public double bailAggression = 0.35;

    // ---- routing cost weights (execution-difficulty model; all in "blocks", tune live) ----
    // ---- MEASURED clearance cost multipliers (cost of one block travelled; one open-walk block = 1.0) ----
    // From the 2026-09-19 data run's median realized speed per wall-clearance band: 4.3 blk/s at 0-1,
    // 10.2 at 2-3, 14.2 at 4-6, 16.7 at >=7. Clearance >= 7 is open and costs 1.0 (implicit, not a field).
    /** Cost multiplier per block travelled in a sub-3-wide gap (clearance <= 1). */
    public double tightMult = 3.9;
    /** Cost multiplier per block travelled in a narrow corridor (clearance 2-3). */
    public double narrowMult = 1.6;
    /** Cost multiplier per block travelled in moderate space (clearance 4-6). */
    public double midMult = 1.2;
    /** Anchor for the openness BLEND only (turn factor, proximity radius) — no travel cost reads it since v13. */
    public int clearanceMin = 4;
    /** Clearance (blocks-to-wall) at which the openness blend saturates — blend anchor only, not a cost term. */
    public int openSatClearance = 7;
    /** Penalty (blocks) per not-yet-broken chest walling in a candidate trigger — routes to easy EDGE chests.
     *  MEASURED 0: peeling from the edge makes each trigger under-fill its chain, so a dense room costs chests
     *  per stop; freehand runs trigger from cluster CENTRES instead. Slider kept so it can be tuned back up. */
    public double corePenaltyWeight = 0.0;
    // ---- dense-cluster proximity discount: consecutive breaks packed close together are nearly free
    // (one arm-swing clears several — "5 breaks ≈ 1 break"). The radius widens in open space.
    /** Fraction of a break's move cost DISCOUNTED when it sits right on the previous break (0..1); fades to 0 by the radius. */
    public double proximityBonus = 0.9;
    /** Radius (blocks) within which the proximity discount applies in tight/normal space. */
    public double proximityRadius = 4.0;
    /** Radius (blocks) within which the proximity discount applies in maximally-open space. */
    public double proximityRadiusOpen = 6.0;
    /** Penalty (blocks) per block a chest sits >=3 above the nearest standable spot — discourages aim-up corner chests. */
    public double abovePathWeight = 2.0;
    /** Penalty (blocks) per solid face beyond 4 (walls + unmined chests) — discourages near-buried corner chests. */
    public double enclosureWeight = 4.0;
    /** Cost (blocks) per block climbed. MEASURED near-free: a 1-block step-up costs no measurable time, and the
     *  walk graph only ever produces +1 steps, so this is a tie-breaker against flat routes, not a real penalty. */
    public double upCost = 0.5;
    /** Penalty (blocks) per block dropped — down is easy. */
    public double downCost = 0.3;
    /** Turn penalty (blocks per radian) — routes prefer chests ahead, not off-axis flicks. */
    public double turnWeight = 7.0;
    /** Turn penalty multiplier in fully-open space (0..1) — kept high so open-room tours don't zig-zag/bounce. */
    public double turnOpenFactor = 0.7;
    /** LIGHT per-turn cost on the WALK PATH itself (blocks per radian of heading change). Straightens the green
     *  ribbon so it stops zig-zagging between equal-cost grid steps; keep small. 0 = off (old bouncy behaviour). */
    public double pathTurnWeight = 0.2;
    /** Require line-of-sight to a chest for it to be a waypoint (the chain still clears the rest). */
    public boolean losRequired = true;
    /** Trigger reach (blocks) for breaking chests as you follow the route. Lower = break chests closer/more head-on
     *  (fewer wide cursor sweeps, more humanlike); higher = reach out to grab distant chests. Below the 6.0 geometry reach. */
    public double breakReach = 4.5;
    /** Fixed cost (blocks) of stopping at a waypoint at all. MEASURED 0.45-0.55s of stop/aim/break overhead per
     *  waypoint = ~8 open-walk blocks. Applies to SELECTION only (constant per waypoint = no ordering effect),
     *  but it rides in both recorded marginals so the hot-spot rate and the bail cut share one scale. */
    public double waypointOverhead = 8.0;

    /** Bumped when the tuned weight defaults change; a stale file gets its weights reset (other settings kept). */
    public int weightsVersion = 0;
    static final int CURRENT_WEIGHTS_VERSION = 14;

    /** Diff mode: solve the route but keep it HIDDEN unless routing is also on, track YOUR path, and write a per-room
     *  {@code room_diff} record (your path vs the solver's, scored by the same cost model) into the vault's run log.
     *  If BOTH this and routing are off, no route is solved (no wasted compute). */
    public boolean diffRoute = true;

    /** Hide the_vault's Hunter chest outlines (the yellow boxes) so they don't clutter the route overlay. */
    public boolean suppressHunter = false;

    public static RouterunnerConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new RouterunnerConfig();
            INSTANCE.fillDefaults();
        }
        return INSTANCE;
    }

    private void fillDefaults() {
        if (hud == null) {
            hud = new EnumMap<>(HudElementId.class);
        }
        for (HudElementId id : HudElementId.values()) {
            hud.computeIfAbsent(id, i -> new ElementConfig(i.defaultVisible, i.defaultX, i.defaultY));
        }
    }

    public ElementConfig element(HudElementId id) {
        return hud.computeIfAbsent(id, i -> new ElementConfig(i.defaultVisible, i.defaultX, i.defaultY));
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("routerunner").resolve("config.json");
    }

    public static void load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            // First run: write defaults so the file is discoverable/editable.
            INSTANCE = new RouterunnerConfig();
            INSTANCE.fillDefaults();
            save();
            return;
        }
        try (Reader r = Files.newBufferedReader(path)) {
            RouterunnerConfig loaded = GSON.fromJson(r, RouterunnerConfig.class);
            if (loaded == null) {
                throw new IOException("config parsed to null");
            }
            INSTANCE = loaded;
            INSTANCE.fillDefaults();
            INSTANCE.migrateWeights();
            INSTANCE.clampWeights();
        } catch (Exception e) {
            // Fall back to defaults rather than crashing; log so the trigger is visible.
            LOGGER.error("[Routerunner] Failed to read config at {}; using defaults.", path, e);
            INSTANCE = new RouterunnerConfig();
            INSTANCE.fillDefaults();
        }
    }

    /** If the persisted file predates the current tuned weights, reset ONLY the weight fields (keep toggles/HUD/etc.). */
    private void migrateWeights() {
        if (weightsVersion >= CURRENT_WEIGHTS_VERSION) return;
        RouterunnerConfig d = new RouterunnerConfig(); // fresh defaults
        tightMult = d.tightMult;
        narrowMult = d.narrowMult;
        midMult = d.midMult;
        clearanceMin = d.clearanceMin;
        openSatClearance = d.openSatClearance;
        corePenaltyWeight = d.corePenaltyWeight;
        proximityBonus = d.proximityBonus;
        proximityRadius = d.proximityRadius;
        proximityRadiusOpen = d.proximityRadiusOpen;
        abovePathWeight = d.abovePathWeight;
        enclosureWeight = d.enclosureWeight;
        upCost = d.upCost;
        downCost = d.downCost;
        turnWeight = d.turnWeight;
        turnOpenFactor = d.turnOpenFactor;
        pathTurnWeight = d.pathTurnWeight;
        losRequired = d.losRequired;
        breakReach = d.breakReach;
        waypointOverhead = d.waypointOverhead;
        tridentActionCost = d.tridentActionCost;
        tridentDistWeight = d.tridentDistWeight;
        tridentMinDist = d.tridentMinDist;
        shaftMinVertical = d.shaftMinVertical;
        shaftMaxLen = d.shaftMaxLen;
        shaftMinSaving = d.shaftMinSaving;
        shaftMinSavingHoriz = d.shaftMinSavingHoriz;
        shaftCapVertical = d.shaftCapVertical;
        shaftCapHoriz = d.shaftCapHoriz;
        shaftMaxDijkstra = d.shaftMaxDijkstra;
        dropActionCost = d.dropActionCost;
        dropHeightWeight = d.dropHeightWeight;
        dropMaxHeight = d.dropMaxHeight;
        openSprintWeight = d.openSprintWeight;
        openSprintMinDist = d.openSprintMinDist;
        openSprintMinClear = d.openSprintMinClear;
        openSprintMaxRise = d.openSprintMaxRise;
        bail = d.bail;
        bailAggression = d.bailAggression;
        lookahead = d.lookahead;
        weightsVersion = CURRENT_WEIGHTS_VERSION;
        LOGGER.info("[Routerunner] Reset routing weights to v{} tuned defaults (toggles/HUD preserved).", CURRENT_WEIGHTS_VERSION);
        save();
    }

    /**
     * Clamp hand-edited weights back into the range the cost model actually defines. Out-of-range values
     * don't crash the solver, they silently distort it, so each clamp is logged at error level.
     */
    private void clampWeights() {
        if (turnOpenFactor < 0.0 || turnOpenFactor > 1.0) {
            double was = turnOpenFactor;
            turnOpenFactor = turnOpenFactor < 0.0 ? 0.0 : 1.0;
            LOGGER.error("[Routerunner] turnOpenFactor {} is outside [0,1]; clamped to {}.", was, turnOpenFactor);
            save();
        }
        if (turnaroundDeg < 1 || turnaroundDeg > 180) {
            int was = turnaroundDeg;
            turnaroundDeg = turnaroundDeg < 1 ? 1 : 180;
            LOGGER.error("[Routerunner] turnaroundDeg {} is outside [1,180]; clamped to {}.", was, turnaroundDeg);
            save();
        }
    }

    public static void save() {
        RouterunnerConfig cfg = get();
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(cfg, w);
            }
        } catch (Exception e) {
            LOGGER.error("[Routerunner] Failed to write config at {}.", path, e);
        }
    }

    /** A single movable/hideable HUD readout. */
    public static class ElementConfig {
        public boolean visible;
        public int x;
        public int y;

        public ElementConfig() {}

        public ElementConfig(boolean visible, int x, int y) {
            this.visible = visible;
            this.x = x;
            this.y = y;
        }
    }

    /** The Tier-0 metrics, each independently placeable. */
    public enum HudElementId {
        TOTAL(true, 5, 5),
        NET_AVG(true, 5, 17),
        ACTIVE_AVG(true, 5, 29),
        SLIDING(true, 5, 41);

        public final boolean defaultVisible;
        public final int defaultX;
        public final int defaultY;

        HudElementId(boolean defaultVisible, int defaultX, int defaultY) {
            this.defaultVisible = defaultVisible;
            this.defaultX = defaultX;
            this.defaultY = defaultY;
        }
    }

    /** Loot-listener tracking mode. AUTO resolves from the first 100 mined chests; ALL hides the panel. */
    public enum TrackedChest {
        AUTO, ALL, GILDED, ORNATE, LIVING, WOODEN
    }
}
