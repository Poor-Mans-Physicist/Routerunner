package com.routerunner;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.client.gui.OverlayRegistry;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Mod-bus, client-only setup: register the keybind and the HUD overlay. */
@Mod.EventBusSubscriber(modid = Routerunner.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ClientRegistry.registerKeyBinding(KeyBindings.OPEN_MENU));
        OverlayRegistry.registerOverlayTop("Routerunner HUD", RouterunnerHud.INSTANCE);

        if (net.minecraftforge.fml.ModList.get().isLoaded("the_vault")) {
            try {
                VaultPickupHook.init();
            } catch (Throwable t) {
                com.mojang.logging.LogUtils.getLogger()
                        .error("[Routerunner] Failed to hook the_vault pickup event; loot listener disabled.", t);
            }
        } else {
            com.mojang.logging.LogUtils.getLogger()
                    .warn("[Routerunner] the_vault not present; loot listener disabled.");
        }
    }

    private ClientSetup() {}
}
