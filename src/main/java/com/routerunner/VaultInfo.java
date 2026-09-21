package com.routerunner;

import iskallia.vault.core.vault.ClientVaults;
import iskallia.vault.core.vault.Modifiers;
import iskallia.vault.core.vault.Vault;
import iskallia.vault.core.vault.stat.StatCollector;
import iskallia.vault.core.vault.stat.StatsCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guarded the_vault accessor for client-side vault metadata. Only classloaded from in-vault code
 * paths (the_vault is guaranteed present in the vault dimension). Keeps the rest of the mod free
 * of a hard the_vault dependency.
 */
public final class VaultInfo {

    /** Current vault's unique id (Vault.ID is client-synced), or null if unavailable. */
    public static String getVaultId() {
        return ClientVaults.getActive()
                .map(v -> v.has(Vault.ID) ? String.valueOf(v.get(Vault.ID)) : null)
                .orElse(null);
    }

    /** Whether the vault is flagged finished (client-synced Vault.FINISHED). */
    public static boolean isFinished() {
        return ClientVaults.getActive().map(v -> v.has(Vault.FINISHED)).orElse(false);
    }

    /** Displayed crystal modifiers as "Nx Name" strings (includes bonus/cascade), newest run state. */
    public static List<String> getModifierSummary() {
        return ClientVaults.getActive().map(v -> {
            List<String> out = new ArrayList<>();
            if (v.has(Vault.MODIFIERS)) {
                Modifiers mods = v.get(Vault.MODIFIERS);
                var group = mods.getDisplayGroup();
                for (var modifier : group.keySet()) {
                    out.add(group.getInt(modifier) + "x " + modifier.getDisplayName());
                }
            }
            return out;
        }).orElseGet(ArrayList::new);
    }

    /**
     * Room template id at the given grid-region index for the local player, or null. Mirrors the
     * client minimap's read-chain: ClientVaults → Vault.STATS → StatsCollector.get(uuid) →
     * StatCollector.ROOMS_DISCOVERED, keyed by BlockPos(regionX, 0, regionZ). The room is only
     * present once it's been discovered (entered), which is exactly when we want to route it.
     */
    public static String getRoomIdAt(int regionX, int regionZ, UUID playerId) {
        return ClientVaults.getActive().map(v -> {
            if (!v.has(Vault.STATS)) return null;
            StatsCollector stats = v.get(Vault.STATS);
            if (stats == null || stats.getMap() == null) return null;
            StatCollector stat = stats.get(playerId);
            if (stat == null) return null;
            Map<BlockPos, ResourceLocation> rooms = stat.getRoomsDiscovered();
            ResourceLocation id = rooms.get(new BlockPos(regionX, 0, regionZ));
            return id == null ? null : id.toString();
        }).orElse(null);
    }

    private VaultInfo() {}
}
