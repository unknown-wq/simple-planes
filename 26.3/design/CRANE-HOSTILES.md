# Crane: hostile mobs on the rope

The owner saw `Crane #1587: refused: cannot lift Creeper` and `... cannot lift Skeleton` in chat, and
asked for the crane to carry ordinary hostile mobs. This file records what changed, what was decided
and how it was tested on a headless 26.3 server.

## What changed

All of the code change is in `src/main/java/xyz/przemyk/simpleplanes/entities/QuadcopterEntity.java`.

- **Load rule.** `ALLOW_HOSTILES = true`. With `false`, the old rule comes back, and
  `simpleplanes:crane_liftable` is again the allow-list for hostiles. The tag check still sits in
  `refusal()`.
- **Bosses.** The new `isBoss()` covers the ender dragon, the wither, the warden, the elder guardian
  and anything in `c:bosses` (Fabric's conventional tag, read by id). They are refused before the tag
  and mass checks, so a boss is always reported as a boss and never as "too heavy".
- **Refusal reasons.** Every refusal now states why:
  - `cannot lift <name>: ` followed by one of:
    - `boss`
    - `aircraft`
    - `spectator`
    - `riding <vehicle>`
    - `has a rider`
    - `dead`
    - `not a mob`
    - `in tag simpleplanes:crane_never`
    - `hostile` (only when `ALLOW_HOSTILES` is false)
    - `it would not attach to the hook`
  - `too heavy: <name> is m, limit 1.55` (at the time; now `too heavy: <name> ≈ m, max 11.96 (p% of
    capacity)`, see CRANE-MASS.md)
- **Limits kept.** The mass limit (`MAX_LOAD` 1.55 then; replaced by the mass estimate and gradual lift
  performance in CRANE-MASS.md), players (allowed unless in spectator mode),
  aircraft, riders and vehicles are all unchanged.
- **Rope behaviour.** See the next section.
- **Load bookkeeping.**
  - The crane keeps a reference (`carried`) to the entity on its hook. It is not saved.
  - `load lost` now names the reason: `died`, `let go`, or `was removed (<reason>)`. The last one
    covers a creeper that exploded, a mob discarded on peaceful and `/kill`.
  - A load that converts on the hook is handed over to its successor, reported as `load changed: ...`.
- **Explosions.** The passenger exemption in `hurtServer` ("the load cannot hit its own crane") no
  longer covers explosions. A lit creeper on the hook therefore damages the crane under the normal
  explosion rules.

Docs:
- `design/DESIGN.md` 6.5 ("Mobs allowed", new "A slung mob does not fight") and Q8 (answered).
- `design/specs/AGENT-5-crane.md`: allowed loads and C11 marked as superseded or changed.
- `design/reports/AGENT-5-REPORT.md`: C11 and Q8 annotated.

Other notes:
- No mixins were added, no access-widener entries, and no Minecraft code was patched. Only public
  26.3 API is used: `Mob.setTarget`, `Brain.eraseMemory`, `AttributeInstance.addTransientModifier`
  and `Creeper.setSwellDir`, plus overrides of `Entity.addPassenger`/`removePassenger`.
- The strike tool was not touched.

## Rope behaviour: decision

**Chosen: suppress targeting while slung.** It is the simplest option that is robust, and it leaves
nothing behind on the mob.

1. **Blind the mob while it is a passenger.** `addPassenger` puts a transient `FOLLOW_RANGE` modifier,
   `simpleplanes:crane_slung` (x0), on a `Mob` passenger, and `removePassenger` takes it off.
   - Transient modifiers are never written to NBT, so a save or a crash cannot leave it on the mob.
   - Every way off the hook goes through `removePassenger`:
     - release
     - overload
     - crane destroyed
     - `/kill` of the crane
     - a teleport
     - a conversion
   - On a world reload, vanilla re-mounts the passenger through `startRiding`, and the modifier
     comes back with it.
   - With range 0, `NearestAttackableTargetGoal` finds nothing, and `TargetGoal.canContinueToUse`
     drops any target it already had. The brain sensors of piglins and breezes also see nothing.
2. **Every crane tick** (the vehicle ticks before its passenger) does four things:
   - `setTarget(null)`
   - erases `ATTACK_TARGET`
   - stops the mob's navigation
   - `setSwellDir(-1)` on a creeper that is not `isIgnited`

**Why the first attempt was not enough.** It cleared the target each tick and nothing more. In test,
a slung zombie next to an iron golem still took the golem from 100 to 79 HP in about 15 s. The reason
is that vanilla `TargetGoal.canContinueToUse` puts its own remembered target back every other tick,
before the melee goal runs. The follow-range modifier is what closes that gap.

| Mob | On the rope | After release |
|---|---|---|
| creeper | no swell, even when provoked beside a target; a creeper lit with flint and steel explodes and damages the crane (10 → 1 HP in test) | normal: provoked beside the golem, it exploded |
| skeleton / stray / bogged / pillager / witch / blaze / evoker | no target, no shots | shoots again |
| zombie / husk / drowned / vindicator / piglin brute | no target, no melee | attacks again |
| enderman | no target; does not teleport while riding (vanilla `Enderman.teleport` refuses passengers), so arrows hit it | normal |
| piglin (overworld) | zombifies after 300 t; the zombified piglin is re-mounted by vanilla and adopted: `load changed: Piglin is now Zombified Piglin (mass 0.70)` | normal |
| undead in daylight | burns; `load lost: Zombie died`; the crane is not hurt | — |
| passive mobs | the same modifier is applied and removed; harmless | normal |

**Despawn.** A passenger never despawns: vanilla `Mob.requiresCustomPersistence()` returns
`isPassenger() || isLeashed()`. This was read in the 26.3 sources but not tested, because without a
connected player nothing despawns at all. A released mob is not made persistent
(`PersistenceRequired: 0b` after release).

**Tag on the mob.** The only lasting mark on a released mob is the existing scoreboard tag
`crane-load`. It is added on pickup, and `/crane kill` uses it to clean up test loads. It is
pre-existing and intended, and it is unchanged.

## Test rig and commands

The server lives outside the repo, under `…/scratchpad/crane-hostiles/server`, on port 25716. Setup:

```sh
26.3/tools/testserver/make-server.sh …/crane-hostiles/server 25716
cp 26.3/build/libs/simpleplanes-26.3-5.4.0-beta.4.jar …/server/mods/
cd …/server && MC_JVM_OPTS=-Xmx1536M ./start.sh
./cmd.sh "gamerule advance_time false"; ./cmd.sh "time set midnight"; ./cmd.sh "difficulty normal"
./cmd.sh "forceload add -48 -48 48 48"
```

The builds were `/home/user/minecolonies-fabric/tools/mc-build.sh <abs>/26.3 compileJava --offline`
and `... build --offline`. There is no datagen in this project.

Typical sequence (`c.sh` = send via `cmd.sh`, wait, print new `console.log` lines):

```
crane spawn 0 -50 0
summon minecraft:creeper 0 -60 4 {Tags:["t_creeper"]}
crane pickup <id> @e[tag=t_creeper,limit=1]
summon minecraft:iron_golem 30 -60 30 {Tags:["t_golem"],NoAI:1b}      # target dummy
crane goto <id> 28.5 -54 30                                            # hang the load 1.5 b from the golem
damage @e[tag=t_creeper,limit=1] 1 minecraft:mob_attack by @e[tag=t_golem,limit=1]
attribute @e[tag=…,limit=1] minecraft:follow_range get
crane stop <id> | crane deliver <id> x y z | crane debug remote <id> pickup/block …
data merge entity @e[tag=t_creeper,limit=1] {ignited:1b}
damage @e[type=simpleplanes:quadcopter,limit=1] 15 minecraft:generic ; kill @e[type=simpleplanes:quadcopter]
```

The batch runs used `tick rate 100` and a script that summons a mob, picks it up, reads its
follow range, releases it and reads the range again.

## Results

**Refusals** (chat and log line `Crane #N: refused: …`):
- Bosses:
  - `cannot lift Wither: boss`
  - `cannot lift Warden: boss`
  - `cannot lift Elder Guardian: boss`
- Too heavy (under the old 1.55 cutoff; superseded by CRANE-MASS.md, where the spider, horse and
  iron golem are lifted and the ravager and hoglin are still refused):
  - `too heavy: Spider is 1.76, limit 1.55`
  - Ravager 8.37, Hoglin 2.73, Zoglin 2.73, Ghast 64.00, Creaking 2.19
  - Slime / Magma Cube `Size:2` 3.80 and `Size:3` 9.00
  - Horse 3.12 and Iron Golem 5.29, as before
- Rider: `cannot lift Zombie: riding Chicken` (a chicken jockey).
- `crane_never` datapack (cow and creeper): `cannot lift Cow: in tag simpleplanes:crane_never` and
  `cannot lift Creeper: in tag simpleplanes:crane_never`, while a sheep was still lifted.

**Accepted** (`picked up <name> (mass m)`, follow range 0.0 while slung, then restored to base after
`set down`):

| Mob | Mass | Base range restored |
|---|---|---|
| creeper | 0.61 | 16 |
| skeleton, stray, bogged | 0.72 | 16 |
| zombie, husk, drowned, zombie villager | 0.70 | 35 |
| witch | 0.70 | 16 |
| pillager | 0.70 | 32 |
| vindicator, evoker | 0.70 | 12 |
| enderman | 1.04 | 64 |
| blaze | 0.65 | 48 |
| piglin brute (immune to zombification) | 0.70 | 12 |
| zombified piglin | 0.70 | 35 |
| breeze | 0.64 | 24 |
| cave spider | 0.24 | 16 |
| silverfish, endermite | 0.05 | 16 |
| vex | 0.13 | 16 |
| shulker | 1.20 | 16 |
| slime / magma cube `Size:0` | 0.14 | 16 |
| slime / magma cube `Size:1` | 1.12 | 16 |

Passive mobs still work: cow 1.13, sheep 1.05, pig 0.73, chicken 0.11, villager 0.70, wolf 0.31 and
llama 1.51 were all picked up and set down, with range 0 while slung and 16 after.

**Behaviour tests:**

- **Creeper, slung.** Hung 1.5 b from a golem (dist² 5.1 < 9) and provoked by the golem 5 times over
  15 s: it did not explode and the golem stayed at 100 HP. As a control, once set down on the ground
  and provoked the same way, it exploded (golem 100 → 80.6).
- **Creeper, lit on the hook** (`{ignited:1b}`):
  - `load lost: Creeper was removed (discarded)`
  - crane health went from 10 to 1
  - the crane stayed flying
- **Skeleton.** Hung 8.5 b from the golem for 24 s: golem HP stayed constant, so no
  hits. After release the golem went from 100 to 66 in 15 s.
- **Zombie.**
  - First build (target clear only): hung 1.2 b from the golem, 100 → 79 HP in about 15 s. That is
    the failure that led to the modifier.
  - Final build: 30 s adjacent, golem stayed at 100 HP.
  - After release, the golem went 100 → 37 in about 20 s.
- **Enderman.**
  - Carried at noon for 24 s, plus 3 `damage … minecraft:arrow` and 3 real arrows: its position
    stayed identical to 1e-9 and it stayed on the hook.
  - The arrows hit it (40 → 28 HP), because vanilla endermen do not dodge while riding.
- **Restart while carrying.**
  - After `stop` and `start`, the crane came back as `#1 CARRY … load=Zombie`.
  - The zombie's follow range read 0.0 again, so the modifier was re-applied through
    `addPassenger` on load.
- **Crane destroyed while carrying** (`damage … 15`):
  - `destroyed … falling`
  - `load released: crane destroyed (Zombie, feet at agl 8.3)`
  - `lost: crashed`
  - the zombie's range went back to 35.
- **`kill @e[type=simpleplanes:quadcopter]` while carrying a skeleton:** the skeleton's range went
  back to 16.
- **Daylight:** `load lost: Zombie died`, crane at 10 HP.
- **Piglin:** `load changed: Piglin is now Zombified Piglin (mass 0.70)`, then
  `set down Zombified Piglin`.
- **Remote item path** (`crane debug remote … pickup`, then `block`): picked up a creeper and set it
  down at the clicked block.
- **Guardian (out of water) and phantom:**
  - They could not be caught: `pickup aborted: could not reach Guardian` / `… Phantom` after
    `PICKUP_TIMEOUT`.
  - This is the existing pursuit limit and is not specific to hostiles.
- **Errors:** 0 `Exception` or `ERROR` lines in `console.log` over the whole session.

## Unrelated observation

The coordinator's note was that a summoned quadcopter appeared at 0, -60, 0 and rendered as a few
dark pieces.
- On the server, `summon simpleplanes:quadcopter 20 -60 20` gave `Pos [20.5, -60.0, 20.5]`, and
  `crane list` showed it at 20.5 -60.0 20.5. The server-side position is correct.
- There is no client on this rig, so the rendering was not checked, and the cause is not obvious
  from the server side.

## Open questions for the owner

1. **Spider.** Answered by the owner's mass request: the hard 1.55 cutoff is gone, the spider (1.76,
   28 % of capacity) is lifted with somewhat slower handling. See CRANE-MASS.md.
2. **Lit creeper.** A creeper lit with flint and steel on the hook explodes and takes the crane
   down to 1 HP. A charged creeper would destroy it. Is that the wanted outcome, or should an
   ignited creeper be refused at pickup?
3. **Retaliation.** A slung mob struck by something right beside it can in theory swing once in the
   same tick as the hit, before the next crane tick clears the target. This was not seen in test.
   Acceptable?
4. **Undead in daylight** burn on the rope and die. Keep this (vanilla), or refuse undead pickups in
   daylight?
5. **Converted loads.** They are adopted even if the successor is heavier. If it is too heavy, the
   overload logic lowers it and lets go.
