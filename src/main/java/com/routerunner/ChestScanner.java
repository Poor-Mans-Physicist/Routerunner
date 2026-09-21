package com.routerunner;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Client-side chest detection: iterates loaded chunks' block entities and matches vault chest blocks by
 * registry id. A tracked chest counts as mined when its position becomes a non-chest block while its chunk
 * is still loaded.
 */
public class ChestScanner {

    /** Chunk radius around the player searched for new chests; mined detection covers all tracked chests. */
    private static final int SCAN_CHUNK_RADIUS = 4;

    /** Chebyshev radius (blocks) around the player checked by {@link #tickCheck(Level, BlockPos)} every tick. */
    private static final int TICK_CHECK_RADIUS = 48;

    /** Vault chest blocks matched by registry id. */
    private static final Set<String> CHEST_IDS = new HashSet<>();
    static {
        String[] names = {
            "wooden_chest", "gilded_chest", "ornate_chest", "living_chest",
            "flesh_chest", "enigma_chest", "hardened_chest", "treasure_chest", "altar_chest",
            "gilded_strongbox", "ornate_strongbox", "living_strongbox"
        };
        for (String n : names) CHEST_IDS.add("the_vault:" + n);
    }

    /** Chests seen this vault: position to chest block id. */
    private static final Map<BlockPos, String> tracked = new HashMap<>();

    private static int tickCheckBreaks = 0;
    private static int scanBreaks = 0;

    public static boolean isChest(Block block) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        return id != null && CHEST_IDS.contains(id.toString());
    }

    public static void reset() {
        tracked.clear();
        tickCheckBreaks = 0;
        scanBreaks = 0;
    }

    /** Breaks caught by the per-tick near check this vault. */
    public static int tickCheckBreaks() {
        return tickCheckBreaks;
    }

    /** Breaks caught only by the periodic discovery scan this vault. */
    public static int scanBreaks() {
        return scanBreaks;
    }

    public static void scan(Level level, BlockPos playerPos) {
        Map<BlockPos, String> current = new HashMap<>();
        ChunkPos center = new ChunkPos(playerPos);
        for (int cx = center.x - SCAN_CHUNK_RADIUS; cx <= center.x + SCAN_CHUNK_RADIUS; cx++) {
            for (int cz = center.z - SCAN_CHUNK_RADIUS; cz <= center.z + SCAN_CHUNK_RADIUS; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    Block block = e.getValue().getBlockState().getBlock();
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
                    if (id != null && CHEST_IDS.contains(id.toString())) {
                        current.put(e.getKey().immutable(), id.toString());
                    }
                }
            }
        }

        tracked.putAll(current);

        int mined = 0;
        Iterator<Map.Entry<BlockPos, String>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, String> entry = it.next();
            if (current.containsKey(entry.getKey())) continue;
            if (checkMined(level, entry.getKey(), entry.getValue(), it)) mined++;
        }

        if (mined > 0) {
            scanBreaks += mined;
            MetricsTracker.get().onMined(mined);
        }
    }

    /**
     * Per-tick mined check over tracked chests within {@link #TICK_CHECK_RADIUS} of the player, so breaks are
     * timestamped on the tick they happen; the rest are left to {@link #scan}.
     */
    public static void tickCheck(Level level, BlockPos playerPos) {
        if (level == null || playerPos == null || tracked.isEmpty()) return;
        int px = playerPos.getX(), py = playerPos.getY(), pz = playerPos.getZ();
        int mined = 0;
        Iterator<Map.Entry<BlockPos, String>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, String> entry = it.next();
            BlockPos pos = entry.getKey();
            if (Math.abs(pos.getX() - px) > TICK_CHECK_RADIUS) continue;
            if (Math.abs(pos.getZ() - pz) > TICK_CHECK_RADIUS) continue;
            if (Math.abs(pos.getY() - py) > TICK_CHECK_RADIUS) continue;
            if (checkMined(level, pos, entry.getValue(), it)) mined++;
        }

        if (mined > 0) {
            tickCheckBreaks += mined;
            MetricsTracker.get().onMined(mined);
        }
    }

    /**
     * Mined detection for one tracked chest: an unloaded position is dropped without counting, a loaded
     * position that is no longer a chest is reported as a break and dropped, anything else stays tracked.
     *
     * @return true iff this position was counted as a break
     */
    private static boolean checkMined(Level level, BlockPos pos, String chestId,
                                      Iterator<Map.Entry<BlockPos, String>> it) {
        if (!level.isLoaded(pos)) {
            it.remove();
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (isChest(state.getBlock())) return false;
        LootListener.get().onMinedChest(chestId);
        RunLog.breakEvent(pos, chestId);
        RouteService.onChestBroken(pos);
        it.remove();
        return true;
    }

    private ChestScanner() {}
}
