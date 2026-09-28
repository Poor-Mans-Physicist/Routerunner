package com.routerunner;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;

/**
 * Per-element opacity for everything Routerunner draws. Each {@link Element} has a multiplier in
 * {@link RouterunnerConfig#opacity}, and {@link RouterunnerConfig#masterOpacity} scales all of them; the renderers
 * multiply their own alphas by {@link #alpha(Element)}.
 */
public final class Visuals {
    /** Lowest text alpha byte Minecraft honours; below it {@code Font} draws the text fully opaque. */
    private static final int MIN_TEXT_ALPHA = 4;

    /** A group of elements shown under one heading in the Visuals and QoL screen. */
    public enum Section {
        LANE("Lane route"),
        HUD("HUD");

        public final String title;

        Section(String title) {
            this.title = title;
        }
    }

    /** Every separately rendered Routerunner element. */
    public enum Element {
        LANE_CARPET(Section.LANE, "Floor carpet", "The floor highlight under the run you are following."),
        LANE_NEXT_CARPET(Section.LANE, "Next-run carpet", "The grey floor highlight under the run after this one."),
        LANE_LINE(Section.LANE, "Route line", "The centreline of the current run, including the orange chevron window."),
        LANE_NEXT_LINE(Section.LANE, "Next-run line", "The grey centreline of the run after this one."),
        LANE_SHAFT_ARROWS(Section.LANE, "Drop/climb arrows", "The floating purple arrows through drops, climbs and flights."),
        LANE_UTURN(Section.LANE, "U-turn marker", "The yellow arc where the next run heads back the way you came."),
        LANE_TRACER(Section.LANE, "Off-lane tracer", "The thin red line leading back to the lane when you leave it."),
        HEATMAP(Section.LANE, "Chest heatmap", "The pink-to-red value boxes on the current run's chests."),
        HEATMAP_NEXT(Section.LANE, "Next-run heatmap", "The fainter value boxes on the next run's chests."),
        TARGET_OUTLINES(Section.LANE, "Target outlines", "The green wireframes on the chests to hit now."),
        PRIORITY_OUTLINES(Section.LANE, "Priority outlines", "The blue wireframes on vein-miner priority chests."),
        HUD_STATS(Section.HUD, "Stat readouts", "Time, chests, averages, 1m rate and density."),
        HUD_LOOT(Section.HUD, "Loot panel", "The loot /min panel, icons included."),
        HUD_STATUS(Section.HUD, "Route status line", "The RR: status text in the bottom-left corner."),
        HUD_ARROW(Section.HUD, "Off-screen arrow", "The screen-edge arrow and distance to the next target.");

        public final Section section;
        public final String label;
        public final String tooltip;

        Element(Section section, String label, String tooltip) {
            this.section = section;
            this.label = label;
            this.tooltip = tooltip;
        }
    }

    /** The effective opacity of an element: its own setting times the master setting, in [0, 1]. */
    public static float alpha(Element e) {
        RouterunnerConfig cfg = RouterunnerConfig.get();
        return (float) (cfg.masterOpacity * cfg.opacityOf(e));
    }

    /**
     * An ARGB text colour with its alpha scaled by {@code scale}, or 0 when the result is too faint to draw (Minecraft
     * would render it fully opaque instead); callers skip drawing on 0. A colour given without alpha counts as opaque.
     */
    public static int textColor(int argb, float scale) {
        int a = (argb >>> 24) == 0 ? 0xFF : (argb >>> 24);
        int scaled = Math.round(a * scale);
        if (scaled < MIN_TEXT_ALPHA) return 0;
        return (Math.min(scaled, 0xFF) << 24) | (argb & 0xFFFFFF);
    }

    /** {@code font.drawShadow} at the scaled alpha; draws nothing when the element is too faint. */
    public static void drawShadow(PoseStack ps, Font font, String text, float x, float y, int rgb, float scale) {
        int c = textColor(rgb, scale);
        if (c == 0) return;
        font.drawShadow(ps, text, x, y, c);
    }

    /**
     * A GUI item icon at the given opacity. Full opacity uses the vanilla path unchanged; below that the model is
     * drawn with every vertex alpha scaled and opaque block layers swapped for the translucent item sheet, so the
     * icon actually blends.
     */
    public static void renderGuiItem(Minecraft mc, ItemStack stack, int x, int y, float alpha) {
        if (alpha <= 0.0f) return;
        ItemRenderer ir = mc.getItemRenderer();
        if (alpha >= 1.0f) {
            ir.renderGuiItem(stack, x, y);
            return;
        }
        BakedModel model = ir.getModel(stack, null, null, 0);
        mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, false);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        view.translate(x, y, 100.0f + ir.blitOffset);
        view.translate(8.0, 8.0, 0.0);
        view.scale(1.0f, -1.0f, 1.0f);
        view.scale(16.0f, 16.0f, 16.0f);
        RenderSystem.applyModelViewMatrix();
        MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
        MultiBufferSource faded = rt -> new AlphaConsumer(bs.getBuffer(translucent(rt)), alpha);
        boolean flat = !model.usesBlockLight();
        if (flat) Lighting.setupForFlatItems();
        ir.render(stack, ItemTransforms.TransformType.GUI, false, new PoseStack(), faded, 0xF000F0, OverlayTexture.NO_OVERLAY, model);
        bs.endBatch();
        RenderSystem.enableDepthTest();
        if (flat) Lighting.setupFor3DItems();
        view.popPose();
        RenderSystem.applyModelViewMatrix();
    }

    private static RenderType translucent(RenderType rt) {
        if (rt == Sheets.cutoutBlockSheet() || rt == Sheets.solidBlockSheet()) return Sheets.translucentItemSheet();
        return rt;
    }

    /** Passes vertices through with their alpha multiplied by a constant. */
    private static final class AlphaConsumer implements VertexConsumer {
        private final VertexConsumer inner;
        private final float scale;

        AlphaConsumer(VertexConsumer inner, float scale) {
            this.inner = inner;
            this.scale = scale;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            inner.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            inner.color(r, g, b, Math.round(a * scale));
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            inner.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            inner.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            inner.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            inner.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            inner.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            inner.defaultColor(r, g, b, Math.round(a * scale));
        }

        @Override
        public void unsetDefaultColor() {
            inner.unsetDefaultColor();
        }
    }

    private Visuals() {}
}
