package com.routerunner;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelLastEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Optional suppression of the_vault's Hunter chest outlines (the yellow boxes), which clutter the route
 * overlay. We do NOT touch the ability or its data (the minimap chest markers stay) — we just clear the
 * client-side {@code HunterOutlineRenderer.POSITIONS} map it draws from, at HIGHEST render priority so
 * this runs before Hunter's own (normal-priority) render that frame; its render early-returns on an empty
 * map. Reflection + guarded, so it's a silent no-op if the_vault is absent or its internals change.
 *
 * <p>Toggled by {@link RouterunnerConfig#suppressHunter}. Turning it back off lets the boxes reappear on
 * Hunter's next scan (a second or two).
 */
@Mod.EventBusSubscriber(modid = Routerunner.MOD_ID, value = Dist.CLIENT)
public final class HunterSuppressor {
    private static final Logger LOG = LogUtils.getLogger();
    private static boolean resolved = false;      // have we tried to locate the field yet?
    private static Map<?, ?> positions = null;    // cached reference to HunterOutlineRenderer.POSITIONS (final, stable)

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLevel(RenderLevelLastEvent event) {
        if (!RouterunnerConfig.get().suppressHunter) return;
        Map<?, ?> map = positions();
        if (map != null && !map.isEmpty()) map.clear();
    }

    /** Lazily resolve (once) the Hunter outline position map via reflection; null if unavailable. */
    private static Map<?, ?> positions() {
        if (!resolved) {
            resolved = true;
            if (!ModList.get().isLoaded("the_vault")) return null;
            try {
                Class<?> cls = Class.forName("iskallia.vault.client.render.HunterOutlineRenderer");
                Field f = cls.getDeclaredField("POSITIONS");
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof Map) {
                    positions = (Map<?, ?>) v;
                } else {
                    LOG.error("[Routerunner] Hunter suppression unavailable: HunterOutlineRenderer.POSITIONS is not a Map; yellow boxes can't be hidden.");
                }
            } catch (Throwable t) {
                LOG.error("[Routerunner] Hunter suppression unavailable (couldn't access HunterOutlineRenderer.POSITIONS); yellow boxes can't be hidden.", t);
            }
        }
        return positions;
    }

    private HunterSuppressor() {}
}
