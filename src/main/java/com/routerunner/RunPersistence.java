package com.routerunner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes the running vault metrics robust to disconnects/server restarts. A snapshot is written
 * periodically (and on suspend) to config/routerunner/current_vault.json, keyed by the vault id;
 * on re-entry the saved state is restored only if the ids match. The file is deleted on a clean
 * vault exit so the next vault starts fresh.
 */
public final class RunPersistence {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("routerunner").resolve("current_vault.json");
    }

    public static void save(String vaultId, List<String> modifiers) {
        if (vaultId == null) return;
        try {
            Snapshot s = new Snapshot();
            s.vaultId = vaultId;
            MetricsTracker m = MetricsTracker.get();
            s.total = m.getTotal();
            s.netMs = m.getNetMs();
            s.activeMs = m.getActiveMs();
            s.lap = m.getLap();
            s.lapStartTotal = m.getLapStartTotal();
            s.lapStartNetMs = m.getLapStartNetMs();
            s.lapStartActiveMs = m.getLapStartActiveMs();
            LootListener l = LootListener.get();
            s.resolvedType = l.getResolvedType();
            s.engaged = l.isEngaged();
            s.lootStartNet = l.getLootStartNet();
            s.lootStartActive = l.getLootStartActive();
            s.autoCounts = l.getAutoCountsCopy();
            s.autoSampled = l.getAutoSampled();
            s.itemTotals = l.getItemTotals();
            s.modifiers = modifiers != null ? new ArrayList<>(modifiers) : new ArrayList<>();

            Path p = file();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(s, w);
            }
        } catch (Exception e) {
            LOG.error("[Routerunner] Failed to save current vault state.", e);
        }
    }

    /** Restores metrics+loot if the saved id matches; returns the saved modifier list, or null. */
    public static List<String> tryLoad(String vaultId) {
        if (vaultId == null) return null;
        Path p = file();
        if (!Files.exists(p)) return null;
        try (Reader r = Files.newBufferedReader(p)) {
            Snapshot s = GSON.fromJson(r, Snapshot.class);
            if (s == null || !vaultId.equals(s.vaultId)) return null; // stale or a different vault
            MetricsTracker.get().restore(s.total, s.netMs, s.activeMs,
                    s.lap, s.lapStartTotal, s.lapStartNetMs, s.lapStartActiveMs);
            LootListener.get().restore(s.resolvedType, s.engaged, s.lootStartNet, s.lootStartActive,
                    s.autoCounts, s.autoSampled, s.itemTotals);
            LOG.info("[Routerunner] Resumed vault {} ({} chests).", vaultId, s.total);
            return s.modifiers != null ? s.modifiers : new ArrayList<>();
        } catch (Exception e) {
            LOG.error("[Routerunner] Failed to load current vault state.", e);
            return null;
        }
    }

    public static void deleteCurrent() {
        try {
            Files.deleteIfExists(file());
        } catch (Exception e) {
            LOG.error("[Routerunner] Failed to delete current vault state.", e);
        }
    }

    private RunPersistence() {}

    public static class Snapshot {
        public String vaultId;
        public int total;
        public long netMs;
        public long activeMs;
        public int lap = 1;
        public int lapStartTotal;
        public long lapStartNetMs;
        public long lapStartActiveMs;
        public String resolvedType;
        public boolean engaged;
        public long lootStartNet;
        public long lootStartActive;
        public Map<String, Integer> autoCounts = new HashMap<>();
        public int autoSampled;
        public Map<String, Long> itemTotals = new HashMap<>();
        public List<String> modifiers = new ArrayList<>();
    }
}
