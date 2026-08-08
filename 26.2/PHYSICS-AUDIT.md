# PHYSICS-AUDIT — Simple Planes flight model (26.2 port)

Scope: `entities/PlaneEntity.java`, `entities/LargePlaneEntity.java`, `entities/CargoPlaneEntity.java`,
`entities/HelicopterEntity.java`, `misc/MathUtil.java`.

All vanilla behaviour cited below was verified against the decompiled 26.2 sources in `/opt/mc-src`,
not from memory. All line numbers refer to the **post-change** files unless marked "(before)".

Out of scope, deliberately untouched (other agents own them): the collision block
`PlaneEntity.java:577..602` (`horizontalCollision` / `crash()`), `hurtServer`, `checkFallDamage`,
`causeFallDamage`. Problems found there are listed but not fixed.

> **Update:** the collision block described above has since been replaced wholesale by
> `entities/PlaneCollisions.java` — the `speedBefore/speedAfter` crash check, the `onGroundTicks`
> crash gate and the `causeFallDamage` roll rule no longer exist. Impact detection is now per-axis
> geometric (full blocked-axis velocity of the motion handed to `move()`), and nothing is inferred
> from `getKnownMovement()` history, which packet timing makes non-monotonic. See
> `COLLISION-DIAGNOSIS.md` for the full derivation and the test-rig numbers. Collision-block
> remarks below are kept for the record and marked where superseded.

---

## 1. How the model actually works

### 1.1 State and coordinate systems

The plane's attitude lives in **three redundant representations that are re-synchronised every tick**:

| Representation | Field | Authority |
|---|---|---|
| Quaternion, synched | `entityData Q` (`Quaternionfc`) | server copy / what remote clients lerp toward |
| Quaternion, client | `Q_Client`, `Q_Prev` | what the renderer uses, interpolated by `tickLerp()` |
| Euler angles | `getXRot()` (pitch), `getYRot()` (yaw), `rotationRoll` | what all the physics code actually reads and writes |

One tick is a **round trip through both representations**:

```
q  = getQ()/getQ_Client()                     // quaternion in
anglesOld = toEulerAngles(q)                  // remember where we started
   ... all physics runs on xRot/yRot/rotationRoll ...
q.rotateZ(rotationRoll - anglesOld.roll)      // fold the euler *deltas* back in
q.rotateX(anglesOld.pitch - getXRot())
q.rotateY(getYRot()   - anglesOld.yaw)
q = normalizeQuaternionf(q)                   // quaternion out
tickDeltaRotation(q)                          // and immediately decompose again
```

`tickDeltaRotation()` then overwrites `xRot`/`yRot`/`rotationRoll` from `q`, so the euler angles are
*derived* state and the quaternion is the truth. The euler round-trip is why `MathUtil.toEulerAngles`
uses the non-standard axis assignment it does (roll around the plane's own Z, pitch around its own X
negated to match Minecraft's "negative xRot = nose up", yaw around world Y) — it is the exact inverse
of `MathUtil.toQuaternionf(yaw, pitch, roll)`.

Body → world transforms go through `transformPos(Vector3f)`, which negates yaw and roll (the render
frame is mirrored relative to the physics frame) and is used for seat positions and the
landing-attitude test.

**Thrust does not go through it, and must not.** `transformPos` rotates by `Q_Client`, which on the
server is written by nothing except `RotationPacket` — a packet sent by the player flying the plane.
A plane with nobody aboard keeps the `Q_Client` it was created with for its whole life, while `Q` is
rewritten from the integrated attitude at the end of every `tick()`. Building the thrust vector from
`Q_Client`, as `getTickPush` originally did, therefore made an unmanned aircraft push in the
direction it was **spawned** facing regardless of where its nose actually pointed: straight-line
flight was perfect and every turn silently destroyed the aircraft's energy. `getTickPush` now uses
`transformPosPhysics`, which picks `Q` when there is no controlling passenger. A ridden plane is
unaffected — its `Q_Client` is refreshed every tick by the authoritative client — so this is
specifically a fix to the server-simulated path. Measured: a 180° turnback that used to leave the
aircraft pinned at 0.36 blocks/tick at full throttle now holds 0.75 throughout. See
`AUTOPILOT.md`, "Thrust direction".

### 1.2 Who is authoritative

`isLocalInstanceAuthoritative()` is true on the client that is riding the plane. That client runs the
**whole** physics tick and ships the resulting quaternion to the server via `RotationPacket`; the
server-side entity also runs the same tick (as the fallback for an unmanned plane) and pushes position
through the normal entity sync. A remote client runs no physics at all — `tick()` bails out at
`PlaneEntity.java:495` into `tickLerp()` + `tickDeltaRotation()` only.

### 1.3 Gravity and drag — nothing comes from vanilla

**Verified in `/opt/mc-src/net/minecraft/world/entity/Entity.java`:**

* `Entity.getDefaultGravity()` returns `0.0` and `applyGravity()` is **never called** anywhere in the
  `Entity` tick chain (only `LivingEntity`/`AbstractBoat` etc. call it from their own `travel()`).
  `PlaneEntity` does not override `getDefaultGravity()` and does not call `applyGravity()`.
  → **All gravity is the mod's manual `tempMotionVars.gravity = -0.03`** added in `tickMotion()`.
* `Entity.move()` (line 718) applies **no drag at all**; the only thing it multiplies into
  `deltaMovement` afterwards is `getBlockSpeedFactor()` (1.0 for normal blocks, 0.4 on soul sand,
  0.4 on honey). → **All drag is the mod's `drag`/`dragMul`/`dragQuad`.**
* `Entity.maxUpStep()` returns `0.0F` for everything that is not a `LivingEntity`, and
  `Entity.collide()` only enters its step-up branch when `maxUpStep() > 0`. See issue **B1**.

Vanilla comparison for the ground roll (`AbstractBoat#floatBoat`, line 527): boats use *multiplicative*
friction `v *= landFriction` with `landFriction` averaged from `Block.getFriction()`. The mod instead
uses an additive/linear/quadratic drag polynomial. Both are defensible; the mod's is stiffer at low
speed, which is what makes the low-throttle creep behaviour described in **B2** possible.

One more non-obvious limiter: the terminal speed is set **not** by the drag polynomial but by the push
scaling in `tickMotion`, `push *= clamp(1 − dot·v/(maxPushSpeed·(push+0.05)), 0, 2)`. On the ground at
throttle 5 that caps the plane at **0.48 b/t**; drag alone would allow 1.20 b/t.

### 1.4 The per-tick pipeline

`PlaneEntity.tick()`:

1. `tempMotionVars = getMotionVars()` — a per-entity scratch struct reset to defaults each tick, then
   overridden by subclasses (`HelicopterEntity.getMotionVars`) and by `isNoGravity()`.
2. `push = 0.00625 * throttle` — raw thrust magnitude (`tick()` line 545 → now line 550).
3. `tickRotateMotion()` (only if `|v| > 0.05`) — **aerodynamics**: turns the velocity vector toward
   the nose, adds lift, and blends the attitude quaternion toward the velocity direction.
4. `tickOnGround()` (only if `getOnGround() || isOnWater()`) — **ground handling**: rest attitude,
   thrust modifiers, rolling resistance, and the `speedingUp` flag that gates the elevator.
5. `tickPitch()` / `tickYaw()` — elevator and rudder, integrated as an angular *rate* with a ramp.
6. `tickMotion()` — **drag, speed cap, thrust application, gravity**.
7. `tickRoll()` — ailerons in the air, nose-wheel steering on the ground.
8. `move(MoverType.SELF, motion)` + collision handling.
9. Euler deltas folded back into the quaternion; sync.

### 1.5 Where lift comes from (important, and not where you'd expect)

`tickRotateMotion()` (`PlaneEntity.java:1031`) has **two** lift-like terms, and the smaller-looking one
does most of the work:

```java
setDeltaMovement(rotationToVector(
    lerpAngle180(0.1f,                      yaw,   getYRot()),        // velocity yaw follows nose yaw
    lerpAngle180(pitchToMotion * d,         pitch, getXRot()) + lift, // velocity pitch follows nose pitch
    speed));                                                          // speed is preserved exactly
```

* `pitchToMotion * d` (0.2 × `d`) drags the **velocity vector's pitch 20 % of the way toward the
  nose's pitch every tick**. This is the real lift: with the nose at +20° and the velocity at −5°, the
  velocity rotates upward by ~5°/tick. It is what holds the plane up.
* `lift` is an *additional* constant upward bias on the velocity pitch, in degrees per tick, capped at
  `maxLift = 2`.
* `d = 1 − min(1, Δ/60)²` where `Δ` = |velocity pitch − nose pitch| is a crude angle-of-attack
  efficiency curve: full authority at 0° AoA, zero at 60° AoA.

Gravity fights this geometrically: adding `-0.03` to `y` tilts the velocity vector down by
`atan(0.03 / speed)` degrees per tick — **5.7°/tick at speed 0.3, 1.7°/tick at speed 1.0**. Note that
**`lift` carries the same `d` factor as the `pitchToMotion` term** (`lift = min(...) * d` in the
source), so level flight is where `d·(0.2·Δ + lift) == atan(0.03/v)`, *not* `0.2·Δ·d + lift`. Getting
that wrong is what produced the bad stall-speed numbers in the first revision of this document.
Maximising `d·(0.2Δ + L)` over Δ gives the hard aerodynamic ceiling — **6.01°/tick** at full lift
(`L = 2`, at `Δ ≈ 31.5°`) — so the plane cannot sustain level flight below `v = 0.03/tan(6.01°) ≈
0.285 b/t`. That is the implicit stall speed, and tuning `lift` is how you place it. See issue **B3**.

Nothing in the model has mass, wing area, air density, or a lift/drag polar. `maxSpeed` is a hard
speed clamp, not a thrust/drag equilibrium.

---

## 2. Constant table

`TempMotionVars.reset()` — `PlaneEntity.java:1596`. "b/t" = blocks per tick (×20 = blocks/s).

| Constant | Value | Where used | Effect | Reasonable? |
|---|---|---|---|---|
| `maxSpeed` | 3 | `tickMotion` | hard clamp on `|v|`, lerped at 0.2 | Yes — 60 b/s is never reached in practice |
| `maxPushSpeed` | `getMaxSpeed()*10` = 10 | `tickMotion` push scaling | **the real terminal-speed limiter**, not the drag polynomial: push is scaled by `1 − v/(maxPushSpeed·(push+0.05))`. Ground terminal at throttle 5 is **0.48 b/t** with it, 1.20 without | Fine, but badly named |
| `takeOffSpeed` | 0.3 | `tickOnGround` (elevator gate, rest attitude), now also lift/authority scaling | minimum flying speed | Yes; now consistently the stall speed too |
| `maxLift` | 2 (°/tick) | `tickRotateMotion` | extra upward bias on the velocity vector | Yes |
| `liftFactor` | 10 | **now unused** (deprecated) | old lift slope; saturated at v=0.2 | Shape is wrong (linear, early ceiling) — see **B3** |
| `stallSpeedFactor` | 0.55 *(new)* | `getLiftRatio` | stall at `0.55 × 0.3 = 0.165` b/t | New |
| `liftSaturationFactor` | 1.3 *(new)* | `getLiftRatio` | full lift at `1.3 × 0.3 = 0.39` b/t | New |
| `gravity` | −0.03 | `tickMotion` | 0.6 b/s² — 2.7× weaker than vanilla's 0.08 | Arcade, but internally consistent |
| `drag` | 0.001 | `tickMotion` | constant speed loss; sets the low-throttle creep floor (0.010 b/t at throttle 1) | Fine |
| `dragMul` | 0.0005, **×48 on the ground** | `tickMotion` | linear drag; `20*(3−f)`, f=0.6 normal, 0.98 ice | Value fine, but the 48× step at lift-off is a discontinuity (**B4**) |
| `dragQuad` | 0.001 | `tickMotion` | quadratic drag; only matters above v≈1 | Yes |
| `push` | `0.00625 × throttle` | `tickMotion` via `getTickPush` | thrust. 0.03125 at throttle 5, 0.0625 with booster (throttle 10) | Yes |
| `groundPush` | 0.01 | `tickOnGround` | thrust floor while holding pitch-up/down on the ground | Yes |
| `passiveEnginePush` | 0.025 | **never read anywhere** | dead constant (also dead in 1.21.1) | Dead code |
| `motionToRotation` | 0.05 | `tickRotateMotion` | how fast the attitude follows the velocity (weathervane) | Yes |
| `pitchToMotion` | 0.2 | `tickRotateMotion` | how fast the velocity follows the attitude — **the actual lift term** | Yes |
| `yawMultiplayer` | 0.5 | **never read anywhere** | dead constant | Dead code |
| `turnThreshold` | `TURN_THRESHOLD/100` = 0.2 | `tick()` | strafe deadzone | Yes |
| `minPitchAuthority` | 0.35 *(new)* | `getPitchAuthority` | elevator floor far below take-off speed | New |
| `minGroundSteering` | 0.2 *(new)* | `tickRoll` | nose-wheel floor at a standstill | New |
| pitch rate ramp / clamp | ±0.5 / ±5.0 °/tick | `tickPitch` | ×`getRotationSpeedMultiplier()` | Yes |
| yaw rate ramp / clamp | ±0.5 / ±2.5 °/tick | `tickYaw` | ×`getRotationSpeedMultiplier()` | Yes |
| roll rate ramp / clamp | ±0.5 / ±5.0 °/tick | `tickRoll` | **not** scaled by `getRotationSpeedMultiplier()` | Inconsistent (**M5**) |
| ground steering | ±3 °/tick | `tickRoll` | now speed-scaled | Was unconditional |
| `getRotationSpeedMultiplier()` | 1.0 / 0.5 / 0.2 | Plane / Large+Heli / Cargo | mass proxy | Yes |
| `getGroundPitch()` | 5 / 0 / 0 | Plane / Large / Cargo+Heli | resting nose-up attitude | Fine (see **B2**, retracted) |
| `getLandingAngle()` | 30 | `checkFallDamage` | max attitude for a safe touchdown | Yes |
| `brakesMul` | 5.0 at throttle 0 | `tickMotion` | air/ground brake | Yes |
| `MAX_THROTTLE` | 5 (10 with booster) | `changeThrottle` | — | Yes |
| `onGroundTicks` reset | 5 | `tickOnGround` | ground/air hysteresis | Buggy (**N4**) |

---

## 3. Problems found

Legend: **(a)** port bug (divergence from `1.21.1/`) · **(b)** model bug · **(c)** numerical stability.
"Impact" is the practical severity.

### Port bugs (a)

#### P1 — `getHorizontalDistanceSqr` → `Vec3.horizontalDistanceSqr()` is not the same function *(NOT FIXED — other agent's block)*
`PlaneEntity.java:577, 578, 590` vs `1.21.1/.../PlaneEntity.java:469, 470, 482`.

`MathUtil.getHorizontalDistanceSqr(v)` is **misnamed**: it returns `sqrt(x²+z²)`, i.e. the horizontal
*distance*. The port replaced it with vanilla `Vec3.horizontalDistanceSqr()`, which really is `x²+z²`.
Two consequences:

* line 577 (the move gate): threshold changes from `dist > 1e-5` to `dist² > 1e-5`, i.e.
  `dist > 3.16e-3` — **316× stricter**. Below that, `move()` only runs on every 4th tick
  (`(tickCount + getId()) % 4 == 0`). A plane creeping forward at 0.001 b/t now stutters.
* lines 578/590 (crash damage): `speedBefore` was `sqrt(sqrt(x²+z²))` = dist^0.5 in 1.21.1 and is now
  the true `dist`. Since `f2 = (speedBefore − speedAfter)*10 − 5` and crashes need `f2 > 5`, the
  threshold moved from a speed drop of ~1.0 (dist^0.5 units, i.e. dist ≈ 1.0) to a speed drop of 1.0
  b/t. **Wall impacts below 1.0 b/t horizontal speed no longer crash the plane** where the 1.21.1
  build would have crashed at ~1.0 b/t as well — the numbers happen to land close, but the *curve* is
  different at low speed (`dist^0.5 > dist` for `dist < 1`), so slow scrapes used to be far more
  lethal than they are now.

The 26.2 form is arguably the *correct* physics; the point is that it silently changed the crash
tuning. **Left alone: lines 577–590 are inside the collision block owned by another agent.**
Recommendation for whoever owns it: decide deliberately, and if 1.21.1 parity is wanted, use
`MathUtil.getHorizontalDistanceSqr(getDeltaMovement())`, which still exists.

> **Superseded:** the `speedBefore/speedAfter` expression is gone entirely; impact severity is now
> measured in `PlaneCollisions.afterMove` from the blocked-axis component of the motion vector, so
> this distance-vs-distance² distinction no longer feeds any crash decision. The move-gate use at
> line 577 (first bullet) is unchanged and the note about it still applies.

#### P2 — block friction lost its per-BlockState hook *(accepted, documented in code)*
`PlaneEntity.java:939` vs `1.21.1:765`. NeoForge's
`BlockState.getFriction(Level, BlockPos, Entity)` has no 26.2 equivalent; the port uses
`state.getBlock().getFriction()`. Verified against `/opt/mc-src`: `Block.getFriction()` (Block.java:486)
is exactly what vanilla `AbstractBoat#getGroundFriction()` uses, so this is the idiomatic form.
Vanilla blocks are unaffected; modded blocks with state-dependent friction lose it. Not worth fixing.

#### P3 — everything else in the flight model ported faithfully
`diff -u` of `MathUtil.java` between `1.21.1/` and `26.2/` was **byte-identical** before this audit.
`HelicopterEntity` differed only by the `npc.Villager → npc.villager.Villager` import.
`LargePlaneEntity`/`CargoPlaneEntity` differed only in networking/`isClientSide()`.
`PlaneEntity`'s physics methods (`tickRotateMotion`, `tickPitch`, `tickYaw`, `tickRoll`, `tickMotion`,
`tickOnGround`, `tickDeltaRotation`) and **every constant in `TempMotionVars.reset()`** matched
1.21.1 exactly. No coefficients or signs were lost in the port. The takeoff problems are **model**
bugs inherited from upstream, not port regressions.

### Model bugs (b)

#### B1 — planes have zero step height, so the `setOnGround(true)` hack is a no-op *(FIXED)*
`PlaneEntity.java:582` / new `maxUpStep()` at `PlaneEntity.java:1004`.

`Entity.collide()` (`/opt/mc-src/.../Entity.java:1152`) only considers stepping up when
`this.maxUpStep() > 0.0F && (onGroundAfterCollision || this.onGround()) && (xCollision || zCollision)`.
`Entity.maxUpStep()` returns **0.0F** (Entity.java:3901) and only `LivingEntity` overrides it —
`PlaneEntity` did not. So the `setOnGround(true)` call placed in front of `move()` specifically to
satisfy the `onGround()` half of that condition **has never done anything**: the branch is dead
because `maxUpStep()` is 0.

Impact: **high, and directly a take-off bug.** A slab, a dirt-path edge, a farmland block or a
grass-block lip stops the ground roll dead and sets `horizontalCollision`, which then feeds the crash
check. Take-off only worked on perfectly flat, uninterrupted terrain.

Fix: override `maxUpStep()` to return `0.6F` while horizontal speed is below 0.5 b/t (the whole
ground-roll range) and `0.0F` above it, so flying into terrain still collides and still crashes
exactly as before. 0.6 clears slabs (0.5) and path/farmland lips (0.0625) but not a full block.

#### B2 — RETRACTED. The ground roll was never broken; my arithmetic was *(change REVERTED)*
`PlaneEntity.java:896..904`.

**An earlier revision of this document claimed the small plane could never reach take-off speed
without holding pitch-up, settling at 0.0104 b/t. That was wrong, and the code change made on the
strength of it has been reverted.** The error: I divided the push by 5 **twice** — once for the
`push /= 5` in `tickOnGround` and, by mistake, a second time when substituting the number. The value
0.00625 is *both* the raw push at throttle 1 *and* the post-`/5` push at throttle 5, and I conflated
them. Correct arithmetic for throttle 5 on grass (`dragMul = 0.0005 × 20 × 2.4 = 0.024`):

`0.024·v + 0.001 = 0.00625` → **v = 0.219 b/t**, not 0.0104. 0.0104 is the throttle-**1** figure.

Since 0.219 > the 0.1 b/t threshold, the plane *escapes* the reduced-thrust regime, full thrust is
restored, and it accelerates away. Verified by transcribing the real tick (`tickRotateMotion` →
`tickOnGround` → `tickMotion` → ground clamp) into a simulation rather than trusting algebra again:

| throttle | ticks to reach 0.3 b/t (original code) |
|---|---|
| 1 | never — settles at **0.010** b/t |
| 2 | never — settles at **0.061** b/t |
| 3 | 126 ticks (6.3 s) |
| 4 | 56 ticks (2.8 s) |
| 5 | **38 ticks (1.9 s)** |

So the upstream ground roll is **fine**: full throttle gets a small plane airborne in under two
seconds of runway with no pitch input at all. What actually exists is much narrower and is *not*
clearly a bug: the `push /= 5` step makes the roll **bistable**, because escaping it requires the
reduced-thrust equilibrium `(push·cos(nose) − drag)/dragMul` to exceed the 0.1 b/t threshold. It does
from throttle 3 up and does not at throttle 1–2, so those two notches settle into a permanent crawl.
That reads perfectly well as "idle taxi power", so it is left alone and recorded here as a wart, not
a defect.

**Reverted:** `tickOnGround` is back to the upstream `push /= 5`, and the `groundRollSpeed` /
`lowSpeedThrustFactor` knobs added for it were removed. The shipped code's ground roll is now
bit-identical to upstream — re-simulated at 38 ticks at throttle 5, matching the original exactly.

Lesson recorded for the rest of this document: every remaining numeric claim below was re-derived by
simulation, not by hand.

#### B3 — lift saturated *below* the take-off speed *(FIXED)*
`PlaneEntity.java:1026` (was `lift = min(speed * liftFactor, maxLift) * d`, `liftFactor = 10`).

`speed × 10` reaches `maxLift = 2` at **speed 0.2** — a third *below* the 0.3 b/t take-off speed. So
a plane crawling at 0.2 b/t had exactly the same wing coefficient as one at cruise. Real lift is
quadratic in airspeed with a hard floor at the stall speed; this was linear with an early ceiling and
no floor at all.

**Corrected severity.** An earlier revision of this section claimed the old stall speed was ≈0.24 b/t
("20 % below take-off speed") and used that to explain "взлетел на нулевой". That was also wrong: I
dropped the `* d` that multiplies `lift`, so I summed `0.2·Δ·d + lift` instead of the correct
`d·(0.2·Δ + lift)`. Re-derived numerically (maximising `d·(0.2Δ + L)` over the angle of attack Δ and
comparing against the gravity tilt `atan(0.03/v)`):

| speed | gravity tilt needed | old max climb | old | new max climb | new |
|---|---|---|---|---|---|
| 0.20 | 8.53 °/t | 6.01 | sinks | 4.76 | sinks |
| 0.25 | 6.84 °/t | 6.01 | sinks | 5.00 | sinks |
| 0.28 | 6.12 °/t | 6.01 | sinks | 5.18 | sinks |
| 0.30 | 5.71 °/t | 6.01 | flies | 5.31 | sinks (marginal) |
| 0.32 | 5.36 °/t | 6.01 | flies | 5.44 | flies |
| 0.35 | 4.90 °/t | 6.01 | flies | 5.67 | flies |
| ≥0.39 | ≤4.40 °/t | 6.01 | flies | 6.01 | **identical** |

**Old stall speed: 0.285 b/t. New: 0.316 b/t. Take-off speed: 0.300 b/t.** So the old model did *not*
let you fly at 0.2 b/t — it stalled 5 % *below* the nominal take-off speed, and the new one stalls 5 %
*above* it. This is a modest tightening that makes `takeOffSpeed` mean what its name says, not a
rescue from a broken model, and the "взлетел на нулевой" story is **not** supported by the code.

Fix: `getLiftRatio()` — zero below `takeOffSpeed × 0.55` (0.165 b/t), rising with `v²`, saturating at
`takeOffSpeed × 1.3` (0.39 b/t). Everything at and above 0.39 b/t — i.e. all normal flight — is
**numerically identical** to the old model. Simulation also confirms the change is ground-roll
neutral: 38 ticks to take-off speed at throttle 5 either way.

The honest justification for keeping it is therefore the *shape*, not a bug fix: lift now builds
progressively through the roll instead of being pinned at maximum from 0.2 b/t onward, which is what
makes the lift-off gradual, and the stall speed now coincides with the documented take-off speed.

#### B4 — elevator authority does not depend on airspeed *(FIXED — but scope corrected)*
`PlaneEntity.java:754..757` (`tickPitch` clamp).

`tickPitch` ramps `pitchSpeed` by 0.5°/tick to a **5°/tick** ceiling — 100°/s. From a standing start
that is 27.5° of nose-up in the first 10 ticks and **vertical in ~22 ticks (1.1 s)**, at any airspeed,
because nothing in the clamp knows about airflow.

**Scope correction.** An earlier revision billed this as the cause of the take-off "jump", on the
grounds that the elevator switches from disabled to full authority as the roll crosses 0.3 b/t. The
switch is real — `tickOnGround()` returns `speedingUp = false` below `takeOffSpeed` and `tick()` then
skips `tickPitch()` entirely — but the fix does **not** affect it: `getPitchAuthority()` returns
exactly 1.0 at and above `takeOffSpeed`, and on the ground `tickPitch` only ever runs at or above
`takeOffSpeed`. **So this change is a no-op for the entire ground roll and lift-off.**

What it actually does is damp the elevator when flying *below* take-off speed, i.e. while stalled,
where reduced control authority is the physically correct answer and forces a nose-down recovery
instead of rewarding pulling harder. That is worth having, but it is a stall-handling change, not a
take-off change. The binary `speedingUp` gate itself is left alone.

#### B5 — the plane could pirouette on the spot *(FIXED)*
`PlaneEntity.java:799..808` (`tickRoll`, ground branch).

On the ground, strafing yaws the plane by a flat ±3°/tick = 60°/s regardless of speed — including at a
dead stop with the engine off. Besides being nonsense (no airflow over the rudder, no wheel motion),
it made the take-off run wander because the same input works at 0.05 b/t as at 0.5 b/t.

Fix: scale by `clamp(speed / takeOffSpeed, 0.2, 1)`. Full authority is restored at take-off speed, so a
plane that is actually rolling behaves exactly as before; a parked one turns at 0.6°/tick (12°/s),
slow but still steerable so taxiing does not become miserable.

#### B6 — 48× rolling resistance was applied for up to 4 ticks after lift-off *(FIXED)*
`PlaneEntity.java:926..939` (`tickOnGround`, friction block).

`getOnGround()` is `onGround() || onGroundTicks > 1`, so `tickOnGround()` — including
`dragMul *= 20*(3−f)`, a **48× drag multiplier** — kept running for up to four ticks after the wheels
left the runway. That is a deceleration spike applied exactly at the moment of lift-off (the "рывок"
on the ground→air transition), and it sampled the friction of whatever *air* block happened to be one
block below the plane.

Fix: gate the friction block on `onGround() || isOnWater()` (real contact) instead of the coyote-timer
`getOnGround()`. Everything else in `tickOnGround()` still uses the hysteresis, so the attitude/thrust
handling is unchanged. Also saves one `getBlockState()` per tick in that window.

#### M5 — roll rate ignores `getRotationSpeedMultiplier()` *(NOT FIXED — documented)*
`PlaneEntity.java:810..824`. `tickPitch` and `tickYaw` both scale their ramp and clamp by
`getRotationSpeedMultiplier()` (1.0 / 0.5 / 0.2), but `tickRoll`'s aileron ramp (`±0.5`) and clamp
(`±5.0`) are hard-coded. A cargo plane therefore pitches at 1°/tick and yaws at 0.5°/tick but rolls at
the same 5°/tick as the tiny starter plane. Present in 1.21.1 too. Left alone: fixing it would visibly
change the handling of every aircraft and is a balance decision, not a bug fix.

#### M6 — `passiveEnginePush` and `yawMultiplayer` are dead *(NOT FIXED)*
`PlaneEntity.java:1616, 1619`. Both are set in `reset()` and by `isNoGravity()`/`HelicopterEntity`,
and **never read**. Dead in 1.21.1 as well. Harmless; left in place because `HelicopterEntity` writes
to `passiveEnginePush` and removing the field would be a gratuitous API break.

#### M7 — `getMaxSpeed()` does not cap speed *(NOT FIXED)*
`getMotionVars()` uses it only for `maxPushSpeed = getMaxSpeed() * 10`; the actual speed clamp is the
hard-coded `maxSpeed = 3`. The synched `MAX_SPEED` value is 1.0 for every plane type (set in the
constructor, only ever changed by NBT), so all four aircraft share one speed cap. Misleading name;
behaviour is intentional upstream.

### Numerical stability (c)

#### N1 — `normalizeQuaternionf` returned the **zero quaternion** on degenerate input *(FIXED)*
`MathUtil.java:158` (was `return new Quaternionf(0, 0, 0, 0);`).

`(0,0,0,0)` is not a rotation. `Vector3f.rotate()` by it collapses every vector to zero (so every seat
position, the thrust vector and the landing-attitude test all become `(0,0,0)`), and `toEulerAngles`
reports `(0,0,0)`. A single degenerate frame therefore silently flattened the plane's orientation and,
because the result is stored straight back into `Q`, it was *sticky*. `RotationPacket.isValidRotation`
already had to defend against exactly this on the network boundary — a strong hint the bug was real.

Fix: return the identity quaternion `(0,0,0,1)`.

#### N2 — `fastInvSqrt` in the normalisation path *(FIXED)*
`MathUtil.java:129`. The Quake-style one-Newton-step reciprocal square root has up to **0.175 %**
relative error and was used to normalise the attitude quaternion **every tick**, immediately after
three chained `mul()` calls that each accumulate their own error. `Math.sqrt` is a JIT intrinsic on
every platform Minecraft runs on, so the approximation bought nothing measurable and cost accuracy.
Replaced with `1.0 / Math.sqrt(f)`. `fastInvSqrt` is kept and `@Deprecated` for source compatibility.

#### N3 — `normalizedDotProduct` divided by zero *(FIXED)*
`MathUtil.java:19..26`. `v1.dot(v2) / (v1.length() * v2.length())` is `0/0 = NaN` when either vector is
zero-length. The single in-mod call site (`tickMotion`) is guarded, but the result flows straight into
`setDeltaMovement()`, and `tick()` only has a *reactive* `Double.isNaN` guard at line 481 — one tick
too late for `move()`. Now returns 0 when the product of the lengths is below 1e-12.

#### N4 — `onGroundTicks` oscillates instead of latching *(NOT FIXED — documented)*
`PlaneEntity.java:881..885`:

```java
if (onGroundTicks < 0) { onGroundTicks = 5; } else { onGroundTicks--; }
```

While the plane sits on the runway this cycles `5,4,3,2,1,0,−1 → 5,…`, so `getOnGround()`
(`onGroundTicks > 1`) is a **7-tick square wave** and the ground/air hysteresis after lift-off is a
random 0–4 ticks depending on which phase the plane happened to leave the ground in. Non-deterministic
transition timing, and it also means the crash gate `onGroundTicks <= 0` is open for exactly 2 ticks
out of every 7 while taxiing (`getOnGround()` is true for the other 4 of the 7-tick cycle 5,4,3,2,1,0,−1).

**Not fixed on purpose**: latching it to 5 while in contact would close the crash gate permanently on
the ground, which is a behavioural change inside the collision block another agent owns. Suggested fix
if that agent wants it: `if (onGround()) onGroundTicks = 5; else onGroundTicks--;` plus a separate
explicit flag for the crash gate.

> **Partially superseded:** the crash gate is gone — `PlaneCollisions` uses no `onGroundTicks` gate
> (taxi bumps are excused by a ground-speed tolerance instead), so the "open 2 ticks out of 7" half
> of this note is history. The hysteresis half (non-deterministic ground/air transition timing for
> the flight model itself) still stands.

#### N5 — `rotationToVector(yaw, pitch, size)` did a redundant sqrt+divide *(FIXED)*
`MathUtil.java:76`. The base vector is unit by construction
(`cos²p·sin²y + sin²p + cos²p·cos²y = 1`), so `vec.scale(size / vec.length())` always evaluated to
`vec.scale(size)` — with a `Math.sqrt` and a division per call, every tick, in the hottest method in
the file. Also removes a divide-by-zero shape that could never actually fire but was there.

#### N6 — lag / long ticks
Everything is per-tick with no `deltaTime`, so a server running below 20 TPS makes planes fly *slower
in wall-clock terms* but the trajectory is unchanged. That is the correct behaviour for Minecraft and
needs no fix. The one real hazard is that `InterpolationHandler(this, 10)` on remote clients plus
`lerpStepsQ = 10` means a dropped position packet leaves a remote plane visually gliding for 10 ticks;
unchanged from upstream.

#### N7 — `markHurt()` every tick *(NOT FIXED)*
`PlaneEntity.java:503`, with an upstream `//TODO: this might be the cause of high network usage`.
Confirmed against `/opt/mc-src`: `hurtMarked` makes `ServerEntity` send a `SetEntityMotionPacket`.
Since the *client* is authoritative for a ridden plane, this is the server echoing velocity back at a
client that is going to overwrite it anyway. Left alone — it interacts with the damage/collision path
another agent owns, and the safe fix (drop it when a local player is in control) needs their sign-off.

---

## 4. Performance (Part C)

Fixed:

| Site | Was | Now |
|---|---|---|
| `isOnWater()` (`PlaneEntity.java:1255`) | up to **5 `BlockPos` allocations + 5 chunk lookups per tick** for the same block (ground test, `tickRoll`, ×2 in `tickRotateMotion`, `checkFallDamage`) | memoised per tick **and** per block position — 1 lookup/tick, still re-samples on a block boundary crossing or one tick after the world changes |
| `tickOnGround` friction (`:932`) | 1 lookup/tick, including 4 wasted ticks after lift-off | gated on real contact (**B6**) |
| `tick()` quaternion fold (`:608`) | `Axis.ZP/XN/YP.rotationDegrees()` → **3 `Quaternionf` allocations/tick** | in-place `q.rotateZ/rotateX/rotateY`. Verified identical: `/opt/mc-src/com/mojang/math/Axis.java` defines `ZP = a -> new Quaternionf().rotationZ(a)`, `XN = a -> new Quaternionf().rotationX(-a)`, and JOML's `rotateZ` is exactly `mul(rotationZ(a))` |
| `toEulerAngles` (`:538`, `:710`, `:1160`) | `new EulerAngles()` per call ×3/tick, plus a redundant `.copy()` | per-method scratch buffers via the new `toEulerAngles(q, dest)` overload |
| `transformPos` (`:1160`) | `new Quaternionf()` per call — and it is called once per passenger per tick **plus** once for thrust | `toQuaternionf(..., dest)` overload into a scratch |
| `getTickPush` (`:849`, `HelicopterEntity:56`) | `new Vector3f` per tick | reused `pushScratch` |
| `tickUpgrades` (`:668`) | `new ArrayList<>()` **every tick for every plane**, almost always empty | allocated lazily, only when an upgrade is actually removed |
| `RotationPacket` (`:625`) | sent **every tick** by every locally controlled plane, including a parked one with the engine off | skipped when the quaternion is unchanged within 1e-5/component (~0.001°). The server keeps the last value it received |

Not fixed, described only:

* `MathUtil.lerpQ` allocates 3–4 `Quaternionf` per call (two from normalising its inputs, which it
  must not mutate). Called once per tick in flight and once per tick per remote plane in `tickLerp`.
  Fixing it properly means an in-place API and touching every caller; low value.
* `getQ()` / `getQ_Client()` / `getQ_Prev()` still allocate where the result is *stored*
  (`setQ_prev(getQ_Client())`, `setQ(q)`, `sendRotation(getQ())`) — those copies are load-bearing,
  since the setters alias their argument.
* `tickMotion` wraps the push `Vector3f` in a `new Vec3(...)`; `Vec3` is immutable so this is
  unavoidable without restructuring the method.
* `markHurt()` — see **N7**.

---

## 5. Change list

### `misc/MathUtil.java`
1. `normalizeQuaternionf` returns the **identity** quaternion instead of `(0,0,0,0)` on degenerate
   input (**N1**), and uses `1/Math.sqrt` instead of `fastInvSqrt` (**N2**). Added an
   allocation-free `(q, dest)` overload; the single-argument form still returns a fresh instance
   because `PlaneEntity.tick()` hands its result straight to `setQ()`, which stores the reference.
2. `fastInvSqrt` kept but `@Deprecated` with the reason.
3. `normalizedDotProduct` guards against zero-length inputs (**N3**).
4. `rotationToVector(yaw, pitch, size)` drops the redundant sqrt/divide (**N5**).
5. `toEulerAngles(q, dest)` and `toQuaternionf(yaw, pitch, roll, dest)` overloads added; the original
   signatures delegate to them. Fixed the misleading axis comments in `toEulerAngles`.
6. Documented that `getHorizontalDistanceSqr` returns a *distance*, not a square, and is **not**
   interchangeable with `Vec3.horizontalDistanceSqr()` (**P1**).

### `entities/PlaneEntity.java`
7. New `TempMotionVars` fields `stallSpeedFactor` (0.55), `liftSaturationFactor` (1.3),
   `minPitchAuthority` (0.35), `minGroundSteering` (0.2). `liftFactor` deprecated but kept.
8. `getLiftRatio()` — quadratic, stall-floored, saturating lift curve (**B3**).
9. `getPitchAuthority()` — airspeed-scaled elevator clamp; **no-op at/above take-off speed, so it
   does not touch the ground roll or lift-off at all** — it only damps a stalled plane (**B4**).
10. `maxUpStep()` override — 0.6 blocks below 0.5 b/t horizontal speed, 0 above (**B1**).
11. `tickOnGround` (`:896`): thrust handling **reverted to upstream** — the `push /= 5` change was
    made on bad arithmetic and is gone (**B2**). Friction is still gated on real contact (**B6**).
12. `tickRoll` (`:799`): ground steering scaled by speed (**B5**).
13. Allocation/lookup work listed in §4.

### `entities/HelicopterEntity.java`
14. `getTickPush` reuses the shared `pushScratch` instead of allocating. **No physics change** — and
    the helicopter is unaffected by items 8–12 by construction: it overrides `tickRotateMotion`
    (returns `q`, no lift at all), `tickPitch`, `tickYaw`, `tickRoll` and `tickOnGround` (which
    replaces `push` wholesale after calling `super`). Vertical take-off is untouched.

### `entities/LargePlaneEntity.java`, `entities/CargoPlaneEntity.java`
15. **Not modified.** They inherit **B1** (step-up), **B3** (stall), **B4** (elevator authority
    below take-off speed), **B5** (ground steering) and **B6** (lift-off drag spike). Their ground
    roll was already bit-identical to upstream and remains so — `getGroundPitch()` is 0 for both, so
    the reverted **B2** branch never applied to them in the first place.

---

## 6. What to check in game

1. **Small plane, flat runway, throttle 5, no pitch input** — ground roll should be *unchanged from
   upstream*: ~38 ticks (1.9 s) to 0.3 b/t, terminal ground speed ~0.48 b/t. If it feels different,
   the **B2** revert did not land (**B2**).
2. **Rotation** — should be *unchanged from upstream* on the ground; **B4** only bites below
   take-off speed. What should feel different is the lift-off itself, via **B3** (**B3**/**B4**).
3. **Stall** — cut the throttle in level flight. The plane should mush below ~0.316 b/t (was
   0.285 b/t) and need a nose-down recovery. That 11 % shift is the *whole* of **B3**'s effect on
   handling; anything at or above 0.39 b/t is bit-identical to upstream. Verify it does not make
   normal landings unpleasant — `stallSpeedFactor`/`liftSaturationFactor` are the dials.
4. **Taxi over a slab / dirt path / farmland** — should roll over it instead of stopping dead
   (**B1**). Also verify that flying into a hillside at cruise speed **still** crashes.
5. **Parked steering** — turning on the spot should be slow but possible (**B5**).
6. **Helicopter** — vertical take-off, hover, translation, and the `MOVE_UP` behaviour should be
   completely unchanged.
7. **Large / cargo plane** — ground roll should feel unchanged; take-off and stall should follow the
   new curve.
8. **Multiplayer** — a parked plane should stop sending `RotationPacket`; check that a remote observer
   still sees a taxiing plane turn correctly (the packet resumes the instant the attitude moves).
