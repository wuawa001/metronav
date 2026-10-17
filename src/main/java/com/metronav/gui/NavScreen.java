package com.metronav.gui;

import com.metronav.MetroNavConfig;
import com.metronav.MtrBridge;
import com.metronav.nav.NavigationSession;
import com.metronav.nav.NavText;
import com.metronav.nav.RailwayIndex;
import com.metronav.nav.RoutePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtr.core.data.ClientData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class NavScreen extends Screen {
    private static final int PAGE_SIZE = 8;

    private RailwayIndex index = new RailwayIndex();
    private String query = "";
    private String statusText = "正在读取 MTR 数据…";
    private String searchNote = "";
    private EditBox searchBox;
    private final List<Button> listButtons = new ArrayList<>();
    private int page = 0;
    private RailwayIndex.StationNode selected = null;
    private RoutePlanner.Plan plan = null;
    private Button startBtn;
    private Button stopBtn;
    private int refreshTimer = 0;
    private int lastRouteCount = -1;

    public NavScreen() {
        super(Component.literal("MetroNav 地铁导航"));
        rebuildIndex();
    }

    private void rebuildIndex() {
        ClientData data = MtrBridge.getClientData();
        if (data == null) {
            statusText = "未检测到 MTR 模组数据";
            return;
        }
        index = RailwayIndex.build(data);
        if (data.stations.isEmpty()) {
            statusText = "MTR 数据尚未同步（请先进入安装了 MTR 的世界）";
        } else {
            statusText = "车站 " + index.stations.size() + " · 线路 " + index.routes.size()
                    + " · 站台 " + data.platforms.size();
            if (index.routes.isEmpty()) {
                statusText += "（正在向服务器请求线路数据…）";
            }
        }
        lastRouteCount = index.routes.size();
    }

    @Override
    public void tick() {
        if (++refreshTimer % 40 == 0 && MtrBridge.available()) {
            RailwayIndex fresh = RailwayIndex.build(MtrBridge.getClientData());
            if (fresh.routes.size() != lastRouteCount || fresh.stations.size() != index.stations.size()) {
                rebuildIndex();
                refreshList();
            }
            if (index.routes.isEmpty()) {
                MtrBridge.requestFullData();
            }
        }
    }

    @Override
    protected void init() {
        MtrBridge.requestFullData();
        int x0 = this.width / 2 - 170;

        searchBox = new EditBox(this.font, x0, 76, 340, 20, Component.literal(""));
        searchBox.setMaxLength(40);
        searchBox.setHint(Component.literal("搜索车站名（也可在下方列表中直接选择）…"));
        searchBox.setValue(query);
        searchBox.setResponder(s -> {
            query = s;
            page = 0;
            refreshList();
        });
        addRenderableWidget(searchBox);
        refreshList();

        Button prevBtn = Button.builder(Component.literal("上一页"), b -> {
            page = Math.max(0, page - 1);
            refreshList();
        }).bounds(x0, 282, 60, 20).build();
        Button nextBtn = Button.builder(Component.literal("下一页"), b -> {
            page++;
            refreshList();
        }).bounds(x0 + 66, 282, 60, 20).build();
        addRenderableWidget(prevBtn);
        addRenderableWidget(nextBtn);

        int by = this.height - 84;
        Button hudBtn = Button.builder(Component.literal("HUD: " + onOff(MetroNavConfig.get().hudEnabled)), b -> {
            MetroNavConfig cfg = MetroNavConfig.get();
            cfg.hudEnabled = !cfg.hudEnabled;
            MetroNavConfig.save();
            b.setMessage(Component.literal("HUD: " + onOff(cfg.hudEnabled)));
        }).bounds(x0, by, 108, 20).build();
        Button lineBtn = Button.builder(Component.literal("指引线: " + onOff(MetroNavConfig.get().pathLineEnabled)), b -> {
            MetroNavConfig cfg = MetroNavConfig.get();
            cfg.pathLineEnabled = !cfg.pathLineEnabled;
            MetroNavConfig.save();
            b.setMessage(Component.literal("指引线: " + onOff(cfg.pathLineEnabled)));
        }).bounds(x0 + 116, by, 108, 20).build();
        Button beaconBtn = Button.builder(Component.literal("信标: " + onOff(MetroNavConfig.get().beaconEnabled)), b -> {
            MetroNavConfig cfg = MetroNavConfig.get();
            cfg.beaconEnabled = !cfg.beaconEnabled;
            MetroNavConfig.save();
            b.setMessage(Component.literal("信标: " + onOff(cfg.beaconEnabled)));
        }).bounds(x0 + 232, by, 108, 20).build();
        addRenderableWidget(hudBtn);
        addRenderableWidget(lineBtn);
        addRenderableWidget(beaconBtn);

        NavigationSession session = NavigationSession.get();
        startBtn = Button.builder(Component.literal("开始导航"), b -> startNavigation())
                .bounds(x0, by + 28, 108, 20).build();
        startBtn.active = selected != null && plan != null && !plan.unreachable;
        stopBtn = Button.builder(Component.literal("停止导航"), b -> {
            NavigationSession.get().stop();
            rebuildWidgetsNow();
        }).bounds(x0 + 116, by + 28, 108, 20).build();
        stopBtn.active = session.isActive();
        Button refreshBtn = Button.builder(Component.literal("刷新数据"), b -> {
            rebuildIndex();
            refreshList();
        }).bounds(x0 + 232, by + 28, 108, 20).build();
        addRenderableWidget(startBtn);
        addRenderableWidget(stopBtn);
        addRenderableWidget(refreshBtn);
    }

    private void rebuildWidgetsNow() {
        this.clearWidgets();
        this.init();
    }

    private String onOff(boolean v) {
        return v ? "开" : "关";
    }

    private List<RailwayIndex.StationNode> listSource() {
        Minecraft mc = Minecraft.getInstance();
        String q = query.trim().toLowerCase();
        List<RailwayIndex.StationNode> list = new ArrayList<>();
        if (q.isEmpty()) {
            list.addAll(index.stations.values());
            if (mc.player != null) {
                list.sort(Comparator.comparingDouble(sn -> dist2(sn, mc.player)));
            } else {
                list.sort(Comparator.comparing(sn -> sn.name));
            }
        } else {
            for (RailwayIndex.StationNode sn : index.stations.values()) {
                if (sn.name.toLowerCase().contains(q)) {
                    list.add(sn);
                }
            }
            list.sort(Comparator.comparing(sn -> sn.name));
        }
        return list;
    }

    private double dist2(RailwayIndex.StationNode sn, net.minecraft.world.entity.Entity player) {
        double dx = sn.x - player.getX();
        double dz = sn.z - player.getZ();
        return dx * dx + dz * dz;
    }

    private void refreshList() {
        for (Button b : listButtons) {
            removeWidget(b);
        }
        listButtons.clear();
        int x0 = this.width / 2 - 170;
        List<RailwayIndex.StationNode> list = listSource();
        int totalPages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) {
            page = totalPages - 1;
        }
        int start = page * PAGE_SIZE;
        int end = Math.min(list.size(), start + PAGE_SIZE);
        Minecraft mc = Minecraft.getInstance();
        for (int i = start; i < end; i++) {
            final RailwayIndex.StationNode sn = list.get(i);
            String label = "◈ " + sn.name;
            if (mc.player != null) {
                label += " · " + Math.round(Math.sqrt(dist2(sn, mc.player))) + "m";
            }
            Button b = Button.builder(Component.literal(label), btn -> selectStation(sn))
                    .bounds(x0, 100 + (i - start) * 22, 340, 20).build();
            listButtons.add(b);
            addRenderableWidget(b);
        }
        searchNote = list.isEmpty() ? "没有匹配的车站" : "";
    }

    private void selectStation(RailwayIndex.StationNode sn) {
        selected = sn;
        query = sn.name;
        searchBox.setValue(sn.name);
        refreshList();
        computePlan();
    }

    private void computePlan() {
        plan = null;
        searchNote = "";
        if (selected == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        double dx = selected.x - mc.player.getX();
        double dz = selected.z - mc.player.getZ();
        if (Math.hypot(dx, dz) < 4) {
            searchNote = "你已在 " + selected.name + " 附近";
            return;
        }
        plan = RoutePlanner.planMixed(index, mc.level,
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                selected.x, selected.y, selected.z, MetroNavConfig.get());
        if (plan.unreachable) {
            searchNote = "未找到路线";
        }
        if (startBtn != null) {
            startBtn.active = plan != null && !plan.unreachable;
        }
    }

    private void startNavigation() {
        if (plan == null || plan.unreachable) {
            searchNote = "未找到路线";
            return;
        }
        NavigationSession.get().startMixed(index, plan);
        rebuildWidgetsNow();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int x0 = cx - 170;

        g.drawCenteredString(this.font, "MetroNav 地铁导航", cx, 26, 0xFFFFFF);
        g.drawCenteredString(this.font, statusText, cx, 42, 0xAAAAAA);

        Minecraft mc = Minecraft.getInstance();
        String originText = "起点: ";
        if (mc.player != null) {
            RailwayIndex.StationNode origin = index.nearest(mc.player.getX(), mc.player.getY(), mc.player.getZ(), 300);
            originText += origin == null ? "附近没有车站（步行导航直达）" : origin.name;
        } else {
            originText += "?";
        }
        g.drawString(this.font, originText, x0, 60, 0xFFFFFF);

        String pageText = "第 " + (page + 1) + " 页";
        g.drawString(this.font, pageText, x0 + 172, 288, 0x999999);

        g.drawString(this.font, selected == null ? "目的地: 未选择" : "目的地: " + selected.name, x0, 306, 0xFFFFFF);
        if (!searchNote.isEmpty()) {
            g.drawString(this.font, searchNote, x0, 320, 0xFF6666);
        }

        NavigationSession session = NavigationSession.get();
        if (session.isActive()) {
            renderProgress(g, x0, 340);
        } else {
            renderPlan(g, x0, 336);
        }

        g.drawString(this.font, "提示: 按 N 打开地图点选导航 · 本面板用于车站搜索", x0, this.height - 20, 0x777777);
    }

    private void renderPlan(GuiGraphics g, int x0, int y) {
        if (selected == null || plan == null) {
            return;
        }
        if (plan.unreachable) {
            g.drawString(this.font, "未找到可换乘路线", x0, y, 0xFF6666);
            return;
        }
        int shown = Math.min(plan.legs.size(), 5);
        for (int li = 0; li < shown; li++) {
            RoutePlanner.Leg leg = plan.legs.get(li);
            if (leg.isMetro()) {
                g.fill(x0, y + 3, x0 + 10, y + 11, 0xFF000000 | leg.color);
                int stops = leg.points.size() - 1;
                String first = leg.stationNames.isEmpty() ? "?" : leg.stationNames.get(0);
                String last = leg.stationNames.size() < 2 ? "?" : leg.stationNames.get(leg.stationNames.size() - 1);
                g.drawString(this.font, "● " + NavText.lineName(leg.name, leg.express) + " — " + first + " → " + last + " · " + stops + " 站 · "
                        + Math.round(leg.distance) + "m", x0 + 16, y, 0xFFFFFF);
            } else {
                g.fill(x0, y + 3, x0 + 10, y + 11, 0xFF00E5FF);
                g.drawString(this.font, "步行 " + leg.name + " · " + Math.round(leg.distance) + "m",
                        x0 + 16, y, 0xFFFFFF);
            }
            y += 12;
        }
        if (plan.legs.size() > shown) {
            g.drawString(this.font, "… 共 " + plan.legs.size() + " 段行程", x0 + 16, y, 0xAAAAAA);
            y += 12;
        }
        int mins = Math.max(1, (int) Math.ceil(plan.estSeconds / 60.0));
        g.drawString(this.font, "全程约 " + Math.round(plan.totalDistance) + " 米 · 约 " + mins + " 分钟 · 换乘 "
                + Math.max(0, metroLegCount(plan) - 1) + " 次", x0, y + 4, 0x55FF55);
    }

    private int metroLegCount(RoutePlanner.Plan plan) {
        int n = 0;
        for (RoutePlanner.Leg leg : plan.legs) {
            if (leg.isMetro()) {
                n++;
            }
        }
        return n;
    }

    private void renderProgress(GuiGraphics g, int x0, int y) {
        NavigationSession session = NavigationSession.get();
        Minecraft mc = Minecraft.getInstance();
        if (session.isArrived()) {
            g.drawString(this.font, "✔ 已到达目的地 " + session.destinationName(), x0, y, 0x55FF55);
            return;
        }
        NavigationSession.Waypoint target = session.currentTarget();
        if (target == null || mc.player == null) {
            return;
        }
        String action = (target.action == null || target.action.isEmpty()) ? "前往 " + target.title : target.action;
        g.drawString(this.font, "导航中: " + action, x0, y, 0xFFFFFF);
        g.drawString(this.font, "当前: " + target.lineName, x0, y + 13, 0xBBBBBB);
        g.drawString(this.font, "剩余约 " + Math.round(session.remainingDistance(mc.player.getX(), mc.player.getZ()))
                        + " 米 · 约 " + Math.max(1, Math.round(session.remainingSeconds(mc.player.getX(), mc.player.getY(), mc.player.getZ()) / 60.0))
                        + " 分钟",
                x0, y + 26, 0xBBBBBB);
    }
}
