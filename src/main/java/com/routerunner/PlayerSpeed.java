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
 * Player movement-speed readouts for the cost model and for the adaptive weights.
 *
 * <p>The number that matters is {@link #attribute}: the player's ACTUAL current MOVEMENT_SPEED, every
 * transient included. Vault modifiers grant speed effects (Tailwind, Quickening, corrupted Speed) mid-run,
 * and the adaptive system has to see them to explain the trail it is measuring, so nothing is filtered out
 * of the live value. {@link #persistentAttribute} is the same attribute with the transients removed — kept
 * only so the log can tell "you swapped gear" apart from "a potion landed", never to hide a modifier.
 *
 * <p>Two things are NOT in the attribute at all and are read separately: {@link #entitySpeed} and
 * {@link #flyingSpeed}, the raw {@code LivingEntity} fields. The Zephyr charm
 * ({@code woldsvaults:zephyr_charm}, {@code AirMobilityItem}) writes those directly in {@code curioTick}
 * ({@code setSpeed(0.2F)}, {@code flyingSpeed = speed * 0.5F}) and never touches the attribute system, so
 * an attribute read cannot see it. {@link #realizedHorizontal} is what the player is actually doing.
 */
public final class PlayerSpeed {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Vanilla walk speed; what a Player reports with no modifiers at all. */
    public static final double DEFAULT_ATTRIBUTE = 0.1;

    /** Mob-effect modifiers (Speed, Slowness, Tailwind, Quickening, Pyretic, ...) are named {@code effect.<id>}. */
    private static final String EFFECT_MODIFIER_PREFIX = "effect.";

    /** {@code LivingEntity.SPEED_MODIFIER_SPRINTING_UUID} — "Sprinting speed boost", MULTIPLY_TOTAL +0.30. */
    private static final UUID VANILLA_SPRINT_UUID = UUID.fromString("662a6b8d-da3e-4c1c-8813-96ea6097278d");

    /**
     * ParCool's FastRun modifier. Its UUID is {@code UUID.randomUUID()} at class-init, so it can only be
     * matched by name. ADDITION of {@code fast-run_modifier / 100} (pack config 2.0 → +0.02, i.e. x1.20 of
     * the total), added server-side while FastRun is active — and FastRun forces {@code setSprinting(true)},
     * so it is sprint, just faster.
     */
    private static final String PARCOOL_FAST_RUN = "parcool.modifier.fast_run";

    private static volatile boolean loggedAttributeFailure = false;
    private static volatile boolean loggedPersistentFailure = false;
    private static volatile boolean loggedBreakdownFailure = false;
    private static volatile boolean loggedEntitySpeedFailure = false;

    private PlayerSpeed() {}

    /**
     * The player's actual current MOVEMENT_SPEED — gear, prestige, sprint, ParCool FastRun and every active
     * mob effect included. This is what {@code Params.speedAttr} carries and what the adaptive system reads.
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
     * The same attribute with vanilla's full formula — {@code (base + sum ADDITION) * (1 + sum MULTIPLY_BASE)
     * * prod (1 + MULTIPLY_TOTAL)} — over the modifiers that are NOT tied to transient player state: the
     * vanilla sprint modifier, ParCool's FastRun modifier and every {@code effect.*} modifier are left out.
     * Gear and the prestige speed power (Swiftness Amplified, MULTIPLY_TOTAL, x1.25 in this pack) stay in.
     *
     * <p>Diagnostic only. It exists so the log can attribute a change to gear rather than to a potion; the
     * exclusions are by identity, never by operation — filtering on {@code MULTIPLY_TOTAL} both misses
     * ParCool (which is ADDITION) and deletes the permanent prestige multiplier.
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
     * Every transient modifier on MOVEMENT_SPEED right now, each rendered as a JSON object
     * {@code {"n":name,"op":0|1|2,"a":amount}} — the mob effects (so a mid-vault Tailwind, Quickening or
     * corrupted Speed is in the log the moment it lands), the vanilla sprint modifier and ParCool FastRun.
     * Sorted, so the list doubles as a change signature. Empty when none apply.
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

    /**
     * {@code LivingEntity.getSpeed()} — the raw movement field, normally re-derived from the attribute each
     * tick but overwritten outright by the Zephyr charm (0.2). Logged because it is the only attribute-free
     * way to see that charm.
     */
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
