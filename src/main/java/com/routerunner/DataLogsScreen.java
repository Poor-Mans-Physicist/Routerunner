package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

import java.nio.file.Files;

/** Data and logs sub-menu: when a vault keeps its run log, and the runs folder. */
public class DataLogsScreen extends Screen {
    private static final int ROWS = 2;
    private final Screen parent;
    private int top;

    public DataLogsScreen(Screen parent) {
        super(new TextComponent("Data and Logs"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int step = RouterunnerConfigScreen.STEP;
        top = RouterunnerConfigScreen.topFor(this.height, ROWS, step);
        int y = top;
        this.addRenderableWidget(new Button(cx - 75, y, 150, 20, runLogLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.forceRunLog = !cfg.forceRunLog;
            b.setMessage(runLogLabel());
        }, (b, pose, mx, my) -> this.renderTooltip(pose, this.font.split(new TextComponent(
                "Gated: a vault is logged once you enter a room with 150+ chests of any type. Always: every vault keeps its log "
                        + "(learning still needs such a room)."), 250), mx, my)));
        y += step;
        this.addRenderableWidget(new Button(cx - 75, y, 150, 20, new TextComponent("Open Log Folder"), b -> openLogFolder()));
        this.addRenderableWidget(new Button(cx - 100, top + ROWS * step + 14, 200, 20,
                new TextComponent("Done"), b -> this.onClose()));
    }

    private static void openLogFolder() {
        try {
            Files.createDirectories(RunLog.runsDir());
            Util.getPlatform().openFile(RunLog.runsDir().toFile());
        } catch (Exception e) {
            LogUtils.getLogger().error("[Routerunner] failed to open log folder", e);
        }
    }

    private Component runLogLabel() {
        return new TextComponent("Run log: " + (RouterunnerConfig.get().forceRunLog ? "Always" : "Gated"));
    }

    @Override
    public void render(PoseStack ps, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(ps);
        drawCenteredString(ps, this.font, this.title, this.width / 2, this.top - 20, 0xFFFFFF);
        super.render(ps, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        RouterunnerConfig.save();
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
