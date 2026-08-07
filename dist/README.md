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
| sha256 | `9edcd1dfe0776546ef85d3a22372b66fbff5b2bef8757a428259b533b893b4f0` |

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

Impacts are now measured as a single scalar — how much of the motion the aircraft asked for the
world refused to give it this tick — covering horizontal and vertical hits and entity
collisions, scaled by lost speed and airframe mass. Speed lost is speed lost, whichever axis
carried it. Landings are unaffected by construction: on touchdown the horizontal component is
not blocked, the aircraft keeps rolling, so only the small vertical part is measured.

Verified server-side without a pilot: the same dive that previously left the aircraft intact now
destroys it. The **player-ridden** case could not be tested here — there is no client in this
environment — so that half is reasoned, not measured.

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
standstill and sagging towards the ground while it does; it reaches about 2 blocks/tick and
goes in **3 blocks from the aimpoint** 400 blocks away. The terminal phase commands the
elevation angle to the target rather than tracking an altitude — tracking an altitude arrives
overhead still high and lands the aircraft 50-odd blocks beyond.

The strike tool now reports both ends of the flight, so nothing happens silently:

```
Strike #126 spawned at 301, 153, 701 (45 above ground), inbound to 300, 80, 300 - 400 blocks, bearing 180.
Strike #126 hit the target at 301, 83, 299 (3 blocks off).
```

A runway survey reports length, width, slope, designators, threshold elevations, roughness and
approach obstacles.

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
