package com.metronav.nav;

public final class NavText {
    private NavText() {}

    /**
     * Normalizes a MTR route name for display:
     *  - drops the "||" suffix (the direction is queried separately from MTR data);
     *  - when the name contains "支线" (branch line), formats it as "X号线支线".
     */
    public static String lineName(String raw) {
        if (raw == null) {
            return "?";
        }
        String main = raw;
        String suffix = "";
        int idx = raw.indexOf("||");
        if (idx >= 0) {
            main = raw.substring(0, idx);
            suffix = raw.substring(idx + 2);
        }
        boolean branch = main.contains("支线") || suffix.contains("支线");
        if (branch) {
            int hx = main.indexOf("号线");
            String base = hx >= 0 ? main.substring(0, hx + 2) : main.replace("支线", "").trim();
            main = base + "支线";
        }
        return main;
    }

    /** Appends a "快车" tag when the route is an express service and the name lacks it. */
    public static String lineName(String raw, boolean express) {
        String n = lineName(raw);
        if (express && !n.contains("快车")) {
            n += "快车";
        }
        return n;
    }
}
