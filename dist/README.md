# dist

Compiled build of the Fabric / Minecraft 26.2 port (source in `../26.2/`).

The jar below is a **finished, ready-to-install build** — you do not need to compile anything to
play, just drop it into `mods/` (see below). The sources it was built from live in `../26.2/`.

| File | `simpleplanes-26.2-5.3.7.jar` |
|---|---|
| Minecraft | 26.2 |
| Loader | Fabric, loader ≥ 0.19.3 |
| Java | 25 |
| Requires | Fabric API 0.154.2+26.2 or newer |
| sha256 | `e78a282a85f28c77b95a3b971385c3b72a17777a3ec9eac1440ce94ecb6141e5` |

Install: drop the jar and Fabric API into the `mods/` folder of a Fabric 26.2 profile
or server.

## Changes in this build

### Impact detection

**Collisions were never detected on a plane carrying a pilot** — not intermittently, never.
The check compared horizontal speed before and after the move and required the difference to
exceed 1.0 blocks/tick, but `Entity.move()` only zeroes the blocked axes of the velocity
inside `restituteMovementAfterCollisions`, and that call is gated on `canSimulateMovement()`.
On the server that resolves to `!isClientAuthoritative()`, and `Player.isClientAuthoritative()`
returns `true` unconditionally, so a ridden plane never has its velocity corrected server-side.
The difference was therefore always zero and the damage term a constant −5.0 against a
threshold of 5.0. The threshold was unreachable regardless: terminal speed is 0.763
blocks/tick, so a head-on hit at full throttle would have produced 2.6.

Vertical impacts had no handling at all. The only path was `causeFallDamage`, which destroys
the plane solely when roll exceeds 45 degrees — so a nose-first dive into a hillside with
level wings did nothing, while a wingtip scrape on landing exploded the plane at any speed,
since `crash()` ignored its damage argument. That asymmetry is why detection felt random.

A first fix measured the impact per axis and still missed: reproduced on a server, an aircraft
diving into a hillside at 1.35 blocks/tick decelerated to 0.08 and was found **intact** on the
ground. Its descent component was only 0.29, under the wings-level vertical tolerance, while the
horizontal test never fired because an aircraft that ends up inside terrain ploughs to a halt
over several ticks without the engine ever setting `horizontalCollision`.

That scalar measure had two faults of its own, both found on the test rig and fixed in this
build:

- **It measured only the part of the tick the wall cut off**, and where the obstacle falls
  within a tick's travel is effectively random — so the reading was a lottery between 0 and the
  real speed. Measured: a head-on into a vertical wall at 2.99 blocks/tick registered 0.555 and
  dealt 5.7 damage; the aircraft survived and stayed pinned against the wall. The same dive into
  the ground read 0.054 on one flight (intact) and 1.438 on another (destroyed). An impact is
  now charged the **full component of the tick's motion on every axis the world blocked** — the
  velocity that actually got destroyed, since the collision response zeroes those axes right
  after. Same wall, same approach, after the fix: impact 2.476, damage 122, aircraft destroyed
  at the wall. Resting, taxiing and gentle landings stay free by the same measure (a parked
  airframe shows contact of ~0.09 against a tolerance of 0.6; a two-block drop lands at 0.32).
- **A "speed dropped too fast" fallback could destroy a ridden aircraft in clean air.** It
  compared consecutive `getKnownMovement()` samples, but for a player-ridden vehicle that value
  is per-packet client displacement: `handleClientTickEnd` zeroes it on any server tick where
  the movement packet did not arrive, and the catch-up packet then carries two ticks of
  displacement. One late packet at cruise reads as a 0.76 blocks/tick deceleration — 11 damage,
  instant mid-air destruction, nothing hit. The fallback is deleted; every charge now requires
  the world to have geometrically blocked the aircraft's motion that tick, and the remaining
  consumers of `getKnownMovement()` (wing scrapes, ramming) take the min of two adjacent
  samples so a single packet hiccup cannot inflate them.

Verified server-side without a pilot (autopilot flights with per-tick instrumentation): full
climb/cruise/dive profiles produce zero impact charges in clean air, and every terrain contact
charge in the logs matches a real wall, crater or stall pancake. The **player-ridden** packet
path could not be driven here — no client in this environment — so that half is reasoned
against the decompiled 26.2 networking code, not measured.

### Flight physics

- Fixed `normalizeQuaternionf` returning a zero quaternion on a zero-length input, which
  collapsed seat positions, the thrust vector and the landing-angle check — and was written
  back into the entity.
- Slabs, paths and farmland stopped a takeoff run dead, because `Entity.maxUpStep()` returns
  `0.0F` and step-up is gated on it being positive; the `setOnGround(true)` call before the
  move never did anything. Aircraft now get a step height at taxi speed only, so flying into a
  slope still collides and still crashes.
- Ground drag applied for four ticks after liftoff, producing a braking jolt exactly at the
  ground-to-air transition; it now applies only on real contact.
- Lift is now quadratic in airspeed rather than saturating at two thirds of takeoff speed, and
  the elevator is damped below takeoff speed instead of being inert and then fully effective.
  This changes the shape of the curve near the stall; cruise flight is numerically unchanged.
- Fewer per-tick allocations and block lookups in the flight hot path — `isOnWater()` alone
  read the same block state up to five times per tick.

An earlier revision of this changelog claimed the ground roll was broken and that small planes
could not reach takeoff speed. **That was wrong** — the arithmetic behind it divided the thrust
by five twice. Simulating the real tick shows the original ground roll reaches takeoff speed in
38 ticks at full throttle. That change has been reverted and the claim retracted; see issue B2
in the audit.

### Autopilot and route tools (new, partly verified)

A server-side flight director with strike, route and runway-survey tools, plus
`/autopilot strike|route|survey|airfields|status|stop`. See
[`../26.2/AUTOPILOT.md`](../26.2/AUTOPILOT.md).

Verified on a dedicated 26.2 server. A strike is launched with a booster fitted, the throttle
open and already at attack speed pointed at the target, rather than accelerating from a
standstill and sagging towards the ground while it does. It goes in **3-6 blocks from the
aimpoint** 400 blocks away.

Three things about the attack run changed in this build, all from the same report — the aircraft
slowed down, hit a tree without breaking, and started down far too early:

- **It no longer loses speed.** The speed ceiling is not a limiter but the point where
  `tickMotion` fades the thrust out, so raising it moves the balance against the drag curve.
  Measured over a 400-block run the speed now rises monotonically 2.30 → 3.13 blocks/tick.
- **The run-in is flown 100 blocks above the ground**, not 35 above the target. This is what
  actually fixes the aircraft parked in a tree: a glancing hit on a canopy blocks only the small
  vertical part of the motion, so the impact registers as a gentle landing — which is what it
  physically is — and the aircraft settles into the branches undamaged. Nothing on the way in
  reaches 100 blocks up. Belt and braces, a strike aircraft now also detonates wherever it stops,
  so a run that clips something can no longer leave an intact airframe in the scenery.
- **The dive starts late and steepens.** It used to begin at a fixed 350 blocks out, which from a
  35-block run-in is a 6-degree glide starting almost immediately after launch. The run-in height
  is now held until the target is 32 degrees below the nose — about 180 blocks out — and the
  terminal phase then aims the nose straight at the target, so the commanded angle is
  `atan(height / distance)`: near-constant through the dive and steepening towards vertical over
  the last few blocks. Measured pitch through one run: −20°, −40°, −47°, −52°, −63°, −76°.

The terminal phase commands that elevation angle rather than tracking an altitude because
tracking an altitude arrives overhead still high and lands the aircraft 50-odd blocks beyond.
The fuse radius scales with speed and is backed by closest-point-of-approach detection — at 3
blocks/tick a fixed 3-block sphere can be stepped straight over between two ticks.

The strike tool now reports both ends of the flight, so nothing happens silently:

```
Strike #248 spawned at 301, 208, 701 (100 above ground), inbound to 300, 80, 300 - 400 blocks, bearing 180.
Strike #248 hit the target at 300, 80, 294 (6 blocks off).
```

A runway survey reports length, width, slope, designators, threshold elevations, roughness and
approach obstacles.

**Route flights are partial.** Route aircraft now launch at flying speed (they used to spawn
with zero airspeed and fall), and straight cruise legs fly clean, but the 180-degree turnback
between legs and the improvised-landing descent can still bleed speed into an unrecovered stall
and pancake in — a real impact, correctly charged by the detector, but a flying-quality bug in
the flight director. Landings, go-arounds and holding patterns are consequently untested.
Helicopters are out of scope.

Details: [`../26.2/PHYSICS-AUDIT.md`](../26.2/PHYSICS-AUDIT.md) and
[`../26.2/COLLISION-DIAGNOSIS.md`](../26.2/COLLISION-DIAGNOSIS.md).

## Changes since the first 26.2 build

- `TempMotionVars` is per-entity instead of a single shared `static`, which was a data race
  between concurrently ticking planes.
- `RotationPacket`, `ChangeThrottlePacket` and `DropPayloadPacket` now require the sender to
  be the controlling passenger, and rotations with non-finite or zero-length components are
  rejected instead of being written into the entity.
- Six block lookups used a truncating `(int)` cast instead of a floor and sampled the wrong
  block at negative coordinates — ground friction, water detection and the parachute ground
  check were all affected for `x` or `z < 0`.
- Less quaternion allocation in the tick and render hot paths.

**This build is tested** — it runs on a Minecraft 26.2 Fabric client and on a dedicated
26.2 server (`Done (…)!`, zero `/ERROR]` lines). Some features were deliberately dropped
during the port — removed mod compat, a non-editable config, and a few visuals that are not
rendered; see the "Disabled content" log in `../26.2/PORT-STATUS.md` before reporting one of
those as a bug. Anything else that misbehaves is a bug worth an issue:
<https://github.com/unknown-wq/simple-planes/issues/new>

Rebuild with:

```sh
../gradle/install.sh          # vendored Gradle 9.6.1
cd ../26.2
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle build --no-daemon
```
