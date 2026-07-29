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
| sha256 | `b2f083f06c3bc1be20bad73457748227c9cfcab35dd3c224025255ef4c1770dc` |

Install: drop the jar and Fabric API into the `mods/` folder of a Fabric 26.2 profile
or server.

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

**Server-side is verified** — a dedicated 26.2 server boots clean with this jar
(`Done (…)!`, zero `/ERROR]` lines). Note that a clean boot exercises loading and
registration only: the fixes listed above live in the entity tick and in packet handlers,
which run only once a plane is actually spawned and flown, and that has not been
play-tested. **The client is not verified**: it has never been
run, because the build environment has no display. Client rendering is known to compile
and nothing more, and several visual features were deliberately dropped during the port —
see the "Disabled content" log in `../26.2/PORT-STATUS.md` before reporting a visual bug.

Rebuild with:

```sh
../gradle/install.sh          # vendored Gradle 9.6.1
cd ../26.2
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle build --no-daemon
```
