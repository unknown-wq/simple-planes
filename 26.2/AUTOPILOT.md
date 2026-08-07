# Autopilot, routes and runways

A server-side flight director for Simple Planes: aircraft that fly themselves — scripted attack
runs, patrol routes, and real instrument-style approaches onto surveyed runways.

Everything here is **new code** in `xyz.przemyk.simpleplanes.autopilot` plus three new items. The
only changes to existing files are additive and marked `// autopilot:`.

---

## 1. The core idea

The autopilot **never sets the plane's position, velocity or rotation.** Every tick it moves the
same four controls a player would move:

| Control | How the autopilot sets it | What the physics does with it |
|---|---|---|
| Throttle | `setThrottle(0..5)` | `push = 0.00625 * throttle` |
| Pitch | `setPitchUp(-1/0/+1)` | `tickPitch` ramps `pitchSpeed` by 0.5 deg/tick, clamped to ±5 |
| Yaw | `setYawRight(-1/0/+1)` | `tickYaw` ramps `yawSpeed` by 0.5 deg/tick, clamped to ±2.5 |
| Roll | `TempMotionVars.moveStrafing` | `tickRoll` ramps `rollSpeed`; on the ground it steers instead |

So the aircraft flies on `PlaneEntity`'s own aerodynamics. It can stall, it can be too slow to
climb, and it will explode if it touches down banked — the autopilot has to actually fly well, not
cheat. There are no teleports and no synthetic velocities anywhere in the feature.

Because roll comes from the strafe input rather than a setter, `PlaneEntity` needed one hook to read
that input from the autopilot when no player is aboard. See §7.

### Control laws

* **Heading** — bang-bang on the yaw control with a *rate* term. Because the controls are a double
  integrator (input changes acceleration, not rate), a naive proportional controller oscillates
  forever. `AutopilotMath.bangBang` subtracts the angular stopping distance
  `rate * |rate| / (2 * accel)` from the error, so the controller starts braking at exactly the
  right moment. The same routine drives pitch and roll.
* **Altitude** — a cascade, each stage clamped, which is what keeps the aircraft inside a sane
  envelope: `altitude error → commanded vertical speed → commanded flight path angle → commanded
  pitch attitude → pitch control`.
* **Speed** — proportional on the throttle notch, adjusted every 5 ticks so the lever does not
  chatter.
* **Bank** — follows the heading error, capped at 25°, and forced to zero for landing and ground
  roll.

All tuning lives in one file: `autopilot/AutopilotConfig.java`.

---

## 2. The items

| Item | Recipe | Purpose |
|---|---|---|
| **Plane Strike Tool** (`plane_strike_tool`) | 2 iron ingots diagonally + TNT | Scripted attack run |
| **Route Wand** (`route_wand`) | 2 sticks diagonally + diamond | Waypoint routes |
| **Runway Survey Tool** (`runway_tool`) | 2 iron ingots diagonally + compass | Survey and register runways |

All three are in the Simple Planes creative tab.

### Plane Strike Tool

* **Right-click a block** — spawns an aircraft the configured distance away (default 400 blocks, on
  the far side of you so it runs in past you) and sends it at that block at full throttle.
* **Right-click the air** — status report.
* **Sneak + right-click the air** — cycle the spawn distance: 100 → 200 → 400 → 800.

The aircraft terrain-follows on the way in, then commits to a dive 200 blocks out. Impact is handled
by the plane's own collision code (`horizontalCollision → crash`), i.e. a real explosion.

### Route Wand

* **Right-click blocks** — add waypoints.
* **Sneak + right-click a block** — add a final waypoint, then launch.
* **Right-click the air** — draw the route with particles and print it.
* **Sneak + right-click the air** — clear the route.

Two waypoints gives you exactly the requested behaviour: the aircraft flies **A → B → A** and then
lands. Cruise altitude is picked automatically, 60 blocks above the highest terrain under the route.
It lands at the nearest surveyed airfield within 512 blocks of the first waypoint; if there is none
it improvises a landing (§5).

The waypoint list is stored in a data component on the item, so a half-drawn route survives logging
out, dropping the wand or stashing it in a chest.

### Runway Survey Tool

* **Right-click one threshold, then the other** — surveys the strip between them and registers it.
* **Sneak + right-click** — cancel a half-marked runway.
* **Right-click the air** — list registered airfields.

Mark the two **centreline ends** (not opposite corners), which is what makes the runway heading
exact. The survey reports:

* length, measured width, slope in degrees
* both thresholds with their elevation and compass heading
* both **runway designators** (`09/27` style, derived from the true heading)
* surface roughness — the standard deviation of the centreline surface height, so `0.00` is a
  perfectly flat strip
* obstacles in each approach funnel: terrain columns poking above the glide slope out to 200 blocks
* the preferred landing direction
* warnings for a short runway or a steep slope

---

## 3. The state machine

```
IDLE ──► TAKEOFF ──► CLIMB ──► CRUISE ──► DESCENT ──► APPROACH ──► FINAL ──► FLARE ──► ROLLOUT ──► IDLE
                                 │            ▲           │  ▲                 │
                                 │            └─ HOLD ◄───┘  └──── GO_AROUND ◄─┘
                                 └──► STRIKE (one-way attack run, no landing)
```

| Mode | What it does |
|---|---|
| `TAKEOFF` | Full power, ground steering on the runway heading, rotate at 0.35 speed, wings level |
| `CLIMB` | Climb to cruise altitude on the first waypoint's bearing |
| `CRUISE` | Fly waypoints, terrain-following, advancing on arrival within 30 blocks |
| `STRIKE` | Run in high, then dive at the target |
| `DESCENT` | Fly to the initial approach fix, 300 blocks out at circuit height |
| `APPROACH` | Track the extended centreline and capture the glide slope |
| `FINAL` | As above, plus the landing gates are enforced |
| `FLARE` | Nose up 4°, throttle closed, wings level |
| `ROLLOUT` | Throttle closed, ground steering, until the aircraft stops |
| `HOLD` | Orbit the approach fix at circuit height until the runway frees up |
| `GO_AROUND` | Full power, climb to circuit height, then rejoin via `HOLD` |

Mode changes are announced to the owning player on the action bar; surveys and confirmations go to
chat.

---

## 4. Landing like an aircraft, not like a dart

The approach geometry is deliberately self-consistent: an 8° glide slope, intercepted 300 blocks out
at a circuit height of 45 blocks above the threshold (`tan(8°) × 300 ≈ 42`). If those three numbers
disagree, the aircraft arrives at the approach fix far above the slope and has to dive at it — so if
you retune one, retune all three.

**The landing gates.** Below 30 blocks above the threshold, the approach must satisfy *all* of:

* heading within 10° of the runway heading — this is the "no landing at an angle" rule
* lateral offset within the runway width (minimum 10 blocks)
* bank within 12° — `PlaneEntity#causeFallDamage` explodes the plane above 45°, so this matters
* sink rate under 0.45 blocks/tick

Any failure triggers **`GO_AROUND`**. After 3 go-arounds the aircraft tries the opposite runway end;
beyond that it commits to the landing rather than orbiting forever.

**Not flying into hills.** Two independent checks:

1. The heightmap terrain profile (§6) keeps a 22-block clearance on every mode that terrain-follows.
2. On approach, a genuine voxel raycast (`Level.clip`) down the corridor from the aircraft to the
   runway aiming point, once a second above 15 blocks AGL. Unlike the heightmap this also catches
   overhangs. A blocked corridor triggers a go-around.

**Holding.** `RunwayOccupancy` is a small reservation registry keyed by dimension and airfield name.
An aircraft reserves the runway when it commits to the approach and releases it on landing, on a
go-around, or when it is destroyed. A second aircraft arriving at a busy field enters `HOLD` and
orbits the approach fix at circuit height until the runway frees up.

**Which end to land on** is chosen by counting approach obstacles at both ends; ties go to the
uphill direction, because landing uphill shortens the roll-out. There is no wind — Minecraft has no
wind API, so runway selection deliberately ignores it rather than inventing one.

---

## 5. Terrain following and obstacle avoidance

Deliberately cheap and predictable — no A*, no world search:

* 12 forward samples out to 220 blocks along the ground track, plus 4 samples in each of two side
  sectors at ±35°.
* Each sample is a single `Level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)` — an **O(1)**
  heightmap lookup, not a block scan. `Level#getHeight` checks `hasChunk` first and returns
  `getMinY()` for absent chunks, so this never forces chunk loading.
* Commanded altitude is raised to `highest terrain ahead + 22`.
* Only if the ridge genuinely cannot be out-climbed in the distance available does the aircraft
  sidestep, biasing its heading 30° towards whichever side is at least 4 blocks lower. That
  hysteresis is what stops it weaving over flat ground.

Total cost is roughly 20 heightmap lookups per aircraft per tick, and the number of live autopilots
is capped at 24.

**Improvised landings.** With no surveyed airfield in range, `Airfield.flattestHeading` scores 12
candidate directions around the first waypoint by summed height change and builds a throwaway 80×8
strip along the flattest one. It is a field landing and it can be rough — survey a real runway if
you want a reliable one.

---

## 6. Persistence

| Data | Where | Survives restart |
|---|---|---|
| Airfields | `SavedData` per dimension, `data/simpleplanes/airfields.dat` | **Yes** |
| In-progress route flight | Plane entity NBT, via `FlightPlan.CODEC` | **Yes** |
| Half-drawn route / half-marked runway | Data component on the item | **Yes** |
| Runway reservations | In memory | No — and correctly so, they are re-derived on load |
| Strike flights | Not written | No — see below |

Strike flights are deliberately **not** persisted. `addAdditionalSaveData` also backs
`PlaneEntity#getItemStack`, so persisting them would let a destroyed strike aircraft drop an item
that launches a fresh attack run when placed. A strike aircraft is a one-shot weapon; losing one to
a restart is the right trade.

---

## 7. Changes to existing files

`entities/PlaneEntity.java` — additive only, every hunk marked `// autopilot:`:

1. `import ...autopilot.PlaneAutopilot;`
2. Field `private @Nullable PlaneAutopilot autopilot;` plus `getAutopilot`, `setAutopilot`,
   `isAutopilotEngaged`, and `autopilotRotationSpeedMultiplier()` (which just exposes the protected
   `getRotationSpeedMultiplier()` so the controllers can size their braking on the slower airframes).
3. `isPowered()` — one extra disjunct so tool-spawned aircraft run on autopilot fuel. A plane the
   **player** built still needs a working engine, so this is not a fuel cheat.
4. `tick()` — `autopilot.tick(this)` right after `markHurt()`, server side only, so the controls it
   sets this tick are the ones the physics acts on.
5. `tick()` — one extra `else if` branch supplying `moveForward` / `moveStrafing` from the autopilot
   when nobody is aboard.
6. `readAdditionalSaveData` / `addAdditionalSaveData` — load and save the flight plan.
7. `remove(RemovalReason)` — new override releasing the runway reservation and the traffic slot.

No existing physics or collision method was rewritten or reformatted.

Other files: three items registered in `setup/SimplePlanesItems.java` (plus creative tab entries),
and two init calls appended in `SimplePlanesMod.onInitialize()`.

---

## 8. Testing without a client

`/autopilot` (permission level: gamemasters) drives the whole feature from a dedicated server.
**No subcommand requires a player** — every one takes explicit coordinates, so they all run from the
server console, a command block or a datapack function. A player is an optional convenience: it
makes relative coordinates (`~ ~ ~`) work and decides which side an attack run comes in from.

```
/autopilot strike <x y z> [distance] [bearing]   launch an attack run
/autopilot route <x y z> <x y z>                 fly A -> B -> A and land
/autopilot survey <x y z> <x y z>                survey a runway between two thresholds
/autopilot airfields                             list registered airfields
/autopilot status                                full telemetry for every autopilot aircraft
/autopilot stop                                  stop every autopilot aircraft in this dimension
```

`bearing` is the compass direction the attack run comes in *from*, 0–359. Omit it and the bearing is
derived from wherever the command was issued (the player, or the console's world-spawn origin); if
that origin sits on top of the target it falls back to a fixed due-south run-in. Given explicitly,
the whole flight is deterministic and repeatable, which is what makes headless testing useful.

`status` is the one to watch while debugging. Per aircraft it prints:

```
#42 approach pos=118,96,-204 agl=41 hdg=047 pitch=-3 roll=+2 spd=0.51 vs=-0.07 thr=3
    want[hdg=044 alt=93 spd=0.50] tgt=300,72,300 dist=286 rwy=airfield-1/09 legs=1/2
```

`pos`/`agl`/`hdg`/`spd`/`thr` are what the aircraft is *actually* doing; `want[...]` is what the
flight director is *commanding*. Comparing the two is how you tell a controller that is tracking
from one that is saturated or fighting itself — and a `pos` that does not change between two polls
means the aircraft is not ticking at all (see chunk loading below).

### Chunk loading

An autopilot aircraft routinely flies hundreds of blocks from any player, and **entities in chunks
nobody keeps loaded simply stop ticking**. The autopilot therefore re-requests a
`TicketType.ENDER_PEARL` ticket with radius 2 every 20 ticks, exactly as vanilla does for a thrown
ender pearl (`ServerPlayer#placeEnderPearlTicket`). The ticket times out after 40 ticks, so nothing
leaks if the aircraft is destroyed. Without this the 400-block strike would just hang in the air.

---

## 9. Limitations and what is not implemented

* **Helicopters are not supported.** `HelicopterEntity` overrides `tickPitch`, `tickRoll` and
  `getTickPush`, so the control laws here do not describe it. The autopilot always spawns a plain
  `PlaneEntity`. Attaching one to a helicopter will fly badly rather than crash.
* **No wind.** Minecraft 26.2 has no wind API, so runway selection uses approach obstacles and slope
  only. Nothing was invented here.
* **Circuit joins are simplified.** The aircraft flies direct to the initial approach fix and tracks
  the extended centreline inbound. There is no downwind/base leg — the holding pattern is a simple
  circular orbit rather than a racetrack.
* **Bank direction is cosmetic.** Turns are produced by the yaw control, as in the base game; bank
  is commanded only so the aircraft looks right. If it banks the wrong way in a turn, flip the sign
  of `desiredRoll` in `PlaneAutopilot#applyControls` — it will not change the flight path.
* **Improvised landings are rough** by nature. Survey a runway for anything reliable.
* **Terrain in ungenerated chunks reads as unknown** and is skipped, so an aircraft flying into
  never-visited terrain holds altitude rather than reacting to ground it cannot see. The chunk
  ticket keeps a bubble loaded around the aircraft itself, which covers the normal case.
* **Route legs are fixed at 2** (out and back) from the wand. Use the `FlightPlan` API for more.
* **No player is ever required.** Aircraft spawn, fly, land, save and load with no player involved;
  an owning player is only an optional recipient for progress messages, and `AutopilotFeedback`
  no-ops when there is none.
