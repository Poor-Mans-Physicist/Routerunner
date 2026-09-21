package com.routerunner;

import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the solver's per-room snapshot from the LIVE client world, ON THE MAIN THREAD (Minecraft
 * block reads aren't thread-safe). Everything downstream ({@link com.routerunner.solver.RoutePlanner})
 * runs off-thread on the returned, Minecraft-free {@link Snapshot}.
 *
 * Cell math is the verified grid layout: a 47³ cell anchored at world (regionX·47, _, regionZ·47).
 * The vertical extent is data-driven from the target chests (so we don't depend on the exact vault
 * y-origin). Entrance/exit are detected geometrically as the wall-centre air openings — no per-template
 * gate table and no rotation/mirror transform needed (the four wall-centres are rotation-invariant).
 */
public final class RoomGeometry {

    public static final int CELL = 47;
    private static final int PAD_BELOW = 4;   // floor headroom under the lowest chest
    private static final int PAD_ABOVE = 5;   // head/flight room above the highest chest
    private static final int MAX_HEIGHT = 64; // clamp the snapshot height

    /** Minecraft-free result handed to the off-thread solver. */
    public static final class Snapshot {
        public final SolidGrid grid;
        public final List<P> targetsLocal;
        public final List<BlockPos> targetsWorld; // parallel to targetsLocal, for live gone-checks/logging
        /** Every chest-like block entity in the cell that is NOT a target (other chest types and strongboxes), LOCAL. */
        public final List<P> othersLocal;
        public final List<String> otherIds; // parallel to othersLocal: full block id
        public final P entranceLocal;
        public final P exitLocal;
        public final int ox, oy, oz; // world origin of local (0,0,0)

        Snapshot(SolidGrid grid, List<P> tl, List<BlockPos> tw, List<P> ol, List<String> oi,
                 P entrance, P exit, int ox, int oy, int oz) {
            this.grid = grid;
            this.targetsLocal = tl;
            this.targetsWorld = tw;
            this.othersLocal = ol;
            this.otherIds = oi;
            this.entranceLocal = entrance;
            this.exitLocal = exit;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }
    }

    /**
     * @return a snapshot, or {@code null} if the cell's chunks aren't all loaded yet (incomplete —
     *         caller should wait and retry). A snapshot with an empty {@code targetsLocal} means the
     *         cell has no target chests (nothing to route).
     */
    public static Snapshot build(Level level, int regionX, int regionZ, String targetSubstring, BlockPos playerPos) {
        int ox = regionX * CELL;
        int oz = regionZ * CELL;

        // Completeness gate: every chunk overlapping the cell must be loaded.
        int cx0 = ox >> 4, cx1 = (ox + CELL - 1) >> 4;
        int cz0 = oz >> 4, cz1 = (oz + CELL - 1) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) return null;
            }
        }

        // Collect target chests within the cell footprint.
        List<BlockPos> targetsWorld = new ArrayList<>();
        List<BlockPos> othersWorld = new ArrayList<>();
        List<String> otherIds = new ArrayList<>();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = e.getKey();
                    if (p.getX() < ox || p.getX() >= ox + CELL || p.getZ() < oz || p.getZ() >= oz + CELL) continue;
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(e.getValue().getBlockState().getBlock());
                    if (id == null) continue;
                    String s = id.toString();
                    if (s.contains(targetSubstring) && !s.contains("strongbox")) {
                        targetsWorld.add(p.immutable());
                        minY = Math.min(minY, p.getY());
                        maxY = Math.max(maxY, p.getY());
                    } else if (s.contains("chest") || s.contains("strongbox")) {
                        othersWorld.add(p.immutable());
                        otherIds.add(s);
                    }
                }
            }
        }

        int oy;
        int sy;
        if (targetsWorld.isEmpty()) {
            // No targets — return an empty snapshot so the caller can mark "nothing to route".
            return new Snapshot(new SolidGrid(CELL, 1, CELL), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), new P(0, 0, 0), new P(0, 0, 0), ox, level.getMinBuildHeight(), oz);
        }
        oy = minY - PAD_BELOW;
        sy = Math.min(MAX_HEIGHT, (maxY + PAD_ABOVE) - oy + 1);

        SolidGrid grid = new SolidGrid(CELL, sy, CELL);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < CELL; lx++) {
            for (int lz = 0; lz < CELL; lz++) {
                for (int ly = 0; ly < sy; ly++) {
                    m.set(ox + lx, oy + ly, oz + lz);
                    BlockState st = level.getBlockState(m);
                    Material mat = st.getMaterial();
                    if (mat.blocksMotion()) grid.setSolid(lx, ly, lz, true);
                }
            }
        }

        List<P> targetsLocal = new ArrayList<>(targetsWorld.size());
        for (BlockPos p : targetsWorld) {
            int lx = p.getX() - ox, ly = p.getY() - oy, lz = p.getZ() - oz;
            targetsLocal.add(new P(lx, ly, lz));
            grid.setTarget(lx, ly, lz);
        }
        grid.bakeClearance(); // openness field for the execution-difficulty cost model

        // Gates: the four wall-centre air openings. Entrance = nearest the player; exit = opposite wall.
        List<int[]> gates = new ArrayList<>(); // {localX, localY, localZ, wall} wall: 0=W,1=E,2=N,3=S
        addGate(gates, grid, 0, 23, 1, 23, 0);    // West wall plane x=0, interior x=1
        addGate(gates, grid, CELL - 1, 23, CELL - 2, 23, 1); // East
        addGate(gates, grid, 23, 0, 23, 1, 2);    // North wall plane z=0, interior z=1
        addGate(gates, grid, 23, CELL - 1, 23, CELL - 2, 3); // South

        P entrance, exit;
        if (gates.isEmpty()) {
            // No detectable doorway: route from the player's position back to itself.
            P pl = new P(clamp(playerPos.getX() - ox, CELL), clamp(playerPos.getY() - oy, sy), clamp(playerPos.getZ() - oz, CELL));
            entrance = pl;
            exit = pl;
        } else {
            int[] ent = gates.get(0);
            long bestd = Long.MAX_VALUE;
            for (int[] g : gates) {
                long dx = (g[0] + ox) - playerPos.getX();
                long dy = (g[1] + oy) - playerPos.getY();
                long dz = (g[2] + oz) - playerPos.getZ();
                long d = dx * dx + dy * dy + dz * dz;
                if (d < bestd) {
                    bestd = d;
                    ent = g;
                }
            }
            int oppWall = oppositeWall(ent[3]);
            int[] ex = null;
            for (int[] g : gates) if (g[3] == oppWall) ex = g;
            if (ex == null) { // no opposite gate: pick the farthest from the entrance, else entrance itself
                long fd = -1;
                for (int[] g : gates) {
                    if (g == ent) continue;
                    long dx = g[0] - ent[0], dy = g[1] - ent[1], dz = g[2] - ent[2];
                    long d = dx * dx + dy * dy + dz * dz;
                    if (d > fd) { fd = d; ex = g; }
                }
                if (ex == null) ex = ent;
            }
            entrance = new P(ent[0], ent[1], ent[2]);
            exit = new P(ex[0], ex[1], ex[2]);
        }

        List<P> othersLocal = new ArrayList<>(othersWorld.size());
        for (BlockPos p : othersWorld) othersLocal.add(new P(p.getX() - ox, p.getY() - oy, p.getZ() - oz));

        return new Snapshot(grid, targetsLocal, targetsWorld, othersLocal, otherIds, entrance, exit, ox, oy, oz);
    }

    public static final String[] TYPES = {"gilded", "ornate", "living", "wooden"};

    /** Per-type target-chest counts in the cell (indexes match {@link #TYPES}), or null if chunks incomplete. */
    public static int[] scanCounts(Level level, int regionX, int regionZ) {
        int ox = regionX * CELL, oz = regionZ * CELL;
        int cx0 = ox >> 4, cx1 = (ox + CELL - 1) >> 4, cz0 = oz >> 4, cz1 = (oz + CELL - 1) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) return null;
            }
        }
        int[] counts = new int[TYPES.length];
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = e.getKey();
                    if (p.getX() < ox || p.getX() >= ox + CELL || p.getZ() < oz || p.getZ() >= oz + CELL) continue;
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(e.getValue().getBlockState().getBlock());
                    if (id == null) continue;
                    String s = id.toString();
                    if (s.contains("strongbox")) continue;
                    for (int i = 0; i < TYPES.length; i++) if (s.contains(TYPES[i])) counts[i]++;
                }
            }
        }
        return counts;
    }

    /** Most common vault-chest type in the cell (by block id substring), or null if none/incomplete. */
    public static String dominantType(Level level, int regionX, int regionZ) {
        int ox = regionX * CELL, oz = regionZ * CELL;
        int cx0 = ox >> 4, cx1 = (ox + CELL - 1) >> 4, cz0 = oz >> 4, cz1 = (oz + CELL - 1) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) return null;
            }
        }
        int[] counts = new int[TYPES.length];
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = e.getKey();
                    if (p.getX() < ox || p.getX() >= ox + CELL || p.getZ() < oz || p.getZ() >= oz + CELL) continue;
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(e.getValue().getBlockState().getBlock());
                    if (id == null) continue;
                    String s = id.toString();
                    if (s.contains("strongbox")) continue;
                    for (int i = 0; i < TYPES.length; i++) if (s.contains(TYPES[i])) counts[i]++;
                }
            }
        }
        int best = -1, bestN = 0;
        for (int i = 0; i < counts.length; i++) if (counts[i] > bestN) { bestN = counts[i]; best = i; }
        return best < 0 ? null : TYPES[best];
    }

    private static void addGate(List<int[]> out, SolidGrid g, int wallX, int wallZ, int interiorX, int interiorZ, int wall) {
        int bestStart = -1, bestLen = 0, curStart = -1, curLen = 0;
        for (int y = 0; y < g.sy; y++) {
            if (!g.isSolid(wallX, y, wallZ)) {
                if (curLen == 0) curStart = y;
                curLen++;
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
            } else {
                curLen = 0;
            }
        }
        if (bestLen < 2) return; // no real opening on this wall
        int gy = bestStart + bestLen / 2;
        out.add(new int[]{interiorX, gy, interiorZ, wall});
    }

    private static int oppositeWall(int wall) {
        switch (wall) {
            case 0: return 1;
            case 1: return 0;
            case 2: return 3;
            default: return 2;
        }
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }

    private RoomGeometry() {}
}
