# Patrol drones

A small quadcopter that takes off from its item, flies a waypoint route at a fixed height above the
terrain, looks at what is below it and reports to whoever launched it. Written for the MineColonies
barracks integration, but the drone has no knowledge of MineColonies: any mod drives it through the
public API in `xyz.przemyk.simpleplanes.api.drone`, and without such a mod it is a standalone item
driven by `/drone`.

Branch `claude/patrol-drones-26.3` (base `origin/26.3-beta` `a8d4872`, 5.4.0-beta.4), tree `26.3/`.
No mixins.

## 1. What is in the tree

| file | what |
|---|---|
| `api/drone/PatrolDrones.java` | entry point: `API_VERSION`, `DETECTION_RADIUS` 16, `MAX_RANGE` 1000, `registerController`, `deploy`, `find`, `all`, `isKnown`, `lastKnownPosition`, `summon`, `isDroneItem`, `newDroneItem` |
| `api/drone/PatrolDrone.java` | one live drone: state, status, home, route, range, cruise height, scan interval, launch / track / stopTracking / returnHome / stow / pack, controller key and data |
| `api/drone/DroneController.java` | callbacks, all default methods: `isTarget`, `ignoresDamage`, `onScan`, `onSighting`, `onLostTrack` (`GONE`, `OUT_OF_SIGHT`, `OUT_OF_RANGE`, `RELEASED`), `onReturned`, `onDestroyed`, `onPickedUp`, `onStowed` |
| `api/drone/DroneState.java` | `PARKED TAKEOFF PATROL TRACKING RETURNING LANDING FALLING` |
| `api/drone/DroneStatus.java` | immutable snapshot for UIs and commands |
| `drone/PatrolDroneEntity.java` | the entity: flight (the crane's controller and physics step), route, detection, tracking, damage, persistence |
| `drone/DroneRegistry.java` | live index by UUID, chunk tickets, restart recovery, write-off of drones that vanished |
| `drone/DroneSavedData.java` | `simpleplanes:patrol_drones` roster (UUID, last position, airborne, controller) |
| `drone/DroneFeedback.java` | log and owner-message helper; the default controller used when none is bound |
| `drone/DroneCommand.java` | `/drone`, permission level 2 |
| `items/PatrolDroneItem.java` | right-click a block to deploy |
| `client/render/PatrolDroneRenderer.java`, `PatrolDroneRenderState.java`, `models/PatrolDroneModel.java` | model built in code (fuselage, sensor pod, two diagonal arms, skids, four motors and spinning blades, green/red beacon drawn full-bright) |
| `textures/plane_upgrades/patrol_drone.png` (64x64), `textures/item/patrol_drone.png` (16x16) | own textures, generated for this item; no vanilla texture is referenced |
| `data/simpleplanes/recipe/patrol_drone.json` | shaped: propeller in the four corners, iron ingot either side, spyglass in the middle |

Registrations: item `simpleplanes:patrol_drone` (stacks to 1, in the Simple Planes tab after the crane
remote), entity `simpleplanes:patrol_drone` (0.8 x 0.5). Lang keys in `en_us` and `ru_ru`.

## 2. Behaviour

| | value |
|---|---|
| health | 12; ignores damage the controller says to ignore |
| cruise | 20 blocks above terrain (8..48 settable), terrain read from the `WORLD_SURFACE` heightmap along a 4-block look-ahead strip |
| speed | patrol 0.6 b/t target, 9.5 b/s measured |
| detection | every 10 ticks (5..100 settable) one `getEntitiesOfClass(LivingEntity)` over a 32 x 80 x 32 box, then a 16-block horizontal radius and an open-to-sky test. Cost is one bounded box query per drone per scan, independent of the route length |
| tracking | orbit radius 7, 10 blocks over the target; 600 ticks of approach grace before a far target counts as unseen; lost after 100 ticks unseen |
| operating radius | at most 1000 blocks from home; waypoints beyond it are refused, tracking a target beyond it ends with `OUT_OF_RANGE` |
| chunk tickets | while airborne, an `ENDER_PEARL`-type ticket of radius 2 around the drone, renewed as it moves; a parked drone holds none |
| restart | the roster is saved every few seconds and on `SERVER_STOPPING`; on start every airborne drone's chunk is ticketed again so it resumes. A drone loaded but not ticking is found by UUID and re-ticketed where it is |
| stuck | takeoff blocked for 100 ticks: carries on sideways into the route; airborne and not moving a block for 200 ticks: lifted onto the surface of its column |
| landing | aligns over home, then sinks towards 2 blocks under the heightmap surface until it touches something (the heightmap also counts blocks without collision); parks on contact, or after 600 ticks of landing wherever it is |
| destroyed | at 0 health it falls, reports `onDestroyed` at once and explodes (particles and sound only, no block damage) on impact; a falling drone is no longer kept in the roster, so it is not written off later |
| air defence | Simple Planes' air defence and MineColonies' anti-air both engage `PlaneEntity` only; the drone is not one |

The item places the drone on the clicked block face if its bounding box fits, bound to no controller,
with the player as owner. A survival player hitting a parked drone picks it up as its item; a creative
player removes it. An airborne drone takes the hit as damage.

## 3. API use, in short

```java
PatrolDrones.registerController("mymod:base", controller);          // once
PatrolDrone d = PatrolDrones.deploy(level, item, home, null, "mymod:base", "base-7/slot-1");
d.setRoute(List.of(a, b, c), true);                                 // takes off, flies the loop
PatrolDrones.find(level, id);                                        // later; null if not loaded
PatrolDrones.isKnown(level, id);                                     // still exists somewhere
PatrolDrones.summon(level, id);                                      // ticket its last known chunk
```

The controller key is saved with the drone; the data string is opaque to Simple Planes. Every
callback runs inside the drone's tick and is wrapped: a controller that throws (`RuntimeException` or
`LinkageError`) is logged and the drone carries on with default behaviour.

`API_VERSION` is 1. New methods are added as defaults; an incompatible change bumps the number.

## 4. `/drone`

```
/drone spawn [pos]                    item-less spawn for testing
/drone list | status [id]
/drone route <id> add <pos> | clear | loop <bool>
/drone patrol <id> square|circle <radius>     4 or 12 points round home, looped
/drone launch|home|stow|untrack <id>
/drone track <id> <entity>
/drone autotrack <id> <bool>          track the nearest thing the filter accepts
/drone height <id> <agl> | range <id> <blocks> | scan <id> <ticks>
/drone kill                           packs every loaded drone
```

`<id>` is the entity id shown by `list`. Every reply is also written to the server log.

## 5. Tests

Standalone server, Simple Planes only (no MineColonies), flat world, `/forceload` where noted:

| test | result |
|---|---|
| boot | clean, no errors |
| spawn + `patrol square 40` | cruises at y=-40 (20 over the flat -60), corners at +-28.3, lap about 24 s, 9.5 b/s |
| detection | zombie 12 blocks off the track seen; zombie 22 blocks off not seen; cow ignored (not `Enemy`, default filter) |
| track | orbit radius about 7 at 10 over the target; target killed -> `GONE` -> back to `PATROL` |
| range | `circle 1200`: all 12 refused; waypoint at 1001 refused, at 999 accepted and flown (about 100 s, no player, tickets carry it); `range 5000` clamped to 1000; tracking a zombie at 1010 refused |
| damage | 5 -> hp 7/12; 20 more -> `FALLING`, "destroyed" reported, crash at the ground, gone from `list` |
| restart mid-patrol | drone ticking and patrolling after restart, no forceload |
| `home` | `RETURNING` -> `LANDING` -> `PARKED`, "landed at home 0 -60 0": about 18 s before the landing change, 13 s after it (4 s returning, 9 s landing) |
| `stow` | lands and drops `simpleplanes:patrol_drone` |
| recipe | id accepted by the recipe manager |

With MineColonies driving it (details in the MineColonies branch, `docs/drones/NOTES.md`):

| test | result |
|---|---|
| deploy from a barracks item | drone off the barracks roof and on its route 5 s after the item arrived |
| border loop, 42 points | perimeter of a 9x9-chunk colony flown at 72..88 blocks from the centre, lap about 100 s |
| restart with three drones airborne | all three resume their routes |
| drone launched inside a roof cavity | takeoff blocked -> sideways -> boxed in -> lifted 2 blocks onto the roof, then patrols |
| `/damage` 30 on a controlled drone | `onDestroyed` delivered, the colony slot refilled 9 s later; no "written off" warning afterwards |
| controller calls it home (slot turned off) over a hut roof with a torch-height block | before: hung in `LANDING` above the block; after: lands, packs, `onStowed` |
| Simple Planes removed from a world with drones, then added back | vanilla drops the drone entities whose chunks load meanwhile; on return those are written off ("its chunk ... loaded without it"), removed from the roster, and `isKnown` turns false, which is how the controller notices; a drone whose chunk never loaded meanwhile is found and resumes |
| boot with MineColonies absent, final jar | clean; nothing references MineColonies |

## 6. Open questions

* A drone whose chunk unloads while parked stays there until something tickets it; `summon` is how a
  controller asks for it. Players walking away from a parked, uncontrolled drone leave it parked.
* The drone does not shoot and does not dodge; it is only a sensor. Whether it should flee when hit is
  left open.
* Detection ignores anything under a roof (open-to-sky test). A cave spider under a tree canopy is
  invisible to it by design; `MOTION_BLOCKING_NO_LEAVES` keeps leaves see-through.
* Recipe cost (4 propellers, 2 iron, 1 spyglass) is a first guess.
