package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** A short, scrollable help panel explaining the three modes, the weights menu, and where the logs go. */
public class HelpScreen extends Screen {
    private final Screen parent;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private double scroll = 0;
    private int viewTop, viewBottom, textW, x0;

    // Player-facing help (placeholder — will be replaced with the user's own copy). Keep it non-technical.
    private static final String[] PARAS = {
        "§eRouterunner§r draws the fastest chest-looting route through a room. Follow the coloured line and break chests as you pass — you don't have to chase every single one.",
        "",
        "§eThe route line:§r",
        "§a  Green§r — just walk it.",
        "§b  Cyan§r — sprint-jump along it, looting as you go.",
        "§6  Amber§r — a drop: walk off the edge and let yourself fall.",
        "§d  Purple§r — trident dash (usually up or down).",
        "§f  White§r — a turnaround is coming; you double back here.",
        "§c  Red§r — the stretch you're on right now.",
        "",
        "§eThe markers (numbered in look order):§r",
        "§d  1 / Pink§r — the chest to break next.",
        "§9  2 / Blue§r — the one after; line up your next look.",
        "§1  Dark blue§r — further ahead.",
        "§6  Orange§r — the exit.",
        "",
        "§eRunning it:§r sweep across each cluster as you reach it. The route deliberately skips awkward, walled-in chests — breaking a neighbour chains them anyway — so don't backtrack for them. When the pink chest is gone, the route advances on its own.",
        "",
        "§eToo bouncy, or skipping too much?§r Open §eAdjust Weights§r and tune it live. Start with §fBail aggression§r (skim vs. loot-full) and §fBreak reach§r (how close you get before breaking).",
        "",
        "§eNew Lap§r resets the HUD counters only — the vault's own clocks and its log keep running underneath.",
        "Every vault writes one §fvault_*.jsonl§r file into the runs folder (§eOpen Log Folder§r) holding the planned routes, your path, your chest breaks and the per-room diffs.",
    };

    public HelpScreen(Screen parent) {
        super(new TextComponent("Routerunner — Help"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        viewTop = 34;
        viewBottom = this.height - 40;
        textW = Math.min(this.width - 40, 380);
        x0 = (this.width - textW) / 2;
        lines.clear();
        for (String p : PARAS) {
            lines.addAll(this.font.split(new TextComponent(p), textW));
            lines.add(FormattedCharSequence.EMPTY); // paragraph gap
        }
        this.addRenderableWidget(new Button(this.width / 2 - 100, this.height - 28, 200, 20,
                new TextComponent("Back"), b -> this.onClose()));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int contentH = lines.size() * 10;
        int maxScroll = Math.max(0, contentH - (viewBottom - viewTop));
        scroll -= delta * 15;
        if (scroll < 0) scroll = 0;
        if (scroll > maxScroll) scroll = maxScroll;
        return true;
    }

    @Override
    public void render(PoseStack ps, int mx, int my, float pt) {
        this.renderBackground(ps);
        drawCenteredString(ps, this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        int y = viewTop - (int) scroll;
        for (FormattedCharSequence line : lines) {
            if (y >= viewTop - 10 && y <= viewBottom) this.font.draw(ps, line, x0, y, 0xFFFFFF);
            y += 10;
        }
        super.render(ps, mx, my, pt);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
