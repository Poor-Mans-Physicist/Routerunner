package com.routerunner;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.routerunner.solver.P;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.ForgeIngameGui;
import net.minecraftforge.client.gui.IIngameOverlay;

/** In-game HUD overlay: the four chest-rate readouts, the movable loot panel and the off-screen target arrow. */
public class RouterunnerHud implements IIngameOverlay {
    public static final RouterunnerHud INSTANCE = new RouterunnerHud();
    /** Length (px) of the off-screen target arrow. */
    private static final float ARROW_PX = 12.0f;
    /** Distance (px) the arrow keeps from the screen edge. */
    private static final double EDGE_MARGIN = 10.0;

    @Override
    public void render(ForgeIngameGui gui, PoseStack poseStack, float partialTicks, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        RouterunnerConfig cfg = RouterunnerConfig.get();
        if (!cfg.enabled) return;
        if (mc.options.hideGui || mc.player == null) return;
        if (mc.screen != null) return;
        if (!ClientEvents.isInVault(mc.level)) return;

        Font font = mc.font;
        MetricsTracker m = MetricsTracker.get();
        for (RouterunnerConfig.HudElementId id : RouterunnerConfig.HudElementId.values()) {
            RouterunnerConfig.ElementConfig ec = cfg.element(id);
            if (!ec.visible) continue;
            font.drawShadow(poseStack, textFor(id, m), ec.x, ec.y, 0xFFFFFF);
        }

        if (cfg.lootPanelVisible && cfg.trackedChest != RouterunnerConfig.TrackedChest.ALL) {
            renderLootPanel(poseStack, mc, font, cfg.lootPanelX, cfg.lootPanelY);
        }

        if (cfg.routingEnabled) {
            font.drawShadow(poseStack, routeStatusLine(), 5, height - 12, 0x9AA0FF);
            if (cfg.offscreenIndicator) renderOffscreenIndicator(poseStack, mc, font, width, height);
        }
    }

    /** The bottom-left routing readout: follow state and the last room's route accuracy. */
    private static String routeStatusLine() {
        StringBuilder sb = new StringBuilder(96).append("RR: ").append(RouteService.debugState());
        int acc = RouteService.lastAccuracyPct();
        if (acc >= 0) sb.append(" | Acc ").append(acc).append('%');
        return sb.toString();
    }

    /**
     * Screen-edge arrow and distance for the current route target while it is outside the view, computed from
     * the camera so it holds in third person.
     */
    private static void renderOffscreenIndicator(PoseStack ps, Minecraft mc, Font font, int width, int height) {
        RouteService.SolvedRoute sr = RouteService.current();
        if (sr == null) return;
        BlockPos w;
        com.routerunner.lane.LaneRoute lr = sr.lane;
        if (lr != null) {
            w = lr.indicatorTarget();
            if (w == null) return;
        } else {
            P target = sr.retargetPos;
            if (target == null) {
                if (sr.cursor >= sr.plan.waypoints.size()) return;
                target = sr.plan.waypoints.get(sr.cursor).pos;
            }
            w = sr.worldOf(target);
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 c = camera.getPosition();
        double dx = w.getX() + 0.5 - c.x, dy = w.getY() + 0.5 - c.y, dz = w.getZ() + 0.5 - c.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001) return;

        // Yaw right-positive, pitch up-positive (camera xRot is down-positive).
        double relYaw = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - camera.getYRot());
        double relPitch = Math.toDegrees(Math.atan2(dy, horiz)) + camera.getXRot();
        double vFov = mc.options.fov;
        double aspect = height > 0 ? (double) width / (double) height : 1.0;
        double hFov = Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(vFov) / 2.0) * aspect));
        if (Math.abs(relYaw) < hFov / 2.0 && Math.abs(relPitch) < vFov / 2.0) return;

        double ux = relYaw, uy = -relPitch;
        double len = Math.hypot(ux, uy);
        if (len < 1.0e-6) return;
        ux /= len;
        uy /= len;
        double halfW = width / 2.0 - EDGE_MARGIN, halfH = height / 2.0 - EDGE_MARGIN;
        double t = Double.MAX_VALUE;
        if (Math.abs(ux) > 1.0e-6) t = Math.min(t, halfW / Math.abs(ux));
        if (Math.abs(uy) > 1.0e-6) t = Math.min(t, halfH / Math.abs(uy));
        if (t <= 0 || t == Double.MAX_VALUE) return;
        double ax = width / 2.0 + ux * t, ay = height / 2.0 + uy * t;

        drawArrow(ps, ax, ay, ux, uy, ARROW_PX, RouteRenderer.NEXT_LABEL_COLOR);
        String txt = ((int) Math.round(dist)) + "m";
        int tw = font.width(txt);
        float tx = (float) clamp(ax - ux * (ARROW_PX + 4.0) - tw / 2.0, 2.0, width - tw - 2.0);
        float ty = (float) clamp(ay - uy * (ARROW_PX + 4.0) - 4.0, 2.0, height - 10.0);
        font.drawShadow(ps, txt, tx, ty, RouteRenderer.NEXT_LABEL_COLOR);
    }

    /** A filled triangle centred on {@code (x,y)} pointing along the unit vector {@code (ux,uy)} (a degenerate quad). */
    private static void drawArrow(PoseStack ps, double x, double y, double ux, double uy, float size, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        double px = -uy, py = ux;
        float tipX = (float) (x + ux * size * 0.5), tipY = (float) (y + uy * size * 0.5);
        double bx = x - ux * size * 0.5, by = y - uy * size * 0.5;
        float leftX = (float) (bx + px * size * 0.45), leftY = (float) (by + py * size * 0.45);
        float rightX = (float) (bx - px * size * 0.45), rightY = (float) (by - py * size * 0.45);
        Matrix4f mat = ps.last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableTexture();
        RenderSystem.disableCull(); // the winding flips with the arrow's direction
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(mat, tipX, tipY, 0.0f).color(r, g, b, a).endVertex();
        buf.vertex(mat, leftX, leftY, 0.0f).color(r, g, b, a).endVertex();
        buf.vertex(mat, rightX, rightY, 0.0f).color(r, g, b, a).endVertex();
        buf.vertex(mat, tipX, tipY, 0.0f).color(r, g, b, a).endVertex();
        tess.end();
        RenderSystem.enableCull();
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
    }

    private static double clamp(double v, double lo, double hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** HUD text for one metric element; counts are per lap. */
    public static String textFor(RouterunnerConfig.HudElementId id, MetricsTracker m) {
        switch (id) {
            case TOTAL:      return "Chests: " + m.getLapTotal();
            case NET_AVG:    return String.format("Net Avg: %.1f/min", m.getLapNetAvgPerMin());
            case ACTIVE_AVG: return String.format("Active Avg: %.1f/min", m.getLapActiveAvgPerMin());
            case SLIDING:    return String.format("1m: %.1f/min", m.getSlidingPerMin());
            default:         return "";
        }
    }

    /** The movable loot panel: one icon + Tot/Act/1m per tracked item for the resolved chest type. */
    private static void renderLootPanel(PoseStack ps, Minecraft mc, Font font, int x, int y) {
        LootListener loot = LootListener.get();
        if (loot.autoWaiting()) {
            font.drawShadow(ps, String.format("Loot: detecting vault (%d/100)", loot.autoProgress()), x, y, 0xAAAAAA);
            return;
        }
        if (!loot.isActive()) return;
        font.drawShadow(ps, cap(loot.getResolvedType()) + " loot /min", x, y, 0xFFD700);
        int i = 0;
        for (String key : loot.keysForDisplay()) {
            int ly = y + 12 + i * 18;
            ItemStack sprite = loot.sprite(key);
            if (sprite != null && !sprite.isEmpty()) {
                mc.getItemRenderer().renderGuiItem(sprite, x, ly);
            }
            String txt = String.format("T %.1f  A %.1f  1m %.1f",
                    loot.netPerMin(key), loot.activePerMin(key), loot.slidingPerMin(key));
            font.drawShadow(ps, txt, x + 20, ly + 4, 0xFFFFFF);
            i++;
        }
    }

    static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private RouterunnerHud() {}
}
