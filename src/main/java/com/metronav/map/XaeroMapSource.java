package com.metronav.map;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Loads Xaero's World Map data as the map backdrop. Two sources are supported:
 *  - the server save format: <x>_<z>.zip (region.xaero) — multiplayer / converted singleplayer;
 *  - the singleplayer cache: <x>_<z>.xwmc (cache.xaero) — the whole explored world.
 * All known regions are enumerated and loaded up-front so panning never shows "loading".
 * Falls back to null (built-in renderer) when Xaero or its files are unavailable.
 */
public final class XaeroMapSource {
    private final List<Path> mapDirs;
    private final List<Path> cacheDirs;
    private final List<long[]> allRegions = new ArrayList<>();
    private final Map<Long, int[]> cache = new LinkedHashMap<Long, int[]>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
            return size() > 128;
        }
    };
    private final ExecutorService exec = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "metronav-xaero");
        t.setDaemon(true);
        return t;
    });

    private volatile int readyX = Integer.MIN_VALUE;
    private volatile int readyZ;
    private volatile int[] readyPixels;
    private final Set<Long> queued = java.util.Collections.synchronizedSet(new HashSet<>());
    private volatile boolean loadedAll = false;

    private XaeroMapSource(List<Path> mapDirs, List<Path> cacheDirs, List<long[]> allRegions) {
        this.mapDirs = mapDirs;
        this.cacheDirs = cacheDirs;
        this.allRegions.addAll(allRegions);
    }

    public int regionCount() {
        return allRegions.size();
    }

    public static XaeroMapSource detect(Minecraft mc) {
        try {
            if (!FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
                return null;
            }
            Path root = xaeroRoot(gameDirectory(mc));
            if (root == null) {
                return null;
            }
            String dim = dimensionFolder(mc.level == null ? null : mc.level.dimension().location());

            List<Path> mapDirs = new ArrayList<>();
            List<Path> cacheDirs = new ArrayList<>();
            Path subworld = null;
            if (mc.getSingleplayerServer() != null) {
                subworld = root.resolve(mc.getSingleplayerServer().getWorldData().getLevelName());
            } else if (mc.getCurrentServer() != null) {
                subworld = root.resolve("Multiplayer_" + mc.getCurrentServer().ip);
            }
            if (subworld == null || !Files.isDirectory(subworld)) {
                subworld = bestSubworld(root, mc.getSingleplayerServer() != null ? null : "Multiplayer_");
            }
            if (subworld != null) {
                Path dimDir = subworld.resolve(dim);
                if (!Files.isDirectory(dimDir)) {
                    dimDir = subworld.resolve("null");
                }
                if (Files.isDirectory(dimDir)) {
                    mapDirs = allDirsWith(dimDir, "*_*.zip");
                    cacheDirs = allDirsWith(dimDir, "*_*.xwmc");
                }
            }
            // Fallback: search the whole Xaero root recursively (handles unknown layouts / launchers).
            if (mapDirs.isEmpty() && cacheDirs.isEmpty()) {
                mapDirs = allDirsWith(root, "*_*.zip");
                cacheDirs = allDirsWith(root, "*_*.xwmc");
            }
            if (mapDirs.isEmpty() && cacheDirs.isEmpty()) {
                return null;
            }

            List<long[]> regions = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (Path d : cacheDirs) {
                enumerateRegions(d, "*_*.xwmc", regions, seen);
            }
            for (Path d : mapDirs) {
                enumerateRegions(d, "*_*.zip", regions, seen);
            }
            if (regions.isEmpty()) {
                return null;
            }
            return new XaeroMapSource(mapDirs, cacheDirs, regions);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Path bestSubworld(Path root, String prefix) {
        Path best = null;
        long bestCount = -1;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                String n = p.getFileName().toString();
                if (prefix != null && !n.startsWith(prefix)) {
                    continue;
                }
                long c = countRecursive(p, "xwmc") + countRecursive(p, "zip");
                if (c > bestCount) {
                    bestCount = c;
                    best = p;
                }
            }
        } catch (Throwable t) {
        }
        return best;
    }

    private static long countRecursive(Path dir, String ext) {
        try (Stream<Path> s = Files.walk(dir, 6)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("." + ext))
                    .count();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Returns all directories under root that contain files matching glob, sorted by count descending. */
    private static List<Path> allDirsWith(Path root, String glob) {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root, 6)) {
            s.filter(Files::isDirectory).forEach(d -> {
                if (countIn(d, glob) > 0) {
                    dirs.add(d);
                }
            });
        } catch (Throwable t) {
        }
        dirs.sort((a, b) -> Long.compare(countIn(b, glob), countIn(a, glob)));
        return dirs;
    }

    private static long countIn(Path dir, String glob) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            long n = 0;
            for (Path p : ds) {
                n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void enumerateRegions(Path dir, String glob, List<long[]> out, Set<Long> seen) {
        if (dir == null) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                int us = name.indexOf('_');
                int dot = name.lastIndexOf('.');
                if (us <= 0 || dot <= us) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(name.substring(0, us));
                    int z = Integer.parseInt(name.substring(us + 1, dot));
                    if (seen.add(key(x, z))) {
                        out.add(new long[]{x, z});
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable t) {
        }
    }

    private static Path gameDirectory(Minecraft mc) {
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        try {
            candidates.add(java.nio.file.Paths.get(System.getProperty("user.dir")));
        } catch (Throwable ignored) {
        }
        try {
            candidates.add(FabricLoader.getInstance().getGameDir());
        } catch (Throwable ignored) {
        }
        try {
            Path c = FabricLoader.getInstance().getConfigDir();
            if (c != null && c.getParent() != null) {
                candidates.add(c.getParent());
            }
        } catch (Throwable ignored) {
        }
        // Pick the candidate that looks like a game directory (has Xaero data or a mods folder),
        // which handles version isolation in launchers like HMCL / PCL2 / BakaXL.
        for (Path p : candidates) {
            if (p == null) {
                continue;
            }
            if (Files.isDirectory(p.resolve("XaeroWorldMap"))
                    || Files.isDirectory(p.resolve("xaero"))
                    || Files.isDirectory(p.resolve("mods"))) {
                return p;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** Finds the Xaero world-map data root under a game directory, supporting multiple layouts. */
    private static Path xaeroRoot(Path gameDir) {
        if (gameDir == null) {
            return null;
        }
        String[] names = {
                "XaeroWorldMap",
                "xaero/world-map",
                "xaero/worldmap",
                "xaero",
                "config/xaero/world-map"
        };
        for (String n : names) {
            Path p = gameDir.resolve(n);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    private static String dimensionFolder(ResourceLocation dim) {
        if (dim == null) {
            return "null";
        }
        String s = dim.toString();
        if (s.equals("minecraft:overworld")) {
            return "null";
        }
        if (s.equals("minecraft:the_nether")) {
            return "DIM-1";
        }
        if (s.equals("minecraft:the_end")) {
            return "DIM1";
        }
        return s.replace(':', '$');
    }

    public void request(int regionX, int regionZ) {
        if (!loadedAll) {
            loadedAll = true;
            // Load every known region up-front so the whole explored map is available.
            for (long[] k : allRegions) {
                enqueue((int) k[0], (int) k[1]);
            }
        }
        int[] hit = cache.get(key(regionX, regionZ));
        if (hit != null) {
            readyPixels = hit;
            readyX = regionX;
            readyZ = regionZ;
            return;
        }
        // Safety net: also enqueue the neighbourhood in case new regions appeared.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                enqueue(regionX + dx, regionZ + dz);
            }
        }
    }

    private void enqueue(final int rx, final int rz) {
        final long k = key(rx, rz);
        if (cache.containsKey(k) || !queued.add(k)) {
            return;
        }
        final List<Path> mds = this.mapDirs;
        final List<Path> cds = this.cacheDirs;
        exec.submit(() -> {
            int[] px = null;
            // Prefer the native cache (.xwmc, newest first), then server-format zips.
            for (Path cd : cds) {
                px = XaeroRegionReader.readCacheRegion(cd.resolve(rx + "_" + rz + ".xwmc"));
                if (px != null) {
                    break;
                }
            }
            if (px == null) {
                for (Path md : mds) {
                    px = XaeroRegionReader.readRegion(md.resolve(rx + "_" + rz + ".zip"));
                    if (px != null) {
                        break;
                    }
                }
            }
            if (px == null) {
                px = new int[0];
            }
            synchronized (cache) {
                cache.put(k, px);
            }
            readyPixels = px;
            readyX = rx;
            readyZ = rz;
            queued.remove(k);
        });
    }

    public int[] pollReady(int regionX, int regionZ) {
        if (readyX == regionX && readyZ == regionZ && readyPixels != null) {
            return readyPixels.length > 0 ? readyPixels : null;
        }
        return null;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
