# Test environment for the 26.3 port

Everything below was run end to end on the 26.3 rig; the only unverified item is the headless
client in §4, which is marked as such there.

Almost all of this document is inherited from the 26.2 port and every trap in it still applies —
the autopilot, the runway geometry and the failure modes did not change. What did change is the
toolchain and the paths, and those are rewritten in §1–§3 below. **Nothing here is version-neutral
by accident: if a path in this file does not exist, check §1 before believing the surrounding
claim.**

---

## 1. Toolchain

| What | Where | Notes |
|---|---|---|
| JDK 25 | `/usr/lib/jvm/java-25-openjdk-amd64` | required by Minecraft 26.3. `/opt/jdk25` from the 26.2 rig **no longer exists**; plain `java` on PATH is 25 now, but set `JAVA_HOME` anyway — Gradle does not read PATH |
| Gradle 9.6.1 | `/opt/gradle-9.6.1/bin/gradle` | the wrapper cannot self-download here |
| Gradle 8.14.3 | `/opt/gradle-8.14.3/bin/gradle` | only for the `1.21.1/` NeoForge sources |
| Dependency cache | `/root/.gradle/caches` | fully warm — `--offline` builds work |

### Build the mod

```sh
cd <checkout>/26.3
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
  flock /tmp/mc-build.lock /opt/gradle-9.6.1/bin/gradle build --no-daemon
# -> build/libs/simpleplanes-26.3-5.3.11.jar
```

**Take `/tmp/mc-build.lock` on every Gradle invocation.** Loom may not run twice at once: two
invocations share `~/.gradle/caches/fabric-loom` and race on the same Minecraft jars, and the
loser corrupts the cache rather than waiting. `flock` simply queues.

`--offline` is the fast path and is enough for a source change. Drop it only if a dependency
version changes.

---

## 2. Reference sources

| Source | Path | Contents |
|---|---|---|
| Minecraft 26.3-snapshot-10 | **generate it** — see below | 7243 `.java`, deobfuscated, client and server |
| Minecraft 26.3-snapshot-10 | `/root/.gradle/caches/fabric-loom/26.3-snapshot-10/minecraft-merged.jar` | the bytecode itself; `javap -p` on it settles any question a source tree cannot |
| Upstream mod (NeoForge 1.21.1) | `<checkout>/1.21.1` | unmodified, for behaviour parity checks |
| 26.2 behaviour parity | `<checkout>/26.2` | the previous port's own sources — `/opt/mc-src` (26.2 vanilla) is **gone** |

**`/opt/mc-src-26.3` is snapshot-9, not snapshot-10, despite the name.** It has
`WORLD_VERSION = 5011`; snapshot-10 is 5015. The two genuinely differ — `SurfaceRules` exists in
one and is deleted in the other, `ChunkStatus.NOISE`/`SURFACE`/`CARVERS` collapsed into `TERRAIN`,
and `PoseStack.mulPose(Quaternionfc)` became `PoseStack.rotate(Quaternionfc)`. Reading a signature
out of that tree and trusting it costs a compile round trip at best and a wrong fix at worst.

Generate the real thing once and grep that instead:

```sh
flock /tmp/mc-build.lock /opt/gradle-9.6.1/bin/gradle -p <checkout>/26.3 genSources --no-daemon
unzip -q -o <checkout>/26.3/.gradle/loom-cache/minecraftMaven/net/minecraft/\
minecraft-merged-*/26.3-snapshot-10/minecraft-merged-*-26.3-snapshot-10-sources.jar -d /tmp/mc-src-s10
grep -n "WORLD_VERSION" /tmp/mc-src-s10/net/minecraft/SharedConstants.java   # -> 5015
```

It takes about 80 seconds (Vineflower, 7243 classes) and is cached afterwards.
| Upstream mod (NeoForge 1.21.1) | `/home/user/simple-planes/1.21.1` | unmodified, for behaviour parity checks |

Claims about vanilla behaviour go in a document only after being read in the **snapshot-10**
sources or bytecode. The physics and collision documents in this directory follow that rule against
26.2; keep it, and re-read rather than assuming when one of them talks about a class that moved.

---

## 3. Headless dedicated server

`/home/user/testserver` from the 26.2 rig is **gone**; the whole thing is rebuilt from scratch.
Keep it outside the repo — nothing there is committed.

```
sp-testserver/
├── fabric-server-launch.jar     Fabric loader 0.19.5 server launcher for 26.3-pre-1
├── mods/fabric-api-….jar        Fabric API 0.159.1+26.3
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
cp <checkout>/26.3/build/libs/simpleplanes-26.3-5.3.11.jar /home/user/sp-testserver/mods/
cd /home/user/sp-testserver
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
(`/home/user/sp-testserver` on 25599 and others beside it); copy the launcher, `libraries`, `versions`,
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
* **Anything about aircraft that are left standing somewhere must be tested without it.** A plane with
  no autopilot renews no chunk ticket and is unloaded 40 ticks after it stops, at which point it is
  invisible to `@e`, to `data get` and to every entity search in the mod. Force-loading the field
  papers over that completely: the marked-parking and taxi-in tests below pass force-loaded and fail
  without, which is the wrong way round for a test.
* Force-loading a corridor and then flying out of it is a good way to *reproduce* the chunk bug
  rather than avoid it: the aircraft freezes at the boundary, keeping its velocity exactly.

**Kill leftovers between runs.** `./cmd.sh "autopilot stop"` stops the flight directors but leaves
the aircraft in the world, and test flights all use the same corridor — a parked hulk on the run-in
line will be rammed by the next one, which registers as a plane-to-plane collision and ends the run
early with a confusing report. Use `./cmd.sh "kill @e[type=simpleplanes:plane]"`.

**And force-load the airfield before you kill, or the kill does nothing.** A command selector only
sees entities in **loaded chunks**, and the aircraft you most need to remove is the one parked at the
far airfield, in chunks nobody is near. `kill @e[…]` reports `No entity was found`, looks like it
worked, and leaves the wreck exactly where the next arrival is going to touch down. What that then
produces is not a crash but a plausible-looking approach defect:

```
did not land at airfield-2/18: came to rest 5 blocks above the runway surface, at 2655, -55, -178
did not land at airfield-2/18: came to rest 6 blocks above the runway surface, at 2655, -54, -180
did not land at airfield-2/18: came to rest 7 blocks above the runway surface, at 2655, -53, -187
```

The tell is that the number **grows** run over run, and the touchdown point marches down the strip,
as the pile gets taller and longer. Force-loading the two fields and killing inside that window
turned up **52 entities** on a rig that had been reporting `No entity was found` all afternoon. Do
this before every run that you intend to draw a conclusion from:

```sh
./cmd.sh "forceload add 640 -200 670 0"
./cmd.sh "forceload add 2640 -200 2670 0"
sleep 6
./cmd.sh "kill @e[type=!player]"
```

**Repair the runway too.** A landing that fails badly enough explodes — `PlaneEntity#causeFallDamage`
above 45 degrees of bank — and the explosion breaks blocks, so a failed run leaves a crater in the
strip that the *next* run flies into. Measured: a cargo arrival tracking the centreline to within 0.5
blocks and the runway heading to within 0.6 degrees went from 0.416 blocks/tick to **0.000 in a single
tick** against the lip of a hole left by an earlier test, and reported `came down at … in go_around`.
The approach was faultless; the ground was not. The superflat is bedrock at −64, dirt at −63/−62 and
grass at −61, so restoring it is three fills per field:

```sh
./cmd.sh "fill 2640 -63 -200 2670 -62 0 minecraft:dirt"
./cmd.sh "fill 2640 -61 -200 2670 -61 0 minecraft:grass_block"
./cmd.sh "fill 2640 -60 -200 2670 -56 0 minecraft:air"     # 31x201x5 = 31155, under the 32768 limit
```

`Successfully filled 111 block(s)` on the dirt layer is how many blocks of runway were missing.

**The mob gamerule was renamed in 26.2 and is unchanged in 26.3.** `gamerule doMobSpawning false` is **rejected** —
`GameRuleRegistryFix` renames it to `minecraft:spawn_mobs`. The failure is one line of
`Incorrect argument for command` in a log that is otherwise full of trace output, so it is very easy
to spend an afternoon believing spawning is off while it is not. That matters here because
`LargePlaneEntity` and `CargoPlaneEntity` mount any nearby non-player `LivingEntity`, so livestock
changes what those two airframes are:

```sh
./cmd.sh "gamerule minecraft:spawn_mobs false"     # -> Gamerule spawn_mobs is now set to: false
```

**`/tick sprint <ticks>` is the difference between minutes and hours.** Vanilla, no mod code
involved: the server runs that many ticks as fast as the CPU allows, and the physics is unaffected
because ticks are still ticks. A 2000-block sortie that takes 90 s of wall clock completes in about
3 s. `/tick sprint stop` aborts it, and the server prints `Sprint completed with N ticks per second`
when it finishes — which is also the signal to poll for, since an aircraft that never produces an
outcome will otherwise keep you waiting for the full sprint.
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
running server at any time; `console.log` is the transcript.

**Open that FIFO read-write, not write-only.** `exec 3>fifo` BLOCKS until a reader arrives, and on
the rig the reader is the java process the same script has not started yet — so a `start.sh` written
the obvious way deadlocks on its own first line and never launches anything. `exec 3<>fifo` returns
immediately. The same applies to `cmd.sh`: a plain `echo cmd > fifo` blocks whenever the server
happens not to be reading at that instant, which looks exactly like a hung server.

```sh
# start.sh - hold stdin open so the server never sees EOF and stops
nohup bash -c 'exec 3<>"$RUN/stdin.fifo"; sleep 100000' >/dev/null 2>&1 &
nohup java -Xmx3G -jar fabric-server-launch.jar nogui < "$RUN/stdin.fifo" > console.log 2>&1 &

# cmd.sh
exec 3<>"$RUN/stdin.fifo"; printf '%s\n' "$*" >&3; exec 3>&-
```

**Pin `level-seed` before comparing two builds.** An empty seed gives each rig its own world, and
on a superflat that still changes where structures generate. Measured here: the same
airfield-to-airfield sortie planned a 36 arrival on one rig and an 18 arrival on the other, because
one world had four obstacle columns in the 36 approach funnel and the other had none — which reads
exactly like a routing regression in the build under test and is a different world. With the seed
pinned, the two builds' transcripts matched line for line. Command output (`sendSuccess`) is
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
| `airfields [info\|show\|resurvey\|rename\|remove\|park\|unpark]` | browse and manage them; every form works headlessly |
| `survey <t1> <t2>` | register a runway — and it is only half the job now, see `airfields park` |
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

./cmd.sh 'autopilot airfields park "airfield-1" 672 -60 6'    # a field is not usable without a stand
./cmd.sh 'autopilot airfields park "airfield-1" 672 -60 -8'
./cmd.sh 'autopilot airfields park "airfield-2" 2672 -60 6'
./cmd.sh 'autopilot airfields park "airfield-2" 2672 -60 -8'

./cmd.sh "forceload remove all"               # prove the flight loads its own chunks
./cmd.sh 'autopilot flight "airfield-1" "airfield-2"'
```

The four `park` calls are not optional any more: a runway surveyed by this build refuses sorties
until at least one stand is marked beside it, and the sortie now ends on a stand rather than on the
strip. See the marked-parking and taxi-in recipes below.

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

That line is no longer the *last* one, though — a sortie into a field with marked parking goes on to
taxi off the strip and park, and ends with `Plane #7 parked at airfield-2, 2673, -60, -6 (stand
2672, -61, -8, 978 ticks from the runway).` A script that stops reading at `landed at` will call the
flight finished about a thousand ticks early.

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
      thr=0 og=false water=false hdg=360.0 cmdhdg=0.6 roll=-0.1 cmdalt=-57.9
      thr_y=-60.0 dthr=15.1 daim=-21.5 lat=-0.2
```

`gnd`/`landable` are the two surface answers (`AGL` reference, and whether it is ground at all),
`dthr` is the distance to the landing threshold along the runway, `lat` the offset across it, `og`
and `water` are `getOnGround()` and `isOnWater()` — which are not the same question and were being
treated as one. A 90-second sortie is about 1800 lines; `grep "trace #5" console.log` per aircraft.
It is off by default and costs a `Boolean.getBoolean` per tick when it is.

**`hdg`/`cmdhdg`/`roll` are what make a lateral problem readable.** Without them a heading can only be
recovered by differencing two `pos=` samples, and that gives the *track* rather than where the nose
points — while the landing gates are written about the nose. More importantly, a reconstructed
heading cannot separate "not tracking the command" from "tracking a command that is wrong". Those are
completely different bugs and they look identical in `pos=`: the cargo approach turned out to be
holding its commanded heading to within a degree the whole time, while the command itself sat 40
degrees off the runway.

A one-tick collapse in `spd=` with `og=true` well above `gnd=` is the signature of hitting something
solid — usually a leftover aircraft or a crater, both of which are rig contamination rather than
flight defects. See "Kill leftovers between runs" above.

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

### Recipe: a runway whose edges the survey can see

**The superflat has no runway edges, and that hides a whole class of bug.** `Airfield.measureWidth`
and the threshold centring both walk sideways until the surface is more than a block off the
threshold elevation; on open superflat that never happens, so every strip surveyed there reports the
maximum width of 25 and every click is already "on the centreline". Testing anything about runway
width or centreline position needs a strip with a detectable lip.

Build a plinth. Superflat is bedrock −64, dirt −63/−62, grass −61, so `surfaceHeight` is −60; filling
the strip up to −58 puts its surface at −57, three blocks proud of the field:

```sh
./cmd.sh "forceload add -64 -240 64 80"
./cmd.sh "fill -6 -60 -160 6 -58 0 minecraft:stone"    # 13 wide (x -6..6), 161 long
./cmd.sh "autopilot survey -6 -58 0 -6 -58 -160"       # both ends clicked on the LEFT EDGE
```

**Three blocks, not two, and the reason is the departure.** Two is enough for the width probe (its
tolerance is ±1) but not for parking: `PARKING_MAX_ELEVATION_DIFFERENCE` is 2, so a two-block plinth
lets the derived apron sit on the grass *beside* the strip, and the aircraft then has to taxi up a
step the ground handling cannot climb. Seen on this rig as `takeoff pos=-3,-60,2 spd=0.000 thr=10`
repeating for 22 000 ticks and then `Plane #4 lost at -3, -60, 2 in takeoff` — a perfect-looking
freeze that is entirely an artefact of the test terrain. At three blocks the apron is refused and the
departure starts on the strip, which is what the code intends.

Read the answer off `airfields info` (the stored thresholds are printed) and off the trace: `pos=` in
`parked`/`takeoff`/`rollout` is where the aircraft actually is across the strip, while `lat=` is only
its error against the *surveyed* line and reads 0.2 whether that line is down the middle or on the
edge. Comparing the two is the whole test.

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

**Vegetation needs three extra precautions**, all learned the hard way here. The measured table for
ten species is in `AUTOPILOT.md`, "What the approach funnel can see, and the bamboo report".

* **Turn random ticking off, and note the gamerule was renamed.** 26.2 namespaces them:
  `/gamerule random_tick_speed 0`, not `randomTickSpeed`. The old name is a *parse error* on the
  console, which prints as `gamerule randomTickSpeed 0<--[HERE]` and is easy to read as success. Two
  runs were wasted on a band of bamboo saplings that had quietly grown into 3-block stalks during a
  `tick sprint 4000`, which is exactly the reading the band exists to take.
* **Most plants pop off when `fill` updates their neighbours, and each has its own rule.** Cactus
  breaks when any horizontal neighbour is solid, and cactus is solid, so a filled slab of it deletes
  itself — place a lattice, one column every 2 blocks in x *and* z. Sugar cane needs water beside the
  block underneath it, so lay water channels every third row and cane in the two rows between. Vines
  need a face to hang on: a stone pillar in the next column along, `minecraft:vine[east=true]` in the
  column you are measuring.
* **Check what actually survived before flying.** `execute if block <x y z> minecraft:cactus run say
  OK cactus` per band, and read the log — a band that silently failed to place reads exactly like a
  block that is invisible to the heightmap.

### Recipe: flying into something with a known speed

`Motion` in the summon NBT plus `tick freeze` / `tick step` is the cleanest impact measurement on
this rig, and it is not only for water. An unridden plane is simulated server-side, so it flies the
real collision path; freezing the clock and stepping it 2 ticks at a time is what makes "where
exactly did it die" answerable.

```sh
./cmd.sh "tick freeze"
./cmd.sh "summon simpleplanes:plane 430 -53.0 390 {Motion:[0.0,0.0,2.0]}"
./cmd.sh "tick step 2"
./cmd.sh "execute as @e[type=simpleplanes:plane] run data get entity @s Pos"
./cmd.sh "execute as @e[type=simpleplanes:plane] run data get entity @s health"
```

Three traps:

* **Select on `type`, not on a tag.** `Tags:["b20"]` in the summon NBT and `@e[tag=b20]` did not
  match on this build; `@e[type=simpleplanes:plane]` always does. Kill the previous aircraft and run
  one at a time instead.
* **Everything must be inside a `forceload`d region**, including the control. An entity in an
  unloaded chunk is not in the entity list, so `data get` prints nothing at all — which is
  indistinguishable from "the aircraft was destroyed", and cost a control run here.
* **A plane summoned with no throttle sinks while it flies**, about 0.1 blocks/tick at 2.0 b/t
  forward, so aim the entry a little high if the obstacle is short.

Measured against a 61×101 grove of 15-block bamboo: free until the grove, then 0.50 b/t stops 4
blocks in at −2 HP and stays there for ever, 1.00 destroys at 4 blocks in, 2.00 destroys at 2 blocks
in, and 2.00 into a stone wall of the same height destroys likewise.

### Recipe: how fast an airframe can actually turn

The number the whole arrival geometry is sized on, and it is not the one in `AutopilotConfig`. A
route out and straight back forces a 180-degree turnback flown at the commanded speed with the yaw
control saturated the whole way:

```sh
./cmd.sh "autopilot route 0 -20 0 600 -20 0 0.50 type cargo"
./cmd.sh "tick sprint 2600"
grep "trace #" console.log        # mean d(hdg)/dt over any window where |hdg-cmdhdg| stays > 20
```

Measure the **mean heading change per tick over a 40-tick window in which the heading error never
drops below 20 degrees** — below that the controller is braking and the rate is no longer the
airframe's limit. Measured on the current build:

| airframe | speed | nominal | measured | radius |
|---|---|---|---|---|
| `plane` | 1.16 | 2.5 | 2.065 | 32 |
| `large` | 1.34 | 1.25 | 1.025 | 75 |
| `cargo` | 0.50 | 0.5 | 0.507 | 56 |
| `cargo` | 1.56 | 0.5 | 0.503 | 178 |
| `cargo` | 1.98 | 0.5 | **0.296** | 380 |

The nominal `MAX_YAW_RATE * getRotationSpeedMultiplier()` clamps the **nose** rate, and
`tickRotateMotion` only pulls the velocity vector round to follow it at a finite rate — so the
realised turn rate falls away above roughly 1.5 blocks/tick and the realised radius at cruise is
nearly double the model's. At approach speed the nominal figure is exact. See `AUTOPILOT.md`,
"Which airframe flies".

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
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
  flock /tmp/mc-build.lock /opt/gradle-9.6.1/bin/gradle -p <worktree>/26.3 build --no-daemon
cp <worktree>/26.3/build/libs/simpleplanes-*.jar /tmp/…/baseline.jar
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

**A freshly surveyed runway now refuses sorties until a stand is marked**, so `survey` is no longer
the last step of setting a field up on this rig — `park` is. Both `flight` and `inbound` print
`airfield-3 has no parking marked, so an aircraft has nowhere to start from and nowhere to taxi to
after landing…` and spawn nothing, which looks exactly like an aircraft that failed to spawn if you
are not reading the refusal. Airfields already in a world are **grandfathered** and are unaffected;
see `AUTOPILOT.md`, "A surveyed runway is not finished until a stand is marked".

That flag is the thing to check after any change near the airfield codec, and the check is to read
the NBT rather than the browser:

```sh
python3 -c "
import gzip, re
d = gzip.open('/home/user/sp-testserver/world/dimensions/minecraft/overworld/data/simpleplanes/airfields.dat','rb').read()
print(re.findall(rb'[ -~]{4,}', d))"
# a grandfathered airfield has no requires_stands key at all; one surveyed by this build does
```

**Mark stands at both ends of a long field.** A stand is validated within 64 blocks of the *nearest*
threshold, so on a 183-block strip a field with stands at only one end sends every arrival that lands
on the other end on a 150-block taxi. Two stands per end, on the same side, is the layout that
exercises the apron lane and the "second aircraft picks the other stand" rule.

The refusals are worth exercising too, and each has its own message: a spot more than 64 blocks from
the threshold, one raised or sunk more than 2 blocks (`fill` a 4-block plinth next to the runway),
one within 5 blocks of an existing spot, and one on unloaded ground.

### Recipe: the taxi in

The arrival's ground phase, and it is the half of an arrival that `tick sprint` makes affordable: the
taxi alone is 400–1000 ticks, which is 20–50 seconds of wall clock at normal speed and under a second
sprinting. Every step prints, so the whole thing is assertable from the log:

```sh
./cmd.sh 'autopilot inbound 654 -20 700 "airfield-1" 2.60'
./cmd.sh "tick sprint 9000"
strings console.log | grep -a "Plane #" | grep -av trace
```

```
Plane #2 landed at airfield-1/36, 654, -60, -47 (38 blocks down the 183-block runway, 21% used).
Plane #2 vacating airfield-1/36, taxiing to the stand at 673, -60, -7 via 3 legs.
Plane #2 is clear of airfield-1/36 after 156 ticks, 40 blocks still to taxi.
Plane #2 parked at airfield-1, 673, -60, -7 (stand 672, -61, -8, 420 ticks from the runway).
```

The landing line is unchanged and is still the assertion to regress an arrival against; the three
lines under it are the new phase. **`is clear of` is the one to watch** — it is the tick the runway
reservation is given back, and it is neither the roll-out nor the end of the taxi.

To see the state rather than the transitions, poll while it runs. Do *not* sprint for this: the taxi
is the one phase slow enough to watch at 20 ticks a second, and `status` gains three fields for it.

```sh
./cmd.sh "tick sprint 1480"      # lands at about t=1420 on a 183-block field from 800 blocks out
for i in $(seq 1 12); do ./cmd.sh "autopilot status"; sleep 2; done
./cmd.sh "autopilot tower"
```

```
#46 taxi_in pos=2668,-60,-153 … stand=2673,-60,-8 to_go=145 rwy_held
#46 taxi_in pos=2676,-60,-153 … stand=2673,-60,-8 to_go=146 rwy_clear
3 runways in this dimension, 0 occupied, 0 holding, 0 waiting to depart, 1 taxiing in.
  taxiing to a stand (runway already released):
    #46 arrival 18, taxi_in, 0:30, 57 blocks to the stand [straight in]
```

**The case worth running is two arrivals, not one.** Order them a few seconds apart at the same
field; the second holds while the first is on the strip, lands where the first landed, and must go to
a *different* stand:

```sh
./cmd.sh 'autopilot inbound 654 -20 900 "airfield-1" 2.60'
sleep 1
./cmd.sh 'autopilot inbound 700 -20 1100 "airfield-1" 2.60'
./cmd.sh "tick sprint 9000"
./cmd.sh "execute as @e[type=simpleplanes:plane] run data get entity @s Pos"
```

Both must end on their own stands and both must survive — a plane-to-plane contact at speed destroys
both, and `PlaneEntity.canBeCollidedWith` is unconditionally true. Before this feature the same pair
ended 2.5 blocks apart on the strip, and six arrivals in a row ended with two aircraft resting on the
roofs of others at `y = -58.2`, each reporting a clean landing.

**Do not force-load the field for that test.** This is the one place on this rig where `forceload`
does not merely fail to help but actively hides the bug, and it hid this one for half a day. A parked
aircraft has no autopilot, so it renews no chunk ticket; 40 ticks after it arrives its chunk unloads
and it stops being findable by `@e`, by `data get`, and by the entity search that decides whether its
stand is free. **The same two flights pass force-loaded and fail without it**, and the failure — two
aircraft driven onto one square — is exactly what the test exists to catch. It is also why
`execute as @e[type=simpleplanes:plane] run data get entity @s Pos` prints nothing at the end of a
clean run: the aircraft are all there, in chunks nobody is loading. `forceload add` around the field
*afterwards* is the way to look at them, and `autopilot airfields info` will read `UNUSABLE: no ground
there (the chunk is not loaded…)` for every stand until you do.

**Reproducing a blocked taxi** takes one summoned hulk on the apron lane. The lane is
`halfWidth + PARKING_SPOT_CLEARANCE` outboard of the outermost stand, so for a 25-wide strip on
`x = 654` with stands at `x = 672` it is `x = 677`:

```sh
./cmd.sh 'summon simpleplanes:plane 677 -60 -22 {Tags:["blocker"]}'
./cmd.sh 'autopilot inbound 654 -20 700 "airfield-1" 2.60'
./cmd.sh "tick sprint 9000"
# -> Plane #21 stopped short of its stand at airfield-1, 679, -60, -24 (18 blocks to go, clear of the runway).
```

Check both aircraft afterwards with `data get entity @s` and read `health: 10` off each; the taxi is
flown at 0.20 blocks/tick, so a contact there is a shove and not a crash.

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
the snapshot-10 sources (`Entity#isLocalInstanceAuthoritative`, `Entity#move`,
`ServerGamePacketListenerImpl#handleMoveVehicle`) or tested with a real client — see below.

---

## 4. Client

`gradle runClient` is available (Loom is configured). The container has no GPU, so the software
Vulkan driver `mesa-vulkan-drivers` (lavapipe, `/usr/share/vulkan/icd.d/lvp_icd.json`) and `xvfb`
are installed:

```sh
cd <checkout>/26.3
XDG_RUNTIME_DIR=/tmp/xdg JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
  xvfb-run -a flock /tmp/mc-build.lock /opt/gradle-9.6.1/bin/gradle runClient --no-daemon
```

**Not verified end to end.** The prerequisites are all in place — the Loom `runClient` task exists,
xvfb and lavapipe are installed, and the run downloads assets normally — but the launch was stopped
partway through the ~500 MB first-run asset download, so no client has actually reached a title
screen here. Expect it to be slow (software rasterisation). It is a last resort for the
client-authoritative path, not the everyday loop.

Note that a `--no-daemon` Gradle run holds locks on `/root/.gradle/caches`: do not leave a client
running while building.
