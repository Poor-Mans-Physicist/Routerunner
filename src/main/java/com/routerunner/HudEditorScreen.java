package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Drag-to-place HUD editor. The four stat readouts and the loot panel (moved as one unit) are
 * draggable; per-element visibility toggles sit along the bottom. Saved to config on close.
 */
public class HudEditorScreen extends Screen {
    private final Screen parent;

    private RouterunnerConfig.HudElementId dragging = null;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;

    private boolean draggingLoot = false;
    private int lootOffsetX = 0;
    private int lootOffsetY = 0;

    public HudEditorScreen(Screen parent) {
        super(new TextComponent("Edit HUD Layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int bx = 5;
        for (RouterunnerConfig.HudElementId id : RouterunnerConfig.HudElementId.values()) {
            this.addRenderableWidget(new Button(bx, this.height - 26, 70, 20, toggleLabel(id), b -> {
                RouterunnerConfig.ElementConfig ec = RouterunnerConfig.get().element(id);
                ec.visible = !ec.visible;
                b.setMessage(toggleLabel(id));
            }));
            bx += 74;
        }
        this.addRenderableWidget(new Button(bx, this.height - 26, 90, 20, lootToggleLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.lootPanelVisible = !cfg.lootPanelVisible;
            b.setMessage(lootToggleLabel());
        }));
        this.addRenderableWidget(new Button(this.width - 85, this.height - 26, 80, 20,
                new TextComponent("Done"), b -> this.onClose()));
    }

    private Component toggleLabel(RouterunnerConfig.HudElementId id) {
        RouterunnerConfig.ElementConfig ec = RouterunnerConfig.get().element(id);
        return new TextComponent(label(id) + ": " + (ec.visible ? "On" : "Off"));
    }

    private Component lootToggleLabel() {
        return new TextComponent("Loot: " + (RouterunnerConfig.get().lootPanelVisible ? "On" : "Off"));
    }

    private static String label(RouterunnerConfig.HudElementId id) {
        switch (id) {
            case TOTAL:      return "Total";
            case NET_AVG:    return "Net";
            case ACTIVE_AVG: return "Active";
            case SLIDING:    return "1m";
            default:         return id.name();
        }
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(poseStack);
        Font font = this.font;
        MetricsTracker m = MetricsTracker.get();

        drawCenteredString(poseStack, font,
                new TextComponent("Drag to move · toggle visibility below · Done to save"),
                this.width / 2, 8, 0xA0A0A0);

        // stat elements
        for (RouterunnerConfig.HudElementId id : RouterunnerConfig.HudElementId.values()) {
            RouterunnerConfig.ElementConfig ec = RouterunnerConfig.get().element(id);
            String text = RouterunnerHud.textFor(id, m);
            int w = font.width(text);
            int h = font.lineHeight;
            fill(poseStack, ec.x - 2, ec.y - 2, ec.x + w + 2, ec.y + h + 2, ec.visible ? 0x80000000 : 0x40000000);
            font.drawShadow(poseStack, text, ec.x, ec.y, ec.visible ? 0xFFFFFF : 0x808080);
        }

        // loot panel (positionable even when not engaged; uses a gilded preview if unresolved)
        RouterunnerConfig cfg = RouterunnerConfig.get();
        if (cfg.trackedChest != RouterunnerConfig.TrackedChest.ALL) {
            int[] lb = lootBox();
            fill(poseStack, lb[0] - 2, lb[1] - 2, lb[0] + lb[2] + 2, lb[1] + lb[3],
                    cfg.lootPanelVisible ? 0x80000000 : 0x40000000);
            String type = previewType();
            font.drawShadow(poseStack, RouterunnerHud.cap(type) + " loot /min", lb[0], lb[1], 0xFFD700);
            int i = 0;
            for (String key : LootListener.itemsFor(type)) {
                int ly = lb[1] + 12 + i * 18;
                ItemStack s = LootListener.previewSprite(key);
                if (!s.isEmpty()) Minecraft.getInstance().getItemRenderer().renderGuiItem(s, lb[0], ly);
                font.drawShadow(poseStack, "T 0.0  A 0.0  1m 0.0", lb[0] + 20, ly + 4,
                        cfg.lootPanelVisible ? 0xFFFFFF : 0x808080);
                i++;
            }
        }

        super.render(poseStack, mouseX, mouseY, partialTicks);
    }

    private String previewType() {
        String t = LootListener.get().getResolvedType();
        return t == null ? "gilded" : t;
    }

    /** {x, y, width, height} of the loot panel for hit-testing/clamping. */
    private int[] lootBox() {
        RouterunnerConfig cfg = RouterunnerConfig.get();
        int lines = LootListener.itemsFor(previewType()).size();
        int w = 20 + this.font.width("T 88.8  A 88.8  1m 88.8");
        int h = 12 + lines * 18;
        return new int[]{cfg.lootPanelX, cfg.lootPanelY, w, h};
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            if (cfg.trackedChest != RouterunnerConfig.TrackedChest.ALL) {
                int[] lb = lootBox();
                if (mouseX >= lb[0] - 2 && mouseX <= lb[0] + lb[2] + 2 && mouseY >= lb[1] - 2 && mouseY <= lb[1] + lb[3]) {
                    draggingLoot = true;
                    lootOffsetX = (int) Math.round(mouseX) - cfg.lootPanelX;
                    lootOffsetY = (int) Math.round(mouseY) - cfg.lootPanelY;
                    return true;
                }
            }
            Font font = this.font;
            MetricsTracker m = MetricsTracker.get();
            for (RouterunnerConfig.HudElementId id : RouterunnerConfig.HudElementId.values()) {
                RouterunnerConfig.ElementConfig ec = RouterunnerConfig.get().element(id);
                String text = RouterunnerHud.textFor(id, m);
                int w = font.width(text);
                int h = font.lineHeight;
                if (mouseX >= ec.x - 2 && mouseX <= ec.x + w + 2 && mouseY >= ec.y - 2 && mouseY <= ec.y + h + 2) {
                    dragging = id;
                    dragOffsetX = (int) Math.round(mouseX) - ec.x;
                    dragOffsetY = (int) Math.round(mouseY) - ec.y;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingLoot) {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            int[] lb = lootBox();
            cfg.lootPanelX = Mth.clamp((int) Math.round(mouseX) - lootOffsetX, 0, this.width - lb[2]);
            cfg.lootPanelY = Mth.clamp((int) Math.round(mouseY) - lootOffsetY, 0, this.height - lb[3]);
            return true;
        }
        if (button == 0 && dragging != null) {
            RouterunnerConfig.ElementConfig ec = RouterunnerConfig.get().element(dragging);
            String text = RouterunnerHud.textFor(dragging, MetricsTracker.get());
            int w = this.font.width(text);
            int h = this.font.lineHeight;
            ec.x = Mth.clamp((int) Math.round(mouseX) - dragOffsetX, 0, this.width - w);
            ec.y = Mth.clamp((int) Math.round(mouseY) - dragOffsetY, 0, this.height - h);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && (dragging != null || draggingLoot)) {
            dragging = null;
            draggingLoot = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        RouterunnerConfig.save();
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        mc.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
