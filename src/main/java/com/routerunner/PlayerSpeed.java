package com.routerunner;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Player movement-speed readouts for the cost model, the adaptive weights and the run log.
 *
 * <p>{@link #attribute} is the live MOVEMENT_SPEED with every transient included; {@link #persistentAttribute}
 * removes the transients. {@link #entitySpeed} and {@link #flyingSpeed} read the raw {@code LivingEntity}
 * fields, which the Zephyr charm writes directly outside the attribute system.
 */
public final class PlayerSpeed {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Vanilla walk speed; what a Player reports with no modifiers at all. */
    public static final double DEFAULT_ATTRIBUTE = 0.1;

    /** Mob-effect modifiers (Speed, Slowness, Tailwind, Quickening, Pyretic, ...) are named {@code effect.<id>}. */
    private static final String EFFECT_MODIFIER_PREFIX = "effect.";

    /** {@code LivingEntity.SPEED_MODIFIER_SPRINTING_UUID} — "Sprinting speed boost", MULTIPLY_TOTAL +0.30. */
    private static final UUID VANILLA_SPRINT_UUID = UUID.fromString("662a6b8d-da3e-4c1c-8813-96ea6097278d");

    /** ParCool's FastRun modifier name (an ADDITION modifier); its UUID is random, so it is matched by name. */
    private static final String PARCOOL_FAST_RUN = "parcool.modifier.fast_run";

    private static volatile boolean loggedAttributeFailure = false;
    private static volatile boolean loggedPersistentFailure = false;
    private static volatile boolean loggedBreakdownFailure = false;
    private static volatile boolean loggedEntitySpeedFailure = false;

    private PlayerSpeed() {}

    /**
     * The player's live MOVEMENT_SPEED, every modifier included ({@code Params.speedAttr}).
     *
     * @return the live attribute, or {@link #DEFAULT_ATTRIBUTE} (logged once) if it can't be read
     */
    public static double attribute(Player p) {
        try {
            if (p == null) return fallback("player is null");
            return p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        } catch (Throwable t) {
            return fallback("attribute read threw " + t);
        }
    }

    /**
     * MOVEMENT_SPEED recomputed with vanilla's formula over the non-transient modifiers only (sprint, ParCool
     * FastRun and {@code effect.*} excluded by identity, not by operation). Diagnostic only.
     *
     * @return the transient-free attribute, or the live attribute (logged once) if it can't be recomputed
     */
    public static double persistentAttribute(Player p) {
        try {
            if (p == null) return persistentFallback(attribute(null), "player is null");
            AttributeInstance inst = p.getAttribute(Attributes.MOVEMENT_SPEED);
            if (inst == null) return persistentFallback(attribute(p), "no MOVEMENT_SPEED instance on the player");
            double add = 0.0, mulBase = 0.0, mulTotal = 1.0;
            for (AttributeModifier m : inst.getModifiers()) {
                if (isTransient(m)) continue;
                AttributeModifier.Operation op = m.getOperation();
                if (op == AttributeModifier.Operation.ADDITION) add += m.getAmount();
                else if (op == AttributeModifier.Operation.MULTIPLY_BASE) mulBase += m.getAmount();
                else if (op == AttributeModifier.Operation.MULTIPLY_TOTAL) mulTotal *= 1.0 + m.getAmount();
            }
            return (inst.getBaseValue() + add) * (1.0 + mulBase) * mulTotal;
        } catch (Throwable t) {
            return persistentFallback(attribute(p), "persistent attribute computation threw " + t);
        }
    }

    /**
     * Every transient MOVEMENT_SPEED modifier (mob effects, vanilla sprint, ParCool FastRun) as a sorted list
     * of JSON objects {@code {"n":name,"op":0|1|2,"a":amount}}. Empty when none apply.
     */
    public static List<String> transientModifiers(Player p) {
        try {
            if (p == null) return Collections.emptyList();
            AttributeInstance inst = p.getAttribute(Attributes.MOVEMENT_SPEED);
            if (inst == null) return Collections.emptyList();
            List<String> out = new ArrayList<>();
            for (AttributeModifier m : inst.getModifiers()) {
                if (!isTransient(m)) continue;
                String name = VANILLA_SPRINT_UUID.equals(m.getId()) ? "vanilla.sprint" : m.getName();
                out.add(String.format(Locale.ROOT, "{\"n\":\"%s\",\"op\":%d,\"a\":%.4f}",
                        name, m.getOperation().toValue(), m.getAmount()));
            }
            Collections.sort(out);
            return out;
        } catch (Throwable t) {
            if (!loggedBreakdownFailure) {
                loggedBreakdownFailure = true;
                LOGGER.error("[Routerunner] transient MOVEMENT_SPEED modifier list unreadable; logging an empty list.", t);
            }
            return Collections.emptyList();
        }
    }

    /** ParCool FastRun's amount right now, or 0 when it is not applied. */
    public static double fastRunAmount(Player p) {
        try {
            if (p == null) return 0.0;
            AttributeInstance inst = p.getAttribute(Attributes.MOVEMENT_SPEED);
            if (inst == null) return 0.0;
            for (AttributeModifier m : inst.getModifiers()) {
                if (PARCOOL_FAST_RUN.equals(m.getName())) return m.getAmount();
            }
            return 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** {@code LivingEntity.getSpeed()}, the raw movement field (overwritten by the Zephyr charm). */
    public static double entitySpeed(Player p) {
        try {
            return p == null ? 0.0 : p.getSpeed();
        } catch (Throwable t) {
            return entitySpeedFallback(t);
        }
    }

    /** {@code LivingEntity.flyingSpeed} — air-movement speed; the Zephyr charm writes this one too. */
    public static double flyingSpeed(Player p) {
        try {
            return p == null ? 0.0 : p.flyingSpeed;
        } catch (Throwable t) {
            return entitySpeedFallback(t);
        }
    }

    /** True for a modifier that only exists while the player is in some transient state. */
    private static boolean isTransient(AttributeModifier m) {
        if (VANILLA_SPRINT_UUID.equals(m.getId())) return true;
        String name = m.getName();
        return name != null && (name.startsWith(EFFECT_MODIFIER_PREFIX) || name.equals(PARCOOL_FAST_RUN));
    }

    /** Horizontal speed the player is actually moving at, in blocks per second. */
    public static double realizedHorizontal(Player p) {
        try {
            if (p == null) return 0.0;
            var v = p.getDeltaMovement();
            return Math.hypot(v.x, v.z) * 20.0;
        } catch (Throwable t) {
            LOGGER.error("[Routerunner] realized speed read failed; reporting 0.", t);
            return 0.0;
        }
    }

    private static double entitySpeedFallback(Throwable t) {
        if (!loggedEntitySpeedFailure) {
            loggedEntitySpeedFailure = true;
            LOGGER.error("[Routerunner] LivingEntity speed fields unreadable; reporting 0 (the Zephyr charm will be invisible).", t);
        }
        return 0.0;
    }

    private static double persistentFallback(double live, String reason) {
        if (!loggedPersistentFailure) {
            loggedPersistentFailure = true;
            LOGGER.error("[Routerunner] persistent MOVEMENT_SPEED unreadable ({}); falling back to the live attribute {} (sprint/effects included).",
                    reason, live);
        }
        return live;
    }

    private static double fallback(String reason) {
        if (!loggedAttributeFailure) {
            loggedAttributeFailure = true;
            LOGGER.error("[Routerunner] MOVEMENT_SPEED unreadable ({}); reporting the vanilla default {}.",
                    reason, DEFAULT_ATTRIBUTE);
        }
        return DEFAULT_ATTRIBUTE;
    }
}
