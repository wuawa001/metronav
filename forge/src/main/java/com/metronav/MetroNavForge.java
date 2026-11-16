package com.metronav;

import com.metronav.gui.MapScreen;
import com.metronav.nav.NavigationSession;
import com.metronav.render.NavHud;
import com.metronav.render.NavWorldRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Forge 1.20.1 客户端入口。
 *
 * <p>和 Fabric 版的差别只有「事件怎么接」：tick / HUD / 世界渲染三处换成了 Forge 事件，
 * 其余（导航逻辑、界面、路网）完全共用同一份源码。
 */
@Mod(MetroNavForge.MOD_ID)
public final class MetroNavForge {

    public static final String MOD_ID = "metronav";
    public static KeyMapping openMapKey;

    public MetroNavForge() {
        MetroNavConfig.load();
    }

    /** 客户端事件：只有物主线程需要的都挂在这里。 */
    @Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            openMapKey = new KeyMapping("key.metronav.open", InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_N, "category.metronav");
            event.register(openMapKey);
        }
    }

    /** 游戏内事件（FORGE 总线）。 */
    @Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeBus {

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (openMapKey != null) {
                while (openMapKey.consumeClick()) {
                    if (mc.level != null && mc.player != null) {
                        mc.setScreen(new MapScreen());
                    }
                }
            }
            NavigationSession.get().tick(mc);
            MtrBridge.tick(mc);
        }

        @SubscribeEvent
        public static void onHud(RenderGuiOverlayEvent.Post event) {
            if (!event.getOverlay().id().getPath().equals("hotbar")) {
                return; // 只画一次，别每个 overlay 都重复画
            }
            NavHud.render(event.getGuiGraphics());
        }

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                NavWorldRenderer.render(event);
            }
        }
    }
}
