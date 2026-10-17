package com.metronav.gui;

public class MapScreenImpl extends MapScreen {

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        applyScroll(verticalAmount);
        return true;
    }
}
