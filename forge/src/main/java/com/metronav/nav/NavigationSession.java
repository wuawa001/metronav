package com.metronav.nav;

import com.metronav.MetroNavConfig;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public class NavigationSession {
    private static final NavigationSession INSTANCE = new NavigationSession();
    private int debugTick = 0;

    /** 同时写文件 + 聊天栏：用户找不到文件时，游戏里也能直接看到。 */
    public static void toast(String msg) {
        debugLog(msg);
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("[导航] " + msg), false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把诊断信息写到游戏目录 logs/metronav-debug.log */
    static void debugLog(String msg) {
        try {
            java.nio.file.Path p = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("logs").resolve("metronav-debug.log");
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.write(p, (System.currentTimeMillis() + " " + msg + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    public static NavigationSession get() {
        return INSTANCE;
    }

    public static final class Waypoint {
        public final double x, y, z;
        public final int kind; // RoutePlanner.WALK / METRO
        public final int legIndex;
        public final double radius;
        public final double speed;
        public final int lineColor;
        public final String lineName;
        public final String title;
        public final String action;
        public final boolean area;
        public final boolean transfer;
        public final double areaMinX, areaMinZ, areaMaxX, areaMaxZ;

        Waypoint(double x, double y, double z, int kind, int legIndex, double radius, double speed,
                 int lineColor, String lineName, String title, String action,
                 boolean area, boolean transfer, double areaMinX, double areaMinZ, double areaMaxX, double areaMaxZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.legIndex = legIndex;
            this.radius = radius;
            this.speed = speed;
            this.lineColor = lineColor;
            this.lineName = lineName;
            this.title = title;
            this.action = action;
            this.area = area;
            this.transfer = transfer;
            this.areaMinX = areaMinX;
            this.areaMinZ = areaMinZ;
            this.areaMaxX = areaMaxX;
            this.areaMaxZ = areaMaxZ;
        }

        public double distanceTo(double px, double pz) {
            double dx = x - px;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dz * dz);
        }

        public double distanceTo(Waypoint other) {
            return distanceTo(other.x, other.z);
        }

        public boolean isMetro() {
            return kind == RoutePlanner.METRO;
        }

    }

    public static final class LegView {
        public final int kind;
        public final String name;
        public final int color;

        LegView(int kind, String name, int color) {
            this.kind = kind;
            this.name = name;
            this.color = color;
        }
    }

    /**
     * 乘车提示：把「现在是哪一站、还有几站、要不要下车换乘」一次算好，
     * HUD 只负责显示。借鉴 HowToGo 的 TransitStep：上车提示由步行段的 action 承担
     * （进站即完成步行段），所以这里只有「坐车中 / 快到站 / 到站」三态。
     */
    public static final class TransitCue {
        public enum Phase { RIDE, APPROACH, ALIGHT }

        public final Phase phase;
        public final String lineName;
        /** 下一站名字。 */
        public final String next;
        /** 本段终点站（往哪个方向）。 */
        public final String terminus;
        /** 还有几站到终点站（含下一站）。 */
        public final int stopsRemaining;
        public final String text;
        /** 下车后要换乘的线路（没有就是 null）。 */
        public final String hint;

        TransitCue(Phase phase, String lineName, String next, String terminus, int stopsRemaining,
                   String text, String hint) {
            this.phase = phase;
            this.lineName = lineName;
            this.next = next;
            this.terminus = terminus;
            this.stopsRemaining = stopsRemaining;
            this.text = text;
            this.hint = hint;
        }
    }

    private final List<Waypoint> waypoints = new ArrayList<>();
    private final List<LegView> legs = new ArrayList<>();
    /** 和 waypoints 一一对应：这个航点属于哪个车站（步行航点 = 0）。
     *  用它判断「计划的最后一点是不是目标站」——比拿坐标比靠谱，站点区中心会偏。 */
    private final List<Long> waypointStation = new ArrayList<>();
    private int nextIndex = 0;
    private boolean active = false;
    private boolean arrived = false;
    private String destinationName = "";
    private int generation = 0;
    /** 整条路线的水平链长（相邻航点 xz 距离之和），用来算进度。 */
    private double chain = 0;
    /** cum[i] = 从 0 号航点走到 i 号航点的累计链长；用来把「整段剩余」换算成「到第 k 个点还有多远」。 */
    private double[] cum = new double[0];
    private boolean offRoute = false;
    private int offRouteTicks = 0;

    /** 站区包围盒最多认多远：MTR 的 station area 是玩家手画的，大站能画到 500x200 格。 */
    private static final double AREA_CLAMP = 120.0;
    /** 终点站到达半径上限：长站台离站中心远，但也不能远到吃掉隔壁站。 */
    private static final double FINAL_RADIUS_CAP = 80.0;
    /** 越走越远连续这么多 tick，就认定这个路点够不着，跳过它（1 秒）。 */
    private static final int STUCK_TICKS = 20;
    /** 距离再也不减少（撞墙、水边、悬崖）多久就跳过这个路点：近处 2 秒、远处 10 秒。 */
    private static final int NO_PROGRESS_NEAR_TICKS = 40;
    private static final double NO_PROGRESS_NEAR_BLOCKS = 32.0;
    private static final int NO_PROGRESS_FAR_TICKS = 200;
    private static final double NO_PROGRESS_FAR_BLOCKS = 200.0;
    /** 自动重规划的冷却（3 秒）与次数上限。 */
    private static final int REPLAN_COOLDOWN_TICKS = 60;
    private static final int MAX_REPLANS = 6;

    /** 当前航点的最近距离 + 越走越远的连续 tick（卡住判定用）。 */
    private double bestDist = Double.MAX_VALUE;
    private int awayTicks = 0;
    /** 距离上次变小以来过了多少 tick（撞墙判定用）。 */
    private int progressTicks = 0;
    /** 自动重规划需要的上下文：线网索引 + 终点坐标（从 Plan 里抄下来）。 */
    private RailwayIndex index;
    /** 规划目标站（0 = 目的地不是车站）。 */
    private long destStationId;
    private double destX, destY, destZ;
    private int tickCounter = 0;
    /** 沿整条路线的链长（米），单调不减。所有「到哪一站了」都由它推出来，
     *  而不是「离某个坐标点多近」——这是 HowToGo 的做法（Trip.Ride.stopPassed）。 */
    private double travelled = 0;
    private int replans = 0;
    private int lastReplanTick = -100000;
    private double lastReplanX, lastReplanZ;
    public double totalDistance;
    public double estSeconds;

    private NavigationSession() {}

    public void startMixed(RailwayIndex index, RoutePlanner.Plan plan) {
        stop();
        if (plan == null || plan.legs.isEmpty()) {
            return;
        }
        boolean destNamed = plan.destinationName != null && !plan.destinationName.isEmpty();
        destinationName = destNamed ? plan.destinationName : "目的地";
        this.index = index;
        this.destStationId = plan.destStationId;
        // 终点以「规划时点下去的那个点」为准：legs 的最后一点有可能早一个站
        // （站台数据缺失、站外换乘结尾、线路末段没解析出来），那会让导航在上一站就报到达。
        if (plan.destX != 0 || plan.destZ != 0) {
            destX = plan.destX;
            destY = plan.destY;
            destZ = plan.destZ;
        } else if (!plan.legs.isEmpty() && !plan.legs.get(plan.legs.size() - 1).points.isEmpty()) {
            RoutePlanner.Leg endLeg = plan.legs.get(plan.legs.size() - 1);
            double[] dp = endLeg.points.get(endLeg.points.size() - 1);
            destX = dp[0];
            destY = dp[1];
            destZ = dp[2];
        }
        boolean lastLegWasMetro = false;
        for (int li = 0; li < plan.legs.size(); li++) {
            RoutePlanner.Leg leg = plan.legs.get(li);
            RoutePlanner.Leg next = li + 1 < plan.legs.size() ? plan.legs.get(li + 1) : null;
            boolean isLastLeg = li == plan.legs.size() - 1;
            for (int i = 0; i < leg.points.size(); i++) {
                double[] p = leg.points.get(i);
                boolean isFirstOfPlan = waypoints.isEmpty();
                boolean isLastOfPlan = isLastLeg && i == leg.points.size() - 1;
                boolean isLastPointOfLeg = i == leg.points.size() - 1;
                String title;
                String action;
                String lineName;
                int lineColor;
                boolean area = false;
                boolean transfer = false;
                double radiusOverride = 0;
                double aminX = 0;
                double aminZ = 0;
                double amaxX = 0;
                double amaxZ = 0;

                if (leg.isMetro()) {
                    String station = i < leg.stationNames.size() ? leg.stationNames.get(i) : leg.name;
                    title = station;
                    lineName = NavText.lineName(leg.name, leg.express);
                    lineColor = leg.color;
                    if (i < leg.stationIds.size()) {
                        RailwayIndex.StationNode sn = index.stations.get(leg.stationIds.get(i));
                        if (sn != null) {
                            // 站区原样用会把邻站吞运来：人站在目的地前一站，已经在「市政府」的巨型站区里，于是提前一站报「已到达」。这里夹到站中心 ±AREA_CLAMP，且终点站干脆不认站区（必须真的到站）。
                            aminX = Math.max(sn.minX, sn.x - AREA_CLAMP);
                            aminZ = Math.max(sn.minZ, sn.z - AREA_CLAMP);
                            amaxX = Math.min(sn.maxX, sn.x + AREA_CLAMP);
                            amaxZ = Math.min(sn.maxZ, sn.z + AREA_CLAMP);
                            area = !isLastOfPlan;
                            if (isLastOfPlan) {
                                double halfDiag = Math.hypot((sn.maxX - sn.minX) / 2.0, (sn.maxZ - sn.minZ) / 2.0);
                                radiusOverride = Math.min(Math.max(radiusOverride, halfDiag), FINAL_RADIUS_CAP);
                            }
                        }
                    }
                    if (isLastOfPlan) {
                        action = "到达目的地 " + station;
                    } else if (i == 0) {
                        String withDir = NavText.lineName(leg.name, leg.express) + directionSuffix(index, leg);
                        if (!isFirstOfPlan && lastLegWasMetro) {
                            transfer = true;
                            action = "在 " + station + " 换乘 " + withDir;
                        } else {
                            action = "在 " + station + " 上车 · 乘 " + withDir;
                        }
                    } else {
                        action = "经过 " + station;
                    }
                } else {
                    title = leg.name;
                    lineName = "步行";
                    lineColor = RoutePlanner.WALK_COLOR;
                    // 最后一段步行：整段都要说「步行至目的地 X」。
                    // 以前只有最后一个路点带这句，其余路点显示光秃秃的「步行」，
                    // 玩家走进最后一段后 HUD 上半行就只剩两个字。
                    if (isLastLeg) {
                        action = destNamed ? "步行至目的地 " + destinationName : "步行至目的地";
                    } else if (next != null && next.isMetro()) {
                        action = leg.name + "，乘坐 " + NavText.lineName(next.name, next.express) + directionSuffix(index, next);
                        // As soon as the player enters the station's footprint, the walk leg
                        // is done and navigation advances (no need to reach the track).
                        if (!next.stationIds.isEmpty()) {
                            RailwayIndex.StationNode sn = index.stations.get(next.stationIds.get(0));
                            if (sn != null) {
                                aminX = Math.max(sn.minX, sn.x - AREA_CLAMP);
                                aminZ = Math.max(sn.minZ, sn.z - AREA_CLAMP);
                                amaxX = Math.min(sn.maxX, sn.x + AREA_CLAMP);
                                amaxZ = Math.min(sn.maxZ, sn.z + AREA_CLAMP);
                                area = true;
                            }
                        }
                    } else {
                        action = "步行";
                    }
                }

                double radius = radiusOverride > 0 ? radiusOverride : (leg.isMetro()
                        ? MetroNavConfig.get().arrivalRadius
                        : MetroNavConfig.get().walkArrivalRadius);
                waypoints.add(new Waypoint(p[0], p[1], p[2], leg.kind, li, radius, leg.speed,
                        lineColor, lineName, title, action, area, transfer, aminX, aminZ, amaxX, amaxZ));
                waypointStation.add(leg.isMetro() && i < leg.stationIds.size() ? leg.stationIds.get(i) : 0L);
            }
            legs.add(new LegView(leg.kind, leg.name, leg.color));
            lastLegWasMetro = leg.isMetro();
        }
        StringBuilder dump = new StringBuilder("startMixed dest=(")
                .append(Math.round(destX)).append(",").append(Math.round(destZ))
                .append(") name=").append(destinationName).append(" wps=").append(waypoints.size());
        for (int i = 0; i < waypoints.size() && i < 40; i++) {
            Waypoint w = waypoints.get(i);
            if (!w.isMetro()) {
                continue;
            }
            dump.append(" | ").append(i).append(":").append(w.title)
                .append("(").append(Math.round(w.x)).append(",").append(Math.round(w.z)).append(")")
                .append(" d=").append(Math.round(Math.hypot(w.x - destX, w.z - destZ)));
        }
        debugLog(dump.toString());
        StringBuilder brief = new StringBuilder("plan:");
        for (LegView lv : legs) {
            brief.append(" ").append(lv.name);
        }
        brief.append(" | 终点 ").append(destinationName).append(" d=")
             .append(Math.round(Math.hypot(waypoints.get(waypoints.size() - 1).x - destX,
                     waypoints.get(waypoints.size() - 1).z - destZ)));
        toast(brief.toString());
        active = true;
        arrived = false;
        nextIndex = 0;
        generation++;
        offRoute = false;
        offRouteTicks = 0;
        chain = 0;
        cum = new double[waypoints.size()];
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            chain += waypoints.get(i).distanceTo(waypoints.get(i + 1));
            cum[i + 1] = chain;
        }
        if (waypoints.size() == 1) {
            cum[0] = 0;
        }
        totalDistance = plan.totalDistance;
        estSeconds = plan.estSeconds;
    }

    /**
     * 兜底：如果最后一个航点离真正要去的地方还差得远（> max(8, 半径)），补一段步行过去。
     *
     * <p>没有这一步时，只要 legs 的最后一点停在上一站（站台数据缺失、换乘结尾、线路末段没解析出来），
     * HUD 就会在那一站报「已到达目的地」——人还在车上，离目的地还有几百米。
     * 到达判定必须以「点下去的那个点」为锚，而不是以「计划最后生成的航点」为锚。
     */
    private void appendTailToDestination() {
        if (waypoints.isEmpty()) {
            return;
        }
        int lastIdx = waypoints.size() - 1;
        Waypoint last = waypoints.get(lastIdx);
        boolean lastIsMetro = last.isMetro();
        long lastStation = lastIdx < waypointStation.size() ? waypointStation.get(lastIdx) : 0L;

        // 情况一：计划本该停在这个站，但最后一点是别的站 → 少了一站，补到目标站
        boolean endsAtWrongStation = lastIsMetro && destStationId != 0 && lastStation != destStationId;
        double gap = Math.hypot(last.x - destX, last.z - destZ);
        if (!endsAtWrongStation && gap <= Math.max(8.0, last.radius)) {
            return;
        }

        double tx = destX;
        double ty = destY;
        double tz = destZ;
        if (endsAtWrongStation && index != null) {
            RailwayIndex.StationNode sn = index.stations.get(destStationId);
            if (sn != null) {
                tx = sn.x;
                ty = sn.y;
                tz = sn.z;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        List<double[]> pts = mc != null && mc.level != null
                ? WalkPlanner.path(mc.level, last.x, last.y, last.z, tx, ty, tz)
                : WalkPlanner.straightPath(null, last.x, last.y, last.z, tx, ty, tz);
        if (pts.size() < 2) {
            return;
        }
        double speed = MetroNavConfig.get().walkSpeedMps;
        for (int i = 1; i < pts.size(); i++) {
            double[] p = pts.get(i);
            waypoints.add(new Waypoint(p[0], p[1], p[2], RoutePlanner.WALK, legs.size(), 4.0, speed,
                    RoutePlanner.WALK_COLOR, "步行", destinationName,
                    destinationName.isEmpty() ? "步行至目的地" : "步行至目的地 " + destinationName,
                    false, false, 0, 0, 0, 0));
            waypointStation.add(0L);
        }
        legs.add(new LegView(RoutePlanner.WALK, "步行", RoutePlanner.WALK_COLOR));
        debugLog("appendTail: endsAtWrongStation=" + endsAtWrongStation
                + " lastStation=" + lastStation + " want=" + destStationId
                + " gap=" + Math.round(gap) + " m, added " + (pts.size() - 1) + " walk points");
    }

    /**
     * Queries the route's destination terminal in the travel direction from MTR data,
     * returning a suffix like " 往 机场 方向" or " 环线" (or "" when unknown).
     */
    private String directionSuffix(RailwayIndex index, RoutePlanner.Leg leg) {
        RailwayIndex.RouteNode rn = index.routes.get(leg.routeId);
        if (rn == null || rn.stationIds.size() < 2 || leg.stationIds.size() < 2) {
            return "";
        }
        if (rn.circular) {
            return " 环线";
        }
        long board = leg.stationIds.get(0);
        long next = leg.stationIds.get(1);
        int bi = rn.stationIds.indexOf(board);
        int ni = rn.stationIds.indexOf(next);
        if (bi < 0 || ni < 0) {
            return "";
        }
        long destId = ni > bi ? rn.stationIds.get(rn.stationIds.size() - 1) : rn.stationIds.get(0);
        RailwayIndex.StationNode dest = index.stations.get(destId);
        if (dest == null) {
            return "";
        }
        return " 往 " + dest.name + " 方向";
    }

    public void stop() {
        active = false;
        arrived = false;
        waypoints.clear();
        waypointStation.clear();
        legs.clear();
        nextIndex = 0;
        chain = 0;
        offRoute = false;
        offRouteTicks = 0;
    }

    public boolean isActive() {
        return active;
    }

    /** 每次开始导航递增；HUD 用它重置箭头滤波，避免新导航从上一次的方向慢慢拧过来。 */
    public int generation() {
        return generation;
    }

    public boolean isArrived() {
        return active && arrived;
    }

    public String destinationName() {
        return destinationName;
    }

    public Waypoint currentTarget() {
        return active && nextIndex < waypoints.size() ? waypoints.get(nextIndex) : null;
    }

    /** Returns the line name of the next transfer station, but only when it is the very next stop. */
    public String upcomingTransfer() {
        if (!active || nextIndex + 1 >= waypoints.size()) {
            return null;
        }
        Waypoint next = waypoints.get(nextIndex + 1);
        return next.transfer ? next.lineName : null;
    }

    public List<Waypoint> remainingWaypoints() {
        List<Waypoint> out = new ArrayList<>();
        if (!active) {
            return out;
        }
        for (int i = nextIndex; i < waypoints.size(); i++) {
            out.add(waypoints.get(i));
        }
        return out;
    }

    /** 整条路线的水平链长。 */
    public double chainLength() {
        return chain;
    }

    /** 当前是否判定为偏离路线（HUD 会换成警告提示）。 */
    public boolean offRoute() {
        return active && offRoute;
    }

    public int currentIndex() {
        return nextIndex;
    }

    public Waypoint waypointAt(int index) {
        return index >= 0 && index < waypoints.size() ? waypoints.get(index) : null;
    }

    /** 从头到尾一共有多少个航点。 */
    public int waypointCount() {
        return waypoints.size();
    }

    /** nextIndex 之后第一个地铁站航点的下标；没有就是 -1。 */
    public int upcomingStationIndex() {
        if (!active) {
            return -1;
        }
        for (int i = nextIndex; i < waypoints.size(); i++) {
            if (waypoints.get(i).isMetro()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 沿路线走到第 {@code index} 个航点还有多远。
     *
     * <p>直接拿「到当前这个 12 格小路点的直线距离」当提示会得到「距车站 10 米」这种废话，
     * 所以要沿链长换算：整段剩余 − 该点之后的剩余链长。
     */
    public double distanceAhead(int index, double px, double pz) {
        if (!active || index < 0 || index >= waypoints.size() || cum.length != waypoints.size()) {
            return 0;
        }
        double tail = chain - cum[index];
        return Math.max(0.0, remainingDistance(px, pz) - tail);
    }

    /**
     * 乘车提示。只有在「当前目标就是地铁站航点」时才有意义——
     * 步行段返回 null，由读出台退回到普通指路文案。
     */
    public TransitCue transitCue() {
        return transitCue(Double.NaN, Double.NaN, Double.NaN);
    }

    /**
     * 带玩家位置的乘车提示：站名以「你现在离哪个站最近」为准。
     * 计划里的站名是按距离/序号落下来的，数据一歪就是「到了大风说石碧」；
     * 物理上最近的站才是真的在那儿。
     */
    public TransitCue transitCue(double px, double py, double pz) {
        if (!active || nextIndex >= waypoints.size()) {
            return null;
        }
        Waypoint cur = waypoints.get(nextIndex);
        String hereName = null;
        if (index != null && !Double.isNaN(px)) {
            RailwayIndex.StationNode near = index.nearest(px, py, pz, 70);
            long sid = nextIndex < waypointStation.size() ? waypointStation.get(nextIndex) : 0L;
            if (near != null && sid == near.id) {
                hereName = near.name;
            }
        }
        if (!cur.isMetro()) {
            return null;
        }
        int li = cur.legIndex;
        int legStart = -1;
        int legEnd = -1;
        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint w = waypoints.get(i);
            if (w.legIndex != li || !w.isMetro()) {
                continue;
            }
            if (legStart < 0) {
                legStart = i;
            }
            legEnd = i;
        }
        if (legStart < 0 || legEnd < legStart) {
            return null;
        }
        String terminus = waypoints.get(legEnd).title;
        int hops = legEnd - nextIndex;      // 还要坐几站到终点站（含下一站）
        String next = nextIndex + 1 <= legEnd ? waypoints.get(nextIndex + 1).title : terminus;

        // 下车后要接的下一段地铁（换乘提示只在快到站时给，别一路念）
        String transferLine = null;
        String transferTerminus = null;
        if (legEnd + 1 < waypoints.size()) {
            Waypoint after = waypoints.get(legEnd + 1);
            if (after.isMetro() && after.legIndex != li) {
                transferLine = after.lineName;
                int end2 = legEnd + 1;
                for (int i = legEnd + 1; i < waypoints.size(); i++) {
                    Waypoint w = waypoints.get(i);
                    if (!w.isMetro() || w.legIndex != after.legIndex) {
                        break;
                    }
                    end2 = i;
                }
                transferTerminus = waypoints.get(end2).title;
            }
        }

        String hint = null;
        String text;
        TransitCue.Phase phase;
        String here = hereName != null ? hereName : cur.title;
        // 「到站」必须是「人已经在这个站」，不是「这个站是当前目标」。
        // 之前按 hops<=0 判，列车还没进站就报「到站 X，请下车」，读起来就是提前一站让你下车。
        double toCur = Double.isNaN(px) ? 0.0 : Math.hypot(cur.x - px, cur.z - pz);
        boolean atCur = !Double.isNaN(px) && toCur <= 24.0;
        if (hops <= 0 && atCur) {
            phase = TransitCue.Phase.ALIGHT;
            text = "到站 " + here + "，请下车";
            if (transferLine != null) {
                text += "，换乘 " + transferLine
                        + (transferTerminus != null ? " 往 " + transferTerminus + " 方向" : "");
                hint = "换乘 " + transferLine;
            }
        } else if (hops <= 0) {
            // 当前目标就是本段要下车的站：这时才说「准备下车 / 换乘」，
            // 站名必须是这一站（cur），不是它的下一站——否则在上一站就会念出换乘站的站名。
            phase = TransitCue.Phase.APPROACH;
            text = "前方到站 " + here + "，请准备下车";
            if (transferLine != null) {
                text += "，换乘 " + transferLine
                        + (transferTerminus != null ? " 往 " + transferTerminus + " 方向" : "");
                hint = "换乘 " + transferLine;
            }
        } else {
            phase = TransitCue.Phase.RIDE;
            // 当前目标站就是下一站；「还有几站」含它自己
            text = "下一站 " + cur.title + " · 还有 " + (hops + 1) + " 站到 " + terminus;
        }
        return new TransitCue(phase, cur.lineName, next, terminus, hops, text, hint);
    }

    /**
     * 玩家到「当前这一段」的横向偏离距离。t<0 / t>1 时退化为到端点的距离，
     * 这样走到路线尽头之外也能算出一个合理的偏离量。
     */
    private double crossTrack(double px, double pz) {
        if (nextIndex >= waypoints.size()) {
            return 0;
        }
        Waypoint a = waypoints.get(nextIndex);
        if (nextIndex + 1 >= waypoints.size()) {
            return a.distanceTo(px, pz);
        }
        Waypoint b = waypoints.get(nextIndex + 1);
        double vx = b.x - a.x;
        double vz = b.z - a.z;
        double len2 = vx * vx + vz * vz;
        if (len2 < 1e-6) {
            return a.distanceTo(px, pz);
        }
        double t = ((px - a.x) * vx + (pz - a.z) * vz) / len2;
        if (t <= 0) {
            return a.distanceTo(px, pz);
        }
        if (t >= 1) {
            return b.distanceTo(px, pz);
        }
        return Math.abs((px - a.x) * vz - (pz - a.z) * vx) / Math.sqrt(len2);
    }

    private boolean insideArea(Waypoint w, double px, double pz) {
        return w.area && px >= w.areaMinX - 1.0 && px <= w.areaMaxX + 1.0
                && pz >= w.areaMinZ - 1.0 && pz <= w.areaMaxZ + 1.0;
    }

    public void tick(Minecraft client) {
        if (!active) {
            return;
        }
        debugTick++;
        if (debugTick % 40 == 0) {
            debugLog("tick nextIndex=" + nextIndex + "/" + waypoints.size()
                    + (nextIndex < waypoints.size()
                        ? " cur=(" + Math.round(waypoints.get(nextIndex).x) + "," + Math.round(waypoints.get(nextIndex).y)
                          + "," + Math.round(waypoints.get(nextIndex).z) + ") r=" + waypoints.get(nextIndex).radius
                          + " area=" + waypoints.get(nextIndex).area
                          + " player=(" + Math.round(client.player.getX()) + "," + Math.round(client.player.getY())
                          + "," + Math.round(client.player.getZ()) + ")"
                        : ""));
        }
        if (client.player == null || client.level == null) {
            stop();
            return;
        }
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        tickCounter++;
        while (nextIndex < waypoints.size()) {
            Waypoint w = waypoints.get(nextIndex);
            if (isReached(w, px, py, pz) || isPassed(w, nextIndex, px, pz)) {
                nextIndex++;
                resetStuck();
            } else {
                break;
            }
        }
        if (nextIndex >= waypoints.size()) {
            arrived = true;
            offRoute = false;
            return;
        }
        skipIfWalkingAway(px, pz);
        updateOffRoute(px, pz);
        maybeReplan(client, px, pz);
    }

    /**
     * 偏离路线判定。
     *
     * <p>为什么不直接每帧比对：玩家正常走路也会有 10 格以内的摆动（贴墙、绕树、跳下去再爬上来），
     * 一超阈值就报警会把正常玩家烦死；所以要求「连续 N tick 都偏出走廊」才认。
     *
     * <p>为什么坐地铁时不判：车站之间是直线距离，实际轨道是弯的、还会爬坡下坡，
     * 列车走在中途离这条直线几十格是常态，这时候报「已偏离路线」纯属添乱。
     */
    private void updateOffRoute(double px, double pz) {
        MetroNavConfig cfg = MetroNavConfig.get();
        Waypoint cur = waypoints.get(nextIndex);
        boolean riding = cur.isMetro();
        boolean nearStation = insideArea(cur, px, pz);
        double limit = Math.max(cfg.offRouteWarnBlocks, cur.radius * 6.0);
        boolean off = !riding && !nearStation && crossTrack(px, pz) > limit;
        if (off) {
            offRouteTicks++;
        } else {
            offRouteTicks = 0;
        }
        offRoute = !riding && offRouteTicks >= Math.max(1, cfg.offRouteWarnTicks);
    }

    /**
     * 用「你现在站在哪个站」校正当前航点。
     *
     * <p>坐车时唯一可靠的定位信息是「离哪个车站最近」，而不是「离某个坐标点多少米」：
     * 车站坐标可能偏（站点区中心是平均值、站台两端可能没落盘），一旦偏了，航点就会
     * 提前被消费掉——表现为「到了大风却以为在石碧」「还没到站就叫你换乘」。
     *
     * <p>所以每 tick 先问一句：最近的站在不在剩余航点里？在就把它设成当前航点
     * （允许回溯两站，纠正已经跑偏的进度）。
     */
    /** 把玩家位置投影到「当前段前后几段」的折线上，取单调不减的最大链长。 */
    private void updateTravelled(double px, double pz) {
        if (waypoints.size() < 2 || cum.length < 2) {
            return;
        }
        double best = travelled;
        int from = Math.max(0, nextIndex - 1);
        int to = Math.min(waypoints.size() - 2, nextIndex + 4);
        for (int i = from; i <= to; i++) {
            Waypoint a = waypoints.get(i);
            Waypoint b = waypoints.get(i + 1);
            double vx = b.x - a.x;
            double vz = b.z - a.z;
            double len2 = vx * vx + vz * vz;
            if (len2 < 1e-6) {
                continue;
            }
            double t = ((px - a.x) * vx + (pz - a.z) * vz) / len2;
            if (t < 0) {
                t = 0;
            } else if (t > 1) {
                t = 1;
            }
            double cand = cum[i] + t * Math.sqrt(len2);
            if (cand > best) {
                best = cand;
            }
        }
        travelled = best;
    }

    /**
     * 地铁段按链长推进：「已经走过的站」= 里程标 <= 当前链长的那一批。
     *
     * <p>这来自 HowToGo（Trip.Ride.stopPassed：按 at 比较），而不是逐个航点比距离。
     * 逐点比距离时，车站坐标一歪就会提前消费一站，表现就是「到了大风说石碧」。
     * 里程标是相对量，站点坐标整体平移不影响谁在前谁在后。
     */
    private void advanceMetroByChainage() {
        if (nextIndex >= waypoints.size()) {
            return;
        }
        Waypoint cur = waypoints.get(nextIndex);
        if (!cur.isMetro()) {
            return;
        }
        int passed = nextIndex;
        for (int i = nextIndex; i < waypoints.size() && i < cum.length; i++) {
            Waypoint w = waypoints.get(i);
            if (w.legIndex != cur.legIndex) {
                break;
            }
            if (cum[i] <= travelled + 0.5) {
                passed = i;
            } else {
                break;
            }
        }
        if (passed > nextIndex) {
            debugLog("chainage: travelled=" + Math.round(travelled) + " passed " + nextIndex
                    + "->" + passed + " (" + waypoints.get(passed).title + ")");
            nextIndex = passed + 1;
            resetStuck();
        }
    }

    private void syncToNearestStation(double px, double py, double pz) {
        if (index == null || nextIndex >= waypoints.size()) {
            return;
        }
        Waypoint cur = waypoints.get(nextIndex);
        if (!cur.isMetro()) {
            return;
        }
        RailwayIndex.StationNode near = index.nearest(px, py, pz, 60);
        if (near == null) {
            return;
        }
        int from = Math.max(0, nextIndex - 2);
        int to = Math.min(waypoints.size() - 1, nextIndex + 8);
        for (int i = from; i <= to; i++) {
            Waypoint w = waypoints.get(i);
            if (!w.isMetro() || w.legIndex != cur.legIndex) {
                continue;
            }
            long sid = i < waypointStation.size() ? waypointStation.get(i) : 0L;
            if (sid == near.id) {
                if (i != nextIndex) {
                    toast("校正 " + cur.title + " -> " + w.title + "（最近站 " + near.name
                            + "，第 " + nextIndex + "→" + i + " 站）");
                    nextIndex = i;
                    resetStuck();
                }
                return;
            }
        }
    }

    private void resetStuck() {
        bestDist = Double.MAX_VALUE;
        awayTicks = 0;
        progressTicks = 0;
    }

    /**
     * 越走越远就跳过这个路点。
     *
     * <p>「在原地转圈」的根子：玩家绕墙/踩水/下坡走，横向出了走廊 → isPassed 不认（它要求横向 24 格内）→ 当前航点永远是身后那个点 → 箭头一直指回去 → 玩家往回走 → 又指向前 → 反复。
     * 这里给的判据是「你在离这个点越来越远」，那这个点就不可能再被「走到」了，跳过它（12 格一个的小路点跳过没任何损失）。
     * 地铁航点不跳：列车离站本来就会越来越远。
     */
    private void skipIfWalkingAway(double px, double pz) {
        if (nextIndex + 1 >= waypoints.size()) {
            resetStuck();
            return; // 最后一个点必须真到
        }
        Waypoint w = waypoints.get(nextIndex);
        if (w.isMetro()) {
            resetStuck(); // 地铁站不能跳：跳了就是坐错车
            return;
        }
        double d = w.distanceTo(px, pz);
        if (d < bestDist - 0.5) {
            bestDist = d;
            awayTicks = 0;
            progressTicks = 0;
        } else {
            progressTicks++;
            if (d > bestDist + 2.0) {
                awayTicks++;
            } else {
                awayTicks = Math.max(0, awayTicks - 2);
            }
        }
        boolean skip = awayTicks >= STUCK_TICKS
                || (progressTicks >= NO_PROGRESS_NEAR_TICKS && bestDist <= NO_PROGRESS_NEAR_BLOCKS)
                || (progressTicks >= NO_PROGRESS_FAR_TICKS && bestDist <= NO_PROGRESS_FAR_BLOCKS);
        if (skip) {
            nextIndex++;
            resetStuck();
        }
    }

    /**
     * 偏离路线后从玩家当前位置重新规划（和 HowToGo 的「已偏离路线，正在重新规划」一个意思）。
     *
     * <p>为什么必须有：步行段是「从出发点到终点的一条直线」，现实里中间有墙、水、山，玩家只能绕着走。绕出走廊后旧路线再也修正不回来，只能靠重规划把人重新接回一条新直线。
     */
    private void maybeReplan(Minecraft client, double px, double pz) {
        if (!offRoute || arrived || index == null || client.player == null || client.level == null) {
            return;
        }
        if (replans >= MAX_REPLANS || tickCounter - lastReplanTick < REPLAN_COOLDOWN_TICKS) {
            return;
        }
        if (Math.hypot(px - lastReplanX, pz - lastReplanZ) < 8.0) {
            return; // 人几乎没动，重算出来还是同一条线
        }
        RoutePlanner.Plan p = RoutePlanner.planMixed(index, client.level, px, client.player.getY(), pz,
                destX, destY, destZ, MetroNavConfig.get());
        if (p == null || p.legs.isEmpty() || p.unreachable) {
            return;
        }
        int keep = replans;
        startMixed(index, p);
        if (!active) {
            return;
        }
        replans = keep + 1;
        lastReplanTick = tickCounter;
        lastReplanX = px;
        lastReplanZ = pz;
    }

    /** 刚刚重算过（HUD 用来把「正在指回路线」换成「已重新规划」）。 */
    public boolean replannedRecently() {
        return tickCounter - lastReplanTick < 100;
    }

    public int replanCount() {
        return replans;
    }

    /** 站/点到达判定。 */
    private boolean isReached(Waypoint w, double px, double py, double pz) {
        double dx = w.x - px;
        double dz = w.z - pz;
        double h2 = dx * dx + dz * dz;
        double r = w.radius;
        double dy = Math.abs(w.y - py);
        // 水平很近就当作到达（避免站在目标正上方时永远不推进）
        // 否则要求高度也接近，防止飞行掠过被误判
        boolean reached = h2 <= (r * 0.6) * (r * 0.6)
                || (h2 <= r * r && dy <= Math.max(12.0, r * 2.0));
        if (w.area) {
            // 进站即算完成这一段（不必走到站台/轨道的精确坐标）。
            // 注意必须和上面的半径判定「或」起来：走向车站的步行段，第一个航点就是玩家
            // 自己站的位置，只用包围盒判定时它永远不是「到达」，导航从出发起就卡住，
            // 箭头一直是"•"、距离一直不下降。
            reached = reached || (px >= w.areaMinX - 1.0 && px <= w.areaMaxX + 1.0
                    && pz >= w.areaMinZ - 1.0 && pz <= w.areaMaxZ + 1.0);
        }
        return reached;
    }

    /**
     * 已经「走过」这个航点：玩家越过该航点到下一航点的连线末端，且离这条连线不远。
     * <p>
     * 只靠 isReached 推进时，玩家只要没进 4 格半径（贴着墙走、绕开楼房、抄近路砍弯、
     * 走上旁边的坡）就会永久卡在同一个航点：HUD 的距离会停在几百米不再下降，
     * 箭头一直指回身后的航点，走到目的地也判不出到达。
     */
    private boolean isPassed(Waypoint w, int i, double px, double pz) {
        if (i + 1 >= waypoints.size()) {
            return false; // 最后一个点必须真正到达
        }
        Waypoint n = waypoints.get(i + 1);
        double vx = n.x - w.x;
        double vz = n.z - w.z;
        double len2 = vx * vx + vz * vz;
        if (len2 < 1e-6) {
            return false;
        }
        double t = ((px - w.x) * vx + (pz - w.z) * vz) / len2;
        if (t < 1.0) {
            return false; // 还没走过这一段
        }
        double cross = Math.abs((px - w.x) * vz - (pz - w.z) * vx) / Math.sqrt(len2);
        return cross <= Math.max(24.0, w.radius * 6.0); // 偏离走廊太远就继续指回来
    }

    public double remainingDistance(double px, double pz) {
        List<Waypoint> wps = remainingWaypoints();
        if (wps.isEmpty()) {
            return 0;
        }
        if (wps.size() == 1) {
            return wps.get(0).distanceTo(px, pz);
        }
        // 只投影到「当前这一段」：横向偏移 + 该段剩余 + 后面所有段的长度。
        // 在全部剩余段里取最近投影会让玩家直接跳到几百米外的下一段（地铁航点很稀疏），
        // 距离会莫名其妙变小；只看当前段则始终单调、也和实际要走的路线一致。
        Waypoint a = wps.get(0);
        Waypoint b = wps.get(1);
        double tail = 0;
        for (int i = 1; i + 1 < wps.size(); i++) {
            tail += wps.get(i).distanceTo(wps.get(i + 1));
        }
        double vx = b.x - a.x;
        double vz = b.z - a.z;
        double len2 = vx * vx + vz * vz;
        if (len2 < 1e-9) {
            return a.distanceTo(px, pz) + tail;
        }
        double len = Math.sqrt(len2);
        double t = ((px - a.x) * vx + (pz - a.z) * vz) / len2;
        if (t < 0) {
            t = 0;
        } else if (t > 1) {
            t = 1;
        }
        double qx = a.x + vx * t;
        double qz = a.z + vz * t;
        return Math.hypot(px - qx, pz - qz) + len * (1.0 - t) + tail;
    }

    public double remainingSeconds(double px, double py, double pz) {
        List<Waypoint> wps = remainingWaypoints();
        if (wps.isEmpty()) {
            return 0;
        }
        double sec = wps.get(0).distanceTo(px, pz) / speedOf(wps.get(0));
        for (int i = 0; i + 1 < wps.size(); i++) {
            Waypoint a = wps.get(i);
            Waypoint b = wps.get(i + 1);
            sec += a.distanceTo(b) / speedOf(b);
        }
        // 候车 / 换乘时间：规划器把这些算进了 estSeconds，HUD 不加就会比地图上的预计少一半
        MetroNavConfig cfg = MetroNavConfig.get();
        for (int i = 0; i < wps.size(); i++) {
            Waypoint w = wps.get(i);
            if (!w.isMetro()) {
                continue;
            }
            int full = nextIndex + i;
            boolean legStart = full == 0 || waypoints.get(full - 1).legIndex != w.legIndex;
            if (!legStart) {
                continue;
            }
            if (w.transfer) {
                sec += cfg.transferPenaltySeconds;
            } else if (full == 0 || !waypoints.get(full - 1).isMetro()) {
                sec += cfg.boardingPenaltySeconds;
            }
        }
        return sec;
    }

    private double speedOf(Waypoint w) {
        return w.speed > 0 ? w.speed : MetroNavConfig.get().walkSpeedMps;
    }
}
