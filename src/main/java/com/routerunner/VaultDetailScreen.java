package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.routerunner.calib.LapRecorder;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One past vault: a row per lap (time, chests, the HUD's active average, and the three room-based rates: actual, your
 * pace and the benchmark, plus density). Click a lap for its charts over time: chests per minute (actual, your pace,
 * benchmark) and room density with clumpiness.
 */
public class VaultDetailScreen extends Screen {
    static final int ACTUAL = 0xFFFFFFFF, PACE = 0xFFFFD24A, BENCH = 0xFF6AD1FF, HUD = 0x60FFFFFF, DENSITY = 0xFF7BE07B, CLUMP = 0xFFC08BFF;
    private static final int ROW_H = 14, CHART_H = 104, W = 380;
    private static final String[] COLS = {"Lap", "Time", "Chests", "Active", "Actual", "Your Pace", "Benchmark", "Density"};
    private static final int[] COL_X = {0, 30, 76, 124, 174, 224, 280, 338};

    private final Screen parent;
    private final VaultSummary vault;
    private final List<LapRecorder.Lap> laps;
    private final Set<Integer> open = new HashSet<>();
    private double scroll = 0;

    public VaultDetailScreen(Screen parent, VaultSummary vault) {
        super(new TextComponent("Vault " + HistoryScreen.FMT.format(new Date(vault.timestamp))));
        this.parent = parent;
        this.vault = vault;
        this.laps = vault.lapDetail == null ? new ArrayList<>() : vault.lapDetail;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(new Button(this.width / 2 - 100, this.height - 28, 200, 20,
                new TextComponent("Back"), b -> this.onClose()));
    }

    private int x0() { return Math.max(6, this.width / 2 - W / 2); }
    private int listTop() { return 70; }
    private int listBottom() { return this.height - 36; }

    private int contentHeight() {
        int h = 0;
        for (int i = 0; i < laps.size(); i++) h += ROW_H + (open.contains(i) ? CHART_H : 0);
        return h;
    }

    @Override
    public void render(PoseStack ps, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(ps);
        Font f = this.font;
        int x0 = x0();
        drawCenteredString(ps, f, this.title, this.width / 2, 10, 0xFFFFFF);
        String head = String.format(Locale.ROOT, "%s  ·  %,d chests  ·  %.1f active min  ·  %.0f/min active",
                RouterunnerHud.cap(vault.type), vault.chests, vault.activeMinutes, vault.activeAvg);
        drawCenteredString(ps, f, new TextComponent(head), this.width / 2, 22, 0xD0D0D0);
        drawCenteredString(ps, f, new TextComponent(f.plainSubstrByWidth(HistoryScreen.modifierLine(vault), W)), this.width / 2, 33, 0x9AA0FF);
        if (laps.isEmpty()) {
            drawCenteredString(ps, f, new TextComponent("No lap detail (recorded before Routerunner 1.2.0)."), this.width / 2, this.height / 2, 0xA0A0A0);
            super.render(ps, mouseX, mouseY, partialTicks);
            return;
        }
        int ly = 48;
        legend(ps, f, x0, ly);
        int hy = listTop() - 11;
        for (int c = 0; c < COLS.length; c++) f.drawShadow(ps, COLS[c], x0 + COL_X[c], hy, colColor(c));
        fill(ps, x0, hy + 9, x0 + W, hy + 10, 0x40FFFFFF);

        int y = listTop() - (int) scroll;
        int top = listTop(), bottom = listBottom();
        String tip = null;
        for (int i = 0; i < laps.size(); i++) {
            LapRecorder.Lap l = laps.get(i);
            if (y >= top && y + ROW_H <= bottom) {
                boolean hov = mouseX >= x0 && mouseX <= x0 + W && mouseY >= y && mouseY < y + ROW_H && mouseY >= top && mouseY < bottom;
                if (hov) fill(ps, x0 - 2, y - 2, x0 + W, y + ROW_H - 3, 0x30FFFFFF);
                String[] v = {(open.contains(i) ? "- " : "+ ") + l.lap, RouterunnerHud.clock((long) (l.activeMin * 60_000)), String.format(Locale.ROOT, "%,d", l.chests),
                        num(l.activeAvg), num(l.actualCpm), num(l.paceCpm), num(l.benchCpm), num(l.density)};
                for (int c = 0; c < v.length; c++) f.drawShadow(ps, v[c], x0 + COL_X[c], y, c >= 4 && c <= 6 ? colColor(c) : 0xFFFFFF);
            }
            y += ROW_H;
            if (open.contains(i)) {
                if (y >= top && y + CHART_H <= bottom) {
                    String t = charts(ps, f, l, x0, y, mouseX, mouseY);
                    if (t != null) tip = t;
                }
                y += CHART_H;
            }
        }
        super.render(ps, mouseX, mouseY, partialTicks);
        if (tip != null) this.renderTooltip(ps, this.font.split(new TextComponent(tip), 240), mouseX, mouseY);
        else if (mouseY >= ly - 2 && mouseY <= ly + 10 && mouseX >= x0 && mouseX <= x0 + W) {
            this.renderTooltip(ps, this.font.split(new TextComponent(
                    "All three over the rooms you looted, idle stretches removed, room switches included. Actual: what you got. "
                            + "Benchmark: the pack author's speed on the same room plans. Your Pace: the same plans at your measured "
                            + "speed, coverage and switch time as they were when the vault started. Actual below Your Pace is time "
                            + "spent off the route's moves; above it, you beat your own pace. Active is the HUD's lap average "
                            + "(all time, hallways and idle included)."), 260), mouseX, mouseY);
        }
    }

    private static int colColor(int c) {
        return switch (c) {
            case 4 -> ACTUAL & 0xFFFFFF;
            case 5 -> PACE & 0xFFFFFF;
            case 6 -> BENCH & 0xFFFFFF;
            default -> 0xB0B0B0;
        };
    }

    private void legend(PoseStack ps, Font f, int x, int y) {
        int cx = x;
        for (Object[] e : new Object[][]{{"Actual", ACTUAL}, {"Your Pace", PACE}, {"Benchmark", BENCH}, {"Active (HUD)", HUD},
                {"Density", DENSITY}, {"Clumpiness", CLUMP}}) {
            fill(ps, cx, y + 3, cx + 8, y + 5, (int) e[1]);
            f.drawShadow(ps, (String) e[0], cx + 11, y, 0xC0C0C0);
            cx += 14 + f.width((String) e[0]) + 8;
        }
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%,.0f", v);
    }

    /** Draw the two charts for one lap below its row; returns the hover text, if any. */
    private String charts(PoseStack ps, Font f, LapRecorder.Lap l, int x0, int y0, int mx, int my) {
        List<float[]> s = l.samples == null ? List.of() : l.samples;
        String info = String.format(Locale.ROOT, "%d rooms (%d with a plan)  ·  idle %.1f min  ·  speed ×%.2f (%s)  ·  clumpiness %s",
                l.rooms, l.plannedRooms, l.idleMin, "vein".equals(l.miner) ? l.speedVein : l.speedChain, l.miner, num(l.clump));
        f.drawShadow(ps, f.plainSubstrByWidth(info, W), x0, y0, 0x909090);
        int cy = y0 + 12, ch = CHART_H - 26;
        int wl = 224, wr = W - wl - 12;
        fill(ps, x0, cy, x0 + wl, cy + ch, 0x40000000);
        fill(ps, x0 + wl + 12, cy, x0 + W, cy + ch, 0x40000000);
        if (s.size() < 2) {
            f.drawShadow(ps, "Too short to chart", x0 + 6, cy + ch / 2 - 4, 0x808080);
            return null;
        }
        float tMax = s.get(s.size() - 1)[LapRecorder.S_T];
        float rMax = 1f;
        for (float[] q : s) {
            for (int k : new int[]{LapRecorder.S_HUD, LapRecorder.S_ACTUAL, LapRecorder.S_PACE, LapRecorder.S_BENCH}) {
                if (!Float.isNaN(q[k])) rMax = Math.max(rMax, q[k]);
            }
        }
        rMax = niceCeil(rMax * 1.05f);
        line(ps, s, LapRecorder.S_HUD, HUD, x0, cy, wl, ch, tMax, rMax);
        line(ps, s, LapRecorder.S_BENCH, BENCH, x0, cy, wl, ch, tMax, rMax);
        line(ps, s, LapRecorder.S_PACE, PACE, x0, cy, wl, ch, tMax, rMax);
        line(ps, s, LapRecorder.S_ACTUAL, ACTUAL, x0, cy, wl, ch, tMax, rMax);
        f.drawShadow(ps, String.format(Locale.ROOT, "%,.0f/min", rMax), x0 + 2, cy + 2, 0x808080);
        f.drawShadow(ps, String.format(Locale.ROOT, "%.0f min", tMax), x0 + wl - 2 - f.width(String.format(Locale.ROOT, "%.0f min", tMax)), cy + ch + 2, 0x808080);
        f.drawShadow(ps, "0", x0, cy + ch + 2, 0x808080);

        int xr = x0 + wl + 12;
        float dMax = 1f, cMax = 1f;
        for (float[] q : s) {
            if (!Float.isNaN(q[LapRecorder.S_DENSITY])) dMax = Math.max(dMax, q[LapRecorder.S_DENSITY]);
            if (!Float.isNaN(q[LapRecorder.S_CLUMP])) cMax = Math.max(cMax, q[LapRecorder.S_CLUMP]);
        }
        dMax = niceCeil(dMax * 1.05f);
        cMax = niceCeil(cMax * 1.05f);
        line(ps, s, LapRecorder.S_DENSITY, DENSITY, xr, cy, wr, ch, tMax, dMax);
        line(ps, s, LapRecorder.S_CLUMP, CLUMP, xr, cy, wr, ch, tMax, cMax);
        f.drawShadow(ps, String.format(Locale.ROOT, "%.0f", dMax), xr + 2, cy + 2, DENSITY & 0xFFFFFF);
        String cm = String.format(Locale.ROOT, "%.0f", cMax);
        f.drawShadow(ps, cm, xr + wr - 2 - f.width(cm), cy + 2, CLUMP & 0xFFFFFF);

        boolean inL = mx >= x0 && mx < x0 + wl && my >= cy && my < cy + ch;
        boolean inR = mx >= xr && mx < xr + wr && my >= cy && my < cy + ch;
        if (!inL && !inR) return null;
        float t = inL ? (mx - x0) / (float) wl * tMax : (mx - xr) / (float) wr * tMax;
        float[] q = s.get(0);
        for (float[] p : s) if (Math.abs(p[LapRecorder.S_T] - t) < Math.abs(q[LapRecorder.S_T] - t)) q = p;
        int gx = inL ? x0 + Math.round(q[LapRecorder.S_T] / tMax * wl) : xr + Math.round(q[LapRecorder.S_T] / tMax * wr);
        fill(ps, gx, cy, gx + 1, cy + ch, 0x60FFFFFF);
        return String.format(Locale.ROOT, "%.1f min: actual %s, your pace %s, benchmark %s, active (HUD) %s /min; density %s, clumpiness %s",
                q[LapRecorder.S_T], num(q[LapRecorder.S_ACTUAL]), num(q[LapRecorder.S_PACE]), num(q[LapRecorder.S_BENCH]),
                num(q[LapRecorder.S_HUD]), num(q[LapRecorder.S_DENSITY]), num(q[LapRecorder.S_CLUMP]));
    }

    /** A polyline from filled 1-pixel columns (skips NaN samples). */
    private static void line(PoseStack ps, List<float[]> s, int k, int color, int x, int y, int w, int h, float tMax, float vMax) {
        int px = -1, py = -1;
        for (float[] q : s) {
            float v = q[k];
            if (Float.isNaN(v) || tMax <= 0) {
                px = -1;
                continue;
            }
            int cx = x + Math.round(q[LapRecorder.S_T] / tMax * (w - 1));
            int cy = y + h - 1 - Math.round(Mth.clamp(v / vMax, 0f, 1f) * (h - 1));
            if (px < 0) {
                fill(ps, cx, cy, cx + 1, cy + 1, color);
            } else {
                for (int xx = px; xx <= cx; xx++) {
                    float a = cx == px ? 1f : (xx - px) / (float) (cx - px);
                    int yy = Math.round(py + a * (cy - py));
                    int ya = xx == px ? py : Math.round(py + (xx - 1 - px) / (float) Math.max(1, cx - px) * (cy - py));
                    fill(ps, xx, Math.min(ya, yy), xx + 1, Math.max(ya, yy) + 1, color);
                }
            }
            px = cx;
            py = cy;
        }
    }

    /** Round up to 1, 2 or 5 times a power of ten. */
    private static float niceCeil(float v) {
        if (!(v > 0)) return 1f;
        double p = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[]{1, 2, 5, 10}) if (m * p >= v) return (float) (m * p);
        return (float) (10 * p);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= x0() && mouseX <= x0() + W && mouseY >= listTop() && mouseY < listBottom()) {
            int y = listTop() - (int) scroll;
            for (int i = 0; i < laps.size(); i++) {
                if (mouseY >= y && mouseY < y + ROW_H) {
                    if (!open.remove(i)) open.add(i);
                    clampScroll();
                    return true;
                }
                y += ROW_H + (open.contains(i) ? CHART_H : 0);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void clampScroll() {
        double max = Math.max(0, contentHeight() - (listBottom() - listTop()));
        scroll = Mth.clamp(scroll, 0, max);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll -= delta * ROW_H * 2;
        clampScroll();
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
    }
}
