package com.metronav.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a single Xaero's World Map region (a .zip containing "region.xaero",
 * 512x512 blocks, 1 px = 1 block) into an ABGR-packed RGBA array.
 * Format ported from the open-source XaerosMapFormat reference reader.
 */
public final class XaeroRegionReader {
    private XaeroRegionReader() {}

    public static final int SIZE = 512;

    /** Returns an int[] of size SIZE*SIZE in ABGR (NativeImage.setPixelRGBA) order, or null. */
    public static int[] readRegion(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zip.getEntry("region.xaero");
            if (entry == null) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(zip.getInputStream(entry))) {
                return parse(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reads a singleplayer cache file (<x>_<z>.xwmc, a zip containing "cache.xaero").
     * The preamble (tile metadata + biome palette) varies between Xaero forks, so we
     * scan for the first tile's signature and decode the 64x64 RGBA tiles from there.
     */
    public static int[] readCacheRegion(Path xwmcPath) {
        try (ZipFile zip = new ZipFile(xwmcPath.toFile())) {
            ZipEntry entry = zip.getEntry("cache.xaero");
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return parseCache(readAll(in));
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static int[] parseCache(byte[] data) {
        int start = findTileStart(data);
        if (start < 0) {
            return null;
        }
        int fullVersion = readIntBE(data, 0);
        int major = (fullVersion >> 16) & 0xFFFF;
        int heightBytes = (major == 0 ? 586 : 1024) * 8;

        int[] rgba = new int[SIZE * SIZE];
        byte[] color = new byte[16384];
        int pos = start;
        while (true) {
            if (pos >= data.length) {
                break;
            }
            int coord = data[pos] & 0xFF;
            if (coord == 255) {
                break;
            }
            int tileX = (coord >> 4) & 0x0F;
            int tileZ = coord & 0x0F;
            if (tileX >= 8 || tileZ >= 8) {
                break;
            }
            pos += 1; // coord
            pos += 1; // compressed flag
            pos += 4; // color buffer format
            int length = readIntBE(data, pos);
            pos += 4;
            if (length < 0 || pos + length > data.length) {
                break;
            }
            boolean hasColor = length == 16384;
            if (hasColor) {
                System.arraycopy(data, pos, color, 0, 16384);
            }
            pos += length;
            pos += 1; // bufferHasLight
            pos += heightBytes; // heights
            pos += heightBytes; // top heights
            int bps = readIntBE(data, pos);
            pos += 4;
            if (bps > 0) {
                for (int i = 0; i < bps; i++) {
                    int v = readIntBE(data, pos);
                    pos += 4;
                    if (v != -1) {
                        pos += 2;
                    }
                }
                int flag = data[pos] & 0xFF;
                pos += 1;
                if (flag == 1) {
                    pos += 1024 * 8;
                }
            }
            if (hasColor) {
                for (int pz = 0; pz < 64; pz++) {
                    for (int px = 0; px < 64; px++) {
                        int o = (pz * 64 + px) * 4;
                        // Xaero packs blue<<24|green<<16|red<<8|alpha into a little-endian
                        // direct buffer, so the byte order is: alpha, red, green, blue.
                        int r = color[o + 1] & 0xFF;
                        int g = color[o + 2] & 0xFF;
                        int b = color[o + 3] & 0xFF;
                        int gx = tileX * 64 + px;
                        int gz = tileZ * 64 + pz;
                        if (gx < SIZE && gz < SIZE) {
                            rgba[gz * SIZE + gx] = (255 << 24) | (b << 16) | (g << 8) | r;
                        }
                    }
                }
            }
        }
        return rgba;
    }

    /** Finds the first tile: a valid coord (0..0x77) followed by a compressed flag and an RGBA color buffer. */
    private static int findTileStart(byte[] data) {
        for (int i = 0; i + 10 < data.length; i++) {
            int coord = data[i] & 0xFF;
            if (coord > 0x77 || (coord & 0x0F) > 7) {
                continue;
            }
            int compressed = data[i + 1] & 0xFF;
            if (compressed > 1) {
                continue;
            }
            int fmt = readIntBE(data, i + 2);
            if (fmt != 32856 && fmt != 32853 && fmt != 32857) {
                continue;
            }
            if (readIntBE(data, i + 6) == 16384) {
                return i;
            }
        }
        return -1;
    }

    private static int readIntBE(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    private static void skipLongs(DataInputStream in, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            in.readLong();
        }
    }

    private static void skipBytes(DataInputStream in, int count, byte[] buf) throws Exception {
        int remaining = count;
        while (remaining > 0) {
            int n = Math.min(remaining, buf.length);
            in.readFully(buf, 0, n);
            remaining -= n;
        }
    }

    private static int[] parse(DataInputStream in) throws Exception {
        int[] rgba = new int[SIZE * SIZE];
        List<Block> statePalette = new ArrayList<>();

        int major = 0;
        int minor = 0;
        boolean is115not114 = false;
        int first = in.readUnsignedByte();
        boolean hasFullVersion = first == 255;
        if (hasFullVersion) {
            major = in.readShort();
            minor = in.readShort();
            if (major == 2 && minor >= 5) {
                is115not114 = in.readUnsignedByte() == 1;
            }
        }
        boolean usesColorTypes = minor < 5 || (major <= 2 && !is115not114);

        boolean firstTilePending = !hasFullVersion;
        int pendingTileCoord = first;
        while (true) {
            int coord;
            if (firstTilePending) {
                coord = pendingTileCoord;
                firstTilePending = false;
            } else {
                try {
                    coord = in.readUnsignedByte();
                } catch (EOFException eof) {
                    break;
                }
            }
            int tileZ = coord & 0x0F;
            int tileX = (coord >> 4) & 0x0F;
            for (int cx = 0; cx < 4; cx++) {
                for (int cz = 0; cz < 4; cz++) {
                    int firstParams = in.readInt();
                    if (firstParams == -1) {
                        continue; // empty chunk
                    }
                    for (int px = 0; px < 16; px++) {
                        for (int pz = 0; pz < 16; pz++) {
                            int params;
                            if (pz == 0 && px == 0) {
                                params = firstParams;
                            } else {
                                params = in.readInt();
                            }
                            BitReader br = new BitReader(params);
                            boolean isNotGrass = br.get(1) == 1;
                            boolean hasOverlays = br.get(1) == 1;
                            int colorType = br.get(2);
                            br.get(1); // hasSlope (minor==2) or skipped
                            br.get(1); // ignored
                            boolean heightInParameters = br.get(1) == 0;
                            br.skipToNextByte();
                            br.get(4); // light
                            int height;
                            if (heightInParameters) {
                                height = br.get(8);
                            } else {
                                br.get(8);
                                height = 0;
                            }
                            boolean hasBiome = br.get(1) == 1;
                            boolean newStatePaletteEntry = br.get(1) == 1;
                            boolean newBiomePaletteEntry = br.get(1) == 1;
                            boolean biomeAsInt = br.get(1) == 1;
                            boolean topHeightAndHeightDontMatch = minor >= 4 && br.get(1) == 1;
                            if (heightInParameters) {
                                height |= br.get(4) << 8;
                                height &= 0x0FFF;
                                if ((height & 0x0800) != 0) {
                                    height |= 0xF000;
                                }
                            }

                            Block block;
                            if (isNotGrass) {
                                if (major == 0) {
                                    in.readInt();
                                    block = Blocks.AIR;
                                } else if (newStatePaletteEntry) {
                                    block = readPaletteEntry(in);
                                    statePalette.add(block);
                                } else {
                                    int idx = in.readInt();
                                    block = idx >= 0 && idx < statePalette.size() ? statePalette.get(idx) : Blocks.AIR;
                                }
                            } else {
                                block = Blocks.GRASS_BLOCK;
                            }
                            if (!heightInParameters) {
                                height = in.readUnsignedByte();
                            }
                            if (topHeightAndHeightDontMatch) {
                                in.readUnsignedByte();
                            }
                            if (hasOverlays) {
                                int size = in.readUnsignedByte();
                                for (int o = 0; o < size; o++) {
                                    int op = in.readInt();
                                    BitReader obr = new BitReader(op);
                                    boolean isWater = obr.get(1) == 0;
                                    boolean legacyOpacity = obr.get(1) == 1;
                                    boolean customColor = obr.get(1) == 1;
                                    boolean hasOpacity = obr.get(1) == 1;
                                    obr.get(4); // light
                                    int ocType = usesColorTypes ? obr.get(2) : 0;
                                    boolean newOverlayState = obr.get(1) == 1;
                                    if (minor >= 8) {
                                        obr.get(4); // opacity
                                    }
                                    if (!isWater) {
                                        if (major == 0) {
                                            in.readInt();
                                        } else if (newOverlayState) {
                                            statePalette.add(readPaletteEntry(in));
                                        } else {
                                            in.readInt();
                                        }
                                    }
                                    if (minor < 1 && legacyOpacity) {
                                        in.readInt();
                                    }
                                    if (ocType == 3 || customColor) {
                                        in.readInt();
                                    }
                                    if (minor < 8 && hasOpacity) {
                                        in.readInt();
                                    }
                                }
                            }
                            if (colorType == 3) {
                                in.readInt();
                            }
                            if ((colorType != 0 && colorType != 3) || hasBiome) {
                                if (major < 4) {
                                    int bb = in.readUnsignedByte();
                                    if (minor >= 3 && bb >= 255) {
                                        in.readInt();
                                    }
                                } else if (newBiomePaletteEntry) {
                                    if (biomeAsInt) {
                                        in.readInt();
                                    } else {
                                        in.readUTF();
                                    }
                                } else {
                                    in.readInt();
                                }
                            }

                            int gx = tileX * 64 + cx * 16 + px;
                            int gz = tileZ * 64 + cz * 16 + pz;
                            rgba[gz * SIZE + gx] = colorOf(block);
                        }
                    }
                    if (minor >= 4) {
                        in.readByte(); // chunk interpretation version
                    }
                    if (minor >= 6) {
                        in.readInt(); // cave start
                        if (minor >= 7) {
                            in.readByte(); // cave depth
                        }
                    }
                }
            }
        }
        return rgba;
    }

    private static Block readPaletteEntry(DataInputStream in) throws Exception {
        CompoundTag tag = NbtIo.read(in);
        if (tag == null) {
            return Blocks.AIR;
        }
        String name = tag.getString("Name");
        ResourceLocation loc = ResourceLocation.tryParse(name);
        if (loc == null) {
            return Blocks.AIR;
        }
        return BuiltInRegistries.BLOCK.getOptional(loc).orElse(Blocks.AIR);
    }

    private static int colorOf(Block block) {
        try {
            int col;
            if (block == null || block == Blocks.AIR) {
                col = 0;
            } else {
                MapColor mc = block.defaultBlockState().getMapColor(null, BlockPos.ZERO);
                col = mc == null ? 0x7F7F7F : mc.col;
            }
            int r = (col >> 16) & 0xFF;
            int g = (col >> 8) & 0xFF;
            int b = col & 0xFF;
            return (255 << 24) | (b << 16) | (g << 8) | r; // ABGR
        } catch (Throwable t) {
            return (255 << 24) | (0x7F << 16) | (0x7F << 8) | 0x7F;
        }
    }

    /** Reads bits from an int LSB-first, matching Xaero's BitView. */
    private static final class BitReader {
        private final int data;
        private int position;

        BitReader(int data) {
            this.data = data;
        }

        int get(int n) {
            int v = (data >>> position) & ((1 << n) - 1);
            position += n;
            return v;
        }

        void skipToNextByte() {
            position = ((position >> 3) + 1) << 3;
        }
    }
}
