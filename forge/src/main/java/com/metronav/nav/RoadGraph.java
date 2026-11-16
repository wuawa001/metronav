package com.metronav.nav;

import com.metronav.MetroNavConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 从世界方块里自动识别出来的路网（步行 / 驾车两种剖面）。
 *
 * <p>HowToGo 的路网要玩家先画（道路、路口、单行、等级），MetroNav 不画：直接读方块。
 * 查询时在起终点走廊里按 spacing 撒一层节点，按「这个宽高的人/车能不能站在这里」过滤，
 * 相邻节点满足台阶规则就连边，A* 找路，最后做直线化简。
 *
 * <p>「自动识别」的含义是：没有编辑、没有持久化、没有道路语义。地图改了下一次查询就是新路网，
 * 室内、山上、楼顶、街区中间能不能走，全由方块说话。
 *
 * <p>车和人的差别只在 {@link Profile}：人占 1×2 格、能下 4 格；车占 3×3 格、坡度超过 1 格就不过，
 * 掉进水里也不算路。所以「马路」不是标签，是「三格宽、两格高、连着平地」这个几何条件。
 */
public final class RoadGraph {

    /** 通行剖面。 */
    public static final class Profile {
        public final boolean vehicle;
        /** 需要的宽度（格）：1=人，3=车。 */
        public final int width;
        /** 需要的高度（格）：2=人，3=车。 */
        public final int height;
        /** 能上的台阶。 */
        public final double stepUp;
        /** 能下的落差，超过就算跳崖/翻车。 */
        public final double stepDown;

        private Profile(boolean vehicle, int width, int height, double stepUp, double stepDown) {
            this.vehicle = vehicle;
            this.width = width;
            this.height = height;
            this.stepUp = stepUp;
            this.stepDown = stepDown;
        }

        public static final Profile WALK = new Profile(false, 1, 2, 1.0, 4.0);
    }

    /** 一次查询的结果。 */
    public static final class Result {
        /** 化简 + 每 12 格重采样后的点（首点=起点，末点=终点，拐点一定保留）。 */
        public final List<double[]> points;
        /** A* 原始折线长度。 */
        public final double rawLength;
        public final int visited;
        public final int spacing;
        /** 用了哪个剖面。 */
        public final boolean vehicle;

        Result(List<double[]> points, double rawLength, int visited, int spacing, boolean vehicle) {
            this.points = points;
            this.rawLength = rawLength;
            this.visited = visited;
            this.spacing = spacing;
            this.vehicle = vehicle;
        }
    }

    public static boolean TRACE = false;

    private static void trace(String msg) {
        if (TRACE) {
            System.out.println("[road] " + msg);
        }
    }

    private RoadGraph() {}

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    public static Result findPath(ClientLevel level, Profile profile, double ax, double ay, double az,
                                  double bx, double by, double bz) {
        MetroNavConfig cfg = MetroNavConfig.get();
        if (level == null) {
            return null;
        }
        if (!cfg.autoRoad) {
            return null;
        }
        double straight = Math.hypot(bx - ax, bz - az);
        if (straight < 1.0) {
            return null;
        }
        if (!level.getChunkSource().hasChunk((int) Math.floor(ax) >> 4, (int) Math.floor(az) >> 4)
                || !level.getChunkSource().hasChunk((int) Math.floor(bx) >> 4, (int) Math.floor(bz) >> 4)) {
            return null;
        }

        int spacing = Math.max(2, cfg.roadSpacing);
        int maxNodes = cfg.roadMaxNodes;
        for (int attempt = 0; attempt < 3; attempt++, spacing++) {
            double margin = 32.0 + straight * (0.12 + 0.12 * attempt);
            Window w = new Window(level, ax, az, bx, bz, margin, spacing, profile);
            if (w.nodeCount() > maxNodes) {
                trace("attempt=" + attempt + " window too big: " + w.nodeCount());
                continue;
            }
            trace("attempt=" + attempt + " spacing=" + spacing + " nodes=" + w.nodeCount()
                    + " vehicle=" + profile.vehicle);
            List<double[]> path = search(w, ax, ay, az, bx, by, bz, maxNodes);
            if (path == null || path.size() < 2) {
                trace("  search -> null visited=" + w.visited);
                continue;
            }
            List<double[]> simplified = simplify(level, path, profile);
            List<double[]> points = resample(simplified, 12.0);
            if (points.size() < 2) {
                continue;
            }
            trace("  ok pts=" + path.size() + " simplified=" + simplified.size()
                    + " final=" + points.size() + " visited=" + w.visited);
            return new Result(points, polyLength(path), w.visited, spacing, profile.vehicle);
        }
        return null;
    }

    private static final class Window {
        final ClientLevel level;
        final int spacing;
        final Profile profile;
        final int gx0, gz0, gx1, gz1;
        final Map<Long, Double> yCache = new HashMap<>();
        int visited = 0;

        Window(ClientLevel level, double ax, double az, double bx, double bz, double margin,
               int spacing, Profile profile) {
            this.level = level;
            this.spacing = spacing;
            this.profile = profile;
            this.gx0 = (int) Math.floor((Math.min(ax, bx) - margin) / spacing);
            this.gx1 = (int) Math.ceil((Math.max(ax, bx) + margin) / spacing);
            this.gz0 = (int) Math.floor((Math.min(az, bz) - margin) / spacing);
            this.gz1 = (int) Math.ceil((Math.max(az, bz) + margin) / spacing);
        }

        long nodeCount() {
            return (long) (gx1 - gx0 + 1) * (long) (gz1 - gz0 + 1);
        }

        boolean inside(int gx, int gz) {
            return gx >= gx0 && gx <= gx1 && gz >= gz0 && gz <= gz1;
        }

        int wx(int g) {
            return g * spacing;
        }

        /** 这个格点能不能站/能不能开（按剖面要求检查整个占地）。 */
        double y(int gx, int gz, double refY) {
            long key = ((long) gx << 32) ^ (gz & 0xffffffffL);
            Double c = yCache.get(key);
            if (c != null) {
                return c;
            }
            double v = nodeY(level, wx(gx), wx(gz), refY, profile);
            yCache.put(key, v);
            return v;
        }
    }

    /**
     * 剖面的可站立高度：中心点找「脚下实心 + 头顶空」，再检查整个占地（宽度方向）都平得起来。
     * 车宽 3 格 → 左右各 1 格也要能站，且高度差不超过 1 格（车不能跨台阶）。
     */
    static double nodeY(ClientLevel level, int wx, int wz, double refY, Profile profile) {
        double center = standY(level, wx, wz, refY, profile.height);
        if (Double.isNaN(center)) {
            return Double.NaN;
        }
        int half = profile.width / 2;
        if (half <= 0) {
            return center;
        }
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                double y = standY(level, wx + dx, wz + dz, center, profile.height);
                if (Double.isNaN(y) || Math.abs(y - center) > 1.0) {
                    return Double.NaN;
                }
            }
        }
        return center;
    }

    /** 从 refY 附近上下找一个「脚下实心 + height 格全空」的高度。找不到返回 NaN。 */
    static double standY(ClientLevel level, int x, int z, double refY, int height) {
        int base = (int) Math.floor(refY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // 先试「和参考点一样高」，平地是绝大多数情况，这样每个节点只要 3 次方块查询
        for (int dy : new int[]{0, -1, 1, -2, 2, -3, -4, -5, -6}) {
            int y = base + dy;
            boolean free = true;
            for (int h = 0; h < height; h++) {
                pos.set(x, y + h, z);
                BlockState s = level.getBlockState(pos);
                if (!s.isAir() && !s.is(Blocks.WATER)) {
                    free = false;
                    break;
                }
            }
            if (!free) {
                continue;
            }
            pos.set(x, y - 1, z);
            BlockState below = level.getBlockState(pos);
            if (!below.isAir() && !below.is(Blocks.WATER)) {
                return y;
            }
        }
        return Double.NaN;
    }

    private static List<double[]> search(Window w, double ax, double ay, double az,
                                         double bx, double by, double bz, int maxNodes) {
        int sgx = (int) Math.round(ax / w.spacing);
        int sgz = (int) Math.round(az / w.spacing);
        int ggx = (int) Math.round(bx / w.spacing);
        int ggz = (int) Math.round(bz / w.spacing);
        double sy = w.y(sgx, sgz, ay);
        double gy = w.y(ggx, ggz, by);
        trace("  start=(" + w.wx(sgx) + "," + w.wx(sgz) + ") y=" + sy
                + " goal=(" + w.wx(ggx) + "," + w.wx(ggz) + ") y=" + gy);
        if (Double.isNaN(sy) || Double.isNaN(gy)) {
            return null;
        }
        long start = node(sgx, sgz);
        long goal = node(ggx, ggz);
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> came = new HashMap<>();
        PriorityQueue<Entry> open = new PriorityQueue<>((p, q) -> Double.compare(p.f, q.f));
        gScore.put(start, 0.0);
        open.add(new Entry(start, h(sgx, sgz, ggx, ggz) * w.spacing, sgx, sgz, sy, 0.0));

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Entry cur = open.poll();
            Double known = gScore.get(cur.key);
            if (known != null && cur.g > known + 1e-6) {
                continue; // 已经有更好的路径到这个点
            }
            expanded++;
            w.visited = expanded;
            if (cur.key == goal) {
                return rebuild(came, cur.key, ax, ay, az, bx, by, bz, w);
            }
            for (int[] d : DIRS) {
                int nx = cur.gx + d[0];
                int nz = cur.gz + d[1];
                if (!w.inside(nx, nz)) {
                    continue;
                }
                double ny = w.y(nx, nz, cur.y);
                if (Double.isNaN(ny)) {
                    continue;
                }
                double dy = ny - cur.y;
                if (dy > w.profile.stepUp || dy < -w.profile.stepDown) {
                    continue;
                }
                if (d[0] != 0 && d[1] != 0) {
                    // 斜着走不能切墙角：两边至少有一边能过
                    boolean sideA = !Double.isNaN(w.y(cur.gx + d[0], cur.gz, cur.y));
                    boolean sideB = !Double.isNaN(w.y(cur.gx, cur.gz + d[1], cur.y));
                    if (!sideA || !sideB) {
                        continue;
                    }
                }
                // 上下坡要罚：平路优先（开车尤其明显）
                double step = Math.hypot(d[0], d[1]) * w.spacing * (1.0 + Math.abs(dy) * 0.8);
                long nk = node(nx, nz);
                double tentative = cur.g + step;
                Double old = gScore.get(nk);
                if (old != null && tentative >= old - 1e-9) {
                    continue;
                }
                gScore.put(nk, tentative);
                came.put(nk, cur.key);
                open.add(new Entry(nk, tentative + h(nx, nz, ggx, ggz) * w.spacing, nx, nz, ny, tentative));
            }
        }
        return null;
    }

    private static double h(int x0, int z0, int x1, int z1) {
        double dx = Math.abs(x0 - x1);
        double dz = Math.abs(z0 - z1);
        return Math.max(dx, dz) + 0.4142 * Math.min(dx, dz);
    }

    private static long node(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xffffffffL);
    }

    private static List<double[]> rebuild(Map<Long, Long> came, long goalKey,
                                          double ax, double ay, double az, double bx, double by, double bz,
                                          Window w) {
        List<double[]> rev = new ArrayList<>();
        long k = goalKey;
        while (true) {
            int gx = (int) (k >> 32);
            int gz = (int) k;
            double y = w.y(gx, gz, by);
            rev.add(new double[]{w.wx(gx), Double.isNaN(y) ? by : y, w.wx(gz)});
            Long p = came.get(k);
            if (p == null) {
                break;
            }
            k = p;
        }
        List<double[]> out = new ArrayList<>();
        out.add(new double[]{ax, ay, az});
        for (int i = rev.size() - 1; i >= 0; i--) {
            out.add(rev.get(i));
        }
        out.add(new double[]{bx, by, bz});
        return out;
    }

    /** 直线化简：把「直接走过去也不撞」的中间点删掉，剩下的就是街角。 */
    private static List<double[]> simplify(ClientLevel level, List<double[]> pts, Profile profile) {
        if (pts.size() <= 2) {
            return pts;
        }
        List<double[]> out = new ArrayList<>();
        out.add(pts.get(0));
        int i = 0;
        while (i < pts.size() - 1) {
            int best = i + 1;
            int limit = Math.min(pts.size() - 1, i + 48);
            for (int j = limit; j > i + 1; j--) {
                if (clearLine(level, pts.get(i), pts.get(j), profile)) {
                    best = j;
                    break;
                }
            }
            out.add(pts.get(best));
            i = best;
        }
        return out;
    }

    /** 从 a 直接走到/开到 b 行不行（逐格检查占地与台阶）。 */
    static boolean clearLine(ClientLevel level, double[] a, double[] b, Profile profile) {
        double dx = b[0] - a[0];
        double dz = b[2] - a[2];
        double dist = Math.hypot(dx, dz);
        // 0.5 格一采：1 格一采时，斜线只是「擦」过一个格子 0.2 格，两个采样点都落在格子外，
        // 于是判定为通、走过去却卡在墙角。密度翻倍就能抓住这种擦角。
        int steps = (int) Math.ceil(dist * 2.0);
        if (steps <= 0) {
            return true;
        }
        double y = a[1];
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            double xf = a[0] + dx * t;
            double zf = a[2] + dz * t;
            // 取整会把「切楼角」四舍五入掉：对角线从楼角擦过去，
            // round 落在楼外、floor 落在楼内。两个格子都查才算干净。
            double ny = nodeY(level, (int) Math.floor(xf), (int) Math.floor(zf), y, profile);
            if (Double.isNaN(ny)) {
                ny = nodeY(level, (int) Math.round(xf), (int) Math.round(zf), y, profile);
            }
            if (Double.isNaN(ny) || Math.abs(ny - y) > profile.stepUp + 1e-6) {
                return false;
            }
            y = ny;
        }
        return true;
    }

    /**
     * 按固定间隔重采样，但**每个化简后的拐点都强制保留**。
     * 早先按整条线统一重采样会在拐角处切角——正好从楼角上抄过去，路网就白做了。
     */
    static List<double[]> resample(List<double[]> pts, double every) {
        List<double[]> out = new ArrayList<>();
        if (pts.isEmpty()) {
            return out;
        }
        out.add(pts.get(0));
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i);
            double[] b = pts.get(i + 1);
            double seg = Math.hypot(b[0] - a[0], b[2] - a[2]);
            if (seg < 1e-6) {
                continue;
            }
            int n = (int) Math.floor(seg / every);
            for (int k = 1; k <= n; k++) {
                double t = (double) k * every / seg;
                out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
                        a[2] + (b[2] - a[2]) * t});
            }
            out.add(new double[]{b[0], b[1], b[2]}); // 拐点必留
        }
        return out;
    }

    private static double polyLength(List<double[]> pts) {
        double d = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i);
            double[] b = pts.get(i + 1);
            d += Math.hypot(b[0] - a[0], b[2] - a[2]);
        }
        return d;
    }

    private static final class Entry {
        final double f;
        final long key;
        final int gx, gz;
        final double y;
        final double g;

        Entry(long key, double f, int gx, int gz, double y, double g) {
            this.key = key;
            this.f = f;
            this.gx = gx;
            this.gz = gz;
            this.y = y;
            this.g = g;
        }
    }
}
