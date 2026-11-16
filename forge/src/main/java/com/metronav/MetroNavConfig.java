package com.metronav;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MetroNavConfig {
    public boolean hudEnabled = true;
    public boolean pathLineEnabled = true;
    public boolean beaconEnabled = true;
    public double arrivalRadius = 40.0;
    public double walkArrivalRadius = 4.0;
    public double transferPenaltySeconds = 150.0;
    public double boardingPenaltySeconds = 45.0;
    public double dwellSeconds = 20.0;
    public double walkSpeedMps = 4.3;
    /** 地铁平均旅行速度（m/s）。MTR 的 TransportMode 默认只有 20 km/h ≈ 5.56 m/s，
     *  比步行(4.3 m/s)快不了多少，会让规划器永远选全程步行；本服 12号线 平均旅速 15 m/s。 */
    public double metroSpeedMps = 15.0;
    /** 判定「偏离路线」的横向阈值（格）；实际用的是 max(本值, 航点半径*6)。 */
    public double offRouteWarnBlocks = 24.0;
    /** 连续多少 tick 都偏出走廊才提示，避免正常绕路时闪警告。 */
    public int offRouteWarnTicks = 40;

    /** 自动识别步行路网：读世界方块撒点建图 + A*，不再走「两点一线」。
     *  关掉就退回旧的直线步行（用来对照/救急）。 */
    public boolean autoRoad = false;
    /** 撒点格距（格）。越小越贴墙、越慢：3 适合城镇，2 适合室内/窄巷。 */
    public int roadSpacing = 3;
    /** 两个站区相距这么近（水平格）就当可以步行换乘。
     *  很多服的换乘站是两块相邻但不相连的 station area（上下行分站、地上海下分站），
     *  数据里它们没有边，规划器就会当成「到不了」，退化成全程步行。 */
    public double transferMaxBlocks = 0.0;
    /** 站外换乘只允许在线路两端做。中途下车走过去会让规划器把「再坐一站」换成
     *  「走一段」，导航就会提前让你换乘（人还在前一站）。 */
    public boolean transferWalkAtTerminalOnly = true;
    /** 站坐标是否采信「站台两端的中点」。MTR 里没落盘的 Position 是 (0,0,0)，
     *  畸形长站台的中点也会跑偏，一旦采信就会「早一站 / 乱认站」，所以默认关闭：
     *  站坐标仍用站点区中心（旧行为），只有你确认服上的站台数据可信才打开。 */
    public boolean stationPointFromPlatform = false;
    /** 一次规划最多展开多少节点（性能上限，超了就退回直线）。 */
    public int roadMaxNodes = 30000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MetroNavConfig INSTANCE = new MetroNavConfig();

    private MetroNavConfig() {}

    public static MetroNavConfig get() {
        return INSTANCE;
    }

    public static void load() {
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve("metronav.json");
            if (Files.exists(path)) {
                MetroNavConfig loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), MetroNavConfig.class);
                if (loaded != null) {
                    INSTANCE = loaded;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve("metronav.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
