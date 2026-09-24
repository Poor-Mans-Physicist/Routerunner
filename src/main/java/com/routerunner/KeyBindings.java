package com.routerunner;

import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Key mappings. Defaults: open menu = left bracket ([), switch time model = right bracket (]); rebindable in Controls. */
public final class KeyBindings {
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.routerunner.open_menu",
            GLFW.GLFW_KEY_LEFT_BRACKET,
            "key.categories.routerunner");
    /** Flip the lane planner between the learned and the simplified time model (A/B testing, per lap). */
    public static final KeyMapping TOGGLE_TIME_MODEL = new KeyMapping(
            "key.routerunner.toggle_time_model",
            GLFW.GLFW_KEY_RIGHT_BRACKET,
            "key.categories.routerunner");

    private KeyBindings() {}
}
