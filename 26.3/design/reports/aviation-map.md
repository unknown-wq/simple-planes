# Aviation map: work report

Branch `claude/aviation-map-26.3`, based on `26.3-beta` `a7bf658`.

A client-side map can now show airfields, helipads, shuttles, autopilot flights and launch silos, and an
operator standing at a silo can launch from the map. Simple Planes provides the server side and a small client
API. It draws nothing itself. The first client is the world map in minecolonies-fabric (`worldmap/26.3`,
branch `claude/worldmap-aviation-26.3`), which reaches this API only through reflection when Simple Planes is
installed.

## What was added

| Where | What |
|---|---|
| `api/map/AviationMap` | The client API: `isAvailable`, `requestSnapshot`, `requestLaunch`, `latest`, `lastResult`, listeners, `SURFACE`. `API_VERSION = 1`. |
| `api/map/AviationSnapshot`, `LaunchResult` | Records for the snapshot and the answer to a launch, load or unload. |
| `aviation/AviationPayloads` | Four payloads, protocol 1, with capped lists and strings. |
| `aviation/AviationService` | Snapshot builder, launch handler, rate limits, silo-index upkeep through Fabric events. |
| `aviation/SiloIndex` | Per-dimension `SavedData` `simpleplanes:silos`. |
| `aviation/AviationCommand`, `AviationTestPlayer` | `/aviation` (level 2): index, snapshot, headless launch tests. |
| `SimplePlanesMod`, `SimplePlanesClient` | Call `AviationService.init()` / `AviationMap.init()`. |
| `lang/en_us.json` | 11 `simpleplanes.aviation.*` keys. Every text also has an English fallback. |

No existing silo, missile or autopilot class was changed. No mixin and no access-widener entry was added. The
full description is in `MISSILES.md` §8 and `AUTOPILOT.md` §9a.

## Rules the server enforces

- **Operators only** (level 2, as `/missile launch`). This was the owner's decision. Non-operators get the
  snapshot, with `launchPermitted = false`.
- **Checks, in order:** permission, then a rate limit of one request per second (wall clock), then the world
  border, then the player within 24 blocks of the silo mouth, then the silo chunk loaded, then the silo present,
  then `LaunchSiloBlockEntity#launch` (mode, idle, loaded, intact, hatch, range, height).
- **The client is trusted for nothing** but the silo position and the target column.
- **Target height:**
  1. If the target chunk is loaded, the server heightmap.
  2. Otherwise, the client's height, clamped to the build range.
  3. If the client sent none, the generator estimate.
- **Refusals** go to the action bar (red), the map and the log.

## Tests

Dedicated server `26.3` with this jar, on port 25713. A real client under Xvfb (llvmpipe) with the world map
for the UI cases.

| Case | Result |
|---|---|
| Non-operator | refused: operator permission is required |
| Player 200 blocks from the silo | refused: too far away (200 blocks; you must be within 24) |
| Silo in an unloaded chunk | refused: the silo's chunk is not loaded |
| Air-defence silo | refused: the silo is in air-defence mode |
| Empty silo | refused: no missile loaded |
| Target 2000 blocks from a T1 / 10 blocks from a T1 | refused: beyond the tier 1 range of 1200 / inside the tier 1 minimum range of 24 |
| Second request within a second | refused: too many launch requests |
| T3 to an unloaded target (−600, −300), `SURFACE` | accepted, y = −19. It arrived after 15.0 s, 727 blocks flown, miss 0.00. |
| T1 to 300, 0 / T4 to 3000, 21 | arrived, miss 0.00. T4 took 41.5 s. |
| UI launch on the client (T4 → 249, 150) | accepted, arrived, miss 0.00 |
| UI launch after the silo was emptied from the console | refused by the server; the reason appears in the map panel and the action bar |
| Index after a restart | intact |
| Index after `/setblock` air / `strict` over a silo | entry dropped by the sweep |
| Index after deleting the silo's region file | entry dropped when the chunk loaded again |
| Dedicated server without the map / client without the map | run normally; nothing is sent to clients that cannot receive it |

## Open points

- **Near radius 24.** Picked so that the silo is always inside the player's simulation distance. A console or
  terminal block could replace the "stand at the silo" rule later.
- **World-border check.** This is the one check beyond what `/missile launch` does.
- **The `protocol` field in `aviation_request`:** checked since protocol 2 (see the follow-up below).
- **Missiles in flight are not in the snapshot.**
- **Flight positions** are only as fresh as the map's poll (2 s on the world map).
- **Translations:** `en_us` only.
- **Existing issue in the test world:** shuttle 1 waited at Eastfield with "could not be put on a departure spot".
  This is unrelated to this work and was not investigated.

## Follow-up: load and unload, range data

The table above describes the first version (protocol 1, API 1). The follow-up adds the following.

| Where | What |
|---|---|
| `api/map/SiloAction` | New enum: `LAUNCH`, `LOAD`, `UNLOAD`. |
| `api/map/AviationMap` | `API_VERSION = 2`. Adds `canService`, `requestLoad` and `requestUnload`. |
| `api/map/AviationSnapshot.Silo` | Adds `serviceable`, `serviceStatus`, `detectionRadius` and `engagementRange`. The last two come from `InterceptorSpec.of(tier)` (100/150/225/350 and 400/600/900/1400). |
| `api/map/LaunchResult` | Adds `action`. |
| `aviation/AviationPayloads` | Protocol 2. Adds a fifth payload, `simpleplanes:aviation_silo` (silo position and action). The reply carries the action. |
| `aviation/AviationService` | Shares the launch checks with load and unload (`locate`), handles `aviation_silo`, and answers only players whose request carried protocol 2. |
| `aviation/AviationCommand` | `/aviation test load\|unload <at> <silo> [op\|nonop]`. |
| `lang/en_us.json` | 8 more `simpleplanes.aviation.*` keys. |

**Rules for load and unload:**

- Operators only, the same as `/missile silo load|unload`.
- One rate limit is shared by launch, load and unload: one request per second.
- The player must be within 24 blocks of the silo mouth, and the silo chunk must be loaded.
- Then the server calls `LaunchSiloBlockEntity#load()` or `unload()`. These are the calls the command makes. No
  item is needed, and an unloaded missile is gone, as with the command.
- A load also checks `SiloStructure.isIntact`. This is the one check beyond the command.
- Strike and air-defence silos can both be loaded and unloaded. Only a strike silo can be launched.
- Refusals carry the silo's own reason: already loaded, not loaded, busy (phase), damaged.

**Protocol check:** a client that asks with a protocol other than 2 gets no snapshot and no replies. The server
logs one line for it. A map built against API 1 fails the version check on its side, so it does not start its
integration.

### Tests (follow-up)

Same server and client as above.

| Case | Result |
|---|---|
| Non-operator loads a strike silo / loads or unloads an AD silo | refused: operator permission is required to load or unload |
| Player 200 blocks away | refused: too far away |
| Silo in an unloaded chunk | refused: the silo's chunk is not loaded |
| Load of a loaded T4 | refused: already loaded |
| Unload of an empty silo | refused: not loaded |
| Unload while the hatch is opening | refused: busy (opening) |
| Operator load and unload, strike T3 | accepted both ways |
| Operator unload and load, air-defence T3 | accepted both ways |
| Unload, then load 150 ms later; then a launch | second and third refused: too many silo requests |
| UI load of an empty T2 on the client | accepted; the panel shows the missile |
| UI unload and reload of an AD silo | accepted |
| UI as a non-operator | Load and Unload disabled, with the operator tooltip |

### Open points (follow-up)

- **Damaged silo:** not reached in a test. Breaking a casing block dismantles the whole silo.
- **Protocol mismatch:** not tested with a real older client.
- **Engagement range** is the length of the interceptor's motor path. It is an upper bound, not a guaranteed
  kill range.
- **Detection radius** is a 3D sphere around the silo mouth. The map draws it as a circle at ground level.
