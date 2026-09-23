package com.routerunner.adaptive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.routerunner.lane.LegTimeModel;
import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Offline replay of the adaptive model through recorded run logs, in the order given, on the exact classes the game
 * uses ({@link LegRowBuilder}, {@link LegLearner}, {@link RunCalibration}). Every leg and every run is scored by the
 * bundled model and by the adaptive model as it stood BEFORE that leg or run was learned, so each vault's numbers are
 * what the player would have seen live. Replay cannot feed plans back: the logged plans were made by the model of the
 * day, so tier 0 learns on them as recorded.
 *
 * <pre>java -cp Routerunner.jar;gson.jar com.routerunner.adaptive.AdaptiveCli prior.json [--rows rows.csv] log.jsonl[@triggerS] ...</pre>
 *
 * {@code @triggerS} is the per-trigger charge the log's planner used (0.053 before 0.18.0's harvest term); logs
 * from 1.0.0 carry nTrig/tTravel/tPen and ignore it.
 */
public final class AdaptiveCli {
    private AdaptiveCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: AdaptiveCli prior.json [--rows rows.csv] log.jsonl[@triggerS] ...");
            System.exit(2);
        }
        LegTimeModel prior = LegTimeModel.load(Paths.get(args[0]));
        PrintWriter rowsOut = null;
        List<String> logs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--rows")) rowsOut = new PrintWriter(Files.newBufferedWriter(Paths.get(args[++i])));
            else logs.add(args[i]);
        }
        if (rowsOut != null) {
            StringBuilder h = new StringBuilder("vault,cell,t0");
            for (int k = 0; k < 12; k++) h.append(",f").append(k);
            rowsOut.println(h.append(",y"));
        }
        LegLearner legs = new LegLearner(prior);
        RunCalibration calib = new RunCalibration(0.3);
        System.out.println("vault                      legs  R2 bundled  R2 adapted  sum pred/act b|a   runs  real/plan bundled  adapted   a      b     fellBack");
        for (String spec : logs) {
            String path = spec;
            double trigS = 0.3;
            int at = spec.lastIndexOf('@');
            if (at > 2) {
                path = spec.substring(0, at);
                trigS = Double.parseDouble(spec.substring(at + 1));
            }
            replay(Paths.get(path), trigS, legs, calib, rowsOut);
        }
        if (rowsOut != null) rowsOut.close();
        double[] th = legs.applied();
        StringBuilder sb = new StringBuilder("final applied coefficients (bundled -> adapted):\n");
        String[] names = {"log_straight", "log_ratio", "climb", "drop", "clr_min", "clr_mean", "tight_frac", "turn", "dens_line",
                "log_dens_dst", "log_prev_burst", "warp", "intercept"};
        for (int i = 0; i < 13; i++) {
            double b = i < 12 ? prior.coef[i] : prior.intercept;
            sb.append(String.format(Locale.ROOT, "  %-15s %+.4f -> %+.4f%n", names[i], b, th[i]));
        }
        System.out.print(sb);
    }

    private static void replay(Path log, double trigS, LegLearner legs, RunCalibration calib, PrintWriter rowsOut) throws Exception {
        List<JsonObject> ev = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String e = evName(line);
                if (e == null) continue;
                if (e.equals("room_solve") || e.equals("room_diff") || e.equals("teleport") || e.equals("lane_plan")
                        || e.equals("lane") || e.equals("break")) {
                    try {
                        ev.add(JsonParser.parseString(line).getAsJsonObject());
                    } catch (Exception ex) {
                        System.err.println("  skipped an unparseable line in " + log.getFileName());
                    }
                }
            }
        }
        String tag = log.getFileName().toString();
        tag = tag.length() > 24 ? tag.substring(6, 21) : tag;
        List<Long> tps = new ArrayList<>();
        List<Long> breaks = new ArrayList<>();
        Map<String, List<JsonObject>> solves = new HashMap<>();
        int chainLimit = 32;
        for (JsonObject e : ev) {
            String n = e.get("ev").getAsString();
            if (n.equals("teleport")) tps.add(e.get("t").getAsLong());
            else if (n.equals("break")) breaks.add(e.get("t").getAsLong());
            else if (n.equals("room_solve")) {
                solves.computeIfAbsent(e.get("cellKey").getAsString(), k -> new ArrayList<>()).add(e);
                JsonObject w = e.has("weights") && e.get("weights").isJsonObject() ? e.getAsJsonObject("weights") : null;
                if (w != null && w.has("chainLimit")) chainLimit = w.get("chainLimit").getAsInt();
            }
        }
        java.util.Collections.sort(breaks);
        legs.newVault();
        double ssTot = 0, e0 = 0, e1 = 0, sumAct = 0, sum0 = 0, sum1 = 0, mean = 0;
        List<double[]> scored = new ArrayList<>();
        Map<String, List<JsonObject>> plans = new HashMap<>();
        Map<String, long[]> runStart = new HashMap<>();
        double rReal = 0, rPlan0 = 0, rPlan1 = 0;
        int runs = 0;
        LegLearner.Update last = null;
        for (JsonObject e : ev) {
            String n = e.get("ev").getAsString();
            if (n.equals("lane_plan")) {
                plans.computeIfAbsent(e.get("cellKey").getAsString(), k -> new ArrayList<>()).add(e);
            } else if (n.equals("lane")) {
                String cell = e.get("cellKey").getAsString();
                long t = e.get("t").getAsLong();
                String what = e.get("what").getAsString();
                if (what.equals("advance")) runStart.put(cell, new long[]{t, e.get("run").getAsInt()});
                else if (what.equals("replan")) runStart.remove(cell);
                if (!what.equals("done")) continue;
                JsonObject pl = null;
                for (JsonObject p : plans.getOrDefault(cell, List.of())) if (p.get("t").getAsLong() <= t) pl = p;
                long[] s0 = runStart.remove(cell);
                if (pl == null) continue;
                long t0 = s0 != null ? s0[0] : pl.get("t").getAsLong();
                int run = s0 != null ? (int) s0[1] : e.get("run").getAsInt();
                JsonArray rl = pl.getAsJsonArray("runList");
                if (run >= rl.size()) continue;
                JsonObject rr = rl.get(run).getAsJsonObject();
                if (rr.has("exit") && rr.get("exit").getAsBoolean()) continue;
                int nb = upper(breaks, t) - lower(breaks, t0 + 1);
                double realized = (t - t0) / 1000.0;
                if (nb < 8 || realized < 0.5) continue;
                double K, T, pen;
                if (rr.has("nTrig")) {
                    K = rr.get("nTrig").getAsDouble();
                    pen = rr.get("tPen").getAsDouble();
                    double pace = pl.has("pace") ? pl.get("pace").getAsDouble() : 1.0;
                    T = rr.get("tTravel").getAsDouble() / pace;
                } else {
                    K = Math.ceil(rr.get("yield").getAsInt() / (double) chainLimit);
                    pen = 0.0;
                    T = Math.max(rr.get("s").getAsDouble() - trigS * K, 0.05);
                }
                double y = realized - pen;
                double p0 = T + calib.priorB() * K, p1 = calib.a() * T + calib.b() * K;
                if (calib.observe(T, K, y)) {
                    rReal += y;
                    rPlan0 += p0;
                    rPlan1 += p1;
                    runs++;
                }
            } else if (n.equals("room_diff")) {
                JsonArray bl = e.has("breakList") && e.get("breakList").isJsonArray() ? e.getAsJsonArray("breakList") : null;
                JsonArray tr = e.has("trail") && e.get("trail").isJsonArray() ? e.getAsJsonArray("trail") : null;
                if (bl == null || tr == null || bl.size() < LegRowBuilder.MIN_CHESTS) continue;
                String cell = e.get("cellKey").getAsString();
                JsonObject sv = null;
                for (JsonObject s : solves.getOrDefault(cell, List.of())) if (s.get("t").getAsLong() <= e.get("t").getAsLong()) sv = s;
                if (sv == null || !sv.has("grid") || !sv.get("grid").isJsonObject()) continue;
                SolidGrid grid = decodeGrid(sv.getAsJsonObject("grid"));
                List<P> chests = new ArrayList<>();
                for (JsonElement c : sv.getAsJsonArray("chestList")) {
                    JsonArray a = c.getAsJsonArray();
                    chests.add(new P(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
                }
                List<double[]> trail = new ArrayList<>(tr.size());
                for (JsonElement x : tr) {
                    JsonArray a = x.getAsJsonArray();
                    trail.add(new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()});
                }
                List<double[]> br = new ArrayList<>(bl.size());
                for (JsonElement x : bl) {
                    JsonArray a = x.getAsJsonArray();
                    br.add(new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()});
                }
                LegRowBuilder.Result res = LegRowBuilder.build(grid, chests, trail, br, tps);
                if (res.rows.isEmpty()) continue;
                LegTimeModel cur = legs.model();
                for (int i = 0; i < res.rows.size(); i++) {
                    double[] row = res.rows.get(i);
                    double[] z = legs.prior().standardize(java.util.Arrays.copyOf(row, 12));
                    scored.add(new double[]{row[12], legs.prior().logSeconds(z), cur.logSeconds(z)});
                    if (rowsOut != null) {
                        StringBuilder sb = new StringBuilder().append(tag).append(",\"").append(cell).append("\",").append(res.startMs.get(i));
                        for (int k = 0; k < 13; k++) sb.append(',').append(String.format(Locale.ROOT, "%.6f", row[k]));
                        rowsOut.println(sb);
                    }
                }
                last = legs.learn(res.rows);
            }
        }
        for (double[] s : scored) mean += s[0];
        mean /= Math.max(1, scored.size());
        for (double[] s : scored) {
            ssTot += (s[0] - mean) * (s[0] - mean);
            e0 += (s[0] - s[1]) * (s[0] - s[1]);
            e1 += (s[0] - s[2]) * (s[0] - s[2]);
            sumAct += Math.exp(s[0]);
            sum0 += Math.exp(s[1]);
            sum1 += Math.exp(s[2]);
        }
        System.out.printf(Locale.ROOT, "%-24s %6d     %6.3f      %6.3f       %5.2f | %5.2f  %5d       %5.2f        %5.2f   %5.3f  %5.3f  %s%n",
                tag, scored.size(), ssTot > 0 ? 1 - e0 / ssTot : Double.NaN, ssTot > 0 ? 1 - e1 / ssTot : Double.NaN,
                sumAct > 0 ? sum0 / sumAct : Double.NaN, sumAct > 0 ? sum1 / sumAct : Double.NaN,
                runs, rPlan0 > 0 ? rReal / rPlan0 : Double.NaN, rPlan1 > 0 ? rReal / rPlan1 : Double.NaN, calib.a(), calib.b(),
                last == null ? "-" : String.valueOf(last.fellBack));
    }

    private static String evName(String line) {
        int i = line.indexOf("\"ev\":\"");
        if (i < 0) return null;
        int j = line.indexOf('"', i + 6);
        return j < 0 ? null : line.substring(i + 6, j);
    }

    private static int lower(List<Long> v, long t) {
        int lo = 0, hi = v.size();
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (v.get(m) < t) lo = m + 1; else hi = m;
        }
        return lo;
    }

    private static int upper(List<Long> v, long t) {
        int lo = 0, hi = v.size();
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (v.get(m) <= t) lo = m + 1; else hi = m;
        }
        return lo;
    }

    /** A logged room grid (gzip+base64 BitSet of fly-solid cells, idx = (x*sy+y)*sz+z), clearance baked. */
    static SolidGrid decodeGrid(JsonObject g) throws java.io.IOException {
        int sx = g.get("sx").getAsInt(), sy = g.get("sy").getAsInt(), sz = g.get("sz").getAsInt();
        byte[] gz = Base64.getDecoder().decode(g.get("solidZ").getAsString());
        byte[] raw;
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            raw = in.readAllBytes();
        }
        java.util.BitSet bits = java.util.BitSet.valueOf(raw);
        SolidGrid grid = new SolidGrid(sx, sy, sz);
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    if (bits.get((x * sy + y) * sz + z)) grid.setSolid(x, y, z, true);
                }
            }
        }
        grid.bakeClearance();
        return grid;
    }
}
