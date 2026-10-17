# MetroNav 地铁导航

一个 **Fabric 客户端模组**，为 [MTR（Minecraft Transit Railway）](https://modrinth.com/mod/mtr) 提供"**地图点选导航**"：
在地图上点一个点，自动规划 **地铁 + 步行** 混合路线，并在屏幕 HUD 与 3D 世界内给出引导。

- **点选即导航**：像真实导航 App 一样，在地图上点一下就开始导航；
- **地铁 + 步行混合规划**：自动组合"步行 → 地铁 → 步行"的最优路线；
- **MTR 可选依赖**：没装 MTR 也能纯步行导航；
- **Xaero 地图底图**：优先读取 Xaero WorldMap 的已探索地图（含单人 `.xwmc` 缓存），否则用内置地形图。

> English: [README.md](README.md)

---

## 一、支持版本

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 / 1.20.4 |
| 模组加载器 | Fabric（Fabric Loader 0.15+） |
| 必需前置 | Fabric API（与 MC 版本对应） |
| 可选模组 | MTR 4.0.5（地铁导航）、Xaero WorldMap（地图底图） |
| Java | 17 及以上 |

> 按 MC 版本选择 jar：
> - `metronav-mc1.20.4-<版本>.jar` → Minecraft 1.20.4
> - `metronav-mc1.20.1-<版本>.jar` → Minecraft 1.20.1
>
> 本模组是**客户端模组**，放入你自己的 mods 文件夹即可，服务器无需安装。

## 二、安装

1. 安装对应 MC 版本的 **Fabric Loader**；
2. 安装 **Fabric API**（版本与 MC 匹配）；
3. （可选，需要地铁导航）安装 **MTR 4.0.5**；
4. （可选，需要 Xaero 地图底图）安装 **Xaero WorldMap**；
5. 将 jar 放入 `.minecraft/mods/` 并进入世界。

## 三、使用方法

### 1. 打开地图
按 **N 键**（可在 选项 → 按键设置 中修改）。

地图底图自动选择：
- 装有 Xaero WorldMap 且有地图数据 → 使用 **Xaero 已探索地图**；
- 否则 → 使用**内置地形图**（高度图采样）。

叠加显示：地铁线路（线路色）、车站标记、你的位置（白点）、目的地（红叉）。

### 2. 点选导航
- **左键点击地图**：立即设为目的地并自动开始导航；
  - 点车站附近自动吸附车站（走地铁）；
  - 点普通位置为"步行 → 地铁 → 步行"最优组合或纯步行；
- **拖拽** = 平移，**滚轮** = 缩放，**右键** = 取消导航；
- 顶部按钮：清除目的地、车站列表、刷新数据、回到玩家、关闭。

### 3. 跟随导航
- **左上角 HUD**：当前线路/步行、动作、方向箭头、剩余距离与预计时间；
- **换乘提示**：下一站就是换乘站时，HUD 额外提示"下一站换乘: X号线"；
- **快车识别**：线路代码含 `k` 或线路名含"快车"的，自动标注为"快车"；
- **3D 指引**：步行 = 青色地面指引线 + 小信标；地铁 = 线路色指引线 + 车站光柱；
- **到站判定**：进入车站占地范围即进入下一步，无需走到轨道/站台中心。

## 四、没有 MTR 时

- 不装 MTR 时**步行导航照常可用**（点地图任意位置即可）；
- 点击【车站列表】会弹出 MTR 下载页提示（一键打开浏览器）。

## 五、配置文件

`.minecraft/config/metronav.json`：

```json
{
  "hudEnabled": true,
  "pathLineEnabled": true,
  "beaconEnabled": true,
  "arrivalRadius": 40.0,
  "walkArrivalRadius": 4.0,
  "transferPenaltySeconds": 150.0,
  "boardingPenaltySeconds": 45.0,
  "dwellSeconds": 20.0,
  "walkSpeedMps": 4.3
}
```

## 六、从源码构建

环境：JDK 17、Gradle（已内置 wrapper 配置，使用 fabric-loom 1.9.2）。

1. 把 MTR 4.0.5 的 jar 放到 `libs/` 目录（`MTR-fabric-4.0.5+1.20.4.jar` 与 `MTR-fabric-4.0.5+1.20.1.jar`）；
2. 执行构建：
   ```bash
   gradle build -Pminecraft_version=1.20.4 -Pfabric_api_version=0.97.3+1.20.4 -Pmtr_jar=libs/MTR-fabric-4.0.5+1.20.4.jar
   gradle build -Pminecraft_version=1.20.1 -Pfabric_api_version=0.92.12+1.20.1 -Pmtr_jar=libs/MTR-fabric-4.0.5+1.20.1.jar
   ```
3. 产物在 `build/libs/` 下。

## 七、常见问题

- **线路显示 0**：稍等几秒自动向服务器请求线路；仍为 0 请确认 MTR 为 4.0.5 且服务器确有线路，点【刷新数据】重试。
- **地图有空白**：Xaero 未探索区域没有地形数据（Xaero 只缓存走过/生成过的区域）；已探索区域会自动加载。
- **短暂闪出 MTR 仪表盘**：拉取线路数据的正常现象，约 1 秒自动关闭。
- **步行指引为直线示意**：遇障碍自行绕行，HUD 箭头始终指向目标。

## 八、开源许可

**GNU General Public License v3.0 (GPL-3.0)** —— 详见 [LICENSE](LICENSE)。

> 本模组的 `region.xaero` 解析部分参考了 GPL-3.0 的 [XaerosMapFormat](https://github.com/DanDucky/XaerosMapFormat) 项目，故整体采用 GPL-3.0。
> 第三方组件与声明详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
