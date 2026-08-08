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
| `strike <x y z> [distance] [bearing] [blast] [blocks] [fire]` | spawns a plane `distance` blocks out and flies an attack run onto the target — the impact test. The last three set the warhead: strength 0–16 (default 4), whether it breaks blocks, whether it sets fire |
| `route <from> <to> [speed]` | point-to-point cruise — the "does it explode for no reason" test, and the speed-regulation test |
| `flight <from> <to> [speed] [delay <s>]` | full sortie between two registered airfields — park, wait, taxi, take-off, cruise, approach, landing. `delay` is seconds spent on the parking spot before the runway is asked for |
| `inbound <x y z> <airfield> [speed]` | one-way arrival into a named airfield — the landing test, without the departure |
| `survey <t1> <t2>` | register a runway |
| `airfields [info\|show\|rename\|remove\|park\|unpark]` | browse and manage them; every form works headlessly |
| `tower [<airfield>]` | runway states — free/occupied, by which aircraft, in what mode, for how long, and who is holding |
| `status` | live list of autopilot aircraft with a status line each |
| `stop` | stop all of them |

`speed` is the cruise speed in blocks per tick, clamped to 0.40–2.80. Omitted, it is the default
2.60. It is the single most useful argument on this rig: the same flight at 0.40 and at 2.80
exercises completely different parts of the controller.

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

Airfields persist in `SavedData`, so the survey only has to be done once per world. A 2000-block
sortie takes about **two minutes** of wall clock at the 2.60 default (it was nearer four at the old
0.80, and adding `0.80` as a trailing argument still gets you that); poll
`./cmd.sh "autopilot status"` to watch it, and assert on the final line:

```
Plane #7 landed at airfield-2/36, 2655, -60, -12 (4 blocks down the runway).
```

Every terminal event now goes through `AutopilotFeedback.report`, which logs to the console when
there is no owning player — landings, go-arounds (with the reason), runway switches and
"came down at". Progress chatter still uses `overlay`, which no-ops headlessly. If you add a new
end-of-flight path, use `report`, or it will be invisible on this rig.

### Recipe: measuring an explosion

Blast strength needs a number, not an impression. The trick is that `fill … replace` reports how
many blocks it changed, so **refilling the crater counts it**. The superflat has exactly three
destructible layers — bedrock at −64, dirt at −63/−62, grass at −61 — so a box over `-63 … -61`
captures the whole crater, and 81×81×3 = 19 683 blocks stays under the 32 768-per-`fill` limit.

```sh
./cmd.sh "forceload add -2040 -2040 -1960 -1960"      # x1 z1 x2 z2 - easy to transpose, check it
sleep 4
./cmd.sh "autopilot strike -2000 -61 -2000 200 0"                    # default warhead
sleep 22
./cmd.sh "fill -2040 -63 -2040 -1960 -61 -1960 minecraft:glass replace minecraft:air"
# -> "Successfully filled 89 block(s)"  == 89 blocks destroyed
```

Count fires the same way, one layer higher, before counting blocks (fire sits on top of the ground):

```sh
./cmd.sh "autopilot strike -2000 -61 -2800 200 0 8.0 false true"     # no block damage, incendiary
./cmd.sh "fill -2040 -60 -2840 -1960 -60 -2760 minecraft:air replace minecraft:fire"
# -> 145                      fires placed
./cmd.sh "fill -2040 -63 -2840 -1960 -61 -2760 minecraft:glass replace minecraft:air"
# -> "No blocks were filled"  nothing destroyed, which is the point of blocks=false
```

Use a fresh site per shot — craters must not overlap — and `tick query` for the cost: it prints the
average and the P50/P95/P99 over the last 100 ticks, which is how the 16.0 ceiling was shown to be
affordable (1.3 ms average, 6.1 ms P99, against a 50 ms budget).

### Recipe: proving something survives a save

`autopilot status` after a restart is **not** proof: a plane in a chunk nobody loads is not ticking
and not in the entity list, so a perfectly good save looks like a lost aircraft. Two things are
needed — the aircraft has to be inside a force-loaded region *at shutdown*, and it has to still be
there when you look.

The trap is that it keeps flying. A routed aircraft covers hundreds of blocks between the `save-all`
and the `stop`, and leaves any corridor you force-loaded around it. **`tick freeze` pins it**, which
makes the whole thing deterministic:

```sh
./cmd.sh "autopilot route -2000 -60 -2000 -1000 -60 -2000"
sleep 6
./cmd.sh "tick freeze"
./cmd.sh 'execute at @e[type=simpleplanes:plane,limit=1] run forceload add ~-32 ~-32 ~32 ~32'
./cmd.sh "save-all flush"
./stop.sh && ./start.sh
./cmd.sh "data get entity @e[type=simpleplanes:plane,limit=1] autopilot.plan.blast"
# -> Plane has the following entity data: {breaks_blocks: 0b, fire: 1b, power: 12.0f}
```

`data get entity … autopilot` is also the quick way to see a flight plan without a restart at all —
it runs the same `addAdditionalSaveData` path — and `data merge entity` runs the read path, so the
pair round-trips a codec in two commands. Remember `tick unfreeze` afterwards.

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

### Per-tick telemetry

`autopilot status` is a snapshot at whatever rate a shell can poll it, and the things this feature
gets wrong last a handful of ticks — the flare fires, the throttle shuts, and the aircraft is in the
water forty ticks later. Start the server with

```sh
java -Xmx3G -Dsimpleplanes.autopilot.trace=true -jar fabric-server-launch.jar nogui
```

and every autopilot aircraft prints one line per tick to `console.log`:

```
trace #5 t=1713 flare pos=0.3,-56.02,15.6 agl=3.98 gnd=-60.0 landable=false vs=-0.063 spd=0.439
      thr=0 og=false water=false cmdalt=-57.9 thr_y=-60.0 dthr=15.1 lat=-0.2
```

`gnd`/`landable` are the two surface answers (`AGL` reference, and whether it is ground at all),
`dthr` is the distance to the landing threshold along the runway, `lat` the offset across it, `og`
and `water` are `getOnGround()` and `isOnWater()` — which are not the same question and were being
treated as one. A 90-second sortie is about 1800 lines; `grep "trace #5" console.log` per aircraft.
It is off by default and costs a `Boolean.getBoolean` per tick when it is.

### Recipe: an approach over water

The whole point of this one is that **nothing about the airfield changes when you flood its
approach** — the survey reports the same length, width, roughness and obstacle counts, because
`MOTION_BLOCKING` reports a waterline exactly the way it reports a field. So the same runway is its
own control: fly it dry, flood the corridor, fly it again.

```sh
./cmd.sh "forceload add -32 -176 32 352"
sleep 10
./cmd.sh "autopilot survey 0 -60 0 0 -60 -160"        # end 36 lands towards -Z, approach from +Z
./cmd.sh 'autopilot inbound 0 -20 700 "airfield-1"'   # dry: lands 6 blocks down the runway

# the sea. -63..-61 is the whole destructible depth of the superflat, so filling it with water
# leaves the heightmap at -60 - identical to the grass it replaced.
./cmd.sh "kill @e[type=simpleplanes:plane]"
./cmd.sh "fill -25 -63 -8 25 -61 168 minecraft:water"
./cmd.sh "fill -25 -63 169 25 -61 330 minecraft:water"
./cmd.sh 'autopilot inbound 0 -20 700 "airfield-1"'
```

Two numbers decide whether it ditches, and both are worth knowing before spending an hour on it:

* **How far the water reaches past the threshold.** The flare is entered 15 blocks *before* the
  threshold and touches down 5 blocks *past* it, so with the shoreline at the threshold the aircraft
  crosses the waterline with **1.2 blocks to spare** — measured identically at 0.40, 2.60 and the
  2.80 maximum, so it is geometry and not luck about speed. Flood to `z = -8` and it goes in. That
  1.2-block margin was the whole difference between "another sortie landed fine" and a drowning.
* **How high the water stands relative to the threshold.** Raising it is the obvious way to make the
  failure bigger and it does not work: standing water above the runway elevation has to be held back
  by something, and whatever holds it back also stands above a glide slope aimed at the threshold, so
  the aircraft hits the seawall instead. Any test built that way is testing the wall.

Restoring the ground afterwards takes three fills (the superflat is dirt at −63/−62 and grass at
−61), and the airfield needs no re-survey — nothing it stores has changed.

Flood **both** funnels and the strip itself to exercise the give-up path: the aircraft goes around
three times, switches ends, goes around once more, commits, and prints
`did not land at airfield-1/18: came to rest in the water, at 0, -63, -152`. That takes about eight
minutes of wall clock — five approaches — so give it the time before assuming it has hung.

### Recipe: what the surface probes read

Lay bands of a surface across a cruise track and fly over them with the trace on; there is no need to
land. One `autopilot route` at 0.80 over three 40-block bands answers the whole question:

```sh
./cmd.sh "forceload add -16 460 16 660"
./cmd.sh "fill -10 -63 600 10 -61 640 minecraft:lava"
./cmd.sh "fill -10 -60 540 10 -58 580 minecraft:oak_leaves[persistent=true]"
./cmd.sh "fill -10 -60 480 10 -58 520 minecraft:powder_snow"
./cmd.sh "autopilot route 0 -20 700 0 -20 460 0.80"
grep "trace #" console.log        # read gnd= and landable= per band
```

Measured at 100 blocks up: grass `gnd=-60 landable=true`, lava `gnd=-60 landable=false`, leaves
`gnd=-57 landable=true`, powder snow `gnd=-60 landable=true` — the ground *under* the snow, because
powder snow is in neither heightmap. See `AUTOPILOT.md`, "Water is not ground".

### Recipe: is the throttle loop actually regulating

The one-line version of the whole speed system. Fly a long straight leg at a commanded speed and
compare `spd=` against `want[... spd=]` in `status`, and the position deltas against the clock.

```sh
./cmd.sh "autopilot route 0 -60 0 2000 -60 0 0.50"
for i in $(seq 1 8); do ./cmd.sh "autopilot status"; sleep 8; done
grep -E "^\[.*\]:   #" console.log | tail -8
```

The position delta divided by the elapsed ticks is the real speed, and it must agree with `spd=`
and with what was ordered. Measured on the current build, straight and level:

| commanded | holds at | lever |
|---|---|---|
| 0.40 | 0.43 | dithering 0/1 |
| 0.50 | 0.52 | dithering 0/1 |
| 1.20 | 1.23 | 1 |
| 2.60 | 2.58–2.61 | dithering 8/9 |
| 2.80 | 2.78–2.83 | pinned at 10 |

A lever pinned at its floor or its ceiling while the speed sits somewhere else is the failure to
look for; that is what "commanded 0.80, flew 0.93 at throttle 1" looked like.

### Recipe: measuring a deceleration

Fly straight at the runway so the whole bleed is flown in a straight line, and poll every second so
the samples are 20 ticks apart:

```sh
./cmd.sh 'autopilot inbound 2655 -1 2000 "airfield-2" 2.80'
for i in $(seq 1 60); do ./cmd.sh "autopilot status"; sleep 1; done
```

Sum the chord lengths between consecutive `pos=` samples for the distance; the speeds come from
`spd=`. Do not use the straight-line distance between the first and last sample — the aircraft turns
onto the approach partway through and the chord sum is already an underestimate of the path.

### Recipe: a short runway, and refusing one

`MIN_USABLE_RUNWAY_LENGTH` is 30 blocks, so a 24-block strip is the test case for the refusal and a
66-block one is the test case for a landing that has to be tidy:

```sh
./cmd.sh "autopilot survey 660 -60 40 660 -60 64"     # 24 blocks -> registers with a warning
./cmd.sh 'autopilot flight "airfield-1" "airfield-3"' # -> refused, with the numbers
./cmd.sh 'autopilot airfields'                        # -> the row is marked TOO SHORT
```

### Recipe: marked parking

`park` needs loaded ground for the same reason `survey` does — it measures the spot and the whole
line from it to the threshold:

```sh
./cmd.sh "forceload add 640 -200 690 70"
sleep 6
./cmd.sh 'autopilot airfields park "airfield-1" 670 -60 10'
./cmd.sh 'autopilot airfields park "airfield-1" 638 -60 3'
./cmd.sh 'autopilot airfields info "airfield-1"'

# two sorties a second apart must park on different spots, not on top of each other
./cmd.sh 'autopilot flight "airfield-1" "airfield-2"'
./cmd.sh 'autopilot flight "airfield-1" "airfield-2"'
```

The refusals are worth exercising too, and each has its own message: a spot more than 64 blocks from
the threshold, one raised or sunk more than 2 blocks (`fill` a 4-block plinth next to the runway),
one within 5 blocks of an existing spot, and one on unloaded ground.

**Check the aircraft is on the spot, not in it.** The launch line prints the spawn position and
`status` prints where it settled, and those are one block apart on purpose:

```
Plane #1 parked at airfield-1 (671, -59, 11), …
  #1 parked pos=671,-60,11 agl=0 …
```

`agl=0` on the second line is the assertion. A spot marked with `park … 670 -60 10` is stored as the
block `670, -61, 10`, so `-60` is its top face; anything lower means the aircraft is inside the
ground and the taxi will never start.

### Recipe: departure delay, and two aircraft for one runway

Both halves of the departure gate are assertable from the log, and the second one needs two aircraft
ordered a few seconds apart out of the *same* field:

```sh
./cmd.sh 'autopilot flight "airfield-1" "airfield-2" delay 30'
for i in $(seq 1 8); do ./cmd.sh "autopilot status"; sleep 5; done
# -> wait=clock 0:27 … 0:14 … 0:04 … wait=runway
# -> Plane #1 cleared to taxi at airfield-1/36 after 31s on the parking spot.

./cmd.sh "kill @e[type=simpleplanes:plane]"
./cmd.sh 'autopilot flight "airfield-1" "airfield-2"'
sleep 3
./cmd.sh 'autopilot flight "airfield-1" "airfield-2"'
for i in $(seq 1 16); do ./cmd.sh "autopilot status"; sleep 1; done
```

Poll `status` every **one** second here, not every five: the whole interesting window is the ten
seconds the second aircraft spends stationary, and the moment to catch is the single tick where the
first one enters `climb` and the second is cleared. The assertions are
`Plane #B holding on the parking spot at airfield-1: runway occupied by #A`, a `pos=` on #B that does
not change while #A taxis, and the pair of lines in the same second:

```
Plane #57 cleared to taxi at airfield-1/36 after 10s on the parking spot.
  #56 climb pos=657,-47,-66 agl=13 …
```

`autopilot tower` is the other view of the same fact and is the quicker check while a run is live —
`airfield-1  36/18  OCCUPIED  #56 departure, taxi, 0:04` with `#57 departure, parked, 0:01, waiting
for the runway` indented under it.

To prove the reservation cannot leak, kill an aircraft while it holds one and read the board back:

```sh
./cmd.sh 'autopilot flight "airfield-1" "airfield-2"'
sleep 4                                            # taxiing, so it holds airfield-1
./cmd.sh "kill @e[type=simpleplanes:plane]"
./cmd.sh "autopilot tower"                         # -> airfield-1  36/18  FREE  no traffic
```

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
