package com.routerunner;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Average target-chest density of the rooms entered this lap: each room counts once, with the number of target
 * chests it held at its FIRST scan (a scan of the cell ahead from the hallway counts), plus any target break seen
 * in that cell before it was ever scanned, so chests broken from the doorway are not lost. Cells with fewer than
 * {@link #MIN_ROOM_CHESTS} chests are hallways and never count. Cleared on vault entry and on New Lap, so a room
 * re-entered in a later lap is measured again at what it holds then.
 */
public final class DensityTracker {
    /** Cells at or below the hallway threshold are not rooms. */
    public static final int MIN_ROOM_CHESTS = 25;

    private static final Map<Long, Integer> firstCount = new HashMap<>();
    private static final Map<Long, Integer> preScanBreaks = new HashMap<>();
    private static final Set<Long> entered = new LinkedHashSet<>();

    private DensityTracker() {}

    /** A cell was scanned; only the first scan of a cell is kept. */
    public static synchronized void onScan(long cellKey, int targetChests) {
        if (firstCount.containsKey(cellKey)) return;
        firstCount.put(cellKey, targetChests + preScanBreaks.getOrDefault(cellKey, 0));
    }

    /** The player stands in the cell; it joins the lap's average once its first scan is known. */
    public static synchronized void onEnter(long cellKey) {
        entered.add(cellKey);
    }

    /** A tracked chest broke; if its cell has not been scanned yet, remember it so the first scan is corrected. */
    public static synchronized void onBreak(BlockPos pos) {
        long key = cellKey(Math.floorDiv(pos.getX(), RoomGeometry.CELL), Math.floorDiv(pos.getZ(), RoomGeometry.CELL));
        if (firstCount.containsKey(key)) return;
        preScanBreaks.merge(key, 1, Integer::sum);
    }

    /** Mean first-scan chest count over the rooms entered this lap, or -1 when none has been entered. */
    public static synchronized double average() {
        double sum = 0;
        int n = 0;
        for (long key : entered) {
            Integer c = firstCount.get(key);
            if (c == null || c < MIN_ROOM_CHESTS) continue;
            sum += c;
            n++;
        }
        return n == 0 ? -1 : sum / n;
    }

    /** Rooms currently in the average. */
    public static synchronized int rooms() {
        int n = 0;
        for (long key : entered) {
            Integer c = firstCount.get(key);
            if (c != null && c >= MIN_ROOM_CHESTS) n++;
        }
        return n;
    }

    public static synchronized void reset() {
        firstCount.clear();
        preScanBreaks.clear();
        entered.clear();
    }

    public static long cellKey(int rx, int rz) {
        return (((long) rx) << 32) ^ (rz & 0xFFFFFFFFL);
    }
}
