package com.metronav;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MetroNavConfig INSTANCE = new MetroNavConfig();

    private MetroNavConfig() {}

    public static MetroNavConfig get() {
        return INSTANCE;
    }

    public static void load() {
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve("metronav.json");
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
            Path path = FabricLoader.getInstance().getConfigDir().resolve("metronav.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
