package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Scrollable player-facing help: the lane route, its colours and markers, how to run it, laps and the run logs. */
public class HelpScreen extends Screen {
    private final Screen parent;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private double scroll = 0;
    private int viewTop, viewBottom, textW, x0;

    private static final String[] PARAS = {
        "§eRouterunner§r plans a route through each room as a chain of short runs and draws it on the floor. Follow the carpet, hold the mine button and keep moving — chain breaking takes the chests around you, so you don't aim at each one.",
        "",
        "§eThe carpet and line:§r",
        "§6  Orange§r — the next 14 blocks ahead of you. This is the part to follow.",
        "§b  Cyan§r — the rest of the current run.",
        "§7  Dim§r — the part you've already walked.",
        "§7  Grey§r — the run after this one.",
        "§a  Green line§r — the walk out to the exit once the room is done.",
        "§d  Purple§r — a climb, drop or trident dash: the big floating purple arrow shows where the up or down move happens.",
        "§c  Thin red line§r — you've strayed; it leads back to the route.",
        "",
        "§eThe chest boxes:§r",
        "§d  Pink to red§r — every chest the current run will clear; the redder, the more falls with it. Hit the reddest first.",
        "§a  Green wireframe§r — the next cluster on the run. Head for those; they stay marked until they fall.",
        "",
        "§eRunning it:§r walk the orange carpet straight through the green cluster. When a run's chests are gone, or you pass its end, the route moves to the next run on its own. Run ends can sit behind you — the planner already priced the turn.",
        "",
        "§eWhen the room is done:§r the planner stops adding runs once the ones left would loot slower than about half your current rate, and the green line leads to the exit. Leaving early there is the right call, not a bug.",
        "",
        "§eOff the route:§r wander more than 6 blocks from the whole run for 5 seconds and the room is replanned from where you stand. Dash warps are fine; the route picks you up where you land.",
        "",
        "§eNew Lap§r resets the HUD counters only — the vault's own clocks and its log keep running underneath. Use it to compare attempts in one vault.",
        "Every vault writes one §fvault_*.jsonl§r file into the runs folder (§eOpen Log Folder§r) holding the planned routes, your path, your chest breaks and the per-room comparisons.",
        "",
        "§eSettings:§r the bail and exit weights live in §fconfig/routerunner/config.json§r (laneBail, laneExitWeight, laneBailRateFrac); the defaults are what the timings were tuned with.",
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
            lines.add(FormattedCharSequence.EMPTY);
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
