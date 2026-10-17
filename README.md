# MetroNav

<p align="center"><img src="logo.png" width="200" alt="MetroNav"></p>

A **client-side Fabric mod** that adds map-based navigation for [MTR (Minecraft Transit Railway)](https://modrinth.com/mod/mtr): click a point on the map, and it automatically plans a combined **metro + walking** route, guiding you with an on-screen HUD and in-world 3D lines.

- **Click-to-navigate**: like a real navigation app — click anywhere on the map to start.
- **Combined metro + walking routing**: automatically plans "walk → metro → walk".
- **MTR is optional**: walking navigation works even without MTR.
- **Xaero map backdrop**: reads your explored Xaero WorldMap (including the singleplayer `.xwmc` cache), with a built-in terrain fallback.

> 中文说明：[README_zh.md](README_zh.md)

---

## Supported versions

| | Version |
| --- | --- |
| Minecraft | 1.20.1 / 1.20.4 |
| Mod loader | Fabric (Fabric Loader 0.15+) |
| Required | Fabric API (matching MC version) |
| Optional | MTR 4.0.5 (metro), Xaero WorldMap (map backdrop) |
| Java | 17+ |

> Pick the jar by MC version:
> - `metronav-mc1.20.4-<version>.jar` → Minecraft 1.20.4
> - `metronav-mc1.20.1-<version>.jar` → Minecraft 1.20.1
>
> This is a **client-side mod** — drop it into your own `mods` folder; servers do not need it.

## Installation

1. Install **Fabric Loader** for your MC version.
2. Install **Fabric API** (matching MC version).
3. (Optional) Install **MTR 4.0.5** for metro navigation.
4. (Optional) Install **Xaero WorldMap** for the map backdrop.
5. Put the jar into `.minecraft/mods/` and join a world.

## Usage

### 1. Open the map
Press **N** (rebindable in Options → Controls).

The map backdrop is chosen automatically:
- Xaero WorldMap data available → **Xaero explored map**;
- otherwise → **built-in terrain** (heightmap sampling).

Overlays: metro lines (line color), stations, your position (white dot), destination (red cross).

### 2. Click to navigate
- **Left-click** the map: set the destination and start navigating.
  - Clicking near a station snaps to it (metro);
  - Clicking elsewhere plans "walk → metro → walk" or a pure walk.
- **Drag** = pan, **scroll** = zoom, **right-click** = cancel.
- Top buttons: clear destination, station list, refresh, recenter on player, close.

### 3. Follow the route
- **HUD (top-left)**: current line / walking, action, direction arrow, remaining distance and ETA.
- **Transfer hint**: when the next stop is a transfer, the HUD shows "Next transfer: Line X".
- **Express detection**: lines whose code contains `k` or whose name contains "快车" are labeled as express.
- **3D guidance**: walking = cyan ground line + small beacons; metro = line-colored line + station pillars.
- **Arrival**: entering a station's footprint advances to the next step (no need to reach the track).

## Without MTR

- Walking navigation still works (click anywhere on the map).
- Clicking "Station list" opens an MTR download prompt.

## Configuration

`.minecraft/config/metronav.json`:

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

## Building from source

Requirements: JDK 17, Gradle (fabric-loom 1.9.2).

1. Put MTR 4.0.5 jars in `libs/` (`MTR-fabric-4.0.5+1.20.4.jar` and `MTR-fabric-4.0.5+1.20.1.jar`).
2. Build:
   ```bash
   gradle build -Pminecraft_version=1.20.4 -Pfabric_api_version=0.97.3+1.20.4 -Pmtr_jar=libs/MTR-fabric-4.0.5+1.20.4.jar
   gradle build -Pminecraft_version=1.20.1 -Pfabric_api_version=0.92.12+1.20.1 -Pmtr_jar=libs/MTR-fabric-4.0.5+1.20.1.jar
   ```
3. Output is in `build/libs/`.

## FAQ

- **0 lines shown**: wait a few seconds for the route request; ensure MTR is 4.0.5 and the server has routes, then click "Refresh".
- **Blank areas**: Xaero only caches explored/generated chunks; explored areas load automatically.
- **Brief MTR dashboard flash**: normal while fetching route data (~1 s).
- **Walking guide is a straight line**: walk around obstacles yourself; the HUD arrow always points at the target.

## License

**GNU General Public License v3.0 (GPL-3.0)** — see [LICENSE](LICENSE).

> The `region.xaero` parser derives from the GPL-3.0 [XaerosMapFormat](https://github.com/DanDucky/XaerosMapFormat) project, hence GPL-3.0.
> See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for third-party notices.
