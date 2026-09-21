package com.routerunner;

import net.minecraftforge.fml.common.Mod;

/**
 * Routerunner: clientside Wold's Vaults chest-looting metrics and routing. Owns the mod id and loads the config;
 * client wiring lives in {@link ClientSetup} and {@link ClientEvents}.
 */
@Mod(Routerunner.MOD_ID)
public class Routerunner {
    public static final String MOD_ID = "routerunner";
    /** Stamped on every run log's {@code vault_enter}; keep in step with mods.toml / build.gradle. */
    public static final String MOD_VERSION = "0.1.0";

    public Routerunner() {
        RouterunnerConfig.load();
    }
}
