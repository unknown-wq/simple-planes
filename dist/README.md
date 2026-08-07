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
| sha256 | `71347ae2ee08a9c8fe526147f4f819085422c0db592a58f3174756102749380e` |

Install: drop the jar and Fabric API into the `mods/` folder of a Fabric 26.2 profile
or server.

## Changes in this build — flight physics and impact detection

- **Small planes could barely accelerate for takeoff.** Ground thrust was cut to a fifth
  whenever the nose sat above one degree, and a small plane parks at five degrees, so the
  penalty was permanently on: thrust balanced drag at three percent of the speed needed to
  fly. The penalty now scales with the nose angle.
- **Planes could leave the ground below their own stall speed** — lift saturated at 0.2
  blocks/tick while takeoff speed is 0.3. Lift is now quadratic in airspeed and the stall
  sits back at the takeoff speed.
- The elevator did nothing below takeoff speed and then engaged at its full rate; elevator
  and ground steering authority now scale with airspeed.
- Slabs, paths and farmland used to stop a takeoff run dead. Aircraft get a step height at
  taxi speed only, so flying into a slope still collides.
- **Impacts were never detected on a plane carrying a pilot.** The check compared speed
  before and after the move, but the server never applies the collision velocity response
  to a client-authoritative vehicle, so the difference was always zero and the damage term
  a constant −5.0 against a threshold of 5.0. The threshold was also set above the aircraft's
  own terminal speed. Impacts are now measured from real positions, cover vertical hits and
  entity collisions, and scale damage with lost speed and airframe mass.
- Rolling on touchdown no longer destroys the aircraft outright at any speed, and a nose-first
  dive into terrain is no longer free.
- Fixed `normalizeQuaternionf` returning a zero quaternion, which collapsed seat positions,
  the thrust vector and the landing-angle check.
- Fewer per-tick allocations and block lookups in the flight hot path.

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
