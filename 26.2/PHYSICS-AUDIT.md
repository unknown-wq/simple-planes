# PHYSICS-AUDIT — Simple Planes flight model (26.2 port)

Scope: `entities/PlaneEntity.java`, `entities/LargePlaneEntity.java`, `entities/CargoPlaneEntity.java`,
`entities/HelicopterEntity.java`, `misc/MathUtil.java`.

All vanilla behaviour cited below was verified against the decompiled 26.2 sources in `/opt/mc-src`,
not from memory. All line numbers refer to the **post-change** files unless marked "(before)".

Out of scope, deliberately untouched (other agents own them): the collision block
`PlaneEntity.java:577..602` (`horizontalCollision` / `crash()`), `hurtServer`, `checkFallDamage`,
`causeFallDamage`. Problems found there are listed but not fixed.

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
frame is mirrored relative to the physics frame) and is used for seat positions, thrust direction and
the landing-attitude test.

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
speed, which is the root of issue **B2**.

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
`atan(0.03 / speed)` degrees per tick — **5.7°/tick at speed 0.3, 1.7°/tick at speed 1.0**. Equilibrium
(level flight) is where `0.2·Δ·d + lift == atan(0.03/v)`. Because `0.2·Δ·d` is maximised at
`Δ = 60/√3 ≈ 34.6°` with value **4.62°/tick**, there is a hard aerodynamic ceiling: the plane cannot
sustain level flight below roughly `v = 0.03 / tan(4.62° + lift)`. That is the implicit stall speed,
and tuning `lift` is how you place it. See issue **B3**.

Nothing in the model has mass, wing area, air density, or a lift/drag polar. `maxSpeed` is a hard
speed clamp, not a thrust/drag equilibrium.

---

## 2. Constant table

`TempMotionVars.reset()` — `PlaneEntity.java:1596`. "b/t" = blocks per tick (×20 = blocks/s).

| Constant | Value | Where used | Effect | Reasonable? |
|---|---|---|---|---|
| `maxSpeed` | 3 | `tickMotion` | hard clamp on `|v|`, lerped at 0.2 | Yes — 60 b/s is never reached in practice |
| `maxPushSpeed` | `getMaxSpeed()*10` = 10 | `tickMotion` push scaling | how fast thrust stops helping | Fine |
| `takeOffSpeed` | 0.3 | `tickOnGround` (elevator gate, rest attitude), now also lift/authority scaling | minimum flying speed | Yes; now consistently the stall speed too |
| `maxLift` | 2 (°/tick) | `tickRotateMotion` | extra upward bias on the velocity vector | Yes |
| `liftFactor` | 10 | **now unused** (deprecated) | old lift slope; saturated at v=0.2 | No — see **B3** |
| `stallSpeedFactor` | 0.55 *(new)* | `getLiftRatio` | stall at `0.55 × 0.3 = 0.165` b/t | New |
| `liftSaturationFactor` | 1.3 *(new)* | `getLiftRatio` | full lift at `1.3 × 0.3 = 0.39` b/t | New |
| `gravity` | −0.03 | `tickMotion` | 0.6 b/s² — 2.7× weaker than vanilla's 0.08 | Arcade, but internally consistent |
| `drag` | 0.001 | `tickMotion` | constant speed loss; sets the ~0.01 b/t creep floor | Borderline — it is what made **B2** fatal |
| `dragMul` | 0.0005, **×48 on the ground** | `tickMotion` | linear drag; `20*(3−f)`, f=0.6 normal, 0.98 ice | Value fine, but the 48× step at lift-off is a discontinuity (**B4**) |
| `dragQuad` | 0.001 | `tickMotion` | quadratic drag; only matters above v≈1 | Yes |
| `push` | `0.00625 × throttle` | `tickMotion` via `getTickPush` | thrust. 0.03125 at throttle 5, 0.0625 with booster (throttle 10) | Yes |
| `groundPush` | 0.01 | `tickOnGround` | thrust floor while holding pitch-up/down on the ground | Yes |
| `passiveEnginePush` | 0.025 | **never read anywhere** | dead constant (also dead in 1.21.1) | Dead code |
| `motionToRotation` | 0.05 | `tickRotateMotion` | how fast the attitude follows the velocity (weathervane) | Yes |
| `pitchToMotion` | 0.2 | `tickRotateMotion` | how fast the velocity follows the attitude — **the actual lift term** | Yes |
| `yawMultiplayer` | 0.5 | **never read anywhere** | dead constant | Dead code |
| `turnThreshold` | `TURN_THRESHOLD/100` = 0.2 | `tick()` | strafe deadzone | Yes |
| `groundRollSpeed` | 0.1 *(new)* | `tickOnGround` | speed under which static rolling resistance applies | Preserves the original 0.1 threshold |
| `lowSpeedThrustFactor` | 0.5 *(new)* | `tickOnGround` | thrust surviving rolling resistance (was an effective 0.2) | New |
| `minPitchAuthority` | 0.35 *(new)* | `getPitchAuthority` | elevator floor far below take-off speed | New |
| `minGroundSteering` | 0.2 *(new)* | `tickRoll` | nose-wheel floor at a standstill | New |
| pitch rate ramp / clamp | ±0.5 / ±5.0 °/tick | `tickPitch` | ×`getRotationSpeedMultiplier()` | Yes |
| yaw rate ramp / clamp | ±0.5 / ±2.5 °/tick | `tickYaw` | ×`getRotationSpeedMultiplier()` | Yes |
| roll rate ramp / clamp | ±0.5 / ±5.0 °/tick | `tickRoll` | **not** scaled by `getRotationSpeedMultiplier()` | Inconsistent (**M5**) |
| ground steering | ±3 °/tick | `tickRoll` | now speed-scaled | Was unconditional |
| `getRotationSpeedMultiplier()` | 1.0 / 0.5 / 0.2 | Plane / Large+Heli / Cargo | mass proxy | Yes |
| `getGroundPitch()` | 5 / 0 / 0 | Plane / Large / Cargo+Heli | resting nose-up attitude | 5° is what triggered **B2** |
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

#### B2 — the small plane could not reach take-off speed at all *(FIXED)*
`PlaneEntity.java:896..913` (was `if (degreesDifferenceAbs(getXRot(), 0) > 1 && |v| < 0.1) push /= 5;`).

Arithmetic, at throttle 5 on grass (`f = 0.6` → `dragMul = 0.0005 × 20 × 2.4 = 0.024`):

* `getGroundPitch()` returns **5** for `PlaneEntity`, and `tickOnGround` lerps `xRot` toward it, so
  `degreesDifferenceAbs(xRot, 0) > 1` is **permanently true** at rest.
* thrust therefore becomes `0.03125 / 5 = 0.00625`.
* `tickMotion` drag at speed `v` is `0.001·v² + 0.024·v + 0.001`.
* Equilibrium: `0.00625 = 0.024·v + 0.001` → **v = 0.0104 b/t**, and the `< 0.1` guard never releases.

So without holding pitch-up the small plane **converges to 0.0104 b/t and stays there forever** —
3 % of the 0.3 b/t take-off speed. Holding pitch-up rescues it only because the `getPitchUp() > 0`
branch further down force-feeds `push = groundPush = 0.01`, and because the pitch target flips to 0°
so the `> 1°` guard eventually releases. That is the "sticky" ground roll, and it is why take-off felt
like it required a secret handshake. `LargePlaneEntity`/`CargoPlaneEntity` park at 0° and were never
affected, which is why only the starter plane felt broken.

Fix: replace the flat `/5` with a physical thrust-alignment model —
`push *= lowSpeedThrustFactor (0.5) × max(0.25, cos(noseAngle))`. At the 5° resting attitude that is
`0.5 × 0.996 = 0.498` instead of `0.2`, giving `push = 0.01556` and a ground-roll equilibrium of
**0.607 b/t**, comfortably above the 0.3 b/t take-off speed. A genuinely nose-high wreck (60°+) still
only gets `0.5 × 0.5 = 0.25` of its thrust. The `noseAngle > 1` guard is kept so Large/Cargo behaviour
is bit-identical to before.

#### B3 — lift saturated *below* the take-off speed *(FIXED)*
`PlaneEntity.java:1026` (was `lift = min(speed * liftFactor, maxLift) * d`, `liftFactor = 10`).

`speed × 10` reaches `maxLift = 2` at **speed 0.2** — a third *below* the 0.3 b/t take-off speed. So
a plane crawling at 0.2 b/t had exactly the same wing authority as one at cruise. Combined with
`pitchToMotion` dragging the velocity vector toward the nose at 0.2/tick, this is the mechanism behind
"взлетел на нулевой": get to 0.2 b/t, pull up, and the velocity follows the nose with full lift behind
it. Real lift is quadratic in airspeed with a hard floor at the stall speed; this was linear with an
early ceiling and no floor at all.

Using the equilibrium condition from §1.5, the *old* stall speed worked out to ≈ **0.24 b/t**, i.e.
20 % below the nominal take-off speed — so the take-off speed was never actually the minimum flying
speed.

Fix: `getLiftRatio()` — zero below `takeOffSpeed × 0.55` (0.165 b/t), rising with `v²`, saturating at
`takeOffSpeed × 1.3` (0.39 b/t). New numbers:

| speed | old lift | new lift | gravity tilt needed | verdict |
|---|---|---|---|---|
| 0.20 | 2.00 | 0.09 | 8.53 °/t | stalls (was: also stalled, barely) |
| 0.25 | 2.00 | 0.35 | 6.84 °/t | stalls |
| 0.30 | 2.00 | 1.01 | 5.71 °/t | marginal — exactly at take-off speed |
| 0.35 | 2.00 | 1.42 | 4.90 °/t | flies |
| ≥0.45 | 2.00 | 2.00 | ≤3.81 °/t | **unchanged from before** |

Stall speed moves from ≈0.24 to ≈0.31 b/t, i.e. it now coincides with `takeOffSpeed`. Cruise flight
(anything above 0.45 b/t, which is everything at throttle ≥ 1) is **numerically identical** to the old
model. "Не добрал скорость — не взлетел" now actually holds.

#### B4 — the elevator went from "disabled" to "full authority" in one tick *(FIXED)*
`PlaneEntity.java:754..757` (`tickPitch` clamp).

`tickOnGround()` returns `speedingUp = false` below `takeOffSpeed`, and `tick()` skips `tickPitch()`
entirely in that case. The instant the roll crosses 0.3 b/t, `tickPitch` starts and ramps `pitchSpeed`
by 0.5°/tick to a **5°/tick** ceiling — 100°/s. Two seconds later the nose is vertical. With
`pitchToMotion` pulling the velocity along, that is the "instant jump upward".

Fix: `getPitchAuthority()` scales the `pitchSpeed` clamp by `clamp(speed / takeOffSpeed, 0.35, 1)`.
**At and above the take-off speed the factor is exactly 1 and the clamp is identical to before**; only
the slow/stalled regime is damped, where reduced elevator authority is the physically correct answer
(and forces the pilot to nose down to recover from a stall instead of pulling harder).

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
transition timing, and it also means the crash gate `onGroundTicks <= 0` is open for ~3 ticks out of
every 7 while taxiing.

**Not fixed on purpose**: latching it to 5 while in contact would close the crash gate permanently on
the ground, which is a behavioural change inside the collision block another agent owns. Suggested fix
if that agent wants it: `if (onGround()) onGroundTicks = 5; else onGroundTicks--;` plus a separate
explicit flag for the crash gate.

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
   `groundRollSpeed` (0.1), `lowSpeedThrustFactor` (0.5), `minPitchAuthority` (0.35),
   `minGroundSteering` (0.2). `liftFactor` deprecated but kept.
8. `getLiftRatio()` — quadratic, stall-floored, saturating lift curve (**B3**).
9. `getPitchAuthority()` — airspeed-scaled elevator clamp, no-op at/above take-off speed (**B4**).
10. `maxUpStep()` override — 0.6 blocks below 0.5 b/t horizontal speed, 0 above (**B1**).
11. `tickOnGround` (`:896`): flat `push /= 5` → `lowSpeedThrustFactor × max(0.25, cos(noseAngle))` (**B2**);
    friction gated on real contact (**B6**).
12. `tickRoll` (`:799`): ground steering scaled by speed (**B5**).
13. Allocation/lookup work listed in §4.

### `entities/HelicopterEntity.java`
14. `getTickPush` reuses the shared `pushScratch` instead of allocating. **No physics change** — and
    the helicopter is unaffected by items 8–12 by construction: it overrides `tickRotateMotion`
    (returns `q`, no lift at all), `tickPitch`, `tickYaw`, `tickRoll` and `tickOnGround` (which
    replaces `push` wholesale after calling `super`), and inherits `getGroundPitch() == 0` from
    `LargePlaneEntity` so the nose-angle thrust term is never even entered. Vertical take-off is
    untouched.

### `entities/LargePlaneEntity.java`, `entities/CargoPlaneEntity.java`
15. **Not modified.** They inherit the fixes. Because `getGroundPitch()` is 0 for both, item 11's
    `noseAngle > 1` guard means their ground roll is bit-identical to before; they gain **B1**
    (step-up), **B3** (stall), **B4** (elevator authority), **B5** (steering) and **B6** (lift-off
    drag spike) unchanged in form.

---

## 6. What to check in game

1. **Small plane, flat runway, throttle 5, no pitch input** — should now accelerate from rest to
   0.3 b/t in roughly a second of runway and keep accelerating toward ~0.6 b/t. Before this change it
   stuck at ~0.01 b/t forever (**B2**).
2. **Rotation** — pull up at take-off speed; the nose should rise progressively rather than snapping
   to 45° in a second (**B4**).
3. **Stall** — cut the throttle in level flight. The plane should mush and drop below ~0.31 b/t and
   need a nose-down recovery. Verify this does not make normal landings unpleasant (**B3** is the most
   behaviour-changing item here; `stallSpeedFactor`/`liftSaturationFactor` are the dials).
4. **Taxi over a slab / dirt path / farmland** — should roll over it instead of stopping dead
   (**B1**). Also verify that flying into a hillside at cruise speed **still** crashes.
5. **Parked steering** — turning on the spot should be slow but possible (**B5**).
6. **Helicopter** — vertical take-off, hover, translation, and the `MOVE_UP` behaviour should be
   completely unchanged.
7. **Large / cargo plane** — ground roll should feel unchanged; take-off and stall should follow the
   new curve.
8. **Multiplayer** — a parked plane should stop sending `RotationPacket`; check that a remote observer
   still sees a taxiing plane turn correctly (the packet resumes the instant the attitude moves).
