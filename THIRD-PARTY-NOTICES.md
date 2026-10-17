# 第三方声明 (Third-Party Notices)

本模组（MetroNav）引用了以下第三方项目 / 格式。遵循其许可证要求，特此声明。

## 1. XaerosMapFormat（Xaero 地图格式参考实现）

- 项目：<https://github.com/DanDucky/XaerosMapFormat>
- 许可证：**GNU General Public License v3.0 (GPL-3.0)**
- 用途：`XaeroRegionReader` 中服务器地图格式（`region.xaero`）的解析逻辑参考 / 移植自该项目。
- 说明：由于本模组包含该 GPL-3.0 项目的衍生代码，本模组整体以 **GPL-3.0** 发布（见 [LICENSE](LICENSE)）。

## 2. MTR（Minecraft Transit Railway）

- 项目：<https://github.com/jonafanho/Minecraft-Transit-Railway>
- 许可证：**MIT License**
- 用途：本模组仅在**编译期**引用 MTR 的 API（`modCompileOnly`），运行时通过反射读取 MTR 的线路 / 车站数据以实现地铁导航。
- 说明：**本模组不打包、不分发 MTR 的任何代码**，MTR 需由用户另行安装。

## 3. Xaero's World Map（Xaero 地图）

- 说明：本模组读取 Xaero WorldMap 的**数据文件**（单人 `.xwmc` 缓存、服务器区域 `.zip`）作为地图底图。`.xwmc` 缓存格式是通过反编译 Xaero 客户端逆向得出的（仅用于互操作性），**不包含、不分发 Xaero 的任何代码**。

## 4. Minecraft / Fabric

- 本模组是基于 Fabric Loader 的 Minecraft 客户端模组，与 Mojang / Microsoft 无关联。Minecraft 相关权利归 Mojang Studios / Microsoft 所有。
