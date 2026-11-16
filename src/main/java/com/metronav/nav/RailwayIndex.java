package com.metronav.nav;

import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;

import com.metronav.MetroNavConfig;

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
        /** 和 stationIds 一一对应的坐标（取自该线在这一站的站台）。
         *  站点区的 center 是所有站台的平均，大交换站能偏出几百格；
         *  站台才是下车的地方。 */
        public final List<double[]> stationPoints = new ArrayList<>();
        /** 这条线在该站的分钟数据不可用时用的备份。 */

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
    /** 站外换乘：两个相隔不远但不同 id 的站区（上下行分站、地上地下分站）。 */
    public final Map<Long, List<Link>> walkLinks = new HashMap<>();

    /** 一条「走过去换乘」边。 */
    public static final class Link {
        public final long other;
        public final double distance;

        Link(long other, double distance) {
            this.other = other;
            this.distance = distance;
        }
    }
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
                        rn.stationPoints.add(platformPoint(platform, st));
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
                        // 线路里出现的车站若不在 stations 表（MTR 数据不同步），补一个占位节点，
                        // 否则这条边会被丢掉，导致"游戏里能坐地铁、导航说不可达"。
                        index.stations.put(sid, new StationNode(sid, "站" + (sid % 1000), 0x9E9E9E,
                                0, 64, 0, -30, -30, 30, 30));
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
        buildWalkLinks(index);
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

    /**
     * 把「相隔不远的两个站区」连起来。
     *
     * <p>MTR 里一个换乘站可以是一个 station area 里放好几条线的站台，
     * 也可以是两块相邻但不相连的 area（上下行分站、地上地下分站、
     * 两条线各建一个）。后一种情况下数据里根本没有换乘边，
     * 规划器会把这个换乘当成「不可达」，于是一条能坐的线路也只能给出全程步行。
     * 这里按水平距离补上这些边，换乘成本在 A* 里按「走过去的时间 + 换乘惩罚」算。
     */
    /**
     * 这个站台的中心点。MTR 的 Platform 存的是两端（position1/position2），
     * 中心 = 两端中点；拿不到就退回站点区 center。
     * 用反射是为了不同 MTR 版本（字段在生成的 Schema 里）都能跑。
     */
    static double[] platformPoint(Platform platform, Station station) {
        if (!MetroNavConfig.get().stationPointFromPlatform) {
            platform = null; // 默认只信站点区中心
        }
        if (platform != null) {
            try {
                Object p1 = readField(platform, "position1");
                Object p2 = readField(platform, "position2");
                if (p1 instanceof Position && p2 instanceof Position) {
                    Position a = (Position) p1;
                    Position b = (Position) p2;
                    boolean aUnset = a.getX() == 0 && a.getZ() == 0;
                    boolean bUnset = b.getX() == 0 && b.getZ() == 0;
                    // 两端都没写、或者两端离得离谱（>400 格）都别信：MTR 里没落盘的 Position 是 (0,0,0)，
                    // 拿它算中点会把车站甩到世界原点，导航就会「早一站」「乱认站」。
                    double span = Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
                    if (!aUnset && !bUnset && span <= 400) {
                        double px = (a.getX() + b.getX()) / 2.0;
                        double pz = (a.getZ() + b.getZ()) / 2.0;
                        if (insideStation(station, px, pz)) {
                            return new double[]{px, (a.getY() + b.getY()) / 2.0, pz};
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Object mid = platform.getClass().getMethod("getMidPosition").invoke(platform);
                if (mid instanceof Position) {
                    Position m = (Position) mid;
                    return new double[]{m.getX(), m.getY(), m.getZ()};
                }
            } catch (Throwable ignored) {
            }
        }
        if (station != null) {
            try {
                Position c = station.getCenter();
                if (c != null) {
                    return new double[]{c.getX(), c.getY(), c.getZ()};
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 点是不是在这个站点区的包围盒附近（±64 格容差）；包围盒不可信时放行。 */
    private static boolean insideStation(Station station, double x, double z) {
        if (station == null) {
            return true;
        }
        try {
            double minX = station.getMinX();
            double minZ = station.getMinZ();
            double maxX = station.getMaxX();
            double maxZ = station.getMaxZ();
            if (maxX <= minX || maxZ <= minZ) {
                return true;
            }
            return x >= minX - 64 && x <= maxX + 64 && z >= minZ - 64 && z <= maxZ + 64;
        } catch (Throwable t) {
            return true;
        }
    }

    private static Object readField(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void buildWalkLinks(RailwayIndex index) {
        double max = MetroNavConfig.get().transferMaxBlocks;
        if (max <= 0) {
            return;
        }
        List<StationNode> all = new ArrayList<>(index.stations.values());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                StationNode a = all.get(i);
                StationNode b = all.get(j);
                double d = Math.hypot(a.x - b.x, a.z - b.z);
                if (d <= 1.0 || d > max) {
                    continue;
                }
                index.walkLinks.computeIfAbsent(a.id, k -> new ArrayList<>()).add(new Link(b.id, d));
                index.walkLinks.computeIfAbsent(b.id, k -> new ArrayList<>()).add(new Link(a.id, d));
            }
        }
    }
}
