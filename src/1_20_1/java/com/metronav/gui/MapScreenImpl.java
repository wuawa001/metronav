package com.metronav.gui;

public class MapScreenImpl extends MapScreen {

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        applyScroll(amount);
        return true;
    }
}
