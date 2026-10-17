package com.metronav.nav;

import com.metronav.MetroNavConfig;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public class NavigationSession {
    private static final NavigationSession INSTANCE = new NavigationSession();

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

    private final List<Waypoint> waypoints = new ArrayList<>();
    private final List<LegView> legs = new ArrayList<>();
    private int nextIndex = 0;
    private boolean active = false;
    private boolean arrived = false;
    private String destinationName = "";
    public double totalDistance;
    public double estSeconds;

    private NavigationSession() {}

    public void startMixed(RailwayIndex index, RoutePlanner.Plan plan) {
        stop();
        if (plan == null || plan.legs.isEmpty()) {
            return;
        }
        destinationName = plan.destinationName == null || plan.destinationName.isEmpty()
                ? "目的地" : plan.destinationName;
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
                            area = true;
                            aminX = sn.minX;
                            aminZ = sn.minZ;
                            amaxX = sn.maxX;
                            amaxZ = sn.maxZ;
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
                    if (isLastOfPlan) {
                        action = "步行至目的地 " + destinationName;
                    } else if (next != null && next.isMetro()) {
                        action = leg.name + "，乘坐 " + NavText.lineName(next.name, next.express) + directionSuffix(index, next);
                        // As soon as the player enters the station's footprint, the walk leg
                        // is done and navigation advances (no need to reach the track).
                        if (!next.stationIds.isEmpty()) {
                            RailwayIndex.StationNode sn = index.stations.get(next.stationIds.get(0));
                            if (sn != null) {
                                area = true;
                                aminX = sn.minX;
                                aminZ = sn.minZ;
                                amaxX = sn.maxX;
                                amaxZ = sn.maxZ;
                            }
                        }
                    } else {
                        action = "步行";
                    }
                }

                double radius = leg.isMetro()
                        ? MetroNavConfig.get().arrivalRadius
                        : MetroNavConfig.get().walkArrivalRadius;
                waypoints.add(new Waypoint(p[0], p[1], p[2], leg.kind, li, radius, leg.speed,
                        lineColor, lineName, title, action, area, transfer, aminX, aminZ, amaxX, amaxZ));
            }
            legs.add(new LegView(leg.kind, leg.name, leg.color));
            lastLegWasMetro = leg.isMetro();
        }
        active = true;
        arrived = false;
        nextIndex = 0;
        totalDistance = plan.totalDistance;
        estSeconds = plan.estSeconds;
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
        legs.clear();
        nextIndex = 0;
    }

    public boolean isActive() {
        return active;
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

    public void tick(Minecraft client) {
        if (!active) {
            return;
        }
        if (client.player == null || client.level == null) {
            stop();
            return;
        }
        double px = client.player.getX();
        double pz = client.player.getZ();
        while (nextIndex < waypoints.size()) {
            Waypoint w = waypoints.get(nextIndex);
            boolean reached;
            if (w.area) {
                // Stop as soon as the player is inside the station's footprint.
                reached = px >= w.areaMinX - 1.0 && px <= w.areaMaxX + 1.0
                        && pz >= w.areaMinZ - 1.0 && pz <= w.areaMaxZ + 1.0;
            } else {
                double dx = w.x - px;
                double dz = w.z - pz;
                reached = dx * dx + dz * dz <= w.radius * w.radius;
            }
            if (reached) {
                nextIndex++;
            } else {
                break;
            }
        }
        if (nextIndex >= waypoints.size()) {
            arrived = true;
        }
    }

    public double remainingDistance(double px, double pz) {
        List<Waypoint> wps = remainingWaypoints();
        if (wps.isEmpty()) {
            return 0;
        }
        double d = wps.get(0).distanceTo(px, pz);
        for (int i = 0; i + 1 < wps.size(); i++) {
            d += wps.get(i).distanceTo(wps.get(i + 1));
        }
        return d;
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
        return sec;
    }

    private double speedOf(Waypoint w) {
        return w.speed > 0 ? w.speed : MetroNavConfig.get().walkSpeedMps;
    }
}
