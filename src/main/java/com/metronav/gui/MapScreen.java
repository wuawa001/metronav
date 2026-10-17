package com.metronav.gui;

import com.metronav.MetroNavConfig;
import com.metronav.MtrBridge;
import com.metronav.map.MapCache;
import com.metronav.map.XaeroMapSource;
import com.metronav.nav.NavigationSession;
import com.metronav.nav.RailwayIndex;
import com.metronav.nav.RoutePlanner;
import com.metronav.nav.WalkPlanner;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtr.core.data.ClientData;

public class MapScreen extends Screen {
    private static final int TOP = 26;

    private final MapCache mapCache = new MapCache();
    private RailwayIndex index = new RailwayIndex();
    private double viewCX;
    private double viewCZ;
    private double zoom = 1.0;
    private boolean follow = true;
    private boolean pressed;
    private double pressX;
    private double pressY;
    private double[] destPos;
    private Long destStationId;
    private RoutePlanner.Plan previewPlan;
    private String statusText = "";
    private int refreshTimer = 0;
    private int lastRouteCount = -1;

    public MapScreen() {
        super(Component.literal("MetroNav 地图导航"));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            viewCX = mc.player.getX();
            viewCZ = mc.player.getZ();
        }
        index = buildIndex();
        mapCache.setIndex(index);
        XaeroMapSource xaero = XaeroMapSource.detect(mc);
        mapCache.setXaero(xaero);
        if (xaero != null) {
            statusText = "地图来源: Xaero(" + xaero.regionCount() + " 区域) · 车站 "
                    + index.stations.size() + " / 线路 " + index.routes.size();
        }
    }

    private static RailwayIndex buildIndex() {
        ClientData data = MtrBridge.getClientData();
        if (data == null) {
            return new RailwayIndex();
        }
        return RailwayIndex.build(data);
    }

    @Override
    protected void init() {
        MtrBridge.requestFullData();
        double minZoom = Math.max(this.width, this.height - TOP) / (double) mapCache.texSize();
        if (zoom < minZoom) {
            zoom = minZoom;
        }
        if (zoom > 8.0) {
            zoom = 8.0;
        }
        int x = 6;
        x = addTopButton(x, "清除目的地", b -> clearDestination());
        x = addTopButton(x, "车站列表", b -> {
            if (MtrBridge.available()) {
                Minecraft.getInstance().setScreen(new NavScreen());
            } else {
                Minecraft.getInstance().setScreen(new DownloadPromptScreen());
            }
        });
        x = addTopButton(x, "刷新数据", b -> refreshData());
        x = addTopButton(x, "回到玩家", b -> {
            follow = true;
            snapViewToPlayer();
        });
        addTopButton(x, "关闭", b -> this.onClose());
        if (statusText.isEmpty()) {
            statusText = "已载入 " + index.stations.size() + " 个车站 / " + index.routes.size() + " 条线路";
        }
    }

    private int addTopButton(int x, String label, Button.OnPress action) {
        Button b = Button.builder(Component.literal(label), action).bounds(x, 3, 88, 20).build();
        addRenderableWidget(b);
        return x + 94;
    }

    private void refreshData() {
        index = buildIndex();
        lastRouteCount = index.routes.size();
        mapCache.setIndex(index);
        statusText = "已载入 " + index.stations.size() + " 个车站 / " + index.routes.size() + " 条线路"
                + (index.routes.isEmpty() ? "（正在向服务器请求线路数据…）" : "");
    }

    private void snapViewToPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            viewCX = mc.player.getX();
            viewCZ = mc.player.getZ();
        }
    }

    private void clearDestination() {
        destPos = null;
        destStationId = null;
        previewPlan = null;
        NavigationSession.get().stop();
        statusText = "已清除目的地";
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (follow && mc.player != null) {
            viewCX = mc.player.getX();
            viewCZ = mc.player.getZ();
        }
        if (mc.level != null) {
            mapCache.updateRegion(mc.level, (int) Math.floor(viewCX), (int) Math.floor(viewCZ));
            mapCache.update(mc);
        }
        clampView();
        if (++refreshTimer % 40 == 0) {
            RailwayIndex fresh = buildIndex();
            if (fresh.routes.size() != lastRouteCount || fresh.stations.size() != index.stations.size()) {
                index = fresh;
                lastRouteCount = fresh.routes.size();
                mapCache.setIndex(index);
                statusText = "已载入 " + index.stations.size() + " 个车站 / " + index.routes.size() + " 条线路"
                        + (index.routes.isEmpty() ? "（正在向服务器请求线路数据…）" : "");
            }
            if (index.routes.isEmpty()) {
                MtrBridge.requestFullData();
            }
        }
    }

    private void clampView() {
        // Loose clamp: allow free panning; the loaded region recenters itself as the view moves.
        double lim = 30000000.0;
        viewCX = Math.max(-lim, Math.min(lim, viewCX));
        viewCZ = Math.max(-lim, Math.min(lim, viewCZ));
    }

    private int mapCX() {
        return this.width / 2;
    }

    private int mapCY() {
        return TOP + (this.height - TOP) / 2;
    }

    private int sx(double wx) {
        return (int) Math.round(mapCX() + (wx - viewCX) * zoom);
    }

    private int sy(double wz) {
        return (int) Math.round(mapCY() + (wz - viewCZ) * zoom);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = mapCX();
        int cy = mapCY();

        int x0 = mapCache.regionX0();
        int z0 = mapCache.regionZ0();
        if (mapCache.ready()) {
            double drawX = cx + (x0 - viewCX) * zoom;
            double drawY = cy + (z0 - viewCZ) * zoom;
            int sx0 = Math.max(0, (int) Math.floor((0 - drawX) / zoom));
            int sx1 = Math.min(mapCache.texSize(), (int) Math.ceil((this.width - drawX) / zoom));
            int sy0 = Math.max(0, (int) Math.floor((TOP - drawY) / zoom));
            int sy1 = Math.min(mapCache.texSize(), (int) Math.ceil((this.height - drawY) / zoom));
            if (sx1 > sx0 && sy1 > sy0) {
                int px = (int) Math.floor(drawX + sx0 * zoom);
                int py = (int) Math.floor(drawY + sy0 * zoom);
                int sw = sx1 - sx0;
                int sh = sy1 - sy0;
                int pw = (int) Math.ceil(sw * zoom) + 1;
                int ph = (int) Math.ceil(sh * zoom) + 1;
                int ts = mapCache.texSize();
                g.blit(MapCache.BASE_LOC, px, py, pw, ph, sx0, sy0, sw, sh, ts, ts);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                g.blit(MapCache.OVERLAY_LOC, px, py, pw, ph, sx0, sy0, sw, sh, ts, ts);
                RenderSystem.disableBlend();
            }
        } else {
            g.drawCenteredString(this.font, "地图加载中…", cx, cy - 8, 0xFFFFFF);
        }

        if (previewPlan != null) {
            drawPlan(g);
        }
        drawStations(g);
        drawDestination(g);
        drawPlayer(g);

        g.drawString(this.font, statusText, 6, this.height - 12, 0xCCCCCC);
        String zoomText = "缩放 x" + String.format(java.util.Locale.ROOT, "%.2f", zoom);
        g.drawString(this.font, zoomText, this.width - this.font.width(zoomText) - 6, this.height - 12, 0xCCCCCC);
    }

    private void drawPlan(GuiGraphics g) {
        if (previewPlan == null) {
            return;
        }
        for (RoutePlanner.Leg leg : previewPlan.legs) {
            int color = leg.isMetro() ? (0xFF000000 | leg.color) : 0xFF00E5FF;
            int thickness = leg.isMetro() ? 3 : 2;
            for (int i = 0; i + 1 < leg.points.size(); i++) {
                double[] a = leg.points.get(i);
                double[] b = leg.points.get(i + 1);
                drawWorldLine(g, a[0], a[2], b[0], b[2], color, thickness);
            }
        }
    }

    private void drawStations(GuiGraphics g) {
        int names = 0;
        boolean showNames = zoom >= 1.6;
        for (RailwayIndex.StationNode sn : index.stations.values()) {
            int x = sx(sn.x);
            int y = sy(sn.z);
            if (x < -4 || y < TOP - 4 || x > this.width + 4 || y > this.height + 4) {
                continue;
            }
            int col = 0xFF000000 | sn.color;
            g.fill(x - 3, y - 3, x + 3, y + 3, 0xE6000000);
            g.fill(x - 2, y - 2, x + 2, y + 2, col);
            if (showNames && names < 80) {
                g.drawString(this.font, sn.name, x + 4, y - 4, 0xFFFFFF);
                names++;
            }
        }
    }

    private void drawDestination(GuiGraphics g) {
        if (destPos == null) {
            return;
        }
        int x = sx(destPos[0]);
        int y = sy(destPos[2]);
        if (x < -20 || y < TOP - 20 || x > this.width + 20 || y > this.height + 20) {
            return;
        }
        g.fill(x - 1, y - 8, x + 2, y + 9, 0xFFFF4040);
        g.fill(x - 8, y - 1, x + 9, y + 2, 0xFFFF4040);
        g.fill(x - 3, y - 3, x + 4, y + 4, 0xFFFFFFFF);
        String label = "目的地";
        if (destStationId != null) {
            RailwayIndex.StationNode sn = index.stations.get(destStationId);
            if (sn != null) {
                label = sn.name;
            }
        }
        g.drawString(this.font, label, x + 9, y - 4, 0xFFFF6060);
    }

    private void drawPlayer(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        int x = sx(mc.player.getX());
        int y = sy(mc.player.getZ());
        if (x < -10 || y < TOP - 10 || x > this.width + 10 || y > this.height + 10) {
            return;
        }
        g.fill(x - 4, y - 4, x + 5, y + 5, 0xCC000000);
        g.fill(x - 3, y - 3, x + 4, y + 4, 0xFFFFFFFF);
    }

    private void drawWorldLine(GuiGraphics g, double ax, double az, double bx, double bz, int color, int thickness) {
        double x1 = mapCX() + (ax - viewCX) * zoom;
        double y1 = mapCY() + (az - viewCZ) * zoom;
        double x2 = mapCX() + (bx - viewCX) * zoom;
        double y2 = mapCY() + (bz - viewCZ) * zoom;
        double dx = x2 - x1;
        double dy = y2 - y1;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) {
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int px = (int) Math.round(x1 + dx * i / steps);
            int py = (int) Math.round(y1 + dy * i / steps);
            g.fill(px, py, px + thickness, py + thickness, color);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (my < TOP) {
            return false;
        }
        if (button == 0) {
            pressed = true;
            pressX = mx;
            pressY = my;
            return true;
        }
        if (button == 1) {
            clearDestination();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && pressed) {
            pressed = false;
            double moved = Math.hypot(mx - pressX, my - pressY);
            if (moved < 5) {
                clickSetDestination(pressX, pressY);
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && pressed) {
            viewCX -= dx / zoom;
            viewCZ -= dy / zoom;
            follow = false;
            clampView();
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    protected void applyScroll(double amount) {
        double minZoom = Math.max(this.width, this.height - TOP) / (double) mapCache.texSize();
        double factor = amount > 0 ? 1.2 : 1 / 1.2;
        zoom = Math.max(minZoom, Math.min(8.0, zoom * factor));
    }

    private void clickSetDestination(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        double wx = viewCX + (mx - mapCX()) / zoom;
        double wz = viewCZ + (my - mapCY()) / zoom;

        RailwayIndex.StationNode snap = index.nearest(wx, mc.player.getY(), wz, 24);
        double dx;
        double dy;
        double dz;
        String destName;
        if (snap != null) {
            destStationId = snap.id;
            dx = snap.x;
            dy = snap.y;
            dz = snap.z;
            destName = snap.name;
        } else {
            destStationId = null;
            dy = WalkPlanner.surfaceY(mc.level, wx, mc.player.getY(), mc.player.getY());
            dx = wx;
            dz = wz;
            destName = null;
        }
        double pdx = dx - mc.player.getX();
        double pdz = dz - mc.player.getZ();
        if (Math.hypot(pdx, pdz) < 3) {
            statusText = "目标就在你脚下";
            return;
        }
        destPos = new double[]{dx, dy, dz};
        previewPlan = RoutePlanner.planMixed(index, mc.level,
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                dx, dy, dz, MetroNavConfig.get());
        if (previewPlan != null && !previewPlan.legs.isEmpty()) {
            NavigationSession.get().startMixed(index, previewPlan);
            int mins = Math.max(1, (int) Math.ceil(previewPlan.estSeconds / 60.0));
            statusText = "已开始导航 → " + (destName != null ? destName : "目标点")
                    + " · 约 " + mins + " 分钟 · " + Math.round(previewPlan.totalDistance) + "m";
        } else {
            statusText = "无法规划路线";
        }
    }
}
