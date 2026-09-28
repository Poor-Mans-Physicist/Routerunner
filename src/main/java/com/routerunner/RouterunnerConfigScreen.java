package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

/**
 * Top-level config menu (opened by the keybind): the everyday toggles on the left, the sub-menus (routing, visuals,
 * HUD layout, past vaults, data and logs) on the right.
 */
public class RouterunnerConfigScreen extends Screen {
    static final int STEP = 24;
    private static final int LEFT_ROWS = 6;
    private static final int RIGHT_ROWS = 5;

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
        top = topFor(this.height, rows, step);
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
        this.addRenderableWidget(new Button(lx, y, bw, 20, timeModelLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.timeModel = RouterunnerConfig.nextTimeModel(cfg.timeModel);
            RunLog.timeModel(cfg.timeModel, "menu");
            b.setMessage(timeModelLabel());
        }, (b, pose, mx, my) -> this.renderTooltip(pose, this.font.split(new TextComponent(
                "Shape: runs, turns and clicks priced move by move, scaled to your measured speed. "
                        + "Learned: the adaptive leg model. Applies from the next room plan."), 250), mx, my)));
        y += step;
        this.addRenderableWidget(new Button(lx, y, bw, 20, new TextComponent("New Lap"), b -> {
            int lap = ClientEvents.newLap();
            b.setMessage(new TextComponent(lap > 0 ? "Lap " + lap + " started ✓" : "New Lap (not in a vault)"));
        }));
        y += step;
        this.addRenderableWidget(new Button(lx, y, bw, 20, new TextComponent("Help"),
                b -> this.minecraft.setScreen(new HelpScreen(this))));

        y = top;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("Routing..."),
                b -> this.minecraft.setScreen(new RoutingScreen(this))));
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("Visuals and QoL..."),
                b -> this.minecraft.setScreen(new VisualsScreen(this))));
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("HUD Layout"),
                b -> this.minecraft.setScreen(new HudEditorScreen(this))));
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("Past Vaults"),
                b -> this.minecraft.setScreen(new HistoryScreen(this))));
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("Data and Logs..."),
                b -> this.minecraft.setScreen(new DataLogsScreen(this))));

        this.addRenderableWidget(new Button(cx - 100, top + rows * step + 14, 200, 20,
                new TextComponent("Done"), b -> this.onClose()));
    }

    /** Start the columns high enough that the longer one AND the Done button below it still fit the screen. */
    static int topFor(int height, int rows, int step) {
        int needed = rows * step + 14 + 20;
        int t = height / 4;
        if (t + needed > height - 4) t = height - 4 - needed;
        return Math.max(24, t);
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

    private Component timeModelLabel() {
        return new TextComponent("Time model: " + RouterunnerConfig.timeModelLabel(RouterunnerConfig.get().timeModel));
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
