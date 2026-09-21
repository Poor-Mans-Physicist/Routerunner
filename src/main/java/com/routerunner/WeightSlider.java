package com.routerunner;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * A labelled slider for one routing weight over {@code min}..{@code max}. Writes to the live config through the
 * setter, so changes apply on the next room solve, and provides a hover tooltip with description, default and range.
 */
public class WeightSlider extends AbstractSliderButton {
    private final String name, desc;
    private final double min, max, def;
    private final boolean isInt;
    private final DoubleConsumer setter;

    public WeightSlider(int x, int y, int w, int h, String name, String desc,
                        double min, double max, double def, boolean isInt,
                        DoubleSupplier getter, DoubleConsumer setter) {
        super(x, y, w, h, TextComponent.EMPTY, clamp01((getter.getAsDouble() - min) / (max - min)));
        this.name = name;
        this.desc = desc;
        this.min = min;
        this.max = max;
        this.def = def;
        this.isInt = isInt;
        this.setter = setter;
        updateMessage();
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private double current() {
        double v = min + this.value * (max - min);
        return isInt ? Math.round(v) : v;
    }

    @Override
    protected void updateMessage() {
        setMessage(new TextComponent(name + ": " + fmt(current())));
    }

    @Override
    protected void applyValue() {
        setter.accept(current());
    }

    /** Snap this slider back to the weight's tuned default and apply it. */
    public void resetToDefault() {
        this.value = clamp01((def - min) / (max - min));
        applyValue();
        updateMessage();
    }

    /** Hover text: name, what it does, and the default + range for reference. */
    public List<Component> tooltip() {
        List<Component> t = new ArrayList<>();
        t.add(new TextComponent(name));
        for (String line : wrap(desc, 46)) t.add(new TextComponent("§7" + line));
        t.add(new TextComponent("§8default " + fmt(def) + "  •  range 0-" + fmt(max)));
        return t;
    }

    private String fmt(double v) {
        return isInt ? String.valueOf((int) Math.round(v)) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** Greedy word-wrap so a long description doesn't overflow the tooltip. */
    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
