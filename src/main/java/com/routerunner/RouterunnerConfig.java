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
 * Persisted client config (Gson JSON at config/routerunner/config.json). Falls back to defaults, with an
 * error log, if the file is corrupt.
 */
public class RouterunnerConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static RouterunnerConfig INSTANCE;

    /** Master enable flag for the whole mod. */
    public boolean enabled = true;
    /** Placement and visibility of each metric HUD element. */
    public Map<HudElementId, ElementConfig> hud = new EnumMap<>(HudElementId.class);

    /** Which vault chest's loot the loot panel tracks (AUTO decides from the first 100 mined chests). */
    public TrackedChest trackedChest = TrackedChest.AUTO;
    /** Loot panel position (moved as one unit in the HUD editor) and visibility. */
    public int lootPanelX = 5;
    public int lootPanelY = 64;
    public boolean lootPanelVisible = true;

    /** Master toggle for the waypoint solver and route overlay. */
    public boolean routingEnabled = true;
    /** Manual absolute leave threshold (chests per travel cost); 0 = auto (bailAggression x hot-spot rate). */
    public double bail = 0.0;
    /** Movement-profile name stamped on logged records and used to key the adaptive weights. */
    public String profileName = "default";
    /** Room-id substrings that disable routing in matching rooms. */
    public List<String> routingSkipList = new ArrayList<>(List.of("labyrinth"));
    /** Number of upcoming waypoints the overlay shows ahead. */
    public int lookahead = 5;
    /** Skip a waypoint that was flown past or whose cluster is mostly spent and continue to the next one. */
    public boolean missedSkip = true;
    /** Once a waypoint's trigger chest is gone, treat it as done if at most this many chests remain; 0 = off. */
    public int stragglerSkip = 3;
    /** Bend angle (degrees, 1-180) past which a waypoint is drawn as a turnaround; display only. */
    public int turnaroundDeg = 120;
    /** Draw a screen-edge arrow toward the current route target while it is off screen. */
    public boolean offscreenIndicator = true;
    /** Scale the measured weights by this profile's own movement speed (see {@link AdaptiveWeights}). */
    public boolean adaptiveWeights = true;

    /** Flat cost (blocks-equiv) of taking a trident dash. */
    public double tridentActionCost = 30.0;
    /** Per-block cost of a trident dash. */
    public double tridentDistWeight = 0.04;
    /** Minimum hop length (blocks) for a trident dash. */
    public double tridentMinDist = 6.0;

    /** Minimum vertical span (blocks) for a vertical trident shaft. */
    public int shaftMinVertical = 6;
    /** Longest dash (blocks) a single shaft edge may span. */
    public double shaftMaxLen = 24.0;
    /** Minimum travel a vertical shaft must save (walk blocks minus dash cost) to be kept. */
    public double shaftMinSaving = 6.0;
    /** Minimum travel a horizontal/shallow shaft must save to be kept. */
    public double shaftMinSavingHoriz = 10.0;
    /** Maximum vertical shafts kept per room, ranked by saving. */
    public int shaftCapVertical = 24;
    /** Maximum horizontal/shallow shafts kept per room; 0 = none. */
    public int shaftCapHoriz = 0;
    /** Cap on walk-distance Dijkstra runs during shaft scoring; remaining shafts rank by length. */
    public int shaftMaxDijkstra = 300;

    /** Flat cost (blocks-equiv) of stepping off a ledge. */
    public double dropActionCost = 1.0;
    /** Cost per sqrt(block) of fall height. */
    public double dropHeightWeight = 4.0;
    /** Tallest fall (blocks) the solver will route; drop edges start at 4 blocks. */
    public int dropMaxHeight = 40;

    /** Per-block cost of an open-space sprint line between two visible chests (open walk = 1.0). */
    public double openSprintWeight = 0.55;
    /** Minimum sprint-line length (blocks). */
    public double openSprintMinDist = 3.0;
    /** Wall clearance (blocks, walls only) both ends of a sprint line need. */
    public int openSprintMinClear = 2;
    /** Maximum vertical change (blocks) a sprint line may span. */
    public int openSprintMaxRise = 3;

    /** Loot down to this fraction of the vault's typical hot-spot rate; higher = skim only the best clusters. */
    public double bailAggression = 0.35;

    /** Cost multiplier per block travelled at clearance &lt;= 1 (open walk = 1.0). */
    public double tightMult = 3.9;
    /** Cost multiplier per block travelled at clearance 2-3. */
    public double narrowMult = 1.6;
    /** Cost multiplier per block travelled at clearance 4-6. */
    public double midMult = 1.2;
    /** Clearance anchor for the openness blend (turn factor, proximity radius); not a travel cost. */
    public int clearanceMin = 4;
    /** Clearance at which the openness blend saturates. */
    public int openSatClearance = 7;
    /** Penalty (blocks) per unbroken chest walling in a candidate trigger chest. */
    public double corePenaltyWeight = 0.0;
    /** Fraction (0-1) of a break's move cost discounted when it sits on the previous break; fades to 0 by the radius. */
    public double proximityBonus = 0.9;
    /** Proximity-discount radius (blocks) in tight space. */
    public double proximityRadius = 4.0;
    /** Proximity-discount radius (blocks) in fully open space. */
    public double proximityRadiusOpen = 6.0;
    /** Penalty (blocks) per block a chest sits 3 or more above the nearest standable spot. */
    public double abovePathWeight = 2.0;
    /** Penalty (blocks) per solid face beyond 4 around a chest. */
    public double enclosureWeight = 4.0;
    /** Cost (blocks) per block climbed. */
    public double upCost = 0.5;
    /** Cost (blocks) per block dropped. */
    public double downCost = 0.3;
    /** Turn penalty (blocks per radian) between waypoints. */
    public double turnWeight = 7.0;
    /** Turn penalty multiplier (0-1) in fully open space. */
    public double turnOpenFactor = 0.7;
    /** Per-turn cost on the walk path itself (blocks per radian); straightens the drawn path. 0 = off. */
    public double pathTurnWeight = 0.2;
    /** Require line of sight to a chest for it to be a waypoint. */
    public boolean losRequired = true;
    /** Reach (blocks) at which a chest counts as broken from the route. */
    public double breakReach = 4.5;
    /** Fixed cost (blocks) of stopping at a waypoint. */
    public double waypointOverhead = 8.0;

    /** Weight-defaults version of the saved file; older files get their weight fields reset on load. */
    public int weightsVersion = 0;
    static final int CURRENT_WEIGHTS_VERSION = 14;

    /** Solve and score routes for per-room {@code room_diff} records even when the route is hidden. */
    public boolean diffRoute = true;

    /** Hide the_vault's Hunter chest outlines. */
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
            LOGGER.error("[Routerunner] Failed to read config at {}; using defaults.", path, e);
            INSTANCE = new RouterunnerConfig();
            INSTANCE.fillDefaults();
        }
    }

    /** If the persisted file predates the current tuned weights, reset ONLY the weight fields (keep toggles/HUD/etc.). */
    private void migrateWeights() {
        if (weightsVersion >= CURRENT_WEIGHTS_VERSION) return;
        RouterunnerConfig d = new RouterunnerConfig();
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

    /** Clamp hand-edited weights into their valid ranges, logging each clamp. */
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

    /** The metric HUD elements, each independently placeable. */
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
