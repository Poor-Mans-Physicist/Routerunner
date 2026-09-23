package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

import java.nio.file.Files;

/** Top-level config menu (opened by the keybind): toggles on the left, HUD editor, history, laps, logs and help on the right. */
public class RouterunnerConfigScreen extends Screen {
    private static final int STEP = 24;
    private static final int LEFT_ROWS = 6;
    private static final int RIGHT_ROWS = 6;

    private final Screen parent;
    private int top;

    public RouterunnerConfigScreen(Screen parent) {
        super(new TextComponent("Routerunner"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int step = STEP;
        int rows = Math.max(LEFT_ROWS, RIGHT_ROWS);
        top = topFor(rows, step);
        int lx = cx - 154, rx = cx + 4, bw = 150;

        int y = top;
        this.addRenderableWidget(new Button(lx, y, bw, 20, enabledLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.enabled = !cfg.enabled;
            b.setMessage(enabledLabel());
        }));
        y += step;
        this.addRenderableWidget(new Button(lx, y, bw, 20, routingLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.routingEnabled = !cfg.routingEnabled;
            b.setMessage(routingLabel());
        }));
        y += step;
        this.addRenderableWidget(new Button(lx, y, bw, 20, trackedLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.trackedChest = nextTracked(cfg.trackedChest);
            LootListener.get().onModeChanged();
            b.setMessage(trackedLabel());
        }));
        y += step;
        this.addRenderableWidget(new Button(lx, y, bw, 20, hunterLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.suppressHunter = !cfg.suppressHunter;
            b.setMessage(hunterLabel());
        }));
        y += step;
        this.addRenderableWidget(new Button(lx, y, bw, 20, arrowLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.offscreenIndicator = !cfg.offscreenIndicator;
            b.setMessage(arrowLabel());
        }));
        y += step;
        this.addRenderableWidget(new Button(lx, y, bw, 20, adaptiveLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.adaptiveLearning = !cfg.adaptiveLearning;
            b.setMessage(adaptiveLabel());
        }, (b, pose, mx, my) -> this.renderTooltip(pose, new TextComponent(
                com.routerunner.adaptive.Adaptive.statusLine() + " (" + com.routerunner.adaptive.Adaptive.detailLine() + ")"), mx, my)));

        y = top;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("Edit HUD Layout"),
                b -> this.minecraft.setScreen(new HudEditorScreen(this))));
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("View Past Vaults"),
                b -> this.minecraft.setScreen(new HistoryScreen(this))));
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("New Lap"), b -> {
            int lap = ClientEvents.newLap();
            b.setMessage(new TextComponent(lap > 0 ? "Lap " + lap + " started ✓" : "New Lap (not in a vault)"));
        }));
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("Open Log Folder"),
                b -> openLogFolder()));
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("Help"),
                b -> this.minecraft.setScreen(new HelpScreen(this))));
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("Reset Adaptive Model"), b -> {
            if (!resetArmed) {
                resetArmed = true;
                b.setMessage(new TextComponent("Click again to reset"));
                return;
            }
            resetArmed = false;
            com.routerunner.adaptive.Adaptive.resetLearned();
            b.setMessage(new TextComponent("Adaptive model reset ✓"));
        }));

        this.addRenderableWidget(new Button(cx - 100, top + rows * step + 14, 200, 20,
                new TextComponent("Done"), b -> this.onClose()));
    }

    /** Start the columns high enough that the longer one AND the Done button below it still fit the screen. */
    private int topFor(int rows, int step) {
        int needed = rows * step + 14 + 20;
        int t = this.height / 4;
        if (t + needed > this.height - 4) t = this.height - 4 - needed;
        return Math.max(24, t);
    }

    private static void openLogFolder() {
        try {
            Files.createDirectories(RunLog.runsDir());
            Util.getPlatform().openFile(RunLog.runsDir().toFile());
        } catch (Exception e) {
            LogUtils.getLogger().error("[Routerunner] failed to open log folder", e);
        }
    }

    private Component enabledLabel() {
        return new TextComponent("Routerunner: " + (RouterunnerConfig.get().enabled ? "Enabled" : "Disabled"));
    }

    private Component routingLabel() {
        return new TextComponent("Route overlay: " + (RouterunnerConfig.get().routingEnabled ? "Shown" : "Hidden"));
    }

    private Component trackedLabel() {
        return new TextComponent("Track loot: " + RouterunnerConfig.get().trackedChest.name());
    }

    private Component hunterLabel() {
        return new TextComponent("Hunter boxes: " + (RouterunnerConfig.get().suppressHunter ? "Hidden" : "Shown"));
    }

    private boolean resetArmed = false;

    private Component adaptiveLabel() {
        return new TextComponent("Adaptive learning: " + (RouterunnerConfig.get().adaptiveLearning ? "On" : "Off"));
    }

    private Component arrowLabel() {
        return new TextComponent("Target arrow: " + (RouterunnerConfig.get().offscreenIndicator ? "On" : "Off"));
    }

    private static RouterunnerConfig.TrackedChest nextTracked(RouterunnerConfig.TrackedChest cur) {
        RouterunnerConfig.TrackedChest[] v = RouterunnerConfig.TrackedChest.values();
        return v[(cur.ordinal() + 1) % v.length];
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(poseStack);
        drawCenteredString(poseStack, this.font, this.title, this.width / 2, this.top - 20, 0xFFFFFF);
        super.render(poseStack, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        RouterunnerConfig.save();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
