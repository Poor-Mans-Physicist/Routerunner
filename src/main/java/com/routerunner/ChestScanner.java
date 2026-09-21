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
 * Live, client-side chest detection. Mirrors the_vault's server-side HunterAbility.forEachTile
 * but runs against the ClientLevel: iterate loaded chunks' block entities and match vault chest
 * blocks by registry id (so we don't compile-depend on the_vault).
 *
 * "Mined" is detected as a tracked chest position transitioning to a non-chest block while its
 * chunk is still loaded (i.e. broken), per the Phase-0 spec.
 */
public class ChestScanner {

    // Discovery window: +/-4 chunks around the player's chunk = a 9x9 chunk square
    // (~144 blocks across, full height) -- a superset of a radius-4 circle, comfortably larger
    // than a 47^3 room. This only governs DISCOVERY of chests; the mined-detection pass below
    // catches any already-tracked chest breaking anywhere still loaded, even outside this window.
    private static final int SCAN_CHUNK_RADIUS = 4;

    /**
     * Chebyshev radius around the player checked by {@link #tickCheck(Level, BlockPos)} every client tick.
     * 48 covers a whole 47-block room from any corner, so a chain-mine anywhere in the room you are in is
     * timestamped on the tick it happens instead of at the next 500 ms discovery scan.
     */
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

    /** Chests seen this vault/level: position -> chest id (kept for future per-tier metrics). */
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

    public static int trackedCount() {
        return tracked.size();
    }

    /** Breaks caught by the per-tick near check this vault (the tick-exact ones). */
    public static int tickCheckBreaks() {
        return tickCheckBreaks;
    }

    /** Breaks caught only by the 500 ms discovery scan this vault (out of tick-check range, or a late chunk). */
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

        // Register newly-seen chests.
        tracked.putAll(current);

        // Count broken chests: tracked positions that are still loaded but no longer a chest.
        int mined = 0;
        Iterator<Map.Entry<BlockPos, String>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, String> entry = it.next();
            if (current.containsKey(entry.getKey())) continue; // still present within scan radius
            if (checkMined(level, entry.getKey(), entry.getValue(), it)) mined++;
        }

        if (mined > 0) {
            scanBreaks += mined;
            MetricsTracker.get().onMined(mined);
        }
    }

    /**
     * Per-tick mined check over the tracked chests near the player, so a break lands on the tick it happens
     * (~50 ms, the server's own resolution) instead of being quantised to the 500 ms discovery {@link #scan}.
     * Only chests within {@link #TICK_CHECK_RADIUS} (Chebyshev) are inspected; everything else is left to the
     * scan, which still sweeps the whole tracked map.
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
     * Mined-detection for one tracked chest, shared by {@link #scan} and {@link #tickCheck}: an unloaded chunk
     * drops the entry silently (not a mine), a loaded position that is no longer a chest is a break (loot,
     * run log, route service) and is dropped, anything else stays tracked.
     *
     * @return true iff this position was counted as a break
     */
    private static boolean checkMined(Level level, BlockPos pos, String chestId,
                                      Iterator<Map.Entry<BlockPos, String>> it) {
        if (!level.isLoaded(pos)) {                 // chunk unloaded -> not a mine; drop
            it.remove();
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (isChest(state.getBlock())) return false; // still a chest -> keep tracking
        LootListener.get().onMinedChest(chestId);
        RunLog.breakEvent(pos, chestId);
        RouteService.onChestBroken(pos);
        it.remove();
        return true;
    }

    private ChestScanner() {}
}
