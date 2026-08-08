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

**Sprint the clock rather than waiting for it.** `/tick sprint <ticks>` is vanilla and runs the world
as fast as the CPU allows — 600–1300 ticks/s with an aircraft airborne here, so the 25-second sleep
above becomes well under a second and a full sortie becomes a few seconds. Ticks are still ticks;
only wall-clock time changes, which is why it does not perturb anything the flight model does. Issue
it after the flight command, and `/tick sprint stop` when the outcome line appears.

**Run your own copy on your own port.** Several of these servers exist side by side on this container
(`/home/user/testserver` on 25565 and others beside it); copy the launcher, `libraries`, `versions`,
`mods`, `eula.txt`, `server.properties` and `ops.json` into a new directory, change `server-port`,
`query.port` and `rcon.port`, and take a fresh world. Sharing one rig between two people running
`tick sprint` and `kill @e` at each other is not a test, and a world full of another agent's
airfields will renumber yours.

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

**`kill @e` only sees loaded chunks, and that will cost you a whole comparison.** `@e` selects
entities in chunks that are resident, so after a server restart — which is exactly what swapping the
jar between a baseline run and an "after" run requires — the aircraft the previous runs parked on the
runway are invisible to it and survive. The next arrival then lands *on top of one of them* and
reports

```
Plane #1 did not land at airfield-1/36: came to rest 4 blocks above the runway surface, at 1, -56, -25.
```

which reads like a landing bug and is a stale hulk. The trace gives it away: `og=true` with
`agl=3.60` and `gnd=-60.0`, i.e. standing on something the heightmap cannot see, because entities are
not in a heightmap. **Force-load the runway before the kill**, every run:

```sh
./cmd.sh "forceload add -32 -240 32 32"; sleep 3
./cmd.sh "kill @e[type=simpleplanes:plane]"; sleep 1
./cmd.sh "forceload remove all"
```

**`/fill` over 32768 blocks does nothing, quietly enough to fool you.** Building test terrain, a
`fill` of 41×37×41 = 62 197 blocks prints its refusal and changes not one block — and the next survey
then reports `approach obstacles: 36 -> 0`, which looks exactly like an obstacle test that has
disproved itself. Split the fill and re-read the survey line before trusting the run.

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
Plane #7 landed at airfield-2/36, 2655, -60, -21 (18 blocks down the 66-block runway, 28% used).
```

The percentage is the assertion worth making on a landing, not the distance: "3 blocks down the
runway" is a tidy arrival on a short field and an aircraft parked on the lip of a 183-block one, and
for a long time it was the latter without anything in the output saying so.

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
      thr=0 og=false water=false cmdalt=-57.9 thr_y=-60.0 dthr=15.1 daim=-21.5 lat=-0.2
```

`gnd`/`landable` are the two surface answers (`AGL` reference, and whether it is ground at all),
`dthr` is the distance to the landing threshold along the runway, `lat` the offset across it, `og`
and `water` are `getOnGround()` and `isOnWater()` — which are not the same question and were being
treated as one. A 90-second sortie is about 1800 lines; `grep "trace #5" console.log` per aircraft.
It is off by default and costs a `Boolean.getBoolean` per tick when it is.

**`daim` is the one the arrival is actually flown to**, and it is not `dthr`. The glide slope ends on
the aim point, the flare is triggered relative to it and the "still airborne" go-around is measured
from it, so `daim` going through zero is the moment the aircraft is over the point it is aiming at.
`dthr` is kept beside it because the survey, the landing report and the airfield browser all speak in
distances from the threshold, and watching the two diverge is how the aim rule is checked. They are
equal only on a runway short enough that `touchdownAimOffset` returns 0, which no usable runway is.

### Recipe: an approach over water

The whole point of this one is that **nothing about the airfield changes when you flood its
approach** — the survey reports the same length, width, roughness and obstacle counts, because
`MOTION_BLOCKING` reports a waterline exactly the way it reports a field. So the same runway is its
own control: fly it dry, flood the corridor, fly it again.

```sh
./cmd.sh "forceload add -32 -176 32 352"
sleep 10
./cmd.sh "autopilot survey 0 -60 0 0 -60 -160"        # end 36 lands towards -Z, approach from +Z
./cmd.sh 'autopilot inbound 0 -20 700 "airfield-1"'   # dry: lands 37 blocks down a 160-block runway

# the sea. -63..-61 is the whole destructible depth of the superflat, so filling it with water
# leaves the heightmap at -60 - identical to the grass it replaced.
./cmd.sh "kill @e[type=simpleplanes:plane]"
./cmd.sh "fill -25 -63 -8 25 -61 168 minecraft:water"
./cmd.sh "fill -25 -63 169 25 -61 330 minecraft:water"
./cmd.sh 'autopilot inbound 0 -20 700 "airfield-1"'
```

Two numbers decide whether it ditches, and both are worth knowing before spending an hour on it:

* **How far the water reaches past the threshold**, which is the number this recipe exists to find,
  and it is **no longer near the threshold at all**. It used to be: the flare fired 15 blocks
  *before* the threshold, so a shoreline stopping exactly at the threshold left 1.2 blocks to spare
  and flooding to `z = -8` drowned the aircraft. Since the glide slope was re-aimed at the touchdown
  point the flare fires 21.8 blocks *past* the threshold on a 183-block field, and the same runway
  flooded 8 and then 20 blocks onto the strip lands at 43.0 both times — the same number as dry, to
  the tenth of a block, because the water is nowhere near where the aircraft stops flying. Flood 26
  blocks in and `landableBelow` simply defers the flare until there is runway underneath
  (`agl=3.29`, 26.5 blocks down) and it still lands. **Flood past the aim point** to see it fail.
  This margin scales with the aim offset, so it is smaller on a short field: use a runway of the
  length you care about, and read `daim` in the trace rather than assuming.
* **How high the water stands relative to the threshold.** Raising it is the obvious way to make the
  failure bigger and it does not work: standing water above the runway elevation has to be held back
  by something, and whatever holds it back also stands above a glide slope that ends on the runway,
  so the aircraft hits the seawall instead. Any test built that way is testing the wall.

Restoring the ground afterwards takes three fills (the superflat is dirt at −63/−62 and grass at
−61), and the airfield needs no re-survey — nothing it stores has changed. Prove the restore worked
by re-flying it: the arrival should reproduce the dry numbers exactly, tick for tick.

Flood **both** funnels and the strip itself to exercise the give-up path: the aircraft goes around
three times, switches ends, goes around once more, commits, and prints
`did not land at airfield-1/18: came to rest in the water, at 1, -63, -142`. That is five approaches
— eight minutes of real time, or about ten seconds under `tick sprint 30000`.

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

### Recipe: building the "mountain off the threshold" arrival

The route and arrival planner was written against a user's world — a runway at **y = 69** on a spit
with sea on both sides, a summit at **y = 158** immediately off the north threshold, and open water
at **y = 61** a short way west. That geometry is reproducible on the superflat, and it has to be:
this is the one case where "climb over" and "go round" give visibly different answers.

**Work in relative heights.** The superflat's surface is `surfaceHeight = -60` (bedrock −64, dirt
−63/−62, grass −61), and there is no cheap way to raise a whole ocean to 61. Subtract 129 from every
one of the user's numbers and the geometry is exact:

| | user's world | on the rig | relative to the runway |
|---|---|---|---|
| sea / open ground | 61 | −60 (the superflat itself) | −8 |
| runway surface | 69 | −52 | 0 |
| summit | 158 | 37 | +89 |

```sh
./cmd.sh "forceload add -48 -224 56 56"        # 126 chunks - under the 256 limit
sleep 8
# the spit: 25 x 141 x 9 = 31725 blocks, one fill, just under the 32768 limit
./cmd.sh "fill -12 -61 -90 12 -53 50 minecraft:stone"
# the mountain: a stepped cone centred (5, -160), half-extents 45 x 55, up to y=36.
# Emit it 4 layers at a time so each fill stays under the limit and the sides stay conical.
./cmd.sh "autopilot survey 0 -52 40 0 -52 -70"
./cmd.sh "forceload remove all"                # fly it with no force-loading, as a real flight is
```

The survey should report `approach obstacles: 36 -> 0, 18 -> 10` and prefer 36 — the mountain sits
inside the 200-block funnel of the north threshold, so the *end* choice is already right and what is
left to test is the path to it. Then:

```sh
./cmd.sh 'autopilot inbound 0 -30 -1000 "airfield-1" 2.60'   # down the extended centreline
./cmd.sh 'autopilot inbound -150 -30 -1000 "airfield-1" 2.60' # offset, so the join is a 180
./cmd.sh 'autopilot inbound 0 120 -900 "airfield-1" 2.60'     # 172 blocks too high
```

### Recipe: measuring an arrival

Poll `autopilot status` once a second for the whole flight and reduce the log afterwards. One
second is 20 ticks, which is fine resolution for a track that is 1600 blocks long, and every field
needed is on the status line already:

```sh
: > console.log
./cmd.sh 'autopilot inbound 0 -30 -1000 "airfield-1" 2.60'
for i in $(seq 1 130); do ./cmd.sh "autopilot status"; sleep 1; done
```

Then, from consecutive `pos=` samples:

* **track flown** — the sum of the horizontal chords, *not* the straight-line distance; the aircraft
  turns, and on an arrival it turns a lot.
* **total climb** — the sum of the positive `y` deltas only. This is the number the whole
  over-versus-round argument is about, and it is invisible in a peak-altitude figure.
* **orbits** — the sum of `|Δhdg|` over the flight, divided by 360. An aircraft that flies a clean
  arrival turns about 0.8 of a circle; 2.1 means it went round something.
* **minimum clearance** — `agl` while airborne. Below `TERRAIN_CLEARANCE` (22) the aircraft is inside
  its own margin, which is the failure the side-probe reflex used to produce.
* **top of descent → wheels stopped** — from the first `descent` sample to the `landed at` line. This
  is the clock the user actually complains about.

Two traps:

* **`console.log` is not clean UTF-8** once a flight has run (mode names and coordinates are fine,
  but `grep` will call it a binary file). Use `strings` or read it as bytes.
* **Poll deduplication matters.** A status command issued twice inside one tick prints the same
  position twice; drop consecutive identical `pos=` samples before summing anything, or the
  track comes out short and the climb comes out zero.

### Recipe: is the arrival being planned, or discovered

The question `AUTOPILOT.md` §4d exists to answer, and it is answered by two numbers off the trace and
one line out of the log. Fly the same arrival on the two jars and compare:

```sh
./cmd.sh 'autopilot inbound 0 -30 700 "airfield-1" 2.60'
./cmd.sh "tick sprint 9000"
strings console.log | grep -E "arrival at|replanning|going around|landed at"
```

* **Where `DESCENT` is entered.** `grep " descent "` the trace and read the first `dthr=`. Positive is
  blocks before the threshold; **negative means the arrival began over the runway**, which is what a
  waypoint-triggered arrival does. Before this work it read `dthr=-51.2`; it now reads about `+415`,
  and the log says so in as many words: `straight in, decided 415 blocks out`.
* **Track flown against the direct distance.** Sum the horizontal chords between consecutive `pos=`
  samples. A straight-in launched 780 blocks out flew **1578** blocks of track when the arrival began
  overhead and **737** when it is decided at range; the difference is one whole unplanned circuit.
* **Peak `lat=` while the mode is `approach` or `final`.** Not while it is `descent` — an aircraft
  legitimately running in from abeam is a long way off the centreline and that is not an excursion.
  41.8 blocks before, 0.0 after, on the same flight.

`plan[…]` on `/autopilot status` and `replans=N` beside `go-arounds=N` are the same story live.

### Recipe: terrain the survey never saw

The case the replan triggers exist for, and the only one on a flat rig that produces a go-around at
all. Survey the field **first**, then build the obstacle, so the stored obstacle counts are honest
about a world that has since changed:

```sh
./cmd.sh "forceload add -32 -240 32 160"; sleep 4
./cmd.sh "fill -15 -61 90 15 -40 130 minecraft:stone"     # 21 blocks tall, 90-130 out on the 36 funnel
./cmd.sh "forceload remove all"
./cmd.sh 'autopilot inbound 0 -30 700 "airfield-1" 2.60'
./cmd.sh "tick sprint 30000"
```

Expect, on a build that only plans overhead, three `going around (n/3): terrain in the approach
corridor` and then a switch to the other end — 2018 ticks and 2350 blocks of track. On one that
re-checks its committed plan, one line:

```
Plane #2 replanning the arrival at airfield-1/18: straight in, decided 529 blocks out (terrain across the 36 glide slope).
```

1353 ticks, no go-arounds. **Restore the ground afterwards in two fills** — the wall replaced the
grass layer, so `air` over `-60…-40` and `grass_block` at `-61` — and re-survey if you changed
anything inside 200 blocks of a threshold.

The departure half of the same test needs the obstacle *where a climb-out actually is*, which is much
closer in: an aircraft turns on course within about 40 blocks of the far threshold, so a wall 90
blocks out is never overflown and proves nothing. Put it 20 to 60 blocks off the threshold, survey,
and send a sortie to a field **in line with the runway** so the departure climbs straight out:

```sh
./cmd.sh "fill -20 -61 20 20 -25 40 minecraft:stone"      # two fills: one would exceed 32768
./cmd.sh "fill -20 -61 41 20 -25 60 minecraft:stone"
./cmd.sh "autopilot survey 0 -60 0 0 -60 -160"            # -> approach obstacles: 36 -> 5, 18 -> 0
./cmd.sh 'autopilot flight "airfield-1" "airfield-3" 2.60'
```

`airfield-3` here is a strip 2660 blocks due south. The assertion is one line either way:
`Plane #100 lost at 19, -27, 19 in climb.` against
`Plane #4 departure from airfield-1: depart 36, 180 deg turn to course.`

### Recipe: several arrivals at once

Four aircraft at one runway is the cheapest way to exercise `HOLD`, the stack separation and the
tower board together. Watch the argument spacing — `inbound  60 …` with two spaces is parsed as a
different argument and the command is rejected with `Expected double`, which looks exactly like an
aircraft that failed to spawn:

```sh
./cmd.sh 'autopilot inbound -60 -30 -700 "airfield-1" 2.60'
./cmd.sh 'autopilot inbound 60 -30 -700 "airfield-1" 2.60'
./cmd.sh 'autopilot inbound -60 -30 700 "airfield-1" 2.60'
./cmd.sh 'autopilot inbound 60 -30 700 "airfield-1" 2.60'
sleep 25 && ./cmd.sh "autopilot tower"
```

Expect losses, and expect them on the unmodified build too — measured there, 3 of 4 aircraft were
destroyed. Two of those are a mid-air between arrivals converging on the same fix, which nothing in
the code prevents; see the limitations in `AUTOPILOT.md`. What this test can show is that no two
aircraft are destroyed *in `HOLD`, at the same altitude, three blocks apart* — that failure is fixed.

### Recipe: comparing a change against the build it replaces

There is no second server and no second world: use one rig and swap the jar.

```sh
git stash push -u                                   # in the worktree
JAVA_HOME=/opt/jdk25 /opt/gradle-9.6.1/bin/gradle -p <worktree>/26.2 build --no-daemon --offline
cp <worktree>/26.2/build/libs/simpleplanes-*.jar /tmp/…/baseline.jar
git stash pop
```

Then run the same scripted flights against each jar in turn, restarting the server between them.
The world, the survey and the terrain are identical, which is the only way the numbers mean
anything. Keep the baseline jar — you will want it again the first time a "fix" makes something
worse, and it is how the four-aircraft result above was shown to be pre-existing rather than new.

### Recipe: a short runway, and refusing one

`MIN_USABLE_RUNWAY_LENGTH` is 18 blocks, so a 16-block strip is the test case for the refusal and an
18-block one is the test case for a landing that has to be tidy — it is the shortest field the
autopilot will accept, so it is the one that has to be flown before the constant may be lowered:

```sh
./cmd.sh "autopilot survey 660 -60 40 660 -60 56"     # 16 blocks -> registers with a warning
./cmd.sh 'autopilot flight "airfield-1" "airfield-3"' # -> refused, with the numbers
./cmd.sh 'autopilot airfields'                        # -> the row is marked TOO SHORT
```

### Recipe: measuring where a landing actually puts the aircraft

The four numbers that matter are all in the trace, and none of them are in `status`: where the
aircraft crosses the threshold and how high, where the flare fires, where the wheels touch, and where
it stops. Run one arrival with the trace on and read them off the ticks, in that order — `daim`
through zero for the aim point, `dthr` through zero for the threshold, the first `flare` line, the
first `og=true` line and the last line of all.

```sh
./cmd.sh 'autopilot inbound 0 -20 700 "airfield-1" 2.60'
./cmd.sh "tick sprint 6000"
grep "trace #" console.log | grep -E " (final|flare|rollout) "
```

Two things make this cheap enough to run a dozen times. **`/tick sprint <ticks>` is vanilla and runs
the world flat out** — measured at 600–1300 ticks/s with an aircraft airborne on this container, so a
90-second arrival completes in 2–5 seconds of wall clock. The physics is untouched: ticks are still
ticks, only wall-clock time changes, which is exactly why it is safe where "tick the aircraft faster"
would not be (every constant in this flight model is per-tick). `/tick sprint stop` aborts it, and
the completion line doubles as a free performance readout. **And the whole event is speed-independent
in the parts that count** — the arrival is flown at `APPROACH_SPEED`/`FINAL_SPEED` whatever the
cruise was ordered at, so 0.40 and 2.80 touch down within three blocks of each other and the trailing
speed argument is a check, not a variable to sweep.

Do not quote wall-clock seconds from this container in a report: it is shared with other agents
running their own servers and builds. Tick counts and positions are solid; seconds are not.

Measured touchdown and stop points across runway lengths and speeds are tabulated in `AUTOPILOT.md`,
"Where on the runway it touches down"; they are the reference to regress against.

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
