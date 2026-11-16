package com.metronav.nav;

/**
 * 结构化「导航读出台」：先把 HUD 要显示的东西算成一个不可变快照，渲染层只负责画。
 *
 * <p>思路来自 HowToGo 的 NavHudReadout：状态（已到达 / 乘车中 / 偏离 / 指路）是互斥的枚举，
 * 文案字段由状态机一次算完。这样做的好处是：渲染代码里没有任何 if 判断业务，
 * 而且 headless 测试可以直接断言字段，不用去解析渲染出来的字符串。
 */
public final class NavReadout {
    public enum State {
        /** 已到达目的地。 */
        ARRIVED,
        /** 正在乘坐地铁：给上车 / 下一站 / 准备下车 / 换乘的提示，而不是「朝这个点走」。 */
        TRANSIT,
        /** 偏离路线：玩家明显走到线路外面了。 */
        OFF_ROUTE,
        /** 普通指路（步行段）。 */
        STEP
    }

    public final State state;
    /** 顶栏："前往 市政府" */
    public final String header;
    /** 当前这段的线路名（"步行" / "12号线"），空串表示不显示这一行。 */
    public final String lineName;
    public final int lineColor;
    /** 主要提示：乘车提示 / 步行段要做什么 / 偏离警告。 */
    public final String instruction;
    /** 剩余距离文案："1.21 公里"。 */
    public final String distanceLabel;
    /** 距下一个「有意义的目标」（车站、换乘站、目的地）还有多远；普通路点时为 null。 */
    public final String nextLabel;
    /** "约 8 分钟"。 */
    public final String etaLabel;
    /** 0…1，已走比例：用路线链长算，和 HUD 上的剩余距离同源，不会互相矛盾。 */
    public final double progress;
    /** 副提示（换乘、偏离时指向哪里），没有就是 null。 */
    public final String hint;

    private NavReadout(State state, String header, String lineName, int lineColor,
                       String instruction, String distanceLabel, String nextLabel,
                       String etaLabel, double progress, String hint) {
        this.state = state;
        this.header = header;
        this.lineName = lineName == null ? "" : lineName;
        this.lineColor = lineColor;
        this.instruction = instruction == null ? "" : instruction;
        this.distanceLabel = distanceLabel == null ? "" : distanceLabel;
        this.nextLabel = nextLabel;
        this.etaLabel = etaLabel == null ? "" : etaLabel;
        this.progress = progress;
        this.hint = hint;
    }

    /** 每帧生成一份；session 不活跃时返回 null。 */
    public static NavReadout of(NavigationSession session, double px, double py, double pz) {
        if (session == null || !session.isActive()) {
            return null;
        }
        String header = "前往 " + session.destinationName();
        if (session.isArrived()) {
            return new NavReadout(State.ARRIVED, header, "", 0,
                    "✔ " + session.destinationName() + " · 已到达目的地",
                    "", null, "", 1.0, null);
        }

        NavigationSession.Waypoint target = session.currentTarget();
        if (target == null) {
            return null;
        }

        double remaining = session.remainingDistance(px, pz);
        double chain = session.chainLength();
        double progress = chain > 1e-6
                ? Math.max(0.0, Math.min(1.0, 1.0 - remaining / chain))
                : 0.0;
        String distanceLabel = NavText.distance(remaining);
        String etaLabel = "约 " + NavText.duration(session.remainingSeconds(px, py, pz));

        // 距离「有意义的目标」：走进车站、下一站、最终目的地。
        // 每 12 格一个的路点不显示，否则 HUD 会一直是「还有 8 米」这种废话。
        String nextLabel = null;
        if (target.isMetro()) {
            nextLabel = "距 " + NavText.station(target.title) + " " + NavText.distance(target.distanceTo(px, pz));
        } else {
            int stationIdx = session.upcomingStationIndex();
            if (stationIdx > session.currentIndex()) {
                NavigationSession.Waypoint st = session.waypointAt(stationIdx);
                if (st != null) {
                    nextLabel = "距 " + NavText.station(st.title) + " "
                            + NavText.distance(session.distanceAhead(stationIdx, px, pz));
                }
            }
        }

        if (session.offRoute()) {
            // 重规划过的就明说，不然玩家会以为是模组傻掉了、一直让他往回走
            boolean replanned = session.replannedRecently();
            return new NavReadout(State.OFF_ROUTE, header, target.lineName, target.lineColor,
                    replanned ? "⚠ 已偏离路线，已按当前位置重新规划" : "⚠ 已偏离路线，正在指回路线",
                    distanceLabel, nextLabel, etaLabel, progress,
                    replanned ? "路线从你脚下重新算了，跟着箭头走新的方向"
                              : "最近的路线点在你后方，跟着箭头走就好");
        }

        if (target.isMetro()) {
            NavigationSession.TransitCue cue = session.transitCue(px, py, pz);
            String instruction = cue != null ? cue.text : target.action;
            String hint = cue != null ? cue.hint : null;
            return new NavReadout(State.TRANSIT, header, target.lineName, target.lineColor,
                    instruction, distanceLabel, nextLabel, etaLabel, progress, hint);
        }

        String hint = session.upcomingTransfer() != null
                ? "下一站换乘 " + session.upcomingTransfer()
                : null;
        return new NavReadout(State.STEP, header, target.lineName, target.lineColor,
                target.action, distanceLabel, nextLabel, etaLabel, progress, hint);
    }

    public boolean arrived() {
        return state == State.ARRIVED;
    }
}
