package com.metronav.render;

import com.metronav.MetroNavConfig;
import com.metronav.nav.NavigationSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class NavHud {
    private static final String[] ARROWS = {"↓", "↙", "←", "↖", "↑", "↗", "→", "↘"};

    private NavHud() {}

    public static void render(GuiGraphics g) {
        NavigationSession session = NavigationSession.get();
        if (!session.isActive()) {
            return;
        }
        if (!MetroNavConfig.get().hudEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (session.isArrived()) {
            lines.add(Component.literal("✔ " + session.destinationName() + " · 已到达目的地"));
            colors.add(0x55FF55);
        } else {
            NavigationSession.Waypoint target = session.currentTarget();
            if (target != null) {
                lines.add(Component.literal("● " + target.lineName));
                colors.add(0xFF000000 | target.lineColor);
                String action = (target.action == null || target.action.isEmpty()) ? "前往 " + target.title : target.action;
                lines.add(Component.literal(action));
                colors.add(0xFFFFFF);
                double dist = session.remainingDistance(mc.player.getX(), mc.player.getZ());
                lines.add(Component.literal(arrow(mc, target) + " 距离: 约 " + Math.round(dist) + " m"));
                colors.add(0xD0D0D0);
                lines.add(Component.literal("预计: 约 "
                        + Math.max(1, Math.round(session.remainingSeconds(mc.player.getX(), mc.player.getY(), mc.player.getZ()) / 60.0))
                        + " 分钟"));
                colors.add(0xD0D0D0);
            }
            String upcoming = session.upcomingTransfer();
            if (upcoming != null) {
                lines.add(Component.literal("下一站换乘: " + upcoming));
                colors.add(0xFFFFB020);
            }
        }
        if (lines.isEmpty()) {
            return;
        }

        int maxW = 0;
        for (Component c : lines) {
            maxW = Math.max(maxW, mc.font.width(c));
        }
        int px = 8;
        int py = 8;
        int pad = 6;
        int lineH = 11;
        int w = maxW + pad * 2;
        int h = pad * 2 + lines.size() * lineH;
        g.fill(px, py, px + w, py + h, 0x99000000);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(mc.font, lines.get(i), px + pad, py + pad + i * lineH, colors.get(i));
        }
        g.drawString(mc.font, "按 N 打开地图", px, py + h + 3, 0x80FFFFFF);
    }

    private static String arrow(Minecraft mc, NavigationSession.Waypoint target) {
        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        if (dx * dx + dz * dz < 1) {
            return "•";
        }
        double yaw = Math.toRadians(mc.player.getYRot());
        double targetYaw = Math.atan2(-dx, dz);
        double rel = Math.toDegrees(Math.atan2(Math.sin(targetYaw - yaw), Math.cos(targetYaw - yaw)));
        int idx = ((int) Math.floor((rel + 180.0) / 45.0)) % 8;
        if (idx < 0) {
            idx += 8;
        }
        return ARROWS[idx];
    }
}
