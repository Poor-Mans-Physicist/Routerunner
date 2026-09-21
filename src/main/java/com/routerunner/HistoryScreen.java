package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.util.Mth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Simple scrollable viewer of completed-vault summaries (newest first). */
public class HistoryScreen extends Screen {
    private final Screen parent;
    private List<VaultSummary> entries;
    private double scroll = 0;
    private static final int ROW_H = 30;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM/dd HH:mm");

    public HistoryScreen(Screen parent) {
        super(new TextComponent("Past Vaults"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.entries = HistoryStore.load();
        Collections.reverse(this.entries); // newest first
        this.addRenderableWidget(new Button(this.width / 2 - 100, this.height - 28, 200, 20,
                new TextComponent("Done"), b -> this.onClose()));
    }

    private int listTop() { return 32; }
    private int listBottom() { return this.height - 36; }

    @Override
    public void render(PoseStack ps, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(ps);
        Font font = this.font;
        drawCenteredString(ps, font, this.title, this.width / 2, 12, 0xFFFFFF);

        if (entries.isEmpty()) {
            drawCenteredString(ps, font, new TextComponent("No vaults recorded yet."),
                    this.width / 2, this.height / 2, 0xA0A0A0);
            super.render(ps, mouseX, mouseY, partialTicks);
            return;
        }

        int top = listTop();
        int bottom = listBottom();
        int x = Math.max(10, this.width / 2 - 180);
        int y = top - (int) scroll;
        for (VaultSummary s : entries) {
            if (y >= top && y <= bottom) {
                String laps = s.laps > 1 ? String.format("  ·  %d laps", s.laps) : "";
                String line1 = String.format("%s  ·  %s  ·  %d chests%s  ·  %.1f/min active (%.1f net)",
                        FMT.format(new Date(s.timestamp)), RouterunnerHud.cap(s.type), s.chests, laps,
                        s.activeAvg, s.netAvg);
                String mods = modifierLine(s);
                font.drawShadow(ps, line1, x, y, 0xFFFFFF);
                font.drawShadow(ps, font.plainSubstrByWidth(mods, 360), x + 8, y + 11, 0x9AA0FF);
            }
            y += ROW_H;
        }
        super.render(ps, mouseX, mouseY, partialTicks);
    }

    /**
     * Show only the modifiers relevant to what this vault was farmed for — the bonus/cascade
     * modifiers naming the tracked chest type (e.g. "Bonus Gilded", "Gilded Cascade" for a gilded
     * run). The full set buries those. Falls back to all modifiers if the type wasn't resolved.
     */
    private static String modifierLine(VaultSummary s) {
        if (s.modifiers == null || s.modifiers.isEmpty()) return "(no modifiers recorded)";
        String type = s.type == null ? "" : s.type.toLowerCase(Locale.ROOT);
        if (type.isEmpty() || type.equals("unknown")) {
            return String.join(", ", s.modifiers);
        }
        List<String> relevant = new ArrayList<>();
        for (String m : s.modifiers) {
            if (m.toLowerCase(Locale.ROOT).contains(type)) relevant.add(m);
        }
        if (relevant.isEmpty()) return "(no " + s.type + " bonus/cascade modifiers)";
        return String.join(", ", relevant);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int viewport = listBottom() - listTop();
        double max = Math.max(0, entries.size() * ROW_H - viewport);
        scroll = Mth.clamp(scroll - delta * ROW_H, 0, max);
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
    }
}
