package com.routerunner.solver;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Binary solidity voxel grid over a room cell, in local coordinates [0,sx)×[0,sy)×[0,sz).
 * {@code isSolid} includes target chests (walk graph); {@code isSolidFly} excludes them (flight lines).
 * Out-of-bounds reads return solid.
 */
public final class SolidGrid {
    /** Cap on baked horizontal clearance (blocks). */
    public static final int MAX_CLEARANCE = 12;

    public final int sx, sy, sz;
    private final boolean[] solid;
    private final boolean[] target;
    /** Horizontal blocks to the nearest wall (target chests excluded); null until baked. */
    private byte[] clearanceFly;

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

    public boolean isSolidFly(int x, int y, int z) {
        if (!inBounds(x, y, z)) return true;
        int i = idx(x, y, z);
        return solid[i] && !target[i];
    }

    public boolean isSolidFly(P p) {
        return isSolidFly(p.x(), p.y(), p.z());
    }

    /**
     * Bake per-cell horizontal clearance: distance (blocks) to the nearest wall in the same y-layer,
     * capped at {@link #MAX_CLEARANCE}. Horizontal-only so the floor below doesn't count; target chests excluded.
     */
    public void bakeClearance() {
        clearanceFly = bake();
    }

    /** Per-y-layer multi-source BFS from wall cells (target chests excluded), capped. */
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
     * Number of cells a chest could occupy: a non-wall cell (target chests count as open) directly on a wall
     * cell, from y = 1. Approximates the decorator placement rule without its sturdy-floor test.
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

    /** Wall-only clearance for a standing body (min of feet and head layers); 0 if not baked or out of bounds. */
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
