package com.routerunner.lane;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Headless entry point for the replay bundle builder: runs {@link LanePlanner} over rooms read as JSONL and
 * writes one JSON line per room with the exported plan per mode. No Minecraft on the classpath is needed —
 * only the mod's own solver classes and Gson.
 *
 * <pre>java -cp Routerunner.jar;gson.jar com.routerunner.lane.LaneCli rooms.jsonl plans.jsonl legmodel.json [threads]</pre>
 *
 * A room line: {@code {"key","grid":{"sx","sy","sz","solidZ"},"chests":[[x,y,z]…],"entrance":[x,y,z],
 * "exit":[x,y,z],"origin":[ox,oy,oz],"tEntry":ms,"chainRange","chainLimit","modes":["corridor","point"],
 * "params":{"timeScale":1.39,"bailAggression":0.12}}} with grid/chests/entrance/exit ROOM-LOCAL, the grid
 * as the run log writes it (gzip + base64 BitSet, bit i = byte i/8 LSB-first, index (x*sy+y)*sz+z).
 */
public final class LaneCli {
    private LaneCli() {}

    static final class RoomIn {
        String key;
        GridIn grid;
        int[][] chests;
        int[] entrance;
        int[] exit;
        int[] origin;
        long tEntry;
        int chainRange = 6;
        int chainLimit = 32;
        String[] modes = {"corridor", "point"};
        Map<String, Double> params;
    }

    static final class GridIn {
        int sx, sy, sz;
        String solidZ;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: LaneCli rooms.jsonl plans.jsonl legmodel.json [threads]");
            System.exit(2);
        }
        Path in = Path.of(args[0]), out = Path.of(args[1]);
        LegTimeModel model = LegTimeModel.load(Path.of(args[2]));
        String nativeDir = System.getenv("ROUTERUNNER_NATIVE_DIR");
        if (nativeDir != null) {
            NativeLane.init(Path.of(nativeDir));
            System.err.println("[LaneCli] native planner: " + NativeLane.status());
        }
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        List<String> lines = Files.readAllLines(in, StandardCharsets.UTF_8);
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        long t0 = System.nanoTime();
        AtomicInteger done = new AtomicInteger();
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            ForkJoinPool pool = new ForkJoinPool(threads);
            pool.submit(() -> lines.parallelStream().forEach(line -> {
                if (line.isBlank()) return;
                String result;
                try {
                    result = gson.toJson(planRoom(gson.fromJson(line, RoomIn.class), model));
                } catch (Exception ex) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", ex.toString());
                    result = gson.toJson(err);
                    System.err.println("[LaneCli] room failed: " + ex);
                }
                synchronized (w) {
                    try {
                        w.write(result);
                        w.newLine();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                int n = done.incrementAndGet();
                if (n % 10 == 0) System.err.printf("[LaneCli] %d/%d rooms, %.1f s%n", n, lines.size(), (System.nanoTime() - t0) / 1e9);
            })).get();
            pool.shutdown();
        }
        System.err.printf("[LaneCli] %d rooms in %.1f s on %d threads%n", done.get(), (System.nanoTime() - t0) / 1e9, threads);
    }

    static Map<String, Object> planRoom(RoomIn r, LegTimeModel model) throws IOException {
        SolidGrid grid = decodeGrid(r.grid);
        List<P> chests = new ArrayList<>(r.chests.length);
        for (int[] c : r.chests) chests.add(new P(c[0], c[1], c[2]));
        P entrance = Grid.snapInside(grid, new P(r.entrance[0], r.entrance[1], r.entrance[2]));
        P exit = Grid.snapInside(grid, new P(r.exit[0], r.exit[1], r.exit[2]));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", r.key);
        List<String> log = new ArrayList<>();
        for (String mode : r.modes) {
            long t0 = System.nanoTime();
            LanePlanner.Params p = new LanePlanner.Params();
            p.pointMode = "point".equals(mode);
            if (r.params != null) {
                if (r.params.containsKey("timeScale")) p.timeScale = r.params.get("timeScale");
                if (r.params.containsKey("bailAggression")) p.bailAggression = r.params.get("bailAggression");
                if (r.params.containsKey("sweepGain")) p.sweepGain = r.params.get("sweepGain");
                if (r.params.containsKey("proxyTopK")) p.proxyTopK = r.params.get("proxyTopK").intValue();
                if (r.params.containsKey("beamWidth")) p.beamWidth = r.params.get("beamWidth").intValue();
                if (r.params.containsKey("minLaneLen")) p.minLaneLen = r.params.get("minLaneLen").intValue();
                if (r.params.containsKey("minLaneClr")) p.minLaneClr = r.params.get("minLaneClr").intValue();
                if (r.params.containsKey("turnCap")) p.turnCap = r.params.get("turnCap");
            }
            LanePlanner planner = new LanePlanner(grid, chests, r.chainRange, r.chainLimit, p, model);
            LanePlanner.Plan plan = planner.plan(entrance, exit);
            out.put(mode, planner.export(plan, r.origin[0], r.origin[1], r.origin[2], r.tEntry));
            log.add(String.format(java.util.Locale.ROOT, "  %-8s %-9s chests %4d lanes %3d runs %3d cover %.2f model %5.1f s  (%.2f s)",
                    r.key, mode, chests.size(), plan.lanes.size(), plan.runs.size(), plan.cover, plan.tTotal, (System.nanoTime() - t0) / 1e9));
        }
        out.put("log", log);
        return out;
    }

    /** Rebuild a {@link SolidGrid} from the run log's grid object; target chests are left unset (walls only). */
    static SolidGrid decodeGrid(GridIn g) throws IOException {
        byte[] raw;
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(g.solidZ)))) {
            raw = gz.readAllBytes();
        }
        SolidGrid grid = new SolidGrid(g.sx, g.sy, g.sz);
        int n = g.sx * g.sy * g.sz;
        for (int i = 0; i < n && (i >> 3) < raw.length; i++) {
            if (((raw[i >> 3] >> (i & 7)) & 1) != 0) {
                int x = i / (g.sy * g.sz), rem = i % (g.sy * g.sz);
                grid.setSolid(x, rem / g.sz, rem % g.sz, true);
            }
        }
        grid.bakeClearance();
        return grid;
    }
}
