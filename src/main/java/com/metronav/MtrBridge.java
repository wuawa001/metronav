package com.metronav;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.TransportMode;
import org.mtr.mod.client.MinecraftClientData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MtrBridge {
    private MtrBridge() {}

    private static long lastRequestTime = 0;
    private static boolean expectingDashboardClose = false;

    public static boolean available() {
        try {
            return FabricLoader.getInstance().isModLoaded("mtr");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns the richest client-side railway data. MTR only sends full ROUTE data
     * through its dashboard "list_data" response, so prefer the dashboard instance
     * whenever it holds more routes than the render instance.
     */
    public static ClientData getClientData() {
        if (!available()) {
            return null;
        }
        try {
            MinecraftClientData inst = MinecraftClientData.getInstance();
            MinecraftClientData dash = MinecraftClientData.getDashboardInstance();
            long instRoutes = inst.routes.size();
            long dashRoutes = dash.routes.size();
            if (dashRoutes > instRoutes && dashRoutes > 0) {
                return dash;
            }
            if (instRoutes > 0) {
                return inst;
            }
            long instStations = inst.stations.size();
            long dashStations = dash.stations.size();
            return dashStations > instStations ? dash : inst;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Requests the full railway data (stations + platforms + ROUTES) from the server
     * using MTR's own dashboard packet. MTR will answer with its full list response
     * (written into the dashboard instance) and open its dashboard screen, which we
     * close automatically in {@link #tick}.
     */
    public static void requestFullData() {
        if (!available()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (now - lastRequestTime < 10000) {
                return;
            }
            MinecraftClientData dash = MinecraftClientData.getDashboardInstance();
            if (dash.routes.size() > 0 && dash.stations.size() > 0) {
                return;
            }
            lastRequestTime = now;
            Class<?> packetClass = Class.forName("org.mtr.mod.packet.PacketOpenDashboardScreen");
            Constructor<?> ctor = packetClass.getDeclaredConstructor(String.class, TransportMode.class);
            ctor.setAccessible(true);
            Object packet = ctor.newInstance("{}", TransportMode.TRAIN);

            Class<?> initClientClass = Class.forName("org.mtr.mod.InitClient");
            Field field = initClientClass.getDeclaredField("REGISTRY_CLIENT");
            field.setAccessible(true);
            Object registry = field.get(null);
            Method send = registry.getClass().getMethod("sendPacketToServer",
                    Class.forName("org.mtr.mapping.registry.PacketHandler"));
            send.invoke(registry, packet);
            expectingDashboardClose = true;
        } catch (Throwable ignored) {
        }
    }

    /** Closes MTR's dashboard screen if it was auto-opened by our data request. */
    public static void tick(Minecraft mc) {
        if (!expectingDashboardClose) {
            return;
        }
        if (mc.screen != null && mc.screen.getClass().getName().contains("DashboardScreen")) {
            mc.setScreen(null);
        }
        if (System.currentTimeMillis() - lastRequestTime > 5000) {
            expectingDashboardClose = false;
        }
    }
}
