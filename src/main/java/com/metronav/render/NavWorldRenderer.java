package com.metronav.render;

import com.metronav.MetroNavConfig;
import com.metronav.nav.NavigationSession;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class NavWorldRenderer {
    private NavWorldRenderer() {}

    public static void render(WorldRenderContext context) {
        NavigationSession session = NavigationSession.get();
        if (!session.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        MetroNavConfig cfg = MetroNavConfig.get();

        Camera camera = context.camera();
        PoseStack ms = context.matrixStack();
        List<NavigationSession.Waypoint> wps = session.remainingWaypoints();
        NavigationSession.Waypoint target = session.currentTarget();
        if (wps.isEmpty() || target == null) {
            return;
        }

        if (cfg.pathLineEnabled) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.lineWidth(4.0F);
            Tesselator tess = Tesselator.getInstance();
            BufferBuilder buf = tess.getBuilder();
            buf.begin(VertexFormat.Mode.LINES, RenderType.lines().format());

            Vec3 eye = mc.player.getEyePosition();
            drawLine(buf, eye.x, eye.y, eye.z, target.x, target.y + yOffset(target), target.z, target.lineColor, 230);

            for (int i = 0; i + 1 < wps.size(); i++) {
                NavigationSession.Waypoint a = wps.get(i);
                NavigationSession.Waypoint b = wps.get(i + 1);
                if (a.legIndex != b.legIndex) {
                    continue;
                }
                drawLine(buf, a.x, a.y + yOffset(a), a.z, b.x, b.y + yOffset(b), b.z, a.lineColor, 170);
            }
            tess.end();
            RenderSystem.lineWidth(1.0F);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }

        if (cfg.beaconEnabled) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            Tesselator tess = Tesselator.getInstance();
            BufferBuilder buf = tess.getBuilder();
            buf.begin(VertexFormat.Mode.LINES, RenderType.lines().format());
            double bx = target.x;
            double by = target.y;
            double bz = target.z;
            if (target.isMetro()) {
                RenderSystem.lineWidth(6.0F);
                drawLine(buf, bx, by + 1, bz, bx, by + 48, bz, target.lineColor, 200);
                drawLine(buf, bx - 0.75, by + 48, bz, bx + 1.75, by + 48, bz, target.lineColor, 255);
                drawLine(buf, bx, by + 48, bz - 0.75, bx, by + 48, bz + 1.75, target.lineColor, 255);
            } else {
                RenderSystem.lineWidth(5.0F);
                drawLine(buf, bx, by + 0.2, bz, bx, by + 7, bz, target.lineColor, 230);
            }
            tess.end();
            RenderSystem.lineWidth(1.0F);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();

            String text = target.title
                    + ((target.lineName == null || target.lineName.isEmpty()) ? "" : " · " + target.lineName);
            double labelY = target.isMetro() ? by + 52 : by + 9;
            ms.pushPose();
            ms.translate(bx - camera.getPosition().x, labelY - camera.getPosition().y, bz - camera.getPosition().z);
            ms.mulPose(camera.rotation());
            ms.scale(-0.03F, -0.03F, 0.03F);
            var pose = ms.last().pose();
            Font font = mc.font;
            FormattedCharSequence seq = Component.literal(text).getVisualOrderText();
            float w = font.width(seq);
            font.drawInBatch(seq, -w / 2.0F, 0.0F, 0xFFFFFFFF, false, pose, context.consumers(),
                    Font.DisplayMode.NORMAL, 0x88000000, LightTexture.FULL_BRIGHT);
            ms.popPose();
        }
    }

    private static double yOffset(NavigationSession.Waypoint w) {
        return w.isMetro() ? 2.0 : 0.4;
    }

    private static void drawLine(BufferBuilder buf, double x1, double y1, double z1,
                                 double x2, double y2, double z2, int rgb, int alpha) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        buf.vertex(x1, y1, z1).color(r, g, b, alpha).normal(0, 1, 0).endVertex();
        buf.vertex(x2, y2, z2).color(r, g, b, alpha).normal(0, 1, 0).endVertex();
    }
}
