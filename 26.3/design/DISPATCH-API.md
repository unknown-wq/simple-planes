# Rotorcraft dispatch API

Package `xyz.przemyk.simpleplanes.api.dispatch`, entry point `RotorcraftDispatch`.
**API_VERSION = 1.**

The API sends a mini helicopter from a registered helipad to any position and brings it back.
The position does not need a helipad. The API knows nothing about any other mod. It needs no
listener at load time and never assumes one exists, so a mod that may or may not be installed can
call it (directly or by reflection) with nothing to set up beforehand.

## Model

- **Aircraft.** A mini helicopter entity, identified by its UUID. `deploy` puts one onto a pad
  from an item. `dispatch` will also adopt a mini helicopter already standing somewhere.
- **Order.** A `DispatchOrder` describes one trip:
  - `ownerId`, `homePad`;
  - `target` (x, y, z);
  - `searchRadius`, `groundHoldTicks`, `returnHome`, `cruiseSpeed`;
  - `userData`, a `CompoundTag` persisted with the order and returned on every event.
- **Phases** (`AircraftStatus.Phase`):
  - `IDLE`: on the ground, no order.
  - `OUTBOUND`: flying to the target. The landing zone may still be being searched for.
  - `AT_TARGET`: on the ground at the landing zone, holding. This is when passengers are loaded
    and unloaded.
  - `RETURNING`: flying home, or to an emergency landing zone after an abort.
  - `LOST`: the aircraft is gone. The record is kept so the outcome can still be read.
- **Riders.** Non-players only get aboard through `loadPassenger`. The medical livery (white
  wool, white concrete, quartz block or smooth quartz material) seats 2: the front seat
  (`SEAT_FRONT`) and a litter over the right skid (`SEAT_LITTER`). Other liveries seat 1.
  - A rider loaded this way never steers: only a player can control the aircraft.
  - A player boards only an empty machine, and never one on a dispatch flight in progress.
  - Any other non-player is put off on the next tick. This is the same rule as before, and it
    keeps out the large helicopter's animal pickup and `/ride`.

## Calls

All calls are static, run on the server thread, and take the `ServerLevel` the aircraft is in.

| Call | Result | Notes |
|---|---|---|
| `apiVersion()` / `API_VERSION` | int | 1 |
| `padsNear(level, x, y, z, radius)` | `List<PadInfo>` | nearest first; radius 0 = all |
| `findLandingZone(level, x, y, z, radius, spec)` | `LandingZone` or null | one-shot, bounded (250k column reads); `spec` null = default |
| `isDispatchable(stack)` | boolean | mini helicopter item |
| `deploy(level, stack, padName)` | `UUID` or null | pad must be loaded and free; takes one item |
| `tryDeploy(level, stack, padName, ownerId)` | `DispatchResult` | same, with the refusal reason |
| `stow(level, uuid)` | `ItemStack` (EMPTY if refused) | idle and on the ground; riders are put off first |
| `status(level, uuid)` | `AircraftStatus` or null | works while unloaded (last known position) |
| `aircraftOf(level, ownerId)` | `List<UUID>` | |
| `dispatch(level, uuid, order)` | `DispatchResult` (`id` = order id) | aircraft loaded, idle, on the ground, no player aboard; riders already aboard fly with it |
| `loadPassenger(level, uuid, entity[, seat])` | `DispatchResult` | aircraft on the ground; entity within 16 blocks; not a player |
| `unloadPassenger(level, uuid, entityUuid)` | boolean | aircraft on the ground |
| `unloadAll(level, uuid)` / `passengers(level, uuid)` | int / `List<UUID>` | |
| `extendHold(level, uuid, ticks)` | boolean | while `AT_TARGET`; while `OUTBOUND` it lengthens the hold that has not started yet |
| `release(level, uuid)` / `departNow` | boolean | ends the hold now |
| `recall(level, uuid)` | boolean | aborts with `RECALLED` and flies home; also brings an idle aircraft that is away back home |
| `registerListener(ownerId, listener)` / `unregisterListener` | | one listener per owner; replacing is allowed |

The refusal and abort codes are string constants in `DispatchReasons`. Unknown codes should be
treated as a generic failure.

## Events

`DispatchListener.onEvent(DispatchEvent)` is the only method the service calls. Its default
forwards to `departed`, `landedAtTarget`, `leftTarget`, `returned`, `aborted(event, reason)` and
`lost`. A `java.lang.reflect.Proxy` therefore only needs to handle `onEvent`. `DispatchEvent.toMap()`
gives every field as plain Java values.

| Type | When |
|---|---|
| `DEPARTED` | lifted off from home on the outbound leg (once per order) |
| `LANDED_AT_TARGET` | on the ground at the landing zone; the hold starts. `reason` is set if it came to rest off the zone centre |
| `LEFT_TARGET` | the hold ended (it expired or was released); the return leg has started |
| `RETURNED` | landed at home. With `aborted` set, `reason` is the abort code. `RETURN_FAILED` means it landed at an emergency zone short of home |
| `ABORTED` | the order was given up (`NO_LANDING_ZONE`, `CEILING`, `FLIGHT_FAILED`, `LANDED_IN_WATER`, `HOME_PAD_UNAVAILABLE`, `RECALLED`, `RETURN_FAILED`). It is followed by `RETURNED` or `LOST` |
| `LOST` | `DESTROYED` (killed), `REMOVED` (discarded), `CHANGED_DIMENSION`, or `VANISHED` (not found at its last known position for 2400 ticks with the chunks loaded). Terminal |

A normal out-and-back order produces `DEPARTED`, `LANDED_AT_TARGET`, `LEFT_TARGET` and `RETURNED`,
in that order. With `returnHome = false`, the order ends when the hold ends: the phase goes to
`IDLE` away from home and no event is sent.

**Delivery.** Events are written to the dimension's saved data (`data/simpleplanes/dispatch.dat`)
the moment they happen. They are delivered at the end of a level tick, in order, to the listener
registered for the owner. Until such a listener exists they wait, persisted across restarts, up to
512 per dimension, after which the oldest are dropped. A listener that throws is logged, and the
event still counts as delivered.

## Landing-zone search

The search looks at the columns within `radius` of the target, nearest first, using heightmaps
only (three O(1) reads per column, cached in a grid). `LandingZoneSpec.DEFAULT` is:

| Field | Default |
|---|---|
| footprint | 5×5 |
| `maxSpread` | 1 |
| `ringWidth` | 2 |
| `clearHeight` | 16 |
| `approachLength` | 24 |
| `minClearSectors` | 1 |
| `maxElevation` | y 120 |
| budget | 2048 column reads per tick |

A candidate is accepted when:

1. The footprint is loaded, dry (MOTION_BLOCKING = OCEAN_FLOOR), free of leaves
   (MOTION_BLOCKING = MOTION_BLOCKING_NO_LEAVES), and within `maxSpread` of level. The centre column
   is tested first, so water and canopy are rejected after one read.
2. The ring around the footprint is at most one block above the zone.
3. At least one of the 8 approach sectors is clear. Each sector is checked along three lanes (the
   centre line and 2 blocks either side), every 2 blocks out to `approachLength`. The terrain must
   stay under a 45° surface that starts at the ring edge and is capped at `clearHeight`.

Unloaded columns are always obstacles. The heightmap gives the top of each column, so the space
above the footprint is clear by construction.

**Cost.** At worst about 300 reads per candidate. With the cache, a typical open field is found in
under 100 reads. For a radius-32 search that fails, the whole grid is at most 125² columns.

**Target over water.** The nearest dry, flat shore within the radius wins. If none is found, the
order is aborted with `NO_LANDING_ZONE`.

**While outbound**:

- The service holds a chunk ticket over the search area (ENDER_PEARL, radius ≤ 6, renewed every 20
  ticks).
- It waits for the area to load, for at most 600 ticks.
- It then searches incrementally. Meanwhile the aircraft flies towards the target and circles near
  it if the search has not finished.
- A zone that is found re-aims the flying leg.
- No zone means `ABORTED(NO_LANDING_ZONE)` and a flight home.

## Flight

- The legs are flown by the helicopter autopilot, using the mini helicopter's profile:
  - cruise capped at y 130;
  - at least 12 blocks of terrain clearance;
  - the hard limit is y 150 (the airframe's thrust fades above y 100, and its ceiling is y 160);
  - terrain that needs more than y 150 fails the leg with `CEILING`;
  - no booster; the autopilot supplies power.
- The departure from a landing zone climbs vertically above everything within 24 blocks, plus 8
  blocks.
- A flying leg keeps its own chunks loaded. While `AT_TARGET`, the service holds a radius-2 ticket
  on the aircraft.
- Any aircraft in an order that cannot be resolved (for example after a restart) gets a radius-2
  ticket on its last known position until it loads. Its flight then resumes from the saved plan.
- A failed return leg is retried 3 times. After that, the aircraft lands at a zone found within 48
  blocks, and the order ends with `RETURNED` (aborted, `RETURN_FAILED`).
- Air defence never engages a dispatch-managed aircraft, whatever its allegiance.
  `deploy` and `dispatch` also set the allegiance to friendly.

## Commands (operators)

`/autopilot medevac <homePad> <x y z> [holdTicks] [crew <entity>] [radius <n>]`:
- It uses an idle mini helicopter standing on the pad, or deploys a new one in the medical livery.
- `crew` boards that entity in the front seat before departure.
- The order's owner is `simpleplanes:command`. Its events go to the server log and to online
  operators.

`/autopilot dispatch <sub>` covers the rest of the API. The subcommands are `list`, `status`,
`deploy <pad> [medical]`, `stow`, `send <aircraft> <homePad> <pos> <hold> [oneway]`,
`load <aircraft> <entities> [front|litter]`, `unload <aircraft> [<entities>]`, `extend`, `release`,
`recall` and `lz <pos> [radius]`. `lz` prints the search cost.

`/autopilot heliflight <from> <to> [speed] type mini_helicopter` flies the mini helicopter
pad to pad.

## Compatibility

- Within API version 1, these never change or disappear:
  - the method signatures above;
  - the record components;
  - the event types, phase names and reason codes.

  Additions (new methods, new reason codes, new event types, new trailing record components with
  a compatible constructor kept) bump `API_VERSION`. Callers should check `apiVersion() >= N`
  before using a feature from version N.
- `DispatchEvent.Type` and `AircraftStatus.Phase` may gain values. Callers should use
  `name()`/`toMap()` and ignore values they do not know.
- The only Minecraft types in signatures are `ServerLevel`, `Entity`, `ItemStack` and
  `CompoundTag`. Positions are plain ints.
- Saved data is `simpleplanes:dispatch`, per dimension. The aircraft's own flight plan (including
  the order id and the ad-hoc zones) is saved with the entity.
