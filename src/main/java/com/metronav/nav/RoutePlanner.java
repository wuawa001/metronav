package com.metronav.nav;

import com.metronav.MetroNavConfig;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class RoutePlanner {
    private RoutePlanner() {}

    public static final int WALK = 0;
    public static final int METRO = 1;
    public static final int WALK_COLOR = 0x00E5FF;

    public static final class Leg {
        public int kind;
        public String name;
        public int color;
        public long routeId = -1;
        public double distance;
        public double speed; // m/s
        public boolean express;
        public final List<Long> stationIds = new ArrayList<>();
        /** 每站的站台坐标（和 stationIds 对齐）。 */
        public final List<double[]> stationPoints = new ArrayList<>();
        public final List<double[]> points = new ArrayList<>();
        public final List<String> stationNames = new ArrayList<>();

        public boolean isMetro() {
            return kind == METRO;
        }

    }

    public static final class Plan {
        public final List<Leg> legs = new ArrayList<>();
        public double totalDistance;
        public double estSeconds;
        public boolean unreachable = true;
        public String destinationName = "";
        /** 真正要去的坐标（点的那个点），不一定等于最后一个航点。 */
        public double destX, destY, destZ;
        /** 目标站 id（0 = 目的地不是车站）。 */
        public long destStationId;
        /**
         * 为什么这样规划（给玩家看的）。地铁被否掉时必须说清原因：
         * 以前界面只显示「线路数 32」，玩家看到的是结果，不是理由。
         */
        public String decisionNote = "";
        /** 步行段是不是用自动识别的路网算出来的（false = 直线兜底）。 */
        public boolean viaRoads = false;
        /** 沿道路比直线多走了多少米。 */
        public double roadExtraMeters = 0;
        /** 站外换乘一共走了多少米（两块不相连 station area 之间）。 */
        public double walkTransferMeters = 0;
    }

    /** 沿道路比直线多走了多少米（负数归零）。 */
    private static double walkDist0(double via, double px, double pz, double dx, double dz,
                                    RoadGraph.Result road) {
        if (road == null || via < 1.0) {
            return 0;
        }
        double straight = Math.hypot(dx - px, dz - pz);
        return Math.max(0, via - straight);
    }

    private static final class NodeKey {
        final long station;
        final int route;

        NodeKey(long station, int route) {
            this.station = station;
            this.route = route;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof NodeKey)) {
                return false;
            }
            NodeKey k = (NodeKey) o;
            return k.station == station && k.route == route;
        }

        @Override
        public int hashCode() {
            return (int) (station ^ (station >>> 32)) * 31 + route;
        }
    }

    private static final class State {
        final NodeKey key;
        final double cost;
        final State prev;
        /** 走到这个状态时「站外换乘」走了多少米（0 = 不是站外换乘）。 */
        final double walkLink;
        /** 这一跳落脚的站台坐标（A* 展开时就知道，别事后再按序号猜）。 */
        final double[] point;

        State(NodeKey key, double cost, State prev) {
            this(key, cost, prev, 0, null);
        }

        State(NodeKey key, double cost, State prev, double walkLink) {
            this(key, cost, prev, walkLink, null);
        }

        State(NodeKey key, double cost, State prev, double walkLink, double[] point) {
            this.key = key;
            this.cost = cost;
            this.prev = prev;
            this.walkLink = walkLink;
            this.point = point;
        }
    }

    /** Metro-only plan between two stations. */
    public static Plan planMetro(RailwayIndex index, long originId, long destId, MetroNavConfig cfg) {
        Plan plan = new Plan();
        if (originId == destId) {
            plan.unreachable = false;
            return plan;
        }
        if (!index.stations.containsKey(originId) || !index.stations.containsKey(destId)) {
            return plan;
        }

        List<RailwayIndex.RouteNode> routeList = new ArrayList<>(index.routes.values());
        Map<Long, Integer> routeIndexById = new HashMap<>();
        for (int i = 0; i < routeList.size(); i++) {
            routeIndexById.put(routeList.get(i).id, i);
        }
        double boarding = cfg.boardingPenaltySeconds;
        double transfer = cfg.transferPenaltySeconds;
        double dwell = cfg.dwellSeconds;

        PriorityQueue<State> pq = new PriorityQueue<>(Comparator.comparingDouble(s -> s.cost));
        Map<NodeKey, Double> best = new HashMap<>();
        State start = new State(new NodeKey(originId, -1), 0, null);
        pq.add(start);
        best.put(start.key, 0.0);

        State goal = null;
        while (!pq.isEmpty()) {
            State cur = pq.poll();
            if (cur.cost > best.getOrDefault(cur.key, Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (cur.key.station == destId) {
                goal = cur;
                break;
            }
            Set<Long> routesHere = index.routesAtStation.getOrDefault(cur.key.station, Collections.emptySet());
            if (cur.key.route == -1) {
                for (long rid : routesHere) {
                    Integer ri = routeIndexById.get(rid);
                    if (ri == null) {
                        continue;
                    }
                    NodeKey nk = new NodeKey(cur.key.station, ri);
                    double nc = cur.cost + boarding;
                    if (nc < best.getOrDefault(nk, Double.POSITIVE_INFINITY)) {
                        best.put(nk, nc);
                        pq.add(new State(nk, nc, cur));
                    }
                }
            } else {
                RailwayIndex.RouteNode rn = routeList.get(cur.key.route);
                double speed = speedOf(rn, cfg);
                List<Integer> occurrences = index.stationRouteIndices
                        .getOrDefault(cur.key.station, Collections.emptyMap())
                        .get(rn.id);
                if (occurrences == null || occurrences.isEmpty()) {
                    // 索引缺失时的兜底：直接扫线路站点表，避免整条线被判为不可达
                    List<Integer> scanned = new ArrayList<>();
                    for (int i2 = 0; i2 < rn.stationIds.size(); i2++) {
                        if (rn.stationIds.get(i2) == cur.key.station) {
                            scanned.add(i2);
                        }
                    }
                    occurrences = scanned.isEmpty() ? null : scanned;
                }
                if (occurrences != null) {
                    int n = rn.stationIds.size();
                    for (int i : occurrences) {
                        if (i > 0) {
                            tryHop(index, cur, rn, i - 1, speed, dwell, best, pq);
                        }
                        if (i + 1 < n) {
                            tryHop(index, cur, rn, i + 1, speed, dwell, best, pq);
                        } else if (rn.circular) {
                            tryHop(index, cur, rn, 0, speed, dwell, best, pq);
                        }
                        if (i == 0 && rn.circular) {
                            tryHop(index, cur, rn, n - 1, speed, dwell, best, pq);
                        }
                    }
                }
                // 站外换乘：下车走到附近另一个站区再上车（数据里两块 area 不相连的换乘站）。
                // 只允许在线路的两端这么做：中途下车走过去，规划器会为了省几十秒把
                // 「再坐一站」换成「走一段」，导航就会在到站前就让你换乘（人还在下一站）。
                boolean atTerminal = !cfg.transferWalkAtTerminalOnly
                        || isTerminalOf(index, rn, cur.key.station);
                List<RailwayIndex.Link> links = atTerminal ? index.walkLinks.get(cur.key.station) : null;
                if (links != null) {
                    for (RailwayIndex.Link link : links) {
                        NodeKey nk = new NodeKey(link.other, -1);
                        double nc = cur.cost + link.distance / Math.max(1.0, cfg.walkSpeedMps) + transfer * 0.5;
                        if (nc < best.getOrDefault(nk, Double.POSITIVE_INFINITY)) {
                            best.put(nk, nc);
                            pq.add(new State(nk, nc, cur, link.distance));
                        }
                    }
                }
                for (long rid : routesHere) {
                    Integer ri = routeIndexById.get(rid);
                    if (ri == null || ri == cur.key.route) {
                        continue;
                    }
                    NodeKey nk = new NodeKey(cur.key.station, ri);
                    double nc = cur.cost + transfer;
                    if (nc < best.getOrDefault(nk, Double.POSITIVE_INFINITY)) {
                        best.put(nk, nc);
                        pq.add(new State(nk, nc, cur));
                    }
                }
            }
        }

        if (goal == null) {
            return plan;
        }

        List<State> chain = new ArrayList<>();
        for (State s = goal; s != null; s = s.prev) {
            chain.add(s);
        }
        Collections.reverse(chain);

        long curRouteId = -1;
        Leg curLeg = null;
        long prevStation = Long.MIN_VALUE;
        for (State st : chain) {
            if (st.walkLink > 0) {
                plan.walkTransferMeters += st.walkLink;
                plan.totalDistance += st.walkLink;
            }
            if (st.key.route == -1) {
                continue;
            }
            long rid = routeList.get(st.key.route).id;
            if (rid != curRouteId) {
                if (curLeg != null) {
                    finishLeg(index, routeList, routeIndexById, curLeg, plan);
                }
                RailwayIndex.RouteNode rn = routeList.get(st.key.route);
                curLeg = new Leg();
                curLeg.kind = METRO;
                curLeg.name = rn.name;
                curLeg.color = rn.color;
                curLeg.routeId = rid;
                curLeg.speed = speedOf(rn, cfg);
                curLeg.express = rn.express;
                curLeg.stationIds.add(st.key.station);
                curLeg.stationPoints.add(st.point != null ? st.point : stationPoint(index, rid, st.key.station, rn));
                curRouteId = rid;
                prevStation = st.key.station;
            } else if (st.key.station != prevStation) {
                curLeg.distance += distance(index, prevStation, st.key.station);
                curLeg.stationIds.add(st.key.station);
                curLeg.stationPoints.add(st.point != null ? st.point : stationPoint(index, rid, st.key.station, routeList.get(st.key.route)));
                prevStation = st.key.station;
            }
        }
        if (curLeg != null) {
            finishLeg(index, routeList, routeIndexById, curLeg, plan);
        }
        for (Leg leg : plan.legs) {
            plan.totalDistance += leg.distance;
        }
        plan.unreachable = false;
        plan.estSeconds = goal.cost;
        RailwayIndex.StationNode d = index.stations.get(destId);
        plan.destinationName = d == null ? "" : d.name;
        return plan;
    }

    /** 这条线在这一站的站台坐标；拿不到返回 null（调用方退回站点区中心）。 */
    private static double[] stationPoint(RailwayIndex index, long routeId, long stationId,
                                         RailwayIndex.RouteNode rn) {
        if (rn == null) {
            return null;
        }
        List<Integer> occ = index.stationRouteIndices
                .getOrDefault(stationId, java.util.Collections.emptyMap()).get(routeId);
        // 一条线上同一个站出现两次（两条站台、支线折返）时序号是有歧义的；
        // 猜错就是「早一站 / 晚一站」，所以宁可不给，退回站点区中心。
        if (occ == null || occ.size() != 1) {
            return null;
        }
        int idx = occ.get(0);
        if (idx >= 0 && idx < rn.stationPoints.size()) {
            return rn.stationPoints.get(idx);
        }
        return null;
    }

    /** 这个站在线路上是不是首站或末站（站外换乘只在这两处允许）。 */
    private static boolean isTerminalOf(RailwayIndex index, RailwayIndex.RouteNode rn, long stationId) {
        List<Integer> occ = index.stationRouteIndices
                .getOrDefault(stationId, java.util.Collections.emptyMap()).get(rn.id);
        if (occ == null || occ.isEmpty()) {
            return rn.stationIds.isEmpty() || rn.stationIds.get(0) == stationId
                    || rn.stationIds.get(rn.stationIds.size() - 1) == stationId;
        }
        for (int i : occ) {
            if (i == 0 || i == rn.stationIds.size() - 1) {
                return true;
            }
        }
        return false;
    }

    private static void finishLeg(RailwayIndex index, List<RailwayIndex.RouteNode> routeList,
                                  Map<Long, Integer> routeIndexById, Leg leg, Plan plan) {
        for (int i = 0; i < leg.stationIds.size(); i++) {
            long sid = leg.stationIds.get(i);
            RailwayIndex.StationNode sn = index.stations.get(sid);
            double[] pt = null;
            if (i < leg.stationPoints.size()) {
                pt = leg.stationPoints.get(i);
            }
            if (pt == null && sn != null) {
                pt = new double[]{sn.x, sn.y, sn.z};
            }
            if (pt != null) {
                leg.points.add(pt);
                leg.stationNames.add(sn == null ? "?" : sn.name);
            }
        }
        plan.legs.add(leg);
    }

    /** Mixed plan: walking to/from stations + metro in between. Always returns a plan (walking fallback). */
    public static Plan planMixed(RailwayIndex index, ClientLevel level,
                                 double px, double py, double pz,
                                 double dx, double dy, double dz, MetroNavConfig cfg) {
        Plan plan = new Plan();
        plan.unreachable = false;
        plan.destX = dx;
        plan.destY = dy;
        plan.destZ = dz;

        RailwayIndex.StationNode destStation = index.nearest(dx, dy, dz, 100);
        RailwayIndex.StationNode originStation = index.nearest(px, py, pz, 600);
        if (destStation != null) {
            plan.destinationName = destStation.name;
            plan.destStationId = destStation.id;
        }

        List<double[]> walkPts = WalkPlanner.path(level, px, py, pz, dx, dy, dz);
        RoadGraph.Result walkRoad = WalkPlanner.lastRoad();
        plan.viaRoads = walkRoad != null;
        double walkDist = WalkPlanner.pathLength(walkPts);
        plan.roadExtraMeters = walkDist0(walkDist, px, pz, dx, dz, walkRoad);
        double walkSec = walkDist / cfg.walkSpeedMps;

        List<double[]> w1 = null;
        List<double[]> w2 = null;
        Plan metro = null;
        double metroTotalSec = Double.MAX_VALUE;
        if (originStation != null && destStation != null && originStation.id != destStation.id) {
            Plan mp = planMetro(index, originStation.id, destStation.id, cfg);
            if (!mp.unreachable) {
                w1 = WalkPlanner.path(level, px, py, pz, originStation.x, originStation.y, originStation.z);
                w2 = WalkPlanner.path(level, destStation.x, destStation.y, destStation.z, dx, dy, dz);
                double w1d = WalkPlanner.pathLength(w1);
                double w2d = WalkPlanner.pathLength(w2);
                metroTotalSec = mp.estSeconds + (w1d + w2d) / cfg.walkSpeedMps;
                metro = mp;
            }
        }

        boolean useMetro = metro != null
                && (metroTotalSec <= walkSec * 1.2 || (destStation != null && metroTotalSec <= walkSec * 1.6));

        String roadNote = plan.viaRoads
                ? "沿自动识别的路网 " + NavText.distance(walkDist)
                  + (plan.roadExtraMeters > 20
                        ? "（绕行，比直线多 " + Math.round(plan.roadExtraMeters) + " 米）" : "")
                : "该区域没有可用路面，按直线估算";
        if (useMetro) {
            plan.decisionNote = "地铁 " + NavText.duration(metroTotalSec) + " / 步行 " + NavText.duration(walkSec)
                    + "（含候车 " + Math.round(cfg.boardingPenaltySeconds) + "s、换乘 "
                    + Math.round(cfg.transferPenaltySeconds) + "s）"
                    + (metro.walkTransferMeters > 1
                        ? "，其中站外换乘步行 " + Math.round(metro.walkTransferMeters) + " 米" : "");
        } else if (originStation == null) {
            plan.decisionNote = "600 格内没有车站，已按步行规划：" + roadNote;
        } else if (destStation == null) {
            plan.decisionNote = "目的地 100 格内没有车站，已按步行规划：" + roadNote;
        } else if (metro == null) {
            plan.decisionNote = "线网里没有 " + originStation.name + " → " + destStation.name
                    + " 的可行乘车方案，已按步行规划：" + roadNote;
        } else {
            plan.decisionNote = "地铁 " + NavText.duration(metroTotalSec) + " 比步行 " + NavText.duration(walkSec)
                    + " 更慢（含候车 " + Math.round(cfg.boardingPenaltySeconds) + "s、换乘 "
                    + Math.round(cfg.transferPenaltySeconds) + "s），已按步行规划：" + roadNote;
        }

        if (useMetro && w1 != null && w2 != null) {
            double w1d = WalkPlanner.pathLength(w1);
            double w2d = WalkPlanner.pathLength(w2);
            if (w1.size() >= 2) {
                Leg wl = new Leg();
                wl.kind = WALK;
                wl.name = "步行至 " + originStation.name;
                wl.color = WALK_COLOR;
                wl.speed = cfg.walkSpeedMps;
                wl.points.addAll(w1);
                wl.distance = w1d;
                plan.legs.add(wl);
            }
            plan.legs.addAll(metro.legs);
            if (w2.size() >= 2) {
                Leg wl2 = new Leg();
                wl2.kind = WALK;
                wl2.name = "步行至目的地";
                wl2.color = WALK_COLOR;
                wl2.speed = cfg.walkSpeedMps;
                wl2.points.addAll(w2);
                wl2.distance = w2d;
                plan.legs.add(wl2);
            }
            plan.estSeconds = metroTotalSec;
            plan.totalDistance = metro.totalDistance + w1d + w2d;
            if (destStation != null) {
                plan.destinationName = destStation.name;
            }
        } else {
            Leg wl = new Leg();
            wl.kind = WALK;
            wl.name = "步行前往目的地";
            wl.color = WALK_COLOR;
            wl.speed = cfg.walkSpeedMps;
            wl.points.addAll(walkPts);
            wl.distance = walkDist;
            plan.legs.add(wl);
            plan.estSeconds = walkSec;
            plan.totalDistance = walkDist;
        }
        return plan;
    }

    private static void tryHop(RailwayIndex index, State cur, RailwayIndex.RouteNode rn, int to,
                               double speed, double dwell, Map<NodeKey, Double> best, PriorityQueue<State> pq) {
        long toId = rn.stationIds.get(to);
        if (toId == cur.key.station) {
            return;
        }
        RailwayIndex.StationNode a = index.stations.get(cur.key.station);
        RailwayIndex.StationNode b = index.stations.get(toId);
        if (a == null || b == null) {
            return;
        }
        double dist = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z));
        double effSpeed = speed > 0 ? speed : 11.0;
        double nc = cur.cost + dwell + dist / effSpeed;
        NodeKey nk = new NodeKey(toId, cur.key.route);
        double[] pt = to >= 0 && to < rn.stationPoints.size() ? rn.stationPoints.get(to) : null;
        if (nc < best.getOrDefault(nk, Double.POSITIVE_INFINITY)) {
            best.put(nk, nc);
            pq.add(new State(nk, nc, cur, 0, pt));
        }
    }

    private static double speedOf(RailwayIndex.RouteNode rn, MetroNavConfig cfg) {
        if (rn.mode == org.mtr.core.data.TransportMode.TRAIN && cfg.metroSpeedMps > 0) {
            // 列车用配置的平均旅速：TransportMode.TRAIN 的默认值(20 km/h)远低于实际列车，
            // 直接用它会让地铁时间被放大约 3 倍，规划器于是永远选择步行。
            return cfg.metroSpeedMps;
        }
        if (rn.mode != null && rn.mode.defaultSpeedMetersPerMillisecond > 0) {
            return rn.mode.defaultSpeedMetersPerMillisecond * 1000.0;
        }
        return 11.0;
    }

    private static double distance(RailwayIndex index, long aId, long bId) {
        RailwayIndex.StationNode a = index.stations.get(aId);
        RailwayIndex.StationNode b = index.stations.get(bId);
        if (a == null || b == null) {
            return 0;
        }
        return Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z));
    }
}
