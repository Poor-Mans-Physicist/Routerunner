package com.routerunner.lane;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.routerunner.RouteService;
import com.routerunner.Routerunner;
import com.routerunner.RouterunnerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelLastEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;

/**
 * Draws the lane route. The run being followed gets a floor carpet (depth-tested, so walls hide it), a centreline
 * whose {@link LaneRoute#HIGHLIGHT_BLOCKS} ahead of the progress pointer are drawn wide and orange with chevrons,
 * the carpet under that window orange too (the part already walked is dim), the next run in grey, the exit walk in
 * green. Any segment that climbs or drops
 * two or more blocks per block walked is purple, and every shaft (a 4+ block drop or climb, or a flight) carries a
 * large floating purple arrow through the space where the vertical move happens. When the player is off the lane a
 * thin red tracer leads from their feet to the pointer. The heatmap boxes every unbroken chest the current run can
 * reach (the next run's too, fainter), bright pink for the least valuable through hot red for the best, and the
 * chests inside the pointer window, the cluster being hit or hit next, get a bright green wireframe. Under Vein Miner,
 * the chests of a big touching group that the run only sees a corner of get a thicker deep-blue wireframe instead
 * (priority breaks, see {@link LaneRoute.Run#priority}). Where the next run heads back the way the current one came,
 * a yellow U-turn arc, tail and arrowhead mark the turn at the junction, full strength once the player is close.
 */
@Mod.EventBusSubscriber(modid = Routerunner.MOD_ID, value = Dist.CLIENT)
public final class LaneRenderer {
    private static final float[] POINT_COL = {0.13f, 0.89f, 1.0f};
    private static final float[] POINT_HOT = {1.0f, 0.55f, 0.05f};
    private static final float[] CORRIDOR_COL = {1.0f, 0.62f, 0.10f};
    private static final float[] CORRIDOR_HOT = {1.0f, 0.40f, 0.0f};
    private static final float[] EXIT_COL = {0.35f, 1.0f, 0.45f};
    private static final float[] EXIT_HOT = {0.0f, 0.85f, 0.25f};
    private static final float[] NEXT_COL = {0.62f, 0.62f, 0.62f};
    private static final float[] SHAFT_COL = {0.66f, 0.22f, 1.0f};
    private static final float[] TRACER_COL = {1.0f, 0.20f, 0.20f};
    private static final float[] HEAT_COLD = {1.0f, 0.25f, 0.69f};
    private static final float[] HEAT_HOT = {1.0f, 0.05f, 0.05f};
    private static final float[] TARGET_COL = {0.15f, 1.0f, 0.30f};
    private static final float TARGET_HALF = 0.035f;
    private static final float[] PRIORITY_COL = {0.10f, 0.30f, 1.0f};
    private static final float PRIORITY_HALF = 0.06f;
    private static final float[] UTURN_COL = {1.0f, 0.92f, 0.20f};
    private static final float UTURN_HALF = 0.16f;
    private static final double UTURN_RADIUS = 2.0;
    private static final double UTURN_TAIL = 3.0;
    private static final int UTURN_STEPS = 12;
    private static final double UTURN_NEAR = 12.0;
    private static final double TARGET_OUTSET = 0.03;
    private static final float CARPET_LIFT = 0.03f;
    private static final float LINE_LIFT = 0.55f;
    private static final float LINE_HALF = 0.09f;
    private static final float LINE_HALF_HOT = 0.15f;
    private static final float LINE_HALF_DONE = 0.07f;
    private static final float LINE_HALF_TRACER = 0.06f;
    private static final double CHEVRON_EVERY = 3.0;
    private static final double CHEVRON_LEN = 0.9;
    private static final double CHEVRON_HALF = 0.7;
    private static final double HEAT_FADE_DIST = 48.0;
    private static final double ARROW_MIN_LEN = 2.5;
    private static final double ARROW_MAX_LEN = 6.0;
    private static final double ARROW_HEAD_LEN = 1.0;
    private static final double ARROW_HEAD_W = 0.75;
    private static final float ARROW_HALF = 0.13f;

    private LaneRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelLastEvent event) {
        Minecraft mc = Minecraft.getInstance();
        RouterunnerConfig cfg = RouterunnerConfig.get();
        if (!cfg.enabled || !cfg.routingEnabled) return;
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        RouteService.SolvedRoute sr = RouteService.current();
        if (sr == null) return;
        LaneRoute lr = sr.lane;
        if (lr == null || lr.runs.isEmpty()) return;
        LaneRoute.Run cur = lr.shown();
        if (cur == null) return;
        LaneRoute.Run next = lr.next();
        boolean corridor = "corridor".equalsIgnoreCase(lr.mode);
        float[] base = cur.exit ? EXIT_COL : (corridor ? CORRIDOR_COL : POINT_COL);
        float[] hot = cur.exit ? EXIT_HOT : (corridor ? CORRIDOR_HOT : POINT_HOT);
        int prog = Math.min(Math.max(lr.prog, 0), Math.max(cur.poly.size() - 1, 0));
        double bob = 0.15 * Math.sin(System.currentTimeMillis() / 250.0);

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = ps.last().pose();

        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.depthMask(false);
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        RenderSystem.enableDepthTest();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (next != null) carpet(buf, mat, next.carpet, null, NEXT_COL, 0.14f, -1, -1, null);
        carpet(buf, mat, cur.carpet, cur.carpetIdx, base, 0.28f, prog, hotEndIndex(cur.poly, prog), hot);
        tess.end();

        RenderSystem.disableDepthTest();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (next != null) {
            plainLine(buf, mat, next.poly, NEXT_COL, 0.35f, LINE_HALF, false);
            for (LaneRoute.Shaft s : next.shafts) arrow(buf, mat, s, 0.35f, bob);
        }
        progressLine(buf, mat, cur.poly, prog, base, hot);
        for (LaneRoute.Shaft s : cur.shafts) arrow(buf, mat, s, 0.85f, bob);
        if (cur.uturn && cur.uturnIn != null && !cur.poly.isEmpty()) {
            BlockPos j = cur.poly.get(cur.poly.size() - 1);
            boolean near = Math.sqrt(j.distToCenterSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ())) <= UTURN_NEAR;
            uturn(buf, mat, j, cur.uturnIn, cur.uturnSide, near ? 0.95f : 0.45f);
        }
        List<BlockPos> tracer = lr.tracer;
        if (lr.offLane && tracer != null && tracer.size() > 1) {
            plainLine(buf, mat, tracer, TRACER_COL, 0.9f, LINE_HALF_TRACER, false);
            for (LaneRoute.Shaft s : lr.tracerShafts) arrow(buf, mat, s, 0.85f, bob);
        }
        Vec3 pp = mc.player.position();
        heatBoxes(buf, mat, lr.heatNext, pp, 0.45f);
        heatBoxes(buf, mat, lr.heat, pp, 1.0f);
        java.util.Set<BlockPos> prio = lr.priorityTargets;
        for (BlockPos b : lr.targets) if (!prio.contains(b)) wire(buf, mat, b, TARGET_COL, 0.95f, TARGET_HALF);
        for (BlockPos b : prio) wire(buf, mat, b, PRIORITY_COL, 1.0f, PRIORITY_HALF);
        tess.end();

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        ps.popPose();
    }

    /** The heatmap: every chest in the map gets a box, bright pink at 0 through hot red at 1, bigger and denser as it heats. */
    private static void heatBoxes(BufferBuilder buf, Matrix4f mat, Map<BlockPos, Float> heat, Vec3 pp, float alphaScale) {
        float[] c = new float[3];
        for (Map.Entry<BlockPos, Float> e : heat.entrySet()) {
            BlockPos b = e.getKey();
            double d = Math.sqrt(b.distToCenterSqr(pp.x, pp.y, pp.z));
            if (d > HEAT_FADE_DIST) continue;
            float v = Math.max(0f, Math.min(1f, e.getValue()));
            for (int i = 0; i < 3; i++) c[i] = HEAT_COLD[i] + (HEAT_HOT[i] - HEAT_COLD[i]) * v;
            float a = (0.30f + 0.55f * v) * alphaScale;
            box(buf, mat, b, 0.0f, c, a);
        }
    }

    private static int hotEndIndex(List<BlockPos> poly, int prog) {
        return LaneRoute.hotEndIndex(poly, prog);
    }

    /** A bright wireframe around a block: twelve thin bars, each drawn in two planes so it reads from any angle. */
    private static void wire(BufferBuilder buf, Matrix4f mat, BlockPos b, float[] c, float a, float half) {
        double o = TARGET_OUTSET;
        double x0 = b.getX() - o, y0 = b.getY() - o, z0 = b.getZ() - o;
        double x1 = b.getX() + 1 + o, y1 = b.getY() + 1 + o, z1 = b.getZ() + 1 + o;
        double[] nx = {1, 0, 0}, ny = {0, 1, 0}, nz = {0, 0, 1};
        for (double y : new double[]{y0, y1}) {
            for (double z : new double[]{z0, z1}) bar(buf, mat, x0, y, z, x1, y, z, ny, nz, half, c, a);
        }
        for (double x : new double[]{x0, x1}) {
            for (double z : new double[]{z0, z1}) bar(buf, mat, x, y0, z, x, y1, z, nx, nz, half, c, a);
            for (double y : new double[]{y0, y1}) bar(buf, mat, x, y, z0, x, y, z1, nx, ny, half, c, a);
        }
    }

    /**
     * The U-turn cue at a junction {@code j}: a half circle of {@link #UTURN_RADIUS} that leaves along the heading
     * {@code in} and swings to {@code side} (+1 toward (-in.z, in.x)), then a {@link #UTURN_TAIL}-block tail back
     * along -in and an arrowhead, at the centreline's height.
     */
    private static void uturn(BufferBuilder buf, Matrix4f mat, BlockPos j, double[] in, int side, float a) {
        double ix = in[0], iz = in[1];
        double px = -iz * side, pz = ix * side;
        double y = j.getY() + LINE_LIFT + 0.02;
        double jx = j.getX() + 0.5, jz = j.getZ() + 0.5;
        double cx = jx + px * UTURN_RADIUS, cz = jz + pz * UTURN_RADIUS;
        double lx = jx, lz = jz;
        for (int k = 1; k <= UTURN_STEPS; k++) {
            double t = Math.PI * k / UTURN_STEPS;
            double x = cx + UTURN_RADIUS * (-px * Math.cos(t) + ix * Math.sin(t));
            double z = cz + UTURN_RADIUS * (-pz * Math.cos(t) + iz * Math.sin(t));
            ribbon(buf, mat, lx, y, lz, x, y, z, UTURN_HALF, UTURN_COL, a);
            lx = x;
            lz = z;
        }
        double ex = lx - ix * UTURN_TAIL, ez = lz - iz * UTURN_TAIL;
        ribbon(buf, mat, lx, y, lz, ex, y, ez, UTURN_HALF, UTURN_COL, a);
        double hx = -iz, hz = ix;
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            ribbon(buf, mat, ex, y, ez, ex + ix * CHEVRON_LEN + hx * sgn * CHEVRON_HALF, y, ez + iz * CHEVRON_LEN + hz * sgn * CHEVRON_HALF,
                    UTURN_HALF, UTURN_COL, a);
        }
    }

    /**
     * Floor carpet: cells generated by polyline points inside [from, to] are drawn in the hot colour, cells already
     * behind the pointer dimmer, the rest in the base colour.
     */
    private static void carpet(BufferBuilder buf, Matrix4f mat, List<BlockPos> cells, List<Integer> idx, float[] c, float a,
                               int from, int to, float[] hot) {
        for (int i = 0; i < cells.size(); i++) {
            BlockPos b = cells.get(i);
            float[] col = c;
            float alpha = a;
            if (idx != null && hot != null) {
                int j = idx.get(i);
                if (j >= from && j <= to) { col = hot; alpha = 0.55f; }
                else if (j < from) alpha = 0.16f;
            }
            float x = b.getX(), y = b.getY() + CARPET_LIFT, z = b.getZ();
            quad(buf, mat, x, y, z, x + 1, y, z, x + 1, y, z + 1, x, y, z + 1, col, alpha);
        }
    }

    /** One colour for the whole polyline, purple where a segment is steep or a shaft. */
    private static void plainLine(BufferBuilder buf, Matrix4f mat, List<BlockPos> poly, float[] c, float a, float half, boolean chevrons) {
        double acc = 0;
        for (int i = 1; i < poly.size(); i++) {
            BlockPos p = poly.get(i - 1), q = poly.get(i);
            float[] col = LaneRoute.segKind(p, q) == LaneRoute.SEG_FLAT ? c : SHAFT_COL;
            acc = segment(buf, mat, p, q, col, a, half, chevrons, acc);
        }
    }

    /** The current run: dim behind the pointer, vivid and wide with chevrons for the highlight window, normal beyond. */
    private static void progressLine(BufferBuilder buf, Matrix4f mat, List<BlockPos> poly, int prog, float[] base, float[] hot) {
        if (poly.size() < 2) return;
        double[] cum = new double[poly.size()];
        for (int i = 1; i < poly.size(); i++) {
            BlockPos p = poly.get(i - 1), q = poly.get(i);
            cum[i] = cum[i - 1] + Math.hypot(q.getX() - p.getX(), q.getZ() - p.getZ());
        }
        double hotStart = cum[Math.min(prog, cum.length - 1)];
        double hotEnd = hotStart + LaneRoute.HIGHLIGHT_BLOCKS;
        double acc = 0;
        for (int i = 1; i < poly.size(); i++) {
            BlockPos p = poly.get(i - 1), q = poly.get(i);
            double mid = (cum[i - 1] + cum[i]) / 2.0;
            float[] col;
            float a, half;
            boolean chev;
            if (i <= prog || mid < hotStart) { col = base; a = 0.22f; half = LINE_HALF_DONE; chev = false; }
            else if (mid <= hotEnd) { col = hot; a = 1.0f; half = LINE_HALF_HOT; chev = true; }
            else { col = base; a = 0.60f; half = LINE_HALF; chev = false; }
            if (LaneRoute.segKind(p, q) != LaneRoute.SEG_FLAT) col = SHAFT_COL;
            acc = segment(buf, mat, p, q, col, a, half, chev, acc);
        }
    }

    /** One centreline segment with optional chevrons; returns the running distance since the last chevron. */
    private static double segment(BufferBuilder buf, Matrix4f mat, BlockPos p, BlockPos q, float[] c, float a, float half,
                                  boolean chevrons, double acc) {
        double ax = p.getX() + 0.5, ay = p.getY() + LINE_LIFT, az = p.getZ() + 0.5;
        double bx = q.getX() + 0.5, by = q.getY() + LINE_LIFT, bz = q.getZ() + 0.5;
        ribbon(buf, mat, ax, ay, az, bx, by, bz, half, c, a);
        if (!chevrons) return acc;
        double len = Math.hypot(bx - ax, bz - az);
        acc += len;
        if (acc >= CHEVRON_EVERY && len > 1e-6) {
            acc = 0;
            double dx = (bx - ax) / len, dz = (bz - az) / len;
            double px = -dz, pz = dx;
            double tx = bx, ty = by + 0.05, tz = bz;
            ribbon(buf, mat, tx, ty, tz, tx - dx * CHEVRON_LEN + px * CHEVRON_HALF, ty, tz - dz * CHEVRON_LEN + pz * CHEVRON_HALF, half * 1.2f, c, a);
            ribbon(buf, mat, tx, ty, tz, tx - dx * CHEVRON_LEN - px * CHEVRON_HALF, ty, tz - dz * CHEVRON_LEN - pz * CHEVRON_HALF, half * 1.2f, c, a);
        }
        return acc;
    }

    /**
     * A large floating purple arrow through a shaft: a bar along the move with a four-stroke head at the far end,
     * each stroke drawn in two perpendicular planes so it reads from every side; bobbing gently so it is noticed.
     */
    private static void arrow(BufferBuilder buf, Matrix4f mat, LaneRoute.Shaft s, float a, double bob) {
        double ax = s.a.getX() + 0.5, ay = s.a.getY() + 1.0, az = s.a.getZ() + 0.5;
        double bx = s.b.getX() + 0.5, by = s.b.getY() + 1.0, bz = s.b.getZ() + 0.5;
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return;
        double ux = dx / len, uy = dy / len, uz = dz / len;
        double L = Math.max(ARROW_MIN_LEN, Math.min(ARROW_MAX_LEN, len));
        double cx = (ax + bx) / 2, cy = (ay + by) / 2 + bob, cz = (az + bz) / 2;
        double tx = cx + ux * L / 2, ty = cy + uy * L / 2, tz = cz + uz * L / 2;
        double sx = cx - ux * L / 2, sy = cy - uy * L / 2, sz = cz - uz * L / 2;
        double[] n1, n2;
        if (Math.abs(uy) > 0.8) {
            n1 = new double[]{1, 0, 0};
            n2 = new double[]{0, 0, 1};
        } else {
            n1 = new double[]{0, 1, 0};
            double cxp = uy * 0 - uz * 1, cyp = uz * 0 - ux * 0, czp = ux * 1 - uy * 0;
            double cl = Math.sqrt(cxp * cxp + cyp * cyp + czp * czp);
            n2 = cl < 1e-6 ? new double[]{1, 0, 0} : new double[]{cxp / cl, cyp / cl, czp / cl};
        }
        bar(buf, mat, sx, sy, sz, tx, ty, tz, n1, n2, ARROW_HALF, SHAFT_COL, a);
        double hx = tx - ux * ARROW_HEAD_LEN, hy = ty - uy * ARROW_HEAD_LEN, hz = tz - uz * ARROW_HEAD_LEN;
        for (double[] n : new double[][]{n1, n2}) {
            for (int sign = -1; sign <= 1; sign += 2) {
                bar(buf, mat, tx, ty, tz, hx + n[0] * sign * ARROW_HEAD_W, hy + n[1] * sign * ARROW_HEAD_W, hz + n[2] * sign * ARROW_HEAD_W,
                        n1, n2, ARROW_HALF, SHAFT_COL, a);
            }
        }
    }

    /** A stroke from a to b drawn as two quads of half-width h in the planes spanned by n1 and n2. */
    private static void bar(BufferBuilder buf, Matrix4f mat, double ax, double ay, double az, double bx, double by, double bz,
                            double[] n1, double[] n2, float h, float[] c, float a) {
        for (double[] n : new double[][]{n1, n2}) {
            double ox = n[0] * h, oy = n[1] * h, oz = n[2] * h;
            quad(buf, mat, (float) (ax + ox), (float) (ay + oy), (float) (az + oz), (float) (bx + ox), (float) (by + oy), (float) (bz + oz),
                    (float) (bx - ox), (float) (by - oy), (float) (bz - oz), (float) (ax - ox), (float) (ay - oy), (float) (az - oz), c, a);
        }
    }

    /** A flat horizontal ribbon of half-width h between two points (a quad in the plane of the floor). */
    private static void ribbon(BufferBuilder buf, Matrix4f mat, double ax, double ay, double az, double bx, double by, double bz,
                               float h, float[] c, float a) {
        double dx = bx - ax, dz = bz - az;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            bar(buf, mat, ax, ay, az, bx, by, bz, new double[]{1, 0, 0}, new double[]{0, 0, 1}, h, c, a);
            return;
        }
        double px = -dz / len * h, pz = dx / len * h;
        quad(buf, mat, (float) (ax + px), (float) ay, (float) (az + pz), (float) (bx + px), (float) by, (float) (bz + pz),
                (float) (bx - px), (float) by, (float) (bz - pz), (float) (ax - px), (float) ay, (float) (az - pz), c, a);
    }

    /** A translucent cube inset from the block's faces (inset 0 = the full block). */
    private static void box(BufferBuilder buf, Matrix4f mat, BlockPos b, float inset, float[] c, float a) {
        float x0 = b.getX() + inset, y0 = b.getY() + inset, z0 = b.getZ() + inset;
        float x1 = b.getX() + 1 - inset, y1 = b.getY() + 1 - inset, z1 = b.getZ() + 1 - inset;
        quad(buf, mat, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, c, a);
        quad(buf, mat, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, c, a);
        quad(buf, mat, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, c, a);
        quad(buf, mat, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, c, a);
        quad(buf, mat, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, c, a);
        quad(buf, mat, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, c, a);
    }

    private static void quad(BufferBuilder buf, Matrix4f mat, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, float[] c, float a) {
        buf.vertex(mat, x0, y0, z0).color(c[0], c[1], c[2], a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(c[0], c[1], c[2], a).endVertex();
        buf.vertex(mat, x2, y2, z2).color(c[0], c[1], c[2], a).endVertex();
        buf.vertex(mat, x3, y3, z3).color(c[0], c[1], c[2], a).endVertex();
    }
}
