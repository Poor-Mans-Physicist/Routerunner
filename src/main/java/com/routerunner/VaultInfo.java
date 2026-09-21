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
 * the_vault accessor for client-side vault metadata. Only classloaded from in-vault code paths, so the rest
 * of the mod has no hard the_vault dependency.
 */
public final class VaultInfo {

    /** Current vault's unique id (Vault.ID is client-synced), or null if unavailable. */
    public static String getVaultId() {
        return ClientVaults.getActive()
                .map(v -> v.has(Vault.ID) ? String.valueOf(v.get(Vault.ID)) : null)
                .orElse(null);
    }

    /** Displayed crystal modifiers as "Nx Name" strings, bonus/cascade included. */
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
     * Room template id at the given grid-region index from the player's discovered rooms (the minimap's
     * source), or null until the room has been entered.
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
