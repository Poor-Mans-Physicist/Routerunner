package com.routerunner;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Detects a position DISCONTINUITY on the local player: the position moved between two consecutive client
 * ticks by far more than that tick's own velocity explains. That is what a Dash Warp landing, a vault portal
 * or a server-side correction looks like from the client, and none of them is travel — a 12-block warp in
 * one tick would otherwise read as 240 blk/s and land in the top bucket of every speed histogram.
 *
 * <p>Runs once per client tick BEFORE the {@code pos} record and the trail capture, so both can carry the
 * flag; raises a {@code teleport} record and tells {@link RouteService#noteTeleport()} so the reach leg and
 * the adaptive accumulators exclude it. A dash or a riptide never qualifies: their displacement matches
 * their velocity.
 */
public final class TeleportDetector {
    private static final Logger LOG = LogUtils.getLogger();
    /** Smallest one-tick displacement that can be a teleport (blocks). Riptide peaks near 3.7 blk/tick but carries a matching velocity. */
    static final double MIN_JUMP = 3.0;
    /** The displacement must exceed this multiple of the tick's own velocity (blocks per tick). */
    static final double VEL_FACTOR = 3.0;
    /** A gap longer than this since the last update (disabled, paused, dimension change) re-arms instead of comparing. */
    private static final long STALE_MS = 500;

    private static double lastX, lastY, lastZ;
    private static long lastUpdateMs = 0;
    private static boolean have = false;
    private static boolean tickTeleport = false;
    private static int count = 0;

    private TeleportDetector() {}

    /** Compare this tick's position against the last one; call once per client tick, before anything reads {@link #teleportedThisTick()}. */
    public static void update(Player p) {
        tickTeleport = false;
        if (p == null) return;
        long now = System.currentTimeMillis();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        try {
            if (have && now - lastUpdateMs <= STALE_MS) {
                double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
                double jump = Math.sqrt(dx * dx + dy * dy + dz * dz);
                Vec3 v = p.getDeltaMovement();
                double vel = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
                if (jump >= MIN_JUMP && jump >= VEL_FACTOR * Math.max(vel, 0.05)) {
                    tickTeleport = true;
                    count++;
                    RunLog.teleport(lastX, lastY, lastZ, x, y, z, jump, vel);
                    RouteService.noteTeleport();
                }
            }
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] teleport detection failed this tick; the sample is treated as ordinary movement.", e);
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        lastUpdateMs = now;
        have = true;
    }

    /** True on the tick the player's position jumped (valid after {@link #update} until the next one). */
    public static boolean teleportedThisTick() {
        return tickTeleport;
    }

    /** Teleports seen since the last {@link #reset()}. */
    public static int count() {
        return count;
    }

    /** Forget the last position (vault exit / trackers reset) so the next update can't compare across worlds. */
    public static void reset() {
        have = false;
        tickTeleport = false;
        count = 0;
        lastUpdateMs = 0;
    }
}
