package com.metronav.gui;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class DownloadPromptScreen extends Screen {
    private static final String MTR_URL = "https://modrinth.com/mod/minecraft-transit-railway";

    public DownloadPromptScreen() {
        super(Component.literal("需要 MTR 模组"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int x0 = cx - 120;
        Button dl = Button.builder(Component.literal("打开 MTR 下载页（Modrinth）"),
                        b -> Util.getPlatform().openUri(MTR_URL))
                .bounds(x0, this.height / 2, 240, 20).build();
        Button close = Button.builder(Component.literal("关闭"), b -> this.onClose())
                .bounds(x0, this.height / 2 + 26, 240, 20).build();
        addRenderableWidget(dl);
        addRenderableWidget(close);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, "未检测到 MTR 模组", cx, this.height / 2 - 42, 0xFFFFFF);
        g.drawCenteredString(this.font, "地铁导航需要 MTR（Minecraft Transit Railway）4.0.5", cx, this.height / 2 - 28, 0xBBBBBB);
        g.drawCenteredString(this.font, "下载后放入 mods 文件夹并重启游戏即可使用地铁导航", cx, this.height / 2 - 16, 0xBBBBBB);
        g.drawCenteredString(this.font, "（未安装 MTR 时仍可使用步行导航）", cx, this.height / 2 - 4, 0x888888);
    }
}
