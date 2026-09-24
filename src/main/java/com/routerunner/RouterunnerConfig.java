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

    /** Hide the_vault's Hunter chest outlines. */
    public boolean suppressHunter = false;

    /** Learn this player's pace, per-burst cost and leg timing while they play (config/routerunner/adaptive/); off = the bundled model. */
    public boolean adaptiveLearning = true;
    /** Delete the oldest run logs once the runs folder is over {@link #runLogCapMB}. */
    public boolean runLogCap = true;
    public int runLogCapMB = 500;

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
        if (routingSkipList == null) routingSkipList = new ArrayList<>(List.of("labyrinth"));
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
            INSTANCE.clamp();
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

    /** The metric HUD elements, each independently placeable; DENSITY is the lap's average room density. */
    public enum HudElementId {
        TOTAL(true, 5, 5),
        NET_AVG(true, 5, 17),
        ACTIVE_AVG(true, 5, 29),
        SLIDING(true, 5, 41),
        DENSITY(true, 5, 53);

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
