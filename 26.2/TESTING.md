# Test environment for the 26.2 port

Everything below is installed in this container. The toolchain, the reference sources and the
headless server were each run end to end and verified; the only unverified item is the headless
client in §4, which is marked as such there.

---

## 1. Toolchain

| What | Where | Notes |
|---|---|---|
| JDK 25 | `/opt/jdk25` | required by Minecraft 26.2; the default `java` on PATH is 21 — always set `JAVA_HOME` |
| Gradle 9.6.1 | `/opt/gradle-9.6.1/bin/gradle` | the wrapper cannot self-download here |
| Gradle 8.14.3 | `/opt/gradle-8.14.3/bin/gradle` | only for the `1.21.1/` NeoForge sources |
| Dependency cache | `/root/.gradle/caches` | fully warm — `--offline` builds work |

### Build the mod

```sh
cd /home/user/simple-planes/26.2
JAVA_HOME=/opt/jdk25 /opt/gradle-9.6.1/bin/gradle build --no-daemon --offline
# -> build/libs/simpleplanes-26.2-5.3.7.jar
```

`--offline` is the fast path and is enough for a source change. Drop it only if a dependency
version changes.

---

## 2. Reference sources

| Source | Path | Contents |
|---|---|---|
| Minecraft 26.2 | `/opt/mc-src` | 7055 `.java`, deobfuscated, **client and server** (`net/minecraft/client/**` is there) |
| Fabric loader 0.19.3 | `/opt/ref-src/fabric-loader` | 183 `.java` |
| Fabric API 0.154.2+26.2 | `/home/user/testserver/mods/fabric-api-0.154.2+26.2.jar` | binary; the aggregate sources jar is empty upstream, per-module sources live under `net/fabricmc/fabric-api/fabric-api-*` on maven.fabricmc.net |
| Upstream mod (NeoForge 1.21.1) | `/home/user/simple-planes/1.21.1` | unmodified, for behaviour parity checks |

Claims about vanilla behaviour go in a document only after being read in `/opt/mc-src`. The
physics and collision documents in this directory follow that rule; keep it.

---

## 3. Headless dedicated server

Lives in `/home/user/testserver` (outside the repo on purpose — nothing there is committed).

```
testserver/
├── fabric-server-launch.jar     Fabric loader 0.19.3 server launcher for 26.2
├── mods/fabric-api-….jar        Fabric API 0.154.2+26.2
├── mods/simpleplanes-….jar      the mod under test — recopy after every build
├── start.sh  cmd.sh  stop.sh    control scripts
├── console.log                  full server output
└── world/                       superflat, creative, offline mode
```

`server.properties` is tuned for automated testing: superflat, creative, `online-mode=false`,
`spawn-monsters=false`, `max-tick-time=-1` (so a breakpoint or a slow tick never triggers the
watchdog), and **`pause-when-empty-seconds=0`** — without that last one the server stops ticking
60 s after the last player leaves and unmanned test aircraft freeze in mid-air.

### Run a test

```sh
cp /home/user/simple-planes/26.2/build/libs/simpleplanes-26.2-5.3.7.jar /home/user/testserver/mods/
cd /home/user/testserver
./start.sh                                  # blocks until "Done (…)", ~10 s
./cmd.sh "forceload add -180 -40 180 40"    # entities do not tick in unloaded chunks
./cmd.sh "autopilot strike 0 -59 0 150 90"
sleep 20
grep -E "Strike|Route" console.log
./stop.sh
```

`start.sh` keeps a FIFO open on the server's stdin, so `cmd.sh` can feed console commands to a
running server at any time; `console.log` is the transcript. Command output (`sendSuccess`) is
printed to the console, so assertions can be made by grepping the log.

### Why the autopilot is the test rig

`autopilot/` is a server-side flight director: it only moves throttle, pitch, yaw and roll, exactly
as a player would, and never sets position, velocity or rotation. So an autopilot flight exercises
the real `PlaneEntity` aerodynamics and the real collision path, reproducibly, with no client.

Useful commands (all `/autopilot …`, console works, permission level 2):

| Command | Use |
|---|---|
| `strike <x y z> [distance] [bearing]` | spawns a plane `distance` blocks out and flies an attack run onto the target — the impact test |
| `route <from> <to>` | point-to-point cruise — the "does it explode for no reason" test |
| `survey <t1> <t2>` / `airfields` | register and list runways |
| `status` | live list of autopilot aircraft with a status line each |
| `stop` | stop all of them |

Both flights print a terminal line (`hit the target at …`, `flew into terrain at …`, …), which is
what makes them assertable from a shell.

### The one thing this rig cannot see

An autopilot plane has **no rider**, so on the server `isClientAuthoritative()` is false and
`canSimulateMovement()` is true — the server simulates it fully. A plane flown by a *player* is
client-authoritative: the client runs the physics and the server only receives positions. That
split is the root cause documented in `COLLISION-DIAGNOSIS.md`, and it is exactly the branch the
autopilot does **not** exercise. Anything about the ridden case has to be reasoned out against
`/opt/mc-src` (`Entity#isLocalInstanceAuthoritative`, `Entity#move`,
`ServerGamePacketListenerImpl#handleMoveVehicle`) or tested with a real client — see below.

---

## 4. Client

`gradle runClient` is available (Loom is configured). The container has no GPU, so the software
Vulkan driver `mesa-vulkan-drivers` (lavapipe, `/usr/share/vulkan/icd.d/lvp_icd.json`) and `xvfb`
are installed:

```sh
cd /home/user/simple-planes/26.2
XDG_RUNTIME_DIR=/tmp/xdg JAVA_HOME=/opt/jdk25 xvfb-run -a /opt/gradle-9.6.1/bin/gradle runClient --no-daemon
```

**Not verified end to end.** The prerequisites are all in place — the Loom `runClient` task exists,
xvfb and lavapipe are installed, and the run downloads assets normally — but the launch was stopped
partway through the ~500 MB first-run asset download, so no client has actually reached a title
screen here. Expect it to be slow (software rasterisation). It is a last resort for the
client-authoritative path, not the everyday loop.

Note that a `--no-daemon` Gradle run holds locks on `/root/.gradle/caches`: do not leave a client
running while building.
