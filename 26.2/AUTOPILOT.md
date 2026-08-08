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

All three are in the Simple Planes creative tab.

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
IDLE ─► PARKED ─► TAXI ─► TAKEOFF ─► CLIMB ─► CRUISE ─► DESCENT ─► APPROACH ─► FINAL ─► FLARE ─► ROLLOUT ─► IDLE
                             │          ▲         │  ▲                 │
                             │          └─ HOLD ◄─┘  └──── GO_AROUND ◄─┘
                             └──► STRIKE (one-way attack run, no landing)
```

| Mode | What it does |
|---|---|
| `PARKED` | Stationary on the parking spot, throttle shut, running the departure clock down and then asking for the runway |
| `TAXI` | Ground steering from the parking spot to the departure threshold at 0.20 speed, elevator neutral |
| `TAKEOFF` | Full power, ground steering on the runway heading, elevator aft, rotate at 0.35 speed, wings level |
| `CLIMB` | Climb to cruise altitude on the first waypoint's bearing |
| `CRUISE` | Fly waypoints, terrain-following, advancing within `max(30, turn radius)`, bleeding speed for the arrival |
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
threshold for ever.

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
2 airfields, nearest first, from 0, 0 (world origin):
  airfield-1 36/18  180x25  662 blocks brg 081  parking 2
  airfield-2 36/18  66x25  2.7km brg 089  reserved by #1
```

The list carries only what decides whether you want to open one: size, distance, bearing, marked
parking, who has it reserved (`RunwayOccupancy.holder`), and `TOO SHORT` if an aircraft cannot use
it. The full survey — slope, roughness, threshold elevations, approach obstacle counts, preferred
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

A marked spot is now also somewhere an aircraft **waits** rather than merely somewhere it appears:
`PARKED` sits there for the ordered delay and then for as long as the runway is busy. That makes the
"more than one spot" rule load-bearing instead of cosmetic — with two spots marked, two sorties
ordered seconds apart wait on different squares while the first one uses the strip, and each is
listed under the field on `/autopilot tower` with what it is waiting for.

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
| Airfields, including marked parking spots | `SavedData` per dimension, `data/simpleplanes/airfields.dat` | **Yes** |
| In-progress route flight | Plane entity NBT, via `FlightPlan.CODEC` | **Yes** |
| Departure delay *ordered* | Plane entity NBT, `departure_delay` on the plan | **Yes** |
| Departure delay *remaining* | Flight director only | No — a reloaded `PARKED` becomes `TAKEOFF`, see §9 |
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
/autopilot strike <x y z> [distance] [bearing] [blast] [blocks] [fire]
                                                 launch an attack run
/autopilot tool <distance> [bearing] [blast] [blocks] [fire]
                                                 write those settings onto the held strike tool
/autopilot route <x y z> <x y z> [speed]         fly A -> B -> A and land
/autopilot flight <from> <to> [speed] [delay <seconds>]
                                                 full sortie between two registered airfields;
                                                 delay is how long it waits on its parking spot
/autopilot inbound <x y z> <airfield> [speed]    one-way arrival into a named airfield
/autopilot survey <x y z> <x y z>                survey a runway between two thresholds
/autopilot tower [<airfield>]                    runway states: free/occupied, by whom, who is holding
/autopilot status                                full telemetry for every autopilot aircraft
/autopilot stop                                  stop every autopilot aircraft in this dimension

/autopilot airfields                             browse, nearest first
/autopilot airfields info <airfield>             the full survey of one field
/autopilot airfields show <airfield>             draw it in world with particles
/autopilot airfields rename <airfield> <name>    rename it
/autopilot airfields remove <airfield>           delete it
/autopilot airfields park <airfield> <x y z>     mark a parking spot
/autopilot airfields unpark <airfield> <x y z>   remove the marked spot nearest that point
```

`speed` is the cruise speed in blocks per tick, clamped to 0.40-2.80. Omitted, it is
`CRUISE_SPEED` — see [The default is fast](#the-default-is-fast).

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

A name that is not registered still gets a row when traffic is flying to it — an improvised landing
strip (`field-52  --/--  OCCUPIED  #52 arrival, final, 0:29  (not registered)`), or a field that was
removed while an aircraft was already inbound.

**The board is read-only and it does not smooth anything over.** Two things it deliberately does
not claim, both of them true of the code as it stands:

* **No queue order.** There is none: an aircraft in `HOLD` or in `PARKED` polls
  `RunwayOccupancy` every 20 ticks and whichever one polls first takes the runway, arrivals and
  departures alike. Numbering them would draw an order that does not exist, so they are listed by
  wait time with the poll rule printed.
* **No runway end in use.** Which of the two ends an arrival picked is private to the flight
  director; the board prints the pair the airfield has.

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
* **There is no taxiway network.** Marked parking makes the *start* of the taxi a human decision;
  the taxi itself is still a straight line to the threshold, which is why a marked spot is validated
  along that line and capped at 64 blocks from it.
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
* **Taxi is a straight line to the threshold.** There is no taxiway network and no obstacle
  avoidance on the ground: the aircraft steers directly at the lineup point. On a surveyed field with
  a sane parking apron that is enough; it will not thread a hangar.
* **No player is ever required.** Aircraft spawn, fly, land, save and load with no player involved;
  an owning player is only an optional recipient for progress messages, and `AutopilotFeedback`
  no-ops when there is none.
