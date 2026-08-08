# HELICOPTER-PHYSICS — the rotorcraft flight model (26.2 port)

Scope: `entities/HelicopterEntity.java`, `entities/LargeAirframeEntity.java`, and the two lines of
`entities/PlaneEntity.java` that were extracted so the helicopter could stop inheriting the wing.

Companion to `PHYSICS-AUDIT.md`, which covers the fixed-wing model, and written to the same rules:
**every claim about vanilla behaviour below was read in `/opt/mc-src`**, and every number is marked
as either *measured* on the headless rig or *derived* from the constants. Nothing is asserted from
memory.

---

## 0. What was there before

`HelicopterEntity extends LargePlaneEntity` with six overrides and a handful of constant tweaks.
Read as a flight model it was a plane with the rudder unbolted:

| symptom | in the code |
|---|---|
| no yaw at all | `tickYaw()` was `{}` — an empty override |
| yaw came out of the roll control | `tickRoll` did `setYRot(getYRot() - moveStrafing * 2)` |
| pitch was a hardcoded ramp | `setXRot(max(getXRot() - 1, -20))`, ±20° in 20 ticks, no rate law |
| momentum never realigned | `tickRotateMotion` returned `q` and did nothing else |
| it flew on a wing it does not have | it inherited `PlaneEntity`'s drag polynomial, `maxLift`, `takeOffSpeed`, `getLiftRatio` |
| thrust was two unrelated hacks | `push *= 1.5` for forward, `push += 0.01 * throttle` for up, both inside `getTickPush` |
| it could only leave the ground with the space bar held | `tickOnGround` set `push = 0` unless `MOVE_UP` |
| **and nothing outside the flying client could move any of it** | translation came from `TempMotionVars.moveForward` / `moveStrafing`, fed from `Player.zza` / `Player.xxa` |

That last row is the one that matters most for anything built on top of this airframe, and it is a
fact about vanilla, so it was checked rather than assumed. **`Player.xxa` and `Player.zza` are
written in exactly one place in 26.2** — `net/minecraft/client/player/LocalPlayer.java:690`,
`this.xxa = modifiedInput.x` — and nowhere on the server. (`Mob.java:434` writes the same fields for
mobs; `LivingEntity.java:3058` zeroes them.) A riding player's `xxa`/`zza` are therefore **always
zero server-side**, so the old helicopter's fore/aft and lateral controls did not exist outside the
one client that was flying it. Measured confirmation on the rig, against the pre-change jar:
`/data get entity @e[type=simpleplanes:helicopter] throttle` answers `Found no elements matching
throttle`, and `/data merge entity … {throttle:5}` changes nothing, because none of the control
state was persisted either.

**Measured, pre-change jar:** an unmanned powered helicopter sits on the ground for ever
(`dy = +0.000` over 200 ticks at any collective, because the collective is unreachable), and dropped
from altitude it falls at a terminal **0.659 b/t = 13.2 blocks/s** — solved exactly by the old
constants once `brakesMul = 5` at throttle 0 is included: `5·(0.01v² + 0.001v + 0.001) = 0.03`
→ `v = 0.6587`.

---

## 1. The model

A helicopter has **one** force generator: a rotor disc rigidly bolted to the fuselage, pushing along
the airframe's own "up" axis. Two numbers describe the whole aircraft —

* **how hard the rotor pushes** — the *collective*, which is the throttle notch;
* **which way the airframe points** — the *cyclic*, which is the pitch and roll attitude;

— plus a tail rotor that yaws the airframe on the spot (the *pedals*). That is the entire model.
There is no wing, no lift term, no angle of attack, no stall speed and no take-off speed.

### 1.1 The thrust vector

`HelicopterEntity#rotorAxis` builds the unit body-up vector directly from `getXRot()`,
`rotationRoll` and `getYRot()`:

```
x = sin(roll)·cos(yaw) + cos(roll)·sin(pitch)·sin(yaw)
y = cos(roll)·cos(pitch)
z = sin(roll)·sin(yaw) − cos(roll)·sin(pitch)·cos(yaw)
```

derived by pushing `(0,1,0)` through the same `Ry(−yaw)·Rx(−pitch)·Rz(−roll)` composition
`PlaneEntity#transformPos` uses, so it agrees with the renderer and with the seat placement. Its `y`
component is `cos(pitch)·cos(roll)`, i.e. exactly `PlaneCollisions#upY`.

It is built from the euler angles rather than from a quaternion for two reasons, both already
documented elsewhere in this repo for other call sites:

* `transformPos` rotates by `Q_Client`, which on the server is only ever written by
  `RotationPacket`. An aircraft with nobody aboard keeps the `Q_Client` it was created with for its
  whole life (`PHYSICS-AUDIT.md` §1.1, `AUTOPILOT.md` "Thrust direction").
* `Q` is only rebuilt from the euler angles at the **end** of `tick()`, so any body vector taken
  from it *during* the tick lags the attitude the cyclic just commanded by one tick. For a plane
  that is a small correction to a wing that is doing the work. For a helicopter the thrust vector
  *is* the flight model, so the lag is not affordable.

**Sign conventions**, which are not obvious and cost an hour to pin down. This mod uses
`PlaneEntity`'s internal convention, which is the inverse of vanilla's on one axis:

* **positive `xRot` is nose UP** (vanilla's `Entity#calculateViewVector` uses `y = −sin(xRot)`, i.e.
  negative is up; the mod's `tickRotateMotion` lerps the velocity pitch toward `getXRot()` and
  `getGroundPitch()` returns +5 for the tail-dragger, so the mod's sign is the other way);
* **positive `rotationRoll` is banked LEFT** (`tickRoll` increases it for `moveStrafing > 0`, and
  `xxa > 0` is the A key).

So a *forward* cyclic command is a **negative** pitch target and a *right* cyclic command is a
**negative** roll target.

### 1.2 The per-tick pipeline

`PlaneEntity.tick()` calls six hooks. The helicopter overrides all six, so **not one line of the
fixed-wing flight model runs**:

| hook | helicopter |
|---|---|
| `tickRotateMotion` | turn coordination only — the velocity vector is dragged toward the nose. No lift, quaternion returned untouched |
| `tickOnGround` | skid friction, the repair timer, the ground-contact counter. Returns `true` |
| `tickPitch` | the whole attitude update: **both** cyclic axes, rate-limited |
| `tickYaw` | pedals + bank-to-turn, with the kinematic correction of §5 |
| `tickMotion` | drag, rotor thrust, gravity, backstop — a complete replacement |
| `tickRoll` | empty; roll is done in `tickPitch`, one hook earlier, so `tickMotion` sees this tick's attitude |

---

## 2. Collective / vertical

`thrust = COLLECTIVE_PER_NOTCH × (throttle + boost)`, with `COLLECTIVE_PER_NOTCH = 0.010` b/t².

**The hover throttle is notch 3 of 5**, and it is solved rather than chosen: `3 × 0.010 = 0.030`,
which is exactly `−gravity` (`TempMotionVars.gravity = −0.03`, unchanged from the fixed-wing model
and confirmed in `/opt/mc-src` not to come from vanilla — `Entity.getDefaultGravity()` returns 0.0
and `applyGravity()` is never called in the `Entity` tick chain).

Two things bound the vertical axis instead of a velocity clamp:

* **Vertical drag**, `(V_DRAG_QUAD·|vy| + V_DRAG_LIN)·vy` with `0.045` and `0.050`. This is the
  rotor disc seen face-on and it is an order of magnitude larger than the horizontal drag. It is the
  single most important asymmetry in the model: it gives a bounded, collective-selectable rate of
  descent that is not a function of the top speed.
* **Axial inflow**, `thrust ×= clamp(1 − vy/ROTOR_INFLOW_LIMIT, 0, 1)` while climbing, with
  `ROTOR_INFLOW_LIMIT = 2.0`. Climbing takes the rotor's air away from it, so every collective
  setting has an *equilibrium* climb rate rather than a constant acceleration.

**Measured** (400 ticks from rest at altitude, `tick rate 2000`, terminal to four decimal places and
flat over the last 100 ticks):

| throttle | boost | vy (b/t) | blocks/s |
|---|---|---|---|
| 0 | – | −0.4320 | **−8.64** |
| 1 | – | −0.3122 | −6.24 |
| 2 | – | −0.1730 | −3.46 |
| **3** | – | **0.0000** | **0.00 — hover** |
| 4 | – | +0.1335 | +2.67 |
| 5 | – | +0.2376 | +4.75 |
| 3 | held | +0.2376 | +4.75 |
| 5 | held | +0.3979 | +7.96 |
| 10 (booster) | – | +0.5737 | +11.47 |
| 10 (booster) | held | +0.6659 | +13.32 |

The hover is exact: **0.000 blocks of drift in 400 ticks**, and `Motion` reads `[0.0d, 0.0d, 0.0d]`.

**The collective boost (space) is +2 notches, momentary.** It is not a separate lift mechanism —
notch 3 + boost and notch 5 give bit-identical numbers (+0.2376 both), which is the check that it
is the same code path.

### What happens at zero power — autorotation, and why

**Decision: autorotation, not a rock.** With the engine dead or the throttle shut the rotor keeps
windmilling, the disc keeps its full face-on drag, and the aircraft descends at a terminal
**0.432 b/t = 8.6 blocks/s** (measured; identical whether the collective is at 0 with fuel or the
aircraft has no fuel at all).

The justification is a number, not a preference: `PlaneCollisions.V_TOLERANCE_MIN +
V_TOLERANCE_LEVEL_BONUS` is **0.60 b/t** for a wings-level arrival, so 0.432 sits 28 % inside the
free-landing band. An engine failure therefore costs the pilot the ability to go anywhere but does
not destroy the aircraft, which is what autorotation means. **Measured end to end:** dropped from 60
blocks with no engine at all, an unpowered helicopter reaches the ground at 0.432 b/t and reads
`health: 10` — undamaged. Every powered descent (notch 2, 1 and 0: 3.5, 6.2 and 8.6 blocks/s) lands
free as well.

The honest cost of that choice: **the vertical axis cannot kill you.** There is no collective
setting from which a straight-down arrival on flat ground damages the airframe. Flying into a
hillside, a wall or the water still does, and a shot-down helicopter still dies (§6).

---

## 3. Cyclic / translation

The disc tilts up to `MAX_CYCLIC = 25°` in both axes, at up to `MAX_CYCLIC_RATE = 2.0 °/tick`
(40 °/s, so full deflection in 13 ticks).

**Cyclic is a position command**, not a rate command, and that is the largest handling difference
between this airframe and every plane in the mod. Holding the stick forward buys a fixed disc tilt,
therefore a fixed forward component of thrust, therefore — through the drag curve — a fixed cruise
speed. Releasing it returns the disc to level. Speed is a function of *where the stick is*, not of
how long it has been there. The fixed-wing elevator is the opposite: `tickPitch` ramps `pitchSpeed`
and integrates it, so holding the stick keeps rotating.

Forward speed and pitch attitude are consequently **rigidly coupled**: at any steady speed the
attitude is `asin(drag / thrust)` and nothing else. Horizontal drag is
`0.009·v² + 0.0025·v + 0.0002`.

**Measured** (1500 ticks, equilibrium; `pitch` is read straight off the entity's `Rotation` tag):

| throttle | cyclic | attitude | vh (b/t) | blocks/s | vy (b/t) |
|---|---|---|---|---|---|
| 3 | 100 % | 25.0° nose down | 1.0468 | **20.9** | −0.054 (sinking 1.1 b/s) |
| 4 | 100 % | 25.0° nose down | 1.2017 | **24.0** | +0.088 (climbing 1.8 b/s) |
| 5 | 100 % | 25.0° nose down | 1.3217 | 26.4 | +0.192 |
| 4 | 50 % | 12.5° nose down | 0.8116 | 16.2 | +0.122 |
| 4 | 25 % | 6.25° nose down | 0.5327 | 10.7 | +0.131 |
| 10 (booster) | 100 % | 25.0° nose down | 1.7458 | **34.9** | +0.525 |
| 4 | −100 % | 25.0° nose **up** | 1.1415 | 22.8 backwards | +0.088 |

**Top speed is 1.20 b/t (24 blocks/s)** in the cruise a player will actually fly, 1.75 b/t
(35 blocks/s) with a booster fitted. For scale, the fixed-wing default cruise is 2.60 b/t: a
helicopter is a little under half the speed of a plane, which is about right.

**Level cruise falls between two notches**, and that is worth saying plainly because a controller
has to deal with it. Holding altitude at 25° of tilt needs `0.030 / cos 25° = 0.0331` b/t² of
thrust, i.e. collective 3.31, and the throttle is an integer. So notch 3 cruises at 20.9 blocks/s
while sinking 1.1 blocks/s and notch 4 cruises at 24.0 while climbing 1.8. Dither the notch to hold
level — which is exactly what the fixed-wing throttle loop already does (`AUTOPILOT.md`: "dithering
8/9", "dithering 0/1").

**Acceleration**, measured from a standing hover at throttle 4, full forward cyclic:

| ticks | 20 | 40 | 60 | 80 | 100 | 150 | 200 | 300 | 400 |
|---|---|---|---|---|---|---|---|---|---|
| blocks/s | 4.5 | 10.1 | 14.5 | 17.7 | 20.0 | 22.8 | 23.7 | 24.0 | 24.0 |
| blocks flown | 1.9 | 9.4 | 21.8 | 38.1 | 57.1 | 111 | 170 | 289 | 409 |

20 blocks/s in **100 ticks (5 s) and 57 blocks**; 95 % of cruise in 150 ticks.

**Braking**, full aft cyclic from cruise: 24.0 → 2.9 blocks/s in **60 ticks over 43 blocks**, and
into a 22.8 blocks/s reverse by tick 200 if the stick is held. Stopping in three seconds and forty
blocks from full cruise is the thing a helicopter is for, and it is why the cyclic is symmetric.

---

## 4. Pedals / yaw

`MAX_YAW_RATE = 3.0 °/tick = 60 °/s`, ramped at `YAW_RAMP = 0.5 °/tick²` — the same
double-integrator shape `PlaneEntity#tickYaw` has, so a heading controller written against the
fixed-wing rudder (bang-bang with an angular stopping-distance term) transfers unchanged.

**It does not depend on airspeed.** This is the single most visible way a helicopter is not a plane,
and it is measured rather than intended:

| condition | pedal yaw rate |
|---|---|
| hover, 0.000 b/t, wings level | **3.000 °/tick = 60 °/s** |
| cruise, 1.13 b/t, disc 25° nose down | **3.000 °/tick = 60 °/s** |
| cruise, 1.13 b/t, disc 25° nose down, pedal left | −3.000 °/tick |

The ramp reaches the ceiling in 6 ticks (`2.62, 3.00, 3.00, …`). A 180° pirouette takes 3 seconds
from a standstill.

At cruise a pedal turn gives a **22-block radius** (measured: 3.000 °/tick of nose, 2.998 °/tick of
track, at 1.131 b/t). That is tighter than any fixed-wing airframe in the mod — the starter plane
manages 32 blocks and the cargo plane 380 at speed (`TESTING.md`, "How fast an airframe can actually
turn"). The pedal is this aircraft's turn control, and a controller should treat it as such.

---

## 5. Roll, and the coupling between roll and yaw

Lateral cyclic banks the disc up to 25°, which produces a real sideways acceleration. What that
*means* depends on speed, and the model makes it depend on speed in two places.

**Below `ALIGN_MIN_SPEED` (0.30 b/t = 6 blocks/s) nothing weathervanes.** `tickRotateMotion` drags
the horizontal velocity vector toward the nose at up to `VELOCITY_ALIGN_RATE = 0.10` per tick,
faded in linearly from 0.30 b/t to `ALIGN_FULL_SPEED` (0.80 b/t). The physical reading is fuselage
and fin authority, which needs airflow. The practical reading is: a helicopter hovers and sidesteps
in any direction, and at cruise it flies where it points. The rotation preserves speed exactly, so
no energy is created or destroyed; it is the same trick `PlaneEntity#tickRotateMotion` uses on the
fixed-wing side (`lerpAngle180(0.1f, yaw, getYRot())`), and `lerpAngle180` rather than `lerpAngle`
so that deliberate rearwards flight is a stable equilibrium instead of flipping through 180°.

**Roll and yaw are coupled.** A bank commands a yaw of
`TURN_FROM_BANK · sin(roll) · clamp(forwardSpeed / TURN_COORDINATION_SPEED, 0, 1)`, so a bank at
speed is a turn and a bank in a hover is a slide.

The gate is on the **forward component** of the velocity, not on the horizontal speed, and that is a
bug fix with a measurement behind it: gated on horizontal speed, a hover sidestep built up 0.5 b/t
of sideways drift, the drift counted as speed, and the aircraft flew a circle — **147° of unasked-for
heading change in 400 ticks** with the stick simply held to one side. Gated on forward speed a
sidestep stays a sidestep until the weathervane has turned enough of it into forward flight to
deserve a turn.

**Measured at cruise** (1.13 b/t, 200-tick windows, no pedal):

| lateral cyclic | nose rate | track rate | turn radius | sideslip |
|---|---|---|---|---|
| +100 % (full right) | +1.706 °/tick | +1.707 °/tick | **35 blocks** | 7.0° |
| −100 % (full left) | −1.706 °/tick | −1.707 °/tick | 35 blocks | 7.0° |
| +50 % | +0.261 °/tick | +0.260 °/tick | 259 blocks | 1.8° |
| pedal only, wings level | +3.000 | +2.998 | 22 blocks | — |
| pedal + full bank | +4.584 | +4.527 | 5 blocks (and the speed collapses to 0.43 b/t) | — |

Bank and pedal add, so a coordinated turn is stick and pedal exactly as it is in a real helicopter.

### The kinematic correction, and the measurement that forced it

This is the part of the model that is not obvious, and it is worth reading before touching
`tickYaw`.

`PlaneEntity#tick` folds the euler deltas back into the attitude quaternion as three **body-frame**
rotations — `q.rotateZ(Δroll)`, `q.rotateX(Δpitch)`, `q.rotateY(Δyaw)` — and then decomposes the
result again (`PHYSICS-AUDIT.md` §1.1). So the numbers written into `yRot`/`xRot`/`rotationRoll` are
consumed as **body rates**, not as euler angles. For the Y-X-Z sequence `MathUtil.toEulerAngles`
uses, the kinematics are

```
wx = ẏ·cos(p)·sin(r) + ṗ·cos(r)
wy = ẏ·cos(p)·cos(r) − ṗ·sin(r)
wz = −ẏ·sin(p)       + ṙ
```

and inverting them for a pure body-Y input gives `ẏ = Δyaw · cos(r) / cos(p)`, for a pure body-X
input `ẏ = Δpitch · sin(r) / cos(p)`. Both are real, and both were **measured before they were
believed**:

* A pedal command of 3.0 °/tick produced **3.309 °/tick** of heading with the disc 25° nose down,
  and exactly 3.000 while level. `3.0 / cos 25° = 3.309`.
* The bank-to-turn term, symmetric by construction, produced **+0.469 °/tick to the right and
  −1.030 to the left** at the same speed and bank — a 2.2× asymmetry with a −0.25 °/tick offset that
  did not flip sign with the bank. That is the second term: the cyclic is holding the pitch against
  the disturbance the yaw itself creates, and each tick of that correction leaks back into heading.

`HelicopterEntity#applyYaw` therefore subtracts the leak and rescales into body-Y units before
calling `setYRot`. After it: the pedal is **3.000 °/tick at every attitude and every speed**, and
left and right are exact mirrors to three decimal places in all four cases in the table above.

Two honest caveats:

* **The residual loop gain was measured, not predicted.** The leak correction closes a small
  feedback loop with the cyclic's attitude hold; its closed-loop gain came out at about 1.55 and the
  response to bank angle is markedly **non-linear** (0.261 °/tick at half stick, 1.706 at full).
  `TURN_FROM_BANK = 2.60` is set from that measurement. Use the **pedal** for heading control; the
  bank is for feel and for coordination.
* **A hover sidestep still drifts round slowly.** Holding full lateral cyclic in a hover, the
  weathervane converts some of the sideways drift into forward speed, which then opens the
  bank-to-turn gate a little: measured 61° of heading change over 400+ ticks at 0.46 b/t of drift.
  That reads as the aircraft weathervaning into its own slipstream, which is what a real one does,
  so it is left in.

---

## 6. Damage and zero health

**Below zero health the rotor stops.** `rotorThrust` returns 0, and the vertical drag — which was
the windmilling disc — collapses to `DEAD_DISC_DRAG = 0.35` of its live value. The airframe pitches
8° nose down, rolls 35° (direction from `getId() % 2`, as the fixed-wing model does) and spins up to
`DEAD_SPIN_RATE = 10 °/tick = 200 °/s` about its yaw axis: a tail-rotor failure, which is the way
helicopters actually come down.

**Measured:** health forced to 0 in cruise at 60 blocks AGL, the aircraft reached **0.919 b/t =
18.4 blocks/s** of descent and was destroyed on impact — `PlaneCollisions.afterMove` explodes any
0-HP aircraft on any contact, and 18.4 blocks/s is three times the 0.60 b/t free-landing band in any
case.

The contrast with the autorotation of §2 is the point: **losing the engine is survivable, being shot
down is not**, and the two are different code paths (`isPowered()` versus `getHealth() <= 0`) with a
2.1× difference in arrival speed between them.

Everything else about damage is inherited unchanged from `PlaneCollisions`: the helicopter's mass
factor is 1.15, its landing angle 30°, and its impact, water-entry, scrape and ram rules are the
shared ones. Nothing in this work touched that file.

---

## 7. Ground handling

`tickOnGround` keeps the repair timer and the ground-contact counter and adds one thing: **skids,
not wheels.** 25 % of the horizontal speed is shed per tick while there is real contact, so a
helicopter does not roll. The gate is `onGround() || isOnWater()` — real contact — and not
`getOnGround()`, whose coyote timer stays true for four ticks after lift-off; that is the same trap
`PHYSICS-AUDIT.md` **B6** describes on the fixed-wing side.

While on the ground the cyclic is ignored and the disc is driven level, because tilting a disc into
the ground is not a manoeuvre. The pedals still work, so a helicopter can be pointed before it
leaves the ground.

**Measured**: sits perfectly still at collective 0, 2 and 3 (`dy = +0.000`, `dxz = 0.000` over 200
ticks, `health: 10`). Take-off, from resting on the superflat:

| collective | 1 block up | 20 blocks up |
|---|---|---|
| 4 | 20 ticks | 170 ticks |
| 5 | 20 ticks | 100 ticks |
| 3 + boost | 20 ticks | 100 ticks |
| 5 + boost | 10 ticks | 60 ticks |

No runway, no take-off speed, no `speedingUp` gate. Collective above hover is the whole procedure.

---

## 8. Constant table

`HelicopterEntity`, all `public static final`, all per tick. Multiply speeds by 20 for blocks/s.

| constant | value | sets |
|---|---|---|
| `COLLECTIVE_PER_NOTCH` | 0.010 b/t² | rotor thrust per throttle notch |
| `HOVER_THROTTLE` | 3 | the notch where thrust == gravity (derived: 3 × 0.010 == 0.030) |
| `COLLECTIVE_BOOST_NOTCHES` | 2 | what the space bar adds |
| `ROTOR_INFLOW_LIMIT` | 2.0 b/t | bounds every climb rate |
| `MAX_CYCLIC` | 25° | disc tilt at full stick → top speed and cruise attitude |
| `MAX_CYCLIC_RATE` | 2.0 °/tick | how fast the disc tilts (40 °/s) |
| `MAX_YAW_RATE` | 3.0 °/tick | pedal authority (60 °/s), airspeed-independent |
| `YAW_RAMP` | 0.5 °/tick² | pedal ramp; same shape as the fixed-wing rudder |
| `TURN_FROM_BANK` | 2.60 | bank-to-turn gain — **commanded**, not realised; see §5 |
| `TURN_COORDINATION_SPEED` | 0.80 b/t | forward speed at which a bank is a full turn |
| `VELOCITY_ALIGN_RATE` | 0.10 /tick | weathervane: how fast velocity follows the nose |
| `ALIGN_MIN_SPEED` / `ALIGN_FULL_SPEED` | 0.30 / 0.80 b/t | where the weathervane fades in |
| `H_DRAG_QUAD` / `H_DRAG_LIN` / `H_DRAG_CONST` | 0.009 / 0.0025 / 0.0002 | horizontal drag → top speed |
| `V_DRAG_QUAD` / `V_DRAG_LIN` | 0.045 / 0.050 | vertical drag → every descent rate |
| `MAX_SPEED` | 2.00 b/t | backstop only, never reached in flight |
| `GROUND_FRICTION` | 0.25 /tick | skid friction |
| `DEAD_DISC_DRAG` | 0.35 | vertical drag once the rotor stops |
| `DEAD_SPIN_RATE` | 10 °/tick | uncontrolled yaw after a kill |
| `DEAD_PITCH` / `DEAD_ROLL` | −8° / 35° | attitude a dead helicopter falls in |
| `CYCLIC_FULL` | 100 | full deflection in the percent units the setters take |
| gravity | −0.03 b/t² | inherited from `TempMotionVars`; honours `isNoGravity()` |

---

## 9. Control surface

All five inputs are **synchronised entity data**, so the server, the flying client and any
server-side controller read the same values. All five are **latching** — set them when they change,
like a held key, not every tick.

| control | setter | getter | units | meaning |
|---|---|---|---|---|
| collective trim | `setThrottle(int)` | `getThrottle()` | 0..5, 0..10 with a booster | 0.010 b/t² of thrust per notch; `HOVER_THROTTLE` = 3 holds altitude |
| collective boost | `setCollectiveBoost(boolean)` (= `setMoveUp`) | `getCollectiveBoost()` | boolean | momentary +2 notches |
| longitudinal cyclic | `setCyclicForward(int)` | `getCyclicForward()` | −100..+100 percent | +100 = 25° nose down = accelerate forward |
| lateral cyclic | `setCyclicRight(int)` | `getCyclicRight()` | −100..+100 percent | +100 = 25° bank right |
| pedals | `setPedal(int)` (= `setYawRight`) | `getPedal()` | −1 / 0 / +1 | yaw command, ±3.0 °/tick |

Read-outs, all public: `getVerticalSpeed()` (b/t, +up), `getHorizontalSpeed()` (b/t),
`forwardSpeed()` (b/t along the nose, negative flying backwards), `getCollectiveNotches()`,
`rotorAxis(Vector3f)`, plus the inherited `getOnGround()`, `getHealth()`, `isPowered()`.

**The cyclic is proportional and the pedal is a sign**, deliberately. The cyclic is a position
command — stick position sets disc tilt sets cruise speed — so a speed controller picks a value and
holds it. The pedal is a rate command on an integrator with a ramp, the same double-integrator the
fixed-wing rudder is, so a heading controller pulses it and `AutopilotMath.bangBang`'s angular
stopping-distance term applies unchanged. Making the pedal proportional would silently break that
controller's braking model.

**`setPitchUp` does nothing on a helicopter.** The fixed-wing elevator input is a rate command whose
sign convention is the opposite of a cyclic (+1 = nose *up*, where the helicopter's "go forward" is
nose *down*), so reusing it would have made `+1` mean opposite things on the two airframes. It is
left untouched and unread; the client does not even send `PitchPacket` while a player is in a
helicopter, which also keeps `PITCH_UP` at 0 and out of the `getPitchUp() != 0 → setOnGround(true)`
branch in `PlaneEntity#tick`.

`getRotationSpeedMultiplier()` returns **1.2**, chosen so the fixed-wing idiom
`2.5f * getRotationSpeedMultiplier()` reproduces this airframe's real 3.0 °/tick yaw ceiling. The
matching pitch idiom, `5.0f * multiplier`, does **not** describe a helicopter — the cyclic is a
position command limited by `MAX_CYCLIC_RATE`. Use the constants.

### Player controls

Unchanged keys, new meanings, chosen so the layout matches the plane's:

| key | control |
|---|---|
| W / S | longitudinal cyclic — forward / backward |
| A / D | lateral cyclic — bank and translate left / right (and, at speed, turn) |
| ← / → | pedals — yaw on the spot |
| ↑ / ↓ | collective trim (throttle) |
| space | collective boost |

A/D reach the server through the new `HeliCyclicPacket`, sent on change from
`ClientEventHandler#onClientTick` when the vehicle is a helicopter. It carries both axes because
they change together, and it exists at all because the strafe axis is not a packet in vanilla (§0).

### Persistence

The helicopter saves and restores `throttle`, `collective_boost`, `cyclic_forward`, `cyclic_right`
and `pedal`. `PlaneEntity` deliberately does not persist any of that, and for a plane it is right —
a plane reloaded at idle glides down and lands. For a helicopter the throttle **is** the collective,
so an airframe restored at notch 0 would drop out of the sky at 8.6 blocks/s through no fault of
whoever parked it.

It also makes the aircraft drivable from the console, which is what every measurement in this
document was taken with, since there is no fixed-wing autopilot that understands this airframe:

```sh
./cmd.sh 'summon simpleplanes:helicopter 0 200 0 {upgrades:{"simpleplanes:electric_engine":{energy:1500000}},throttle:4,cyclic_forward:100}'
./cmd.sh 'data merge entity @e[type=simpleplanes:helicopter,limit=1] {pedal:1b}'
./cmd.sh 'data get entity @e[type=simpleplanes:helicopter,limit=1] Motion'
```

---

## 10. Class hierarchy

`HelicopterEntity extends LargePlaneEntity` was the wrong shape: the helicopter wanted the **cabin**,
not the **wing**, and it was getting both and then stubbing out about half of the wing one override
at a time.

The cabin is now `LargeAirframeEntity extends PlaneEntity` — the large-upgrade bay, the payload rack,
the seat count, the `getEntityYOffset` and the habit of collecting livestock, all moved verbatim.
`LargePlaneEntity` and `HelicopterEntity` are now peers extending it, and `LargePlaneEntity` keeps
only what makes it a plane: `getGroundPitch() = 0`, `getRotationSpeedMultiplier() = 0.5`, its seat
layout, its camera distance and its item.

Four type checks moved from `LargePlaneEntity` to `LargeAirframeEntity`, because they are all asking
"does this aircraft have a large-upgrade bay" and the helicopter does: `LargeUpgrade`'s constructor
and `remove()`, `PlaneUpgradeSlot#mayPlace`, `StorageContainer#stillValid`,
`ModifyUpgradesContainer#tryUpgradeFromItem`. `PlaneCollisions.massOf` is unchanged — it tests
`HelicopterEntity` first, so the helicopter's 1.15 was never reached through the
`LargePlaneEntity` branch. `SolarPanelUpgrade` is unchanged too: it tests `LargePlaneEntity` for
panel placement and the helicopter refuses solar panels in `canAddUpgrade` anyway.

`PlaneEntity` gains exactly one thing: `refreshGroundContact()`, the two-line `onGroundTicks`
hysteresis lifted verbatim out of `tickOnGround` so a subclass with its own ground handling can keep
the counter running. No behaviour change — and all three fixed-wing airframes were re-flown
afterwards (§11).

---

## 11. What was tested, and what was not

The rig is a private copy of the `TESTING.md` server on **port 25580** (`/home/user/heliserver`),
superflat, `pause-when-empty-seconds=0`, `spawn_mobs false`, `random_tick_speed 0`. The clock is
`/time query gametime` — the world's own tick counter — so every number above is in ticks and no
wall-clock assumption enters anywhere. `tick rate 2000` (measured: 0.0 ms/tick, target 0.5) makes a
400-tick run cost 0.2 s instead of 20.

**Verified by measurement**, all numbers above: the hover throttle, the ten-row vertical ladder, top
speed and cruise attitude at seven throttle/cyclic combinations, the acceleration and braking
curves, the pedal rate in the hover and at cruise, the bank-to-turn rates and radii and their
symmetry, hover sidestep behaviour, sitting on the ground, take-off at four collectives, landings at
three collectives, the engine-out descent, and the shot-down descent and destruction.

**One end-to-end sortie**, scripted through `/data merge entity` so every input goes through the
public control surface and nothing is teleported: lift off from the superflat at collective 4, level
at 25 blocks, trim to 3 and 35 % forward cyclic, cruise at 0.55 b/t, a 172° pedal turn in 60 ticks,
fly back, full aft cyclic to brake, collective 1 to descend, touch down. `health: 10` at the end —
undamaged, and no NaN, no stuck attitude and no runaway anywhere in the sequence.

**Regression on the fixed-wing airframes**: `autopilot route` flown on all three at 1.20 b/t after
the hierarchy change — `Plane #34 landed at field-34/36 … 27% used`, `#35 (large) … 24% used`,
`#36 (cargo) … 24% used`, no exceptions in the log. The helicopter's cabin was re-checked too: a cow
mounted it by itself (so `LargeAirframeEntity#tick` works) and a `simpleplanes:chest` large upgrade
is accepted and reads back out of the entity's `upgrades` tag.

**Force-loading**: `TESTING.md` is right that force-loading hides bugs, and it is used here anyway,
for a reason that does not apply to the autopilot tests it warns about. An unmanned helicopter
carries no autopilot and therefore renews no chunk ticket, so at 1.2 b/t it leaves any loaded region
in about a minute and freezes with its velocity intact — which is exactly what happened on the first
horizontal run and produced a table of plausible-looking equilibria that were 150 ticks of
simulation and 1350 ticks of a frozen entity (the tell: `pos` and `Motion` identical across eleven
consecutive 150-tick probes). The tests here are of the flight model, not of chunk loading, so a
±112-block square and a 1900-block corridor along +Z are force-loaded and the fact is recorded
rather than hidden. **Nothing above is a claim about chunk behaviour**, and a parked helicopter
unloads exactly like a parked plane.

**NOT verified: the ridden path.** No real client was run — `TESTING.md` §4 marks the headless
client as unverified end to end, and driving one through a 500 MB first-run asset download and a
software rasteriser was not affordable here. What can be said precisely:

* Every control input is synched entity data read identically on both logical sides, so the client's
  physics and the server's fallback see the same numbers. The old model's inputs came from
  `Player.xxa`/`zza`, which exist on one side only; that asymmetry is gone.
* `rotorAxis()` — the whole flight model — is built from `xRot`/`rotationRoll`/`yRot`, not from
  `Q_Client` or `Q`, so it does not care which side is authoritative. This is the specific trap that
  `AUTOPILOT.md` "Thrust direction" documents for the fixed-wing thrust vector, and the helicopter
  is structurally immune to it.
* The key→packet mapping in `ClientEventHandler#onClientTick` is client-only code that was compiled
  but not exercised.

So: the *model* is measured, and the *player input plumbing* is reviewed and compiled but not
flown. That is the honest statement.

---

## 12. Known warts

* **The bank-to-turn response is non-linear** (§5): 0.261 °/tick at half stick, 1.706 at full. The
  pedal is linear and exact; use it for heading.
* **No collective notch holds altitude at cruise attitude** (§3). Dither 3/4.
* **The vertical axis cannot destroy the aircraft** (§2). By design, stated so it is a decision and
  not an oversight.
* **`onGroundTicks` still oscillates** — `PHYSICS-AUDIT.md` **N4**, inherited unchanged through
  `refreshGroundContact()`. It makes the ground/air transition timing non-deterministic by 0–4
  ticks. Not fixed here: it is shared with all three planes.
* **A hover sidestep drifts round slowly** (§5), about 61° over 400 ticks at full lateral stick.
