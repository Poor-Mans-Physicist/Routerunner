package com.routerunner;

import com.mojang.logging.LogUtils;
import iskallia.vault.core.event.CommonEvents;
import iskallia.vault.core.event.common.NewItemPickupEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Subscribes to the_vault's {@code CommonEvents.NEW_PICKUP_EVENT} (fires client-side for normal and backpack
 * pickups) and forwards the local player's pickups by item id to {@link LootListener}. Classload only when
 * the_vault is present.
 */
public final class VaultPickupHook {
    private static final Logger LOG = LogUtils.getLogger();
    private static final UUID KEY = UUID.fromString("b0f4a1e2-0000-4000-8000-a1b2c3d4e5f6");

    private static boolean registered = false;

    public static void init() {
        if (registered) return;
        registered = true;
        CommonEvents.NEW_PICKUP_EVENT.register(KEY, (NewItemPickupEvent.Data data) -> {
            try {
                if (data.getPlayer() != Minecraft.getInstance().player) return;
                ItemStack stack = data.getItemStack();
                if (stack == null || stack.isEmpty()) return;
                int count = stack.getCount();

                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (id != null) {
                    LootListener.get().record(id.toString(), count, stack);
                }
            } catch (Throwable t) {
                LOG.error("[Routerunner] error handling pickup", t);
            }
        });
        LOG.info("[Routerunner] Subscribed to the_vault NEW_PICKUP_EVENT (backpack-safe loot tracking active).");
    }

    private VaultPickupHook() {}
}
