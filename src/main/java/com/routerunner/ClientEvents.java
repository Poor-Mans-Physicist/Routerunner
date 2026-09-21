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
 * Forge-bus client runtime. Owns the vault lifecycle: Routerunner only scans/tracks while in the
 * vault dimension. State resets on entry, is persisted periodically + on disconnect (resume on
 * reconnect), a summary is appended to history on a clean exit, and the temp file is then removed.
 * The whole vault is written to ONE {@link RunLog} file, opened once the vault id resolves and
 * closed on exit or disconnect.
 */
@Mod.EventBusSubscriber(modid = Routerunner.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {

    private static final long SCAN_INTERVAL_MS = 500L;
    private static final long SAVE_INTERVAL_MS = 10_000L;
    private static final double SPEED_LOG_FRACTION = 0.01;   // log the speed attribute on a >1 % change
    private static final long SPEED_MIN_INTERVAL_MS = 2000L; // ...and never more often than this
    private static final long WEIGHTS_SNAPSHOT_DELAY_MS = 5000L; // ability tree + speed have synced by now

    private static boolean inVaultPrev = false;
    private static String loadedVaultId = null;
    private static long lastScanMs = 0L;
    private static long lastSaveMs = 0L;
    private static List<String> lastModifiers = new ArrayList<>();
    private static List<String> loggedModifiers = new ArrayList<>(); // what the run log has already recorded
    private static boolean prevEnabled = true;
    private static double lastSpeedBase = -1.0; // <0 = not logged yet this vault
    private static long lastSpeedMs = 0L;
    private static String lastSpeedMods = null; // the transient-modifier signature at the last speed record
    private static long vaultIdResolvedMs = 0L;         // when this vault's id came through
    private static boolean loggedWeightsSnapshot = false; // the delayed `weights` record has gone out

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
            // Disconnect / world unload: suspend (keep the temp file so we resume on reconnect).
            if (inVaultPrev) {
                LookSampler.drainTo(); // whatever was buffered when the connection dropped
                RouteService.reset(); // finalizes the room we were in, so its diff lands before the file closes
                AdaptiveWeights.get().save(); // ...and persist what that room measured
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
            // Entered a vault: start fresh; saved state (if any) is loaded once the id resolves.
            resetTrackers();
            clearVaultState();
            prevEnabled = RouterunnerConfig.get().enabled;
        } else if (!inVault && inVaultPrev) {
            // Left a vault while still connected = finished/exited: close the log out, then clear.
            LookSampler.drainTo(); // last frames of look detail, before the file closes
            RouteService.reset();  // last room's diff
            AdaptiveWeights.get().save(); // ...and its measurements
            finalizeVault();       // history summary (whole-vault totals)
            logVaultExit();
            logBreakSources();
            RunLog.close();
            RunPersistence.deleteCurrent();
            resetTrackers();
            clearVaultState();
        }
        inVaultPrev = inVault;
        if (!inVault) return;

        // Resolve the vault id and resume saved state once (a tick or two after entry).
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
                    loggedModifiers = new ArrayList<>(lastModifiers); // the existing file already has them
                } else {
                    RunLog.vaultEnter(vid, MetricsTracker.get().getLap());
                }
                logSpeed(player);
            }
        }

        RouterunnerConfig cfg = RouterunnerConfig.get();
        if (prevEnabled && !cfg.enabled) {
            LookSampler.drainTo();    // sampling stops with the pause; don't strand the last frames
            RunLog.pause("disabled"); // clocks freeze from here — being disabled IS the pause
        } else if (!prevEnabled && cfg.enabled) {
            RunLog.resume("enabled");
            logSpeed(player);
        }
        prevEnabled = cfg.enabled;
        if (!cfg.enabled) return;

        boolean paused = mc.isPaused() || (mc.screen instanceof PauseScreen);
        MetricsTracker.get().advanceClock(paused);

        TeleportDetector.update(player); // position discontinuities, before anything reads this tick's movement
        RunLog.pos(player);      // one record per client tick (20 Hz)
        LookSampler.drainTo();   // this tick's per-frame look samples, as one batched record
        if (loadedVaultId != null) checkSpeedChange(player);
        if (loadedVaultId != null && !loggedWeightsSnapshot
                && System.currentTimeMillis() - vaultIdResolvedMs >= WEIGHTS_SNAPSHOT_DELAY_MS) {
            loggedWeightsSnapshot = true;
            logWeightsSnapshot();
        }

        try {
            ChestScanner.tickCheck(level, player.blockPosition()); // tick-exact breaks near the player
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
            if (m.getTotal() <= 0) return; // skip empty/aborted runs
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

    /** The closing {@code vault_exit} record — whole-vault totals, written before the file is closed. */
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

    /** How the vault's chest breaks were caught — confirms the per-tick check is doing the work, not the 500 ms scan. */
    private static void logBreakSources() {
        LogUtils.getLogger().info("[Routerunner] chest breaks this vault: {} tick-exact (tickCheck), {} from the 500 ms scan.",
                ChestScanner.tickCheckBreaks(), ChestScanner.scanBreaks());
    }

    /**
     * A second weight snapshot, {@link #WEIGHTS_SNAPSHOT_DELAY_MS} after the vault id resolves. The
     * {@code vault_enter} snapshot is taken on the tick the id arrives, which is before the ability tree
     * (chain-miner tier) and MOVEMENT_SPEED have synced — so it records defaults, not what the solver runs with.
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
        LootListener.get().reset(); // loot rates are per lap too
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

    /**
     * Which transient modifiers are applied, ignoring their amounts — sprint and ParCool FastRun toggle
     * constantly and are already in the 20 Hz {@code pos} stream, so they are deliberately NOT part of the
     * signature; an effect landing or expiring is.
     */
    private static String modSignature(java.util.List<String> mods) {
        StringBuilder sb = new StringBuilder(64);
        for (String m : mods) {
            if (m.contains("vanilla.sprint") || m.contains("parcool.modifier.fast_run")) continue;
            sb.append(m).append('|');
        }
        return sb.toString();
    }

    /**
     * Two triggers. An EFFECT appearing or expiring (a vault modifier granting Tailwind, a Quickening proc,
     * corrupted Speed) is logged immediately and unthrottled — the adaptive system has to see it to explain
     * the trail it is measuring. A change in the PERSISTENT value (gear/prestige) over 1 % is logged at most
     * once every {@link #SPEED_MIN_INTERVAL_MS}.
     *
     * <p>Sprint and ParCool FastRun are excluded from the trigger, not from the record: they toggle several
     * times a second and change-detecting them fired ~350 speed events a vault, while {@code pos.sprint} and
     * {@code pos.spd} already carry that state at 20 Hz. Every record still logs the full live {@code attr}
     * and the complete transient breakdown.
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
