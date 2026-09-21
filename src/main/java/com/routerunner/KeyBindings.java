package com.routerunner;

import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Key mappings. Default open-menu key is left bracket ([), rebindable in Controls. */
public final class KeyBindings {
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.routerunner.open_menu",
            GLFW.GLFW_KEY_LEFT_BRACKET,
            "key.categories.routerunner");

    private KeyBindings() {}
}
