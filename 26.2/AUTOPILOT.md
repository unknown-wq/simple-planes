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
  chatter, with two exceptions at the ends: below `MIN_FLYING_SPEED` the lever slams fully open on
  the spot, and more than `THROTTLE_CUT_EXCESS` (0.20 b/t) above the commanded speed it shuts to its
  floor in one step. Both exist because a notch every 5 ticks is 50 ticks from end to end, and the
  aircraft does not always have 50 ticks. See [Flying the speed it was told](#flying-the-speed-it-was-told).
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
`brakesMul = 5` at throttle 0. It is the only way this airframe slows down, so `MIN_AIRBORNE_THROTTLE`
is a floor on *needed* power only: it applies while the aircraft is at or below its commanded speed
and is dropped the moment it is above it. Below `MIN_FLYING_SPEED` (0.32 b/t) the lever goes fully
open **on the spot** rather than one notch per 5 ticks, which would take 25 ticks the aircraft does
not have. And while the aircraft is manoeuvring — bank over 8° or heading error over 15° — the loop
may not *reduce* power: a turn costs energy and is the last moment to be closing the throttle.

All tuning lives in one file: `autopilot/AutopilotConfig.java`.

### Flying the speed it was told

Every route, sortie and inbound aircraft carries a booster and `setMaxSpeed(3.0)`. That is capability,
not a command — but two rules in the throttle loop turned it into one, and both had to go.

**The manoeuvre rule raised the floor to full power.** "Do not reduce power in a turn" was written as
`floor = cmdMaxThrottle`. On the unboosted airframe it was written for, that was the same thing as
holding station at 5. On a boosted one it is a demand for throttle 10. Measured on an argument-free
sortie: the 93° turn off the departure runway put the lever on 10 and the aircraft climbed away at
**2.18 b/t against a commanded 0.70**, then took another 45 ticks to wind back down. The rule now
holds whatever the loop had already chosen, which is what it always meant.

**The floor itself was a cruise setting.** `tickMotion` fades thrust out towards
`maxSpeed × 10 × (push + 0.05)`, which at throttle 1 and `maxSpeed = 3.0` is 1.6875 — and the drag
curve balances that at about 1.0 b/t. So `MIN_AIRBORNE_THROTTLE = 1` *was* the minimum speed of the
aircraft. Measured: a cruise commanded at 0.80 sat at **0.93 for a whole 2000-block leg** with the
lever pinned on its floor, with no way to go slower. The floor now applies only while the aircraft is
short of its commanded speed.

Each throttle notch has its own equilibrium on this airframe, and that is what the loop is choosing
between:

| notch | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| settles at (b/t) | 1.01 | 1.35 | 1.59 | 1.79 | 1.98 | 2.15 | 2.33 | 2.49 | 2.66 | 2.82 |

Measured on the rig after the fix, straight and level:

| commanded | before | after | lever |
|---|---|---|---|
| 0.40 | 0.93 | **0.43** | dithering 0/1 |
| 0.50 | 0.93 | **0.52** | dithering 0/1 |
| 1.20 | — | **1.23** | 1 |
| 2.60 (the default) | — | **2.58–2.61** | dithering 8/9 |
| 2.80 (the maximum) | — | **2.78–2.83** | pinned at 10 |

### The default is fast

`CRUISE_SPEED` is **2.60 b/t**, not the old 0.80. The booster was fitted to route aircraft so it
would be used.

It is 2.60 rather than the airframe's absolute 2.80 because of the table above: notch 9 settles at
2.66 and notch 10 at 2.82, so a commanded 2.80 sits on the stop with nothing left to regulate with —
the number is accepted rather than flown. 2.60 sits inside the band, so the aircraft holds the speed
it was given and keeps a notch in hand for a climb or a turn. Both fly and both land; only one of
them is being controlled.

The climb is flown at `max(CLIMB_SPEED, cruise speed)`. Climbing at a fixed 0.70 and then being told
to fly 2.60 means spending the climb braking against a speed you are about to be asked for again, and
the climb rate is capped by `MAX_CLIMB_RATE` rather than by thrust, so there is nothing to trade.

`route`, `flight` and `inbound` all take an optional trailing `speed` in blocks per tick, clamped to
0.40–2.80 by `AutopilotConfig.clampCruiseSpeed`. The range is deliberately wider than the argument
accepts errors for: asking for 9 gets 2.80 and a launch line saying so, rather than a syntax error.

### Slowing down in time

A fast cruise is only useful if the aircraft can still land at the end of it, and the approach is
tuned around arriving at `APPROACH_SPEED`. `AutopilotMath.speedSchedule` therefore sheds the energy
*before* the descent, over the distance the drag curve needs, and `PlaneAutopilot.cruiseSpeedSchedule`
applies it on the final leg only — an intermediate waypoint is a turn, not an arrival.

The model integrates `tickMotion` exactly at throttle 0: **2.80 → 0.50 b/t is 158 blocks and 124
ticks**, which a straight tick-loop simulation agrees with to the block. Two things it does not model,
both since corrected:

* **It assumes the throttle is already shut.** From a cruise at throttle 10 the lever needs 50 ticks
  to get there, and 50 ticks at nearly cruise speed is another ~110 blocks. Measured straight-in from
  2.80, the modelled 158 came out as **270 blocks / 180 ticks**, and the aircraft was still doing
  1.4 b/t at the waypoint it was braking for. Hence `THROTTLE_CUT_EXCESS`.
* **It assumes level flight.** The real bleed is flown while giving up cruise altitude, and that
  descent puts energy back in. Measured on a descending segment: 1.49 → 0.49 b/t took 102 blocks
  against a modelled 76, a ratio of 1.34 — which is `DECELERATION_MARGIN` (1.35) almost exactly.

The claim that "with the floor at 1 the same deceleration needs 800 blocks" is **wrong**, and wrong in
a way worth recording: 800 is the drag-only figure with the thrust left out. With one notch still in,
the boosted airframe does not decelerate to `APPROACH_SPEED` slowly — it does not get there at all,
because throttle 1 balances the drag curve at ~1.0 b/t and simply holds it.

### Sequencing waypoints at speed

`WAYPOINT_ARRIVAL_RADIUS` was a fixed 30 blocks. An aircraft cannot turn inside its own turn radius,
which is `v / yawRate` with `tickYaw` clamping the yaw rate to 2.5°/tick: 18 blocks at 0.80 b/t, but
**64 blocks at 2.80**, so a fast aircraft physically could not get within 30 of a waypoint and would
orbit it instead of sequencing. The radius is now `max(30, v / yawRate)`. Verified on a 3000-block
out-and-back at 2.80: the aircraft advanced its waypoint and rolled into the turnback in one pass,
`legs=1/2`, no orbit.

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
| **Helipad Marker** (`helipad_tool`) | 2 gold ingots diagonally + compass | Survey and register helicopter landing pads |

All four are in the Simple Planes creative tab. The helipad marker is a separate item rather than a
mode of the runway tool, and §4h argues why.

### Plane Strike Tool

* **Right-click a block** — spawns an aircraft the configured distance away (default 400 blocks, on
  the far side of you so it runs in past you) and sends it at that block at full throttle.
* **Right-click the air** — status report: distance, warhead and run-in bearing.
* **Sneak + right-click the air** — cycle the spawn distance: 100 → 200 → 400 → 800, and the blast
  strength one step each time the distance wraps. See [The strike tool](#the-strike-tool).
* `/autopilot tool <distance> [bearing] [blast] [blocks] [fire]` — write the full set of settings,
  including "do not break blocks", "set fire" and a pinned run-in bearing, onto the tool in hand.

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

Two modes, switched with **sneak + right-click the air**; the tooltip says which one it is in.

*Survey mode:*

* **Right-click one threshold, then the other** — surveys the strip between them and registers it.
* **Sneak + right-click a block** — cancel a half-marked runway.

*Parking mode:*

* **Right-click a block** — mark it as a parking spot on the nearest airfield (§4b).
* **Sneak + right-click a block** — remove the marked spot nearest that block.

**Right-click the air** in either mode browses the airfields, nearest first.

**Click anywhere on each end** — a corner is fine. The survey finds the middle of the strip for
itself; see [The centreline is the middle of the strip](#the-centreline-is-the-middle-of-the-strip).
The survey reports:
A survey that registers a **new** runway switches the tool into parking mode by itself, because a
runway with nowhere to park is not a finished airfield — see
[A surveyed runway is not finished until a stand is marked](#a-surveyed-runway-is-not-finished-until-a-stand-is-marked).

Mark the two **centreline ends** (not opposite corners), which is what makes the runway heading
exact. The survey reports:

* length, measured width, slope in degrees
* both thresholds with their elevation and compass heading, and how far the thresholds had to move
  sideways to reach the middle of the strip
* both **runway designators** (`09/27` style, derived from the true heading)
* surface roughness — the standard deviation of the centreline surface height, so `0.00` is a
  perfectly flat strip
* obstacles in each approach funnel: 10-block segments of the funnel with something poking above the
  glide slope, out to 200 blocks
* the preferred landing direction
* warnings for a short runway or a steep slope

---

## 3. The state machine

```
IDLE ─► PARKED ─► TAXI ─► TAKEOFF ─► CLIMB ─► CRUISE ─► DESCENT ─► APPROACH ─► FINAL ─► FLARE ─► ROLLOUT ─► TAXI_IN ─► IDLE
                             │          ▲         │  ▲                 │
                             │          └─ HOLD ◄─┘  └──── GO_AROUND ◄─┘
                             └──► STRIKE (one-way attack run, no landing)
```

| Mode | What it does |
|---|---|
| `PARKED` | Stationary on the parking spot, throttle shut, running the departure clock down and then asking for the runway. Which end it will use, and the turn onto course, were decided before it was put there — see [4e](#4e-deciding-the-departure-before-the-aircraft-rolls) |
| `TAXI` | Ground steering from the parking spot to the departure threshold at 0.20 speed, elevator neutral |
| `TAXI_IN` | Ground steering from where the aircraft stopped, off the strip and on to a marked stand — see [Taxiing in](#4d-taxiing-in-runway--stand) |
| `TAKEOFF` | Full power, ground steering on the runway heading, elevator aft, rotate at 0.35 speed, wings level |
| `CLIMB` | Climb to cruise altitude on the first waypoint's bearing |
| `CRUISE` | Fly waypoints, terrain-following, advancing within `max(30, turn radius)`, bleeding speed for the arrival — and, on the last leg into a named airfield, handing over to `DESCENT` at the arrival decision range rather than overhead ([4d](#4d-deciding-the-arrival-at-range-and-then-flying-it)) |
| `STRIKE` | Hold 100 above the ground, then dive at the target — see [The attack run](#the-attack-run) |
| `DESCENT` | Fly to the initial approach fix, 300 blocks out at circuit height |
| `APPROACH` | Track the extended centreline and capture the glide slope |
| `FINAL` | As above, plus the landing gates are enforced |
| `FLARE` | Nose up 4°, throttle closed, wings level |
| `ROLLOUT` | Throttle closed, ground steering, until the aircraft stops — then either the taxi in or the end of the flight |
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
/autopilot flight <from> <to> [speed] [delay <seconds>]
```

Two registered airfields by name; one aircraft flies the whole thing:

**Parked.** The aircraft is created stationary, on the ground, throttle shut, on a **marked parking
spot** if the field has one (§4b) and otherwise on an apron derived from the survey — `width/2 + 4`
blocks off the centreline and 12 blocks back from the threshold. If the ground alongside is not level
with the runway (within 2 blocks) the apron is abandoned and it parks on the centreline behind the
threshold instead, because the surveyed strip is the one piece of ground known to be flat. **No
initial velocity.** The velocity a strike is launched with is an air-launch and stays exclusive to
strikes; a runway departure has a runway.

It is spawned one block above the parking surface and settles onto it. Verified on the rig against a
spot marked at `670, -61, 10`: the launch line reads `parked at airfield-1 (671, -59, 11)` and the
first `status` poll reads `pos=671,-60,11 agl=0` — the top of the marked block, not inside it. The
half-block is the difference between the block a player clicked and
`TerrainScanner.surfaceHeight`, which reports the first *free* block; both `Airfield#pointA` and the
parking validation use that same convention, so they are directly comparable.

**Waiting.** `PARKED` is where the aircraft sits until it is allowed to move, and there are two
separate gates — see [Departure delay and the runway gate](#departure-delay-and-the-runway-gate).

**Taxi.** `TAXI` steers to a lineup point at the threshold at `TAXI_SPEED` (0.20 b/t), then stops
chasing the point and simply holds the runway heading until it is within `TAXI_ALIGNED_ERROR` (8°) —
chasing a point the aircraft is nearly on top of makes the nosewheel hunt. Throttle is capped at 3
so it creeps rather than charges. `TAXI_TIMEOUT` (900 ticks) departs anyway rather than circling a
threshold for ever. The run *to* the threshold is bounded by the same 900 ticks and by the arrival
taxi's stall detector (`TAXI_IN_STALLED_SPEED` for `TAXI_IN_STALLED_TICKS`), and it ends the flight
where it stands instead of departing: an aircraft that never reaches the threshold is holding a
reservation the rest of the field is queued behind, and the runway gate has no timeout of its own.

**Departure.** Along the *surveyed* runway, on its real heading, from its real threshold.

**Cruise.** Altitude chosen from the terrain sampled along the whole great-circle leg between the two
fields, plus 60.

**Arrival.** The existing approach machinery, onto the destination's surveyed runway, with the end
chosen by `Airfield#bestEnd` (approach obstacles, ties to uphill).

**Report.** Every phase change that matters prints to the console, and the flight ends with one
assertable line — `Plane #7 landed at airfield-2/36, 2655, -60, -21 (18 blocks down a 66-block
runway, 28% used).`

### Departure delay and the runway gate

`PARKED` holds the aircraft on its spot until two things are true, in order.

**The clock.** `delay <seconds>` on the command, stored on the flight plan as ticks. It runs down
whatever else is happening and is purely what was ordered.

**The runway.** Only once the clock has run out does the aircraft ask `RunwayOccupancy` for the
departure field, and it does not move until it has it. Asking earlier would reserve a strip for an
aircraft that is not going to use it for another five minutes, which is worse than not reserving one
at all.

**A departure now reserves the runway**, which is new — it used to reserve nothing, so two sorties
out of one field would taxi onto the same threshold and the tower board printed `FREE` for a strip
with an aircraft rolling down it. The reservation is taken *before* the mode changes, so the aircraft
is never in `TAXI` without holding the runway, and it is released on the `TAKEOFF → CLIMB` transition
— `TAKEOFF_CLEAR_HEIGHT`, 10 blocks above the ground and past the far threshold. Measured on the rig,
two sorties ordered 3 s apart out of `airfield-1`:

```
Plane #56 cleared to taxi at airfield-1/36 after 1s on the parking spot.
Plane #57 holding on the parking spot at airfield-1: runway occupied by #56.
  #56 taxi    pos=670,-60,10 ...          dep=airfield-1/36
  #57 parked  pos=639,-60,4  spd=0.00 ... dep=airfield-1/36 wait=runway
  … ten seconds of #57 sitting still while #56 taxis, lines up and rolls …
Plane #57 cleared to taxi at airfield-1/36 after 10s on the parking spot.
  #56 climb   pos=657,-47,-66 agl=13 ...   ← the same second: #56 left TAKEOFF, #57 got the runway
```

**It cannot leak.** Nothing here is a new lifetime to get wrong: `RunwayOccupancy` validates every
reservation against `PlaneAutopilot#holdsRunway` rather than trusting its map, and that method is now
true for a departure exactly while the mode is `TAXI` or `TAKEOFF`. An aircraft that is killed,
crashes, despawns or is stopped therefore stops holding the runway without anything having to notice
— on top of the existing `releaseAll` on `stop` and on `PlaneEntity#remove`. Verified: an aircraft
killed mid-taxi while holding `airfield-1` leaves the board reading `airfield-1  36/18  FREE`.

**There is no timeout on the runway gate, and that is deliberate.** Rolling anyway after some number
of failed polls would put an aircraft onto a runway that is genuinely occupied, which is the one
thing the gate exists to prevent. A departure waits for as long as it takes; `/autopilot tower` is
what makes that visible. (The *taxi* keeps its `TAXI_TIMEOUT` — by then the aircraft already owns the
strip and the only question is whether it is straight on it.)

**No order between waiting aircraft.** A parked aircraft polls every `DEPARTURE_POLL_INTERVAL` (20)
ticks on its own tick counter, which is the same rule and the same interval an arrival in `HOLD`
uses, so departures and arrivals compete on equal terms and neither can poll the other out simply by
asking more often. Whoever polls a free runway first takes it. With two aircraft waiting the order is
unspecified and a long-waiting aircraft can be passed over — the tower board says so in as many words
rather than printing a queue number that means nothing.

### Why the delay is a keyword argument

`/autopilot flight <from> <to> [speed] [delay <seconds>]`, not `/autopilot flight <delay> <from> <to>`.

The delay is the first thing anyone thinks of, but it cannot be the first argument: `flight` already
takes two strings, so a leading positional would reinterpret every existing
`/autopilot flight "airfield-1" "airfield-2"` as a delay and one airfield. A *trailing* positional is
no better — it would be reachable only by also giving a speed, so "wait 30 seconds" would mean
"wait 30 seconds and, by the way, here is a cruise speed I did not care about". The keyword branches
off both the two-argument and the three-argument forms, so all four of these parse and the first two
are byte-identical to what they were:

```
/autopilot flight "airfield-1" "airfield-2"
/autopilot flight "airfield-1" "airfield-2" 2.60
/autopilot flight "airfield-1" "airfield-2" delay 30
/autopilot flight "airfield-1" "airfield-2" 2.80 delay 15
```

Seconds, because that is what a person thinks in; the plan stores ticks. Bounded at
`MAX_DEPARTURE_DELAY_SECONDS` (3600) by the argument parser — `Integer must not be more than 3600:
found 99999` — because a parked aircraft still holds one of the 24 autopilot slots and a chunk bubble
for the whole wait, so a mistyped delay is indistinguishable from a launch that failed.

The plan field is `optionalFieldOf("departure_delay", 0)`, so a plan with no delay writes no key at
all and is byte-identical to one written before this existed. Verified with `data get`:
`{kind: "route", …, max_legs: 1}` with no delay ordered, and `…, departure_delay: 300, …` for
`delay 15`.

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

**The landing gates.** Below 30 blocks above the touchdown point, the approach must satisfy *all* of:

* heading within 10° of the runway heading — this is the "no landing at an angle" rule
* lateral offset within half the runway width (minimum 10 blocks) — the same half-width the
  roll-out judges the stopped aircraft against, so a landing the gates clear is one the report
  will call a landing
* bank within 12° — `PlaneEntity#causeFallDamage` explodes the plane above 45°, so this matters
* sink rate under 0.45 blocks/tick

Any failure triggers **`GO_AROUND`**. After 3 go-arounds the aircraft tries the opposite runway end;
beyond that it commits to the landing rather than orbiting forever.

**Not flying into hills.** Two independent checks:

1. The heightmap terrain profile (§6) keeps a 22-block clearance on every mode that terrain-follows.
2. On approach, a genuine voxel raycast (`Level.clip`) down the corridor from the aircraft to the
   runway aiming point, once a second above 15 blocks AGL. Unlike the heightmap this also catches
   overhangs. A blocked corridor triggers a go-around.

### The centreline is the middle of the strip

The user's report was that the aircraft "always takes off from the exact first point marked with
right-click and lands on that point or on the opposite one, instead of down the middle of the runway
end" — *"самолет всегда взлетает из крайней первой точки что отмечена ПКМ и садится туда же или на
противоположную, а не по середине края"*. That was exactly what the code did: `Airfield.survey` took
the two clicked blocks as the thresholds, literally, and **every number the aircraft flies hangs off
the threshold** — the take-off lineup, the parking apron, the touchdown aim point, the glide slope,
the lateral offset on the approach and the landing gates. Mark an edge and all of them move to the
edge together.

Nobody clicks the middle of a runway end, because there is nothing there to click. You stand on a
corner, where you can see what you are marking.

Measured on the rig, both ends clicked on the left edge of a 13-wide plinth running from x = −6.0 to
x = 7.0 (true middle x = 0.5):

| | before | after |
|---|---|---|
| stored thresholds | `-6 -57 0` / `-6 -57 -160` | `0 -57 0` / `0 -57 -160` |
| parked and rolling, whole take-off | x = **−5.50** | x = **0.50** |
| touchdown and stop | x = **−5.8** | x = **0.20** |
| off the middle of the strip | **6.3 blocks**, on a strip 6.5 blocks wide either side | **0.3** |
| lateral tracking error (`lat` in the trace) | −0.2 | −0.2 |

That last row is the point. The aircraft was never flying badly; it was flying a perfect approach
onto a line the survey had put on the runway edge, with the outboard wing over the drop-off.

**Each end is centred on its own cross-section.** The survey measures how far the strip reaches
either side of each clicked point, perpendicular to the current centreline, and moves that end to the
middle of what it finds — then re-derives the heading and does it again, up to
`SURVEY_CENTRING_PASSES` (3) times, because moving an end sideways changes the perpendicular.

The obvious alternative was to keep the clicked heading exactly and shift both ends by one common
amount. It was tried first and it is wrong on the case that matters most: the two corners easiest to
reach at the two ends of a strip are usually on *opposite* sides, and averaging +6 and −6 gives 0, so
the centreline stays diagonal — the very arrival being complained about. Measured with the near-left
and far-right corners of the same 160×13 strip clicked, independent centring returns `0 -57 0` /
`0 -57 -160` and designators 000/180: the two corner clicks become the true axis. The cost is that
the survey may report a slightly different heading from the one clicked, which is a correction — the
strip's own edges are better evidence of which way it runs than two clicks are.

**It also fixes the measured width.** The width probe ran ±`SURVEY_MAX_WIDTH/2` from the clicked
line, which is only half the strip when the click is on an edge. A 25-wide strip clicked on its left
edge measured **13**; clicked in the middle it measured 25. It now reports 25 either way, because the
probe runs from a centreline that is actually central. Width feeds the landing lateral gate, the
parking apron offset and now the approach funnel, so halving it was not cosmetic.

#### A painted runway has edges too: the two-rule cross-section

Centring on elevation closed the report for a strip that stands *above* the ground around it. It did
nothing at all for the other way people build a runway: a strip of concrete, gravel or smooth stone
**laid flush** with the field it sits in. There the sideways probe walks off the paint and out across
the field without the height ever changing, so the survey never finds an edge — and the same
complaint came back, in the same words, for a runway that has no step anywhere on it.

That case was wrong in three places at once, and the third is the one that made it hard to see:

Measured on the rig on the same field, with the same two clicks, before and after — a 25-wide
smooth-stone strip running `z=20..44` (so the strip spans `20.0` to `45.0` and its middle is `32.5`)
laid flush on a stone plateau, both ends clicked on the `z=20` edge:

| | before | after |
|---|---|---|
| survey says | *no correction printed* | `centreline moved 12 blocks` |
| stored thresholds | `715 101 20` / `885 101 20` | `715 101 32` / `885 101 32` |
| stored width | 25 — the probe ceiling, not a measurement | 25, measured |
| the same strip repainted 13 wide | 25 | **13**, thresholds `715 101 26` |
| whole take-off roll | z = **19.11, 18.83, 18.74** — *off the strip* | z = **29.29, 29.25, 29.36**, converging on 30.2 as it climbs |
| straight-in touchdown | `landed at airfield-3/09, 752, 101, **21**` — 1 block inside the near edge | `landed at airfield-3/09, 752, 101, **33**` |
| `airfields info` on the field stored crooked | *silent* | `centreline is 12 blocks off the middle of the strip - run /autopilot airfields resurvey "airfield-3"` |
| `resurvey` on it | — | `the centreline moved 12 blocks onto the middle of the strip` |

The take-off row is the report in one line: the aircraft was not rolling down the edge of the runway,
it was rolling down the field *beside* it and lifting off from there, while tracking its own
centreline perfectly. Where the surrounding field does happen to have an edge inside probe range the
old answer was worse than merely absent — the same strip on a narrower plateau stored `z=24`, having
found the far edge of the **plateau** and centred the runway on that.

**Elevation first, material only where elevation found nothing.** `Airfield.crossSection` walks out
on height exactly as it always did. Only when that walk is *unbounded on both sides* — when it ran
to the probe limit each way without meeting an edge, so the terrain has said nothing whatsoever about
where the strip ends — does it walk out a second time on the **surface block**, stopping at the first
column whose top block differs from the one under the probed point.

The ordering is the whole safety argument, and it is why this is not a rewrite of what a survey
measures:

* A raised strip, a plinth, an embankment, a runway cut into a slope — anything the elevation rule
  already reads — **never reaches the material walk**, so no survey that works today can change its
  answer. Verified like-for-like: the same 25-wide raised strip over open air, both ends clicked on
  the `z=20` edge, gives `centreline moved 12 blocks`, thresholds `1005 101 32` / `1215 101 32`,
  width 25, designators 09/27, obstacles 0/0, roughness 0.00 — identical to the build before this
  change on identical geometry.
* The two are never blended, never averaged and never minimised together. Taking the smaller of the
  two widths would collapse a strip to a block or two on any naturally patchy surface — grass beside
  dirt beside coarse dirt is not a runway edge, and a real height edge must always win.

**A material answer that is not credible is thrown away**, and then everything behaves exactly as it
does today. Two ways it fails, both verified on the rig:

* **Uniform ground.** A superflat world, or a stone plateau of one block: the material walk runs to
  the limit on both sides as well, so it has found no edges either. A survey clicked at `z=40` on a
  bare plateau stores thresholds `1420 101 40` / `1580 101 40`, prints no correction and reports the
  ceiling width, exactly as before.
* **A patch narrower than 3 blocks**, the same floor `measureWidth` already applies. With dirt laid
  one block to the left of the clicked line and coarse dirt one block to the right, the material walk
  returns a width of 1, is rejected, and the survey again stores `1420 101 40` / `1580 101 40` and
  prints no correction.

So the promise the centring made from the start still holds: **nothing invents a centreline out of
ground that has none.** What changed is only that paint now counts as ground that has one.

Cost is a bounded handful of block lookups at survey time — the material walk is `2 x (limit + 1)`
reads, it runs only where the heightmap already came back with nothing, and nothing on this path runs
per tick. `crossSection` has exactly three callers and they are all corrected together, which is why
they were all wrong together: `centreEnd` (the centring), `measureWidth` (the stored width) and
`centrelineOffset` (the `airfields info` warning, and therefore whether a player is ever told to
re-survey at all).

> **Airfields already on disk are not touched.** `Airfield` persists its two thresholds, and
> re-centring them on load would silently move every runway in every existing world — this codebase
> has been bitten by silently reinterpreting persisted data before, and the correction here is up to
> half a runway width. **Only newly surveyed airfields are centred.** A stored airfield keeps exactly
> the geometry it was saved with, and there are two ways to bring it up to date, both of which a
> human has to ask for:
>
> * re-click both ends with the survey tool, which already replaces an airfield whose thresholds land
>   within 12 blocks of a registered pair, or
> * **`/autopilot airfields resurvey <airfield>`**, new here, which re-measures the field from its own
>   stored thresholds and keeps its name and its parking spots.
>
> `/autopilot airfields info` says when a field needs it — `centreline is 6 blocks off the middle of
> the strip - run /autopilot airfields resurvey "airfield-1" while standing near it` — and says
> nothing when the runway's chunks are not loaded, because an unloaded strip reads as having no edges
> and a field nobody is standing near must not be accused of being crooked on no evidence. `resurvey`
> refuses an unloaded field for the same reason `/autopilot survey` does, refuses while an aircraft
> holds the runway, and is idempotent: run twice it reports `its centreline was already down the
> middle of the strip`.
>
> **This is the migration path for a painted field as well**, and it is the one a player with runways
> already on disk actually needs, since the material rule only runs when a survey does. Verified on a
> field stored crooked by the previous build: `airfields info` on `airfield-3` reported `centreline is
> 8 blocks off the middle of the strip`, `resurvey "airfield-3"` answered `the centreline moved 8
> blocks onto the middle of the strip`, the stored thresholds moved from `715 101 24` to `715 101 32`,
> and `airfields info` went quiet. Parking spots and the field's name come through it untouched.

### Where on the runway it touches down

The user's complaint was that a 183-block strip was being used from the very edge — "садятся
буквально на границе, 10-20 блоков используют". They were right, and the cause was that the aim
point was fiction. `TOUCHDOWN_AIM_OFFSET` was 12 blocks and **only the corridor raycast ever read
it**: `RunwayEnd#glideSlopeAltitude` put the bottom of the slope on the *threshold*, and the flare
fired on height above the *threshold*. So the aircraft was aimed at the threshold, floated 17 blocks
past it in the flare and stopped there, whatever the constant said.

Measured on the rig before the change, a 183×25 field, four commanded speeds — the numbers are the
same to within a block at 0.40 and at 2.80, because none of this is speed-dependent:

| commanded | crosses the threshold at | touches down | stops | runway used |
|---|---|---|---|---|
| 0.40 | **+0.89** | 3.4 | 4.6 | 3 % |
| 1.20 | **+0.92** | 4.0 | 5.2 | 3 % |
| 2.60 | **+0.28** | 1.4 | 2.6 | 1 % |
| 2.80 | **+0.36** | 1.5 | 2.8 | 2 % |

The touchdown figures are the cosmetic half. The threshold crossing height is the real problem: less
than a block. For the last 15 blocks before the threshold the aircraft was in ground effect over
ground the survey never measured, and **all of the error margin was on the side that destroys
aircraft**. Undershoot by five blocks and the aircraft is in whatever lies before the threshold;
overshoot on a 183-block strip costs nothing at all.

**The fix is one line of geometry: the glide slope ends on the aim point, not on the threshold.**
Everything else follows, because moving the endpoint `A` blocks down the runway moves the whole
approach `A` blocks down the runway and lifts it by `tan(8°) × A`. The flare still fires at
`FLARE_HEIGHT` above the touchdown datum and the float is still 17 blocks, so the touchdown moves by
exactly `A` and the roll-out is untouched.

**How far in.** `AutopilotConfig.touchdownAimOffset(length)` is a fifth of the runway, floored at 6
blocks, capped at 40, and never closer than `LANDING_STOP_RESERVE` (12) to the far end. A fifth
because the trade it settles is undershoot margin against overrun margin, and only a long runway has
both to spend: the near fifth buys terrain clearance short of the threshold, the far four fifths are
the overrun. The 40-block cap exists because the benefit saturates — and because the corridor raycast
is aimed at this point, so putting it far down the strip makes it a weaker test of the ground short
of the threshold.

After, same field, same four speeds:

| commanded | crosses the threshold at | touches down | stops | runway used |
|---|---|---|---|---|
| 0.40 | **+7.07** | 40.1 | 41.3 | 23 % |
| 1.20 | **+7.07** | 37.9 | 39.1 | 21 % |
| 2.60 | **+6.92** | 41.8 | 43.0 | 23 % |
| 2.80 | **+6.93** | 39.9 | 41.0 | 22 % |

And across lengths, all at the 2.60 default. `aim` is what the rule produces, `past aim` is
everything that happens after the aircraft reaches it — the float, the touchdown and the roll-out
together, which is what `LANDING_STOP_RESERVE` has to cover:

| length | aim | crosses at | touches | stops | past aim | used |
|---|---|---|---|---|---|---|
| 18 | 6.0 | +2.52 | 10.1 | 11.4 | 5.4 | 63 % |
| 24 | 6.0 | +2.17 | 8.6 | 9.8 | 3.8 | 41 % |
| 30 | 6.0 | +1.90 | 7.4 | 8.7 | 2.7 | 29 % |
| 34 | 6.8 | +2.76 | 11.8 | 13.1 | 6.3 | 38 % |
| 40 | 8.0 | +2.42 | 8.8 | 10.0 | 2.0 | 25 % |
| 66 | 13.2 | +3.72 | 17.2 | 18.4 | 5.2 | 28 % |
| 183 | 36.6 | +6.92 | 41.8 | 43.0 | 6.4 | 23 % |
| 300 | 40.0 | +7.57 | 43.3 | 44.5 | 4.5 | 15 % |

Worst case past the aim point over thirteen arrivals: **6.4 blocks**. The roll-out itself was
**1.1–1.3 blocks every single time** — the brakes are not the variable, the float is, which is why
the reserve is sized on the float and not on the braking distance.

**The whole chain now shares one datum**, which it did not before, and that disagreement is exactly
what produced "lands 1 block down the runway" on one field and a go-around on another:

| | before | after |
|---|---|---|
| glide slope ends on | the threshold | the aim point |
| flare fires relative to | the threshold | the aim point |
| corridor raycast aimed at | a point 12 blocks in | the aim point |
| "still airborne" go-around | 5 blocks past the threshold | 5 blocks past the aim point |
| `MIN_USABLE_RUNWAY_LENGTH` derived from | a 12 that nothing flew to | the floor of the aim rule plus a measured reserve |

That last go-around gate is the one that *had* to move with the rest. Left on the threshold it would
have sent every approach around 32 blocks before it flared, on any runway long enough to earn a real
aim offset. Its message changed with it: `crossed the touchdown point still airborne`.

The datum is `RunwayEnd#touchdownElevation()`, the runway surface interpolated at the aim point,
rather than the threshold elevation. On a level strip the two are the same number and every rig
measurement above was flown on one. They differ by `aimOffset × tan(slope)` on a surveyed runway that
slopes, which at the 40-block cap and the 5° the survey starts warning about is 3.5 blocks — most of
the `FLARE_HEIGHT` the flare is triggered on, so it is not a difference that could be left alone.

**Aiming further in is also worth several blocks of water.** The previous section's measurement was
that the flare fired 15 blocks *before* the threshold, leaving 1.2 blocks of margin over a shoreline
that stopped at the threshold. The flare now fires 21.8 blocks *past* the threshold on the same
field, so the same runway tolerates a sea standing **20 blocks onto the strip** and lands
identically — verified by flooding progressively: 8 blocks in, 20 blocks in, both `43.0`, the same
number as dry. At 26 blocks in `landableBelow` simply defers the flare until there is runway under
the aircraft (`agl=3.29` at 26.5 blocks down) and it still lands. Flood the whole strip and both
funnels and it goes around four times and reports `came to rest in the water` — the give-up path,
unchanged and still honest.

**What is not bought by weakening a gate.** Nothing in `gateFailure` moved: heading, lateral, bank
and sink rate are the values they were. Re-verified on the rig with a 13-block wall in the 36
approach funnel of a 66-block field — three go-arounds for `terrain in the approach corridor`, a
switch to 18, and a landing 17 blocks down the other end.

### Water is not ground

The heightmap cannot tell a sea from a field, and for a long time neither could the approach.

`TerrainScanner.surfaceHeight` reads `Heightmap.Types.MOTION_BLOCKING`, whose predicate is
`blocksMotion() || !getFluidState().isEmpty()`. A column of ocean therefore reports its **waterline**
as the surface, at the same O(1) cost and with the same shape of answer as a column of grass. Every
number derived from it inherits that: the survey's approach obstacle counts, the terrain profile, and
`agl` in `/autopilot status`. Filling the approach corridor of a test runway with water changed
nothing whatsoever in the survey — `160x25, roughness 0.00, approach obstacles 36 -> 0, 18 -> 0`
before and after.

That is the *right* answer for "how low may I fly here" and the wrong one for "may I put the wheels
down here", and the flare asked the second question using the first answer:

* **The flare is a commitment, not a manoeuvre.** `tickFlare` forces the throttle to 0 and holds it
  there. Whatever is under the aircraft when it fires is what the aircraft is going to come down on.
* **`getOnGround()` is true in water.** `PlaneEntity#tickOnGround` runs on `getOnGround() ||
  isOnWater()` and sets the `onGroundTicks` coyote timer from inside it, so an aircraft floating in
  the sea reports itself as on the ground — with the same 48x rolling drag.

Put together, an approach that crossed the shoreline four blocks up entered `FLARE` over open water,
stopped flying, sank, and the roll-out announced a landing. Measured on the rig, a runway on flat
ground with the sea lapping eight blocks past its threshold:

| | before | after |
|---|---|---|
| `FLARE` entered | 15 blocks **before** the threshold, `agl=3.98` over the waterline | never — no landable surface |
| water contact | 6 blocks past the threshold, still descending | none, `water=false` throughout |
| came to rest | `y = -63`, three blocks under the surface, `water=true` | went around, switched ends, landed on 18 |
| reported | `Plane #5 landed at airfield-1/36, 0, -63, -6 (7 blocks down the runway).` | `going around (1/3): crossed the touchdown point still airborne` |

Three changes, and they are all "reference the runway, not the ground":

1. **`TerrainScanner.isLandable`** compares `MOTION_BLOCKING` against `OCEAN_FLOOR` — the same
   predicate without the fluid clause, and `Usage.LIVE_WORLD`, so a running server maintains it. The
   two differ by exactly the fluid standing on the terrain, so one extra heightmap lookup answers
   "is this ground" with no block reads at all. An **unloaded** column answers *false*: everything
   that consults it is deciding whether to commit, and "not loaded" must never be the answer that
   lets the commitment be made.
2. **The flare needs a landable surface *and* the runway's own elevation.** `agl <= FLARE_HEIGHT`
   alone also fires over ground that rises under the approach — a beach or a ridge brings AGL to four
   blocks while the aircraft is nine above the runway and fifty short of it. Both numbers agree over
   the runway, which is the only place a flare belongs.
3. **The gates and the corridor raycast are flown on height above the threshold**, not AGL. The
   documentation always said "below 30 blocks above the threshold"; the code said "below 30 blocks
   above whatever is underneath". Identical on a flat field, and on a coastal or valley approach the
   ground reading starts the gates late over low ground and early over high. The corridor raycast is
   also now traced with `ClipContext.Fluid.ANY` instead of `NONE`: a sea standing above the glide
   slope is as good a reason to go around as a hillside, and it is the one obstacle the heightmap
   check cannot see, because it reports the waterline as ground.

**What the fix does not do** is make a flooded runway landable. Standing water above the runway
elevation is held back by something, and whatever holds it back stands above a glide slope that ends
on the runway — such an approach is unflyable in principle, and the correct outcome is the go-around
it now gets. What the fix converts is the class where the runway *is* dry and the water is only on
the way in.

The margin on that class is much larger since the glide slope was re-aimed at the touchdown point
(see "Where on the runway it touches down"): the flare used to fire 15 blocks *before* the threshold
and now fires 21.8 blocks *past* it on a 183-block field, so the shoreline may stand 20 blocks onto
the strip instead of having to stop 1.2 blocks short of the threshold.

**Other surfaces that are not ground.** Three bands laid across a cruise track on the rig, read
straight off the per-tick trace at 100 blocks up (`gnd` is the AGL reference, `landable` the new
test):

| under the aircraft | `gnd` | `landable` | |
|---|---|---|---|
| plain grass, the superflat | −60 | true | the baseline |
| **lava**, 3 deep, replacing −63…−61 | −60, its own surface | **false** | fluid: in `MOTION_BLOCKING`, not in `OCEAN_FLOOR` |
| **oak leaves**, a 3-block canopy on −60…−58 | −57, the canopy | true | leaves block motion, so both heightmaps agree |
| **powder snow**, 3 deep on −60…−58 | −60, the ground *under* it | true | in neither heightmap |

So lava is fixed by the same test with no lava-specific code — it is a fluid, and the false flare
fired over it for exactly the reason it fired over the sea.

Powder snow needed nothing: `Blocks.POWDER_SNOW` is registered with `dynamicShape()`, so
`BlockBehaviour#calculateSolid` has no collision-shape cache to consult and returns false, and the
block is therefore absent from *both* heightmaps. An aircraft is measured against the real ground
underneath the snow — which is also where it ends up, since powder snow has no collision for it. The
altitude was never wrong; the aircraft simply arrives already buried, which is the correct outcome
for landing in a snowdrift.

A forest canopy is a genuine surface and reads as one. An aircraft that settles into the treetops
really has come to rest on them — that was never a false altitude, only a false *report*, and it is
the next section that fixes it. What did change for a forest is the flare: a canopy rising under the
approach used to bring AGL to four blocks while the aircraft was still well above the runway, and the
height-above-threshold term now refuses that.

### What the approach funnel can see, and the bamboo report

The report was that **bamboo is not treated as an obstacle** — the modern bamboo, not sugar cane.
Most of that does not reproduce, and the half that does is not about bamboo at all.

**Bamboo is visible to every heightmap the mod uses.** `Blocks.BAMBOO` is registered
`.forceSolidOn()`, and `BlockBehaviour.BlockStateBase#calculateSolid` returns true on that flag
before it ever looks for a collision-shape cache — which matters, because bamboo is also
`.dynamicShape()` and therefore has no cache. `blocksMotion()` is
`block != COBWEB && block != BAMBOO_SAPLING && isSolid()`, so a stalk is in `MOTION_BLOCKING` *and* in
`OCEAN_FLOOR`. Measured by flying over a 15-block bamboo wall on the superflat with the trace on:
`gnd=-45` against a ground of −60, `landable=true`. Terrain following sees it, the survey counts it,
and the flare treats its canopy as a surface.

**Bamboo collides, and hard.** Its collision shape is a full-height column 3/16 of a block wide, and
that is enough. Measured with planes summoned into a 61×101 bamboo grove 15 stalks tall, entering
horizontally at canopy height with a known `Motion` (the same method as the water-impact recipe):

| entry speed | outcome |
|---|---|
| 0.50 b/t | stopped 4 blocks inside the grove, **−2 HP**, and stayed there permanently |
| 1.00 b/t | **destroyed**, 4 blocks in |
| 2.00 b/t | **destroyed**, 2 blocks in |
| 2.00 b/t into a stone wall of the same height (control) | destroyed |

So an aircraft flown into a bamboo forest is stopped or destroyed exactly as it is by a wall. Nothing
here needed fixing, and the "an aircraft that settles into a canopy really has come to rest on it"
judgement recorded for leaves applies unchanged: the aircraft does come to rest on the bamboo, it is
not on a runway, and `landingProblem` says so rather than reporting a landing.

**What was actually broken is how the approach funnel was sampled**, and it lost stone just as
happily as bamboo. `countApproachObstacles` took **one heightmap column every 10 blocks along the
extended centreline** — 20 points, and nothing else, in a corridor 200 blocks long and as wide as the
runway. Measured on a 160-block field with a 20-block-tall obstruction in its 36 funnel:

| obstruction | before | after |
|---|---|---|
| bamboo wall 5 deep, on the centreline, **between** two stations | **0** | 1 |
| bamboo clump 4–8 blocks **beside** the centreline, over a station | **0** | 2 |
| **stone** wall 5 deep, on the centreline, between two stations | **0** | 1 |
| bamboo wall 5 deep, moved 5 blocks so a station lands on it (control) | 1 | 2 |
| nothing at all (control) | 0 | 0 |

The preferred landing direction followed: before, only the control flipped the field to its other
end; after, every one of them does.

Each station is now a **cell** rather than a column — `SURVEY_APPROACH_SUBSTEPS` (5) positions along
track, so there is a sample every 2 blocks instead of every 10, and
`SURVEY_APPROACH_LATERAL_SAMPLES` (5) columns spread across the funnel width, which is the runway's
own width with a floor of `SURVEY_FUNNEL_MIN_HALF_WIDTH` (5) either side. A station is flagged when
the highest column in its cell pokes above the glide slope, so **the reported number keeps its old
scale** — still 20 stations, still "n of 20" — and stays comparable with the counts already persisted
on existing airfields. It can only ever go up, which is the safe direction. Cost is 25 heightmap
lookups per station and 500 per funnel, paid at survey time and once per arrival for an airfield old
enough to have no stored counts; nothing here runs per tick.

`TerrainScanner.scan` samples the cruise profile the same way — 12 columns over 220 blocks — and was
deliberately left alone. It is far less exposed, because the grid moves with the aircraft: an
obstacle missed at 200 blocks out is sampled again at 180, at 160 and at every sample distance in
between as the aircraft closes on it, which a one-shot survey of a fixed funnel never gets to do.

**Other tall vegetation.** Bands laid across a cruise track on the superflat and read straight off
the per-tick trace, with `random_tick_speed` set to 0 so nothing grows mid-measurement:

| band | `gnd` | `landable` | in the heightmaps? |
|---|---|---|---|
| plain grass (control) | −60 | true | — |
| **bamboo stalks**, 15 tall | **−45**, the canopy | true | both. `forceSolidOn` |
| **bamboo saplings** | −60, the bare ground | true | **neither** — `blocksMotion` excludes them by name |
| **sugar cane**, 3 tall | −60 | true | neither — `noCollision`, so not solid |
| **cactus**, 3 tall | **−57**, its top | true | both — it has a nearly full collision box |
| **kelp** in 4 of water | −59, the waterline | false | the water is seen, the kelp is not |
| **big dripleaf** on a 2-block stem | −60 | true | neither — `forceSolidOff` |
| **tall grass** (two blocks) | −60 | true | neither |
| **sweet berry bushes** | −60 | true | neither |
| **vines** on a wall face | −60 | true | neither |

Nothing in that table is worth code. Everything invisible is at most three blocks tall against a
`TERRAIN_CLEARANCE` of 22, and everything invisible except one has no collision either, so an
aircraft passes straight through it — the same consistency powder snow has. The exception is worth
knowing about: **big dripleaf is `forceSolidOff` but `BigDripleafBlock` does have a collision shape**,
so it is the one plant that is invisible to the scanner and solid to `Entity#move`. A leaf on a stem
is not an obstacle at aircraft scale, so it is recorded here rather than fixed.

### A landing report has to be true

`tickRollout` used to announce a landing on `speed < ROLLOUT_STOP_SPEED && getOnGround()` and nothing
else. Both are satisfied by an aircraft resting on a sea floor a hundred blocks short of the field,
and the distance it printed — `|alongTrack|` — stays a small, plausible-looking number whether the
aircraft is on the strip, short of it, or far out to one side. That is how a drowning came to be
reported as `landed at airfield-2/19 … (58 blocks down the runway)`.

`landingProblem` now asks the three questions the claim actually rests on, and the roll-out prints
one line or the other:

* along the strip, between the thresholds within `LANDING_POSITION_TOLERANCE` (5 blocks)
* across it, inside `max(width/2, 5)` of the centreline
* at the runway surface underneath, within `LANDING_ELEVATION_TOLERANCE` (3 blocks) — interpolated
  along the strip, because a surveyed runway is allowed to slope
* and not in water, which is checked first because it is the one that was being missed

```
Plane #7 landed at airfield-2/36, 2655, -60, -21 (18 blocks down the 66-block runway, 28% used).
Plane #5 did not land at airfield-1/36: came to rest in the water, at 0, -63, -6.
```

**The length and the percentage are there because a distance on its own says nothing.** "3 blocks
down the runway" is a tidy arrival on a short field and an aircraft parked on the very lip of a
183-block one, and the line read the same either way — which is how the aim point stayed broken
without anyone reading a report that looked wrong. The user spotted it by eye out of the window; the
percentage is the same observation, in the output, where it can be asserted on.

Both paths go through `stop()`, so the runway reservation is released either way — a runway held for
ever by an aircraft on the sea floor was the second thing the false report hid. `checkGrounded` gained
the same distinction one level up: an aircraft that stops flying in a mode that is meant to be
airborne now **ditched in water at** rather than **came down at** when it is floating.

All of these go through `AutopilotFeedback.report`, which logs when there is no owning player, so a
console-issued sortie prints every one of them — verified on the rig, where every line quoted above
was read out of `console.log` with no player connected.

**Holding.** `RunwayOccupancy` is a small reservation registry keyed by dimension and airfield name.
An arrival reserves the runway when it commits to the approach and releases it on landing, on a
go-around, or when it is destroyed; a departure reserves it before it starts to taxi and releases it
on the climb-out (see [Departure delay and the runway gate](#departure-delay-and-the-runway-gate)).
A second aircraft arriving at a busy field enters `HOLD` and orbits the approach fix at circuit
height until the runway frees up; one departing from a busy field stays on its parking spot.

Aircraft in a hold are separated by entity id: the level is `id mod 4` times 10 blocks and the
starting angle round the fix is `id * 137°`. `PlaneEntity#canBeCollidedWith` is unconditionally
true, so planes are hard-colliding entities and several of them orbiting one fix at one altitude
eventually block each other's `move()`, which `PlaneCollisions` correctly reads as an impact — seen
in the field as two aircraft destroyed three blocks apart, at the same altitude, in the same tick,
both in `HOLD`. This is **separation, not sequencing**: there is still no queue, and whoever polls a
free runway first takes it. The spacing is deliberately only 10 blocks — the stack is bought with
height the aircraft has to give back before it can land, and at 20 blocks a level the fourth
aircraft held 60 blocks above the fix and then could not get down again.

### 4c. Choosing the arrival: `ArrivalPlan`

**The pattern is not the default any more, and neither is the fixed 300-block fix.** Every arrival
used to fly to a point exactly `FINAL_INTERCEPT_DISTANCE` out at `PATTERN_HEIGHT`, whatever height
it happened to arrive carrying, and an aircraft that could not get down in that distance simply
pressed on, crossed the threshold still airborne and went around. Measured on the rig with an
arrival 172 blocks above the runway: it flew the whole 300-block final still ~100 high, went around,
and took **150 seconds** from top of descent to wheels stopped, against 66 for the same arrival flown
from a sane height. The orbits users report are mostly that loop, not a deliberate hold.

`ArrivalPlan.decide` picks one of four, and says which in one phrase:

| Entry | When | Phrase |
|---|---|---|
| `STRAIGHT_IN` | the height above the standard fix can be lost on the way to it at `MAX_DESCENT_ANGLE` | `straight in` |
| `EXTENDED` | it cannot — so join the same glide slope further out, in 150-block steps to 900 | `extended final 600` |
| `ORBIT` | even the longest final cannot absorb the height | `orbit to lose 120` |
| `TRAFFIC` | someone else has the runway | `holding, runway busy` |

Extending is always tried before circling because it helps twice over — more track to descend across
*and* a higher slope that far out — and because it makes progress towards the runway, which an orbit
does not. `MAX_INTERCEPT_DISTANCE` is 900 (126 blocks above the threshold at 8°, nearly three times
circuit height); past that the extension is a long way flown in the wrong direction and an orbit
really is cheaper.

`tickApproach` caps the commanded altitude at `glideSlopeAltitude(min(distance, interceptDistance))`
rather than at circuit height. That cap was the reason an extended final could not work: an aircraft
joining 700 blocks out was ordered down to circuit height at once and then flew the rest of the
final level, arriving exactly as high as before.

**Which end, for an aircraft that is already somewhere.** `Airfield#bestEnd` now takes an optional
position and scores each end as `track to fly + 400 × flagged columns − 40 if uphill`. Obstacles
still dominate — 400 blocks of track per flagged column is more than any plausible overfly, because
landing over a hill to save a detour is exactly the trade this function exists to refuse. What the
position settles is the case the old code settled arbitrarily: with both funnels clean it returned
end A regardless of where the aircraft came from, so an arrival from the wrong side flew the length
of the field, turned round and came back — 400 blocks and about 40 seconds at approach speed. A
departure has its own scorer now; see [4d](#4d-deciding-the-arrival-at-range-and-then-flying-it).

**The obstacle count is the larger of the surveyed one and what is visible now.** A survey is a
photograph: it is trustworthy about the moment it was taken and says nothing about a hill built, or a
chunk generated, afterwards. Taking the maximum keeps the survey as the *floor* — which is what stops
an unloaded funnel scoring zero and winning, the bug this function was fixed for once already — while
letting an obstacle the aircraft can now actually see be counted. Unknown columns are skipped in the
live half for exactly that reason: the surveyed number already speaks for them.

### 4d. Deciding the arrival at range, and then flying it

The user's question was blunt: **"почему за 200 блоков нельзя сразу расчитать по какому маршруту
удастся сесть? … сделать расчёт а потом уже садиться и взлетать"** — why can the whole route to a
landing not be worked out a couple of hundred blocks out and then simply flown, take-off included.

They were right, and for a worse reason than they knew. Nothing was worked out at range at all.

**The arrival began overhead.** `AutopilotSpawner#launchInbound` and `#launchSortie` make the flight's
last waypoint the **centre of the destination runway**, and `tickCruise` only started the arrival once
that waypoint was reached. Measured on the rig, a straight-in down the extended centreline at the
2.60 default:

```
trace #1 t=340 descent pos=0.5,-0.04,-50.7 … dthr=-51.2   ← DESCENT entered 51 blocks PAST the threshold
trace #1 t=440 descent pos=-89.2,-12.79,-40.0 … lat=-89.7 ← 90 blocks off the centreline, coming back round
```

The aircraft flew the length of the field, out to 90 blocks abeam, and back to a fix 300 blocks out on
the side it had just come from — **1578 blocks of track for a 780-block flight**. That loop is on
every single arrival, and none of it was a decision.

So `ArrivalPlan` grew three things: a range at which the decision has to be made, a feasibility test
against what the airframe can actually do, and a commit point.

#### The decision range is the aircraft's own geometry

```
decisionRange = interceptDistance + max(ARRIVAL_DECISION_FLOOR, 2 × turnRadius)
```

`turnRadius` is `v / yawRate`, and `tickYaw` clamps the yaw rate to `2.5°/tick × the airframe's
getRotationSpeedMultiplier`. **That multiplier is the whole reason this is not a constant**: 1.0 on
the starter plane, 0.5 on the large one, **0.2 on the cargo plane**. The same 0.50 b/t approach is an
11.5-block turn on one airframe and a **57-block** turn on another.

Two radii, because the manoeuvre the range has to pay for is the join onto the centreline and its
worst case is a course reversal, which displaces the aircraft `2r` sideways before it rolls out.

**On the user's 100 blocks.** It is the floor, not the rule, and the arithmetic says why. At cruise
speed the starter airframe's radius is 59.6 blocks, so two of them are 119 and the floor never binds.
At approach speed the radius is 11.5 and two are 23 — without a floor the aircraft would be deciding
its arrival from inside the pattern, so 100 is what stops that. But on the cargo airframe the
approach-speed radius is 57 blocks and two are 115: **100 blocks is under two turn radii there**,
which is enough to *verify* a straight-in and not enough to *repair* a bad entry. Flown on the rig,
`inbound 0 -30 120` — 120 blocks straight down the centreline — lands on both builds, so 100 blocks
really is enough for the easy case on the light airframe. It is not enough for the case that matters.

The range is measured to the **threshold**, not to the intercept fix. An arrival from abeam never
passes near the fix at all, so a fix-referenced trigger would sail straight past the decision and end
up overhead again, which is the behaviour being removed.

**Only a flight whose last waypoint *is* the field cuts the corner.** Three conditions:
this is the last leg; the destination is a named airfield; and that waypoint is within
`ARRIVAL_WAYPOINT_IS_THE_FIELD` (300 blocks) of it. A route wand's last waypoint is somewhere a player
pointed at, and it is still flown to exactly as before — verified with `/autopilot route`, which still
reads `arrival at field-17/36: straight in, decided 45 blocks out` off its improvised strip.

#### Feasibility, not merely geometry

The old test was one line: can the height above the fix be lost on the way there at
`MAX_DESCENT_ANGLE`. Two things the airframe knows and that line did not:

* **The descent is sink-rate limited, not angle limited.** The altitude cascade clamps the commanded
  vertical speed to `MAX_SINK_RATE` (0.30 b/t) *before* it becomes a flight path angle, so the
  gradient really available is `min(tan(12°), 0.30 / v)`. At cruise speed that is 0.115 against the
  0.213 of 12° — the old figure promised **nearly twice** the descent the aircraft could fly.
  `AutopilotMath.descentAvailable` integrates it along `speedSchedule`, which is the same profile the
  descent leg is actually flown on, because neither end is honest alone: the current speed
  under-counts by the whole braked part of the run and the target speed over-counts by the fast part.
* **The turn onto final has to fit.** Joining through `θ` displaces the aircraft `r(1 − cos θ)` to the
  outside, up to `2r`; washing that off against the centreline costs `offset / tan(40°)` of track
  (40° is the largest cut `tickApproach` takes), and all of it has to be spent before the gates arm at
  `FINAL_HANDOVER_DISTANCE`. On the starter airframe at approach speed the worst case costs 27 of the
  150 available and this never binds — nothing about an ordinary arrival changes. On a cargo plane it
  costs 136, which only just fits, and from the transit speed it does not fit at all. That is the case
  that used to be discovered at the gate: measured before `speedAtFix` existed, an aircraft reaching
  the fix at 1.91 b/t swung **87 blocks** off the centreline and went around.

Both failures are repaired the same way and in the same order as before — extend the final, orbit only
when no final can absorb it — because a longer final buys track for the height *and* for the turn.

#### Committed, and re-checked

The plan used to be recomputed from scratch every tick. That is not a commitment, and the phrase gave
it away: a 172-block-high arrival announced `extended final 600 → 450 → 600 → straight in` inside a
single second, because the extension ladder is discrete and an aircraft between two rungs alternates
between them.

It is now decided once and held, and re-checked every `ARRIVAL_RECHECK_INTERVAL` (20 ticks). **What
triggers a replan**, in the order they are tested:

| Trigger | Phrase | Live in |
|---|---|---|
| The runway became busy, or free | `the runway is busy` / `the runway is free` | descent and approach |
| The glide slope is blocked — raycast from the fix to the aim point | `terrain across the 36 glide slope` | descent and approach |
| The end's approach obstacle count has risen since the plan was made | `5 columns now visible in the 36 approach` | descent and approach |
| The committed profile no longer closes | `the profile no longer closes` | descent only |
| A final a whole rung shorter now closes | `a shorter final now closes` | descent only |

Everything else leaves the plan alone. Four details that were each found by getting them wrong first:

* **The corridor raycast is the only probe that loads what it looks at.** `Level#clip` reads block
  states and `Level#getBlockState` generates the chunk if it has to, where every heightmap probe in
  this feature answers `UNKNOWN_HEIGHT`. The ground under a final is exactly the part of the world
  nobody has generated when the arrival is decided 415 blocks out. With only the heightmap probe the
  plan never changed at all on the wall test below — the wall's chunks were still unloaded every time
  it was consulted. Its cost is bounded: the trace starts no further out than
  `FINAL_INTERCEPT_DISTANCE`, so a 900-block extended final is checked over its last 300 and the first
  call is about twenty chunks whatever the plan.
* **It fires once per runway end.** An overhang is invisible to the heightmap, so a replan can land on
  the same end again; firing every second after that would replan for ever and change nothing. Said
  once, and the corridor raycast in `tickApproach` — unchanged — still produces the go-around.
* **The profile test is not re-run once the aircraft is established on the final.** The plan has been
  executed by then and the authority on whether the approach is good enough to land from is the
  landing gates. Re-deciding there produced nothing but noise: a perfectly ordinary straight-in
  announcing an extended final five blocks short of its own fix, because the distance still to run
  goes to zero and any height above the slope reads as a failure. `ARRIVAL_PROFILE_SLACK` (5 blocks,
  the cascade's steady-state lag with a block in hand) covers the same hair-trigger nearer the fix.
* **A replan re-chooses the runway end — but not after a go-around.** `goAround` takes the end over at
  that point (it switches to the opposite one after `MAX_GO_AROUNDS`), and a replan that re-ran
  `bestEnd` would hand it straight back, swapping the aircraft between the two for ever.

#### Measured

Same rig, same world, same jar swapped underneath it; a 160×25 strip on the superflat, `tick sprint`
throughout. **Ticks** is launch to wheels stopped, **track** is the summed horizontal chords, **lat**
is the worst lateral offset once established on the approach.

| Arrival | ticks | track | lat | go-arounds |
|---|---|---|---|---|
| straight in, 2.60 | 1396 → **889** | 1578 → **737** | 41.8 → **0.0** | 0 → 0 |
| straight in, 2.80 | 1372 → **897** | 1575 → **736** | 39.5 → **0.0** | 0 → 0 |
| straight in, 0.40 | 3389 → **1559** | 1477 → **736** | 47.4 → **0.0** | 0 → 0 |
| from the wrong side | 1334 → **823** | 1409 → **575** | 42.2 → **0.0** | 0 → 0 |
| 120 blocks high | 1649 → **1396** | 2174 → **1733** | 25.7 → **13.2** | 0 → 0 |
| wrong side *and* high | 1585 → **1321** | 2017 → **1566** | 26.0 → **13.1** | 0 → 0 |
| from abeam, 500/300 | 1317 → **971** | 1354 → **876** | 44.6 → 44.4 | 0 → 0 |
| from abeam, 150/150 | 1193 → **974** | 1021 → **701** | 45.6 → 49.9 | 0 → 0 |
| 120 blocks out, centreline | 1177 → **991** | 985 → **837** | — | 0 → 0 |
| two arrivals, one runway (first) | 1423 → **947** | 1664 → **857** | 40.6 → 38.8 | 0 → 0 |
| two arrivals, one runway (second) | 2509 → **2038** | 2622 → **1775** | 43.1 → 58.0 | 0 → 0 |
| **wall across the funnel, built after the survey** | 2018 → **1353** | 2350 → **1502** | — | **3 → 0** |
| southbound sortie, both ends | 2457 → **1868** | 3742 → **2715** | 40.9 → **4.2** | 0 → 0 |

Replans fired 0 times on six of the nine clean arrivals, twice on each of the two high ones (walking
the extension ladder down as the height came off), and once on the wall.

**The two arrivals from abeam are the honest exception.** An aircraft 150 blocks from the runway and
90° off it has to reposition whatever the planner says — the fix is behind it — so the lateral figure
does not improve and should not. What improves is that it decides to reposition at range instead of
arriving overhead and discovering it.

**Nothing in `gateFailure` moved.** Heading, lateral offset, bank and sink rate are the values they
were, and the corridor raycast in `tickApproach` is untouched. The wall case is the measure of
success: the go-arounds went away because the approach became flyable, not because a gate stopped
complaining.

#### The one thing that was not the problem

The premise "compute it 200 blocks out" is right in spirit and wrong in the arithmetic. On the flat,
clean superflat **not one arrival went around before this change** — 8 scenarios, 0 go-arounds — so
"how often does an arrival go around" was already zero for the easy cases and there was nothing there
to fix. What was there to fix was a guaranteed 400–800 blocks of unplanned circuit on every arrival,
and a plan that was recomputed rather than committed to. The go-arounds only appear when the world
disagrees with the survey, and that is the case the replan triggers exist for.

### 4e. Deciding the departure before the aircraft rolls

`Airfield#departureEnd` called `bestEnd(level)` with no position and no destination, and `bestEnd`
answers a different question: *which threshold would you rather cross on the way in*. It scores each
end by its own **approach** funnel — the ground **before** that threshold. A departure that rolls from
that threshold runs the other way down the strip and climbs out past the **far** one, over the
opposite end's funnel: the one `bestEnd` had just rejected.

**So on a field with a hill off one end, the aircraft landed away from the hill and took off straight
at it.** Reproduced on the rig with a 36-block wall 20 to 60 blocks off the 36 threshold, surveyed
(`approach obstacles: 36 -> 5, 18 -> 0`), with the destination due south so the departure climbs
straight out:

```
Plane #100 cleared to taxi at airfield-1/18 …
Plane #100 lost at 19, -27, 19 in climb.        ← flown into the wall
```

`DeparturePlan` scores it properly, and with the input the old call did not have — where the flight is
going:

```
cost(end) = track from the far threshold to the first waypoint
          + turnRadius × the turn onto course, in radians
          + 400 × columns in the climb-out funnel
```

* The first two terms are what a wrong-way departure costs: a runway length flown in the wrong
  direction plus the turn, about 210 blocks on a 160-block strip at climb speed.
* The obstacle term is the same 400 blocks an arrival pays, so **one blocked column outweighs any
  wrong-way departure** — turning the aircraft round is cheap and climbing out at a hillside is not.
  The count comes from the survey, for the same reason `bestEnd` takes it from there: a departure is
  decided while most of the climb-out is unloaded ground.
* Both of the first two terms favour the same end (the one nearer the destination is also the one with
  the smaller turn), so the airframe's turn rate can change the turn the plan *reports* but not the
  end it picks. That matters because the choice is made twice — once by the spawner, which puts the
  aircraft on a parking spot beside one threshold, and once by the flight director, which then taxis
  to it — and the two must not disagree.

Same wall, same sortie, after:

```
Plane #4 departure from airfield-1: depart 36, 180 deg turn to course.
Plane #4 landed at airfield-3/18, 0, -60, 2537 (36 blocks down the 160-block runway, 23% used).
```

And with no obstacle at all, a sortie to a field 2660 blocks due south departs 18 (straight out)
instead of 36 (180° turn): **2457 → 1868 ticks, 3742 → 2715 blocks of track, 28.4 → 15.7 full turns of
heading change.**

The phrase goes in the same `plan[…]` field as everything else, and is what `status` and the tower
board show while the aircraft is on the ground:

```
#20 parked pos=17,-60,13 … dep=airfield-1/36 wait=clock 0:15 legs=0/1 plan[depart 36, 92 deg turn to course]
airfield-1  36/18  FREE      2 waiting to depart, none cleared yet
    #20 departure, parked, 0:05, 0:14 on the clock [depart 36, 92 deg turn to course]
```

`/autopilot status` also carries `replans=N` beside `go-arounds=N`. The two are read together: a
replan is the plan being repaired in the air, which is the outcome this feature wants, and a
go-around is the same failure discovered at the gate, which is the one it does not.

### How long a landing takes, and where the time went

The user's second report was simply **"долго слишком садятся"** — landings take far too long. The
clock that matters is top of descent to wheels stopped, and almost all of it was being spent flying
slowly in a straight line.

* **The descent leg was flown at `APPROACH_SPEED` from its first tick** — 0.5 blocks/tick for however
  many hundred blocks the fix happened to be away, measured at 31 seconds of straight-line flying on
  an ordinary arrival. Worse than merely slow: the flight path angle is capped, so the sink rate
  available is `v × tan(12°)`, and flying slowly is precisely what makes an aircraft unable to get
  down. It now uses the same measured deceleration schedule the cruise brakes with, aimed at
  `APPROACH_TRANSIT_SPEED` (1.20) *at* the fix rather than from the start of the leg.
* **The approach leg likewise**, aimed at `APPROACH_SPEED` by `APPROACH_SETTLED_DISTANCE` (240) and
  handed to `FINAL` at 150 as always.

Two measured corrections in that, both of which cost a whole set of go-arounds before they were made:

* **A turn onto final has to be flown slowly.** The turn radius is `v / yawRate`, so a 180° join —
  which is what an arrival from the far side of the field always is — displaces the aircraft
  `2v / yawRate` sideways before it rolls out. With the transit speed applied regardless, the
  aircraft reached the fix at 1.91 b/t, swung **87 blocks** off the centreline coming round, was
  still 12 off and 15° skewed at the gate, and went around. `speedAtFix` drops to `APPROACH_SPEED`
  whenever the turn still to be made exceeds 30°; at 0.50 the same turn displaces 11 blocks.
* **Slower is not automatically safer.** Aiming the approach schedule at `FINAL_SPEED` rather than
  `APPROACH_SPEED` put the aircraft *below* the old profile for the last 240 blocks, where the
  throttle sits on its floor with the airbrake on, and three arrivals in a row went around on
  "sinking 0.46 blocks/tick" against a 0.45 gate. The gates were not weakened for any of this.

Measured on the rig, arriving at a runway 8 blocks above the surrounding sea with an 89-block summit
off the north threshold (`/autopilot inbound … "airfield-1" 2.60`):

| arrival | top of descent → stopped | track | total climb | turns flown | go-arounds |
|---|---|---|---|---|---|
| from the north, offset 150 | 67 s → **47 s** | 1587 → 1600 | 2 → 3 | 0.7 → 0.8 | 0 → 0 |
| from the north, over the summit | 66 s → **47 s** | 1562 → 1598 | 16 → 11 | 0.9 → 0.9 | 0 → 0 |
| from the north, 172 blocks high | 150 s → **59 s** | 2361 → 2111 | 49 → 3 | 2.1 → 0.7 | 1 → 0 |

The high arrival is the one to read: it used to fly a 300-block final still 100 blocks above the
slope, cross the threshold airborne, go around and come back — 2.1 full turns of heading change and
49 blocks of climb spent on it. It now reads `extended final 450 → 600 → straight in` and lands
first time.

**Which end to land on** is chosen from the approach obstacle counts **recorded by the survey**;
ties go to the uphill direction, because landing uphill shortens the roll-out. There is no wind —
Minecraft has no wind API, so runway selection deliberately ignores it rather than inventing one.

It used to recount both funnels at the moment the aircraft committed, and that chose exactly wrongly.
`TerrainScanner.surfaceHeight` returns `UNKNOWN_HEIGHT` for a column in an unloaded chunk, and an
unknown column was *skipped* rather than counted — so an unloaded funnel scored **zero obstacles and
won**. `resolveLanding` runs while the aircraft is still hundreds of blocks out, when the far end's
approach is precisely the part of the world nobody has loaded, so the aircraft systematically chose
the end it could not see. On flat ground that cost about 50 seconds of flying past the field and
turning back; on hilly ground it picks the end with the hill in it.

The survey runs with the chunks loaded — `/autopilot survey` insists on a loaded position — so its
counts are the trustworthy ones and are now persisted on the `Airfield` and used directly. An
airfield stored before they were recorded still measures live, but that fallback counts an unknown
column **as an obstacle**: "not loaded" must never be the cheapest answer.

Verified on the rig with a 15-block wall in the 36 approach funnel: the survey recorded `36 -> 4,
18 -> 0` and preferred 18, and an aircraft arriving from 1500 blocks away with both funnels unloaded
flew past the field, turned back and landed on **18**.

---

## 4a. The airfield browser

`/autopilot airfields` used to print a flat list. It is now a browser, and every part of it works
from the server console — that is how it is tested.

**Sorted by distance from whoever asked**, with a bearing. A player gets distances from where they
are standing; the console and command blocks have no position of their own, so the source origin
(the world spawn) is used and the header names it, because an unlabelled "2.7km" is worse than
useless.

```
3 airfields, nearest first, from 0, 0 (world origin):
  airfield-1 36/18  180x25  662 blocks brg 081  parking 2
  airfield-3 36/18  91x25  1.7km brg 088  NO PARKING
  airfield-2 36/18  66x25  2.7km brg 089  reserved by #1
```

The list carries only what decides whether you want to open one: size, distance, bearing, marked
parking, who has it reserved (`RunwayOccupancy.holder`), `TOO SHORT` if an aircraft cannot use it,
and `NO PARKING` if it was surveyed under the stand rule and nobody has finished the job. Those last
two are the same red for the same reason: both are states in which a sortie is refused, and a browser
that shows a refusal as an absence gets blamed for the refusal. The full survey — slope, roughness, threshold elevations, approach obstacle counts, preferred
landing direction, parking spots and their state — is one click away in `airfields info`, because
twenty airfields' worth of that is not something anyone reads in chat.

**Interactive, and degrades to plain text.** The airfield name runs `airfields info`, coordinates
copy to the clipboard, `[show in world]` runs `airfields show`, which reuses the survey tool's own
particle highlight. `AutopilotOutput.component` flattens components to their string for a console,
so every row is written to read correctly without any of that — a click event is never the only
place a value appears.

**Localised without breaking the rig.** A dedicated server resolves translatable components through
`Language`, which only ever loads `/assets/minecraft/lang/en_us.json` out of the vanilla jar — a
mod's lang files are client assets and are never on that path, so a plain `Component.translatable`
prints its raw key in `console.log`. Everything user-visible here goes through `AutopilotText.tr`,
which is `translatableWithFallback`: a client renders the translation, the console renders the
English compiled in beside the key.

### How much runway an aircraft actually needs

`MIN_USABLE_RUNWAY_LENGTH` is **18 blocks**, and take-off is not what sets it. Simulating
`tickOnGround` from a standstill to `ROTATE_SPEED`, where `dragMul` is multiplied by
`20 × (3 − blockFriction)` — 48x on grass — gives a ground roll of 3.8 blocks at throttle 5 and
**1.9 at the booster's throttle 10**. The landing is the constraint, and within the landing it is
the *aiming*, not the braking: the roll-out from touchdown speed measures 1.1–1.3 blocks and does
not vary. So the number is the shortest aim offset the rule will ever produce (`TOUCHDOWN_AIM_MIN`,
6) plus everything that has to fit behind it (`LANDING_STOP_RESERVE`, 12).

**It used to be 30, and 30 was not honest.** It came out of `(TOUCHDOWN_AIM_OFFSET + 3) × 2` with an
aim offset that nothing in the flight director ever flew to — the aircraft aimed at the threshold
and stopped 3 blocks in, so 30 blocks of runway were being demanded for something that fitted in 5.
Now that the aim point is real the arithmetic can be done properly, and it comes out smaller.

Verified by landing on it. A strip of exactly 18 blocks, arrivals at the 0.40 minimum, the 2.60
default and the 2.80 maximum: touchdown at 7.8 / 10.1 / 8.6, stopped at 8.9 / 11.4 / 9.8, so between
6.6 and 9.1 blocks of runway left over in every case. A full `flight` sortie also *departs* an
18-block strip — park, taxi, line up, roll, rotate — and lands on a 24-block one. A 16-block strip is
still refused, with the numbers: `airfield-8 is 16 blocks long; an aircraft needs 18 to land on it.`

> **Behaviour change on existing worlds.** Nothing persisted is reinterpreted — an `Airfield` stores
> its two thresholds and derives the length from them, so no saved number changes meaning — but a
> surveyed 24-block strip that used to be marked `TOO SHORT` and refused at the command is usable
> after this update, without being re-surveyed. That is a change in what a world will let you do, and
> it is worth knowing before it surprises someone.

A field shorter than that is marked `TOO SHORT` in the list, warned about by the survey, and
**refused at the command** rather than discovered by an aircraft in the air — `flight` checks both
ends, `inbound` checks the destination.

`airfields info` also prints where an arrival will actually put its wheels, because the aim point is
derived from the length rather than fixed and there is otherwise no way to find out:
`touchdown aim 37 blocks in, stopping by about 49 of 183`.

### Management

`rename` and `remove` both refuse while an aircraft holds the runway — a flight in progress carries
the destination by name, in its flight plan, its reservation and the line it will print when it
lands.

Surveying the same strip twice used to accumulate `airfield-1`, `airfield-2`, … on top of each
other, with no way to tell them apart and no way to delete either. A survey whose two thresholds
both land within 12 blocks of a registered pair, in either order, now **replaces** that airfield and
keeps its name and its parking spots — re-marking a threshold that was a few blocks out is the
normal way to correct a survey, not a way to create a second field.

---

## 4b. Marked parking

Where an aircraft parks before it taxis used to be derived at spawn time by probing the ground beside
the threshold. That heuristic is still there and is still the fallback, but a player can now **mark**
the spots, the same way they mark a runway.

The **Runway Survey Tool** has two modes; sneak + right-click the air switches between them, and the
tooltip says which one it is in. In parking mode, right-click marks a spot on the nearest airfield
and sneak + right-click removes the one nearest the click. A mode on the existing tool rather than a
fourth item in the creative tab: an apron only means anything beside a runway that has already been
surveyed, so it is the second half of the same job.

Spots live on the `Airfield` record and persist in `SavedData` with the rest of the survey. The
codec field is optional with an empty default, so every airfield surveyed before this existed loads
unchanged and simply uses the derived apron.

**Marked spots are validated, not trusted** — the whole point is to stop an aircraft being put
somewhere it cannot leave. `Airfield.parkingSpotProblem` refuses, with the reason, when the spot is:

* further than `PARKING_MAX_TAXI_DISTANCE` (64) from the nearest threshold — the taxi is a straight
  line with no obstacle avoidance, so every block of it has to be level and clear
* on ground that is unknown or absent
* more than `PARKING_MAX_ELEVATION_DIFFERENCE` (2) off the runway elevation — the ground handling
  cannot taxi up or down a step
* separated from the threshold by anything that is not level all the way
* within `PARKING_SPOT_CLEARANCE` (5) of a spot that is already marked, or past `MAX_PARKING_SPOTS` (8)

A spot on the strip itself is accepted with a warning rather than refused; it is what the fallback
does anyway when nothing beside the runway is level.

**More than one spot per airfield**, deliberately. `Airfield.parkingPosition` tries them in the order
they were marked and takes the first that is both still usable *for this departure threshold* — which
end is the departure end is chosen per flight by `bestEnd`, so it is re-checked rather than assumed —
and not already occupied by another aircraft. So the first spot is the normal departure position and
the rest are where a queue forms behind it. Verified: two sorties launched a second apart parked at
`671, -59, 11` and `639, -59, 4`, the two marked spots, rather than on top of each other.

Anything that fails re-validation drops through to the next spot and finally to the derived apron, so
a marked spot can never strand an aircraft that would otherwise have flown.

Two of those rules had to change once arrivals started taxiing in and *staying* on stands, because
both were written when every aircraft that ever stood on one was a departure about to leave it:

* **The taxi distance from the departure threshold orders the stands**, rather than the order they
  were marked in. A spot is validated when it is marked against whichever threshold it is closer to;
  which end a sortie departs from is chosen per flight by `bestEnd`, so a stand 14 blocks behind one
  threshold of a 183-block strip is 169 blocks from the other, and sending a departure to that one
  while a nearer stand stands empty is a needless crawl down the runway.

  **It is a ranking and not a veto, and it was briefly written as a veto.** With
  `PARKING_MAX_TAXI_DISTANCE` applied in `usableParkingSpot` against the departure threshold, every
  stand on a strip longer than 64 blocks whose apron is grouped at one end — which is every apron a
  human builds — was thrown away on the departures that leave from the other end, and the aircraft
  was put on the derived apron or, when the ground off that end is not level, on the runway itself.
  Which end a sortie leaves from depends on where it is going, so the same field kept its stands for
  some destinations and lost them for others: from the ground it looks like `/autopilot flight`
  ignoring the marked parking at random. Measured on the rig on a 210-block strip with one stand 51
  blocks behind threshold 09: the sortie out of 09 spawned on the stand at `456, 102, 49`, and the
  sortie out of 27 spawned at `613, 102, 33` — on the runway, 2 blocks from the far threshold. Now
  the near stands are still tried first, in the order they were marked, and only if none of them
  qualifies does the nearest of the far ones get it; the derived apron is reached only when no marked
  stand is level, rollable and free. Unsurveyed ground is what marking a stand exists to avoid, so it
  never wins against a stand that works. The 160-block roll that follows lines up and departs
  normally — verified end to end, stand to stand, on the rig.
* **There is no "least bad" stand any more.** `markedParkingPosition` used to remember the first
  *occupied* spot and return it when nothing was free, on the reasoning that known-good ground beats
  a derived apron. With arrivals parked for good on those squares that reasoning spawns an aircraft
  inside another one — measured, once the third stand was ruled out by the rule above. It now falls
  through to the derived apron, and the derived candidates are checked for an aircraft standing on
  them as well, which they never were: the apron is a fixed offset from the threshold, so every
  departure from that end picks the same square.

A marked spot is now also somewhere an aircraft **waits** rather than merely somewhere it appears:
`PARKED` sits there for the ordered delay and then for as long as the runway is busy. That makes the
"more than one spot" rule load-bearing instead of cosmetic — with two spots marked, two sorties
ordered seconds apart wait on different squares while the first one uses the strip, and each is
listed under the field on `/autopilot tower` with what it is waiting for.

### A surveyed runway is not finished until a stand is marked

Marking parking used to be optional, and an airfield with none fell back to an apron derived from the
terrain — the heuristic that used to park aircraft in a hole. It is now **required**, and the
requirement is carried by a stored flag rather than by "has this field got any spots", for one
reason: a field surveyed before the rule existed also has no spots, and it has to go on working
exactly as it did.

`Airfield.requiresStands` is `Codec.BOOL.optionalFieldOf("requires_stands", false)`. An **absent key
means grandfathered**, which is what every airfield already on disk is; only a survey run by this
build writes `true`. Verified by reading the NBT of a world with all three cases in it — the two
airfields surveyed by the previous build carry no such key at all after loading, saving and even
being *re-surveyed*, and the one surveyed by this build carries it:

```
airfield-1  name threshold_a threshold_b width parking obstacles_a obstacles_b
airfield-3  name threshold_a threshold_b width parking obstacles_a obstacles_b requires_stands
```

Four things follow from the flag, and none of them touches a grandfathered field:

* **The survey says the job is not done**, in the report, with the next gesture spelled out:
  `NOT FINISHED: no parking marked … Next: sneak + right-click the air to put the Runway Survey Tool
  into parking mode, then right-click beside the runway. Or: /autopilot airfields park "airfield-3"
  <x y z>`.
* **The tool puts itself into parking mode** after such a survey. It is the same gesture sequence
  either way and the report has just said in words what the tool does silently; re-surveying a field
  that already has stands leaves the mode alone.
* **The browser marks it `NO PARKING`**, in red, in the same column and the same tone as `TOO SHORT`,
  and `airfields info` prints the whole instruction.
* **Sorties are refused at the command**, at both ends — `flight` checks the departure and the
  destination, `inbound` checks the destination. Refused rather than warned, and for the reason
  `TOO SHORT` is: a field with no stand is one an aircraft departs from a square nobody surveyed and
  arrives at by stopping on the landing area, which is the exact defect this feature exists to
  remove, so completing the flight and leaving the mess behind is not an outcome worth having. It
  costs nothing to obey — the refusal names the one command that fixes it.

**Re-surveying never changes the grandfathering.** A fresh survey sets the flag; a survey that
replaces a registered airfield keeps whatever that airfield had, alongside its name and its spots.
Re-marking a threshold that was a few blocks out is how a player *fixes* a runway, not how they opt
into a new requirement, and converting a working field into one whose sorties are refused would be
exactly the silent reinterpretation this design exists to avoid.

**Taxiing in is not gated on the flag.** Any field with a marked stand gets it, grandfathered or not;
any field without one keeps the old behaviour and says so. The flag decides only who is nagged and
who is refused.

---

## 4d. Taxiing in: runway → stand

After `ROLLOUT` the aircraft used to simply stop wherever it stopped, and the flight ended there —
on the runway. Measured on the rig before this change, six arrivals into one 183-block field:

```
654,-60,-47   654,-60,-44   654,-60,-42   654,-60,-39   654,-58.2,-45   654,-58.2,-41
```

Six aircraft inside eight blocks of runway, two of them **resting on the roofs of the others** at
`y = -58.2`, and every one of them reporting `landed at airfield-1/36 … 21% used` as though the
arrival had been tidy — `LANDING_ELEVATION_TOLERANCE` is 3 blocks, so a plane parked on another plane
is still "on the runway surface". The runway reservation was released correctly each time; what was
never released was the runway.

`TAXI_IN` is the arrival's ground phase. It is entered from the roll-out stop when — and only when —
the landing was real (`landingProblem` is null, so an aircraft that came to rest in the water or
fifty blocks off the centreline never taxis anywhere) and the field has a marked stand it can reach.

### The route is three straight legs

There is still no taxiway network, and this is not a path search. It is: turn off the side of the
strip, run down the apron, turn in. Both of the first two legs were forced by a measurement.

**Turning off first, rather than heading straight for the stand.** A stand beside the *far* threshold
of a 183-block runway is 150 blocks from where an arrival stops, and the straight line to it runs
down the strip for most of that. Measured: the aircraft would still have been holding the runway 545
ticks after touchdown, against 794 ticks for the whole arrival it is meant to improve on. Turning off
sideways costs about 16 blocks of extra track — 80 ticks at `TAXI_SPEED` — and clears the landing
surface in that time instead.

**Running down the apron rather than cutting across it.** Stands are normally marked in a row, and a
straight line from the runway to the far one goes through the near one, where an aircraft is very
likely to be standing. Measured on the two-stand rig with the direct route: two arrivals seconds
apart, the second correctly picked the further stand because the nearer was claimed, drove at it and
came to rest **against the first aircraft, 18 blocks short**. The middle leg is now flown one
`PARKING_SPOT_CLEARANCE` outboard of the outermost stand on that side — a taxiway lane in everything
but name — and the aircraft turns in only when it is abeam its own stand. It costs 64 ticks on the
183-block field (356 → 420) and converts that failure into two aircraft on two stands.

A stand that is not off to one side at all — marked off the end of the runway, or on the strip itself
— gets neither leg and is driven at directly. Every leg of whatever route comes out is checked for
level ground every 2 blocks before the aircraft is committed to it, by the same
`taxiPathIsRollable` a departure's spot is validated with, and a lane that fails falls back to the
direct line rather than costing the aircraft its stand.

### The runway comes back when the aircraft is off it

Not when it stops, and not on the mode change. `AutopilotMode.usesRunway()` deliberately does **not**
include `TAXI_IN`; `PlaneAutopilot.holdsRunway` keeps the reservation while the mode is `TAXI_IN` and
a flag written by the tick that measured it says the aircraft is still on the strip.

**"Clear" is a rectangle test against the survey, not a distance.** No distance answers this question.
Measured from the threshold the aircraft gets *further away* the whole time it is still on the
runway; measured from the centre it can be nearer after turning off than it was on the centreline.
`Airfield.isOnStrip(Vec3, margin)` uses the two coordinates the survey actually measured — how far
along and how far across — grown by `RUNWAY_CLEAR_MARGIN` (3) so that "clear" means the whole
aircraft is off rather than its centre being on the edge. It is the same pair of numbers the landing
report is written in.

Measured on the rig, one arrival into a 183-block field at 2.60, identical approach either way:

| | before | after |
|---|---|---|
| mode ticks: `approach` / `final` / `flare` / `rollout` | 626 / 947 / 1365 / 1412 | 626 / 947 / 1365 / 1412 — identical |
| landing report | `654, -60, -47 (38 blocks down the 183-block runway, 21% used)` | identical |
| flight ends | t = 1420, **on the runway** | t = 1841, on the stand at `672, -61, -8` |
| runway **reserved** | 626 → 1420 = **794 ticks** | 626 → 1577 = **951 ticks** |
| runway **physically obstructed** | 1412 → **for ever** | 1412 → 1577 = **165 ticks** |

Not one tick of the arrival moved: the approach, the gates, the flare and the roll-out are untouched,
and the landing line is the same to the block. The reservation is 157 ticks longer and that is the
honest trade — it now covers the part of the taxi that is genuinely on the strip, which nothing used
to cover at all. What used to end with an aircraft parked on the landing area for the rest of the
session ends with it parked on a stand 43 blocks away.

### Two aircraft must not want the same stand

`Airfield.parkingPosition` already skipped occupied spots, but "occupied" meant *an entity is standing
there* — which is the only state that existed when every aircraft that ever used a stand was already
on it. A taxi takes hundreds of ticks, and for all of them the aircraft is somewhere between the
runway and a square it fully intends to occupy. `PlaneEntity.canBeCollidedWith` is unconditionally
true, so two arrivals that pick the same square meet on it.

`Airfield.standFree` therefore asks three questions, and only the first of them existed before.

**1. Is anything standing on it.** The entity search. Unchanged.

**2. Is anything on its way to it.** `PlaneAutopilot.claimsStand`, **derived from the live autopilots**
rather than kept in a reservation registry, for the reason `RunwayOccupancy.activeCount` is derived —
a reservation with its own lifetime leaks one for every aircraft that goes away without running its
release path, which is what happens on every crash. An aircraft destroyed mid-taxi stops claiming its
stand in the same tick it stops existing. Both ends use this test, so a *departure* is not spawned
onto a stand an arrival is taxiing to either: verified on the rig, with #51 taxiing to the stand at
`672, -61, -8` a sortie ordered out of the same field was parked at `673, -59, 7`, the other stand.

**3. Is one remembered as standing on it, in a chunk nobody has loaded.** This one is
`StandOccupancy`, and it is the test that only shows up once you stop force-loading the rig.

> A parked aircraft has no autopilot, so it renews no chunk ticket. Forty ticks after it arrives its
> chunk unloads, the entity is written to disk and removed from the level, and every search of that
> square comes back empty. Measured with no force-loading at all: two sorties into one field with
> three stands, the first parked, the second landed 550 ticks later, searched the same square, found
> nothing and taxied on top of it. The identical pair of flights *with* the field force-loaded picked
> two different stands — which is the whole diagnosis in one pair of runs, and the reason this failure
> was invisible for the first half of the work: `forceload` hid it.
>
> Loading the chunk before asking is not enough either. Block data comes back from `getChunk`
> synchronously; entities do not, so a search run in the tick a chunk is pulled in still finds an
> empty stand. (Loading the airfield's stands is still worth doing and is now part of
> `AutopilotSpawner.loadAirfield`, because the *ground* test needs it — a stand in an unloaded chunk
> reads as "no ground there" and gets skipped, which was silently costing departures their marked
> apron.)
>
> So a stand is remembered from the moment an aircraft finishes taxiing onto it, by UUID, and the
> record is believed **unless the level can actually see the square**: `ServerLevel.areEntitiesLoaded`
> is exactly the vanilla predicate for "have this chunk's entities been deserialised", so the entity
> search is only trusted where it means something. Where it is not, the answer is *taken* — the same
> rule this feature already applies to unknown terrain, that "not loaded" must never be the cheapest
> answer. A stand nobody can look at costs one aircraft a taxi in, and it stops on the runway and says
> so; a stand wrongly called free costs two aircraft.
>
> It self-heals in the one way it has to: the first look at a loaded, empty square forgets the record,
> so an aircraft a player flies away does not leave its stand blocked for the session. It is
> runtime-only, like `RunwayOccupancy`, and a restart forgets everything — after which the plain
> entity search is back in charge and is right whenever the chunk happens to be loaded. That is a real
> hole and it is the cheap side of the trade; the alternative is persisting an occupancy that nothing
> can validate on load.

Measured end to end with **no force-loading**, three stands marked at one field, four arrivals:

```
#1 parked at airfield-2, 2673, -60, -178 (stand 2672, -61, -178, 343 ticks from the runway)
#2 parked at airfield-2, 2673, -60, -6   (stand 2672, -61, -8,   930 ticks from the runway)
#4 parked at airfield-2, 2673, -60, 7    (stand 2672, -61, 6,    487 ticks from the runway)
#8 stopped on the runway at airfield-2: no free stand it can reach from here.
```

Three aircraft, three stands, one each; the fourth had nowhere to go and said so. All four alive.

### What it does when it cannot

Four outcomes, all of them reported, none of them a wait. An aircraft that has just landed is
standing on the one surface every other aircraft at the field needs, so "hold here until something
frees up" is the one answer that must never be given.

| Situation | What happens | The line |
|---|---|---|
| No marked parking at all (grandfathered field) | Stops where it landed, exactly as every build before this | `stopped on the runway at airfield-2: no marked parking. Mark a stand with the Runway Survey Tool in parking mode, or /autopilot airfields park "airfield-2" <x y z>.` |
| Every stand taken, or none reachable over level ground | Stops where it landed | `stopped on the runway at airfield-3: no free stand it can reach from here.` |
| Blocked or stuck part way (100 ticks under `TAXI_IN_STALLED_SPEED`, or `TAXI_IN_TIMEOUT` = 2400 ticks) | Ends the flight where it stands | `stopped short of its stand at airfield-1, 679, -60, -24 (18 blocks to go, clear of the runway)` |
| Stand marked **on the strip** | One leg, no turn-off; the reservation is held to the end of the taxi, because an aircraft parked on the strip really is occupying it | `taxiing to the stand at 1655, -60, -59 via 1 leg` and then `parked at airfield-3` |

The stuck case was exercised deliberately by leaving a hulk on the apron lane: the taxiing aircraft
came to rest against it at 0.2 blocks/tick, both aircraft finished at full health, the flight ended
with the line above, and the tower board read `airfield-1 36/18 FREE` — the strip had been given back
156 ticks earlier. "Clear of the runway" versus "STILL ON THE RUNWAY" is spelled out in that message
because it is the only thing about a failed taxi that matters to anyone else.

### Watching it happen

`/autopilot status` gains three fields on a taxiing aircraft, because on the ground almost every
other field on the line reads the same as a stopped one:

```
#46 taxi_in pos=2668,-60,-153 agl=0 hdg=088 spd=0.18 thr=3 want[hdg=088 alt=-60 spd=0.20]
    tgt=2673,-60,-8 dist=145 rwy=airfield-2/18 stand=2673,-60,-8 to_go=145 rwy_held plan[straight in]
```

`rwy_held` becomes `rwy_clear` on the tick the rectangle test passes. `tgt`/`dist` follow the stand
rather than the threshold the aircraft has already crossed.

The tower board grows a section, and the aircraft moves into it at the moment it stops being the
runway's occupant, which is exactly when it would otherwise have vanished off the board while still
trundling across the field:

```
> autopilot tower
3 runways in this dimension, 0 occupied, 0 holding, 0 waiting to depart, 1 taxiing in.
airfield-2  36/18  FREE      no traffic
  taxiing to a stand (runway already released):
    #46 arrival 18, taxi_in, 0:30, 57 blocks to the stand [straight in]
```

---

## 4h. Helipads and helicopter sorties

A helicopter flies pad to pad the way a plane flies field to field, and almost nothing in between is
shared. This section is the whole of it: what a helipad is, why it is not an `Airfield`, how it is
marked, and why the rotorcraft flight director is a separate 600-line class rather than a mode of
the 2400-line one.

```
/autopilot helipad survey <corner1 x y z> <corner2 x y z>
/autopilot helipads [info|show|resurvey|remove|rename] …
/autopilot heliflight <"pad"> <"pad"> [speed] [delay <seconds>]
/autopilot heliinbound <x y z> <"pad"> [speed]
```

### A helipad is not a short runway

An `Airfield` is two thresholds and a width, and **everything** derived from it is a function of the
line between them. `RunwayEnd` exists only to name one direction along that line. The glide slope is
measured back down it, the aim point is a fifth of the way along it, the approach funnel is the
extended centreline, the parking apron is an offset from it, `isOnStrip` is a rectangle in its
coordinates, and the designators are its compass bearing.

A pad has no line. Encoding one as an airfield with the two thresholds on top of each other gives a
length of zero, a heading of whatever `atan2(0, 0)` returns, a designator that means nothing, an aim
offset of six blocks down a runway that is not there, and two "ends" that are the same point — and
every one of those numbers would then be printed by `/autopilot airfields`, sorted by
`AirfieldBrowser` and put on the tower board as a runway. Spreading the pad out into a short strip
instead is worse: it would make the arrival fly a centreline, which is the one thing a helicopter
arrival should not do.

So `Helipad` is its own record with its own list, **stored in the same `airfields.dat`** under its
own `helipads` key. That key is `optionalFieldOf` with an empty default, so a world saved before
this existed loads unchanged and a world with no pads writes no key at all. Names live in their own
space too — `helipad-1`, not `airfield-1` — so no command has to disambiguate and
`/autopilot heliflight "airfield-1"` simply says there is no such pad.

What is stored is the centre block, the radius, and a bitmask of which approach bearings were clear
when it was surveyed. Everything else is derived. The centre is stored as the **surface block**, the
same convention `Airfield` uses for a threshold, so `centre.y + 1` is where the skids rest and a pad
elevation and a runway elevation are directly comparable.

Nothing in the fixed-wing path can see a pad, and `TowerWatch` filters rotorcraft out of the tower
board outright: the board is about runways, and a helicopter filed under an "airfield" that does not
exist would be counted in the traffic totals of a field it is nowhere near.

### Marking one: a separate tool, and two corners

**A separate item, `Helipad Marker` (`helipad_tool`)**, not a third mode of the Runway Survey Tool.
The parking mode on that tool earned its place by being *the second half of the same job* — an apron
only means anything beside a runway that has already been surveyed, so the survey ends by switching
the tool into parking mode and the player carries on clicking. A helipad is not the second half of
anything. Three concrete reasons on top of that:

* **The gestures are already spent.** The runway tool uses right-click-block, sneak +
  right-click-block, right-click-air and sneak + right-click-air, and the last of those is the mode
  switch. A third mode makes the mode indicator the only way to know what a click will do, on a tool
  where one of the modes registers a permanent object.
* **The clicks mean different things.** Two clicks on the runway tool are two ends of a *line*; two
  clicks here are two corners of an *area*. One item means the same gesture builds a strip or a
  square depending on a mode set five minutes ago, and gets it wrong silently.
* **A half-marked shape is stateful.** Both tools remember the first click on the stack. Sharing one
  anchor component between two shapes would let a mode switch turn half a runway into half a
  helipad; giving them separate components on one item is two anchors on one tool, which is a
  separate tool with extra steps.

Gestures:

| Gesture | What it does |
|---|---|
| right-click a block | mark one corner of the pad, then the opposite one — which runs the survey |
| sneak + right-click a block | cancel a half-marked pad |
| right-click the air | list the helipads, nearest first |
| sneak + right-click the air | survey the 7×7 you are standing in the middle of |

**Two corners, because a pad is an area.** It is the selection idiom every Minecraft player already
knows, and unlike a runway's two thresholds it gives the extent directly: the centre is the middle of
the box and the radius is the larger of its two half-spans, so clicking opposite corners of a 7×7 pad
produces exactly that pad. The same block clicked twice is a 1×1 and is refused, because a one-block
pad has no cross-section to centre on and gives the arrival no lateral tolerance at all.

The last gesture exists because the pad a player wants is nearly always the one they are standing on,
and walking to two corners of a 7×7 to say so is three gestures where one will do.

### The marked shape and the used shape are the same shape

This is the lesson the runway survey learned expensively — it took the clicked blocks as the
thresholds, so a strip clicked on its edge was *flown* on its edge, and the whole take-off roll and
touchdown happened 6 blocks off the middle of a 13-wide plinth. The fix here is the same shape of
fix: **the seed centre is moved onto the middle of the pad the terrain actually shows**, probing
north/south/east/west for as long as the surface stays within a block of the seed's elevation and
moving to the middle of what it finds, iterated `SURVEY_CENTRING_PASSES` (3) times because moving the
centre changes what the probes see. The probe limit is the pad radius plus one — this is a correction
to a marked pad, not a search for a pad somewhere nearby.

**Both coordinates are always printed, and the derived one is the one the machine flies to.**
Measured on the rig against a 9×9 stone plinth 3 blocks proud of the superflat, with the two clicked
corners deliberately not symmetric about its middle:

```
Helipad helipad-3 registered - 7x7 at 1000, -58, 1000
  marked centre 999, -58, 999 -> pad centre 1000, -58, 1000 (moved 1.4 blocks); touchdown at 1000.5, -57.0, 1000.5
```

and then, from a sortie flown into it from 1414 blocks away:

```
Helicopter #254 landed at helipad-3, 1001.0, -57.0, 999.7 (0.96 blocks from the pad centre 1000.5, -57.0, 1000.5, tolerance 3.0; 2430 ticks from lift-off, 691 ticks from the run-in).
```

The touchdown coordinate in the survey and the pad centre in the landing report are the same number,
and the machine is within a block of it. That pair of lines is the assertion.

**Ground with no edges is left alone**, exactly as for a runway: on the open superflat both probes
run to the limit, the offset comes out zero and the clicked box is used unchanged. Testing the
centring needs a pad with a detectable lip; build a plinth.

### What the survey checks, and what it refuses

A pad has to pass four measurements. Every one of them reads **every block column** in its area —
there is no sampling step anywhere in this survey, for the reason in the next paragraph.

1. **The pad surface.** Every column of the `(2r+1)²` square: all loaded, all landable
   (`TerrainScanner.isLandable`, so a pond or a lava pool is not a pad however good the heightmap
   makes it look), and within `PAD_MAX_ROUGHNESS` (1 block) of the pad elevation. The elevation
   itself is the **modal** surface of the pad rather than whatever the middle column reads — see
   below.
2. **The column above it.** Every column of the pad *plus* a `PAD_CLEARANCE_MARGIN` (2 block) ring
   must be clear to `PAD_CLEAR_HEIGHT` (24 blocks). `MOTION_BLOCKING` reports the top of the highest
   thing in a column, so a branch overhanging the pad with air underneath it is caught — which a
   "walk up from the ground" test would miss. A departure is vertical, so everything in that volume
   is on the flight path.
3. **The approach sectors.** Eight bearings, 45° apart, each a wedge out to `APPROACH_LENGTH` (64
   blocks) checked against a 25° path that ends on the pad. A runway has one approach direction per
   end and an obstacle in it is fatal; a pad has as many as the terrain allows, and the question is
   not "is the funnel clear" but "is there *a* way in".
4. **At least one sector clear.** A pad in a courtyard with a single way in is a real helipad and
   refusing it would be wrong; a pad with no way in at all is a hole, and registering it would
   produce a machine that arrives overhead and cannot get down.

**A pad that fails is not registered at all.** That is the opposite of the runway rule, which
registers a too-short strip and marks it `TOO SHORT` in the browser — and the difference is that a
short runway is still a runway. A pad that fails here is not a pad; putting its name in the list
would mean a name no flight can ever use. Every refusal names the measurement that failed, the
coordinate, and what would fix it:

```
REFUSED: the pad is not all solid ground - 1197, -60, -3 is water or lava
REFUSED: the pad surface varies by 6 blocks (highest at 1300, -54, 0); flatten it to within 1
REFUSED: something stands 6 blocks above the pad at 1300, -54, 0; a departure is vertical, so the column above the pad and 2 blocks of ring around it must be clear to 24 blocks
REFUSED: no clear approach: every one of the 8 bearings has terrain across it inside 64 blocks. A machine could hover over this pad but not fly to it
```

#### Every block, because two blocks was not enough

The approach sectors were first written the way the runway funnel is written — sample along the
centre line every *n* blocks, a few lanes across — with `n` reduced from the runway's 10 to 2, on the
reasoning that 2 was small enough to be safe. It was not.

Measured on the rig with a **closed one-block-thick stone ring, 20 blocks tall, 9 blocks out from a
pad**: two of the eight sectors reported clear, and the pad registered. Nine is not a multiple of
two, so on those two bearings the wall sat exactly between the sample at 8 blocks and the sample at
10 and was invisible. This is the same defect as the bamboo report, at a fifth of the scale, and it
does not have a smaller step as its fix: a step small enough for a stone wall is still too big for a
fence post.

The sectors are now scanned by **one pass over the square that contains all of them**, reading each
column's surface once and testing it against whichever sectors it falls inside. A sector is a wedge
of half-angle 22.5° — so the eight tile the compass with no gaps between them — capped at
`APPROACH_MAX_HALF_WIDTH` (8 blocks) so that a sector at 64 blocks is a 16-block-wide corridor rather
than a 53-block one. The cost is the *area* rather than the area times the number of sectors: 129×129
heightmap lookups, once, at survey time. After the change the same ring reads `0 of 8` and the pad is
refused.

An unloaded column counts *against* a sector rather than being skipped, for the reason the whole
feature applies to unknown terrain: a survey is run standing on the pad and refuses unloaded ground
under it, so a column out here that cannot be read is genuinely unknown, and "nobody has loaded it"
must never be the cheapest way to pass.

#### The pad elevation is the pad's, not the middle column's

Found on the rig with a single stone block floating five above the middle of an otherwise perfect
pad. The elevation came from that column, so the survey decided the pad was at that height and then
reported the other 48 columns as being **six blocks below the pad** — it refused, which is right, but
for a reason that reads as nonsense, and the obstacle check said "nothing standing over the pad".

The elevation is now the **modal** surface across the pad. Modal rather than lowest, because a pad
with a one-block hole in it should not have its datum dragged into the hole; both are refused anyway,
and the modal answer is the one whose message a player can act on. The same block is now described as
what it is, twice, with the coordinate both times.

### Flying it: `HelicopterAutopilot`

**A separate class beside `PlaneAutopilot`, not a mode inside it.** About nine tenths of the 2400
lines describe things a helicopter does not have: the arrival planner reasons in turn radii,
intercept distances and extended centrelines; the throttle loop is fitted to a drag polynomial and a
stall speed; there is a departure plan that chooses which end of a strip to roll down, a taxi, a
taxi-in, a hold pattern sized on a bank angle, a go-around counter and a glide slope. A machine that
can stop in the air needs none of it, and adding a helicopter branch to each would mean touching the
arrival planner — the part of this feature that took three agents to get right and that is working,
tested and shipped — in order to teach it about an aircraft it will never fly.

It is also the split the airframe itself makes: `HelicopterEntity` overrides all six of
`PlaneEntity`'s flight hooks, so **not one line of the fixed-wing flight model runs on it** — no
wing, no lift, no stall speed, no take-off speed (`HELICOPTER-PHYSICS.md` §1.2). A controller shared
with the plane would be a controller with two disjoint halves.

The fixed-wing state machine is therefore **not modified at all**. `PlaneAutopilot` gains one field,
one guard at the top of `tick`, and a handful of one-line delegations:

```java
if (rotorcraft != null) {
    rotorcraft.tick(plane);
    return;
}
```

placed *after* the registry heartbeat and the chunk ticket, because a helicopter needs those exactly
as much as a plane does and nothing below them. When the field is null the fixed-wing path is
byte-for-byte what it was. What the two genuinely share is shared: the registry and its chunk
tickets, `TerrainScanner`, `AutopilotMath`, the persistence hook, `StandOccupancy` and
`AutopilotFeedback`.

The entity owns exactly one autopilot field and it is typed `PlaneAutopilot`, which is why the
rotorcraft controller arrives through that reference rather than through a second field. What it does
not have to do is share the state machine.

#### The profile

```
PARKED ─► TAKEOFF ─► CRUISE ─► DESCENT ─► FINAL ─► ROLLOUT ─► IDLE
                        │
                        └──► HOLD (pad occupied) ──┘
```

Existing `AutopilotMode` values, reused rather than added to, so `/autopilot status` and every
existing reader keep working.

| Mode | What it does |
|---|---|
| `PARKED` | On the departure pad, throttle shut, running the departure clock down |
| `TAKEOFF` | Straight up to `DEPARTURE_HEIGHT` (30 blocks). **No translation at all** until it is reached — the survey guarantees the column above the pad and a ring around it, and nothing else, so a departure that starts moving sideways early is a departure over ground nobody measured |
| `CRUISE` | To the hover point at cruise altitude, terrain-following, bleeding speed inside `DECELERATION_DISTANCE` (90 blocks) |
| `DESCENT` | The run-in: down to the departure height above the pad while closing on the hover point |
| `FINAL` | Overhead: stop, then go down, with the nose held on the bearing the run-in was flown on rather than chasing the pad |
| `ROLLOUT` | On the ground, throttle shut, waiting for `SETTLED_TICKS` (20) before the outcome is called |
| `HOLD` | Orbiting above an occupied pad, 15 blocks higher than the arrival height so two machines waiting are not in the same block of air |

The arrival geometry is one number, `HOVER_CAPTURE_RADIUS`, where the fixed-wing arrival needs a
dozen — a rotorcraft arrival has no shape to get wrong. The bearing it runs in on is the clear sector
nearest the direction it is already coming from, so a pad with one way in is approached from that way
and a pad with eight is approached from wherever the machine happens to be.

#### Written against quantities, and what that bought when the airframe changed

This controller was written while `HelicopterEntity` was being replaced underneath it. Every loop
therefore closes on something **measurable** — vertical speed, velocity error, heading — and the
mapping onto actual controls is confined to three short methods at the bottom of the class. When the
new flight model landed, the profile, the timeouts, the survey, the reporting and both flight laws
did not move at all; three actuators and four constants did. That is the whole argument for writing
it this way, and it is worth recording as a result rather than as an intention.

| control | how the loop uses it |
|---|---|
| **collective** (`setThrottle`) | a search for the notch whose *equilibrium* vertical speed is the one demanded — one notch every 2 ticks, with a one-step slam for the case that has no ticks to spare |
| **cyclic** (`setCyclicForward` / `setCyclicRight`) | proportional-plus-integral on the **velocity error**, integrated in the **world** frame and resolved into the two sticks every tick |
| **pedal** (`setPedal`) | `AutopilotMath.bangBang` on the heading error, unchanged from the fixed-wing rudder |

**The collective is a search, not a table and not a PID.** `HELICOPTER-PHYSICS.md` §2 measures the
ladder exactly — notches 0 to 5 settle at −8.6, −6.2, −3.5, 0.0, +2.7, +4.8 blocks per second, with
0.000 blocks of drift in 400 ticks at the hover notch — so "pick the notch nearest the demand" is
the entire vertical controller. Copying that table into the autopilot would have been the wrong way
to use it: those are the equilibria at a *level* disc, and level flight at 25° of tilt wants notch
3.31, which is not a notch. Searching finds 3 in a hover, dithers 3/4 in the cruise (which is what
§3 says to do, and what the fixed-wing throttle loop already does), and needs no revision if the
ladder moves.

**The pedal is bang-bang because the pedal is a rate command on an integrator.** `setPedal` is a
sign rather than a proportion, with a `YAW_RAMP` of 0.5 °/tick² — the same double-integrator shape
`PlaneEntity#tickYaw` has — so `AutopilotMath.bangBang`, which subtracts the angular stopping
distance `rate·|rate| / (2·accel)` from the error, is the correct controller for it with not a line
of change. Yaw is 3.0 °/tick at every airspeed and attitude, so a heading is a heading whether the
machine is hovering or at cruise.

**`setPitchUp` is never called on a helicopter.** It does nothing on this airframe *and* its sign
convention is the opposite of the cyclic's, so a controller reaching for it out of fixed-wing habit
would be writing into a control that is both dead and backwards.

#### The cyclic loop, and two ways of getting it wrong

The cyclic is a **position** command: hold the stick and the machine settles at a speed. That single
property decides the shape of the controller, and this loop was written wrong twice before it was
written right. Both failures are worth keeping, because both look like tuning problems and neither
is.

**First: proportional alone leaves a permanent shortfall.** `stick = G·(demand − v)` closes a loop
whose plant already has a finite gain `v = k·stick`, so its equilibrium is
`v = demand · kG/(1 + kG)` — not an offset that decays, a shortfall that stays. Measured with
G = 160 and the airframe's k of about 0.0125: a cruise commanded at 1.20 blocks/tick flew **0.815**,
and the predicted ratio for that loop gain is 0.67 against the observed 0.68. The cure is to
integrate the error onto the stick instead, whose equilibrium is where the error is zero whatever
the plant gain is.

**Second: integral alone oscillates, and it oscillates because of what is already in the chain.**
Tilt sets an acceleration, acceleration integrates to velocity, velocity integrates to position —
two integrations before the loop is closed, plus a proportional outer law on position. Making the
inner loop a third integration puts 270° of phase lag round it. Measured: an arrival held station to
within 2 to 3.5 blocks of the pad and oscillated there at 0.1 to 0.2 blocks/tick for the whole
2400-tick descent timeout, never slow enough to be allowed to let down, and reported honestly that
it could not settle. The proportional term is a velocity damper and is what stops that; the integral
stays, small and slow, purely to remove the shortfall on a constant demand.

**And the integrator has to live in the world frame.** Held in the body frame it goes into a limit
cycle for a reason that has nothing to do with gains: the body is turning at up to 3 °/tick under
it, so a stick position that meant "forward" a second ago means "sideways" by the time the pedal has
finished. Measured with the integrator in the body frame, an arrival limit-cycled at 4 to 7 blocks
and spun through 200° of heading doing it. Integrating in world coordinates and resolving into the
two sticks every tick makes the loop independent of what the nose is doing. The clamp is on the
vector rather than per axis, because the disc has one tilt: clamping x and z separately would let a
diagonal command ask for 1.41 times full stick.

#### Two axes, and why the arrival needs both

The one control idea in here worth reading, and it came out of a failure on the *previous* flight
model that the new one made easy to fix.

The first arrival law was the obvious one and the same one the fixed-wing controller uses: point the
nose at the pad and ask for a speed. Measured on the rig, a machine arriving over a pad on that law
**never got closer than 10.5 blocks**. It orbited the pad for the whole descent timeout and
reported, correctly, that it could not settle:

```
Helicopter #25 could not settle onto helipad-2 - 12.8 blocks off the pad centre and 30.5 blocks up after 2401 ticks, at 599.7, -29.5, 13.3 in final.
```

What made that an orbit rather than a wobble was that the only translational control was "accelerate
along the nose", so correcting a lateral error meant turning — and the machine kept its old velocity
while it turned. Point at the pad, accelerate, go past it; point at it again from the far side,
accelerate, go past it again.

The rewritten airframe has a second axis. `setCyclicRight` tips the disc sideways, and below
`HelicopterEntity.TURN_COORDINATION_SPEED` (0.80 b/t of *forward* speed) that is a pure sidestep
with no turn in it at all; an arrival flown at `APPROACH_SPEED` is comfortably inside that band. So
the arrival law is: take the velocity the machine wants — towards the point, at the fastest it could
be going and still stop on it — subtract the velocity it has, and put the difference on the two
sticks. A drift is corrected by tipping the disc sideways, not by turning. Where the nose points is a
separate question, and inside the let-down the answer is "hold the bearing you ran in on" — see
"Four things flying it found" below for what happens when it is allowed to chase the pad instead.

Braking is the same command with the sign reversed, which on a position-command cyclic is simply a
negative stick. Full aft is 24 blocks/s to a stop in 60 ticks and 43 blocks (`HELICOPTER-PHYSICS.md`
§3), so there is **no deceleration table anywhere in this arrival** — nothing of what the fixed-wing
side needs 270 blocks of runway-in to do. What there is instead is one line of arithmetic: the
closure demand is `sqrt(2·CLOSURE_BRAKING·distance)` capped at `APPROACH_SPEED`, a constant-
deceleration profile rather than a schedule fitted to anything. It replaced a demand proportional to
distance, and the 2x2 that chose it is in `RotorcraftConfig#CLOSURE_BRAKING`.

Two laws, chosen by phase. The transit uses bearing-and-speed, which is right while the target is
hundreds of blocks away and is what the fixed-wing cruise does; it drives the longitudinal stick only
and leaves the lateral one centred, because at transit speed a bank is a turn rather than a sidestep
and the pedal is this airframe's turn control. Everything from the run-in inwards uses
`HelicopterAutopilot#station`, which drives both.

#### Measured, eight sorties on a world built from nothing

All on the headless rig, one machine at a time, **no force-loading during any flight**, world wiped
and rebuilt for this table, pads cleared between runs. The world:

| pad | what it is | how it was marked |
|---|---|---|
| `helipad-1`, `helipad-2` | flush 7x7 on the superflat | `/autopilot helipad survey` |
| `helipad-3` | 15x15 stone plinth, 2 blocks proud | survey, from **two adjacent corners** |
| `helipad-4` | 7x7 in a walled courtyard with one 9-block gap on the east side | survey |
| `helipad-5` | 3x3 — the smallest the survey registers | survey |
| `helipad-6` | flush 7x7 | **the Helipad Marker, two right-clicks** |
| `helipad-7` | flush 7x7 | **the Helipad Marker, sneak + right-click the air** |

| from → to | distance | commanded | cruise made good | off the pad centre | lift-off → touchdown |
|---|---|---|---|---|---|
| 1 → 2 | 600 | 1.20 | 1.101 | 0.12 | 1094 |
| 2 → 3 | 400 | 1.20 | 1.072 | **0.09** | 898 |
| 3 → 4 | 600 | 1.20 | 1.102 | 0.12 | 1047 |
| 4 → 5 | 600 | 1.20 | 1.101 | 0.12 | 1094 |
| 5 → 6 | 600 | **0.50** | **0.524** | **0.02** | 1604 |
| 6 → 7 | 600 | 1.20 | 1.101 | 0.12 | 1094 |
| 7 → 1 | 3400 | **1.75** | 1.107 | 0.08 | 3623 |
| 1 → 3 | 1000 | **0.80** | **0.817** | 0.12 | 1741 |

**Eight of eight landed on the pad. Worst lateral error 0.12 blocks, mean 0.10.** Two `heliinbound`
arrivals flown separately — one from 300 blocks off the pad's axis, one into the courtyard from the
opposite side to its only gap — landed 0.14 and 0.15 off. The other numbers are the same on every
sortie to the tick, because none of those phases is speed-dependent:

* **spawn to airborne: 2 ticks.** The machine is spawned one block above the pad and is climbing on
  the third tick.
* **the vertical departure: 138 ticks** to `DEPARTURE_HEIGHT`, every run.
* **the run-in: `FINAL` entered 23.5–24.0 blocks from the pad**, 30 above it.
* **run-in call to standing still: 376–491 ticks.**

**The cruise is 1.10 whatever it is told above that, and it now says so.** Level flight at full
forward cyclic wants collective 3.31, the collective is an integer, and the loop dithers 3/4 —
`HELICOPTER-PHYSICS.md` §3 says exactly this. Commanded 0.50 is made good at 0.524 and commanded 0.80
at 0.817, with the cyclic off its stop 96% of the time; commanded 1.20 and commanded 1.75 both fly
1.10 with the stick pinned for the whole leg. That last case used to be silent, and it was a real
piece of dishonesty — a 3400-block leg ordered at 1.75 and the same leg ordered at 1.20 took the same
number of ticks with two different numbers echoed back at the player. One line, once, in the air:

```
Helicopter #253 cannot make good 1.75 blocks/tick in level flight - full forward cyclic is holding 1.11. The leg will take that much longer.
```

The test is "the stick is on its stop **and** the speed is short", not "the speed is short": a machine
short because it is climbing, turning or still accelerating is not being lied to about anything, and
200 ticks of settling keeps the departure out of it.

**The plinth is the row that proves the geometry.** `helipad-3` was marked by clicking two *adjacent*
corners of the plinth, so the midpoint of the clicks — the thing a naive survey would use — is on the
plinth's southern edge, seven blocks off centre. It is the pad version of the runway survey bug that
put thresholds on the blocks the player clicked and then landed aircraft on the edge of the strip.
Survey and landing, printed by two different pieces of code:

```
marked centre 1000, -59, -7 -> pad centre 1000, -59, 0 (moved 7.0 blocks); touchdown at 1000.5, -58.0, 0.5
Helicopter #118 landed at helipad-3, 1000.6, -58.0, 0.5 (0.09 blocks from the pad centre 1000.5, -58.0, 0.5, tolerance 7.0; …)
```

`touchdown at 1000.5, -58.0, 0.5` and `pad centre 1000.5, -58.0, 0.5` are the same coordinate. **The
marked shape and the used shape are the same shape**, and the correction is never silent: both
coordinates and the distance between them are on the survey line every time.

#### Six things flying it found that compiling it did not

Every one of these passed a build and read as ordinary telemetry.

**The nose pirouetted over the pad.** The station-keeping law points the nose at its target, with a
2.5-block deadband to stop it hunting once it is on top of it. That deadband cannot help, because the
event that starts the spin is bigger than it: the machine overshoots the pad by about 3 blocks — the
stopping distance from `APPROACH_SPEED` — the bearing to the pad reverses, and the pedal is asked for
180° at 3 °/tick. Measured, with the machine wandering 603.7 → 598.0 → 601.3 on one axis while it did
so: 298 ticks in `FINAL` against the 146 the commanded descent rates need. The let-down now holds the
bearing the run-in was flown on and does not turn at all; the lateral cyclic corrects the drift.

**The last ten blocks were under-braked, and it took two changes to see it.** The closure demand was
proportional to distance and the proportional gain of the velocity loop was 250, i.e. full stick at
0.40 b/t of error — so a machine 2.6 blocks out doing 0.29 b/t was asking for 45% of a stick it needed
most of. Neither change alone did much; the pair is a factor of six.

| | gain 250 | gain 500 |
|---|---|---|
| demand ∝ distance (0.04/block) | 0.20–0.86 | 0.69, 0.69, 0.71 |
| demand = √(2·0.003·distance) | 0.72, 0.72, 0.75 | **0.12, 0.12, 0.13** |

They interact because they are the two halves of one loop: the proportional demand collapses faster
than the machine can follow it near the pad (0.04 b/t at one block out, against the 0.077 the braking
profile asks for), so the stick centres and the machine coasts the last block on drag — and at the old
gain the loop could not track either demand well enough for the difference to show. Gain 900 was also
tried: it buys ten ticks, spends a block of overshoot on them and stops improving the touchdown, so
500 is the interior point.

**A machine that came to rest on a roof reported `landed`.** A pad was surveyed clear, a stone roof
was built 16 blocks over it, and the arrival flew a faultless approach onto the roof:

```
Helicopter #1 landed at helipad-6, 2800.5, -44.0, 0.5 (0.03 blocks from the pad centre 2800.5, -60.0, 0.5, …)
```

Two coordinates in one sentence that contradict each other, and nothing was comparing them: the
verdict was `miss <= tolerance && onGround && !onWater`, with **no vertical term at all**. This is the
rotorcraft form of the plane that reported `landed` after ditching in the sea. `landingProblem` now
applies the same elevation test, and the same `LANDING_ELEVATION_TOLERANCE`, that the fixed-wing side
has had since that bug was fixed there:

```
Helicopter #100 did not land on helipad-6: came to rest 16 blocks above the pad surface - on something the survey did not measure, at 2800.5, -44.0, 0.5 (pad centre 2800.5, -60.0, 0.5, tolerance 3.0). …
```

**A refused survey printed `clear approach bearings: none`.** A pad refused for its size, or for
standing on unloaded ground, never reaches the sector scan, and the empty array read out as a
measurement. "Never looked" and "looked, and there is no way in" are different facts and the second is
much worse news; the line now says `not measured - the pad was refused before the sector scan`.

**Two machines bound for one pad deadlocked each other over empty ground.** `Helipad#free` asks the
live autopilots whether any of them claims the pad, and `claims` returned the *destination* for every
mode after take-off — so a machine claimed its destination from the moment it lifted off, 1600 blocks
away. Three sent to one pad from 600, 1000 and 1600 blocks out therefore all reported the pad
occupied while it was bare ground, two ran the full 3601-tick hold timeout out and gave up, and the
third only landed because the other two had by then stopped being live autopilots:

```
Helicopter #78 holding over helipad-4: the pad is occupied.        <- the pad is empty
Helicopter #78 helipad-4 never became free - held over it for 3601 ticks
Helicopter #77 helipad-4 never became free - held over it for 3601 ticks
```

Going somewhere is not being there. `CRUISE` and `HOLD` now claim nothing and the claim starts at
`DESCENT`, which is the point the machine is committed to the pad; the departure end is claimed in
every mode, because a machine standing on a pad is on it. Re-flown, the nearest machine runs in and
lands 0.12 from the centre and the other two hold — correctly, because the pad now genuinely has
something on it.

**Machines holding for the same pad flew into the same block of air.** The orbit point walks round
the pad on `modeTicks`, so two holders converge on it: measured at `1618.3, 17.1` for both, 0.3 blocks
apart vertically. Helicopters are hard-colliding entities and `PlaneCollisions` reads a blocked
`move()` as an impact, so that is a way to destroy two aircraft rather than to separate them. The
hold now takes its level *and* its starting angle from the entity id, the same rule the fixed-wing
stack uses — four slots, 10 blocks apart, a quarter of the orbit apart. Re-flown with four machines
converging: `agl=45.0`, `55.1`, `64.8`.

#### The descent is gated on the lateral error

`FINAL` holds height until the machine is over the pad *and* has stopped, then lets down — and stops
letting down again if it drifts back off. Without the gate the arrival lands wherever the drift left
it and reports it as a landing, which is the rotorcraft version of the plane that reported `landed`
after ditching in the sea 200 blocks short.

The altitude the descent is commanded to is one block *below* the pad rather than the pad itself. The
altitude loop's demand fades to zero as the error does, so a demand that ends exactly on the surface
leaves the machine hovering a fraction of a block above it for ever.

#### The report is the outcome, not the mode

Two landing outcomes, and the difference between them is one **measured distance** rather than the
mode the machine happened to be in:

```
Helicopter #132 landed at helipad-2, 600.8, -60.0, 1.1 (0.69 blocks from the pad centre 600.5, -60.0, 0.5, tolerance 3.0; 1247 ticks from lift-off, 478 ticks from the run-in).
Helicopter #7 did not land on helipad-2: came down at …, 9.4 blocks from the pad centre … (tolerance 3.0), in the water. …
```

Everything that ends in the air says why, with the position and the mode:

* `could not get off <pad> - N blocks up after M ticks, against the 30 it needs`
* `gave up en route to <pad> - N blocks still to run after M ticks, against the K a straight leg at S blocks/tick needs`
* `could not reach the hover point over <pad> - still N blocks short after M ticks`
* `could not settle onto <pad> - N blocks off the pad centre and M blocks up after K ticks`
* `<pad> never became free - held over it for N ticks`
* `lost at x, y, z in <mode>, N blocks short of <pad>` — from `PlaneEntity#remove`, so a machine that
  is destroyed, killed or stopped by hand still produces a line

The en-route timeout exists because of the cargo plane that orbited a field for 24000 ticks without
landing, without going around and without a single line saying so. It is
`TRANSIT_TIMEOUT_FACTOR` (3) times the time a straight leg at the commanded speed would need, plus
`TRANSIT_TIMEOUT_MARGIN` (1200) ticks for the climb, the turn and the hover.

#### One pad, one machine

`Helipad#free` asks the same three questions `Airfield.standFree` asks about a parking stand, and
derives the answer the same way rather than storing it, so a machine that crashes or is killed stops
claiming its pad without anything having to notice: an entity search, a walk over the live autopilots
asking `PlaneAutopilot#claimsStand` (a pad and a stand are both "one square, one aircraft", so it is
the same call), and `StandOccupancy` for the machine that has landed and gone quiet in a chunk nobody
is loading.

An arrival that finds the pad taken enters `HOLD` and polls every `PAD_POLL_INTERVAL` (20) ticks —
the same interval a fixed-wing departure polls its runway on, so neither can poll the other out by
asking more often.

**What a machine claims depends on where it is in the flight, and that is not a detail.** A
destination is claimed from `DESCENT` onwards and not before: `CRUISE` and `HOLD` claim nothing,
because going somewhere is not being there. Claimed from the launch — which is what it did first —
any two machines bound for one pad deadlock over empty ground; see "Six things flying it found"
below. The departure pad is claimed in every mode it applies to, because a machine standing on a pad
is on it whatever its mode says.

One consequence worth knowing on a test rig: `StandOccupancy` keeps a record until a look at a
*loaded* chunk has read the square empty for a second, so killing a machine on a pad and immediately
ordering another gets `helipad-2 already has an aircraft on it` for about one second. Ask
`/autopilot helipads` twice with the chunks resident and the record clears.

### Helicopters on the fixed-wing commands

`AircraftType.HELICOPTER` exists now, so `/autopilot status` and the tower board can name what they
are looking at, and `AircraftType.of` returns it — matched before the three fixed-wing airframes,
matched on the `EntityType` and never on the class. `HelicopterEntity` used to extend
`LargePlaneEntity` and now extends `LargeAirframeEntity` beside it, so an `instanceof` written
against either would have been silently wrong on one side of that change; an `EntityType` comparison
was right before it and is right after it.

It is **not** in the list `random` draws from, and `/autopilot route|flight|inbound … type helicopter`
is refused rather than substituted:

```
A helicopter cannot use a runway sortie: it has no take-off roll and nothing on the approach applies
to it. Mark a helipad (/autopilot helipad survey) and use /autopilot heliflight instead.
```

A refusal and not a silent substitution, because all three of those commands are written in runways,
thresholds and glide slopes, and a machine that cannot use any of the three would not fly them badly
— it would sit on a threshold for ever.

---

## 5. Terrain following and obstacle avoidance

Deliberately cheap and predictable — no A*, no world search:

* 12 forward samples out to 220 blocks along the ground track, plus 4 samples in each of two side
  sectors at ±35°.
* Each sample is a single `Level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)` — an **O(1)**
  heightmap lookup, not a block scan. `Level#getHeight` checks `hasChunk` first and returns
  `getMinY()` for absent chunks, so this never forces chunk loading.
* Commanded altitude is raised to `highest terrain ahead + 22`.

Total cost is roughly 20 heightmap lookups per aircraft per tick, and the number of live autopilots
is capped at 24.

### Over it or round it: `RoutePlanner`

Raising the altitude is the right answer for a hill and the wrong one for a mountain. Reported from
a user's world: a runway at 69 with a summit at **158 immediately off the north threshold** and open
water at **61** a short way west. The aircraft climbed ~90 blocks to cross the summit and then dived
back down onto the threshold — buying height exactly where it needed to be low and slow — when a
small sidestep west was clear the whole way.

`TerrainScanner.avoidanceBias` did have a sidestep, but it was a reflex, not a decision: it only
fired when the ridge could not be out-climbed **at all**, and it chose its side by comparing two
fixed ±35° probes. Measured on the rig, that sent the aircraft into the *higher* flank and down to
**15 blocks of ground clearance against its own 22-block minimum**.

`RoutePlanner` makes it a choice, scored in one currency:

```
cost(heading) = extra track flown  +  CLIMB_TRACK_COST x blocks of climb needed
```

* **Candidates** are the current heading plus 0, ±10 … ±60°. Flying straight on is one of them, so
  "over" wins whenever it really is cheaper — which over open or rolling ground it always is.
* **A block of climb is worth six blocks of track.** A block of height at `MAX_CLIMB_ANGLE` costs
  `1/tan(18°)` = 3.1 blocks of track, and every block bought to cross a ridge is given back on the
  far side. Six is that round trip. Deliberately not larger: this exists to refuse 90 blocks of
  climb for a summit a 60-block sidestep clears, not to detour round every hummock.
* **Extra track** for a deviation of `d` held for `L` and then undone is `2L(1/cos d − 1)`: 46
  blocks at 30° over a 150-block horizon, 300 blocks at 60°.
* **Unknown ground is never cheap.** The *horizon* is how far the ground straight ahead is actually
  loaded; nothing is planned beyond it, and a candidate with a single `UNKNOWN_HEIGHT` column inside
  that horizon is **discarded**, not optimistically scored. Flying straight on needs no evidence and
  is always available, so with nothing loaded the planner returns zero and the old terrain following
  is exactly what happens. This is the same rule that `Airfield#bestEnd` was fixed with, applied
  before it could be broken again.
* **Hysteresis.** A chosen deviation is held for 60 ticks, and the side already being flown keeps a
  25-block bonus, so a marginal choice cannot alternate. Without it the aircraft weaves.

Cost: the search runs **only when the terrain ahead would force a climb**, and then at most every 20
ticks — 13 candidates × 8 samples = 104 lookups a second per aircraft, about 5 a tick. That is a
quarter of what the always-on scanner profile already costs. Over flat ground it never runs at all.

The chunk-ticket lead was raised from 20 to 40 ticks for it. Deciding to go *round* something needs
more warning than deciding to climb over it: at cruise speed a 20-tick lead put the edge of the
loaded area ~116 blocks ahead — half of `SCAN_DISTANCE`, so the outer half of the profile was always
blind — and 116 blocks is 45 ticks against a 60-block turn radius. 40 ticks puts it near 170 and the
two ticket bubbles still overlap (each makes 64 blocks resident; the lead is 104 at cruise speed).

Measured on the rig, arriving down the extended centreline with the summit 89 blocks above the
runway and clear water 60 blocks west (`/autopilot inbound 0 -30 -1000 "airfield-1" 2.60`):

| | before | after |
|---|---|---|
| highest ground crossed | runway + 59 | runway + 39 |
| minimum clearance | **15** (inside the 22-block minimum) | **27** |
| side chosen | east, into the higher flank | west, over the water |
| what `status` said | nothing | `plan[around right 20 deg, saves 37 of climb]` |

The control matters as much as the case: on the open superflat the same build prints `plan[direct]`
for every sample of a 2800-block route and never leaves its track, and a 1-block ridge reads
`plan[over, 1 to climb]` rather than being detoured around.

**Improvised landings.** With no surveyed airfield in range, `Airfield.flattestHeading` scores 12
candidate directions around the first waypoint by summed height change and builds a throwaway 80×8
strip along the flattest one. It is a field landing and it can be rough — survey a real runway if
you want a reliable one.

---

## 6. Persistence

| Data | Where | Survives restart |
|---|---|---|
| Airfields, including marked parking spots | `SavedData` per dimension, `data/simpleplanes/airfields.dat` | **Yes** |
| Whether an airfield is held to the stand rule | `requires_stands` on the airfield, optional, default false | **Yes** — an absent key means grandfathered |
| The stand an arrival is taxiing to, and the legs to it | Flight director only | No — see §9; a reloaded `TAXI_IN` simply stays parked where it is |
| In-progress route flight | Plane entity NBT, via `FlightPlan.CODEC` | **Yes** |
| Departure delay *ordered* | Plane entity NBT, `departure_delay` on the plan | **Yes** |
| Departure delay *remaining* | Flight director only | No — a reloaded `PARKED` becomes `TAKEOFF`, see §9 |
| Half-drawn route / half-marked runway / half-marked helipad | Data component on the item | **Yes** |
| Surveyed helipads | `SavedData`, `helipads` key in the same `airfields.dat` | **Yes** |
| In-progress helicopter sortie | Plane entity NBT, `kind: "heli"` on the plan | **Yes** — resumed in transit, see §4h |
| Runway reservations | In memory | No — and correctly so, they are re-derived on load |
| Which stands have an aircraft parked on them | In memory (`StandOccupancy`) | No — a restart falls back to the entity search; see §4d |
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

Other files: four items registered in `setup/SimplePlanesItems.java` (plus creative tab entries),
and two init calls appended in `SimplePlanesMod.onInitialize()`.

`entities/HelicopterEntity.java` — **not changed at all**. The rotorcraft flight director drives it
through the same public surface a player's keyboard does: `setThrottle`, `setPitchUp`, `setYawRight`,
the autopilot's `moveForward`/`moveStrafing`, and the existing public `setMoveUp`.

---

## 8. Testing without a client

`/autopilot` (permission level: gamemasters) drives the whole feature from a dedicated server.
**No subcommand requires a player** — every one takes explicit coordinates, so they all run from the
server console, a command block or a datapack function. A player is an optional convenience: it
makes relative coordinates (`~ ~ ~`) work and decides which side an attack run comes in from.

```
/autopilot strike <x y z> [distance] [bearing] [blast] [blocks] [fire]
                                                 launch an attack run
/autopilot tool <distance> [bearing] [blast] [blocks] [fire]
                                                 write those settings onto the held strike tool
/autopilot route <x y z> <x y z> [speed] [type <airframe>]
                                                 fly A -> B -> A and land
/autopilot flight <from> <to> [speed] [delay <seconds>] [type <airframe>]
                                                 full sortie between two registered airfields;
                                                 delay is how long it waits on its parking spot
/autopilot inbound <x y z> <airfield> [speed] [type <airframe>]
                                                 one-way arrival into a named airfield
/autopilot survey <x y z> <x y z>                survey a runway between two thresholds
/autopilot tower [<airfield>]                    runway states: free/occupied, by whom, who is holding
/autopilot status                                full telemetry for every autopilot aircraft
/autopilot stop                                  stop every autopilot aircraft in this dimension

/autopilot airfields                             browse, nearest first
/autopilot airfields info <airfield>             the full survey of one field
/autopilot airfields show <airfield>             draw it in world with particles
/autopilot airfields resurvey <airfield>         re-measure it from its own stored thresholds
/autopilot airfields rename <airfield> <name>    rename it
/autopilot airfields remove <airfield>           delete it
/autopilot airfields park <airfield> <x y z>     mark a parking spot
/autopilot airfields unpark <airfield> <x y z>   remove the marked spot nearest that point

/autopilot helipad survey <x y z> <x y z>        survey a helipad between two opposite corners
/autopilot heliflight <pad> <pad> [speed] [delay <seconds>]
                                                 full helicopter sortie between two pads
/autopilot heliinbound <x y z> <pad> [speed]     one-way arrival onto a named pad
/autopilot helipads                              browse the pads, nearest first
/autopilot helipads info <pad>                   the full survey of one pad
/autopilot helipads show <pad>                   draw it, and its clear approaches, with particles
/autopilot helipads resurvey <pad>               re-measure it from its own stored centre
/autopilot helipads rename <pad> <name>          rename it
/autopilot helipads remove <pad>                 delete it
```

`speed` is the cruise speed in blocks per tick, clamped to 0.40-2.80. Omitted, it is
`CRUISE_SPEED` — see [The default is fast](#the-default-is-fast). The helicopter commands have their
own range, **0.20-2.00**, defaulting to 1.20: a rotorcraft's useful band starts below a plane's stall
speed and stops well below its cruise, so sharing the fixed-wing clamp would silently raise a slow
helicopter flight to 0.40.

`flight` and `inbound` take airfield **names** (tab-completed from the registered list, quoted
because names contain a hyphen), so they need no block-position argument at all and cannot be
refused for pointing at unloaded ground — which is the normal case, not an edge case, since both
runways are usually nowhere near a player.

`bearing` is the compass direction the attack run comes in *from*, 0–359 — where the aircraft is
placed relative to the target, not the direction it then flies. That is the useful way round,
because what someone choosing a bearing is picking is which side of the target the run-in passes
over. Measured against a target at `0 80 0` from 100 blocks:

| `bearing` | Spawned at | Attacks toward |
|---|---|---|
| 0 | `1, -99` (north) | south |
| 90 | `101, 1` (east) | west |
| 180 | `1, 101` (south) | north |
| 270 | `-99, 1` (west) | east |

Omit it and the bearing is derived from wherever the command was issued (the player, or the
console's world-spawn origin); if that origin sits on top of the target it falls back to a fixed
due-south run-in. Given explicitly, the whole flight is deterministic and repeatable, which is what
makes headless testing useful.

### Which airframe flies

`type <plane|large|cargo|random>` on `route`, `flight` and `inbound`. `random` draws from the three
fixed-wing airframes; **helicopters are excluded deliberately**, because `HelicopterEntity` overrides
`tickPitch`, `tickRoll`, `tickRotateMotion` and `getTickPush` — the control laws do not describe it,
so dispatching one would not fly it badly, it would fly something the flight director has no model of.

A keyword branch for the same reason `delay` is one, and accepted after it, so the two read in the
order a person says them (`… delay 30 type cargo`). Omitted, the airframe is the starter plane,
exactly as before.

The three are not interchangeable. `getRotationSpeedMultiplier` scales both the pitch and the yaw
ramp, and the turn radius that falls out of it is what the whole arrival has to be sized around:

| airframe | multiplier | max yaw | max pitch | turn radius at approach speed |
|---|---|---|---|---|
| `plane` | 1.0 | 2.5 deg/tick | 5.0 deg/tick | 11.5 blocks (measured 11) |
| `large` | 0.5 | 1.25 deg/tick | 2.5 deg/tick | 22.9 blocks (measured 23) |
| `cargo` | 0.2 | 0.5 deg/tick | 1.0 deg/tick | 57.3 blocks (measured 56) |

All three now fly a complete sortie — park, taxi, depart, cruise, approach, land — at every commanded
speed, into both a 183-block and an 80-block runway. Getting there needed two changes, and neither of
them touches a landing gate, a tolerance or the glide slope.

#### The nominal yaw rate is a lie above about 1.5 blocks/tick

`MAX_YAW_RATE * getRotationSpeedMultiplier()` is what `tickYaw` clamps the **nose** rate to, and the
flight director had always used it as the turn rate. It is accurate when the aircraft is slow and
optimistic when it is fast, because `tickRotateMotion` only pulls the velocity vector round to follow
the nose at a finite rate. Peak sustained rates with the yaw control saturated throughout, measured
by flying a 180-degree turnback at a commanded speed:

| airframe | speed | nominal | measured | radius |
|---|---|---|---|---|
| `plane` | 1.16 | 2.5 | 2.065 | 32 |
| `large` | 1.34 | 1.25 | 1.025 | 75 |
| `cargo` | 0.50 | 0.5 | **0.507** | 56 |
| `cargo` | 1.56 | 0.5 | 0.503 | 178 |
| `cargo` | 1.98 | 0.5 | **0.296** | 380 |

At approach speed the nominal figure is exactly right, which is why `approachTurnRadius` is evaluated
there and carries no margin. `TURN_RATE_MARGIN` (0.6) exists only for the speed cap below, where the
aircraft is fast and the model is not.

#### The descent could latch, and did

The failure that mattered was not a bad landing. It was a flight that never ended.

`tickDescent` commands the bearing to the approach fix and brakes on a schedule keyed to the distance
to it. The cruise leg ends over the destination and the fix sits on the extended centreline on the
far side of it, so the fix is routinely 300–400 blocks away and abeam or behind. A cargo plane
leaving cruise turns at 0.30 deg/tick at 1.98 blocks/tick — a **380-block radius** — so it could not
turn tightly enough to reach the fix; the distance to the fix therefore never fell; the schedule
therefore never braked it; and it flew a circle around the fix at a steady 24 degrees of bank with a
heading error pinned between 73 and 101 degrees. Measured: **24 000 ticks and still going** — no
landing, no go-around, no outcome line at all. Speed was both the cause and the thing the loop
refused to give up.

`PlaneAutopilot#turnLimitedSpeed` closes it. An arc that leaves the current heading and passes
through a point `d` away, `θ` off the nose, has radius `d / (2 sin θ)`, so the aircraft can make the
point only while `v ≤ ω·d / (2 sin θ)`. The descent flies the lower of that and the existing
schedule. It is self-correcting rather than permanently conservative: the cap slows the aircraft, and
slowing down is exactly what makes the nominal turn rate true again, so the cap stops binding.

#### Every arrival begins with a reversal, and it scales with turn radius

This is structural, not accidental, and it is the same manoeuvre for all three airframes. Flying to a
fix that lies beyond the runway means arriving on roughly the reciprocal of the final approach course
and turning most of 180 degrees onto it. The resulting teardrop throws the aircraft **2.5 turn radii**
off the centreline:

| airframe | turn radius | peak lateral excursion | ratio |
|---|---|---|---|
| `plane` | 11.5 | 30 blocks | 2.6 |
| `large` | 22.9 | 58 blocks | 2.5 |
| `cargo` | 57.3 | 118 blocks | 2.1 |

**What did not scale was the room it was given.** The recovery from that excursion is exponential
with a space constant of about **48 blocks, and that constant is independent of airframe and of
speed** — the intercept cut in `tickApproach` is degrees per block of offset, so the decay is per
block of ground covered rather than per tick. But it starts from 2.5 radii, while the intercept
distance was a constant 300 and `FINAL_HANDOVER_DISTANCE` a constant 150. Every airframe got the same
room and only the small-radius ones fitted in it. That is worth knowing before anyone makes the cut
gain airframe-aware: it does not need to be, and the measurement is why.

`AutopilotConfig#minimumInterceptDistance` raises the floor of `ArrivalPlan`'s existing extension
ladder to `FINAL_HANDOVER_DISTANCE + 11 × turn radius`. A floor rather than a scaling, so the starter
plane — which wants 276, and is the regression baseline — keeps exactly the 300 it always flew and its
numbers are unmoved. A large plane gets 402 and a cargo plane 780, both inside
`MAX_INTERCEPT_DISTANCE` so the ladder above still has somewhere to go.

Lengthening the intercept does **not** break the rule that the slope, the fix distance and the circuit
height have to agree. That rule is about not arriving above the slope and having to dive at it, and
`tickApproach` commands the slope altitude capped at the intercept height: further out the slope is
higher, the cap binds, and the extra distance is flown level until the slope descends to meet it. The
aircraft captures the same slope in the same place and gets a longer level segment first, which is
precisely the room the turn needs.

#### Measured, before and after

Whole sorties between two 183×25 fields 2000 blocks apart, livestock off, runways repaired between
runs, under `/tick sprint`:

| airframe | speed | before | after |
|---|---|---|---|
| `plane` | 0.60 | landed, 39 down | landed, 41 down |
| `plane` | 1.60 | landed, 40 down | landed, 40 down |
| `plane` | 2.60 | landed, 42 down | landed, 40 down |
| `plane` | 2.80 | landed, 40 down | landed, 40 down |
| `large` | 0.60 | **go-around**, then landed | landed, 42 down |
| `large` | 1.60 | **go-around**, then landed | landed, 41 down |
| `large` | 2.60 | **go-around**, then landed | landed, 41 down |
| `large` | 2.80 | **go-around**, then landed | landed, 40 down |
| `cargo` | 0.60 | 4 go-arounds, end switch, **landed only with the gates disabled** | landed, 39 down |
| `cargo` | 1.60 | **never terminated** | landed, 38 down |
| `cargo` | 2.60 | **never terminated** | landed, 37 down |
| `cargo` | 2.80 | **never terminated** | landed, 38 down |

The starter plane is unmoved: 39–42 blocks down before, 40–41 after, inside the run-to-run spread it
has always had. The large plane went around on **every** approach before this and on none after — that
go-around was `heading 11–14 deg off the runway`, the same thin margin the starter plane was quietly
living with, and widening the room for the cargo plane fixed it for free.

Into an 80-block runway (`airfield-3`), at 0.60 and 2.60: all three land, 8–19 blocks down, 10–24% of
the strip used. `inbound` and `route` carry the keyword too, and all three airframes complete both.

`random` was flown 15 times: 5 `plane`, 3 `large`, 7 `cargo` — **15 landings, no go-arounds**. Every
airframe the draw can produce completes a flight, which is the bar. A random draw that sometimes
produced an aircraft that could not land would be worse than having no random at all.

#### A cow aboard is a real scenario, and it flies

`LargePlaneEntity` and `CargoPlaneEntity` mount any nearby non-player `LivingEntity` from their own
`tick()`, so livestock on the parking spot really does board. Measured with four cows summoned onto
the spot: **two boarded and flew**, and both airframes completed the sortie anyway — cargo landed 37
blocks down, large 41. The old failure this used to cause is already fixed and stays fixed: the thrust
vector picks `Q` over `Q_Client` on `getPlayer() == null` rather than `getControllingPassenger() ==
null`, so a mob as passenger 0 no longer freezes the thrust direction. See
[Thrust direction](#thrust-direction).

The mount uses `getBoundingBox().inflate(0.2)`, so a mob has to be *touching* the aircraft. Livestock
two blocks away is simply ignored — a test that places it there proves nothing, and the first version
of this one did exactly that.

#### The type is on the readouts

`/autopilot status` and the tower board both print the airframe, because mixed traffic that all reads
`#12 approach` cannot be followed and the three fly quite different circuits:

```
  #1654 cargo descent pos=2478,13,-189 … rwy=airfield-2/18 plan[straight in]
  #1656 large cruise  pos=1977,1,-151  … plan[direct]
  #1657 cruise        pos=1052,-1,-129 … plan[direct]

airfield-2  36/18  OCCUPIED  #1758 arrival 36, flare, 0:38, 53 blocks out [straight in]
  holding (no sequence: the first to poll a free runway takes it):
    #1723 cargo arrival 36, hold, 0:38, 823 blocks out [holding, runway busy]
    #1725 large arrival 36, hold, 0:38, 422 blocks out [holding, runway busy]
```

**The starter plane prints nothing**, so a readout with no mixed traffic in it is byte-identical to
what it printed before airframes existed — which matters, because the assertions in `TESTING.md` are
written against it. The board is translated (`simpleplanes.autopilot.airframe.*`, in both `en_us` and
`ru_ru`); the status line is not, like the rest of that fixed-format telemetry dump. Both read the
airframe off the entity rather than off the flight plan, because a plan ordered as `random` records
`random`, and a plan from an older save records nothing at all.

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

Sneak + right-click cycles the two settings that get changed in flight: the spawn distance advances
on every press, and the blast strength advances one step each time the distance wraps back to the
start, through 4.0 → 8.0 → 16.0 → 1.0. Both are printed on every press and both are on the tooltip.

The other three settings are reachable, just not through that gesture. A held item offers exactly
one spare gesture, and cycling five independent settings through it would be worse than not having
them — so the full set is written onto the tool in hand by a command:

```
/autopilot tool <distance> [bearing] [blast] [blocks] [fire]
```

The same arguments in the same order as `strike`, minus the target, because the target of a tool
strike is whichever block gets right-clicked. This is the one subcommand that requires a player:
the thing being configured is an item in a hand (main hand first, then off hand).

`bearing` here is optional in a second sense — **-1 unpins it**, restoring the default behaviour of
working the run-in out from wherever the player is standing so the aircraft passes them on its way
in. Any pinned bearing makes a tool strike as repeatable as a console one. Arguments left off keep
their current value rather than resetting to the default, so a second call can change one setting
without restating the rest.

Every setting lives on the stack as a data component, so it survives logging out, a chest, and
death. They are four separate components rather than one `Blast` component because tools already in
players' inventories carry a bare float under the old key: adding fields beside it keeps those
tools working, where changing the type would silently reset them.

The sneak-cycle deliberately preserves `blocks` and `fire` when it advances the strength — a
gesture meant for the two numbers must not quietly undo the two settings it does not show.

`status` is the one to watch while debugging. Per aircraft it prints:

```
#42 approach pos=118,96,-204 agl=41 hdg=047 pitch=-3 roll=+2 spd=0.51 vs=-0.07 thr=3
    want[hdg=044 alt=93 spd=0.50] tgt=300,72,300 dist=286 rwy=airfield-1/09 legs=1/2
```

`pos`/`agl`/`hdg`/`spd`/`thr` are what the aircraft is *actually* doing; `want[...]` is what the
flight director is *commanding*. Comparing the two is how you tell a controller that is tracking
from one that is saturated or fighting itself — and a `pos` that does not change between two polls
means the aircraft is not ticking at all (see chunk loading below).

A `solid=` field appears beside `agl` only when the two disagree, which is exactly when the aircraft
is over something it cannot land on: `agl=4 solid=7` is four blocks above a waterline and seven above
the sea floor under it. Without it a ditching and an approach over a field read identically here.

`-Dsimpleplanes.autopilot.trace=true` on the server JVM adds a per-tick line per aircraft to the log
with the same quantities plus `landable`, `og`, `water` and the distance along and across the runway.
It is what the water bug was found with; `status` polls too slowly to see a flare fire.

### The tower board

`status` answers "what is aircraft #42 doing". `tower` answers the other question — "what is this
runway doing, and who is waiting for it":

```
> autopilot tower
3 runways in this dimension, 2 occupied, 1 holding, 1 waiting to depart.
airfield-1  36/18  OCCUPIED  #56 departure, taxi, 0:04
  waiting to depart (no sequence: the first to poll a free runway takes it):
    #57 departure, parked, 0:01, waiting for the runway
airfield-2  36/18  OCCUPIED  #2 arrival, final, 0:22, 186 blocks out
  holding (no sequence: the first to poll a free runway takes it):
    #1 arrival, hold, 0:19, 328 blocks out
airfield-3  09/27  FREE      1 waiting to depart, none cleared yet
  waiting to depart (no sequence: the first to poll a free runway takes it):
    #9 departure, parked, 0:04, 0:25 on the clock
```

Per runway: the designator pair, `FREE` or `OCCUPIED`, and for an occupant its id, whether it is a
`departure` or an `arrival`, its mode and how long it has held the reservation. Aircraft orbiting for
that runway are listed under it, longest-wait-first, with the same elapsed time and their horizontal
range to the field; aircraft still on their parking spots are listed the same way with **what they
are waiting for** — `0:25 on the clock` or `waiting for the runway`. Those two are never merged into
one word, because a wait that cannot say which of the two gates it is behind is indistinguishable
from a hang. `/autopilot tower <airfield>` adds the runway geometry, both thresholds, and everything
else on the way in that has not asked for the runway yet.

`/autopilot status` carries the same two facts per aircraft: `dep=airfield-1/36` while it is on the
ground at the departure field, plus `wait=clock 0:14` or `wait=runway` while it is parked.

```
#1 parked pos=671,-60,11 agl=0 hdg=324 pitch=+5 roll=-0 spd=0.03 vs=-0.03 thr=0
    want[hdg=324 alt=-60 spd=0.00] tgt=2655,0,-100 dist=1987 dep=airfield-1/36 wait=clock 0:14 legs=0/1
```


Per runway: the designator pair, `FREE` or `OCCUPIED`, and for an occupant its id, the end it picked,
its mode and how long it has held the reservation. Aircraft orbiting for that runway are listed under it,
longest-wait-first, with the same elapsed time and their horizontal range to the field.
`/autopilot tower <airfield>` adds the runway geometry, both thresholds, and everything else on the
way in that has not asked for the runway yet.

A name that is not registered still gets a row when traffic is flying to it — an improvised landing
strip (`field-52  --/--  OCCUPIED  #52 arrival, final, 0:29  (not registered)`), or a field that was
removed while an aircraft was already inbound.

Every aircraft's row ends with the flight director's own account of its plan, in brackets — on the
ground `depart 36, straight out` or `depart 18, 92 deg turn to course`, en route
`around right 30 deg, saves 44 of climb`, and arriving `straight in`, `extended final 600`,
`orbit to lose 120` or `holding, runway busy`. Three planners, one field, in the order the flight uses
them. It comes from the aircraft rather than being re-derived,
so the board cannot describe an arrival differently from the way it is being flown, and it is the
answer to the question a board full of holding traffic exists to raise. The same phrase is the
`plan[…]` field on every `/autopilot status` line and is reported to the console whenever it changes.

**The board is read-only and it does not smooth anything over.** One thing it deliberately does
not claim, and it is true of the code as it stands:

* **No queue order.** There is none: an aircraft in `HOLD` or in `PARKED` polls
  `RunwayOccupancy` every 20 ticks and whichever one polls first takes the runway, arrivals and
  departures alike. Numbering them would draw an order that does not exist, so they are listed by
  wait time with the poll rule printed.

The third thing it used to not claim was **departures**, and that is now fixed rather than
documented: a departure holds a reservation from the start of the taxi to the climb-out, so the
strip it is using reads `OCCUPIED`, by whom, and in which direction.

Occupancy comes from `RunwayOccupancy.holder()`, which validates the holder instead of trusting the
map, so the board can never show a runway as busy because of an aircraft that crashed — and it can
never disagree with the answer the aircraft themselves get, because it is the same call. Durations
come from `TowerWatch`, which samples the live autopilot set from the server tick every 10 ticks and
records nothing but "since when"; it writes nothing back into occupancy, so a board that shows
something odd is reporting a fact rather than causing one. Every duration is therefore accurate to
half a second, and a role less than half a second old prints `?`.

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

* **Helicopters fly pad to pad only.** `/autopilot heliflight` and `/autopilot heliinbound` are the
  whole of it (§4h). A rotorcraft cannot be dispatched onto a runway — `route`, `flight` and
  `inbound` refuse `type helicopter` outright — and there is no rotorcraft equivalent of a waypoint
  route, of a strike or of the route wand. A helicopter is also not in the set `random` draws from.
* **A helipad has no traffic board, and the hold is a stack rather than a queue.**
  `/autopilot tower` is about runways and `TowerWatch` filters rotorcraft out of it; the only view of
  pad occupancy is `/autopilot helipads`, which says `OCCUPIED` and nothing about who is holding for
  what. Holders are separated — four levels 10 blocks apart, phased a quarter-orbit each, chosen by
  entity id — but they are not *sequenced*: whichever machine next polls a free pad takes it,
  regardless of who has been waiting longest, and two machines whose ids are congruent modulo four
  share a slot. Both are the same trades the fixed-wing hold makes.
* **The rotorcraft controller has no terrain avoidance beyond climbing.** It raises its commanded
  altitude for whatever `TerrainScanner` sees ahead, and that is all: there is no `RoutePlanner`
  equivalent, so it will try to climb over a mountain rather than going round it, and it has no
  corridor raycast on the way down. The descent is vertical onto ground the survey measured, which
  is what makes that survivable.
* **A helicopter arrival does not re-check the pad's approach sectors in the air.** The bearing it
  runs in on comes from the stored survey, so a wall built across the only clear sector after the
  survey is not noticed until the machine is in it. `/autopilot helipads info` says when a live
  re-measurement disagrees with the stored one, and `resurvey` stores the new answer, but both need a
  human to ask.
* **The default cruise is the fastest level flight there is, so it is never quite made good.**
  Holding altitude at full forward cyclic wants collective 3.31 and the collective is an integer, so
  the loop dithers 3/4 and the machine settles around **1.10 blocks/tick against the 1.20 it is
  commanded** (`HELICOPTER-PHYSICS.md` §3). Ask for less and it holds exactly what it was asked for —
  0.50 flies 0.524, 0.80 flies 0.814. What it does *not* do is refuse a speed it cannot make good:
  `/autopilot heliflight … 1.75` is inside the argument's range, is accepted, is echoed back in the
  launch line and then flies 1.10. It now says so once from the air, which is the honest half of the
  answer; the dishonest half — a launch line quoting a speed nothing will ever fly — is still there,
  because the ceiling is a property of the loaded airframe (a machine without a booster has a lower
  one) and the command does not have the entity when it prints.
* **A helicopter sortie interrupted by a server restart resumes, but nothing wakes it up.** The plan,
  the mode and the pads all come back off the entity NBT and the flight continues correctly the moment
  the aircraft ticks — measured, a sortie stopped 880 blocks out finished on the pad 0.13 blocks from
  the centre after the restart. But the chunk-ticket renewal walks `AutopilotRegistry`, which is
  memory-only, so after a restart nothing holds the ticket of an aircraft nobody has loaded and it
  hangs where it was until something else loads that chunk. This is not specific to rotorcraft: an
  in-progress `route` flight has exactly the same hole.
* **Nothing on the vertical axis can damage a helicopter, so the let-down proves less than a landing
  does.** Autorotation is 0.432 b/t and the free-landing band is 0.60, so every descent this
  controller can command arrives undamaged whatever it does. The number the arrival is judged on is
  therefore the lateral error, not the survival.
* **No wind.** Minecraft 26.2 has no wind API, so runway selection uses approach obstacles and slope
  only. Nothing was invented here.
* **Circuit joins are simplified.** The aircraft flies direct to the intercept point and tracks the
  extended centreline inbound. There is no downwind/base leg — an aircraft that arrives high extends
  the final rather than flying a circuit, and the hold is a simple circular orbit rather than a
  racetrack. Coming from the far side of the field the join is therefore a 180° turn, flown at
  approach speed so it rolls out on the line. `ArrivalPlan` now *checks* that turn fits (§4d) and
  lengthens the final until it does, but it still cannot construct a different shape of join.
* **An arrival from abeam still has to reposition, and the planner does not make that cheaper.** The
  intercept fix is on the far side of an aircraft that arrives 150 blocks out and 90° off the runway,
  so it flies away from the field to get onto the final. Measured on the rig, the lateral excursion in
  that case is the same before and after (45.6 → 49.9 blocks). What changed is that it *decides* to
  reposition at range instead of discovering it overhead. A base leg is what would actually fix it.
* **The corridor raycast used for planning forces chunk generation.** It is the only probe in this
  feature that does; every heightmap probe declines to answer for unloaded ground instead. That is
  deliberate — the ground under a final is exactly what nobody has generated when the arrival is
  decided 400 blocks out — but it means a committed arrival generates about twenty chunks under its
  own glide slope, once, and that cost lands on a single tick.
* **The departure's climb-out obstacles come from the survey and nowhere else.** A hill built after
  the survey, or in a chunk nobody has loaded, does not enter the choice of departure end: unlike the
  arrival, a departure has no time in which to notice. Re-survey the field after changing the ground
  off either threshold.
* **The decision range is derived from the turn radius, not from the terrain.** It says how much room
  the *join* needs; it knows nothing about how far out the aircraft would have to start descending
  over a ridge. `RoutePlanner` still owns the en-route half of that and only sees as far as the
  loaded horizon.
* **The route planner is a heading search, not a path search.** It scores candidate headings along
  straight rays out to the loaded horizon; it has no notion of a gap it could aim at, and it cannot
  plan a route that needs two turns. It re-decides every second, which is what makes that adequate
  for a ridge and not adequate for a maze.
* **The planning horizon is however much terrain is loaded.** With nothing loaded the planner
  declines to answer (`direct (terrain not loaded)`) and the aircraft holds altitude, exactly as it
  always did. The chunk-ticket lead sets how far it can see, so at high speed it sees relatively
  less.
* **Bank direction is cosmetic, but bank angle is not free.** Turns are produced by the yaw control,
  as in the base game; bank is commanded only so the aircraft looks right. If it banks the wrong way
  in a turn, flip the sign of `desiredRoll` in `PlaneAutopilot#applyControls` — it will not change the
  flight path. The *magnitude* does matter, though: a banked aircraft yawing hard couples into pitch
  through the quaternion, which is why bank is surrendered at low speed.
* **Improvised landings are rough** by nature. Survey a runway for anything reliable. They are also
  not centred on anything: `Airfield.improvise` builds its thresholds straight from the terrain
  rather than going through `survey`, so the centring described in
  [The centreline is the middle of the strip](#the-centreline-is-the-middle-of-the-strip) does not
  apply to them. There is nothing to centre on — the strip is a guess, not a built runway.
* **A runway the survey cannot see the edges of keeps the clicked centreline.** The cross-section
  stops at the first column more than a block off the threshold elevation, so a strip laid flush with
  the ground around it — mown grass, a dirt road, anything on the superflat — offers no evidence of
  where its middle is and the two clicks are used as given. Build a lip, a verge or a step of two
  blocks or more if you want the survey to find the middle for you.
* **Approach obstacles are counted in a corridor as wide as the runway**, out to 200 blocks and no
  further, with a floor of 5 blocks either side on a narrow strip. A mast standing 30 blocks off the
  centreline of a 13-wide field is not in the count, and neither is anything past 200 blocks.
* **The touchdown aim point is derived from the runway's length and nothing else.** Not from the
  approach speed, which the arrival does not inherit anyway; not from the surface, which changes the
  roll-out by less than the block-to-block scatter of the float; and not from what stands past the
  far end, which nothing measures. A fifth of the strip is a rule of thumb applied to a number the
  survey knows, and it is deliberately not a landing-performance calculation. The one input that
  would genuinely change it is wind, and there is no wind.
* **An improvised runway gets an aim offset it has not earned.** `Airfield.improvise` fabricates an
  80-block strip out of the flattest heading it can find, so the rule aims 16 blocks down it — a
  number derived from a length nobody measured. It is not worse than aiming at the near edge of the
  same guess, but it is not the surveyed case and should not be read as one.
* **A runway cannot be reached across standing water that is higher than it.** The glide slope ends
  on a point on the runway, so anything holding water above the runway elevation also stands above
  the slope near it: that approach is unflyable and the aircraft goes around rather than ditching.
  Nothing picks the drier end for you either — `bestEnd` ranks the two funnels on the obstacle counts
  the survey recorded, and a sea at or below the runway elevation is not an obstacle to *clearance*,
  which is all that count measures. It is only after the three go-arounds that the other end is
  tried.
* **Terrain in ungenerated chunks reads as unknown** and is skipped, so an aircraft flying into
  never-visited terrain holds altitude rather than reacting to ground it cannot see. The chunk
  ticket keeps a bubble loaded around the aircraft itself, which covers the normal case.
* **Route legs are fixed at 2** (out and back) from the wand. Use `/autopilot flight` or
  `/autopilot inbound` for a one-way sortie, or the `FlightPlan` API for more.
* **The cruise speed is a cruise speed.** The approach, flare and landing gates are tuned around
  `APPROACH_SPEED` and never inherit it; the aircraft sheds the difference on the final cruise leg.
  A route whose last leg is shorter than the deceleration distance will arrive at the descent still
  fast and rely on the descent to finish the job.
* **There is no taxiway network.** Marked parking makes both ends of a taxi a human decision, and the
  arrival's taxi in has three fixed legs (off the strip, down the apron, in to the stand) rather than
  one — but there is still no route search, no obstacle avoidance and nothing that reads a path a
  player has built. Every leg is validated for level ground before the aircraft sets off; nothing
  validates it against *entities*, which is why an aircraft can still come to rest against a hulk
  someone left on the apron and has a stall timeout for exactly that.
* **A taxi in holds one of the 24 traffic slots for the length of the taxi**, which on a 183-block
  field with the stands beside the far threshold is about 950 ticks. Before this change the flight
  ended at the roll-out, so a busy field now carries more concurrent autopilots than it used to for
  the same number of sorties.
* **A stand marked on the strip keeps the runway reserved for the whole taxi.** Correctly — an
  aircraft standing there is standing on the landing area — but it means the one placement the
  validation accepts with a warning is also the one that gets no benefit from the early release.
* **A restart during a taxi in abandons it.** `PlaneAutopilot.load` does not restore `TAXI_IN`: the
  stand and the legs to it were never written to disk, and promoting it the way `TAXI` and `PARKED`
  are promoted would send an aircraft that has already completed its flight back down the runway.
  The aircraft stays where it stands, off the runway, and the flight is over. That is a worse parking
  job than it asked for, not a lost aircraft.
* **Nothing re-parks an aircraft that stopped on the runway.** All three "cannot taxi in" outcomes
  end the flight where the aircraft is; there is no retry when a stand later frees up, and no
  dispatcher to notice. `/autopilot tower` shows the strip as free — because the *reservation* is —
  while an aircraft is physically sitting on it.
* **Stand occupancy does not survive a restart.** `StandOccupancy` is in memory, so after a restart a
  stand with an aircraft parked on it in an unloaded chunk reads as free until something loads it. A
  sortie ordered in that window can be spawned on top of a parked aircraft, exactly as it could
  before this feature existed. Persisting it would mean writing an occupancy nothing can validate on
  load, which is a different and worse failure.
* **There is no runway sequencing.** One reservation per airfield, now taken by departures as well
  as arrivals, but still no queue behind it: waiting aircraft re-poll every 20 ticks and whoever
  polls first is next, so a long-waiting aircraft can be passed over and the order between two
  aircraft waiting for the same strip is unspecified. `/autopilot tower` makes it visible; it is not
  fixed. What *is* fixed is the collision it used to allow — two sorties out of the same field can
  no longer taxi onto the same threshold.
* **A restart during a departure delay departs the aircraft immediately.** `PlaneAutopilot.load`
  maps a saved `PARKED` to `TAKEOFF`, exactly as it already does for `TAXI`, because it does not
  re-resolve the departure runway and a restored `PARKED` would have nothing to ask for and no way
  to leave the spot. The remaining delay is lost. Departing from where it stands is the same
  compromise a half-finished taxi has always made.
* **There is no en-route separation, and nothing diverts.** Aircraft converging on the same field
  from different directions fly through each other's airspace, and planes are hard-colliding
  entities: two arrivals launched 120 blocks apart towards the same runway were reproducibly
  destroyed against each other in `DESCENT` on the rig, before and after the work above. A second,
  completely free runway in the same dimension attracts nothing. Both belong to a dispatcher that
  does not exist yet.
* **A go-around from short final over water is unreliable.** From ~30 blocks above the surface at
  final speed the aircraft frequently does not climb away and ends up in the sea. Reproduced on the
  unmodified build as well, so it is not new, but it is what turns one failed gate into a lost
  aircraft rather than a second approach.
* **Taxi is a straight line to the threshold.** There is no taxiway network and no obstacle
  avoidance on the ground: the aircraft steers directly at the lineup point. On a surveyed field with
  a sane parking apron that is enough; it will not thread a hangar.
* **No player is ever required.** Aircraft spawn, fly, land, save and load with no player involved;
  an owning player is only an optional recipient for progress messages, and `AutopilotFeedback`
  no-ops when there is none.
