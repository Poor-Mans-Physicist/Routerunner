package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;

import java.util.Collections;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * The "Visuals and QoL" menu: a scrolling list with a master opacity slider, one opacity slider for every element
 * Routerunner draws ({@link Visuals.Element}) grouped by section, and the map toggles. Changes apply live and are saved
 * on close.
 */
public class VisualsScreen extends Screen {
    private static final int ROW_H = 24;
    private static final int SLIDER_W = 310;
    /** Slider steps in percent. */
    private static final int STEP_PCT = 5;

    private final Screen parent;
    private OptionList list;
    private boolean resetArmed = false;

    public VisualsScreen(Screen parent) {
        super(new TextComponent("Visuals and QoL"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        list = new OptionList(this.minecraft, this.width, this.height, 32, this.height - 32, ROW_H);
        RouterunnerConfig cfg = RouterunnerConfig.get();
        list.add(new HeaderEntry("Opacity"));
        list.add(new SliderEntry(new PercentSlider("All elements (master)",
                "Scales every element below; 0 % hides the whole overlay and HUD.",
                () -> cfg.masterOpacity, v -> cfg.masterOpacity = v)));
        Visuals.Section section = null;
        for (Visuals.Element e : Visuals.Element.values()) {
            if (e.section != section) {
                section = e.section;
                list.add(new HeaderEntry(section.title));
            }
            list.add(new SliderEntry(new PercentSlider(e.label, e.tooltip,
                    () -> cfg.opacityOf(e), v -> cfg.opacity.put(e, v))));
        }
        list.add(new HeaderEntry("Map"));
        list.add(new ToggleEntry(() -> "Vault Mapper axis lines: " + (cfg.mapperAxisLines ? "Shown" : "Hidden"),
                "Purple borders on Vault Mapper's map around the rooms on the densest lines: the start room's column and row, "
                        + "then every 16 map cells (every 8th room, with a tunnel between rooms); lighter purple marks the nearly-as-dense room line just east/south of each. Needs Vault Mapper; follows its position, scale and player-centric settings.",
                () -> cfg.mapperAxisLines = !cfg.mapperAxisLines));
        list.add(new HeaderEntry("World"));
        list.add(new ToggleEntry(() -> "Hunter boxes: " + (cfg.suppressHunter ? "Hidden" : "Shown"),
                "Show or hide the_vault's Hunter ability chest outlines.",
                () -> cfg.suppressHunter = !cfg.suppressHunter));
        this.addWidget(list);

        int cx = this.width / 2;
        this.addRenderableWidget(new Button(cx - 154, this.height - 26, 150, 20, new TextComponent("Reset all to 100%"), b -> {
            if (!resetArmed) {
                resetArmed = true;
                b.setMessage(new TextComponent("Click again to reset"));
                return;
            }
            resetArmed = false;
            RouterunnerConfig.get().resetOpacity();
            RouterunnerConfig.save();
            double scroll = list.getScrollAmount();
            this.clearWidgets();
            this.init();
            list.setScrollAmount(scroll);
        }));
        this.addRenderableWidget(new Button(cx + 4, this.height - 26, 150, 20, new TextComponent("Done"), b -> this.onClose()));
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(poseStack);
        list.render(poseStack, mouseX, mouseY, partialTicks);
        drawCenteredString(poseStack, this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        super.render(poseStack, mouseX, mouseY, partialTicks);
        String tip = list.hoveredTooltip(mouseX, mouseY);
        if (tip != null) this.renderTooltip(poseStack, this.font.split(new TextComponent(tip), 250), mouseX, mouseY);
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

    /** The scrolling list of headers and sliders. */
    private static final class OptionList extends ContainerObjectSelectionList<Row> {
        OptionList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
        }

        void add(Row e) {
            this.addEntry(e);
        }

        @Override
        public int getRowWidth() {
            return SLIDER_W;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width / 2 + SLIDER_W / 2 + 8;
        }

        /** The tooltip of the row under the mouse, or null. */
        String hoveredTooltip(int mouseX, int mouseY) {
            if (!this.isMouseOver(mouseX, mouseY)) return null;
            Row e = this.getEntryAtPosition(mouseX, mouseY);
            if (e instanceof SliderEntry se) return se.slider.tooltip;
            if (e instanceof ToggleEntry te) return te.tooltip;
            return null;
        }
    }

    private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    }

    /** A section heading. */
    private static final class HeaderEntry extends Row {
        private final String text;

        HeaderEntry(String text) {
            this.text = text;
        }

        @Override
        public void render(PoseStack ps, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTicks) {
            Minecraft mc = Minecraft.getInstance();
            drawCenteredString(ps, mc.font, text, left + width / 2, top + (height - mc.font.lineHeight) / 2, 0xFFD700);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return Collections.emptyList();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return Collections.emptyList();
        }
    }

    /** A row holding one slider. */
    private static final class SliderEntry extends Row {
        private final PercentSlider slider;

        SliderEntry(PercentSlider slider) {
            this.slider = slider;
        }

        @Override
        public void render(PoseStack ps, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTicks) {
            slider.x = left;
            slider.y = top;
            slider.render(ps, mouseX, mouseY, partialTicks);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(slider);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(slider);
        }
    }

    /** A row holding one on/off button. */
    private static final class ToggleEntry extends Row {
        private final Button button;
        final String tooltip;

        ToggleEntry(java.util.function.Supplier<String> label, String tooltip, Runnable flip) {
            this.tooltip = tooltip;
            this.button = new Button(0, 0, SLIDER_W, 20, new TextComponent(label.get()), b -> {
                flip.run();
                b.setMessage(new TextComponent(label.get()));
            });
        }

        @Override
        public void render(PoseStack ps, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTicks) {
            button.x = left;
            button.y = top;
            button.render(ps, mouseX, mouseY, partialTicks);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(button);
        }
    }

    /** A 0-100 % slider in {@link #STEP_PCT} steps bound to a config value in [0, 1]. */
    private static final class PercentSlider extends AbstractSliderButton {
        private final String label;
        final String tooltip;
        private final DoubleConsumer setter;

        PercentSlider(String label, String tooltip, DoubleSupplier getter, DoubleConsumer setter) {
            super(0, 0, SLIDER_W, 20, TextComponent.EMPTY, clamp01(getter.getAsDouble()));
            this.label = label;
            this.tooltip = tooltip;
            this.setter = setter;
            this.value = snap(this.value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int pct = (int) Math.round(this.value * 100.0);
            this.setMessage(new TextComponent(label + ": " + (pct == 0 ? "Hidden" : pct + "%")));
        }

        @Override
        protected void applyValue() {
            this.value = snap(this.value);
            setter.accept(this.value);
        }

        private static double snap(double v) {
            return Math.round(clamp01(v) * 100.0 / STEP_PCT) * STEP_PCT / 100.0;
        }

        private static double clamp01(double v) {
            return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
        }
    }
}
