# Piercing blast

The owner asked for the Plane Strike Tool and `/autopilot strike` to hit harder than blast 16, with
as little destruction as possible (ideally none). A player or citizen in full enchanted netherite
must still be killed or knocked down. Whether a target survives must depend on its distance from the
centre and not on its armour.

The answer is a flag on the warhead, `pierce`. A piercing blast breaks no blocks and starts no fires.
It can go up to power 64, and it does its own entity pass instead of calling `Level.explode`. This
file records the model, the decisions and the measurements.

## What changed

| file | change |
|---|---|
| `autopilot/Blast.java` | The record gains `pierce`, an optional codec field that defaults to `false`, so older saves and tools read as before. With `pierce`, `breaksBlocks` and `fire` are forced off and power is clamped to `MAX_PIERCE_POWER` = 64 instead of 16. The 3-argument constructor is kept, meaning not piercing, because guards bind to it reflectively. `detonate` asks the guards as before, then goes to `PiercingBlast` or to `level.explode`. |
| `autopilot/PiercingBlast.java` | New. The model below, the effects, the cover rays, the lethal and non-lethal hits, and a log line per blast. |
| `data/simpleplanes/damage_type/piercing_blast.json` | New damage type. `scaling: never`, so difficulty does not change it. |
| `data/minecraft/tags/damage_type/*.json` | The type is added to `is_explosion`, `bypasses_armor`, `bypasses_enchantments`, `bypasses_shield`, `bypasses_wolf_armor`, `bypasses_cooldown` and `no_knockback`. |
| `lang/en_us.json`, `lang/ru_ru.json` | Death messages `death.attack.simpleplanes.piercing_blast` and `.player`. |
| `autopilot/AutopilotComponents.java` | `STRIKE_PIERCE`, a Boolean stored on the Plane Strike Tool. |
| `items/PlaneStrikeToolItem.java` | Reads `STRIKE_PIERCE`. When the tool is piercing, sneak-use cycles 16, 32, 64, 8. The tooltip and settings line show the blast through `Blast.describe()`. |
| `autopilot/AutopilotCommand.java` | Adds `pierce [true\|false]` to `strike` and `tool` without changing the argument order. The power argument now goes to 64. Above 16 without `pierce` the command is refused with the reason. `blocks`/`fire` `true` with `pierce` gets a note that they are ignored. |
| `autopilot/StrikeToolTest.java` | `tooltest charge <pos> <power> [<blocks>\|pierce]`, the same limit. |

The `pierce` keyword is grafted under every executable node of `strike` and `tool`, like `hostile`.
It may follow any argument or `type <aircraft>`. `hostile` goes after it:
`... 64 type fighter pierce hostile`.

## The model

With `R = 2 × power` (vanilla's own entity radius), `r` the distance from the centre and `x = r / R`:

```
x ≤ 0.25                        death is certain (the core; cover is not looked at)
s = (x − 0.25) / 0.75           position across the lethal band, 0 at the core, 1 at R
e = exposure                    fraction of 9 rays from the body to the centre that no block stops
f = 1 − 0.5 × (1 − e)           cover: fully hidden halves the band, fully exposed leaves it whole
u = s / f
P(death) = 1 − (3u² − 2u³)      for u < 1, else 0        (a smoothstep, falling from 1 to 0)
```

Each living entity in range is rolled once.

- **Lethal roll.** The entity is dealt `(health + absorption) / (1 − 0.2 × (amp + 1)) × 1.25 + 10`
  through `hurtServer` with `simpleplanes:piercing_blast`. The division by `1 − 0.2 × (amp + 1)`
  applies only for Resistance I–IV. Nothing is killed through `kill()`, `/kill` or `discard`, so
  graves, MineColonies' critical care and every other death hook see an ordinary death from a named
  damage type. A target that caps one blow (MineColonies citizens take at most 20% of maximum health
  per blow) is struck again. This repeats up to 8 times, until the entity reaches the point of death
  (seen through Fabric's `ALLOW_DEATH`, which the blast observes and never vetoes) or stops losing
  health.
- **Non-lethal roll.** The entity takes `0.75 × maxHealth × (1 − u)` and a knockback of
  `1.0 × (1 − u) × e` blocks/tick, reduced by explosion knockback resistance. At full health this
  never kills on its own: a survivor just outside the core keeps a quarter of its health, and one at
  the edge is barely touched.

Probability of death in the open (`e = 1`), half hidden (`e = 0.5`) and fully hidden (`e = 0`), with
the distance in blocks for each power:

| x = r/R | blast 16 (R 32) | blast 32 (R 64) | blast 64 (R 128) | open | half cover | full cover |
|---|---|---|---|---|---|---|
| ≤ 0.25 | ≤ 8 | ≤ 16 | ≤ 32 | 100% | 100% | 100% |
| 0.30 | 10 | 19 | 38 | 98.7% | 97.8% | 95.1% |
| 0.40 | 13 | 26 | 51 | 89.6% | 82.5% | 64.8% |
| 0.50 | 16 | 32 | 64 | 74.1% | 58.3% | 25.9% |
| 0.60 | 19 | 38 | 77 | 55.0% | 32.0% | 1.3% |
| 0.625 | 20 | 40 | 80 | 50.0% | 25.9% | 0% |
| 0.70 | 22 | 45 | 90 | 35.2% | 10.4% | 0% |
| 0.80 | 26 | 51 | 102 | 17.5% | 0.1% | 0% |
| 0.90 | 29 | 58 | 115 | 4.9% | 0% | 0% |
| ≥ 1.00 | ≥ 32 | ≥ 64 | ≥ 128 | 0% | 0% | 0% |

The constants are at the top of `PiercingBlast`: `CORE_FRACTION`, `COVER_WEIGHT`, `SURVIVOR_DAMAGE`,
`KNOCKBACK` and `MAX_BLOWS`.

### Cover

Nine rays run from three heights by three points across the entity's width, converging on the
centre. A ray counts as stopped by any block with a collision shape that it actually clips. Fluids do
not stop a ray, as in vanilla. The last half block before the centre does not count, and a centre
buried in a block is lifted out of it first, so the ground under the warhead is not cover for
everybody. Rays read loaded chunks only (`getChunkNow`); an unloaded chunk counts as open air and is
never loaded for this. Inside the core, cover is not looked at.

## Decisions

- **No blocks, no fire, ever.** `pierce` forces `breaksBlocks` and `fire` off in the canonical
  constructor, so no code path can produce a piercing blast that breaks things. Asking for
  `blocks true` or `fire true` together with `pierce` is not an error; the command says they are
  ignored.
- **Cap 64, radius 128.** This matches the brief. A blast is one entity query and at most nine short
  ray walks per entity, so the radius costs little (see Performance). A blast that breaks blocks
  keeps its cap of 16, and above 16 is refused without `pierce`.
- **What still saves.**
  - Creative and spectator players are skipped outright: no damage and no knockback.
  - A Totem of Undying works as usual. The type is not in `bypasses_invulnerability`, and the totem
    check in `LivingEntity` runs as for any death. `bypasses_cooldown` means a second blast straight
    after kills the saved target.
  - Resistance V (commands only) leaves nothing through and is deliberately not compensated.
    Resistance I–IV is compensated.
  - Invulnerable entities stay invulnerable.
- **What does not save.**
  - Armour of any kind, and any enchantment on it, including Protection and Blast Protection.
  - A raised shield (`bypasses_shield`) and wolf armour.
  - Absorption, which is added to the amount.
  - Difficulty: `scaling: never`, so the blast kills on peaceful too.
- **MineColonies.** The type is in `is_explosion` and in no tag MineColonies treats as instant death.
  A citizen hit by a lethal roll therefore goes down CRITICAL, as with any explosion. About one in
  ten dies outright; that is MineColonies' own `criticalcareinstantdeathchance`. A non-lethal roll
  leaves it wounded, with at most 20% of its health taken per blow by MineColonies itself.
- **Drones.** A strike drone keeps its fixed charge (`DRONE_POWER` = 1, radius 2) with `pierce` on.
  It does not scale to the tool's power, but it becomes piercing. A drone is a precise weapon; a
  radius-128 drone would make the fleet a set of flying nukes. `Blast.forDrone()` carries the flag.
- **Only living entities.** Items, item frames, paintings, boats and minecarts are left alone.
  Because vanilla breaks an armour stand on any `is_explosion` damage, an armour stand is hit only
  by a lethal roll.
- **The aircraft or missile itself** is the direct entity of the damage source and is never hurt by
  its own blast. The source has no causing entity: nobody is credited with the kill. This has a
  consequence for MineColonies, listed under open questions.
- **Effects.** The EXPLODE game event is kept, so sculk sensors hear it. There are
  `1 + R/16` explosion-emitter particles, forced so they are seen from far away, and one explosion
  sound at volume `max(4, R/16)`; above 1, volume widens the audible range rather than the
  loudness.
- **Crashes, missiles and silos are unchanged.** They do not set `pierce` and go through
  `level.explode` exactly as before.
- **Blast guards.** A piercing blast is offered to `BlastGuards` like any other, and a guard may
  suppress it. MineColonies' guard binds reflectively to the 3-argument constructor. Its `defuse`
  returns the same object when a blast breaks no blocks and starts no fire, so a piercing blast
  passes through with its flag. On the default `DAMAGE_ENTITIES` setting, citizens are hit.

## Measurements

Measured on 26.3, headless dedicated server, at site (−250, −60, 0), clear of villages and
force-loaded.

**Rings.** For each run there were 20 AI-less zombies per ring at 7 fractions of R. They alternated
between full netherite with Protection IV and full netherite with Blast Protection IV. Five runs per
power gave 100 per cell. Deaths:

| x | blast 16 | blast 32 | blast 64 | model (open) |
|---|---|---|---|---|
| 0.15 | 100 | 100 | 100 | 100% |
| 0.30 | 98 | 97 | 98 | 98.7% |
| 0.45 | 81 | 79 | 82 | 82.5% |
| 0.60 | 53 | 57 | 56 | 55.0% |
| 0.75 | 28 | 30 | 21 | 25.9% |
| 0.90 | 4 | 4 | 3 | 4.9% |
| 1.05 | 0 | 0 | 0 | 0% |

Protection IV and Blast Protection IV die at the same rate. Survivors' health matched
`20 − 15 × (1 − u)`: 6, 9, 12, 15 and 18 at the successive rings. One blast over 120 zombies took
2.4–6.1 ms.

**Vanilla control, blast 16.** Four zombies per cell, not piercing:

| armour | 3 | 6 | 10 | 16 | 24 blocks |
|---|---|---|---|---|---|
| Blast Protection IV netherite | dead | dead | dead | alive, 6.4 hp | alive, 17.1 hp |
| Protection IV netherite | dead | dead | dead | dead | alive, 14.7 hp |
| none | dead | dead | dead | dead | dead |

**Blocks.**

- A piercing 64 on a grass field, a piercing 64 half a block into the ground, and a piercing 16
  inside a planks-and-glass box: 0 of 9375 snapshotted blocks changed each time.
- Controls: not piercing, `blocks false`, 0 changed; not piercing, `blocks true`, 871 changed.

**Saves.** On zombies in the core at blast 16:

- Totem: 6 of 6 survived and the totem was used up; a second blast killed all 6.
- Resistance IV: all died.
- Resistance V: all survived.
- `Invulnerable`: all survived.
- Absorption V with Resistance II: all died.

**Death message.** A named zombie was logged as `Testdummy was caught in a piercing blast`.

**Drone.** An FPV strike drone with a piercing tool reported "blast 1.0, piercing (drone charge)". It
hit 0.6 blocks off target and killed the 2 adjacent Blast Protection IV zombies; the 3 farther ones
survived.

**MineColonies 0.0.94**, headless: a colony at (−250, −60, −400), 25 citizens, and the default
`DAMAGE_ENTITIES`. The log confirms the guard bound ("colony blast protection is active").

- Blast 16 inside the colony: 25 in range, 11 lethal rolls. 9 went down CRITICAL INJURY (hp 2–4,
  2400 ticks) and 2 died outright (the 10% chance).
- A second blast 16: 17 lethal rolls. The 5 in the core went 4 down and 1 dead. At 20 blocks, 4 of
  10 went down and the others were wounded to 16/20. Citizens already down from the first blast died.
- Over the two blasts, 20 healthy citizens took a lethal roll: 17 went down CRITICAL and 3 died
  outright.
- Blast 64 at the town hall: 5 in range, all 5 down CRITICAL.
- Blocks around the town hall (36414 snapshotted): an ordinary blast 8 with `blocks true` was defused
  by the guard, and a piercing 64 broke nothing. 0 changed after both.
- MineColonies' own handling of downed citizens made those blasts 24–72 ms. The same blast over
  zombies is a few ms.

**Performance.** 100 zombies at r = 100–127, with blast 64 over open ground so that every one needs
all nine cover rays: 4.47, 3.93 and 3.18 ms per blast. `/tick query` over the run: average 2.6 ms,
P50 2.4, P95 3.8, P99 7.4 ms.

## Not tested

- Real players: the creative/spectator skip, a raised shield, the `.player` death message and
  knockback reaching the client. The server was headless; the code paths are vanilla's
  (`hurtServer`, `push` before the hurt).
- An armour stand ring.
- A guard that suppresses a blast outright, for example a MineColonies colony set to `FALSE`, with a
  piercing blast.

## Open questions

- **Pierce is gentler than vanilla at long range against the unarmoured.** At the same power,
  vanilla kills an unarmoured zombie at 24 of 32 blocks; pierce kills about a quarter of targets
  there. Pierce is much deadlier against armour, and needs a higher power to be as sure against bare
  targets at the edge.
- **Downed citizens die on a second blast.** MineColonies ignores damage to a downed citizen only
  when the source has a causing entity. Setting the pilot or the tool user as the causing entity
  would spare them, would credit a player with the kills and would make the `.player` death message
  appear. Left without one for now.
- **Guards that rebuild a blast** with the 3-argument constructor drop `pierce`, and the blast
  becomes ordinary (still capped and still without blocks or fire, since those guards only take
  things away). MineColonies does not do this; `/missile guard`'s test guard does.
- **Unloaded chunks count as open air** for cover. Only a target standing in an unloaded chunk could
  be affected, and such a target is not ticking anyway.
- The `dist/` README was not updated. That lives on `main`.
