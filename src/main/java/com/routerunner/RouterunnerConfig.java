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
 * error log, if the file is corrupt. Fields from older versions that no longer exist are ignored on load.
 */
public class RouterunnerConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static RouterunnerConfig INSTANCE;
    /** The config layout this build writes; older files are migrated once on load ({@link #migrate}). */
    static final int CONFIG_VERSION = 2;

    /** Layout version of this file: 0 for files written before 1.2.0. */
    public int configVersion = 0;

    /** Master enable flag for the whole mod. */
    public boolean enabled = true;
    /** Placement and visibility of each metric HUD element. */
    public Map<HudElementId, ElementConfig> hud = new EnumMap<>(HudElementId.class);

    /** Which vault chest's loot the loot panel tracks (AUTO decides from the first 100 mined chests). */
    public TrackedChest trackedChest = TrackedChest.AUTO;
    /** Loot panel position (moved as one unit in the HUD editor) and visibility. */
    public int lootPanelX = 5;
    public int lootPanelY = 76;
    public boolean lootPanelVisible = true;

    /** Draw the route. Rooms are solved and logged either way while the mod is enabled. */
    public boolean routingEnabled = true;
    /** Leave threshold as a fraction of the room's opportunity rate (the rate floor below usually binds first). */
    public double laneBail = 0.12;
    /** How strongly a lane is charged (or credited) for the change in walk-out time to the exit it causes: 1 = the
     *  full model delta, 0 = ignore the exit when choosing and stopping. */
    public double laneExitWeight = 1.0;
    /** Rate-anchored bail: a lane must beat this fraction of the running realized chest rate (last 2 minutes,
     *  converted to model seconds with the live model-to-real ratio) or the plan ends and leads to the exit. */
    public double laneBailRateFrac = 0.6;
    /** Plan lanes on the bundled Rust library when it loads; false forces the Java planner. */
    public boolean laneNative = true;
    /** Leave out (and don't highlight) chest groups too small to repay a break: under the running realized rate times
     *  the seconds one break costs. Needs the rate, so the first rooms of a vault are never pruned. */
    public boolean lanePrune = true;
    /** Room-id substrings that disable routing in matching rooms. */
    public List<String> routingSkipList = new ArrayList<>(List.of("labyrinth"));
    /** Draw a screen-edge arrow toward the next target chests while they are off screen. */
    public boolean offscreenIndicator = true;

    /** Adaptive room picker: leave each room by the door toward the best unvisited rooms around it (chest counts of
     *  the loaded rooms, chunk-alignment priors beyond them) instead of the door opposite the entrance. */
    public boolean adaptiveRooms = true;

    /** Opacity multiplier applied on top of every per-element opacity, in [0, 1]. */
    public double masterOpacity = 1.0;
    /** Per-element opacity in [0, 1]; missing elements default to 1 (see {@link Visuals.Element}). */
    public Map<Visuals.Element, Double> opacity = new EnumMap<>(Visuals.Element.class);

    /** Hide the_vault's Hunter chest outlines. */
    public boolean suppressHunter = false;

    /** Learn this player's pace, per-burst cost and leg timing while they play (config/routerunner/adaptive/); off = the bundled model. */
    public boolean adaptiveLearning = true;
    /**
     * Which time model the lane planner prices routes with: {@code shape} (the move model: runs, turns, turnarounds and
     * clicks priced from the route's cells, scaled to this player by the player calibration, see
     * {@code LegTimeModel.shape()}) or {@code learned} (the ridge leg model with the adaptive pace and per-burst cost).
     * Only {@code learned} rooms feed the adaptive pace calibration.
     */
    public String timeModel = "shape";
    /** Delete the oldest run logs once the runs folder is over {@link #runLogCapMB}. */
    public boolean runLogCap = true;
    public int runLogCapMB = 500;
    /** Keep every vault's run log, even when the density gate finds no room of 150+ chests (learning stays gated). */
    public boolean forceRunLog = false;
    /** Draw purple borders around the rooms on the densest chunk-alignment lines on Vault Mapper's map. */
    public boolean mapperAxisLines = false;

    public static RouterunnerConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new RouterunnerConfig();
            INSTANCE.configVersion = CONFIG_VERSION;
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
        if (routingSkipList == null) routingSkipList = new ArrayList<>(List.of("labyrinth"));
        if (opacity == null) opacity = new EnumMap<>(Visuals.Element.class);
        if (opacity.containsKey(null)) {
            LOGGER.error("[Routerunner] config opacity has an unknown element name; dropping it.");
            opacity.remove(null);
        }
        opacity.values().removeIf(v -> {
            if (v != null) return false;
            LOGGER.error("[Routerunner] config opacity has a null value; that element falls back to 1.0.");
            return true;
        });
        for (Visuals.Element e : Visuals.Element.values()) opacity.putIfAbsent(e, 1.0);
        if (!"learned".equals(timeModel) && !"shape".equals(timeModel)) {
            LOGGER.error("[Routerunner] config timeModel \"{}\" is not a time model (shape, learned); using shape.", timeModel);
            timeModel = "shape";
        }
    }

    /**
     * One-time upgrade of a file from before 1.2.0: the simple time model is gone and shape is the default (learned
     * and simple become shape; pick learned again in the menu), the density readout is a debug aid and starts hidden,
     * and the new Time readout goes above Chests (the stat column moves down a row when it is still at its defaults).
     */
    private void migrate() {
        if (configVersion < 2) {
            if (!"shape".equals(timeModel)) {
                LOGGER.info("[Routerunner] config upgrade to 1.2.0: time model {} -> shape (the new default; learned is still in the menu).", timeModel);
                timeModel = "shape";
            }
            if (hud == null) hud = new EnumMap<>(HudElementId.class);
            ElementConfig total = hud.get(HudElementId.TOTAL);
            boolean stock = total == null || (total.x == 5 && total.y == 5);
            int[][] old = {{5, 5}, {5, 17}, {5, 29}, {5, 41}, {5, 53}};
            HudElementId[] ids = {HudElementId.TOTAL, HudElementId.NET_AVG, HudElementId.ACTIVE_AVG, HudElementId.SLIDING, HudElementId.DENSITY};
            for (int i = 0; i < ids.length && stock; i++) {
                ElementConfig e = hud.get(ids[i]);
                if (e != null && (e.x != old[i][0] || e.y != old[i][1])) stock = false;
            }
            if (stock) {
                for (HudElementId id : ids) {
                    ElementConfig e = hud.get(id);
                    hud.put(id, new ElementConfig(e == null ? id.defaultVisible : e.visible, id.defaultX, id.defaultY));
                }
                hud.put(HudElementId.ELAPSED, new ElementConfig(true, HudElementId.ELAPSED.defaultX, HudElementId.ELAPSED.defaultY));
                if (lootPanelX == 5 && lootPanelY == 64) lootPanelY = 76;
            } else if (total != null) {
                hud.put(HudElementId.ELAPSED, new ElementConfig(true, total.x, Math.max(0, total.y - 12)));
            } else {
                hud.put(HudElementId.ELAPSED, new ElementConfig(true, HudElementId.ELAPSED.defaultX, HudElementId.ELAPSED.defaultY));
            }
            ElementConfig d = hud.get(HudElementId.DENSITY);
            if (d != null) d.visible = false;
            LOGGER.info("[Routerunner] config upgrade to 1.2.0: Time readout added {}, density readout hidden.",
                    stock ? "above Chests (stat column moved down one row)" : "12 px above your Chests readout");
        }
        configVersion = CONFIG_VERSION;
    }

    /** True when the lane planner should price routes with the shape (move) time model. */
    public boolean shapeTimeModel() {
        return "shape".equals(timeModel);
    }

    /** The other time model: shape and learned alternate. */
    public static String nextTimeModel(String cur) {
        return "shape".equals(cur) ? "learned" : "shape";
    }

    /** The label a time model shows in the menu. */
    public static String timeModelLabel(String m) {
        return "shape".equals(m) ? "Shape" : "Learned";
    }

    /** The stored opacity of one element, 1 when unset. */
    public double opacityOf(Visuals.Element e) {
        Double v = opacity.get(e);
        return v == null ? 1.0 : v;
    }

    /** Set every opacity, the master included, back to 1. */
    public void resetOpacity() {
        masterOpacity = 1.0;
        for (Visuals.Element e : Visuals.Element.values()) opacity.put(e, 1.0);
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
            INSTANCE.configVersion = CONFIG_VERSION;
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
            boolean upgrade = INSTANCE.configVersion < CONFIG_VERSION;
            if (upgrade) INSTANCE.migrate();
            INSTANCE.fillDefaults();
            INSTANCE.clamp();
            if (upgrade) save();
        } catch (Exception e) {
            LOGGER.error("[Routerunner] Failed to read config at {}; using defaults.", path, e);
            INSTANCE = new RouterunnerConfig();
            INSTANCE.fillDefaults();
        }
    }

    /** Clamp hand-edited lane settings into their valid ranges, logging each clamp. */
    private void clamp() {
        boolean changed = false;
        if (laneBail < 0.0 || laneBail > 1.0) {
            double was = laneBail;
            laneBail = laneBail < 0.0 ? 0.0 : 1.0;
            LOGGER.error("[Routerunner] laneBail {} is outside [0,1]; clamped to {}.", was, laneBail);
            changed = true;
        }
        if (laneExitWeight < 0.0 || laneExitWeight > 2.0) {
            double was = laneExitWeight;
            laneExitWeight = laneExitWeight < 0.0 ? 0.0 : 2.0;
            LOGGER.error("[Routerunner] laneExitWeight {} is outside [0,2]; clamped to {}.", was, laneExitWeight);
            changed = true;
        }
        if (laneBailRateFrac < 0.0 || laneBailRateFrac > 1.0) {
            double was = laneBailRateFrac;
            laneBailRateFrac = laneBailRateFrac < 0.0 ? 0.0 : 1.0;
            LOGGER.error("[Routerunner] laneBailRateFrac {} is outside [0,1]; clamped to {}.", was, laneBailRateFrac);
            changed = true;
        }
        if (!(masterOpacity >= 0.0 && masterOpacity <= 1.0)) {
            double was = masterOpacity;
            masterOpacity = masterOpacity < 0.0 ? 0.0 : 1.0;
            LOGGER.error("[Routerunner] masterOpacity {} is outside [0,1]; clamped to {}.", was, masterOpacity);
            changed = true;
        }
        for (Map.Entry<Visuals.Element, Double> e : opacity.entrySet()) {
            double v = e.getValue();
            if (v >= 0.0 && v <= 1.0) continue;
            double fixed = v < 0.0 ? 0.0 : 1.0;
            LOGGER.error("[Routerunner] opacity {} = {} is outside [0,1]; clamped to {}.", e.getKey(), v, fixed);
            e.setValue(fixed);
            changed = true;
        }
        if (runLogCapMB < 50) {
            int was = runLogCapMB;
            runLogCapMB = 50;
            LOGGER.error("[Routerunner] runLogCapMB {} is below 50; clamped to 50.", was);
            changed = true;
        }
        if (changed) save();
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

    /**
     * The metric HUD elements, each independently placeable; ELAPSED is the lap's active time, DENSITY the lap's average
     * room density (a debug aid, hidden by default).
     */
    public enum HudElementId {
        ELAPSED(true, 5, 5),
        TOTAL(true, 5, 17),
        NET_AVG(true, 5, 29),
        ACTIVE_AVG(true, 5, 41),
        SLIDING(true, 5, 53),
        DENSITY(false, 5, 65);

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
