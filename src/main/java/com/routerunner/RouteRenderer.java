package com.routerunner;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.routerunner.solver.P;
import com.routerunner.solver.RoutePlan;
import com.routerunner.solver.RoutePlanner;
import com.routerunner.solver.WalkGraph;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelLastEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Fallback overlay for a room whose lane plan failed: the waypoint route from the player through the next
 * {@link #LOOKAHEAD} waypoints to the exit,
 * as depth-test-off quads. The current leg is red; later edges are coloured by mode (green walk, amber drop,
 * purple trident, cyan sprint, dim grey gap), and turnaround approaches are white (over red too). Waypoints get
 * a filled box and numbered label (current pink, next neon blue, further dark blue, exit orange); turnaround
 * waypoints also get a white arrow showing the direction the route leaves.
 */
@Mod.EventBusSubscriber(modid = Routerunner.MOD_ID, value = Dist.CLIENT)
public final class RouteRenderer {
    /** Waypoints drawn ahead of the cursor. */
    private static final int LOOKAHEAD = 5;
    /** Half-width of the path ribbon (blocks). */
    private static final float RIBBON_HALF = 0.12f;
    /** Current-target label colour; shared with the HUD indicator. */
    static final int NEXT_LABEL_COLOR = 0xFFFF3FB0;
    private static final int SECOND_LABEL_COLOR = 0xFF33CCFF;
    private static final int UPCOMING_LABEL_COLOR = 0xFF3A5AA0;
    private static final int EXIT_LABEL_COLOR = 0xFFFFB030;
    /** Squared distance from the current leg's start beyond which a connector is drawn from the player. */
    private static final double DETACH_DIST_SQ = 25.0;
    /** Turnaround arrow shaft length (blocks). */
    private static final double MARKER_LEN = 3.0;
    /** Turnaround arrowhead barb length (blocks). */
    private static final double MARKER_BARB_LEN = 0.9;
    /** Angle of each barb back from the shaft (degrees). */
    private static final double MARKER_BARB_DEG = 30.0;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelLastEvent event) {
        Minecraft mc = Minecraft.getInstance();
        RouterunnerConfig cfg = RouterunnerConfig.get();
        if (!cfg.enabled || !cfg.routingEnabled) return;
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;

        RouteService.SolvedRoute sr = RouteService.current();
        if (sr == null) return;
        if (sr.lane != null) return;
        RoutePlan plan = sr.plan;
        if (plan.path == null || plan.path.isEmpty() || plan.pathMode == null) return;
        int size = plan.waypoints.size();
        int cursor = sr.cursor;
        int lookahead = LOOKAHEAD;
        boolean done = cursor >= size;
        P exit = sr.exitLocal;
        boolean exitShown = exit != null && (done || (size - cursor) <= lookahead);

        P curTarget = done ? exit : (sr.retargetPos != null ? sr.retargetPos : plan.waypoints.get(cursor).pos);
        boolean retarget = !done && curTarget != null && sr.retargetPos != null;

        int last = plan.path.size() - 1;
        int segStartIdx = done
                ? (size > 0 ? clamp(plan.waypoints.get(size - 1).pathIndex, 0, last) : 0)
                : (cursor > 0 ? clamp(plan.waypoints.get(cursor - 1).pathIndex, 0, last) : 0);
        int nextWpIdx = done ? segStartIdx : clamp(plan.waypoints.get(cursor).pathIndex, 0, last);
        int endIdx = exitShown ? last
                : clamp(plan.waypoints.get(Math.min(size - 1, cursor + lookahead - 1)).pathIndex, segStartIdx, last);

        // far from the current leg's start: draw a connector from the player instead of the stale approach
        Vec3 pp = mc.player.position();
        P segStart = plan.path.get(clamp(segStartIdx, 0, last));
        double sdx = pp.x - (sr.ox + segStart.x() + 0.5), sdy = pp.y - (sr.oy + segStart.y()), sdz = pp.z - (sr.oz + segStart.z() + 0.5);
        boolean detached = !done && cursor < size
                && (retarget || (sdx * sdx + sdy * sdy + sdz * sdz) > DETACH_DIST_SQ);
        int drawFrom = detached ? nextWpIdx : segStartIdx;

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = ps.last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // when done, the planned exit leg is skipped in favour of the player→exit connector below
        for (int i = drawFrom; !done && i < endIdx && i + 1 < plan.path.size(); i++) {
            char m = plan.pathMode[i];
            float r, g, b, a = 0.9f;
            if (m == 'x') { r = 0.6f; g = 0.6f; b = 0.6f; a = 0.35f; }
            else if (m == 'd') { r = 1.0f; g = 0.65f; b = 0.10f; }
            else if (plan.pathWhite != null && plan.pathWhite[i]) { r = 1.0f; g = 1.0f; b = 1.0f; }
            else if (i < nextWpIdx) { // current leg: red walk, magenta trident, bright cyan sprint
                if (m == 't') { r = 1.0f; g = 0.10f; b = 0.90f; }
                else if (m == 's') { r = 0.25f; g = 1.0f; b = 1.0f; }
                else { r = 1.0f; g = 0.12f; b = 0.10f; }
            }
            else if (m == 'w') { r = 0.25f; g = 1.0f; b = 0.35f; }
            else if (m == 's') { r = 0.10f; g = 0.85f; b = 1.0f; }
            else { r = 0.72f; g = 0.20f; b = 1.0f; }
            ribbon(buf, mat, cam, sr, plan.path.get(i), plan.path.get(i + 1), r, g, b, a);
        }

        // connector: straight line to a retarget chest; otherwise a graph path to the waypoint/exit, else straight
        if (retarget) {
            ribbonWorld(buf, mat, cam, pp.x, pp.y + 0.5, pp.z,
                    sr.ox + curTarget.x() + 0.5, sr.oy + curTarget.y() + 0.7, sr.oz + curTarget.z() + 0.5,
                    1.0f, 0.12f, 0.10f, 0.9f, RIBBON_HALF);
        } else if (detached || (done && exit != null)) {
            P goal = done ? exit : plan.waypoints.get(cursor).pos;
            int key = done ? -2 : cursor;
            List<P> conn = connectorPath(sr, goal, key, pp);
            if (conn != null && conn.size() >= 2) {
                for (int i = 0; i + 1 < conn.size(); i++) {
                    ribbon(buf, mat, cam, sr, conn.get(i), conn.get(i + 1), 1.0f, 0.12f, 0.10f, 0.9f);
                }
            } else {
                ribbonWorld(buf, mat, cam, pp.x, pp.y + 0.5, pp.z,
                        sr.ox + goal.x() + 0.5, sr.oy + goal.y() + 0.7, sr.oz + goal.z() + 0.5,
                        1.0f, 0.12f, 0.10f, 0.9f, RIBBON_HALF);
            }
        }

        // thin orange tracer from just below the crosshair to the current target
        if (curTarget != null) {
            var camObj = mc.gameRenderer.getMainCamera();
            Vec3 look = new Vec3(camObj.getLookVector());
            Vec3 up = new Vec3(camObj.getUpVector());
            ribbonWorld(buf, mat, cam,
                    cam.x + look.x * 0.6 - up.x * 0.35, cam.y + look.y * 0.6 - up.y * 0.35, cam.z + look.z * 0.6 - up.z * 0.35,
                    sr.ox + curTarget.x() + 0.5, sr.oy + curTarget.y() + 0.5, sr.oz + curTarget.z() + 0.5,
                    1.0f, 0.60f, 0.10f, 0.6f, 0.025f);
        }

        int boxEnd = Math.min(size, cursor + lookahead);
        for (int k = cursor; k < boxEnd; k++) {
            int rel = k - cursor;
            RoutePlan.WP wp = plan.waypoints.get(k);
            P bp = (rel == 0 && curTarget != null) ? curTarget : wp.pos;
            if (rel == 0) cube(buf, mat, sr, bp, 1.0f, 0.15f, 0.70f, 0.9f);
            else if (rel == 1) cube(buf, mat, sr, bp, 0.15f, 0.80f, 1.0f, 0.95f);
            else cube(buf, mat, sr, bp, 0.20f, 0.25f, 0.60f, 0.40f);
            if (wp.turnaround) turnaroundMarker(buf, mat, cam, sr, wp);
        }
        if (exitShown) cube(buf, mat, sr, exit, 1.0f, 0.55f, 0.1f, done ? 0.85f : 0.5f);
        tess.end();

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        ps.popPose();

        Font font = mc.font;
        MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
        for (int k = cursor; k < boxEnd; k++) {
            int rel = k - cursor;
            int color = rel == 0 ? NEXT_LABEL_COLOR : rel == 1 ? SECOND_LABEL_COLOR : UPCOMING_LABEL_COLOR;
            P lp = (rel == 0 && curTarget != null) ? curTarget : plan.waypoints.get(k).pos;
            drawLabel(mc, font, bs, ps, cam, sr, lp, String.valueOf(rel + 1), color);
        }
        if (exitShown) drawLabel(mc, font, bs, ps, cam, sr, exit, "EXIT", EXIT_LABEL_COLOR);
        bs.endBatch();
    }

    /**
     * Walkable path (local coords) between the player and a target via the plan's graph, or null if none.
     * The Dijkstra from the target is cached on {@code sr} per {@code cacheKey}.
     */
    private static List<P> connectorPath(RouteService.SolvedRoute sr, P targetLocal, int cacheKey, Vec3 pp) {
        RoutePlan plan = sr.plan;
        if (plan.graph == null || targetLocal == null) return null;
        if (sr.connCursor != cacheKey) {
            int tn = plan.graph.nearestNode(targetLocal, RoutePlanner.ACCESS_RADIUS);
            sr.connDijk = tn >= 0 ? plan.graph.dijkstra(tn) : null;
            sr.connCursor = cacheKey;
        }
        if (sr.connDijk == null) return null;
        int fx = (int) Math.floor(pp.x) - sr.ox, fy = (int) Math.floor(pp.y) - sr.oy, fz = (int) Math.floor(pp.z) - sr.oz;
        // project down up to 5 blocks (player may be mid-air), else nearest node within 5
        int pn = -1;
        for (int k = 0; k <= 5 && pn < 0; k++) pn = plan.graph.nearestNode(new P(fx, fy - k, fz), 0);
        if (pn < 0) pn = plan.graph.nearestNode(new P(fx, fy, fz), 5);
        if (pn < 0 || WalkGraph.nodeDist(sr.connDijk, pn) == Integer.MAX_VALUE) return null;
        return plan.graph.pathToNode(sr.connDijk, pn);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void v(BufferBuilder buf, Matrix4f mat, double x, double y, double z, float r, float g, float b, float a) {
        buf.vertex(mat, (float) x, (float) y, (float) z).color(r, g, b, a).endVertex();
    }

    private static void quad(BufferBuilder buf, Matrix4f mat,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             float r, float g, float b, float a) {
        v(buf, mat, x1, y1, z1, r, g, b, a);
        v(buf, mat, x2, y2, z2, r, g, b, a);
        v(buf, mat, x3, y3, z3, r, g, b, a);
        v(buf, mat, x4, y4, z4, r, g, b, a);
    }

    /** White arrow above a turnaround waypoint pointing the way the route leaves it. */
    private static void turnaroundMarker(BufferBuilder buf, Matrix4f mat, Vec3 cam, RouteService.SolvedRoute sr,
                                         RoutePlan.WP wp) {
        double len = Math.hypot(wp.outDirX, wp.outDirZ);
        if (len < 1.0e-6) return;
        double ux = wp.outDirX / len, uz = wp.outDirZ / len;
        double x0 = sr.ox + wp.pos.x() + 0.5, y0 = sr.oy + wp.pos.y() + 1.5, z0 = sr.oz + wp.pos.z() + 0.5;
        double tipX = x0 + ux * MARKER_LEN, tipZ = z0 + uz * MARKER_LEN;
        ribbonWorld(buf, mat, cam, x0, y0, z0, tipX, y0, tipZ, 1.0f, 1.0f, 1.0f, 0.9f, RIBBON_HALF);
        double c = Math.cos(Math.toRadians(MARKER_BARB_DEG)), s = Math.sin(Math.toRadians(MARKER_BARB_DEG));
        for (int side = -1; side <= 1; side += 2) { // barbs: -outDir rotated MARKER_BARB_DEG each way
            double bx = -ux * c - side * (-uz) * s, bz = -uz * c + side * (-ux) * s;
            ribbonWorld(buf, mat, cam, tipX, y0, tipZ,
                    tipX + bx * MARKER_BARB_LEN, y0, tipZ + bz * MARKER_BARB_LEN,
                    1.0f, 1.0f, 1.0f, 0.9f, RIBBON_HALF);
        }
    }

    /** Camera-facing flat ribbon between two local cell points. */
    private static void ribbon(BufferBuilder buf, Matrix4f mat, Vec3 cam, RouteService.SolvedRoute sr, P a, P b,
                               float r, float g, float bl, float al) {
        ribbonWorld(buf, mat, cam,
                sr.ox + a.x() + 0.5, sr.oy + a.y() + 0.7, sr.oz + a.z() + 0.5,
                sr.ox + b.x() + 0.5, sr.oy + b.y() + 0.7, sr.oz + b.z() + 0.5,
                r, g, bl, al, RIBBON_HALF);
    }

    /** Camera-facing flat ribbon between two world points at the given half-width. */
    private static void ribbonWorld(BufferBuilder buf, Matrix4f mat, Vec3 cam,
                                    double ax, double ay, double az, double bx, double by, double bz,
                                    float r, float g, float bl, float al, float half) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double mx = (ax + bx) * 0.5 - cam.x, my = (ay + by) * 0.5 - cam.y, mz = (az + bz) * 0.5 - cam.z;
        double px = dy * mz - dz * my, py = dz * mx - dx * mz, pz = dx * my - dy * mx;
        double pl = Math.sqrt(px * px + py * py + pz * pz);
        if (pl < 1.0e-6) return;
        px = px / pl * half;
        py = py / pl * half;
        pz = pz / pl * half;
        quad(buf, mat, ax + px, ay + py, az + pz, bx + px, by + py, bz + pz, bx - px, by - py, bz - pz, ax - px, ay - py, az - pz, r, g, bl, al);
    }

    /** Filled cube around a target chest. */
    private static void cube(BufferBuilder buf, Matrix4f mat, RouteService.SolvedRoute sr, P p, float r, float g, float b, float a) {
        double x0 = sr.ox + p.x() - 0.05, y0 = sr.oy + p.y() - 0.05, z0 = sr.oz + p.z() - 0.05;
        double x1 = sr.ox + p.x() + 1.05, y1 = sr.oy + p.y() + 1.05, z1 = sr.oz + p.z() + 1.05;
        quad(buf, mat, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        quad(buf, mat, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        quad(buf, mat, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        quad(buf, mat, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
        quad(buf, mat, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
        quad(buf, mat, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
    }

    /** Billboarded label above a local cell point, drawn through walls. */
    private static void drawLabel(Minecraft mc, Font font, MultiBufferSource bs, PoseStack ps, Vec3 cam,
                                  RouteService.SolvedRoute sr, P p, String text, int color) {
        double wx = sr.ox + p.x() + 0.5, wy = sr.oy + p.y() + 1.7, wz = sr.oz + p.z() + 0.5;
        ps.pushPose();
        ps.translate(wx - cam.x, wy - cam.y, wz - cam.z);
        ps.mulPose(mc.gameRenderer.getMainCamera().rotation());
        ps.scale(-0.07f, -0.07f, 0.07f);
        Matrix4f m = ps.last().pose();
        float w = -font.width(text) / 2.0f;
        font.drawInBatch(text, w, 0, color, false, m, bs, true, 0, 0xF000F0);
        ps.popPose();
    }

    private RouteRenderer() {}
}
