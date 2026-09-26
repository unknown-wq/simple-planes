# Missiles and launch silos

Ground-launched missiles in four tiers, fired from silos sunk into the ground. A missile rises out of its
silo with its fins folded, deploys them clear of the tube, turns toward the target, cruises and dives on it.

**Phase 2 (this build)** adds two things:

- **The silo item** (§1a). One item, `simpleplanes:launch_silo`, builds a tier 1 silo, and each further item
  used on the silo raises it one tier, up to 4. Breaking it gives back one item per tier and puts the ground back.
- **Warheads** (§1b). A missile that arrives, or hits terrain on the way, detonates with a blast that depends on
  its tier, up to `Blast.MAX_POWER` for tier 4. Every detonation goes through `Blast#detonate`, the same path
  as the strike aircraft, so blast guards (claims, protection) apply to missiles exactly as to aircraft. The game
  rule `simpleplanes:missile_explosions` (default `true`) switches warheads off and restores the phase 1
  behaviour: a puff of smoke and particles, nothing broken, nobody hurt.

Loading and launching are still commands. Interception is not implemented yet (see
[Not done, planned next](#not-done-planned-next)).

The render models and their contract are in [`MISSILES-MODEL.md`](MISSILES-MODEL.md).

![Tier 4 launch, slowed to 4 ticks per second](docs/missiles/launch-t4.png)

---

## 1. How it works

### Code layout

Everything lives in its own packages. Each package is registered with one line from the matching initialiser.

| Where | What |
|---|---|
| `missile/Missiles` | Registration of the entity type, the two silo blocks, the block entity, the silo item, the `missile_explosions` game rule and the creative tab entry, plus `init()`. Called once from `SimplePlanesMod.onInitialize` |
| `missile/MissileTier` | Per-tier geometry, flight parameters and warhead (`warhead`, a `Blast`), and the shared constants |
| `missile/MissileEntity` | The missile |
| `missile/LaunchSiloBlock`, `LaunchSiloCasingBlock` | The master block and the dependent blocks of the multiblock |
| `missile/SiloStructure` | Multiblock geometry: placement checks, placement, upgrade, integrity check, dismantling and restoring the ground, break drops |
| `missile/LaunchSiloItem` | The silo item: place a tier 1 silo, or upgrade the silo it is used on |
| `missile/LaunchSiloBlockEntity` | Silo state and the launch sequence |
| `missile/MissileTracker` | Chunk tickets, stall accounting, flight reports, telemetry |
| `missile/MissileFx` | Particles and sounds, and nothing else |
| `missile/MissileCommand` | `/missile` |
| `missile/MissileTestGuard`, `SiloTestPlayer` | Test aids behind `/missile guard` and `/missile item` |
| `autopilot/Blast#detonate` | The one detonation path, shared with the aircraft: blast guards, then `Level#explode` |
| `client/missile/MissilesClient` | Model layers and renderers. Called once from `SimplePlanesClient` |
| `client/missile/MissileRenderer`, `LaunchSiloRenderer` | The entity renderer and the block entity renderer |
| `client/render/MissileRenderState`, `client/render/models/MissileModel`, `LaunchTubeModel` | The ported models |

`SimplePlanesEntities`, `SimplePlanesItems`, `SimplePlanesBlocks` and `PlanesModelLayers` are not touched; the
silo item joins the planes creative tab through Fabric's `CreativeModeTabEvents`. Outside the missile packages,
phase 2 changes one method: `PlaneEntity#explode` now calls `Blast#detonate` instead of running the guards and
`Level#explode` itself. The phase 1 lang entries are one block of three keys at the end of each lang file; the
phase 2 keys (item tooltip, item messages, game rule name) are in `en_us.json` only, and other languages fall
back to English.

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
  a firework sound), then, for `ARRIVED` and `TERRAIN` only, the warhead (§1b), writes a report and discards the
  entity.
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
- **Placement.** With the item (§1a), or with `/missile silo place`. The command refuses when:
  - the shaft would reach below the world;
  - the volume overlaps another silo;
  - the volume contains a block entity;
  - the volume contains an unbreakable block, such as bedrock.

  Otherwise the command replaces what was in its volume, as a structure placement does. The item is stricter
  (§1a).
- **What the silo displaced.** Every silo, whether built by the item or the command, records in its block entity
  the block state it replaced at each position of its volume, including air and water. Upgrades add the new
  positions to the record, and a master that moves hands the record on.
- **Breaking.** Removing any part removes the whole silo: player breaking, `/setblock`, `/missile silo remove`
  and anything else that sends neighbour updates. The hook is `affectNeighborsAfterRemoval` on both blocks, with
  a re-entrancy guard, so the cascade runs once. **Every position of the silo gets back the block it displaced**,
  bottom layer first, so sand and gravel land on what they stood on before. The part whose removal started the
  cascade is filled on the next level tick, not inside the removal, so that the vanilla break still counts as a
  break (drops, statistics, Fabric's break events). Anything else placed there in the meantime, such as the stone
  of a `/setblock`, is kept. When the master itself is the part removed, its block entity is already gone, so it
  hands its record over in `preRemoveSideEffects`. A silo placed by the phase 1 build has no record; its
  positions are filled with dirt, so no shaft is left open either way. A missile that is still loaded is lost.
- **Break drops.** A player breaking any part in survival gets back one silo item per tier (1 to 4), dropped at
  the silo mouth. A creative player, `/setblock`, `/missile silo remove` and other non-player removals drop
  nothing. The `block_drops` game rule applies.
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

### 1a. The silo item

`simpleplanes:launch_silo` ("Launch Silo") stacks to 64 and is in the planes creative tab. Its tooltip says how
to use it. One item is one tier level: a tier 4 silo is four items.

**Recipe** (shaped, 1 item):

```
i R i      i = iron ingot (#c:ingots/iron)
S   S      R = redstone dust (#c:dusts/redstone)
S i S      S = minecraft:smooth_stone
```

**Model.** `item/generated` with a 16x16 placeholder texture drawn for this (a cut-away silo with a missile in
the ground). It has not been looked at on a client.

**Placing.** Use the item on the **top face of the ground**. The clicked block becomes the master, so the silo's
top is flush with the ground, and the tier 1 shaft goes one block further down. Clicking a plant or a snow layer
uses the block under it. A side or bottom face is refused.

**Upgrading.** Use the item on **any part of an existing silo**, in practice its top face, the only part that
shows. Each item raises the silo one tier. This is the interaction chosen for "stacking", for these reasons:

- The click identifies the silo exactly, whichever part and face is hit, because every casing points at its
  master. Nothing has to be guessed from where a block would land.
- "Placing it adjacent above" would put a block in the hatch's airspace, which has to be clear to launch; "below"
  is underground and cannot be clicked. Adjacent placement would also be ambiguous with building a second silo
  next to the first.
- Using an item on a thing to improve it is ordinary vanilla behaviour (bone meal, for example).

| Step | What changes | New positions dug |
|---|---|---|
| 1 → 2 | one layer deeper, same 1 x 1 | 1 |
| 2 → 3 | the 1 x 1 column becomes one corner of a 2 x 2 footprint, 4 deep | 13 |
| 3 → 4 | one layer deeper, same 2 x 2 | 4 |

**The 2 → 3 footprint.** Tier 3 and 4 missiles do not fit a 1 x 1 bore, so the footprint has to grow. The old
column is kept and becomes one corner of the new 2 x 2 square. There are four such squares; they are tried in
order, best first, and the first one whose new positions all pass the space check below is built:

1. the square on the side the player is **facing** (the horizontal look direction; the command uses
   south-east);
2. the two squares that flip one axis, the less-looked-at axis first;
3. the opposite square.

The message says which way the shaft grew ("grew south-west"). If all four are blocked, the upgrade is refused
with the reason found for the preferred square, and nothing changes. The master/casing invariant holds after
every step. The master is always the minimum X/Z corner of the top layer. When the chosen square puts the old
column at another corner, the master moves: the old master becomes a casing, and the new master's block entity
takes over the mode, the launch count, the last missile id, the last target and the displaced-block record. The
tests check `isIntact` after every step (§6a).

**Space check.** It applies to item placement and to every upgrade, and only to the positions that would be
dug. Each position must be:

- inside the world;
- not part of another silo;
- not a block entity;
- breakable;
- either replaceable (air, water, lava, plants, a snow layer) or **natural ground**, in the block tag
  `#simpleplanes:silo_ground`. That tag holds the vanilla tags `substrate_overworld` (dirt, grass, podzol,
  mycelium, mud, moss, ...), `base_stone_overworld`, `base_stone_nether`, `sand`, `terracotta`, `snow` and `ores`,
  plus gravel, clay, sandstone, red sandstone, calcite, dripstone block, end stone, soul sand and soul soil;
- a position the player may edit (`Level#mayInteract`: spawn protection, world border).

No living entity may be inside the new positions either. Planks, cobblestone, concrete, glass, farmland, paths,
and anything else built are never dug, so a floor or a wall next to a silo blocks the upgrade instead of being
eaten. A refusal names the first blocking block and its position, the item is not used up, and not one block
changes. What *is* dug is not destroyed: it is recorded and put back when the silo is removed. It is not dropped
as items either, so a silo cannot be used to mine or to duplicate anything.

**Refused while the silo is in use:**

- a missile loaded ("a missile is loaded; unload it first", `/missile silo unload`);
- any phase other than idle: opening, launching, closing or cooldown;
- a damaged structure;
- tier 4 already.

`/missile silo upgrade <pos>` does the same upgrade from the console, with the same rules and no player.

### 1b. Warheads

A missile that ends its flight `ARRIVED` or `TERRAIN` detonates. The values are the `warhead` field of
`MissileTier`, one `Blast` per tier, and nothing else holds them:

| Tier | Power | Block damage | Fire | Damage radius (2 x power) |
|---|---|---|---|---|
| 1 | 2.0 | yes | no | 4 |
| 2 | 4.0 (`Blast.DEFAULT_POWER`, TNT) | yes | no | 8 |
| 3 | 8.0 | yes | yes | 16 |
| 4 | 16.0 (`Blast.MAX_POWER`) | yes | yes | 32 |

This is the suggested table, unchanged:

- power doubles per tier;
- tier 2 is exactly TNT, what a plane has always exploded with;
- tier 4 is the ceiling `Blast` already clamps every strike aircraft to, for cost reasons;
- the two small tiers leave no fire, so they can be used near one's own builds; the two big ones are incendiary.

Measured sizes are in §6a.

**One path for every blast.** `MissileEntity.detonate` calls `Blast#detonate(level, missile, centre)`. That
method runs `BlastGuards.filter`, which respects `/blastguard off`, and then `ServerLevel#explode(source, x, y,
z, power, fire, interaction)`. `PlaneEntity#explode` now calls the same method, so aircraft and missiles cannot
drift apart. A guard sees the missile as the `source` entity and may downgrade or suppress the blast as for an
aircraft.

- **The centre** is the target point on arrival. On a terrain hit it is the hit point moved 0.05 blocks back
  along the flight path, so that the blast starts in the air cell in front of the face and not inside the block.
- **The missile** is immune to explosions and is removed right after.
- **What does not detonate.**
  - `FUEL`, `TIMEOUT`, `OUT_OF_WORLD`: the missile never hit anything.
  - `STALLED`: the watchdog removing a frozen missile is not an impact.
  - `ABORTED`, `REMOVED`, a server stop.
  - Tier 4 staging and the dropped booster: particles only, as before.

  Each of these ends with the puff alone.
- **Entities.** Missiles still pass through entities without touching them. The blast hurts them.

**The harmless switch** is a game rule, registered through Fabric's game rule API:

```
/gamerule simpleplanes:missile_explosions false     # warheads off: phase 1 behaviour, a puff and nothing else
/gamerule simpleplanes:missile_explosions true      # the default
```

It is stored in the world like any game rule, can be set on the world creation screen (under "Misc"), and takes
effect on the next detonation. With it off, `Blast` is never called and no guard is asked. Tests and builds that
need an unchanged world set it first.

**The report** carries a `blast=` field (§4): what was applied and the time spent in the explode call, a
`guarded:` prefix when a guard changed the blast, or `suppressed`, `inert` (the game rule is off) or `none` (the
ending does not detonate).

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
| `/missile silo upgrade <pos>` | Raises the silo one tier with the item's rules (§1a), preferring south-east for 2 → 3. Refused with the reason | `/missile silo upgrade 0 -20 0` |
| `/missile silo remove <pos>` | Removes the whole silo and puts back the blocks it displaced. No items drop | `/missile silo remove 0 -21 0` |
| `/missile silo load <pos>` | Loads a missile of the silo's tier. Refused if a missile is already loaded or a launch is under way | `/missile silo load 10 -20 20` |
| `/missile silo unload <pos>` | Removes the loaded missile | `/missile silo unload 10 -20 20` |
| `/missile silo status <pos>` | Prints the tier, loaded or empty, the phase, the hatch, the mode, the cooldown, the launch count, the last missile id and the target, and flags a damaged structure | `/missile silo status 0 -20 0` |
| `/missile launch <silo> <target>` | Starts the launch sequence toward the point `target` (x y z; integer x and z are centred on the block). Refused with the reason for anything in §1's check list | `/missile launch 0 -20 0 500 -19 0` |
| `/missile list` | One telemetry line per missile in flight | `/missile list` |
| `/missile report [count]` | The last `count` (default 10, max 64) flight reports since the server started | `/missile report 4` |
| `/missile abort all` / `/missile abort <id>` | Ends one or all flights with the harmless puff and no warhead (outcome `ABORTED`) | `/missile abort 1445` |
| `/missile telemetry <interval>` | Logs a telemetry line for each missile every `interval` ticks (0 = off, max 1200) | `/missile telemetry 10` |
| `/missile hash <from> <to> [census]` | FNV-1a 64-bit hash of every block state in the box, plus the block and non-air counts (max 16,777,216 blocks). `census` also lists every block state with its count. The ids are runtime block-state ids, so compare hashes within one server session. Loads or generates chunks as it reads them | `/missile hash -3 -30 -3 4 10 4 census` |
| `/missile hash <from> <to> snapshot` / `... diff` | **Test.** `snapshot` remembers every block state in the box (one snapshot at a time, same size limit). `diff`, on exactly the same box, counts the blocks that changed since and lists the transitions (`dirt -> air`, `air -> fire`, ...) | `/missile hash 170 -40 -28 228 0 28 diff` |
| `/missile tickets <true\|false>` | **Test switch.** Turns the missiles' chunk tickets off or on. It resets to on at every server start | `/missile tickets false` |
| `/missile item use <pos> <face> <yaw> [count]` | **Test.** A survival fake player (Fabric `FakePlayer`) holding `count` (default 1) silo items and facing `yaw` (vanilla yaw: 0 = south, -90 = east; pitch 45 down) uses them on `face` (`up`, `north`, ...) of `pos`, through the vanilla `ServerPlayerGameMode#useItemOn` path. Prints accepted or refused, the items left, the action-bar message and the silo's status | `/missile item use 0 -20 0 up -45` |
| `/missile item break <pos>` | **Test.** The same fake player breaks the block at `pos` in survival, through `ServerPlayerGameMode#destroyBlock`. Prints what was there, what is there now, and the silo items and other items dropped | `/missile item break 0 -22 0` |
| `/missile item recipe` | **Test.** Looks the recipe up from its ingredients, as a crafting table does, and prints the recipe id and its result | `/missile item recipe` |
| `/missile guard add <from> <to> [suppress]` | **Test.** Registers (on first use) a `BlastGuard` that stands in for a claim mod. A blast whose damage radius (2 x power) reaches the box loses its block damage and fire, or with `suppress` is cancelled outright. It applies to aircraft and missiles alike. Zones are forgotten at a restart; the guard stays registered until then | `/missile guard add 230 -64 230 270 319 270` |
| `/missile guard clear` / `/missile guard list` | **Test.** Removes or lists the zones | `/missile guard clear` |

Outside `/missile`:

| Command | Effect |
|---|---|
| `/gamerule simpleplanes:missile_explosions <true\|false>` | Warheads on (default) or off (harmless, phase 1 behaviour). §1b |
| `/blastguard on\|off\|status` | The existing switch for the blast guard chain; it applies to missiles as to aircraft |

**Report line**, one per flight, to the log and to `/missile report`:

```
[missile] #101 T4 ARRIVED at 10.50,-19.00,-199.50 target 10.50,-19.00,-199.50 miss=0.00 closest=0.00 flight=93t
          total=134t (6.7s) flown=270.3 range=210.5 max_y=42.5 stalls=0 silo=10, -20, 10 blast=16.0,blocks,fire,66.0ms
```

- `flight` is counted from ignition.
- `total` is counted from the launch command, so it includes the hatch opening. `total` is the launch-to-arrival
  time.
- The outcome is one of `ARRIVED`, `TERRAIN`, `FUEL`, `TIMEOUT`, `OUT_OF_WORLD`, `STALLED`, `ABORTED` or
  `REMOVED`. `REMOVED` means removed by something else, such as `/kill`.
- `blast` is the warhead. It is `<power>[,blocks][,fire],<ms>`, where `ms` is the time spent in the explode call.
  `guarded:` in front means a blast guard changed it. It can also read `suppressed` (a guard cancelled it),
  `inert` (the game rule is off) or `none` (the ending does not detonate).

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

### Phase 2 rig and procedure

A second server of our own, `/home/user/sp-missiles-2-server` (outside the repository):

- **Server.** Port 25692, no RCON, `-Xmx1536M`, the same loader, Fabric API and FIFO console.
- **World.** A fresh world with the same superflat generator and seed (surface at y = −19), `spawn_mobs false`, and
  `forceload add -100 -100 100 100` for the silo area only.
- **Silos.** Built with the item through `/missile item use` (yaw −45, facing south-east): T1 at `0 -20 0`, T2 at
  `0 -20 10`, T3 at `10 -20 0`, T4 at `10 -20 10`.
- **Scripts.** `tests/silo_item.py`, `tests/blast.py build|live|inert`, `tests/protect.py` and `tests/endings.py`.
  They use the phase 1 driver, `mt.py`.

1. **Silo item.**
   - Build a silo of each tier step by step; check the tier and `intact` after every step.
   - Break it with `/missile item break`: the master for T1 and T4, the bottom casing for T2, a casing of a grown
     column for T3. Count the drops.
   - Compare a 640-block hash box with the one taken before placement.
   - Refusals, each with a hash before and after:
     - oak planks on all eight surface neighbours of a T2;
     - then only the south-west corner freed, while facing south-east: the upgrade should fall back to it;
     - a chest under a T1;
     - a pig in an air pocket in the T4 layer;
     - a side face;
     - a planks surface;
     - loaded, opening and cooldown, then idle.
   - The recipe lookup.
   - Command placement and removal.
2. **Warheads.**
   - One flight per tier onto flat ground about 200 blocks out.
   - Before each flight:
     - force-load the target area;
     - `snapshot` a 57 x 41 x 57 box (133,209 blocks, y −40..0) around the target;
     - place five NoAI iron golems (100 health, full knockback resistance, so they stay put) 3, 6, 10, 16 and 24
       blocks from the target along +X.
   - After the report:
     - `tick query`, whose P99 over the last 100 ticks is the detonation tick;
     - `diff` for the blocks changed and the fires;
     - the golems' health (0 = dead).
3. **Harmless mode.** The same with `missile_explosions false`, at fresh targets.
4. **Protection.** `/missile guard` zones of 41 x 41 columns over the target:
   - tier 4 missiles into a downgrade zone and a suppress zone;
   - the same for a strike aircraft with the same warhead
     (`/autopilot strike <target> 300 0 16 true true`);
   - a missile with the zone still registered but `/blastguard off`;
   - an unguarded strike as a control;
   - three more unguarded tier 4 shots for the tick cost.
5. **Other endings.**
   - A T1 into a 130-high stone wall 60 blocks out.
   - `/missile abort all` mid-flight.
   - A watchdog stall with `/missile tickets false`.

---

## 6. Results

§6a is phase 2, measured on the code of commit `7db6dbe`. The subsections after it are phase 1, measured on
commit `66a7c9c`, before warheads existed. Phase 2 does not touch the flight code. The phase 1 "nothing changed"
results describe what the harmless mode still does, and §6a confirms it. Times are game ticks at 20 TPS.

### 6a. Phase 2: silo item, warheads, protection

**Silo item, all as intended.**

| Tier | Steps (item uses) | After every step | Part broken | Silo items dropped | 640-block box after the break |
|---|---|---|---|---|---|
| 1 | 1 | right tier, intact | master | 1 | identical to before placement |
| 2 | 2 | right tier, intact | bottom casing | 2 | identical |
| 3 | 3 (grew south-east) | right tier, intact | casing of a grown column | 3 | identical |
| 4 | 4 | right tier, intact | master | 4 | identical |

- **Every step used exactly one item. A refusal used none.**
- **Blocked 2 → 3.** With oak planks on the eight surface neighbours, the upgrade was refused: "minecraft:oak_planks
  at 0, -20, -39 is not natural ground". The hash was unchanged. With only the south-west corner freed and the
  player facing south-east, it grew south-west. The master moved from `0 -20 -40` to `-1 -20 -40` and the silo
  was intact. Breaking it dropped 3 items and restored everything: the hash was identical once the planks were
  put back on the freed corner.
- **Other refusals, hash unchanged each time:**
  - a chest under a T1: "minecraft:chest at 20, -22, -40 is a block entity";
  - a pig in the T4 layer: "Pig is in the way at 41, -24, -40", accepted once the pig was gone;
  - a side face;
  - a planks surface;
  - a loaded silo;
  - `busy (opening)`;
  - `busy (cooldown)`.

  Once idle again, the silo upgraded, and the launch count and target carried over.
- **Recipe.** "Recipe simpleplanes:launch_silo matches and gives 1 x simpleplanes:launch_silo."
- **Command.** `/missile silo place … 4` then `remove`: "20 blocks put back as they were". The hash was identical.
- **The record survives a restart.** A T3 was grown north-west (yaw 135, so the master moved), then the server
  was restarted and a casing broken. It dropped 3 items, and the 640-block hash and census were identical to
  before placement.

**Warheads.** Flat ground, targets about 200 blocks out, launched from the item-built silos. In the 133,209-block
box, "changed" counts every block that differs afterwards, and "fires" is the part of it that became fire (on the
surface and in the crater). The golems stand at the given distance from the target.

| Tier | Blast (report) | Blocks changed | of which fire | Destroyed | Golem at 3 | 6 | 10 | 16 | 24 | Explode call | Detonation tick (P99) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `2.0,blocks` | 17 | 0 | 9 grass, 8 dirt | 94.6 | 100 | 100 | 100 | 100 | 26.6 ms¹ | 31.0 ms¹ |
| 2 | `4.0,blocks` | 87 | 0 | 38 grass, 49 dirt | 70.6 | 90.3 | 100 | 100 | 100 | 16.5 ms | 18.7 ms |
| 3 | `8.0,blocks,fire` | 524 | 132 | 108 grass, 297 dirt, 22 stone | 7.1 | 21.0 | 50.8 | 95.3 | 100 | 46.7 ms | 51.7 ms |
| 4 | `16.0,blocks,fire` | 1609 | 279 | 317 grass, 911 dirt, 241 stone | dead | dead | dead | 15.2 | 64.1 | 66.0 ms | 68.5 ms |

¹ The first explosion of the session, and it includes warm-up: a later T1 terrain hit took 1.8 ms.

- **Accuracy.** All four arrived with a miss of 0.00. So did every other flight from item-built silos in phase 2:
  4 in harmless mode and 7 tier 4 shots in the protection runs, 15 arrivals in all.
- **Tier 4 is `Blast.MAX_POWER`.** The report reads `blast=16.0,blocks,fire`, and the value is the constant
  itself.
- **Crater repeatability.** Five unguarded tier 4 shots changed 1609, 1674, 1649, 1637 and 1681 blocks. A strike
  aircraft with the same warhead changed 1709.
- **Tick cost of a tier 4 detonation.** In five shots, the explode call took 66.0, 55.6, 35.0, 33.8 and 27.9 ms.
  The first two had golems in range or came earlier in the session. That makes one tick of 31–69 ms (the P99),
  while P50 stays 1.3–1.9 ms. The unguarded strike aircraft with the same warhead gave one tick of 79.8 ms. It is
  a single long tick, the same one a strike aircraft already causes, not a stall. In two of the five shots it was
  over the 50 ms budget.

**Harmless mode** (`missile_explosions false`). Four flights, one per tier, gave `blast=inert` and a miss of
0.00. **0 blocks changed** in each 133,209-block box. All 20 golems stayed at 100 health.

**Protection: missiles and strike aircraft behave the same.**

| Case | Missile T4 | Strike aircraft (16, blocks, fire) |
|---|---|---|
| downgrade zone (a claim: no craters, no fire) | `blast=guarded:16.0`, **0 blocks changed**; golems at 3/6/10 dead, 16: 15.0, 24: 64.0 | **0 blocks changed**; golems 3/6/10 dead, 16: 21.7, 24: 67.4 |
| suppress zone | `blast=suppressed`, **0 blocks changed**, all golems 100 | **0 blocks changed**, all golems 100 |
| zone registered, `/blastguard off` | 1674 blocks changed: the switch is respected | – |
| no zone (control) | 1609–1681 blocks changed | 1709 blocks changed |

**Other endings.**

| Case | Report | Effect |
|---|---|---|
| T1 into a 130-high stone wall 60 blocks out | `TERRAIN at 60.00,6.15,0.50 … blast=2.0,blocks,1.8ms` | 3 stone blocks removed from the wall face |
| `/missile abort all` mid-flight | `ABORTED … blast=none` | none |
| tickets off, frozen at the edge of loaded ground | `STALLED … stalls=201 blast=none` | none |

In the wall case the diff also shows 5 grass blocks under the new wall turned to dirt. That comes from covering
them, not from the blast.

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

- **Loading and launching are commands only.** There is no missile item and no launch interface (see below).
- **Removing a silo** puts back what it displaced. A silo built by the phase 1 build has no record, and its
  shaft is filled with dirt. A missile loaded in a broken silo is lost.
- **Upgrading needs natural ground.** A silo surrounded by built blocks cannot go from 2 to 3 until one corner is
  clear. That is by design: it never digs a player's blocks.
- **Tier 4 cost.** One tick of 30–70 ms per detonation on this machine, the same as a strike aircraft with the
  same warhead (§6a). Many simultaneous tier 4 impacts add up.
- **Fire.** Tier 3 and 4 fires are vanilla fire. In a forest they spread as any fire does, subject to the
  `fire_spread_radius_around_player` and related game rules.
- **Translations.** The phase 2 messages and the tooltip are in `en_us.json` only.
- **The item's look.** The item texture is a placeholder and has not been looked at on a client. The upgrade
  messages mix the translated frame with an English reason.
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

**Missile items and a launch interface.** These were suggested but left out on purpose: the request was the
silo item only. A natural next step:

- a missile item per tier, loaded by using it on a silo of that tier, replacing `/missile silo load`;
- a launch terminal block or a targeting item that sets coordinates and fires, replacing `/missile launch`.

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
