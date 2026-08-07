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
| sha256 | `879ea3347a7a529a97c13f63b82eab64165eb34adbf2fe1dbd2910b4639c27c9` |

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

Impacts are now measured from actual positions and `getKnownMovement()`, cover horizontal and
vertical hits and entity collisions, and scale damage with lost speed and airframe mass, with
a tolerance band so ordinary landings and taxiing stay harmless.

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

Verified on a dedicated 26.2 server: a strike flight spawns 400 blocks out, accelerates to
1.27 blocks/tick under its own physics and hits its target; a runway survey reports length,
width, slope, designators, threshold elevations, roughness and approach obstacles.

**Not yet working: route flights.** The aircraft spawns on the ground, never enters the takeoff
phase and sinks into terrain instead of climbing to its cruise altitude. Landings, go-arounds
and holding patterns are consequently untested. Helicopters are out of scope.

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
