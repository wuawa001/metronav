package com.metronav.nav;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class WalkPlanner {
    private WalkPlanner() {}

    /** Surface height at (x, z): topmost non-air block + 1; water surface when water on top. */
    public static double surfaceY(ClientLevel level, double x, double z, double fallback) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
            return fallback;
        }
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = maxY; y >= minY; y--) {
            pos.set(bx, y, bz);
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) {
                continue;
            }
            if (s.is(Blocks.WATER)) {
                return y + 0.6;
            }
            return y + 1.0;
        }
        return fallback;
    }

    /** Straight-line walking path with surface-height waypoints every ~12 blocks. */
    public static List<double[]> path(ClientLevel level, double ax, double ay, double az,
                                      double bx, double by, double bz) {
        List<double[]> pts = new ArrayList<>();
        double dx = bx - ax;
        double dz = bz - az;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5) {
            pts.add(new double[]{bx, by, bz});
            return pts;
        }
        int n = Math.max(1, (int) Math.ceil(dist / 12.0));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double x = ax + dx * t;
            double z = az + dz * t;
            double y = surfaceY(level, x, z, ay + (by - ay) * t);
            if (pts.isEmpty()) {
                pts.add(new double[]{ax, ay, az});
            }
            double[] last = pts.get(pts.size() - 1);
            if (Math.hypot(x - last[0], z - last[2]) >= 3.0) {
                pts.add(new double[]{x, y, z});
            }
        }
        double[] lp = pts.get(pts.size() - 1);
        if (Math.hypot(bx - lp[0], bz - lp[2]) > 0.5) {
            pts.add(new double[]{bx, by, bz});
        }
        return pts;
    }

    public static double pathLength(List<double[]> pts) {
        double d = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i);
            double[] b = pts.get(i + 1);
            d += Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]));
        }
        return d;
    }
}
