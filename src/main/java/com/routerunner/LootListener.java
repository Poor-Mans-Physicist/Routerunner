package com.routerunner;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-vault loot tracking for the four chest types. Pickups are fed in by {@link VaultPickupHook}
 * (which owns all the_vault references); this class keys on item-id strings.
 *
 * <p>Item rates use the chest metrics' clocks, measured from when tracking engages (immediately for a
 * manual type, after 100 mined chests in AUTO). Engagement offsets and per-item totals are persisted.
 */
public class LootListener {
    private static final long WINDOW_MS = 60_000L;
    private static final int AUTO_SAMPLE = 100;

    private static final LootListener INSTANCE = new LootListener();
    public static LootListener get() { return INSTANCE; }

    private static final Map<String, List<String>> ITEMS = new LinkedHashMap<>();
    static {
        ITEMS.put("gilded", List.of("the_vault:vault_diamond", "the_vault:vault_essence", "the_vault:key_piece", "the_vault:jewel_pouch"));
        ITEMS.put("living", List.of("the_vault:knowledge_star_essence", "the_vault:burger_sauce", "woldsvaults:soul_ichor", "the_vault:inscription"));
        ITEMS.put("ornate", List.of("the_vault:carbon_nugget", "the_vault:wild_focus", "the_vault:fundamental_focus"));
        ITEMS.put("wooden", List.of("the_vault:vault_plating", "the_vault:vault_essence", "the_vault:vault_sweets", "the_vault:bounty_pearl"));
    }

    private String resolvedType = null;
    private boolean engaged = false;
    /** MetricsTracker net/active clock values at engagement. */
    private long lootStartNet = 0L;
    private long lootStartActive = 0L;

    private final Map<String, Integer> autoCounts = new HashMap<>();
    private int autoSampled = 0;

    private final Map<String, Tracker> trackers = new HashMap<>();

    private static class Tracker {
        long total = 0;
        /** Sliding-window entries: {activeMs stamp, qty}. */
        final Deque<long[]> recent = new ArrayDeque<>();
        ItemStack sprite = ItemStack.EMPTY;
    }

    public void reset() {
        resolvedType = null;
        engaged = false;
        lootStartNet = 0L;
        lootStartActive = 0L;
        autoCounts.clear();
        autoSampled = 0;
        trackers.clear();
        RouterunnerConfig.TrackedChest mode = RouterunnerConfig.get().trackedChest;
        if (mode != RouterunnerConfig.TrackedChest.AUTO && mode != RouterunnerConfig.TrackedChest.ALL) {
            engageWith(mode.name().toLowerCase(Locale.ROOT));
        }
    }

    public void onModeChanged() {
        reset();
    }

    private void engageWith(String type) {
        resolvedType = type;
        engaged = true;
        lootStartNet = MetricsTracker.get().getNetMs();
        lootStartActive = MetricsTracker.get().getActiveMs();
        trackers.clear();
        for (String key : ITEMS.getOrDefault(type, List.of())) {
            Tracker t = new Tracker();
            t.sprite = makeStack(key);
            trackers.put(key, t);
        }
    }

    public void onMinedChest(String chestId) {
        if (RouterunnerConfig.get().trackedChest != RouterunnerConfig.TrackedChest.AUTO) return;
        if (engaged) return;
        String type = typeOf(chestId);
        if (type == null) return;
        autoCounts.merge(type, 1, Integer::sum);
        if (++autoSampled >= AUTO_SAMPLE) {
            String best = null;
            int bestN = -1;
            for (Map.Entry<String, Integer> e : autoCounts.entrySet()) {
                if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
            }
            if (best != null) engageWith(best);
        }
    }

    public void record(String key, int count, ItemStack stack) {
        if (!engaged || resolvedType == null || count <= 0) return;
        Tracker t = trackers.get(key);
        if (t == null) return;
        long now = MetricsTracker.get().getActiveMs();
        t.total += count;
        t.recent.addLast(new long[]{now, count});
        evict(t, now);
    }

    private void evict(Tracker t, long now) {
        long cutoff = now - WINDOW_MS;
        while (!t.recent.isEmpty() && t.recent.peekFirst()[0] < cutoff) t.recent.pollFirst();
    }

    public boolean isActive() {
        return engaged && resolvedType != null
                && RouterunnerConfig.get().trackedChest != RouterunnerConfig.TrackedChest.ALL;
    }

    public boolean autoWaiting() {
        return RouterunnerConfig.get().trackedChest == RouterunnerConfig.TrackedChest.AUTO && !engaged;
    }

    public int autoProgress() { return autoSampled; }
    public String getResolvedType() { return resolvedType; }
    public List<String> keysForDisplay() { return resolvedType == null ? List.of() : ITEMS.getOrDefault(resolvedType, List.of()); }

    public ItemStack sprite(String key) {
        Tracker t = trackers.get(key);
        return t == null ? ItemStack.EMPTY : t.sprite;
    }

    public double netPerMin(String key) {
        Tracker t = trackers.get(key);
        if (t == null) return 0.0;
        return rate(t.total, MetricsTracker.get().getNetMs() - lootStartNet);
    }

    public double activePerMin(String key) {
        Tracker t = trackers.get(key);
        if (t == null) return 0.0;
        return rate(t.total, MetricsTracker.get().getActiveMs() - lootStartActive);
    }

    public double slidingPerMin(String key) {
        Tracker t = trackers.get(key);
        if (t == null) return 0.0;
        long now = MetricsTracker.get().getActiveMs();
        evict(t, now);
        long sum = 0;
        for (long[] e : t.recent) sum += e[1];
        return sum * (60_000.0 / WINDOW_MS);
    }

    private static double rate(long count, long elapsedMs) {
        if (count == 0) return 0.0;
        double min = elapsedMs / 60_000.0;
        if (min < 1.0 / 60.0) min = 1.0 / 60.0;
        return count / min;
    }

    public boolean isEngaged() { return engaged; }
    public long getLootStartNet() { return lootStartNet; }
    public long getLootStartActive() { return lootStartActive; }
    public int getAutoSampled() { return autoSampled; }

    public Map<String, Integer> getAutoCountsCopy() {
        return new HashMap<>(autoCounts);
    }

    public Map<String, Long> getItemTotals() {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Tracker> e : trackers.entrySet()) out.put(e.getKey(), e.getValue().total);
        return out;
    }

    public void restore(String resolvedType, boolean engaged, long lootStartNet, long lootStartActive,
                        Map<String, Integer> autoCounts, int autoSampled, Map<String, Long> itemTotals) {
        this.resolvedType = resolvedType;
        this.engaged = engaged;
        this.lootStartNet = lootStartNet;
        this.lootStartActive = lootStartActive;
        this.autoCounts.clear();
        if (autoCounts != null) this.autoCounts.putAll(autoCounts);
        this.autoSampled = autoSampled;
        this.trackers.clear();
        if (engaged && resolvedType != null) {
            for (String key : ITEMS.getOrDefault(resolvedType, List.of())) {
                Tracker t = new Tracker();
                t.sprite = makeStack(key);
                if (itemTotals != null) t.total = itemTotals.getOrDefault(key, 0L);
                trackers.put(key, t);
            }
        }
    }

    public static List<String> itemsFor(String type) {
        return ITEMS.getOrDefault(type, List.of());
    }

    public static ItemStack previewSprite(String key) {
        return makeStack(key);
    }

    private static String typeOf(String chestId) {
        if (chestId == null) return null;
        if (chestId.contains("gilded")) return "gilded";
        if (chestId.contains("ornate")) return "ornate";
        if (chestId.contains("living")) return "living";
        if (chestId.contains("wooden")) return "wooden";
        return null;
    }

    private static ItemStack makeStack(String id) {
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}
