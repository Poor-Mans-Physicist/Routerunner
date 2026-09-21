package com.routerunner;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Live weight-tuning menu: every routing weight as a slider (0 to 2x its default, or a fixed cap), grouped into
 * category tabs. Changes apply on the next room solve; "Reset all" restores the defaults.
 */
public class WeightsScreen extends Screen {
    private static final String[] CATS = {"Bail", "Move", "Open", "Cluster", "Shafts", "Sprint"};

    private final Screen parent;
    private final List<WeightDef> defs = new ArrayList<>();
    private final List<WeightSlider> shown = new ArrayList<>();
    private int tab = 0;

    public WeightsScreen(Screen parent) {
        super(new TextComponent("Routerunner — Weights"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        buildDefs();
        showTab(tab);
    }

    private void showTab(int t) {
        tab = t;
        this.clearWidgets();
        shown.clear();

        int contentW = Math.min(this.width - 20, 440);
        int x0 = (this.width - contentW) / 2;
        int tabW = contentW / 4 - 2;

        for (int i = 0; i < CATS.length; i++) {
            int row = i / 4;
            int col = i % 4;
            int bx = x0 + col * (tabW + 2);
            int by = 28 + row * 22;
            int ci = i;
            Button tb = new Button(bx, by, tabW, 20, new TextComponent(CATS[i]), b -> showTab(ci));
            tb.active = (i != tab);
            this.addRenderableWidget(tb);
        }

        int y = 78;
        for (WeightDef d : defs) {
            if (d.cat != tab) continue;
            WeightSlider s = new WeightSlider(x0, y, contentW, 20, d.name, d.desc, d.min, d.max, d.def, d.isInt, d.get, d.set);
            this.addRenderableWidget(s);
            shown.add(s);
            y += 24;
        }

        int third = (contentW - 8) / 3;
        int by = this.height - 28;
        this.addRenderableWidget(new Button(x0, by, third, 20,
                new TextComponent("Reset all to defaults"), b -> { for (WeightSlider s : allSliders()) s.resetToDefault(); showTab(tab); }));
        this.addRenderableWidget(new Button(x0 + third + 4, by, third, 20,
                new TextComponent("Reset adaptive"), b -> {
            AdaptiveWeights.get().reset();
            b.setMessage(new TextComponent("Adaptive reset ✓"));
        }));
        this.addRenderableWidget(new Button(x0 + 2 * (third + 4), by, contentW - 2 * (third + 4), 20,
                new TextComponent("Done"), b -> this.onClose()));
    }

    /** Throwaway sliders for every weight on every tab, bound to the live setters, so all can be reset. */
    private List<WeightSlider> allSliders() {
        List<WeightSlider> all = new ArrayList<>();
        for (WeightDef d : defs) {
            all.add(new WeightSlider(0, 0, 10, 10, d.name, d.desc, d.min, d.max, d.def, d.isInt, d.get, d.set));
        }
        return all;
    }

    @Override
    public void render(PoseStack ps, int mx, int my, float pt) {
        this.renderBackground(ps);
        drawCenteredString(ps, this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        super.render(ps, mx, my, pt);
        for (WeightSlider s : shown) {
            if (s.visible && s.isHoveredOrFocused()) {
                this.renderComponentTooltip(ps, s.tooltip(), mx, my);
                break;
            }
        }
    }

    @Override
    public void onClose() {
        RouterunnerConfig.save();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Builds the weight catalogue: sliders bind to the live config, defaults come from a fresh config. */
    private void buildDefs() {
        defs.clear();
        RouterunnerConfig c = RouterunnerConfig.get();
        RouterunnerConfig d = new RouterunnerConfig();
        // Bail
        def(0, "Bail aggression", "Skim to the densest clusters; higher = fewer, richer stops (raises chests/min if hallways are cheap).", d.bailAggression, false, () -> c.bailAggression, v -> c.bailAggression = v);
        defMax(0, "Manual bail", "Absolute leave-threshold (chests per travel cost). 0 = auto (aggression x hot-spot rate).", 1.0, d.bail, false, () -> c.bail, v -> c.bail = v);
        // Movement
        def(1, "Up cost", "Cost per block climbed. Measured near-free (a 1-block step-up costs no time) — a tie-breaker, not a penalty.", d.upCost, false, () -> c.upCost, v -> c.upCost = v);
        def(1, "Down cost", "Penalty per block dropped — dropping is easy/cheap.", d.downCost, false, () -> c.downCost, v -> c.downCost = v);
        def(1, "Turn weight (order)", "Penalty per radian when ordering the stops — routes prefer chests ahead, not off-axis.", d.turnWeight, false, () -> c.turnWeight, v -> c.turnWeight = v);
        defMax(1, "Turn open factor", "How much of the turn cost still applies in open rooms (keeps open tours from zig-zagging).", 1.0, d.turnOpenFactor, false, () -> c.turnOpenFactor, v -> c.turnOpenFactor = v);
        def(1, "Path turn cost", "Straightens the walked path itself (per radian of heading change). 0 = off (bouncier).", d.pathTurnWeight, false, () -> c.pathTurnWeight, v -> c.pathTurnWeight = v);
        def(1, "Break reach", "How close you must be to break a chest as you follow the route. Lower = more head-on, less craning.", d.breakReach, false, () -> c.breakReach, v -> c.breakReach = v);
        def(1, "Waypoint overhead", "Fixed cost of stopping at a waypoint at all (measured 0.45-0.55s = ~8 blocks). Higher = fewer, denser stops.", d.waypointOverhead, false, () -> c.waypointOverhead, v -> c.waypointOverhead = v);
        defMax(1, "Turnaround angle", "DISPLAY ONLY — no cost term reads it, so it never moves the route. Bends sharper than this are drawn as a turnaround: white approach + a white exit arrow. Lower = more of them flagged.", 180, d.turnaroundDeg, true, () -> c.turnaroundDeg, v -> c.turnaroundDeg = (int) v);
        // Openness / clearance
        def(2, "Tight mult", "Cost per block in a sub-3-wide gap (clearance 0-1), vs 1.0 in the open. Measured 3.9x.", d.tightMult, false, () -> c.tightMult, v -> c.tightMult = v);
        def(2, "Narrow mult", "Cost per block in a narrow corridor (clearance 2-3), vs 1.0 in the open. Measured 1.6x.", d.narrowMult, false, () -> c.narrowMult, v -> c.narrowMult = v);
        def(2, "Mid mult", "Cost per block in moderate space (clearance 4-6), vs 1.0 in the open. Measured 1.2x.", d.midMult, false, () -> c.midMult, v -> c.midMult = v);
        def(2, "Clearance min", "Anchor for the openness blend (turn factor, proximity radius). Not a cost term.", d.clearanceMin, true, () -> c.clearanceMin, v -> c.clearanceMin = (int) v);
        def(2, "Open sat clearance", "Clearance at which the openness blend saturates. Not a cost term.", d.openSatClearance, true, () -> c.openSatClearance, v -> c.openSatClearance = (int) v);
        // Clustering
        def(3, "Core penalty", "Prefer easy edge chests over ones walled in by other chests. Measured 0 — the chain fills better from cluster centres; edge preference costs chests per trigger.", d.corePenaltyWeight, false, () -> c.corePenaltyWeight, v -> c.corePenaltyWeight = v);
        def(3, "Proximity bonus", "Breaks packed right together are near-free — one swing clears several (0-1).", d.proximityBonus, false, () -> c.proximityBonus, v -> c.proximityBonus = v);
        def(3, "Proximity radius", "Range of the proximity discount in tight/normal space.", d.proximityRadius, false, () -> c.proximityRadius, v -> c.proximityRadius = v);
        def(3, "Proximity radius (open)", "Range of the proximity discount in wide-open space.", d.proximityRadiusOpen, false, () -> c.proximityRadiusOpen, v -> c.proximityRadiusOpen = v);
        def(3, "Above-path penalty", "Penalty per block a chest sits 3+ above the path (aim-up corner chests).", d.abovePathWeight, false, () -> c.abovePathWeight, v -> c.abovePathWeight = v);
        def(3, "Enclosure penalty", "Penalty for near-buried chests (5+ solid faces incl. other chests).", d.enclosureWeight, false, () -> c.enclosureWeight, v -> c.enclosureWeight = v);
        // Shafts and trident dashes
        def(4, "Trident base cost", "Flat cost of one dash. Measured ~2.4s per shaft leg = ~40 open-walk blocks, so it only fires for a real vertical save.", d.tridentActionCost, false, () -> c.tridentActionCost, v -> c.tridentActionCost = v);
        def(4, "Trident dist weight", "Tiny per-block cost of a dash (distance is otherwise free).", d.tridentDistWeight, false, () -> c.tridentDistWeight, v -> c.tridentDistWeight = v);
        def(4, "Trident min dist", "Don't dash for hops shorter than this — just walk them.", d.tridentMinDist, false, () -> c.tridentMinDist, v -> c.tridentMinDist = v);
        def(4, "Shaft min vertical", "Minimum vertical span for a shaft to count as a (generously-capped) vertical shaft.", d.shaftMinVertical, true, () -> c.shaftMinVertical, v -> c.shaftMinVertical = (int) v);
        def(4, "Shaft max length", "Longest a single shaft dash edge will span.", d.shaftMaxLen, false, () -> c.shaftMaxLen, v -> c.shaftMaxLen = v);
        def(4, "Shaft min saving", "Minimum travel a vertical shaft must save to be kept.", d.shaftMinSaving, false, () -> c.shaftMinSaving, v -> c.shaftMinSaving = v);
        def(4, "Shaft min saving (horiz)", "Minimum travel a shallow/horizontal shaft must save (a higher bar).", d.shaftMinSavingHoriz, false, () -> c.shaftMinSavingHoriz, v -> c.shaftMinSavingHoriz = v);
        def(4, "Shaft cap (vertical)", "Max vertical shafts kept per room.", d.shaftCapVertical, true, () -> c.shaftCapVertical, v -> c.shaftCapVertical = (int) v);
        defMax(4, "Shaft cap (horizontal)", "Max horizontal/shallow shafts kept (0 = off; they made tours teleport sideways).", 16, d.shaftCapHoriz, true, () -> c.shaftCapHoriz, v -> c.shaftCapHoriz = (int) v);
        // Drops
        def(4, "Drop base cost", "Flat cost of committing to a fall. Near-free: you keep full speed either side of it.", d.dropActionCost, false, () -> c.dropActionCost, v -> c.dropActionCost = v);
        def(4, "Drop height weight", "Cost per sqrt(block) fallen (fall time). Higher = the route prefers stairs over big drops.", d.dropHeightWeight, false, () -> c.dropHeightWeight, v -> c.dropHeightWeight = v);
        def(4, "Drop max height", "Tallest fall the route will send you off. Below 4 = no drops at all (stairs and dashes only).", d.dropMaxHeight, true, () -> c.dropMaxHeight, v -> c.dropMaxHeight = (int) v);
        // Sprint lines
        def(5, "Sprint cost", "Cost per block of a sprint line (open walk = 1.0). Measured 0.53. LOWER = the route leans on long sprint-jump lines more.", d.openSprintWeight, false, () -> c.openSprintWeight, v -> c.openSprintWeight = v);
        def(5, "Sprint min dist", "Shortest hop that becomes a sprint line. Lower = more (and shorter) sprint lines.", d.openSprintMinDist, false, () -> c.openSprintMinDist, v -> c.openSprintMinDist = v);
        def(5, "Sprint min clear", "Open space (walls only) both ends need for a sprint line. Lower = sprints fire in tighter spots.", d.openSprintMinClear, true, () -> c.openSprintMinClear, v -> c.openSprintMinClear = (int) v);
        def(5, "Sprint max rise", "Biggest height change a sprint line may span (it's a sprint-jump, not a climb).", d.openSprintMaxRise, true, () -> c.openSprintMaxRise, v -> c.openSprintMaxRise = (int) v);
    }

    /** Weight whose slider spans 0 .. 2x its default. */
    private void def(int cat, String name, String desc, double def, boolean isInt, DoubleSupplier g, DoubleConsumer s) {
        double max = def > 0 ? def * 2 : 1.0;
        defs.add(new WeightDef(cat, name, desc, 0, max, def, isInt, g, s));
    }

    /** Weight with an explicit max (for zero-default weights, where 2x default would be a useless 0). */
    private void defMax(int cat, String name, String desc, double max, double def, boolean isInt, DoubleSupplier g, DoubleConsumer s) {
        defs.add(new WeightDef(cat, name, desc, 0, max, def, isInt, g, s));
    }

    private static final class WeightDef {
        final int cat;
        final String name, desc;
        final double min, max, def;
        final boolean isInt;
        final DoubleSupplier get;
        final DoubleConsumer set;

        WeightDef(int cat, String name, String desc, double min, double max, double def, boolean isInt, DoubleSupplier get, DoubleConsumer set) {
            this.cat = cat;
            this.name = name;
            this.desc = desc;
            this.min = min;
            this.max = max;
            this.def = def;
            this.isInt = isInt;
            this.get = get;
            this.set = set;
        }
    }
}
