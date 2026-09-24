package com.routerunner;

import com.mojang.logging.LogUtils;
import iskallia.vault.client.data.ClientAbilityData;
import iskallia.vault.skill.base.LearnableSkill;
import iskallia.vault.skill.base.Skill;
import iskallia.vault.skill.base.SpecializedSkill;
import iskallia.vault.skill.base.TieredSkill;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Guarded the_vault accessor for the player's mining ability, which {@link com.routerunner.solver.ChainModel}
 * simulates. Reads the client-synced ability tree: {@code Vein_Miner} → its selected specialization → that
 * specialization's tier child node, whose {@code getUnmodifiedBlockLimit()}/{@code getRange()} are called
 * reflectively because the range getter lives in the Wold's Vaults addon, which is not on the compile classpath.
 * <p>
 * Wold's Vaults routes every Vein Miner specialization through the same break handler: {@code Vein_Miner_Chain}
 * jumps up to its tier's range, every other specialization ({@code Base}, {@code Fortune}, {@code Durability},
 * {@code Void}) has range 1, i.e. it only follows touching chests (faces, edges and corners). Range 1 is what puts
 * the planner in vein mode.
 */
public final class ChainMinerInfo {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ABILITY_ID = "Vein_Miner";
    private static final String CHAIN_SPEC_ID = "Vein_Miner_Chain";
    /** Chain Miner defaults used when the tree can't be read. */
    private static final int FALLBACK_RANGE = 6, FALLBACK_LIMIT = 32;

    /**
     * What the planner routes with.
     *
     * @param mode  {@code "chain"} or {@code "vein"} (range 1); {@code "default"} when the tree was unreadable
     * @param spec  the selected specialization id, or null when unreadable
     * @param tier  the specialization's actual tier (learned + bonus), -1 when unknown
     * @param range Chebyshev step of one break (1 = touching only)
     * @param limit chests one break can take
     */
    public record Miner(String mode, String spec, int tier, int range, int limit) {
        public boolean vein() {
            return "vein".equals(mode);
        }

        /** Compact form for logs and the HUD, e.g. {@code vein 30 (896)} or {@code chain 29 (6/32)}. */
        public String label() {
            return vein() ? mode + " " + tier + " (" + limit + ")" : mode + " " + tier + " (" + range + "/" + limit + ")";
        }
    }

    private static volatile String lastReason = null;
    private static volatile String lastRangeReason = null;

    private ChainMinerInfo() {}

    /** {range, limit} of {@link #current()}. */
    public static int[] rangeAndLimit() {
        Miner m = current();
        return new int[]{m.range(), m.limit()};
    }

    /**
     * The equipped mining ability. Falls back to the default Chain Miner model ({@code 6}/{@code 32}) when the tree
     * can't be read; each distinct failure reason is logged once (this runs on every solve).
     */
    public static Miner current() {
        try {
            Optional<Skill> found = ClientAbilityData.getTree().getForId(ABILITY_ID);
            if (found.isEmpty()) return fallback(null, "ability tree has no '" + ABILITY_ID + "' skill (not learned?)");
            if (!(found.get() instanceof SpecializedSkill specialized)) {
                return fallback(null, "'" + ABILITY_ID + "' is a " + found.get().getClass().getName() + ", not a SpecializedSkill");
            }
            LearnableSkill spec = specialized.getSpecialization();
            if (spec == null) return fallback(null, "'" + ABILITY_ID + "' has no selected specialization");
            String specId = spec.getId();
            if (!(spec instanceof TieredSkill tiered)) {
                return fallback(specId, "'" + specId + "' is a " + spec.getClass().getName() + ", not a TieredSkill");
            }
            LearnableSkill node = tiered.getChild();
            if (node == null) return fallback(specId, "'" + specId + "' is at tier 0 (no child node)");
            Integer tierBoxed = callInt(tiered, "getActualTier");
            int tier = tierBoxed == null ? -1 : tierBoxed;

            Integer limit = callInt(node, "getUnmodifiedBlockLimit");
            if (limit == null) {
                return fallback(specId, "tier node " + node.getClass().getName() + " is missing getUnmodifiedBlockLimit()");
            }
            boolean chain = CHAIN_SPEC_ID.equals(specId);
            Integer range = callInt(node, "getRange");
            if (range == null) {
                if (chain) return fallback(specId, "Chain Miner tier node " + node.getClass().getName() + " is missing getRange()");
                range = 1;
                String reason = specId + " tier node " + node.getClass().getName() + " has no getRange()";
                if (!reason.equals(lastRangeReason)) {
                    lastRangeReason = reason;
                    LOGGER.error("[Routerunner] {}; assuming range 1 (touching chests only), which is what every non-chain Vein Miner uses.", reason);
                }
            }
            lastReason = null;
            return new Miner(range <= 1 ? "vein" : "chain", specId, tier, range, limit);
        } catch (Throwable t) {
            return fallback(null, "ability tree read threw " + t);
        }
    }

    /** Invoke a public no-arg int getter by name; null if it isn't there or throws. */
    private static Integer callInt(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            return v instanceof Integer i ? i : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Log this reason once and return the default Chain Miner model. */
    private static Miner fallback(String spec, String reason) {
        if (!reason.equals(lastReason)) {
            lastReason = reason;
            LOGGER.error("[Routerunner] mining ability unreadable ({}); routing with the default {}-range / {}-limit chain model.",
                    reason, FALLBACK_RANGE, FALLBACK_LIMIT);
        }
        return new Miner("default", spec, -1, FALLBACK_RANGE, FALLBACK_LIMIT);
    }
}
