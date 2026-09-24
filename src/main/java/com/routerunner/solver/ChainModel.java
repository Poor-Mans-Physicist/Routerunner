package com.routerunner.solver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic chain-miner model for the player's Chain Miner tier: breaking one chest clears up to
 * {@link #limit} same-type chests, each within {@link #range} (Chebyshev) of a cleared one, BFS nearest-first.
 * Neighbour queries use a spatial hash with bucket size {@code range + 1}.
 */
public final class ChainModel {
    /** Chebyshev radius of one chain step (blocks). */
    public final int range;
    /** Cap on how many chests a single trigger clears. */
    public final int limit;
    /** Spatial-hash cell size; {@code range + 1} so a ±1 bucket scan covers a full chain step. */
    public final int bucket;

    private final List<P> pts;
    private final Map<Long, List<Integer>> buckets;
    /**
     * Range 1 (Vein Miner) only: component root (lowest chest index) per chest and component size per root, the
     * static 26-connected components; null for chain ranges. Exact for live chests while every trigger clears its
     * whole live component, i.e. while no component is larger than {@link #limit}.
     */
    private final int[] comp;
    private final int[] compSize;
    /** Largest component, 0 for chain ranges. */
    public final int compMax;

    public ChainModel(int range, int limit, List<P> pts) {
        this.range = Math.max(0, range);
        this.limit = Math.max(1, limit);
        this.bucket = Math.max(1, this.range + 1);
        this.pts = pts;
        this.buckets = buildBuckets(pts);
        if (this.range <= 1 && this.limit > 1) {
            int[][] cs = label(this);
            int max = 0;
            for (int v : cs[1]) max = Math.max(max, v);
            this.comp = cs[0];
            this.compSize = cs[1];
            this.compMax = max;
        } else {
            this.comp = null;
            this.compSize = null;
            this.compMax = 0;
        }
    }

    /**
     * {root per chest (the lowest index of its group), size per root}: the groups of chests linked by one break step
     * of {@code m}'s range, i.e. what a break could ever reach with no limit.
     */
    private static int[][] label(ChainModel m) {
        int n = m.pts.size();
        boolean[] all = new boolean[n];
        java.util.Arrays.fill(all, true);
        int[] c = new int[n];
        int[] sz = new int[n];
        java.util.Arrays.fill(c, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for (int s0 = 0; s0 < n; s0++) {
            if (c[s0] >= 0) continue;
            c[s0] = s0;
            stack.push(s0);
            int k = 0;
            while (!stack.isEmpty()) {
                int h = stack.pop();
                k++;
                for (int j : m.neighbors(h, all)) {
                    if (c[j] < 0) {
                        c[j] = s0;
                        stack.push(j);
                    }
                }
            }
            sz[s0] = k;
        }
        return new int[][]{c, sz};
    }

    /** The groups of {@link #label} for any range, e.g. the Chain Miner's when deciding which chests are worth a break. */
    public static int[][] components(int range, List<P> pts) {
        ChainModel m = new ChainModel(range, Integer.MAX_VALUE, pts);
        return m.comp != null ? new int[][]{m.comp, m.compSize} : label(m);
    }

    /** Size of chest i's touching group (range 1 only; 0 for chain ranges). */
    public int compSizeOf(int i) {
        return comp == null ? 0 : compSize[comp[i]];
    }

    /** True at range 1, where the candidate pre-filter scores whole components ({@link #preKey}, {@link #preValue}). */
    public boolean hasComponents() {
        return comp != null;
    }

    /** Pre-filter stamp key of chest i: the chest itself, or at range 1 its component root. */
    public int preKey(int i) {
        return comp == null ? i : comp[i];
    }

    /** Pre-filter value of chest i: 1, or at range 1 its component's size capped at {@link #limit}. */
    public int preValue(int i) {
        return comp == null ? 1 : Math.min(compSize[comp[i]], limit);
    }

    private Map<Long, List<Integer>> buildBuckets(List<P> list) {
        Map<Long, List<Integer>> m = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            P p = list.get(i);
            m.computeIfAbsent(bucketKey(p.x(), p.y(), p.z()), k -> new ArrayList<>()).add(i);
        }
        return m;
    }

    /** The spatial hash itself, for callers that run their own bucket scan (see {@link #bucketRadius(double)}). */
    public Map<Long, List<Integer>> buckets() {
        return buckets;
    }

    public int bucketCoord(int v) {
        return Math.floorDiv(v, bucket);
    }

    /** Pack bucket coords into one key; the +64 offset keeps each field non-negative within 10 bits. */
    public long packBucket(int bx, int by, int bz) {
        return (((long) (bx + 64)) << 20) | (((long) (by + 64)) << 10) | (long) (bz + 64);
    }

    public long bucketKey(int x, int y, int z) {
        return packBucket(bucketCoord(x), bucketCoord(y), bucketCoord(z));
    }

    /** Buckets a scan must span each way to cover everything within {@code reach} blocks (at least 1). */
    public int bucketRadius(double reach) {
        return Math.max(1, (int) Math.ceil(reach / bucket));
    }

    private static int cheb(P a, P b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
    }

    private static int manh(P a, P b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    /** Indices j != idx with remaining[j] and within {@link #range} (Chebyshev) of pts[idx]. */
    public List<Integer> neighbors(int idx, boolean[] remaining) {
        if (limit <= 1) return new ArrayList<>();
        List<Integer> out = new ArrayList<>();
        P c = pts.get(idx);
        int bx = bucketCoord(c.x()), by = bucketCoord(c.y()), bz = bucketCoord(c.z());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> v = buckets.get(packBucket(bx + dx, by + dy, bz + dz));
                    if (v == null) continue;
                    for (int j : v) {
                        if (j != idx && remaining[j] && cheb(c, pts.get(j)) <= range) out.add(j);
                    }
                }
            }
        }
        return out;
    }

    /** Cheap value estimate: how many chests a trigger at idx would chain (capped at {@link #limit}). */
    public int clearProxy(int idx, boolean[] remaining) {
        if (limit <= 1) return 1;
        return Math.min(neighbors(idx, remaining).size() + 1, limit);
    }

    /** The exact set of chests one trigger at {@code start} removes; does not mutate {@code remaining}. */
    public List<Integer> clearFrom(int start, boolean[] remaining) {
        if (limit <= 1) return new ArrayList<>(Collections.singletonList(start));
        List<Integer> trav = new ArrayList<>();
        Set<Integer> inset = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.addLast(start);
        while (!queue.isEmpty()) {
            int head = queue.pollFirst();
            List<Integer> cand = neighbors(head, remaining);
            cand.add(head);
            P hp = pts.get(head);
            cand.sort(Comparator.comparingInt(c -> manh(pts.get(c), hp)));
            boolean capped = false;
            for (int c : cand) {
                if (trav.size() >= limit) {
                    capped = true;
                    break;
                }
                if (inset.contains(c) || !remaining[c]) continue;
                trav.add(c);
                inset.add(c);
                queue.addLast(c);
            }
            if (capped) break;
        }
        return trav;
    }
}
