package com.routerunner;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeMod;
import org.slf4j.Logger;

/**
 * The player's block reach, for the lane planner's break reach and the run log.
 *
 * <p>Two readings bound it. {@code Player.getReachDistance()} is Forge's survival reach (the REACH_DISTANCE
 * attribute minus 0.5). {@code getAttributeValue(REACH_DISTANCE)} is what the_vault caps at 7.0 inside a vault (its
 * {@code MixinLivingEntity}). The game reach is the smaller of the two. Run 1 of the vein test (vault
 * 2026-09-23 21:51, reach gear at the vault cap) measured hits out to 7.1 blocks from the eye (p99), and 6.4 at p95
 * in the planner's own feet-cell to chest-cell metric.
 *
 * <p>The planner plans at the reach the player actually uses (the learned quantile of their hits, see
 * {@code Adaptive.learnedReach}), capped at the game reach minus {@link #PLAN_MARGIN} and at {@link #PLAN_CAP}. Greed
 * prestige tiers of Spirit's Hand can lift the attribute past the current release's 7.0 (vein run 2 read 7.5), and
 * planning at 7.0 then asked for hits the player rarely took.
 */
public final class PlayerReach {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The planner's reach before this was read from the player, and the fallback when it can't be. */
    public static final double FALLBACK = 4.5;
    /** Planned reach = game reach minus this. */
    public static final double PLAN_MARGIN = 0.5;
    /** Never plan tighter than this, whatever the readings say. */
    public static final double PLAN_MIN = 3.0;
    /** Never plan wider than this: the current release's vault reach. */
    public static final double PLAN_CAP = 7.0;

    private static volatile String lastFailure = null;

    private PlayerReach() {}

    /**
     * {game reach, Forge getReachDistance(), vault-capped attribute value}. When the player or the attribute can't
     * be read, every entry is {@link #FALLBACK} + {@link #PLAN_MARGIN}, so {@link #plan} keeps the old 4.5-block
     * break reach (logged once per distinct reason).
     */
    public static double[] read(Player player) {
        try {
            if (player == null) return fail("no player");
            double forge = player.getReachDistance();
            double capped = player.getAttributeValue(ForgeMod.REACH_DISTANCE.get());
            if (!(forge > 0) || !(capped > 0)) return fail("non-positive reach (forge " + forge + ", attribute " + capped + ")");
            lastFailure = null;
            return new double[]{Math.min(forge, capped), forge, capped};
        } catch (Throwable t) {
            return fail("reach read threw " + t);
        }
    }

    /** The planner's break reach: the reach the player uses, capped by the game reach minus the margin and by {@link #PLAN_CAP}. */
    public static double plan(double gameReach, double usedReach) {
        double cap = Math.min(PLAN_CAP, gameReach - PLAN_MARGIN);
        return Math.max(PLAN_MIN, Math.min(usedReach, cap));
    }

    private static double[] fail(String reason) {
        if (!reason.equals(lastFailure)) {
            lastFailure = reason;
            LOGGER.error("[Routerunner] player reach unreadable ({}); planning with the default {}-block break reach.", reason, FALLBACK);
        }
        return new double[]{FALLBACK + PLAN_MARGIN, FALLBACK + PLAN_MARGIN, FALLBACK + PLAN_MARGIN};
    }
}
