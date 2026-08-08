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
* **Bank** — follows the heading error, capped at 25°, given up as the speed decays (a banked turn
  needs `1/cos(bank)` times the lift of level flight, and a slow aircraft has none to spare), and
  forced to zero for landing and ground roll.

### Staying inside the envelope

Three rules exist purely to stop the aircraft leaving controlled flight. All three were added after
watching it do so, with numbers.

**Angle-of-attack limiter.** The commanded pitch is clamped to within `MAX_ANGLE_OF_ATTACK` (20°) of
the *current flight path angle*, not of the horizon. This is the important one.
`PlaneEntity#tickRotateMotion` scales both the wing lift and the rate at which the velocity vector
follows the nose by `d = 1 - min(1, aoa/60)²`, which is **exactly zero at 60° of angle of attack**:
the wings stop working and the aircraft falls with the nose up and no way out. The altitude cascade
then reads the growing sink rate as "too low" and asks for more nose-up — a divergence, not an
oscillation. Field telemetry of the failure: pitch +25° against a flight path of −79°, 104° of angle
of attack, 1.09 b/t of sink, 33 blocks above the ground, nose 188° off the commanded heading.
Referencing the clamp to the flight path makes the same rule serve as the recovery: deep in a stall
the flight path is steeply down, so the clamp forces the nose down with it and the aircraft flies out.

**The throttle is flown on horizontal speed.** It used to compare `getDeltaMovement().length()`
against the commanded speed, which counts the rate of *falling* as though it were progress. An
aircraft dropping out of a descent therefore reads as fast, so the loop closes the throttle, so it
gets slower and falls faster, so it reads faster still. That is a latch; it was observed sitting in
it at throttle 0. Airspeed along the wing is what keeps the wings working, so that is what is
regulated.

**Idle is an airbrake, not neutral.** `tickMotion` multiplies the whole drag polynomial by
`brakesMul = 5` at throttle 0. The descent and approach phases are allowed to use it — it is the only
way to slow down on an 8° slope, and without it the aircraft arrived on short final at 0.94 b/t
against a commanded 0.40, floated, and went around three times. Everywhere else `MIN_AIRBORNE_THROTTLE`
keeps one notch in. Below `MIN_FLYING_SPEED` (0.32 b/t) the lever goes fully open **on the spot**
rather than one notch per 5 ticks, which would take 25 ticks the aircraft does not have. And while
the aircraft is manoeuvring — bank over 8° or heading error over 15° — the loop may not reduce power
at all: a turn costs energy and is the last moment to be closing the throttle.

All tuning lives in one file: `autopilot/AutopilotConfig.java`.

### Thrust direction

The single largest defect found in this whole subsystem, and the cause of three separate reports.

`getTickPush` builds the engine thrust vector by rotating `(0, 0, push)` out of the body frame with
`transformPos`, and `transformPos` rotates by **`Q_Client`**. `Q_Client` is a client-side quantity:
on the server the only thing that ever writes it is `RotationPacket`, sent by the player flying the
plane. An aircraft with nobody aboard therefore kept whatever `Q_Client` it was created with for its
entire life, while `Q` — set from the freshly integrated attitude at the end of every `tick()` —
tracked reality.

**An unmanned aircraft was thrusting in the direction it was spawned facing, for ever.**

Straight-line flight looked flawless, which is why it survived so long: a strike run launched pointing
at its target accelerated 2.15 → 3.14 b/t without a wobble. Anything that turned fell apart. Measured
on a 200-block out-and-back, the aircraft came out of the 180° turnback at **0.36 b/t and stayed
pinned there at full throttle**, descending, until it reached the ground — with the engine pushing
backwards. That one frozen quaternion is the "spawned aircraft gradually loses speed" report, the
turnback stall and the failed landing descent, all three.

`PlaneEntity#transformPosPhysics` uses `Q` when there is **no player aboard** and `Q_Client`
otherwise, so a ridden plane is bit-for-bit unchanged. After the fix the same turnback holds
0.75 → 0.78 → 0.75 with no speed loss at all. `PlaneCollisions#upY` had already documented this exact
trap for the attitude calculation; the thrust vector had the same bug and nobody had joined the dots.

**A mob is not a pilot.** The predicate was originally `getControllingPassenger() == null`, and
`getControllingPassenger()` returns the first passenger if it is any `LivingEntity`. A cow or a pig
aboard therefore selected `Q_Client` — which only a player's `RotationPacket` ever writes — and
brought the whole frozen-thrust bug straight back. `LargePlaneEntity` and `CargoPlaneEntity` mount
any nearby non-player `LivingEntity` from their own `tick()`, so this happens by itself. Measured
with a pig aboard a 200-block out-and-back: commanded heading 236, nose reading 236, and the
aircraft flying the other way with the range to its target growing 380 → 1287 blocks and never
coming back. The test is now `getPlayer() == null`, which is exactly "is anyone's client
authoritative here". See `PHYSICS-AUDIT.md`, "Thrust does not go through it".

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
* **Right-click the air** — status report, including the blast setting.
* **Sneak + right-click the air** — cycle the spawn distance: 100 → 200 → 400 → 800, and the blast
  strength one step each time the distance wraps. See [The strike tool](#the-strike-tool).

The aircraft is launched already at attack speed with a booster fitted, cruises the run-in at
**100 blocks above the ground**, and only then dives — see [The attack run](#the-attack-run).

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
IDLE ─► TAXI ─► TAKEOFF ─► CLIMB ─► CRUISE ─► DESCENT ─► APPROACH ─► FINAL ─► FLARE ─► ROLLOUT ─► IDLE
                              │          ▲         │  ▲                 │
                              │          └─ HOLD ◄─┘  └──── GO_AROUND ◄─┘
                              └──► STRIKE (one-way attack run, no landing)
```

| Mode | What it does |
|---|---|
| `TAXI` | Ground steering from the parking spot to the departure threshold at 0.20 speed, elevator neutral |
| `TAKEOFF` | Full power, ground steering on the runway heading, elevator aft, rotate at 0.35 speed, wings level |
| `CLIMB` | Climb to cruise altitude on the first waypoint's bearing |
| `CRUISE` | Fly waypoints, terrain-following, advancing on arrival within 30 blocks |
| `STRIKE` | Hold 100 above the ground, then dive at the target — see [The attack run](#the-attack-run) |
| `DESCENT` | Fly to the initial approach fix, 300 blocks out at circuit height |
| `APPROACH` | Track the extended centreline and capture the glide slope |
| `FINAL` | As above, plus the landing gates are enforced |
| `FLARE` | Nose up 4°, throttle closed, wings level |
| `ROLLOUT` | Throttle closed, ground steering, until the aircraft stops |
| `HOLD` | Orbit the approach fix at circuit height until the runway frees up |
| `GO_AROUND` | Full power, climb to circuit height, then rejoin via `HOLD` |

Mode changes are announced to the owning player on the action bar; surveys and confirmations go to
chat.

### The attack run

Three things decide whether a strike arrives: the speed it is launched at, how high it runs in, and
when it stops holding that height.

**Speed.** The aircraft is spawned with a booster fitted, the throttle at 10 and its attack speed
already in the velocity vector, pointed at the target. Accelerating from a standstill instead costs
the first seconds of the run and makes the aircraft sag towards the ground while it does it. The
speed ceiling is set through `PlaneEntity#setMaxSpeed`, which is not a limiter but the point the
thrust fades out at: `tickMotion` scales the push by `1 − speed / (maxSpeed × 10 × (push + 0.05))`,
so raising it from 2.0 to 3.0 moves the balance against the drag curve from about 2.0 to about 2.8
blocks/tick. Measured over an 800-block run with no chunk force-loading at all, the speed rises
monotonically 2.15 → 2.73 → 2.82 → 2.87 on the run-in and 3.14 in the dive, and never falls.

A strike run is the one profile that was *always* fast, and for an unfortunate reason: it is flown
in a straight line. See [Thrust direction](#thrust-direction) — until recently an unmanned aircraft
thrusted in the direction it was spawned facing, so straight-line flight was perfect and everything
that turned quietly lost all its speed.

**Height.** The run-in is flown at 100 blocks *above the ground*, not above the target. This is the
whole fix for aircraft that used to end up stuck in a tree: a glancing hit on a canopy blocks only
the small vertical part of the motion, so the impact registers as a gentle landing — correctly, that
is what it is — and the aircraft settles into the branches at walking pace, undamaged, and stays
there. Nothing on the way in reaches 100 blocks up.

**When to dive.** Not at a fixed distance. The run-in is held until the target sits
`STRIKE_DIVE_ANGLE` (32°) below the nose, so the dive point follows the height actually being flown
— about 160–200 blocks out from 100 up. Past that point the nose is aimed *straight at the target*
rather than at an altitude, which makes the commanded angle `atan(height / distance)`: nearly
constant for most of the dive and steepening towards vertical over the last few blocks.

```
 alt          ______________________________
              run-in, 100 above ground       \
                                              \__     atan(h/d) — steepens as d → 0
                                                 \_
                                                   \|  target
              |------------------------------|-----|
              400 blocks                    ~180    0
```

That shape is not decoration. An earlier build dived from a fixed 350 blocks out at a 35-block
run-in — a 6° glide starting almost immediately after launch, which is what "descends too early"
looks like. Tracking an altitude in the terminal phase does not work either: the cascade turns
altitude error into vertical speed and then into a flight path angle, and the aircraft arrives over
the target still high, going in 54–57 blocks long (measured twice). Aiming the nose is
self-correcting — the further behind the profile it falls, the steeper the dive it commands.

**Fusing.** A 3-block sphere is too small at 3 blocks/tick, so the fuse radius scales with speed and
is backed by closest-point-of-approach detection: if the range starts opening again inside 24
blocks, the aircraft is already past and detonates. And because a strike aircraft carries a warhead,
it also goes off wherever it stops — touching the ground or dropping below 0.35 blocks/tick on the
run counts as an impact. Without that last rule a run that clips something leaves an intact,
permanently stationary aircraft parked in the scenery with the autopilot still running.

Measured over three runs: 400 blocks, launched at 100 above ground, **3-6 blocks from the aimpoint**.
That is the tick discretisation, not the guidance - at 3.13 blocks/tick the aircraft cannot be
sampled any closer than that to the aimpoint, and the detonation covers the difference.

---

## 3a. Airfield to airfield: the scripted sortie

```
/autopilot flight <from> <to>
```

Two registered airfields by name; one aircraft flies the whole thing:

**Parked.** The aircraft is created stationary, on the ground, throttle shut, on an apron beside the
departure runway — `width/2 + 4` blocks off the centreline and 12 blocks back from the threshold.
If the ground alongside is not level with the runway (within 2 blocks) the apron is abandoned and it
parks on the centreline behind the threshold instead, because the surveyed strip is the one piece of
ground known to be flat. **No initial velocity.** The velocity a strike is launched with is an
air-launch and stays exclusive to strikes; a runway departure has a runway.

**Taxi.** `TAXI` steers to a lineup point at the threshold at `TAXI_SPEED` (0.20 b/t), then stops
chasing the point and simply holds the runway heading until it is within `TAXI_ALIGNED_ERROR` (8°) —
chasing a point the aircraft is nearly on top of makes the nosewheel hunt. Throttle is capped at 3
so it creeps rather than charges. `TAXI_TIMEOUT` (900 ticks) departs anyway rather than circling a
threshold for ever.

**Departure.** Along the *surveyed* runway, on its real heading, from its real threshold.

**Cruise.** Altitude chosen from the terrain sampled along the whole great-circle leg between the two
fields, plus 60.

**Arrival.** The existing approach machinery, onto the destination's surveyed runway, with the end
chosen by `Airfield#bestEnd` (approach obstacles, ties to uphill).

**Report.** Every phase change that matters prints to the console, and the flight ends with one
assertable line — `Plane #7 landed at airfield-2/36, 2655, -60, -12 (4 blocks down the runway).`

### Testing the arrival on its own

```
/autopilot inbound <x y z> <airfield>
```

Launches in the air at that point and flies a genuine **one-way** sortie into the named field. This
is something `route` cannot express: a route is always out-and-back, and it picks its landing field
by proximity to where it *departed*, so an aircraft starting far away always ended in an improvised
field landing instead of an approach to the runway it was sent to. `inbound` also lets the approach
be iterated on without flying the departure first.

### The ground-phase pitch trap

Both ground phases were commanding the elevator the wrong way, and neither had ever been exercised —
routes and strikes are both launched in the air, so nothing had ever departed from a standstill.

A parked plane rests at `PlaneEntity#getGroundPitch`, **+5°**. Commanding a level attitude therefore
leaves the pitch controller permanently holding nose-down, and `PlaneEntity#tickOnGround` reads a
negative pitch input as **reverse thrust** (`push = -groundPush`). Measured: the aircraft taxied
smoothly *backwards* away from the runway at 0.13 b/t, facing the right way the whole time.

* `TAXI` holds the elevator strictly **neutral** — no reverse, no boost.
* `TAKEOFF` holds it **aft** for the whole roll, which is also how a real departure is flown: a
  positive input levels the aircraft on its wheels (removing the static-friction penalty that divides
  thrust by five while the nose sits at +5°) and guarantees at least `groundPush`. It cannot rotate
  early, because `tickOnGround` returns `speedingUp = false` below take-off speed and `tick()` only
  runs `tickPitch` when it is true. Before: the roll stuck at 0.13 b/t against a 0.35 rotate speed.
  After: 0.27 → 0.58 → 0.63, rotate, lift off.

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
/autopilot strike <x y z> [distance] [bearing] [blast] [blocks] [fire]  launch an attack run
/autopilot route <x y z> <x y z>                 fly A -> B -> A and land
/autopilot flight <from> <to>                    full sortie between two registered airfields
/autopilot inbound <x y z> <airfield>            one-way arrival into a named airfield
/autopilot survey <x y z> <x y z>                survey a runway between two thresholds
/autopilot airfields                             list registered airfields
/autopilot status                                full telemetry for every autopilot aircraft
/autopilot stop                                  stop every autopilot aircraft in this dimension
```

`flight` and `inbound` take airfield **names** (tab-completed from the registered list, quoted
because names contain a hyphen), so they need no block-position argument at all and cannot be
refused for pointing at unloaded ground — which is the normal case, not an edge case, since both
runways are usually nowhere near a player.

`bearing` is the compass direction the attack run comes in *from*, 0–359. Omit it and the bearing is
derived from wherever the command was issued (the player, or the console's world-spawn origin); if
that origin sits on top of the target it falls back to a fixed due-south run-in. Given explicitly,
the whole flight is deterministic and repeatable, which is what makes headless testing useful.

### The warhead

The last three arguments of `strike` decide what happens when the aircraft arrives. They are
positional and progressively optional, so setting a later one means giving the earlier ones — which
a repeatable test wants to do anyway.

| Argument | Range | Default | What it does |
|---|---|---|---|
| `blast` | 0.0 – 16.0 | **4.0** | Vanilla explosion strength. The damage radius is `2 × blast`. |
| `blocks` | true / false | **true** | Whether the blast breaks blocks. |
| `fire` | true / false | **false** | Whether it leaves fires behind. |

The defaults are exactly what a plane has always done, so `/autopilot strike <x y z>` is unchanged.

**Why 16 is the ceiling.** Not taste — cost. `ServerExplosion` casts 1352 rays and then drops every
block it removed, so the work grows with the volume of the crater, and a bound is what stops a
mistyped argument stalling the server or eating a build. 16 is four times TNT: a 32-block damage
radius. Measured on the superflat rig, counting the blocks actually removed from the three
destructible surface layers:

| Blast | Blocks destroyed | Fires placed |
|---|---|---|
| 4.0 (default) | 89 | 0 |
| 16.0 | 999 | 0 |
| 8.0, `blocks=false`, `fire=true` | **0** | 145 |

The server stayed comfortable at the ceiling: average 1.3 ms per tick, P95 2.0 ms, P99 6.1 ms
against the 50 ms budget. Out-of-range values are rejected by the argument parser before anything is
spawned — `Float must not be more than 16.0: found 99.0` — and `Blast`'s canonical constructor clamps
as well, so a hand-edited save cannot smuggle a larger one back in through the flight-plan codec.

**`blocks=false` is worth more than a bigger number.** It selects
`Level.ExplosionInteraction.NONE` instead of `TNT`, which maps to `Explosion.BlockInteraction.KEEP`:
entities are still hurt and thrown, and not one block moves. That is what makes a strike testable on
a build you care about.

**Fire is independent of block damage**, which is not obvious and is worth stating because it is the
combination people want. `ServerExplosion#explode` computes the affected positions *before* it
consults the interaction, and calls `createFire` outside the `interactsWithBlocks()` guard — so
`blocks=false fire=true` really does place fire and destroy nothing. Verified above: 145 fires, zero
blocks removed. Vanilla's `Level#explode` overload that takes a fire flag covers this completely; no
third-party explosion code was needed or used.

**Persistence.** The warhead is part of `FlightPlan` and goes through its codec, so it survives a
save — verified by restarting the server with a flight in progress and reading
`{breaks_blocks: 0b, fire: 1b, power: 12.0f}` back off disk. A blast equal to the default is omitted
from the NBT entirely (`optionalFieldOf` drops it), so plans written by older builds load unchanged
and new plans with a default warhead are byte-identical to old ones. Note the standing exception in
§6: **strike flights are deliberately not persisted at all**, so in practice a non-default warhead
only reaches disk on a route or sortie.

### The strike tool

The item carries a blast **strength** only, cycled by sneak + right-click: the spawn distance
advances on every press, and the blast advances one step each time the distance wraps back to the
start, through 4.0 → 8.0 → 16.0 → 1.0. Both values are printed on every press and both are on the
tooltip. A held item offers exactly one spare gesture, and spending it on a three-way cycle of
independent settings would be harder to use than not having them there at all — so `blocks` and
`fire` stay command-only, and the tool always breaks blocks and never sets fire, as it always has.

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
nobody keeps loaded simply stop ticking**. Aircraft therefore carry a rolling
`TicketType.ENDER_PEARL` ticket. Two details in that sentence were wrong in the first version and
both cost whole flights:

**The radius is not the bubble.** `TicketStorage#addTicketWithRadius(type, pos, r)` gives the centre
chunk level `33 - r`, and the level rises by one per chunk outwards, while `ChunkLevel#isEntityTicking`
needs level ≤ 31. A ticket of radius `r` therefore ticks entities only within `r - 2` chunks of the
centre — vanilla's ender-pearl radius of 2, which this copied, produces an entity-ticking area of
**exactly one chunk**. That is fine for a pearl, which the player re-tickets, and useless for an
aircraft covering 3 blocks a tick: it leaves that chunk in five ticks. The radius is now
`CHUNK_TICKET_RADIUS = 4`, which ticks entities two chunks (32 blocks) in every direction.

**The renewal cannot come from the aircraft.** Renewing the ticket from `PlaneAutopilot#tick` is
circular: an aircraft that slips out of the entity-ticking area stops ticking, so it stops renewing
the ticket that would bring it back, and it hangs in the air for ever. Measured on the rig before the
fix, an 800-block strike froze permanently the instant it crossed out of the force-loaded region,
keeping its velocity and its position to the decimal for the rest of the run. Renewal now runs from
the **server level tick**, over the strong references in `AutopilotRegistry`, so a frozen aircraft is
picked up and thawed.

A second ticket is placed `CHUNK_TICKET_LEAD_TICKS` (20 ticks) of travel **ahead** of the aircraft.
That is not an optimisation: without it the aircraft permanently flies at the edge of its own loaded
area, so `TerrainScanner` reads `UNKNOWN_HEIGHT` for most of its forward profile and terrain
following silently degrades to "hold altitude".

The ticket still times out after 40 ticks, so nothing leaks when the aircraft is destroyed. Verified
on the rig: an 800-block strike and a 2000-block airfield-to-airfield sortie both complete with
**zero** `forceload` and no player anywhere near either end.

### Command arguments and unloaded ground

`strike` and `route` take their positions with `BlockPosArgument.getBlockPos`, **not**
`getLoadedBlockPos`. A destination hundreds of blocks away is by definition outside anyone's
simulation distance, and demanding a loaded position made the command reject exactly the flights it
exists to fly, with `That position is not loaded`. The aircraft loads its own chunks, so the
destination does not have to be resident when the order is given.

`survey` deliberately keeps `getLoadedBlockPos`: a survey measures real blocks — surface heights,
width, slope, roughness — so surveying unloaded ground would register a runway made of nothing.
Refusing is the right answer there; go and stand on the runway.

### How many aircraft are flying

`RunwayOccupancy.activeCount()` is **derived** from the live set in `AutopilotRegistry`, recounted on
every call. It used to be a `static int` bumped on activation and decremented on release, which
leaked a slot for every aircraft that went away without running its release path — i.e. on every
crash, the most common ending. A live server was seen reporting `19/24 autopilot aircraft active, 2
in this dimension`: five launches from refusing everything, with nothing but a restart to clear it.
Only server-side entities are ever registered, so the shared client/server JVM of a single-player
world cannot double-count either.

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
* **Bank direction is cosmetic, but bank angle is not free.** Turns are produced by the yaw control,
  as in the base game; bank is commanded only so the aircraft looks right. If it banks the wrong way
  in a turn, flip the sign of `desiredRoll` in `PlaneAutopilot#applyControls` — it will not change the
  flight path. The *magnitude* does matter, though: a banked aircraft yawing hard couples into pitch
  through the quaternion, which is why bank is surrendered at low speed.
* **Improvised landings are rough** by nature. Survey a runway for anything reliable.
* **Terrain in ungenerated chunks reads as unknown** and is skipped, so an aircraft flying into
  never-visited terrain holds altitude rather than reacting to ground it cannot see. The chunk
  ticket keeps a bubble loaded around the aircraft itself, which covers the normal case.
* **Route legs are fixed at 2** (out and back) from the wand. Use `/autopilot flight` or
  `/autopilot inbound` for a one-way sortie, or the `FlightPlan` API for more.
* **Taxi is a straight line to the threshold.** There is no taxiway network and no obstacle
  avoidance on the ground: the aircraft steers directly at the lineup point. On a surveyed field with
  a sane parking apron that is enough; it will not thread a hangar.
* **No player is ever required.** Aircraft spawn, fly, land, save and load with no player involved;
  an owning player is only an optional recipient for progress messages, and `AutopilotFeedback`
  no-ops when there is none.
