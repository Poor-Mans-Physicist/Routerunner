package com.routerunner.solver;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * A binary solidity voxel grid over a room cell, in LOCAL coordinates [0,sx)×[0,sy)×[0,sz).
 * Two predicates (per the beta spec §3.1):
 *   - isSolid:    blocks the player; INCLUDES target chests (you stand on them) — used by the walk graph.
 *   - isSolidFly: isSolid minus target-chest cells (you fly straight through a target chest line) — used by flight.
 * Out-of-bounds reads return solid (the room is walled), which bounds the walk graph and flight LOS.
 */
public final class SolidGrid {
    public static final int MAX_CLEARANCE = 12; // raised so a ~20+ wide chamber reads as "very open" (speed bonus)

    public final int sx, sy, sz;
    private final boolean[] solid;
    private final boolean[] target;
    private byte[] clearanceFly; // horizontal blocks to nearest WALL (chests excluded) — the only clearance used

    public SolidGrid(int sx, int sy, int sz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.solid = new boolean[sx * sy * sz];
        this.target = new boolean[sx * sy * sz];
    }

    private int idx(int x, int y, int z) {
        return (x * sy + y) * sz + z;
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    public void setSolid(int x, int y, int z, boolean v) {
        if (inBounds(x, y, z)) solid[idx(x, y, z)] = v;
    }

    public void setTarget(int x, int y, int z) {
        if (inBounds(x, y, z)) target[idx(x, y, z)] = true;
    }

    public boolean isSolid(int x, int y, int z) {
        if (!inBounds(x, y, z)) return true;
        return solid[idx(x, y, z)];
    }

    public boolean isSolid(P p) {
        return isSolid(p.x(), p.y(), p.z());
    }

    public boolean isSolidFly(int x, int y, int z) {
        if (!inBounds(x, y, z)) return true;
        int i = idx(x, y, z);
        return solid[i] && !target[i];
    }

    public boolean isSolidFly(P p) {
        return isSolidFly(p.x(), p.y(), p.z());
    }

    /**
     * Bake per-cell horizontal clearance = distance (blocks) to the nearest WALL within the same
     * y-layer, via a per-layer multi-source BFS from solid cells, capped at {@link #MAX_CLEARANCE}.
     * Horizontal-only so the floor directly below doesn't make every walkable cell read as "tight."
     * Target chests are excluded: reaching into a dense chest cluster is fast, not "tight."
     */
    public void bakeClearance() {
        clearanceFly = bake();
    }

    /** Per-y-layer multi-source BFS from WALL cells (target chests excluded), capped. */
    private byte[] bake() {
        byte[] field = new byte[sx * sy * sz];
        Arrays.fill(field, (byte) MAX_CLEARANCE);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        Deque<int[]> q = new ArrayDeque<>();
        for (int y = 0; y < sy; y++) {
            q.clear();
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    int i = idx(x, y, z);
                    if (solid[i] && !target[i]) {
                        field[i] = 0;
                        q.addLast(new int[]{x, z});
                    }
                }
            }
            while (!q.isEmpty()) {
                int[] c = q.pollFirst();
                int d = field[idx(c[0], y, c[1])];
                if (d >= MAX_CLEARANCE) continue;
                for (int[] dd : dirs) {
                    int nx = c[0] + dd[0], nz = c[1] + dd[1];
                    if (nx < 0 || nz < 0 || nx >= sx || nz >= sz) continue;
                    int ni = idx(nx, y, nz);
                    if (field[ni] > d + 1) {
                        field[ni] = (byte) (d + 1);
                        q.addLast(new int[]{nx, nz});
                    }
                }
            }
        }
        return field;
    }

    /**
     * Cells a chest could occupy: a non-wall cell (target chests count as open) sitting directly on a
     * wall cell, over the whole slab from y = 1. This is the decorator_add placement rule minus the
     * sturdy-floor test the grid cannot see; water is open here, and chests do spawn in water.
     */
    public int slotCount() {
        int n = 0;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                for (int y = 1; y < sy; y++) {
                    if (!isSolidFly(x, y, z) && isSolidFly(x, y - 1, z)) n++;
                }
            }
        }
        return n;
    }

    /** Wall-only clearance (chests excluded) for a standing body (min of the feet and head layers). 0 if not baked/oob. */
    public int clearanceFlyAt(int x, int y, int z) {
        return clearanceOf(clearanceFly, x, y, z);
    }

    private int clearanceOf(byte[] field, int x, int y, int z) {
        if (field == null || !inBounds(x, y, z)) return 0;
        int c = field[idx(x, y, z)];
        if (inBounds(x, y + 1, z)) c = Math.min(c, field[idx(x, y + 1, z)]);
        return c;
    }
}
