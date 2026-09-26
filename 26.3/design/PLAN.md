# Work plan: five aircraft, five agents

Read `DESIGN.md` first. This file says who builds what, where, in which order, and how the branches
come back together. The per-agent specs in `specs/` are self-contained; this file is the map.

## 1. Agent count and the reason

**Five agents: one foundation agent, then four aircraft agents in parallel.**

The five aircraft share a lot: the ported models, the render-state fields, three renderer hooks, seven
`PlaneEntity` hooks and twelve `HelicopterEntity` getters, `UpgradesModels`, `PlaneCollisions.massOf`,
and every registration file (`SimplePlanesEntities`, `SimplePlanesItems`, `PlanesModelLayers`,
`SimplePlanesMod`, lang, datapack). If four agents each added their own lines to those files, every
merge would conflict in the same places. So the foundation agent lands **all** of it first, including
stub entities for all five aircraft that boot, summon and tick, and the test tooling. After that the
aircraft agents own disjoint files and merge in any order. The mini helicopter (a subclass of the
existing helicopter with smaller numbers) is small enough to share an agent; it goes with the airship,
the other "plane family, own force model" aircraft.

| agent | scope (one line) | branch | worktree | test server |
|---|---|---|---|---|
| 1 foundation | port the models; render-state fields and renderer hooks; `PlaneEntity` hooks and `HelicopterEntity` getters; register all five aircraft, items, layers, renderers, lang, recipes, tags; stub entities; `/aircraft` test command; test-server and quick-javac scripts; access-widener wiring (empty) | `claude/aircraft-foundation-26.3` | `/home/user/sp-agent-1` | `/home/user/sp-test-1`, port 25601 |
| 2 fighter | `FighterEntity` physics and numbers, tests | `claude/aircraft-fighter-26.3` | `/home/user/sp-agent-2` | `/home/user/sp-test-2`, port 25602 |
| 3 airliner | `AirlinerEntity` physics, tail-strike clamp, 6 seats, skin and logo, `/airliner` test subcommands, tests | `claude/aircraft-airliner-26.3` | `/home/user/sp-agent-3` | `/home/user/sp-test-3`, port 25603 |
| 4 airship + mini helicopter | `AirshipEntity` buoyancy/ballast/altitude hold, slow turning, envelope clearance, `/airship` command; `MiniHelicopterEntity` getter overrides, ceiling, one rider; tests for both | `claude/aircraft-airship-26.3` | `/home/user/sp-agent-4` | `/home/user/sp-test-4`, port 25604 |
| 5 crane | `QuadcopterEntity` multirotor physics, controller, rope/winch/pendulum, remote item, `/crane` command, tests | `claude/aircraft-crane-26.3` | `/home/user/sp-agent-5` | `/home/user/sp-test-5`, port 25605 |

## 2. Ordering

```
design (this directory, committed by the owner on claude/aircraft-physics-26.3)
   |
   v
Agent 1: foundation  ---- pushes claude/aircraft-foundation-26.3 ---- (gate: boots headless, 5 types summon)
   |
   +-----------+-----------+-----------+
   v           v           v           v
Agent 2      Agent 3     Agent 4     Agent 5        (parallel, disjoint files)
fighter      airliner    airship     crane
```

The foundation is the gate. Its acceptance test (spec §Tests) is that the jar boots headless with no
mod error, all five new types summon and tick for 200 ticks without an exception, `/aircraft spawn` and
`/aircraft status` work for every type, and `mc-build.sh ... build` is green. Only then are agents 2 to
5 launched, each branching from `origin/claude/aircraft-foundation-26.3`.

Agent 1 runs alone, so it may use the box freely (one test server, one build at a time anyway).

## 3. Worktrees

All worktrees are `git worktree`s of `/home/user/simple-planes`. The design docs live in the
`/home/user/simple-planes-26.3` worktree (branch `claude/aircraft-physics-26.3`); nobody works there.

```sh
# Agent 1
git -C /home/user/simple-planes fetch origin
git -C /home/user/simple-planes worktree add -b claude/aircraft-foundation-26.3 /home/user/sp-agent-1 origin/claude/aircraft-physics-26.3

# Agents 2..5 (after Agent 1 has pushed)
git -C /home/user/simple-planes fetch origin
git -C /home/user/simple-planes worktree add -b claude/aircraft-fighter-26.3  /home/user/sp-agent-2 origin/claude/aircraft-foundation-26.3
git -C /home/user/simple-planes worktree add -b claude/aircraft-airliner-26.3 /home/user/sp-agent-3 origin/claude/aircraft-foundation-26.3
git -C /home/user/simple-planes worktree add -b claude/aircraft-airship-26.3  /home/user/sp-agent-4 origin/claude/aircraft-foundation-26.3
git -C /home/user/simple-planes worktree add -b claude/aircraft-crane-26.3    /home/user/sp-agent-5 origin/claude/aircraft-foundation-26.3
```

If `origin/claude/aircraft-physics-26.3` does not exist yet when Agent 1 starts (the owner has not
pushed the design), Agent 1 branches from the local `claude/aircraft-physics-26.3` instead:
`git -C /home/user/simple-planes worktree add -b claude/aircraft-foundation-26.3 /home/user/sp-agent-1 claude/aircraft-physics-26.3`.

Nobody touches `/home/user/simple-planes` (the `claude/fighter-render-model` worktree; read it with
`git show` only), `/home/user/simple-planes-26.3`, another agent's `/home/user/sp-agent-*`, or
`/home/user/minecolonies-fabric`.

## 4. Test servers, side by side

Each agent has its own dedicated server directory and port (table above), built by the foundation's
`26.3/tools/testserver/make-server.sh <dir> <port>` from the launcher already on this container
(`/tmp/mc-server-26.3/`: `fabric-server-launch.jar`, `libraries/`, `versions/`, `.fabric/`) and the
Fabric API jar in `/home/user/minecolonies-fabric/.cache/fabric-api-0.160.5+26.3.jar`. Nothing is
downloaded and nothing is shared between servers except those read-only inputs. Every server:

- `-Xmx2G`, `nogui`, superflat creative world, `online-mode=false`, `spawn-monsters=false`,
  `max-tick-time=-1`, `pause-when-empty-seconds=0`, `level-seed=aircraft`, `view-distance=6`,
  `enable-query=false`, `enable-rcon=false`, its own `server-port`;
- `start.sh` (FIFO stdin held open read-write, blocks until `Done (`), `cmd.sh "<console command>"`,
  `stop.sh`; `console.log` is the transcript. This is `TESTING.md` §3 verbatim, with paths per agent.

**Memory.** The box has 16 GB and 4 cores. Budget: four servers x 2 GB = 8 GB, one Gradle build 3 GB
(`org.gradle.jvmargs=-Xmx3G`, `--no-daemon`), JVM overheads about 1 GB. That leaves 4 GB. Rules:
stop your server (`./stop.sh`) while you run a full `build`; never raise `-Xmx`; never run two servers
yourself. `free -m` before starting a server; if less than 3 GB is available, wait.

**CPU.** `/tick sprint` on four servers at once shares four cores; a sprint that reports 300 ticks/s
instead of 1000 is still correct (ticks are ticks). Do not draw wall-clock conclusions.

**Recipes worth keeping from `TESTING.md` §3**: sprint the clock (`tick sprint N`, `tick sprint stop`),
kill leftovers inside a force-loaded box before every measured run, `gamerule minecraft:spawn_mobs false`
(the old name is rejected), `/fill` does nothing above 32768 blocks, `@e` only sees loaded chunks.

## 5. Builds and the lock

- **Every Gradle invocation** goes through `/home/user/minecolonies-fabric/tools/mc-build.sh <absolute
  project dir> <task>` (`<project dir>` is `/home/user/sp-agent-<n>/26.3`). It takes the global flock on
  `/tmp/mc-build.lock` and uses Java 25. Never call `gradle` directly, never in parallel; `runDatagen`
  and `build` are separate invocations (this mod has no datagen; only `build` and `compileJava` are used).
- Four agents will queue on the lock. A full `build` of `26.3/` takes a few minutes; expect to wait
  behind up to three others. So iterate with `javac`: the foundation's `26.3/tools/quick-javac.sh`
  compiles `src/main/java` in seconds without the lock, and the plain physics classes of the crane
  (`entities/crane/*`) have no Minecraft imports and compile and run with `javac`/`java` alone. Do a
  real `build` only when you need a jar for the server.
- `--offline` is the fast path: `mc-build.sh <dir> build --offline`. Drop it only if a dependency
  changes (none should).

## 6. Files: who owns what

| file / package | owner | others |
|---|---|---|
| `client/render/models/{Fighter,Airliner,Airship,Drone,MiniHeli}*` | 1 (port, and wires every `setupAnim` hook to the render state) | 5 may edit `DroneMetalModel` (jaws); nobody else edits models |
| `client/render/PlaneRenderState`, `PlaneRenderer`, `UpgradesModels` | 1 | read-only for 2 to 5 |
| `client/render/AirlinerRenderer` | 1 creates | 3 owns afterwards |
| `client/render/AirshipRenderer`, `MiniHeliRenderer` | 1 creates | 4 owns afterwards |
| `entities/HelicopterEntity` (getters) | 1 | read-only for 2 to 5 |
| `entities/MiniHelicopterEntity` | 1 stub | 4 |
| `client/render/QuadcopterRenderer`, `QuadcopterRenderState` | 1 creates | 5 owns afterwards |
| `client/PlanesModelLayers`, `setup/*`, `SimplePlanesMod`, lang, `data/`, `assets/*/items`, `models/item` | 1 | read-only for 2 to 5 |
| `entities/PlaneEntity` (hooks), `entities/PlaneCollisions` (massOf) | 1 | read-only for 2 to 5 |
| `entities/FighterEntity` | 1 stub | 2 |
| `entities/AirlinerEntity` | 1 stub | 3 |
| `entities/AirshipEntity` | 1 stub | 4 |
| `entities/QuadcopterEntity`, `entities/crane/*` | 1 stub | 5 |
| `items/QuadcopterItem`, `items/CraneRemoteItem` | 1 stub | 5 |
| `autopilot/AircraftType` (+ `FIGHTER`, `AIRLINER`) | 1 | 2 and 3 read; changes to the autopilot's per-type gains, if any, go in `AutopilotConfig` and are 2's/3's, coordinated through the report |
| `commands/AircraftCommand` (`/aircraft`) | 1 | read-only; agents add their own command classes |
| `commands/AirlinerCommand`, `AirshipCommand` (also carries the `/airship miniheli ...` test aids), `crane/CraneCommand` | 3, 4, 5 | registered by 1 in `SimplePlanesMod` as empty stubs that each agent fills; the fighter agent adds nothing unless it must (see its spec) |
| `tools/testserver/*`, `tools/quick-javac.sh` | 1 | read-only |
| `src/main/resources/simpleplanes.accesswidener` | 1 (empty) | any agent may append a line; must be listed in its report |
| `design/reports/AGENT-<n>-REPORT.md` | each agent, its own | |

Rule for agents 2 to 5: if you believe you must edit a file owned by 1, stop, write it in your report
under "needs a foundation change", and work around it on your branch only if the workaround is local
to your own files.

## 7. Merge order and conflict avoidance

1. `claude/aircraft-foundation-26.3` merges first (the owner merges; no PRs are opened by agents).
2. `claude/aircraft-fighter-26.3`, `-airliner-`, `-airship-`, `-crane-` merge next in any order.
   They touch disjoint files by construction (§6). The only files two of them may both touch:
   - `simpleplanes.accesswidener`: line appends, union merge; expected to stay empty;
   - `client/render/models/*Model.java`: each aircraft edits only its own models;
   - `design/reports/`: distinct files.
3. Registration touch points (`SimplePlanesEntities`, `SimplePlanesItems`, `PlanesModelLayers`,
   `SimplePlanesMod`, `en_us.json` and the other lang files, `data/simpleplanes/recipe`, tags,
   `assets/simpleplanes/items`, `models/item`) are **complete in the foundation** and are not edited by
   agents 2 to 5. Command feedback strings are `Component.literal` in the agent's own command class, as
   `GunshipCommand` and `AutopilotCommand` already do, so no lang edits are needed.
4. The aircraft agents do not rebase onto each other. If the owner merges one before another is
   finished, the later one rebases onto the merge target once, at the end, and reruns its tests.

## 8. Reports

Every agent writes `26.3/design/reports/AGENT-<n>-REPORT.md` on its own branch: what was done, test
results with numbers against the spec's acceptance table, commands added (syntax, permission level,
effect, example), access-widener entries added (expected none), what is not done, and what could not
be verified headless.
