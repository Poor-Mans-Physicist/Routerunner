package com.routerunner.calib;

import com.google.gson.Gson;
import com.routerunner.lane.LaneCli;
import com.routerunner.lane.LegTimeModel;
import com.routerunner.lane.TrailPricer;
import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline check of the player calibration's room measurement against research/2026-09-28_player_calib: prices each
 * room's trail and hits, measures its idle time and clumpiness, exactly as the game does at room exit.
 *
 * <p>Input lines: {@code {"key","grid":{"sx","sy","sz","solidZ"},"trail":[[lx,ly,lz,ms,yaw,pitch,tp]],"breaks":[[ms,lx,ly,lz]],
 * "ticks":[[ms,x,z,yaw,pitch,onGround]],"chests":[[x,y,z]],"vein":bool,"speed":attr}}; output lines
 * {@code {"key","priced","idle","clump","hits","warps","cells"}}.
 *
 * <pre>java -cp routerunner.jar;gson.jar com.routerunner.calib.CalibCli rooms.jsonl out.jsonl [shape.json]</pre>
 */
public final class CalibCli {
    static final class GridIn {
        int sx, sy, sz;
        String solidZ;
    }

    static final class RoomIn {
        String key;
        GridIn grid;
        double[][] trail, breaks, ticks;
        int[][] chests;
        boolean vein;
        double speed;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: CalibCli rooms.jsonl out.jsonl [timemodel_shape.json]");
            System.exit(2);
        }
        LegTimeModel.Shape shape = args.length > 2 ? LegTimeModel.loadShape(Path.of(args[2])) : LegTimeModel.shape();
        Gson gson = new Gson();
        int n = 0, bad = 0;
        try (BufferedWriter w = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                RoomIn r = gson.fromJson(line, RoomIn.class);
                try {
                    SolidGrid grid = LaneCli.decodeGrid(r.grid.sx, r.grid.sy, r.grid.sz, r.grid.solidZ);
                    TrailPricer.Price p = TrailPricer.price(grid, shape.forMiner(r.vein, r.speed), List.of(r.trail), List.of(r.breaks));
                    List<Double> bt = new ArrayList<>();
                    for (double[] b : r.breaks) bt.add(b[0]);
                    List<P> ch = new ArrayList<>();
                    for (int[] c : r.chests) ch.add(new P(c[0], c[1], c[2]));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("key", r.key);
                    out.put("priced", p.seconds());
                    out.put("idle", IdleFilter.idleSeconds(r.ticks == null ? List.of() : List.of(r.ticks), bt));
                    out.put("clump", RoomMeasure.clump(ch));
                    out.put("hits", p.hits());
                    out.put("warps", p.warps());
                    out.put("cells", p.cells());
                    w.write(gson.toJson(out));
                    w.write('\n');
                    n++;
                } catch (Exception e) {
                    bad++;
                    System.err.println("room failed " + r.key + ": " + e);
                }
            }
        }
        System.err.printf("[CalibCli] %d rooms, %d failed%n", n, bad);
    }
}
