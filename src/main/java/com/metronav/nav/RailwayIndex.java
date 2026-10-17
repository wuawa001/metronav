package com.metronav.nav;

import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RailwayIndex {

    public static final class StationNode {
        public final long id;
        public final String name;
        public final int color;
        public final double x, y, z;
        public final double minX, minZ, maxX, maxZ;

        public StationNode(long id, String name, int color, double x, double y, double z,
                           double minX, double minZ, double maxX, double maxZ) {
            this.id = id;
            this.name = name == null ? "?" : name;
            this.color = color;
            this.x = x;
            this.y = y;
            this.z = z;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }
    }

    public static final class RouteNode {
        public final long id;
        public final String name;
        public final int color;
        public final TransportMode mode;
        public final boolean circular;
        public final boolean express;
        public final List<Long> stationIds = new ArrayList<>();

        public RouteNode(long id, String name, int color, TransportMode mode, boolean circular, boolean express) {
            this.id = id;
            this.name = name == null ? "?" : name;
            this.color = color;
            this.mode = mode;
            this.circular = circular;
            this.express = express;
        }
    }

    public final Map<Long, StationNode> stations = new LinkedHashMap<>();
    public final Map<Long, RouteNode> routes = new LinkedHashMap<>();
    public final Map<Long, Set<Long>> routesAtStation = new HashMap<>();
    public final Map<Long, Map<Long, List<Integer>>> stationRouteIndices = new HashMap<>();

    public static RailwayIndex build(Data data) {
        RailwayIndex index = new RailwayIndex();
        if (data == null) {
            return index;
        }
        for (Station station : data.stations) {
            try {
                String name = station.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Position center = null;
                try {
                    center = station.getCenter();
                } catch (Throwable ignored) {
                }
                if (center == null || (center.getX() == 0 && center.getY() == 0 && center.getZ() == 0)) {
                    center = fallbackCenter(station);
                }
                if (center == null) {
                    continue;
                }
                double minX;
                double minZ;
                double maxX;
                double maxZ;
                try {
                    minX = station.getMinX();
                    minZ = station.getMinZ();
                    maxX = station.getMaxX();
                    maxZ = station.getMaxZ();
                } catch (Throwable t) {
                    minX = center.getX() - 40;
                    minZ = center.getZ() - 40;
                    maxX = center.getX() + 40;
                    maxZ = center.getZ() + 40;
                }
                if (maxX < minX) {
                    double tx = minX;
                    minX = maxX;
                    maxX = tx;
                }
                if (maxZ < minZ) {
                    double tz = minZ;
                    minZ = maxZ;
                    maxZ = tz;
                }
                index.stations.put(station.getId(), new StationNode(station.getId(), name, station.getColor(),
                        center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5, minX, minZ, maxX, maxZ));
            } catch (Throwable ignored) {
            }
        }
        for (Route route : data.routes) {
            try {
                String routeName = route.getName();
                if (routeName == null || routeName.isEmpty()) {
                    continue;
                }
                boolean express = routeName.contains("快车");
                if (!express) {
                    try {
                        String code = route.getRouteNumber();
                        if (code != null && code.toLowerCase().contains("k")) {
                            express = true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                RouteNode rn = new RouteNode(route.getId(), routeName, route.getColor(), route.getTransportMode(),
                        route.getCircularState() != Route.CircularState.NONE, express);
                List<RoutePlatformData> rpds;
                try {
                    rpds = route.getRoutePlatforms();
                } catch (Throwable t) {
                    continue;
                }
                long prevStation = Long.MIN_VALUE;
                for (RoutePlatformData rpd : rpds) {
                    if (rpd == null) {
                        continue;
                    }
                    Platform platform = null;
                    try {
                        platform = rpd.getPlatform();
                    } catch (Throwable ignored) {
                    }
                    if (platform == null) {
                        // MTR client data: platform cache may be empty until written
                        try {
                            rpd.writePlatformCache(route, data.platformIdMap);
                            platform = rpd.getPlatform();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (platform == null) {
                        platform = platformByIdReflective(data, rpd);
                    }
                    if (platform == null) {
                        continue;
                    }
                    Station st = platform.area;
                    if (st == null) {
                        st = stationOfPlatform(data, platform);
                    }
                    if (st == null) {
                        continue;
                    }
                    if (prevStation != st.getId()) {
                        rn.stationIds.add(st.getId());
                        prevStation = st.getId();
                    }
                }
                if (rn.stationIds.size() < 2) {
                    continue;
                }
                index.routes.put(rn.id, rn);
                for (int i = 0; i < rn.stationIds.size(); i++) {
                    long sid = rn.stationIds.get(i);
                    if (!index.stations.containsKey(sid)) {
                        continue;
                    }
                    index.routesAtStation.computeIfAbsent(sid, k -> new LinkedHashSet<>()).add(rn.id);
                    index.stationRouteIndices
                            .computeIfAbsent(sid, k -> new HashMap<>())
                            .computeIfAbsent(rn.id, k -> new ArrayList<>())
                            .add(i);
                }
            } catch (Throwable ignored) {
            }
        }
        return index;
    }

    private static Position fallbackCenter(Station station) {
        try {
            double x = 0, y = 0, z = 0;
            int n = 0;
            for (Platform p : station.savedRails) {
                Position mid = p.getMidPosition();
                if (mid != null) {
                    x += mid.getX();
                    y += mid.getY();
                    z += mid.getZ();
                    n++;
                }
            }
            if (n > 0) {
                return new Position((long) Math.round(x / n), (long) Math.round(y / n), (long) Math.round(z / n));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Station stationOfPlatform(Data data, Platform platform) {
        try {
            for (Station s : data.stations) {
                if (s.savedRails.contains(platform)) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Platform platformByIdReflective(Data data, RoutePlatformData rpd) {
        try {
            java.lang.reflect.Field f = rpd.getClass().getSuperclass().getDeclaredField("platformId");
            f.setAccessible(true);
            long id = f.getLong(rpd);
            return data.platformIdMap.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    public StationNode nearest(double x, double y, double z, double maxDist) {
        StationNode best = null;
        double bestD = maxDist * maxDist;
        for (StationNode sn : stations.values()) {
            double dx = sn.x - x;
            double dy = sn.y - y;
            double dz = sn.z - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = sn;
            }
        }
        return best;
    }
}
