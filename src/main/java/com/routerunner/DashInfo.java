package com.routerunner;

import com.mojang.logging.LogUtils;
import iskallia.vault.client.data.ClientAbilityData;
import iskallia.vault.skill.base.LearnableSkill;
import iskallia.vault.skill.base.Skill;
import iskallia.vault.skill.base.SpecializedSkill;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Guarded the_vault accessor for the player's selected Dash specialization ({@code Dash_Base},
 * {@code Dash_Damage} or {@code Dash_Warp}), logged so {@code teleport} records can be read against
 * whether Warp was equipped.
 */
public final class DashInfo {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ABILITY_ID = "Dash";

    private static volatile String lastReason = null;

    private DashInfo() {}

    /** The selected Dash specialization id, or {@code null} when it can't be read (each reason logged once). */
    public static String specId() {
        try {
            Optional<Skill> found = ClientAbilityData.getTree().getForId(ABILITY_ID);
            if (found.isEmpty()) return fallback("ability tree has no '" + ABILITY_ID + "' skill (not learned?)");
            if (!(found.get() instanceof SpecializedSkill specialized)) {
                return fallback("'" + ABILITY_ID + "' is a " + found.get().getClass().getName() + ", not a SpecializedSkill");
            }
            LearnableSkill spec = specialized.getSpecialization();
            if (spec == null) return fallback("'" + ABILITY_ID + "' has no selected specialization");
            lastReason = null;
            return spec.getId();
        } catch (Throwable t) {
            return fallback("ability tree read threw " + t);
        }
    }

    private static String fallback(String reason) {
        if (!reason.equals(lastReason)) {
            lastReason = reason;
            LOGGER.error("[Routerunner] Dash specialization unreadable ({}); logging dashSpec as null.", reason);
        }
        return null;
    }
}
