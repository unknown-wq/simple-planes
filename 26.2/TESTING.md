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
./cmd.sh "autopilot strike 0 -59 0 800 90"
sleep 25
grep -E "Strike|Plane #" console.log
./stop.sh
```

**No `forceload` is needed for a flight**, and adding one mostly hides bugs. Autopilot aircraft carry
their own rolling chunk tickets and are renewed from the server tick (see `AUTOPILOT.md`, "Chunk
loading"), so an 800-block strike and a 2000-block airfield-to-airfield sortie both complete with
none. Two things to know if you reach for it anyway:

* `forceload add` refuses more than **256 chunks per command** — `forceload add -400 -400 400 400` is
  2601 chunks and simply prints `Too many chunks in the specified area` and does nothing. An earlier
  version of this file recommended a command in that shape, so tests that "passed with forceload"
  were in fact running with none.
* Force-loading a corridor and then flying out of it is a good way to *reproduce* the chunk bug
  rather than avoid it: the aircraft freezes at the boundary, keeping its velocity exactly.

**Kill leftovers between runs.** `./cmd.sh "autopilot stop"` stops the flight directors but leaves
the aircraft in the world, and test flights all use the same corridor — a parked hulk on the run-in
line will be rammed by the next one, which registers as a plane-to-plane collision and ends the run
early with a confusing report. Use `./cmd.sh "kill @e[type=simpleplanes:plane]"`.

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
| `flight <from> <to>` | full sortie between two registered airfields — taxi, take-off, cruise, approach, landing |
| `inbound <x y z> <airfield>` | one-way arrival into a named airfield — the landing test, without the departure |
| `survey <t1> <t2>` / `airfields` | register and list runways |
| `tower [<airfield>]` | runway states — free/occupied, by which aircraft, in what mode, for how long, and who is holding |
| `status` | live list of autopilot aircraft with a status line each |
| `stop` | stop all of them |

### Recipe: a complete airfield-to-airfield sortie

`survey` is the one subcommand that does require loaded chunks (it measures real blocks), so it is
also the only place `forceload` is genuinely useful. Two runways 2000 blocks apart on the superflat:

```sh
./cmd.sh "forceload add 640 -200 670 0"      # 28 chunks - under the 256 limit
./cmd.sh "forceload add 2640 -200 2670 0"
sleep 8
./cmd.sh "autopilot survey 654 -60 -9 654 -60 -192"
./cmd.sh "autopilot survey 2654 -60 -9 2654 -60 -192"
./cmd.sh "autopilot airfields"

./cmd.sh "forceload remove all"               # prove the flight loads its own chunks
./cmd.sh 'autopilot flight "airfield-1" "airfield-2"'
```

Airfields persist in `SavedData`, so the survey only has to be done once per world. The sortie takes
about four minutes of wall clock; poll `./cmd.sh "autopilot status"` to watch it, and assert on the
final line:

```
Plane #7 landed at airfield-2/36, 2655, -60, -12 (4 blocks down the runway).
```

Every terminal event now goes through `AutopilotFeedback.report`, which logs to the console when
there is no owning player — landings, go-arounds (with the reason), runway switches and
"came down at". Progress chatter still uses `overlay`, which no-ops headlessly. If you add a new
end-of-flight path, use `report`, or it will be invisible on this rig.

### Recipe: water impact

Water has no collision shape, so it needs its own test. Build a basin, then summon planes into it
with a known entry velocity — `Motion` in the summon NBT is the cleanest way to control the entry
speed, and spawning right at the waterline stops gravity adding to it:

```sh
./cmd.sh "forceload add 690 690 740 740"
./cmd.sh "fill 700 -64 700 735 -60 735 minecraft:water"
./cmd.sh 'summon simpleplanes:plane 705 -59.0 705 {upgrades:{"simpleplanes:floaty_bedding":{}},Motion:[0.0,-1.0,0.0],Tags:["wt"]}'
sleep 5
./cmd.sh "execute as @e[tag=wt] run data get entity @s health"
```

Health is an int, default 10. A plane that no longer answers `data get` was destroyed. Measured
boundary with Floaty Bedding and wings level: free to 0.70 b/t, 1 HP at 0.75, 5 HP at 1.00,
destroyed from 1.2. See `COLLISION-DIAGNOSIS.md`, section Р3.

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
