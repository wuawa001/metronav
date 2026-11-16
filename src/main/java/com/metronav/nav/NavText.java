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

    /** 车站名补「站」字：MTR 里的名字有「市政府」也有「市政府站」，统一成后者。 */
    public static String station(String name) {
        if (name == null || name.isEmpty()) {
            return "车站";
        }
        return name.endsWith("站") ? name : name + "站";
    }

    /** 距离文案：不到 1 公里用整数米，超过用两位小数公里（避免 HUD 出现 4783 这种数字）。 */
    public static String distance(double blocks) {
        if (Double.isNaN(blocks) || blocks < 0) {
            return "--";
        }
        if (blocks >= 1000.0) {
            return String.format(java.util.Locale.ROOT, "%.2f 公里", blocks / 1000.0);
        }
        return Math.round(blocks) + " 米";
    }

    /** 时长文案：不足 1 分钟按 1 分钟（HUD 上写「约 0 分钟」没有意义）。 */
    public static String duration(double seconds) {
        if (Double.isNaN(seconds) || seconds <= 0) {
            return "不到 1 分钟";
        }
        long total = Math.max(0L, Math.round(seconds));
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        if (h > 0L) {
            return m > 0L ? h + " 小时 " + m + " 分钟" : h + " 小时";
        }
        return Math.max(1L, m) + " 分钟";
    }
}
