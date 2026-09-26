# Missiles and launch silos

Harmless ground-launched missiles in four tiers, fired from silos sunk into the ground. A missile rises out of its
silo with its fins folded, deploys them clear of the tube, turns toward the target, cruises and dives on it. When
it arrives it makes a puff of smoke and particles and disappears.

**It never breaks a block, damages an entity or sets a fire.** That holds however the flight ends: on arrival,
on hitting terrain early, on running out of motor, on an abort or at a server stop. No explosion of any power is
created, and the mod's `Blast` code and explosion paths are never called. `MissileFx` is the only place an
effect comes from, and it only sends particles and plays sounds.

This is phase 1: launch and flight to coordinates. Interception is not implemented yet (see
[Not done, planned next](#not-done-planned-next)).

The render models and their contract are in [`MISSILES-MODEL.md`](MISSILES-MODEL.md).

![Tier 4 launch, slowed to 4 ticks per second](docs/missiles/launch-t4.png)

---

## 1. How it works

### Code layout

Everything lives in its own packages. Each package is registered with one line from the matching initialiser.

| Where | What |
|---|---|
| `missile/Missiles` | Registration of the entity type, the two silo blocks and the block entity, plus `init()`. Called once from `SimplePlanesMod.onInitialize` |
| `missile/MissileTier` | Per-tier geometry and flight parameters, and the shared constants |
| `missile/MissileEntity` | The missile |
| `missile/LaunchSiloBlock`, `LaunchSiloCasingBlock` | The master block and the dependent blocks of the multiblock |
| `missile/SiloStructure` | Multiblock geometry: placement checks, placement, integrity check, dismantling |
| `missile/LaunchSiloBlockEntity` | Silo state and the launch sequence |
| `missile/MissileTracker` | Chunk tickets, stall accounting, flight reports, telemetry |
| `missile/MissileFx` | Particles and sounds, and nothing else |
| `missile/MissileCommand` | `/missile` |
| `client/missile/MissilesClient` | Model layers and renderers. Called once from `SimplePlanesClient` |
| `client/missile/MissileRenderer`, `LaunchSiloRenderer` | The entity renderer and the block entity renderer |
| `client/render/MissileRenderState`, `client/render/models/MissileModel`, `LaunchTubeModel` | The ported models |

`SimplePlanesEntities`, `SimplePlanesItems`, `SimplePlanesBlocks` and `PlanesModelLayers` are not touched. The lang
entries are one block of three keys at the end of each lang file.

### The missile entity

`simpleplanes:missile` is one entity type for all four tiers. The tier is synced entity data, and
`getDimensions` returns the cube hitbox that `MISSILES-MODEL.md` suggests for each tier: 0.4, 0.5, 0.625 and 1.0
blocks.

- **Physics.** The kinematics are server-authoritative, and `noPhysics` is set. The authoritative point is the
  centre of the missile's length, `C`, and the entity position is the bottom of the hitbox centred on it. Each
  tick the missile turns its unit direction toward a desired direction by at most the tier's turn rate, sets its
  speed, and moves `C`. The client only interpolates the synced position and rotation, and draws the trail.
- **Collision.** Impact and arrival do not use the hitbox. Every tick, the segment the nose swept is ray-traced
  with `ClipContext.Block.COLLIDER` and `Fluid.ANY`, so leaves and water count as terrain. The closest approach of
  that segment to the target is also measured.
- **Arrival.** The missile arrives when the closest approach is 1.5 blocks or less, and either the target is no
  longer ahead of the nose or the path is blocked. The reported miss is therefore the true closest approach, not
  the first distance under 1.5.
- **Terrain impact.** The flight also ends when the path is blocked anywhere else.
- **What it ignores.** A missile passes through entities without touching them. It cannot be hurt, is immune to
  explosions and fire, uses no portals, triggers no pressure plates and cannot be pushed by pistons. It cannot be
  `/summon`ed (`noSummon`) and is never saved (`noSave`). At a server stop every missile in flight is discarded,
  and a line is written to the log.
- **Ending a flight.** Every ending goes through `MissileEntity.finish`. It calls `MissileFx.puff` (particles and
  a firework sound), writes a report and discards the entity.
- **Render data.** The synced data is `tier`, `fins` (0 to 1), `thrust` (0 to 1) and `booster` (attached or
  not). The renderer copies them into `MissileRenderState`, and the model's hooks read them in `setupAnim`.
  26.3 defers `setupAnim` to draw time, so the hooks are never called by hand.

### The silo multiblock: a master plus dependents

![Tier 4 silo, closed and loaded](docs/missiles/silo-t4-closed.png)

| Tier | Footprint | Depth (layers) | Launch seat below the surface |
|---|---|---|---|
| 1 | 1 x 1 | 2 | 1.25 |
| 2 | 1 x 1 | 3 | 2.25 |
| 3 | 2 x 2 | 4 | 3.25 |
| 4 | 2 x 2 | 5 | 4.25 |

- **Master block.** `simpleplanes:launch_silo` is the top-layer block at the minimum X/Z corner. Its top face is
  the ground surface. It carries the silo tier in its state (`tier=1..4`) and holds the block entity.
- **Dependent blocks.** Every other block of the `footprint x footprint x (tier + 1)` volume is a
  `simpleplanes:launch_silo_casing`. Its state is the offset back to the master (`dx`, `dy`, `dz`), so any part
  finds its master without a search and without a block entity.
- **Placement.** Placement is done with `/missile silo place`. It refuses when:
  - the shaft would reach below the world;
  - the volume overlaps another silo;
  - the volume contains a block entity;
  - the volume contains an unbreakable block, such as bedrock.

  Otherwise the silo replaces what was in its volume, as a structure placement does. The replaced blocks are not
  dropped.
- **Breaking.** Removing any part removes the whole silo: player breaking, `/setblock` and anything else that
  sends neighbour updates. The hook is `affectNeighborsAfterRemoval` on both blocks, with a re-entrancy guard, so
  the cascade runs once. The silo leaves an open shaft behind it. A missile that is still loaded is lost.
- **Collision and opacity.** All silo blocks are full, opaque cubes with an invisible render shape. The block
  entity renderer draws the whole tube. The blocks are opaque on purpose: while they were not, skylight reached
  the dirt under a tier 1 or tier 2 shaft and grass spread onto it. See "Results" below.
- **Light.** The renderer lights the tube with the light of the block above the master.

### The block entity

`LaunchSiloBlockEntity` holds:

- the loaded missile (`loaded_tier`);
- the phase;
- the hatch progress;
- the cooldown;
- the target;
- the mode;
- the launch count and the id of the last missile.

It is synced to the client with the vanilla block entity data packet.

The mode is an enum that has only `MANUAL` for now. It is saved by name, so other modes can be added later without
changing the save format.

**Launch sequence:**

```
IDLE --launch--> OPENING --hatch fully open, missile created--> LAUNCHING (60 t, smoke from the mouth)
     --> CLOSING (30 t) --> COOLDOWN (100 t) --> IDLE
```

- **Before the sequence starts,** `launch` checks all of these:
  - the silo is idle;
  - a missile is loaded;
  - the structure is intact;
  - the horizontal range is inside the tier's minimum and maximum;
  - the target lies within the world's height range;
  - the `tier + 3` blocks above the whole footprint have no collision shape. The hatch has to open and the missile
    has to get out.
- **The hatch.** It opens over 25, 30, 35 or 40 ticks, depending on the tier.
- **The missile.** It is created only once the hatch is fully open. The contract says the open leaves clear the
  folded missile by about 0.1 px.
- **Smoke.** The flame is hidden inside the shaft, so the launch is carried by smoke: `CAMPFIRE_COSY_SMOKE`,
  `LARGE_SMOKE` and `CLOUD` from the mouth, sent with `force` so it is visible from far away.
- **The client.** It runs the same hatch ramp from the synced phase, so the renderer can interpolate it.

### Renderers

- **`MissileRenderer`:**
  - bakes the four `MissileModel` layers (`simpleplanes:missile#t1` to `#t4`);
  - bakes the same layers a second time as flame-only models for an emissive pass with `RenderTypes.eyes`;
  - rotates the model's nose axis onto the interpolated direction, with `Quaternionf.rotationTo(+Y, dir)`;
  - inflates the culling box by the missile length, and draws out to 512 blocks.
- **`LaunchSiloRenderer`:**
  - bakes the four `LaunchTubeModel` layers (`simpleplanes:launch_tube#t1` to `#t4`) and the four missile layers;
  - draws the tube with its hatch and, until the missile entity exists, the stowed missile on the launch seat;
  - draws the crumbling overlay separately, which is how 26.3 wants it;
  - returns `true` from `shouldRenderOffScreen`, because the geometry reaches below the master block and, for
    2x2, into three other columns.

The 26.3 API differences the port ran into are listed at the top of `MISSILES-MODEL.md`.

**Checked on a real 26.3 client.** A client was run under Xvfb with Mesa llvmpipe (OpenGL 4.5 via EGL) and joined
the test server. It drew the following, as the screenshots in `docs/missiles/` show:

- the four tube tiers with their tier bars;
- the clamshell opening;
- the stowed tier-4 missile rising with folded fins;
- the booster, the interstage and the glowing flame;
- the fins deploying;
- the pitch-over with the model oriented along its path, and the smoke trail.

![Tier 2 pitch-over, slowed to 3 ticks per second](docs/missiles/pitchover-t2.png)

---

## 2. Flight profile and parameters

The profile is **climb, cruise, dive.**

1. **Tube.** The missile rises vertically from the seat at 0.04 b/t² (blocks per tick squared), up to 1 b/t
   (block per tick), with its fins folded and full thrust. It does no terrain checks, because it is inside its
   own silo.
2. **Deploy.** Once the base is 0.25 blocks above the surface, the missile keeps rising vertically. It
   accelerates at the tier's rate while the fins deploy over 6 ticks.
3. **Midcourse.** The missile steers toward the target's bearing. Its pitch is aimed at the desired altitude over
   a look-ahead of 15 ticks of travel, clamped to between 50° up and 30° down. The desired altitude is the higher
   of these two:
   - the cruise altitude: the higher of the silo mouth and the target, plus the tier's cruise height;
   - the highest ground ahead plus 12 blocks. The ground is read from the `MOTION_BLOCKING` heightmap every
     8 blocks along 30 ticks of travel, and only from chunks that are already loaded.

   The turn rate limits every change of direction, so the pitch-over from vertical is a smooth arc.
4. **Terminal.** The missile dives when the target is 45° or more below its line of sight **and** that line is
   clear. It also dives when it is within 2 blocks of being overhead. Without the line-of-sight condition, a
   missile over real terrain clipped a tree 8 blocks short of its target. The dive is pure pursuit from the nose.
   The turn limit is the larger of the tier's terminal rate and `2.2 · speed / distance`: the turn radius stays
   under half the remaining distance, so the missile cannot orbit its target. In the last 1.5 ticks it aims
   straight at the point.
5. **Tier 4 staging.** 60 ticks after leaving the tube, the booster is dropped. It disappears in a small cloud,
   the model hides the `Booster` part, and the flame moves to the sustainer nozzle.

| | T1 | T2 | T3 | T4 |
|---|---|---|---|---|
| Length (blocks) | 1 | 2 | 3 | 4 |
| Hitbox | 0.4 | 0.5 | 0.625 | 1.0 |
| Cruise speed (b/t) | 2.0 | 2.5 | 3.0 | 4.0 |
| Acceleration after the tube (b/t²) | 0.10 | 0.10 | 0.10 | 0.12 |
| Maximum range (blocks, horizontal) | 1200 | 2500 | 5000 | 10000 |
| Minimum range | 24 | 32 | 48 | 64 |
| Cruise height above the higher end | 24 | 32 | 48 | 64 |
| Midcourse turn rate (°/t) | 6 | 5 | 4 | 3.5 |
| Terminal turn rate, minimum (°/t) | 12 | 10 | 9 | 8 |
| Hatch opening (t) | 25 | 30 | 35 | 40 |
| Booster | none | none | none | dropped 60 t after the tube |

These limits apply to every tier:

- **Motor.** It burns for a path of `1.3 × range + 200` blocks. After that the flight ends as `FUEL`.
- **Lifetime.** The limit is `1.3 × range / speed + 600` ticks. After that the flight ends as `TIMEOUT`.
- **Height.** The flight ends as `OUT_OF_WORLD` more than 16 blocks below the world or 256 blocks above it.
- **Arrival radius.** 1.5 blocks.

**Why this profile.** A lofted ballistic arc would need very different trajectories from 24 blocks to 10 km. A
cruise at a fixed height over the higher end has three advantages:

- it keeps flights predictable;
- it lets the missile see and climb over terrain on the way;
- it makes the final approach a steep dive, which a ridge or a tree next to the target rarely masks.

---

## 3. Chunk loading

`MissileTracker` renews `TicketType.ENDER_PEARL` tickets from `ServerTickEvents.START_LEVEL_TICK`, using public API
only. The ticket type has a 40-tick timeout, simulates, and keeps the dimension active. The renewals run over
strong references, so a missile that has stopped ticking is still found and thawed.

| What | Ticket |
|---|---|
| each missile | radius 3 on its own chunk, every tick: its 3x3 chunks entity-tick and 7x7 stay resident |
| ahead of each missile | radius 3 at 10 and at 20 ticks of travel ahead, every tick. The ground ahead is generated and entity-ticking before the missile arrives, and the terrain look-ahead has real heightmaps to read |
| each silo in a launch sequence | radius 3, every 10 ticks, from the launch command until the silo is idle again |

- **Stalls.** A tick in which a tracked missile did not run is counted as a stall and reported.
- **Watchdog.** A missile that misses 200 ticks in a row is ended where it hangs, as `STALLED`, instead of staying
  frozen.
- **Force-loading.** None is needed, and the tests used none on the flight path.

---

## 4. Commands

Every subcommand is under `/missile`. They all need **permission level 2** (`Commands.LEVEL_GAMEMASTERS`) and they
all run from the server console, since no subcommand needs a player. Output goes to the command source, which on
the console is the server log. Positions accept `~` and `^`. For a silo, `pos` may be **any part** of it, except
in `place`, where it is the master.

| Command | Effect | Example |
|---|---|---|
| `/missile silo place <pos> <tier> [loaded]` | Builds a silo of tier 1–4 with its master (the top layer, minimum X/Z corner) at `pos`. The shaft goes `tier` blocks down from there. Placement is checked as described in §1. `loaded` defaults to false | `/missile silo place 0 -20 0 1 true` |
| `/missile silo remove <pos>` | Removes the whole silo. The shaft is left open | `/missile silo remove 0 -21 0` |
| `/missile silo load <pos>` | Loads a missile of the silo's tier. Refused if a missile is already loaded or a launch is under way | `/missile silo load 10 -20 20` |
| `/missile silo unload <pos>` | Removes the loaded missile | `/missile silo unload 10 -20 20` |
| `/missile silo status <pos>` | Prints the tier, loaded or empty, the phase, the hatch, the mode, the cooldown, the launch count, the last missile id and the target, and flags a damaged structure | `/missile silo status 0 -20 0` |
| `/missile launch <silo> <target>` | Starts the launch sequence toward the point `target` (x y z; integer x and z are centred on the block). Refused with the reason for anything in §1's check list | `/missile launch 0 -20 0 500 -19 0` |
| `/missile list` | One telemetry line per missile in flight | `/missile list` |
| `/missile report [count]` | The last `count` (default 10, max 64) flight reports since the server started | `/missile report 4` |
| `/missile abort all` / `/missile abort <id>` | Ends one or all flights with the usual harmless puff (outcome `ABORTED`) | `/missile abort 1445` |
| `/missile telemetry <interval>` | Logs a telemetry line for each missile every `interval` ticks (0 = off, max 1200) | `/missile telemetry 10` |
| `/missile hash <from> <to> [census]` | FNV-1a 64-bit hash of every block state in the box, plus the block and non-air counts (max 16,777,216 blocks). `census` also lists every block state with its count. The ids are runtime block-state ids, so compare hashes within one server session. Loads or generates chunks as it reads them | `/missile hash -3 -30 -3 4 10 4 census` |
| `/missile tickets <true\|false>` | **Test switch.** Turns the missiles' chunk tickets off or on. It resets to on at every server start | `/missile tickets false` |

**Report line**, one per flight, to the log and to `/missile report`:

```
[missile] #1290 T1 ARRIVED at -839.50,-19.00,840.50 target -839.50,-19.00,840.50 miss=0.00 closest=0.00
          flight=620t total=645t (32.3s) flown=1211.2 range=1187.9 max_y=5.0 stalls=0 silo=0, -20, 0
```

- `flight` is counted from ignition.
- `total` is counted from the launch command, so it includes the hatch opening. `total` is the launch-to-arrival
  time.
- The outcome is one of `ARRIVED`, `TERRAIN`, `FUEL`, `TIMEOUT`, `OUT_OF_WORLD`, `STALLED`, `ABORTED` or
  `REMOVED`. `REMOVED` means removed by something else, such as `/kill`.

**Telemetry line:** `#id Tn t=<ticks> <phase> pos=x,y,z spd=<b/t> pitch=<deg> agl=<height above ground>
to_go=<horizontal> dist=<nose to target> flown=<path> fins=<0..1> booster=on|off|- stalls=<n>`.

---

## 5. Test procedure

The rig is a headless dedicated server of our own, kept outside the repository at `/home/user/sp-missiles-server`:

- **Server.** Port 25690, no RCON, `-Xmx2G`, Fabric loader 0.19.5, Fabric API 0.160.5+26.3 and the built jar.
  It has the same `start.sh`, `cmd.sh` and `stop.sh` FIFO console as `TESTING.md` §3.
- **World.** Superflat with a deep crust: bedrock, 40 stone, 3 dirt, grass. The surface is at y = −19, deep
  enough for a tier-4 shaft. Seed 20260926, creative, `spawn_mobs false`, `pause-when-empty-seconds=0`.
- **A second world.** A noise world with the same seed, for real terrain and real chunk generation.
- **The driver.** A small Python driver sends console commands and waits on log lines.

Silos: T1 at `0 -20 0`, T2 at `0 -20 20`, T3 at `10 -20 0`, T4 at `10 -20 20`. The silo area is force-loaded
(`forceload add -8 -8 40 40`) only so that mobs placed next to the silos stay visible to `@e`. The flight path is
never force-loaded.

1. **Arrivals with no-change checks** (10 rounds × 4 tiers, all four tiers fired together in each round):
   - **Targets.** Ranges run from twice the minimum up to 800 blocks, on a different bearing every round. Even
     rounds aim at the ground surface (y = −19); odd rounds aim at mid-air points (y = −11 to +13).
   - **Before each launch:**
     - force-load the target chunk;
     - summon four NoAI pigs at the target: one exactly on it, the others 1.4, 2 and 3 blocks away;
     - summon one NoAI pig beside the silo;
     - hash a 33x61x33 box around the target (66,429 blocks) and an 8x41x8 box around the silo (2,624 blocks);
     - read `Health` and `Fire` of every pig.
   - **After each report:** hash and read the same again, and compare.
2. **Maximum range** per tier, on bearings where no chunk had ever been generated, and without force-loading.
3. **Terrain early:**
   - a stone wall 130 high and 41 wide, 60 blocks out on a T1 path;
   - a target buried 6 blocks underground (T3);
   - a 61x61 stone roof 10 blocks over a T4 target;
   - as a control, a 50-high ridge that a T2 can see and climb over.

   Each case gets the same hashes and pigs. The walls are built from the grass layer up, so that grass dying
   under the new stone does not show up in the hashes.
4. **Corridor hashes** over the whole path of a 1100-block T1 flight (`x −3..1110, y −25..30, z −3..3`) and a
   2020-block T4 flight (`x 7..15, y −25..60, z −2010..25`), before and after.
5. **Chunk loading under stress:**
   - two long flights under `/tick sprint`;
   - flights over a noise world;
   - a negative control with `/missile tickets false`.
6. **Behaviour checks:**
   - a hatch obstructed by a block;
   - a launch while the silo is busy;
   - out of range and inside the minimum range;
   - `/summon` refused;
   - breaking a casing removes the silo;
   - placement on bedrock refused;
   - a server stop mid-flight, then a restart;
   - `/missile abort`.

---

## 6. Results

Final build, commit `66a7c9c` plus the telemetry cosmetic. Times are game ticks at 20 TPS.

### Arrival (40 flights, flat world)

**All 40 arrived. The miss distance was 0.00 blocks in every flight**, against a target of 1.5 or better. The
final 1.5 ticks aim straight at the point, so the swept segment passes through it.

Launch to arrival, measured from the launch command (flight ticks in brackets):

| Range | T1 | T2 | T3 | T4 |
|---|---|---|---|---|
| shortest tested (48 / 64 / 96 / 128) | 75 t, 3.8 s (50) | 86 t, 4.3 s (56) | 103 t, 5.2 s (68) | 113 t, 5.7 s (72) |
| ≈ 300 | 202 t, 10.1 s (177) | 186 t, 9.3 s (156) | 183 t, 9.2 s (148) | 171 t, 8.6 s (130) |
| ≈ 500 | 287 t, 14.4 s (262) | 252 t, 12.6 s (222) | 236 t, 11.8 s (201) | 209 t, 10.5 s (168) |
| 800 | 457 t, 22.9 s (432) | 385 t, 19.3 s (355) | 342 t, 17.1 s (307) | 285 t, 14.3 s (244) |

The hatch accounts for the first 25, 30, 35 or 41 ticks.

### Maximum range, over ground never generated, no force-loading

| Tier | Range | Flight | Launch to arrival | Path flown | Miss | Stalls |
|---|---|---|---|---|---|---|
| T1 | 1187.9 | 620 t | 645 t (32.3 s) | 1211 | 0.00 | 0 |
| T2 | 2474.9 | 1021 t | 1051 t (52.6 s) | 2507 | 0.00 | 0 |
| T3 | 4984.4 | 1698 t | 1733 t (86.7 s) | 5029 | 0.00 | 0 |
| T4 | 9985.1 | 2537 t | 2578 t (128.9 s) | 10046 | 0.00 | 0 |

The same four flights on other bearings, with an earlier build, gave identical times and zero stalls.

### Long flights and chunk loading

- **Noise world, real terrain, zero stalls, all arrived with a miss of 0.00:**
  - T1 over 1000 blocks: 557 t;
  - T4 over 3000 blocks: 833 t;
  - T4 over 3663 blocks toward a jagged-peaks biome: 998 t.
- **Under `/tick sprint`,** which ran at 112 TPS:
  - T4 over 5811 blocks and T3 over 4250 blocks, together;
  - both arrived, with zero stalls.
- **Negative control, tickets off.** A T2 froze at x = 49.4, the edge of the force-loaded silo area. It missed
  201 ticks in a row and was ended as `STALLED`, with the usual puff. This shows both that the tickets are what
  carry a missile and that the stall counter and the watchdog work.

### Hitting terrain early

| Case | Outcome |
|---|---|
| T1, wall 130 high at 60 blocks | `TERRAIN` on the wall face at (60.0, 4.7, 0.5) after 52 flight ticks; the target was 241.7 blocks further on |
| T3, target 6 blocks underground | `TERRAIN` on the surface 6.92 blocks from the target. The line of sight never clears, so it dives from overhead |
| T4, roof 10 blocks over the target | `TERRAIN` on the roof, 13.38 blocks from the target |
| T2, 50-high ridge on the path (control) | Climbed over it with 7.7 blocks to spare and `ARRIVED`, miss 0.00 |

### Nothing changed, nothing hurt

- **Blocks.** All 44 target-box and silo-box hash pairs were equal: 40 arrivals and the 4 terrain cases. Both
  corridor hashes (46,788 and 109,944 non-air blocks) were equal after their flights.
- **Mobs.** 5 pigs per arrival, 200 readings in all, plus 2–3 per terrain case. `Health` was 10.0 and `Fire` was
  0, before and after, including the pig standing exactly on the target point.
- **A real bug the hashes caught, and fixed.** The first campaign found one silo box that had changed. A census
  (`/missile hash … census`) showed one more grass block and one less dirt block. The cause was the silo blocks'
  `noOcclusion`: skylight came down the shaft and grass spread to the dirt under tier 1 and tier 2 silos. The
  missiles had nothing to do with it. The silo blocks are opaque now, and 30 s at `random_tick_speed 1000`
  (roughly 3 hours of normal random ticks) changes nothing.

### Behaviour checks, all as intended

- **Refusals, each with its reason:**
  - an obstructed hatch;
  - a launch while busy;
  - out of range;
  - inside the minimum range;
  - an overlapping silo;
  - bedrock in the way;
  - a shaft below the world.
- **`/summon simpleplanes:missile`** fails with "Can't summon entity".
- **`/setblock` on a casing** removes the whole silo.
- **A server stop mid-flight** discards both flights and logs it. After the restart the silos resume their
  sequence: closing, then cooldown.
- **`/missile abort all`** ends the flight with `ABORTED` and a puff.

---

## 7. Known limitations

- **No item.** Silos are placed and loaded by command only.
- **Removing a silo** leaves an open shaft, and the blocks it replaced are not given back.
- **Missiles do not survive a restart.** Flights in progress are discarded at a server stop. A silo in the middle
  of a launch sequence is saved and finishes the sequence when its chunk next ticks. Its chunk ticket is not
  re-created at startup.
- **Terrain following.** It only sees chunks that are already loaded, which the lead tickets normally provide. It
  climbs at most 50°, so a sheer wall taller than the missile can out-climb is hit (by design, see the test). There
  is no lateral avoidance: the missile goes over terrain, never round it.
- **The dive needs a clear line of sight.** A target under a roof or underground is reached from overhead, and
  ends as `TERRAIN` on whatever covers it.
- **Accuracy.** The miss is 0.00 because the final aim is exact. Nothing models guidance error.
- **Speed.** Missiles fly through entities, and the tier-4 cruise speed of 4 b/t is near the upper end of what a
  client renders smoothly.
- **Client check.** The client was checked with llvmpipe under Xvfb at low tick rates. Lighting at night and in
  deep shafts was not examined.

## Not done, planned next

**Interception.** None of it is implemented yet:

- an intercept mode on the silo with a detection radius;
- automatic interceptor launch against missiles that are not the owner's;
- proximity kills that remove both missiles with a harmless puff;
- a command to launch an interceptor at a given missile;
- a filter that restricts targets to missiles;
- success-rate measurements per tier.

The groundwork is in place:

- the silo's mode field is a saved enum;
- each missile records its launching silo;
- `/missile list` and the telemetry already give the per-missile state an interceptor would need;
- every ending already goes through one harmless `finish` path.
