package com.metronav.render;

import com.metronav.MetroNavConfig;
import com.metronav.nav.NavReadout;
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
        NavReadout readout = NavReadout.of(session, mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (readout == null) {
            return;
        }
        // 顶栏：线路 + 目的地（线路色当作归属标识，换乘时一眼能看出现在在哪条线上）
        String head = readout.lineName.isEmpty()
                ? readout.header
                : "● " + readout.lineName + "  ·  " + readout.header;
        lines.add(Component.literal(head));
        colors.add(0xFF000000 | (readout.lineColor != 0 ? readout.lineColor : 0xFFFFFF));

        NavigationSession.Waypoint target = session.currentTarget();
        if (readout.state == NavReadout.State.ARRIVED) {
            lines.add(Component.literal(readout.instruction));
            colors.add(0x55FF55);
        } else {
            // 主提示：乘车时是「下一站 / 准备下车」，步行时是「去哪个方向、做什么」
            lines.add(Component.literal(readout.instruction));
            colors.add(readout.state == NavReadout.State.OFF_ROUTE ? 0xFF6060 : 0xFFFFFF);

            String dist = "距离: 剩余 " + readout.distanceLabel;
            if (readout.nextLabel != null) {
                dist += " · " + readout.nextLabel;
            }
            lines.add(Component.literal((target != null ? arrow(mc, target, session.generation()) : "•") + " " + dist));
            colors.add(0xD0D0D0);
            lines.add(Component.literal("预计: " + readout.etaLabel));
            colors.add(0xD0D0D0);
            if (readout.hint != null) {
                lines.add(Component.literal(readout.hint));
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

    private static double lastRel = 0;
    private static int lastGeneration = -1;
    private static int lastIdx = -1;

    private static String arrow(Minecraft mc, NavigationSession.Waypoint target, int generation) {
        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        // 已在目标点正上/正下方：水平向量接近 0，atan2 会抖动 → 直接显示"到达点"。
        // 但航点每 12 格一个，正常走路时会有一半时间落在 6 格内，箭头会一直闪成"•"，
        // 所以贴住航点时就保持上一次的方向，方向感不丢。
        if (horiz < 6.0) {
            return lastIdx >= 0 ? ARROWS[lastIdx] : "•";
        }
        double yaw = Math.toRadians(mc.player.getYRot());
        double targetYaw = Math.atan2(-dx, dz);
        double rel = Math.toDegrees(Math.atan2(Math.sin(targetYaw - yaw), Math.cos(targetYaw - yaw)));
        boolean freshSession = generation != lastGeneration;
        if (freshSession) {
            // 新的一次导航：直接取真实方位，不要从上一个任务的朝向慢慢转过来
            lastGeneration = generation;
            lastRel = rel;
        }
        // 低通滤波：目标点在两个航点间来回切换时，箭头会疯狂转圈；这里限制每帧最大转角
        double delta = rel - lastRel;
        while (delta > 180) delta -= 360;
        while (delta < -180) delta += 360;
        // 目标确实换了方向（换镜/换段）时直接跳过去，否则要拧十几帧才追上
        if (Math.abs(delta) > 90.0) {
            lastRel = rel;
        } else {
            double maxStep = 18.0;
            if (delta > maxStep) delta = maxStep;
            if (delta < -maxStep) delta = -maxStep;
            lastRel += delta;
        }
        rel = lastRel;
        int idx = ((int) Math.floor((rel + 180.0) / 45.0)) % 8;
        if (idx < 0) {
            idx += 8;
        }
        // 方位正好压在 45° 分界线上时，抖动会让箭头在两个方向间来回闪。
        // 只有越过分界线 8° 以上才换箭头。
        if (freshSession) {
            lastIdx = idx;
        } else if (idx != lastIdx) {
            double lo = lastIdx * 45.0 - 180.0;
            double hi = lo + 45.0;
            if (rel > hi + 8.0 || rel < lo - 8.0) {
                lastIdx = idx;
            } else {
                idx = lastIdx;
            }
        }
        return ARROWS[idx];
    }
}
