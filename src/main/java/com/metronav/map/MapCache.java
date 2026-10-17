package com.metronav.map;

import com.metronav.nav.RailwayIndex;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-down map: samples chunk columns into a 512x512 texture on the render thread
 * (throttled budget per frame), caches per chunk, and stamps metro route lines
 * into a transparent overlay texture.
 */
public class MapCache {
    public static final int SIZE = 512;
    /** Number of Xaero regions per side composited into one texture. */
    public static final int GRID = 6;
    public static final int TEX_SIZE = GRID * SIZE;
    public static final ResourceLocation BASE_LOC = new ResourceLocation("metronav", "map_base");
    public static final ResourceLocation OVERLAY_LOC = new ResourceLocation("metronav", "map_overlay");

    private static final class ChunkSample {
        long time;
        final int[] rgba = new int[256];
    }

    private NativeImage base;
    private NativeImage overlay;
    private DynamicTexture baseTex;
    private DynamicTexture overlayTex;
    private boolean registered;
    private boolean baseDirty;
    private boolean overlayDirty;
    private int anchorX;
    private int anchorZ;
    private boolean regionSet;
    private final Map<Long, ChunkSample> cache = new HashMap<>();
    private final List<Long> pending = new ArrayList<>();
    private RailwayIndex index;
    private XaeroMapSource xaero;
    private int xaeroAppliedX = Integer.MIN_VALUE;
    private int xaeroAppliedZ;
    private final java.util.Set<Long> xaeroApplied = new java.util.HashSet<>();
    private int texSize = SIZE;

    public void setIndex(RailwayIndex index) {
        if (this.index == index) {
            return;
        }
        this.index = index;
        if (regionSet && overlay != null) {
            stampRoutes();
            overlayDirty = true;
        }
    }

    public void setXaero(XaeroMapSource source) {
        this.xaero = source;
        this.xaeroAppliedX = Integer.MIN_VALUE;
        this.xaeroApplied.clear();
        this.texSize = source != null ? TEX_SIZE : SIZE;
    }

    public int texSize() {
        return texSize;
    }

    public int regionX0() {
        return anchorX - texSize / 2;
    }

    public int regionZ0() {
        return anchorZ - texSize / 2;
    }

    public int baseTexture() {
        return baseTex != null ? baseTex.getId() : -1;
    }

    public boolean ready() {
        return registered;
    }

    public void updateRegion(ClientLevel level, int viewCX, int viewCZ) {
        if (xaero != null) {
            int rx = Math.floorDiv(viewCX, SIZE);
            int rz = Math.floorDiv(viewCZ, SIZE);
            boolean need = !regionSet;
            if (regionSet) {
                // Rebuild only when the view reaches the outer ring of the composite,
                // so dragging inside the composite stays cheap.
                int baseRX = Math.floorDiv(anchorX - texSize / 2, SIZE);
                int baseRZ = Math.floorDiv(anchorZ - texSize / 2, SIZE);
                need = rx <= baseRX || rx >= baseRX + GRID - 1
                        || rz <= baseRZ || rz >= baseRZ + GRID - 1;
            }
            if (need) {
                anchorX = rx * SIZE;
                anchorZ = rz * SIZE;
                regionSet = true;
                if (base == null) {
                    base = new NativeImage(NativeImage.Format.RGBA, texSize, texSize, false);
                    overlay = new NativeImage(NativeImage.Format.RGBA, texSize, texSize, false);
                }
                base.fillRect(0, 0, texSize, texSize, 0);
                overlay.fillRect(0, 0, texSize, texSize, 0);
                xaeroApplied.clear();
                stampRoutes();
                baseDirty = true;
                overlayDirty = true;
            }
            return;
        }
        if (!regionSet) {
            anchorX = viewCX;
            anchorZ = viewCZ;
            regionSet = true;
            base = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
            overlay = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
            overlay.fillRect(0, 0, texSize, texSize, 0);
            rebuildQueue();
            stampRoutes();
            baseDirty = true;
            overlayDirty = true;
            return;
        }
        if (Math.abs(viewCX - anchorX) > 128 || Math.abs(viewCZ - anchorZ) > 128) {
            anchorX = viewCX;
            anchorZ = viewCZ;
            base.fillRect(0, 0, texSize, texSize, 0);
            overlay.fillRect(0, 0, texSize, texSize, 0);
            rebuildQueue();
            stampRoutes();
            baseDirty = true;
            overlayDirty = true;
        }
    }

    public void update(Minecraft mc) {
        if (!regionSet || base == null) {
            return;
        }
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (xaero != null) {
            int baseRX = Math.floorDiv(regionX0(), SIZE);
            int baseRZ = Math.floorDiv(regionZ0(), SIZE);
            boolean anyNew = false;
            int budget = 4; // apply at most a few regions per frame to avoid lag spikes
            for (int gx = 0; gx < GRID && budget > 0; gx++) {
                for (int gz = 0; gz < GRID && budget > 0; gz++) {
                    int rrX = baseRX + gx;
                    int rrZ = baseRZ + gz;
                    long rk = key(rrX, rrZ);
                    if (xaeroApplied.contains(rk)) {
                        continue;
                    }
                    xaero.request(rrX, rrZ);
                    int[] px = xaero.pollReady(rrX, rrZ);
                    if (px == null) {
                        continue;
                    }
                    int offX = gx * SIZE;
                    int offZ = gz * SIZE;
                    for (int i = 0; i < SIZE * SIZE; i++) {
                        base.setPixelRGBA(offX + i % SIZE, offZ + i / SIZE, px[i]);
                    }
                    xaeroApplied.add(rk);
                    anyNew = true;
                    budget--;
                }
            }
            if (anyNew) {
                baseDirty = true;
            }
        } else {
            int budget = 2048; // columns per frame
            while (budget >= 256 && !pending.isEmpty()) {
                long k = pending.remove(0);
                int cx = cxOf(k);
                int cz = czOf(k);
                ChunkSample s = cache.get(k);
                if (s != null && now - s.time < 30000) {
                    writeSampleToBase(k, s.rgba);
                    continue;
                }
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    continue; // picked up again by the sliding refresh
                }
                if (s == null) {
                    s = new ChunkSample();
                    cache.put(k, s);
                }
                sampleChunk(level, cx, cz, s);
                s.time = now;
                writeSampleToBase(k, s.rgba);
                budget -= 256;
            }
            if (pending.isEmpty()) {
                int x0 = regionX0();
                int z0 = regionZ0();
                outer:
                for (int cx = x0 >> 4; cx <= (x0 + SIZE - 1) >> 4; cx++) {
                    for (int cz = z0 >> 4; cz <= (z0 + SIZE - 1) >> 4; cz++) {
                        long k = key(cx, cz);
                        ChunkSample s = cache.get(k);
                        if (s == null || now - s.time > 30000) {
                            pending.add(k);
                            if (pending.size() >= 64) {
                                break outer;
                            }
                        }
                    }
                }
            }
        }
        if (!registered) {
            baseTex = new DynamicTexture(base);
            overlayTex = new DynamicTexture(overlay);
            mc.getTextureManager().register(BASE_LOC, baseTex);
            mc.getTextureManager().register(OVERLAY_LOC, overlayTex);
            registered = true;
            baseDirty = false;
            overlayDirty = false;
            return;
        }
        if (baseDirty) {
            baseTex.upload();
            baseDirty = false;
        }
        if (overlayDirty) {
            overlayTex.upload();
            overlayDirty = false;
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static int cxOf(long k) {
        return (int) (k >> 32);
    }

    private static int czOf(long k) {
        return (int) (long) k;
    }

    private void rebuildQueue() {
        pending.clear();
        List<Long> keys = new ArrayList<>();
        int x0 = regionX0();
        int z0 = regionZ0();
        for (int cx = x0 >> 4; cx <= (x0 + SIZE - 1) >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= (z0 + SIZE - 1) >> 4; cz++) {
                keys.add(key(cx, cz));
            }
        }
        final int ax = anchorX;
        final int az = anchorZ;
        keys.sort((a, b) -> {
            double da = Math.pow((double) cxOf(a) * 16 + 8 - ax, 2) + Math.pow((double) czOf(a) * 16 + 8 - az, 2);
            double db = Math.pow((double) cxOf(b) * 16 + 8 - ax, 2) + Math.pow((double) czOf(b) * 16 + 8 - az, 2);
            return Double.compare(da, db);
        });
        pending.addAll(keys);
    }

    private void writeSampleToBase(long k, int[] rgba) {
        int x0 = (cxOf(k) << 4) - regionX0();
        int z0 = (czOf(k) << 4) - regionZ0();
        if (x0 < -16 || z0 < -16 || x0 >= SIZE || z0 >= SIZE) {
            return;
        }
        for (int z = 0; z < 16; z++) {
            int pz = z0 + z;
            if (pz < 0 || pz >= SIZE) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                int px = x0 + x;
                if (px < 0 || px >= SIZE) {
                    continue;
                }
                base.setPixelRGBA(px, pz, rgba[z * 16 + x]);
            }
        }
        baseDirty = true;
    }

    private void sampleChunk(ClientLevel level, int cx, int cz, ChunkSample out) {
        ChunkAccess chunk = level.getChunk(cx, cz);
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bx = cx << 4;
        int bz = cz << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int color = 0;
                // Heightmap sampling: O(1) per column instead of scanning 384 Y levels.
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                if (y >= minY) {
                    pos.set(bx + x, y, bz + z);
                    BlockState st = chunk.getBlockState(pos);
                    for (int d = 0; d <= 4 && st.isAir(); d++) {
                        pos.set(bx + x, y - d, bz + z);
                        st = chunk.getBlockState(pos);
                    }
                    if (!st.isAir()) {
                        if (st.is(Blocks.WATER)) {
                            color = pack(255, 0x3F, 0x6F, 0x76);
                        } else {
                            MapColor mapColor = st.getMapColor(level, pos);
                            int r = (mapColor.col >> 16) & 0xFF;
                            int g = (mapColor.col >> 8) & 0xFF;
                            int b = mapColor.col & 0xFF;
                            if (isTintable(st)) {
                                try {
                                    int t = Minecraft.getInstance().getBlockColors().getColor(st, level, pos, 0);
                                    r = (t >> 16) & 0xFF;
                                    g = (t >> 8) & 0xFF;
                                    b = t & 0xFF;
                                } catch (Throwable ignored) {
                                }
                            }
                            color = pack(255, r, g, b);
                        }
                    }
                }
                out.rgba[z * 16 + x] = color;
            }
        }
    }

    private static boolean isTintable(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN)
                || s.is(Blocks.LARGE_FERN) || s.is(Blocks.OAK_LEAVES) || s.is(Blocks.BIRCH_LEAVES)
                || s.is(Blocks.SPRUCE_LEAVES) || s.is(Blocks.JUNGLE_LEAVES) || s.is(Blocks.ACACIA_LEAVES)
                || s.is(Blocks.DARK_OAK_LEAVES) || s.is(Blocks.MANGROVE_LEAVES) || s.is(Blocks.VINE);
    }

    /** Packs ARGB into the ABGR int expected by NativeImage.setPixelRGBA. */
    private static int pack(int a, int r, int g, int b) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private void stampRoutes() {
        if (overlay == null) {
            return;
        }
        overlay.fillRect(0, 0, texSize, texSize, 0);
        if (index == null) {
            return;
        }
        int x0 = regionX0();
        int z0 = regionZ0();
        int count = 0;
        for (RailwayIndex.RouteNode rn : index.routes.values()) {
            if (count++ >= 100 || rn.stationIds.size() > 48) {
                continue;
            }
            int r = (rn.color >> 16) & 0xFF;
            int g = (rn.color >> 8) & 0xFF;
            int b = rn.color & 0xFF;
            int packed = pack(170, r, g, b);
            List<Long> ids = rn.stationIds;
            for (int i = 0; i + 1 < ids.size(); i++) {
                RailwayIndex.StationNode a = index.stations.get(ids.get(i));
                RailwayIndex.StationNode bn = index.stations.get(ids.get(i + 1));
                if (a != null && bn != null) {
                    stampLine(packed, a.x - x0, a.z - z0, bn.x - x0, bn.z - z0);
                }
            }
            if (rn.circular && ids.size() >= 2) {
                RailwayIndex.StationNode a = index.stations.get(ids.get(ids.size() - 1));
                RailwayIndex.StationNode bn = index.stations.get(ids.get(0));
                if (a != null && bn != null) {
                    stampLine(packed, a.x - x0, a.z - z0, bn.x - x0, bn.z - z0);
                }
            }
        }
    }

    private void stampLine(int packed, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        // Clip the segment to the overlay bounds so off-screen routes cost nothing.
        double t0 = 0.0;
        double t1 = 1.0;
        double[] clip = {t0, t1};
        if (!clipAxis(x1, dx, clip)) {
            return;
        }
        if (!clipAxis(z1, dz, clip)) {
            return;
        }
        t0 = clip[0];
        t1 = clip[1];
        double cx1 = x1 + dx * t0;
        double cz1 = z1 + dz * t0;
        double cx2 = x1 + dx * t1;
        double cz2 = z1 + dz * t1;
        int steps = (int) Math.ceil(Math.max(Math.abs(cx2 - cx1), Math.abs(cz2 - cz1)));
        if (steps <= 0) {
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int px = (int) Math.round(cx1 + (cx2 - cx1) * i / steps);
            int pz = (int) Math.round(cz1 + (cz2 - cz1) * i / steps);
            if (px >= 0 && pz >= 0 && px < texSize && pz < texSize) {
                overlay.setPixelRGBA(px, pz, packed);
            }
        }
    }

    /** Liang-Barsky clip along one axis against [0, texSize); returns false if fully outside. */
    private boolean clipAxis(double p, double d, double[] t) {
        if (d == 0) {
            return p >= 0 && p < texSize;
        }
        double tLo = (0 - p) / d;
        double tHi = (texSize - 1 - p) / d;
        if (d > 0) {
            t[0] = Math.max(t[0], tLo);
            t[1] = Math.min(t[1], tHi);
        } else {
            t[0] = Math.max(t[0], tHi);
            t[1] = Math.min(t[1], tLo);
        }
        return t[0] <= t[1];
    }
}
