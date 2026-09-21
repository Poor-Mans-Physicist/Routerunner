package com.routerunner;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame look sampler for the run log: buffers yaw/pitch on each render tick and hands one batched
 * {@code look} line per client tick to {@link RunLog}. A frame is buffered only if the view moved more than
 * {@link #ANGLE_EPS} degrees on either axis and at least {@link #MIN_GAP_MS} passed since the last sample.
 * Render-thread only; the buffer is unsynchronized.
 */
@Mod.EventBusSubscriber(modid = Routerunner.MOD_ID, value = Dist.CLIENT)
public final class LookSampler {
    private static final Logger LOG = LogUtils.getLogger();
    /** Minimum movement on either axis (degrees) for a frame to be worth a sample. */
    private static final double ANGLE_EPS = 0.5;
    /** Minimum wall-clock gap between two buffered samples (ms); caps the stream at ~62 Hz. */
    private static final long MIN_GAP_MS = 16L;
    /** Hard cap per client tick; a frame rate this far above the tick rate means something is wrong. */
    private static final int MAX_SAMPLES = 400;

    /** Pending samples, each {offsetMs from baseTs, yaw, pitch}. */
    private static final List<double[]> buffer = new ArrayList<>(64);
    private static long baseTs = 0L;
    private static float lastYaw = 0f;
    private static float lastPitch = 0f;
    private static long lastSampleMs = 0L;
    private static boolean sampled = false;
    private static boolean overflowLogged = false;

    private LookSampler() {}

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || !ClientEvents.isInVault(mc.level) || !RouterunnerConfig.get().enabled) return;
        try {
            float yaw = player.getYRot();
            float pitch = player.getXRot();
            long now = System.currentTimeMillis();
            if (sampled) {
                if (now - lastSampleMs < MIN_GAP_MS) return;
                if (Math.abs(yaw - lastYaw) <= ANGLE_EPS && Math.abs(pitch - lastPitch) <= ANGLE_EPS) return;
            }
            if (buffer.size() >= MAX_SAMPLES) {
                if (!overflowLogged) {
                    overflowLogged = true;
                    LOG.error("[Routerunner] look buffer hit {} samples in one client tick; dropping the rest of this tick's frames.",
                            MAX_SAMPLES);
                }
                return;
            }
            if (buffer.isEmpty()) baseTs = now;
            buffer.add(new double[]{now - baseTs, yaw, pitch});
            lastYaw = yaw;
            lastPitch = pitch;
            lastSampleMs = now;
            sampled = true;
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] look sampling failed this frame; the look stream has a gap.", e);
        }
    }

    /** Hand this tick's buffered samples to {@link RunLog#look} as one line and clear the buffer. */
    public static void drainTo() {
        if (buffer.isEmpty()) return;
        try {
            RunLog.look(baseTs, buffer);
        } finally {
            buffer.clear();
        }
    }

    /** Vault entry/exit: drop anything pending and forget the last sample. */
    public static void reset() {
        buffer.clear();
        baseTs = 0L;
        lastYaw = 0f;
        lastPitch = 0f;
        lastSampleMs = 0L;
        sampled = false;
        overflowLogged = false;
    }
}
