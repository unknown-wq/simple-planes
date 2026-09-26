FOUNDATION READY: yes, code at `bff9915`, on branch `claude/aircraft-foundation-26.3`. This report is committed on top of it.

# Agent 1 report: the foundation

Base: `claude/aircraft-physics-26.3` (`d72f8e7`). Worktree `/home/user/sp-agent-1`, module `26.3/`.
Test server `/home/user/sp-test-1`, port 25601, `-Xmx2G`.

## Commits

| commit | step | content |
|---|---|---|
| `fa7f543` | 1 | `tools/testserver/make-server.sh`, `tools/quick-javac.sh` |
| `a43b204` | 2 | the 20 model classes, 8 textures and 6 contract documents, ported from `claude/fighter-render-model` `fb218f6`; the quadcopter payload canister is never drawn |
| `6d5fe61` | 2, 3 | render-state fields, `PlaneRenderer` hooks, and the fighter, airliner, airship and quadcopter model hooks wired to the render state |
| `175e0a5` | 2 | the mini helicopter rework from `claude/fighter-render-model` **`4392b64`**: cabin frame, nose, new textures, `MiniHeliGlassModel` and `mini_heli_glass.png` |
| `499bbde` | 4 | `PlaneEntity` hooks and the `TEST_STRAFE` input; `HelicopterEntity` getters |
| `8187ec5` | 3, 5, 6, 7 | stub entities, items, the component, registration, renderers, `UpgradesModels`, per-type translates, lang, recipes, tags, `AircraftType` |
| `c509ab2` | 8 | the empty access widener and its wiring |
| `bff9915` | 9 | `/aircraft`, plus the `/airliner`, `/airship` and `/crane` stubs, registered in `SimplePlanesMod` |

Steps 3, 5, 6 and 7 are one commit. The renderers, per-type translates and `UpgradesModels` branches need
the entity types, and the entity types need the entities and items, so no smaller split compiles.

The mini helicopter is ported at **`4392b64`**, the head of `claude/fighter-render-model` when I checked
last.

## What was done, per step

1. **Tools.**
   - `make-server.sh <dir> <port>` builds the server exactly as the spec says. It refuses a non-empty
     directory (exit 1) and exits 2 if an input is missing. It does not copy the MineColonies jar. It
     prints the mods directory, the port and the `cmd.sh` path.
   - `start.sh` passes `$MC_JVM_OPTS` through to java and refuses to start a second copy.
   - `quick-javac.sh [out-dir]` works, in 5.2 s for 247 files. It must compile against **this project's
     Loom-processed Minecraft jar**, `26.3/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.3/*.jar`.
     The raw `minecraft-merged-deobf-26.3.jar` lacks Fabric API's transitive access wideners (for example
     `MenuScreens.register`) and its injected interfaces (for example `RecipeAccess.getSynchronizedRecipes`),
     and fails with 13 errors.
   - The processed jar exists after one `mc-build.sh <dir> compileJava` in the worktree. Until then the
     script warns and falls back to the raw jar.
   - The Fabric API module versions are read from the `fabric-api-0.160.5+26.3` pom, because the Gradle
     cache also holds the 26.2 modules.
2. **Models.**
   - Ported unchanged, except for four things:
     - the drone payload canister is always invisible;
     - the four hooks read the render state;
     - stale "not wired yet" javadoc is updated;
     - the glass model uses 26.3's render type (see the deviations section).
   - Render types are as committed at `4392b64`. `FighterMetalModel`, `AirlinerModel`, `AirlinerSkinModel`
     and `AirlinerMetalModel` use `entityCutoutCull`. **`MiniHeliMetalModel` is back on the default
     `entityCutout`**: the rework moved the pilot's eye out of the metal layer and into the glass. The
     glass uses `entityTranslucentCull`. Everything else uses the default.
3. **Render state and renderers.** Implemented as the spec says, plus the glass wiring described in the
   deviations section. `QuadcopterRenderer` also overrides `getBoundingBoxForCulling` to reach down the
   rope, so the rope is not culled with the drone.
4. **Hooks.** Every default returns the literal it replaced; see the equivalence checks below.
5. **Stubs.** As the spec says. The mini helicopter also refuses payloads and large upgrade types in
   `acceptsUpgrade`; see the deviations section.
6. **Items.** As the spec says. The crane remote is in the creative tab's tool group, after the helipad
   tool. The five aircraft stacks appear once per material, right after each material's helicopter.
7. **Registration.** As the spec says, plus:
   - a fifth mini helicopter layer, `MINI_HELI_GLASS_LAYER` (`mini_helicopter`/`glass`);
   - lang keys `simpleplanes.airliner_logo` ("Airline: %s") and `simpleplanes.airline.0` to `.5` (the six
     airline names from `AirlinerMetalModel`), for the airliner work;
   - `simpleplanes.autopilot.airframe.fighter` and `.airliner`.
8. **Access widener.** An empty file, the `fabric.mod.json` entry and the `loom` block.
   `validateAccessWidener` passes. **The header is `accessWidener v2 official`, not `v2 named`**; see the
   deviations section.
9. **`/aircraft`.** All subcommands are implemented and exercised on the rig; see "Commands added".

## Tests (final jar, `bff9915`, on `/home/user/sp-test-1`)

The results are from one scripted run of the final jar. `tick freeze` is used around each measurement so
that console latency adds no ticks.

| # | result | numbers |
|---|---|---|
| T1 | pass | `mc-build.sh /home/user/sp-agent-1/26.3 build --offline`: BUILD SUCCESSFUL in 13 to 25 s. `validateAccessWidener` runs and passes. The jar is `build/libs/simpleplanes-26.3-5.3.15.jar` |
| T2 | pass | `Done (` about 14 s after launch. No `ERROR`, `FATAL` or `Exception` line in `console.log` over the whole session (count 0). `simpleplanes 5.3.15` is listed |
| T3 | pass | 9 status lines, no exception. All 8 planes show `og=true spd=0.000`. The quadcopter line is present (`og=false`: it has no gravity) |
| T-H | pass | Notch 5: `vs=0.238` (published +0.238). Notch 3: `vs=-0.001` after 400 ticks. `gunship launch 0 -60 200`: on station on tick 162 at 17.85 agl, then `engage` at agl 18.79 |
| T-M | pass | See the T-M notes below the table |
| T4 | pass | Rotation at **4.3 blocks (39 ticks)**, airborne at **11.6 blocks (58 ticks)** at 0.44 b/t. Design: 4.3 b / 38 t and 11.6 b / 57 t. I count the tick of the command, hence +1 |
| T5 | pass | After 1500 ticks: `spd=0.747` (design 0.748). y = **-30.25**. pitch 1.4 to 1.8 (design trim +1.4). All 1500 entity ticks ran. This needed a change to the hold law; see the deviations section |
| T6 | pass | Fighter: rotation 2.8 b / 16 t, airborne **9.6 b / 34 t**. Airliner: rotation 2.8 b / 16 t, airborne **16.1 b / 47 t**, which is later than the fighter |
| T7 | pass | 102 `trace #id` lines for a 100-tick sprint, all with `t pos spd vs hdg pitch roll thr og agl`. The 2 extra lines are ticks run while the console commands around the sprint were processed |
| T8 | pass | Spawned at 0, -50, 0; after 200 ticks it is at 0.00, -50.00, 0.00 with health 10 |
| T9 | pass | `Logo` 3 before `save-all flush`, stop and start, and 3 after (run twice). Six summoned airliners rolled 4, 3, 2, 2, 0, 0 |
| T10 | pass | `material` is `"minecraft:iron_block"`, and status shows `skin=metal`. A default airliner shows `skin=wood` |
| T11 | pass | `type fighter` and `type airliner` both flew the out-and-back route and landed at the improvised field (20 and 17 blocks down the runway), with no exception |
| T12 | pass | `quick-javac.sh` exits 0 in 5.2 s on the final tree |
| T13 | pass | `aircraft kill` prints `Removed N test aircraft`, then `status` prints `no test aircraft` |

T-M, the mini helicopter stub:

- **Vertical ladder.** Notch 5: `vs=0.238`. Notch 3: `vs=-0.000`, the same as the helicopter. The absolute
  heights differ by 0.04 to 0.6 b.
- **Why the heights differ.** A parked plane moves only on ticks where `(tickCount + id) % 4 == 0`. So each
  aircraft lifts off 0 to 3 ticks later, depending on its entity id.
- **Evidence that the physics is the same.** A per-tick trace of two helicopters and one mini helicopter
  gives identical velocity sequences once each has lifted off: 0.056, 0.071, 0.086 … 0.201.
- **Villager.** A villager placed at the skids for 200 ticks was **not** mounted. The control helicopter
  mounted its villager.
- **Livery.** `white_wool` gives `livery=medical`; `oak_planks` gives `livery=standard`.

Extra checks:

- **Baseline comparison.** I built the unmodified `d72f8e7` tree and ran the same frozen-clock script:
  `gunship launch 0 -60 200` plus `/autopilot route ... type plane`, twice with each jar.
  - The plane's landing line is identical with both jars.
  - The gunship reached station on tick 177 and 176 with both jars. Its agl varies by ±0.1 from run to
    run with either jar, because of the entity-id move phase described in T-M.
- **Helicopter getters.** A diff of `HelicopterEntity` shows only constant-to-getter substitutions and the
  `ceilingThrustFactor` multiplication, which is by 1.0. No numeric literal changed.
- **Other subcommands.**
  - `set roll` rolls an unmanned plane.
  - `set yaw` yaws it.
  - `set cyclic` and `set boost` work on the helicopter, and `set cyclic` on a plane answers "is not a
    helicopter".
  - `set` and `hold` on the quadcopter answer "is not a plane".
  - `damage` of 20 kills the quadcopter, and it drops its item.
  - `/airliner status`, `/airship status` and `/crane status` print "not implemented".

## Commands added

All subcommands of `/aircraft` need permission level 2 and work from the console. Every result goes to the
command source through `sendSuccess` and to the log at INFO, through logger `simpleplanes-aircraft`.
`<id>` is the entity's numeric id, as printed by `spawn`.

| syntax | effect | example |
|---|---|---|
| `aircraft spawn <type> <x y z> [heading]` | `type` is one of `plane large cargo helicopter fighter airliner airship mini_helicopter quadcopter`. Spawns the aircraft facing `heading` (default 0), with `yRot`, `Q`, `Q_Client` and `Q_Prev` set. A plane gets a furnace engine with 64 coal. The aircraft is tagged `aircraft-test`. Prints `Aircraft #<id> <type> spawned at x, y, z heading h` | `aircraft spawn fighter 0 -60 0 0` |
| `aircraft set <id> throttle <0..10>` | `setThrottle` | `aircraft set 7 throttle 5` |
| `aircraft set <id> pitch <-1..1>` | `setPitchUp` (+1 is nose up) | `aircraft set 7 pitch 1` |
| `aircraft set <id> yaw <-1..1>` | `setYawRight` | `aircraft set 7 yaw -1` |
| `aircraft set <id> roll <-1..1>` | `setTestStrafe`, which the physics reads as `moveStrafing` when nobody is aboard and no autopilot is flying | `aircraft set 7 roll 1` |
| `aircraft set <id> cyclic <fwd> <right>` | Helicopters only: cyclic in percent, -100 to 100 | `aircraft set 9 cyclic 50 -20` |
| `aircraft set <id> boost on\|off` | Helicopters only: `setCollectiveBoost` | `aircraft set 9 boost on` |
| `aircraft launch <id> <speed> [pitchDeg]` | Sets `deltaMovement` along the heading (and the pitch), at `speed` b/t | `aircraft launch 7 0.5` |
| `aircraft hold <id> <y>` / `hold <id> off` | Altitude hold; see the deviations section for the law | `aircraft hold 7 -30` |
| `aircraft trim <id> <pitchDeg>` / `trim <id> off` | Holds the nose at a fixed pitch with the same inner loop | `aircraft trim 7 3` |
| `aircraft takeoff <id>` | Throttle 5 and pitch 0 until the speed reaches `testTakeOffSpeed()`, then pitch up until the aircraft is 1 block up. Reports `Aircraft #id rotation at N.N blocks (t ticks), airborne at M.M blocks (t ticks) at v.vv b/t pitch p.p`, then holds at `y0 + 30`. Ticks are the aircraft's own ticks | `aircraft takeoff 7` |
| `aircraft status [id]` | One line per `aircraft-test` aircraft in loaded chunks: `#id type pos=x,y,z spd= vs= hdg= pitch= roll= thr= og= agl=`, plus `skin= logo=` (airliner), `livery=` (mini helicopter) or `state= L= carrying= health=` (quadcopter). With an id, any entity | `aircraft status` |
| `aircraft trace <id> on\|off` | Logs `trace #id t= pos= spd= vs= hdg= pitch= roll= thr= og= agl=` for every tick the aircraft actually runs. `-Dsimpleplanes.aircraft.trace=true` (through `MC_JVM_OPTS`) turns it on for every spawned aircraft | `aircraft trace 7 on` |
| `aircraft kill` | Discards every `aircraft-test` entity in loaded chunks, clears the harness state and prints the count | `aircraft kill` |

Notes on the fields:

- `spd` and `vs` are the **measured displacement over the last tick**, not `deltaMovement`. A parked plane
  moves only every fourth tick and accumulates gravity in `deltaMovement` in between, so `deltaMovement`
  would show a parked plane at 0.05 to 0.07 b/t. In flight the two are the same.
- `hdg` is `yRot` in 0 to 360. `pitch` is `xRot`, positive nose up. `roll` is `rotationRoll`. `agl` is
  measured from the `MOTION_BLOCKING` heightmap.

Chunk loading:

- A spawn loads the 3x3 chunks around the point at once and keeps a radius-3 `ENDER_PEARL` ticket there for
  400 ticks.
- Every aircraft the harness knows about renews a radius-4 ticket every tick at its position, and another
  40 ticks ahead of it. These are the autopilot's own values.

Stub commands, each with permission level 2:

- `/airliner status`, `/airship status` and `/crane status` print "not implemented".
- They live in `commands/AirlinerCommand`, `commands/AirshipCommand` and `crane/CraneCommand` (package
  `xyz.przemyk.simpleplanes.crane`).

## Access-widener entries added

None. The file contains only its header and a comment.

## Deviations from the spec, and why

1. **Access widener header: `accessWidener v2 official`, not `v2 named`.** Loom 1.17.19 on the
   unobfuscated 26.3 game refuses the file: "Expected official namespace for access widener entry, found:
   named". The file removed in `3518e05` also used `official`. Entries use the game's own (Mojang) names.
2. **Mini helicopter glass (coordinator's instruction).**
   - `MiniHeliGlassModel` uses `RenderTypes::entityTranslucentCull`. On 26.3 that is the 26.2
     `entityTranslucentCullItemTarget` under a new name. I checked the bytecode: the render type is named
     `entity_translucent_cull_item_target`, its pipeline `pipeline/entity_translucent_cull` has a
     `TRANSLUCENT` blend, and it has no `withCull(false)`, so it culls.
   - `PlaneRenderState` gains `glassSortOrigin`.
   - `MiniHeliRenderer` takes the glass model as a fifth model. Its `submitExtraLayers` does the contract's
     sort-origin submit: it inverts the pose to find the camera, clamps it to the glass box, translates
     there and submits.
   - 26.3 still sorts translucent submits by distance (`TranslucentFeatureRenderPhase`), so the fix
     applies.
   - The ported `MINI-HELI-MODEL.md` gets a short "26.3 port" note at the top.
3. **Airship rudder sign.** `AirshipMetalModel.setupAnim` calls `applyControls(-state.rudder, state.elevator)`.
   `state.rudder` is `getYawRight()`, which is positive for a right yaw. The model's contract says
   `rudder > 0` yaws the nose left, so passing it unchanged would deflect the rudder the wrong way.
4. **The hold law.** The spec's law, `pitchTarget = clamp(0.5*(y - Y) - 60*vy, -15, 15)` with a bang-bang
   on `sign(pitchTarget - xRot)`, oscillated ±20° and never settled. The cause is the pitch-rate ramp of
   0.5 deg/tick², which makes the nose overshoot. Two changes fixed it:
   - The inner loop aims at where the nose will stop, `xRot + rate*|rate|/(2*0.5*multiplier)`, with the
     same 0.3° deadband.
   - A proportional-only outer loop settled 3.2 b low, because trim ≈ 1.6° equals 0.5 × error. I added an
     integral term: 0.004 deg per block-tick, limited to ±5°, active only within 5 b of the target.
   - With both changes, T5 holds -30.25.
   - `trim` uses the same inner loop.
5. **Chunk tickets for the aircraft.** They use radius 4 and a 40-tick lead instead of radius 3; the spawn
   point keeps radius 3 as the spec says. These are the autopilot's values (`AutopilotConfig.CHUNK_TICKET_*`):
   radius 4 keeps two chunks around the aircraft entity-ticking, and the lead loads chunks ahead of a fast
   aircraft.
6. **The mini helicopter refuses more upgrades.** Its `acceptsUpgrade` also refuses `PAYLOAD` and every
   large upgrade type. `ModifyUpgradesContainer.tryUpgradeFromItem` offers large upgrades and payloads to
   any `LargeAirframeEntity` and checks only `canAddUpgrade`. Without the change, the wrench screen would
   bypass the `tryToAddUpgrade` refusal.
7. **`AircraftReuse`.** The one-line change is `RANDOM ? actual.drawnByRandom() : ...`, with a new
   `AircraftType.drawnByRandom()`. Without it, a `random` sortie could reuse a parked fighter or airliner,
   and "random never draws them" would not hold.
8. **Quadcopter additions.** `hurtServer` triples damage from a player while on the ground, like
   `PlaneEntity`. `status` prints `state=hover` (the stub has no state machine) and its health.

## What agents 2 to 5 need to know that is not in their specs

- **Fetching the foundation branch.** `git -C /home/user/simple-planes fetch origin` fetches only `26.2`;
  the fetch refspec is limited. Fetch the branch explicitly:
  `git -C /home/user/simple-planes fetch origin claude/aircraft-foundation-26.3:refs/remotes/origin/claude/aircraft-foundation-26.3`.
  The local branch `claude/aircraft-foundation-26.3` also exists in that repository.
- **`quick-javac.sh` in a new worktree.** Run `mc-build.sh <your dir>/26.3 compileJava --offline` once
  first, so the processed Minecraft jar exists in your `.gradle/loom-cache`.
- **An appended access-widener line must use the official namespace**, which means Mojang names.
- **Measuring.** Freeze the clock around every measurement: `tick freeze`, the commands,
  `tick sprint N`, `tick unfreeze`.
  - Console commands reach the server about 0.5 s apart, which is about 10 ticks.
  - A parked plane only moves on ticks where `(tickCount + id) % 4 == 0`, so lift-off is 0 to 3 ticks
    later depending on the entity id. Compare velocities per tick, or compare after lift-off, not absolute
    heights.
- **Terrain on the `level-seed=aircraft` superflat.**
  - A village stands at roughly x 55 to 75, z -46 to 22. An airliner taking off at x = 40 flew into a
    house at z = 22.5, so keep ground runs on x ≤ 20.
  - Sprinting into chunks that were never generated stalls the aircraft until they generate. In one run
    only 657 of 1500 ticks ran. `trace` logs only the ticks that ran, so count its lines.
  - On `/home/user/sp-test-1`, the +z corridor at x = 0 is generated out to z ≈ 1200. Your own server
    starts with a fresh world: fly your corridor once before measuring on it.
- **`PlaneEntity.testTakeOffSpeed()`** calls `getMotionVars()`, which resets the per-entity scratch. That
  is harmless between ticks. Do not call it from inside a tick.
- **The groundPitchLimit hook** runs at the end of `PlaneEntity.tickPitch`. A subclass that overrides
  `tickPitch` without calling `super` loses it. The helicopter already does this, and the airship's flight
  model probably will.
- **Mini helicopter (Agent 4).**
  - The glass layer is wired: `MiniHeliRenderer.submitExtraLayers` and `PlaneRenderState.glassSortOrigin`.
  - `acceptsUpgrade` already refuses payloads and large upgrades, so keep that when overriding it.
  - `ceilingThrustFactor(getY())` multiplies before the inflow scaling.
- **Crane remote (Agent 5).** Right-clicking the quadcopter reaches `CraneRemoteItem.use`, because
  `QuadcopterEntity.interact` returns the default `PASS`. `interactLivingEntity` fires only for living
  entities. The link range is 6 b along the view ray.
- **Airliner (Agent 3).** The lang keys `simpleplanes.airliner_logo` and `simpleplanes.airline.0` to `.5`
  exist for a logo tooltip.

## Not done or not verifiable

- **Client rendering was not seen.** There is no client on the rig. A client check must cover:
  - that the fighter, airliner, airship, mini helicopter and quadcopter draw at the right place against
    their hitboxes;
  - the fighter nozzle at throttle 5;
  - the airliner logos and the metal skin;
  - the airship envelope and its culling box (it must not vanish when the gondola leaves the screen), and
    the rudder and elevator directions;
  - the mini helicopter's medical livery, and its glass: the pilot must stay visible behind it on Fast and
    Fancy graphics, and the view from inside must be clear;
  - the quadcopter's rope, which only draws when the rope is longer than 1.05 or a load is attached (never
    in the stub), and the clamp jaws;
  - the item icons (tinted plane and helicopter icons).
- **Rider-side behaviour** (the client-authoritative path) cannot be tested headless. This includes seat
  positions with a real player and the camera through `CameraMixin`, which is untouched.
- **Minor.** `DroneMetalModel.DEFAULT_PAYLOAD_ATTACHED`, `FighterExhaustModel.IDLE_THROTTLE` and
  `AirlinerMetalModel.DEFAULT_LOGO` are unused now. They were kept so the models stay as close as possible
  to the model branch.
