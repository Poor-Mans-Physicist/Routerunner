package com.routerunner;

import net.minecraftforge.fml.common.Mod;

/**
 * Routerunner — clientside Wold's Vaults chest-looting tools.
 *
 * Phase 0 scope: live chests/min metrics on a configurable, draggable HUD.
 * Client wiring (keybind, HUD overlay, tick/scan) lives in {@link ClientSetup} and
 * {@link ClientEvents}; this class only owns the mod id and eager config load.
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
