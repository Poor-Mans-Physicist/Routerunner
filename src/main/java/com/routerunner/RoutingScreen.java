package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.routerunner.calib.PlayerCalibration;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

import java.util.Locale;

/**
 * Routing sub-menu: the room picker, the target arrow, the learned model's adaptive learning and its reset, and the
 * player calibration (measured speed against the benchmark per miner) with its reset.
 */
public class RoutingScreen extends Screen {
    private static final int ROWS = 6;
    private final Screen parent;
    private int top;
    private boolean adaptiveResetArmed = false, calibResetArmed = false;
    private Button adaptiveButton;

    public RoutingScreen(Screen parent) {
        super(new TextComponent("Routing"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int step = RouterunnerConfigScreen.STEP;
        top = RouterunnerConfigScreen.topFor(this.height, ROWS + 2, step);
        int lx = cx - 154, rx = cx + 4, bw = 150;

        int y = top;
        this.addRenderableWidget(new Button(lx, y, bw, 20, roomPickerLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.adaptiveRooms = !cfg.adaptiveRooms;
            b.setMessage(roomPickerLabel());
        }, (b, pose, mx, my) -> this.renderTooltip(pose, this.font.split(new TextComponent(
                "Adaptive: leave each room toward the best unvisited rooms around it. Straight: always the door opposite the entrance."), 250), mx, my)));
        y += step;
        this.addRenderableWidget(new Button(lx, y, bw, 20, arrowLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.offscreenIndicator = !cfg.offscreenIndicator;
            b.setMessage(arrowLabel());
        }));

        y = top;
        adaptiveButton = this.addRenderableWidget(new Button(rx, y, bw, 20, adaptiveLabel(), b -> {
            RouterunnerConfig cfg = RouterunnerConfig.get();
            cfg.adaptiveLearning = !cfg.adaptiveLearning;
            b.setMessage(adaptiveLabel());
        }, (b, pose, mx, my) -> this.renderTooltip(pose, this.font.split(new TextComponent(
                (RouterunnerConfig.get().shapeTimeModel() ? "Only used by the Learned time model. " : "")
                        + com.routerunner.adaptive.Adaptive.statusLine() + " (" + com.routerunner.adaptive.Adaptive.detailLine() + ")"), 250), mx, my)));
        adaptiveButton.active = !RouterunnerConfig.get().shapeTimeModel();
        y += step;
        this.addRenderableWidget(new Button(rx, y, bw, 20, new TextComponent("Reset Adaptive Model"), b -> {
            if (!adaptiveResetArmed) {
                adaptiveResetArmed = true;
                b.setMessage(new TextComponent("Click again to reset"));
                return;
            }
            adaptiveResetArmed = false;
            com.routerunner.adaptive.Adaptive.resetLearned();
            b.setMessage(new TextComponent("Adaptive model reset ✓"));
        }));

        int cy = top + 3 * step + 20;
        this.addRenderableWidget(new Button(cx - 75, cy + 2 * 12 + 8, 150, 20, new TextComponent("Reset Speed Calibration"), b -> {
            if (!calibResetArmed) {
                calibResetArmed = true;
                b.setMessage(new TextComponent("Click again to reset"));
                return;
            }
            calibResetArmed = false;
            PlayerCalibration.reset();
            b.setMessage(new TextComponent("Calibration reset ✓"));
        }, (b, pose, mx, my) -> this.renderTooltip(pose, this.font.split(new TextComponent(
                "Forget your measured speed and go back to the benchmark. It is re-measured from the rooms you loot."), 250), mx, my)));

        this.addRenderableWidget(new Button(cx - 100, top + (ROWS + 2) * step + 14, 200, 20,
                new TextComponent("Done"), b -> this.onClose()));
    }

    private Component roomPickerLabel() {
        return new TextComponent("Next room: " + (RouterunnerConfig.get().adaptiveRooms ? "Adaptive" : "Straight"));
    }

    private Component arrowLabel() {
        return new TextComponent("Target arrow: " + (RouterunnerConfig.get().offscreenIndicator ? "On" : "Off"));
    }

    private Component adaptiveLabel() {
        return new TextComponent("Adaptive learning: " + (RouterunnerConfig.get().adaptiveLearning ? "On" : "Off"));
    }

    @Override
    public void render(PoseStack ps, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(ps);
        drawCenteredString(ps, this.font, this.title, this.width / 2, this.top - 20, 0xFFFFFF);
        int step = RouterunnerConfigScreen.STEP;
        int cy = top + 3 * step;
        drawCenteredString(ps, this.font, new TextComponent("Your speed vs the benchmark"), this.width / 2, cy, 0xFFD700);
        drawCenteredString(ps, this.font, new TextComponent(PlayerCalibration.describe(false)), this.width / 2, cy + 20, 0xE0E0E0);
        drawCenteredString(ps, this.font, new TextComponent(PlayerCalibration.describe(true)), this.width / 2, cy + 32, 0xE0E0E0);
        super.render(ps, mouseX, mouseY, partialTicks);
        int x0 = this.width / 2 - 120, x1 = this.width / 2 + 120;
        if (mouseX >= x0 && mouseX <= x1 && mouseY >= cy - 2 && mouseY <= cy + 44) {
            this.renderTooltip(ps, this.font.split(new TextComponent(String.format(Locale.ROOT,
                    "Measured from the path you actually walked and the hits you took in each room, priced move by move and "
                            + "compared with the benchmark player (the pack author) on the same moves. x1.00 plays like the benchmark. "
                            + "Route choice does not count here: that shows up as the gap between Actual and Your Pace in Past Vaults. "
                            + "Room fit R² chain %s, vein %s.", r2(false), r2(true))), 260), mouseX, mouseY);
        }
    }

    private static String r2(boolean vein) {
        double v = PlayerCalibration.r2(vein);
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f", v);
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
