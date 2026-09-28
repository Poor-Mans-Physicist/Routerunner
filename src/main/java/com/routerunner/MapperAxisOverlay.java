package com.routerunner;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * Purple borders on Vault Mapper's overlay map around every room on the densest chunk-alignment lines: region x or z
 * a multiple of 16 (the start room's column and row, then every 16 map cells = every 8th room, since rooms sit on
 * even cells with a tunnel cell between them), where rooms get the most extra Bonus and cascade rounds
 * (research/2026-09-26_chestgen). Drawn right after Vault Mapper's own overlay pass with its geometry (anchor, room
 * width, player-centric window and cutoff, and the offsets from its config), so it follows every Vault Mapper setting.
 * Rooms on the next line over (region x or z = 2 mod 16, chunk alignment 14, the second-densest) get a lighter,
 * fainter purple border: the picker often runs the two-room band. Works with Vault Mapper 1.10 and 1.10.1. Vault Mapper is read by reflection: when it is missing or its internals do
 * not match, one error is logged and nothing is drawn. Config {@code mapperAxisLines}, default off.
 */
@Mod.EventBusSubscriber(modid = Routerunner.MOD_ID, value = Dist.CLIENT)
public final class MapperAxisOverlay {
    private static final Logger LOG = LogUtils.getLogger();
    /** Region period of the chunk alignment: 47 x 16 is a whole number of chunks. */
    private static final int PERIOD = 16;
    private static final int PURPLE = 0xB026FF;
    private static final int LIGHT_PURPLE = 0xE2B8FF;
    /** Region offset (mod {@link #PERIOD}) of the second-densest line, next to each densest one. */
    private static final int BAND = 2;

    private static boolean resolved = false;
    private static boolean broken = false;
    private static Field fEnabled, fPlayerCentric, fCutoff, fRoomWidth, fCenterX, fCenterZ, fPlayerX, fPlayerZ;
    private static Field fNorth, fEast, fSouth, fWest;
    private static ForgeConfigSpec.ConfigValue<?> mapEnabled, mapOffsetX, mapOffsetZ;

    private MapperAxisOverlay() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        RouterunnerConfig cfg = RouterunnerConfig.get();
        if (!cfg.mapperAxisLines || broken) return;
        if (!resolve()) return;
        try {
            if (!fEnabled.getBoolean(null) || !Boolean.TRUE.equals(mapEnabled.get())) return;
            float w = fRoomWidth.getFloat(null);
            if (!(w > 0)) return;
            boolean pc = fPlayerCentric.getBoolean(null);
            int cutoff = fCutoff.getInt(null);
            float cx = fCenterX.getFloat(null), cz = fCenterZ.getFloat(null);
            int ox = ((Number) mapOffsetX.get()).intValue(), oz = ((Number) mapOffsetZ.get()).intValue();
            int px = fPlayerX.getInt(null), pz = fPlayerZ.getInt(null);
            int x0, x1, z0, z1;
            if (pc) {
                x0 = px - cutoff;
                x1 = px + cutoff;
                z0 = pz - cutoff;
                z1 = pz + cutoff;
            } else {
                x0 = -fWest.getInt(null);
                x1 = fEast.getInt(null);
                z0 = -fNorth.getInt(null);
                z1 = fSouth.getInt(null);
            }
            int alpha = (int) Math.round(255 * Math.max(0.0, Math.min(1.0, cfg.masterOpacity)));
            if (alpha <= 0) return;
            int color = (alpha << 24) | PURPLE;
            int light = ((int) Math.round(alpha * 0.6) << 24) | LIGHT_PURPLE;
            float half = w / 2f;
            float line = Math.max(1f, 0.15f * w);
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            RenderSystem.enableBlend();
            RenderSystem.disableTexture();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int x = x0; x <= x1; x++) {
                if ((x & 1) != 0) continue;
                for (int z = z0; z <= z1; z++) {
                    if ((z & 1) != 0) continue;
                    int mx16 = Math.floorMod(x, PERIOD), mz16 = Math.floorMod(z, PERIOD);
                    boolean main = mx16 == 0 || mz16 == 0;
                    if (!main && mx16 != BAND && mz16 != BAND) continue;
                    float mx = pc ? cx + (x - px) * w + ox : cx + x * w + ox;
                    float mz = pc ? cz + (z - pz) * w + oz : cz + z * w + oz;
                    border(bb, main ? color : light, mx - half, mz - half, mx + half, mz + half, main ? line : Math.max(1f, 0.10f * w));
                }
            }
            bb.end();
            BufferUploader.end(bb);
            RenderSystem.enableTexture();
            RenderSystem.disableBlend();
        } catch (Throwable t) {
            broken = true;
            LOG.error("[Routerunner] Vault Mapper axis lines failed to draw; turned off until the game restarts.", t);
        }
    }

    /** Four quads framing the rectangle, {@code t} thick, inside its edge. */
    private static void border(BufferBuilder bb, int c, float x0, float z0, float x1, float z1, float t) {
        quad(bb, c, x0, z0, x1, z0 + t);
        quad(bb, c, x0, z1 - t, x1, z1);
        quad(bb, c, x0, z0 + t, x0 + t, z1 - t);
        quad(bb, c, x1 - t, z0 + t, x1, z1 - t);
    }

    private static void quad(BufferBuilder bb, int c, float x0, float z0, float x1, float z1) {
        bb.vertex(x0, z1, 0).color(c).endVertex();
        bb.vertex(x1, z1, 0).color(c).endVertex();
        bb.vertex(x1, z0, 0).color(c).endVertex();
        bb.vertex(x0, z0, 0).color(c).endVertex();
    }

    /** Resolve Vault Mapper's renderer, map and config once; false (logged once) when it is missing or unreadable. */
    private static boolean resolve() {
        if (resolved) return !broken;
        resolved = true;
        if (!ModList.get().isLoaded("vaultmapper")) {
            broken = true;
            LOG.error("[Routerunner] Vault Mapper axis lines are on, but Vault Mapper is not installed; nothing will be drawn.");
            return false;
        }
        try {
            Class<?> r = Class.forName("com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer");
            Class<?> m = Class.forName("com.nodiumhosting.vaultmapper.map.VaultMap");
            Class<?> c = Class.forName("com.nodiumhosting.vaultmapper.config.ClientConfig");
            fEnabled = field(r, "enabled");
            fPlayerCentric = field(r, "playerCentricRender");
            fCutoff = field(r, "cutoff");
            fRoomWidth = field(r, "mapRoomWidth");
            fCenterX = field(r, "centerX");
            fCenterZ = field(r, "centerZ");
            fPlayerX = field(r, "playerX");
            fPlayerZ = field(r, "playerZ");
            fNorth = field(m, "northSize");
            fEast = field(m, "eastSize");
            fSouth = field(m, "southSize");
            fWest = field(m, "westSize");
            mapEnabled = (ForgeConfigSpec.ConfigValue<?>) field(c, "MAP_ENABLED").get(null);
            mapOffsetX = (ForgeConfigSpec.ConfigValue<?>) field(c, "MAP_X_OFFSET").get(null);
            mapOffsetZ = (ForgeConfigSpec.ConfigValue<?>) field(c, "MAP_Y_OFFSET").get(null);
            LOG.info("[Routerunner] Vault Mapper found; axis lines will be drawn on its overlay map.");
            return true;
        } catch (Throwable t) {
            broken = true;
            LOG.error("[Routerunner] Vault Mapper is installed but its map internals could not be read (version changed?); axis lines stay off.", t);
            return false;
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
