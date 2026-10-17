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

        State(NodeKey key, double cost, State prev) {
            this.key = key;
            this.cost = cost;
            this.prev = prev;
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
                double speed = speedOf(rn);
                List<Integer> occurrences = index.stationRouteIndices
                        .getOrDefault(cur.key.station, Collections.emptyMap())
                        .get(rn.id);
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
                curLeg.speed = speedOf(rn);
                curLeg.express = rn.express;
                curLeg.stationIds.add(st.key.station);
                curRouteId = rid;
                prevStation = st.key.station;
            } else if (st.key.station != prevStation) {
                curLeg.distance += distance(index, prevStation, st.key.station);
                curLeg.stationIds.add(st.key.station);
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

    private static void finishLeg(RailwayIndex index, List<RailwayIndex.RouteNode> routeList,
                                  Map<Long, Integer> routeIndexById, Leg leg, Plan plan) {
        for (long sid : leg.stationIds) {
            RailwayIndex.StationNode sn = index.stations.get(sid);
            if (sn != null) {
                leg.points.add(new double[]{sn.x, sn.y, sn.z});
                leg.stationNames.add(sn.name);
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

        RailwayIndex.StationNode destStation = index.nearest(dx, dy, dz, 100);
        RailwayIndex.StationNode originStation = index.nearest(px, py, pz, 600);
        if (destStation != null) {
            plan.destinationName = destStation.name;
        }

        List<double[]> walkPts = WalkPlanner.path(level, px, py, pz, dx, dy, dz);
        double walkDist = WalkPlanner.pathLength(walkPts);
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
        if (nc < best.getOrDefault(nk, Double.POSITIVE_INFINITY)) {
            best.put(nk, nc);
            pq.add(new State(nk, nc, cur));
        }
    }

    private static double speedOf(RailwayIndex.RouteNode rn) {
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
