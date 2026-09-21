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
 * Guarded the_vault accessor for the player's ACTUAL Chain Miner tier, which is what the solver's
 * {@link com.routerunner.solver.ChainModel} must simulate: a tier-6 chain clears a very different
 * blob than a tier-1 one, so planning against fixed 6/32 mis-values every cluster off-tier.
 *
 * <p>Read chain (client-synced ability tree, all inside try/catch):
 * {@code ClientAbilityData.getTree()} → {@code getForId("Vein_Miner")} → {@link SpecializedSkill} →
 * {@code getSpecialization()} (must be id {@code Vein_Miner_Chain}, else a different Vein Miner spec
 * is selected) → {@link TieredSkill} → {@code getChild()} (the node at the ACTUAL tier, gear bonus
 * included). {@code getUnmodifiedBlockLimit()} and {@code getRange()} are then called REFLECTIVELY:
 * the range getter lives in the Wold's Vaults addon ({@code VeinMinerChainAbility implements
 * DuckGetRange}), which is not on our compile classpath.
 */
public final class ChainMinerInfo {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ABILITY_ID = "Vein_Miner";
    private static final String CHAIN_SPEC_ID = "Vein_Miner_Chain";
    /** Used when the tree can't be read — matches the pre-tier-awareness hardcoded model. */
    private static final int[] FALLBACK = {6, 32};

    private static volatile String lastReason = null;

    private ChainMinerInfo() {}

    /**
     * {range, limit} for the equipped Chain Miner tier, or {@code {6, 32}} if it can't be read.
     * Each distinct failure reason is logged once (this runs on every solve).
     */
    public static int[] rangeAndLimit() {
        try {
            Optional<Skill> found = ClientAbilityData.getTree().getForId(ABILITY_ID);
            if (found.isEmpty()) return fallback("ability tree has no '" + ABILITY_ID + "' skill (not learned?)");
            if (!(found.get() instanceof SpecializedSkill specialized)) {
                return fallback("'" + ABILITY_ID + "' is a " + found.get().getClass().getName() + ", not a SpecializedSkill");
            }
            LearnableSkill spec = specialized.getSpecialization();
            if (spec == null) return fallback("'" + ABILITY_ID + "' has no selected specialization");
            if (!CHAIN_SPEC_ID.equals(spec.getId())) {
                return fallback("selected Vein Miner specialization is '" + spec.getId() + "', not '" + CHAIN_SPEC_ID + "'");
            }
            if (!(spec instanceof TieredSkill tiered)) {
                return fallback("'" + CHAIN_SPEC_ID + "' is a " + spec.getClass().getName() + ", not a TieredSkill");
            }
            LearnableSkill node = tiered.getChild();
            if (node == null) return fallback("Chain Miner is at tier 0 (no child node)");

            Integer limit = callInt(node, "getUnmodifiedBlockLimit");
            Integer range = callInt(node, "getRange");
            if (limit == null || range == null) {
                return fallback("tier node " + node.getClass().getName() + " is missing getUnmodifiedBlockLimit()/getRange()");
            }
            lastReason = null;
            return new int[]{range, limit};
        } catch (Throwable t) {
            return fallback("ability tree read threw " + t);
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

    /** Log this reason once (it would otherwise fire on every solve) and hand back the hardcoded model. */
    private static int[] fallback(String reason) {
        if (!reason.equals(lastReason)) {
            lastReason = reason;
            LOGGER.error("[Routerunner] Chain Miner tier unreadable ({}); routing with the default {}-range / {}-limit chain model.",
                    reason, FALLBACK[0], FALLBACK[1]);
        }
        return FALLBACK.clone();
    }
}
