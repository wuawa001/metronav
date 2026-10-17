package com.metronav;

import com.metronav.gui.MapScreenImpl;
import com.metronav.nav.NavigationSession;
import com.metronav.render.NavHud;
import com.metronav.render.NavWorldRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class MetroNavClient implements ClientModInitializer {
    public static KeyMapping openMapKey;

    @Override
    public void onInitializeClient() {
        MetroNavConfig.load();
        openMapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.metronav.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.metronav"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMapKey.consumeClick()) {
                if (client.level != null && client.player != null) {
                    Minecraft.getInstance().setScreen(new MapScreenImpl());
                }
            }
            NavigationSession.get().tick(client);
            MtrBridge.tick(client);
        });

        HudRenderCallback.EVENT.register((guiGraphics, tickDelta) -> NavHud.render(guiGraphics));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(NavWorldRenderer::render);
    }
}
