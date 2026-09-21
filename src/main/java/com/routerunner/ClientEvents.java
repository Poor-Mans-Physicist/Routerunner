package com.routerunner;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge-bus client runtime and vault lifecycle. Scanning and tracking run only in the vault dimension.
 * State resets on entry, is persisted periodically and on disconnect (resumed on reconnect), and a summary
 * is appended to history on a clean exit. The vault's {@link RunLog} file opens once the vault id resolves
 * and closes on exit or disconnect.
 */
@Mod.EventBusSubscriber(modid = Routerunner.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {

    private static final long SCAN_INTERVAL_MS = 500L;
    private static final long SAVE_INTERVAL_MS = 10_000L;
    /** Relative persistent-speed change that triggers a speed record. */
    private static final double SPEED_LOG_FRACTION = 0.01;
    /** Minimum gap between persistent-speed records. */
    private static final long SPEED_MIN_INTERVAL_MS = 2000L;
    /** Delay after the vault id resolves before the second weights snapshot is logged. */
    private static final long WEIGHTS_SNAPSHOT_DELAY_MS = 5000L;

    private static boolean inVaultPrev = false;
    private static String loadedVaultId = null;
    private static long lastScanMs = 0L;
    private static long lastSaveMs = 0L;
    private static List<String> lastModifiers = new ArrayList<>();
    private static List<String> loggedModifiers = new ArrayList<>();
    private static boolean prevEnabled = true;
    private static double lastSpeedBase = -1.0;
    private static long lastSpeedMs = 0L;
    private static String lastSpeedMods = null;
    private static long vaultIdResolvedMs = 0L;
    private static boolean loggedWeightsSnapshot = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        while (KeyBindings.OPEN_MENU.consumeClick()) {
            mc.setScreen(new RouterunnerConfigScreen(null));
        }

        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            if (inVaultPrev) {
                LookSampler.drainTo();
                RouteService.reset();
                AdaptiveWeights.get().save();
                RunLog.pause("suspend");
                RunLog.close();
                suspend();
                inVaultPrev = false;
                loadedVaultId = null;
            }
            return;
        }

        boolean inVault = isInVault(level);
        if (inVault && !inVaultPrev) {
            resetTrackers();
            clearVaultState();
            prevEnabled = RouterunnerConfig.get().enabled;
        } else if (!inVault && inVaultPrev) {
            LookSampler.drainTo();
            RouteService.reset();
            AdaptiveWeights.get().save();
            finalizeVault();
            logVaultExit();
            logBreakSources();
            RunLog.close();
            RunPersistence.deleteCurrent();
            resetTrackers();
            clearVaultState();
        }
        inVaultPrev = inVault;
        if (!inVault) return;

        if (loadedVaultId == null) {
            String vid = safeVaultId();
            if (vid != null) {
                loadedVaultId = vid;
                vaultIdResolvedMs = System.currentTimeMillis();
                List<String> resumed = RunPersistence.tryLoad(vid);
                if (resumed != null) lastModifiers = resumed;
                boolean resumedLog = RunLog.open(vid);
                if (resumedLog) {
                    RunLog.resume("reconnect");
                    loggedModifiers = new ArrayList<>(lastModifiers);
                } else {
                    RunLog.vaultEnter(vid, MetricsTracker.get().getLap());
                }
                logSpeed(player);
            }
        }

        RouterunnerConfig cfg = RouterunnerConfig.get();
        if (prevEnabled && !cfg.enabled) {
            LookSampler.drainTo();
            RunLog.pause("disabled");
        } else if (!prevEnabled && cfg.enabled) {
            RunLog.resume("enabled");
            logSpeed(player);
        }
        prevEnabled = cfg.enabled;
        if (!cfg.enabled) return;

        boolean paused = mc.isPaused() || (mc.screen instanceof PauseScreen);
        MetricsTracker.get().advanceClock(paused);

        TeleportDetector.update(player);
        RunLog.pos(player);
        LookSampler.drainTo();
        if (loadedVaultId != null) checkSpeedChange(player);
        if (loadedVaultId != null && !loggedWeightsSnapshot
                && System.currentTimeMillis() - vaultIdResolvedMs >= WEIGHTS_SNAPSHOT_DELAY_MS) {
            loggedWeightsSnapshot = true;
            logWeightsSnapshot();
        }

        try {
            ChestScanner.tickCheck(level, player.blockPosition());
        } catch (Exception e) {
            LogUtils.getLogger().error("[Routerunner] per-tick chest check failed", e);
        }

        long now = System.currentTimeMillis();
        if (now - lastScanMs >= SCAN_INTERVAL_MS) {
            lastScanMs = now;
            try {
                ChestScanner.scan(level, player.blockPosition());
            } catch (Exception e) {
                LogUtils.getLogger().error("[Routerunner] chest scan failed", e);
            }
            refreshModifiers();
        }
        if (loadedVaultId != null && now - lastSaveMs >= SAVE_INTERVAL_MS) {
            lastSaveMs = now;
            RunPersistence.save(loadedVaultId, lastModifiers);
        }

        try {
            RouteService.onClientTick(level, player);
        } catch (Exception e) {
            LogUtils.getLogger().error("[Routerunner] routing tick failed", e);
        }
    }

    /** True iff in the vault dimension (mirrors the_vault's own namespace check). */
    public static boolean isInVault(Level level) {
        return level != null && level.dimension().location().getNamespace().equals("the_vault");
    }

    private static void finalizeVault() {
        try {
            MetricsTracker m = MetricsTracker.get();
            if (m.getTotal() <= 0) return;
            VaultSummary s = new VaultSummary();
            s.timestamp = System.currentTimeMillis();
            String type = LootListener.get().getResolvedType();
            s.type = type == null ? "unknown" : type;
            s.chests = m.getTotal();
            s.laps = m.getLap();
            s.netAvg = m.getNetAvgPerMin();
            s.activeAvg = m.getActiveAvgPerMin();
            s.activeMinutes = m.getActiveMs() / 60_000.0;
            s.modifiers = new ArrayList<>(lastModifiers);
            s.loot = LootListener.get().getItemTotals();
            HistoryStore.append(s);
        } catch (Exception e) {
            LogUtils.getLogger().error("[Routerunner] failed to record vault summary", e);
        }
    }

    /** Writes the closing {@code vault_exit} record with whole-vault totals. */
    private static void logVaultExit() {
        try {
            MetricsTracker m = MetricsTracker.get();
            String type = LootListener.get().getResolvedType();
            RunLog.vaultExit(m.getTotal(), m.getLap(), m.getNetMs(), m.getActiveMs(),
                    m.getNetAvgPerMin(), m.getActiveAvgPerMin(), type == null ? "unknown" : type,
                    lastModifiers, LootListener.get().getItemTotals());
        } catch (Exception e) {
            LogUtils.getLogger().error("[Routerunner] failed to write the vault_exit record", e);
        }
    }

    /** Logs how many of the vault's chest breaks came from the per-tick check versus the periodic scan. */
    private static void logBreakSources() {
        LogUtils.getLogger().info("[Routerunner] chest breaks this vault: {} tick-exact (tickCheck), {} from the 500 ms scan.",
                ChestScanner.tickCheckBreaks(), ChestScanner.scanBreaks());
    }

    /**
     * Logs a second weight snapshot {@link #WEIGHTS_SNAPSHOT_DELAY_MS} after the vault id resolves, once the
     * ability tree and MOVEMENT_SPEED have synced.
     */
    private static void logWeightsSnapshot() {
        try {
            RunLog.weights(RouteService.snapshotParams().toJson());
        } catch (Exception e) {
            LogUtils.getLogger().error("[Routerunner] failed to write the delayed weights snapshot; this vault only has vault_enter's stale one.", e);
        }
    }

    private static void resetTrackers() {
        ChestScanner.reset();
        MetricsTracker.get().reset();
        LootListener.get().reset();
        RouteService.reset();
        LookSampler.reset();
        TeleportDetector.reset();
        RunLog.reset();
    }

    private static void clearVaultState() {
        loadedVaultId = null;
        lastModifiers = new ArrayList<>();
        loggedModifiers = new ArrayList<>();
        lastSpeedBase = -1.0;
        lastSpeedMs = 0L;
        lastSpeedMods = null;
        vaultIdResolvedMs = 0L;
        loggedWeightsSnapshot = false;
    }

    /**
     * Start a new lap: the HUD counters restart, the vault clocks / run log / history do NOT.
     * No-op outside a vault. Persists immediately so the lap survives a reload.
     *
     * @return the new lap number, or 0 if not in a vault
     */
    public static int newLap() {
        Minecraft mc = Minecraft.getInstance();
        if (!isInVault(mc.level)) return 0;
        MetricsTracker m = MetricsTracker.get();
        m.newLap();
        LootListener.get().reset();
        RunLog.lap(m.getLap(), m.getTotal(), m.getActiveMs());
        logSpeed(mc.player);
        if (loadedVaultId != null) RunPersistence.save(loadedVaultId, lastModifiers);
        return m.getLap();
    }

    /** Write a {@code vault_info} record whenever the crystal's modifier list first resolves or changes. */
    private static void refreshModifiers() {
        List<String> m = safeModifiers();
        if (m.isEmpty() || m.equals(loggedModifiers)) {
            if (!m.isEmpty()) lastModifiers = m;
            return;
        }
        lastModifiers = m;
        loggedModifiers = new ArrayList<>(m);
        RunLog.vaultInfo(m);
    }

    private static void logSpeed(Player player) {
        double persistent = PlayerSpeed.persistentAttribute(player);
        java.util.List<String> mods = PlayerSpeed.transientModifiers(player);
        RunLog.speed(PlayerSpeed.attribute(player), persistent, player != null && player.isSprinting(),
                PlayerSpeed.fastRunAmount(player), PlayerSpeed.entitySpeed(player),
                PlayerSpeed.flyingSpeed(player), mods);
        lastSpeedBase = persistent;
        lastSpeedMods = modSignature(mods);
        lastSpeedMs = System.currentTimeMillis();
    }

    /** Signature of the applied transient modifiers, excluding sprint and ParCool FastRun. */
    private static String modSignature(java.util.List<String> mods) {
        StringBuilder sb = new StringBuilder(64);
        for (String m : mods) {
            if (m.contains("vanilla.sprint") || m.contains("parcool.modifier.fast_run")) continue;
            sb.append(m).append('|');
        }
        return sb.toString();
    }

    /**
     * Logs a speed record immediately when the transient-modifier signature changes, and at most once every
     * {@link #SPEED_MIN_INTERVAL_MS} when the persistent value moves more than 1 %.
     */
    private static void checkSpeedChange(Player player) {
        String mods = modSignature(PlayerSpeed.transientModifiers(player));
        if (lastSpeedBase < 0 || !mods.equals(lastSpeedMods)) {
            logSpeed(player);
            return;
        }
        if (System.currentTimeMillis() - lastSpeedMs < SPEED_MIN_INTERVAL_MS) return;
        double persistent = PlayerSpeed.persistentAttribute(player);
        double threshold = Math.max(1e-6, SPEED_LOG_FRACTION * Math.abs(lastSpeedBase));
        if (Math.abs(persistent - lastSpeedBase) > threshold) logSpeed(player);
    }

    private static String safeVaultId() {
        try { return VaultInfo.getVaultId(); } catch (Throwable t) { return null; }
    }

    private static List<String> safeModifiers() {
        try { return VaultInfo.getModifierSummary(); } catch (Throwable t) { return new ArrayList<>(); }
    }

    private static void suspend() {
        if (loadedVaultId != null) RunPersistence.save(loadedVaultId, lastModifiers);
    }

    private ClientEvents() {}
}
