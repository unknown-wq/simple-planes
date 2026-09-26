# No item drop when an aircraft is destroyed

Branch `claude/no-drop-on-destroy-26.3`, based on `26.3-beta` `a8d4872` (5.4.0-beta.4).

Owner's request: when an aircraft is destroyed (crash, explosion, missile, air defence, gunfire, fire, lava,
mobs, fall, void, `/kill`) its item must not drop. It drops only when a player breaks it by hand in survival.
Creative breaking stays as it was (no drop). The folding upgrade's return to the inventory stays.

## The rule as implemented

`PlaneEntity.isPlayerBreak(DamageSource)` is true when all of these hold:

- the damage type is in `#minecraft:is_player_attack` (`player_attack`, `spear`, `mace_smash`), so it is a melee
  hit and not an arrow, trident, explosion, fire or command damage;
- the direct entity is a `Player`;
- the causing entity is that same player.

The item drops only if this hit is the one that takes health from above 0 to 0 or below and the `entity_drops`
game rule is on. The aircraft is then removed at once and drops its item, on the ground or in reach in the air.

| Airframe | Where |
|---|---|
| plane, large, cargo, helicopter, fighter, airliner (and its hitbox parts, which forward to it), airship, mini helicopter | `PlaneEntity.hurtServer` |
| quadcopter crane | `QuadcopterEntity.applyDamage`, using the same `isPlayerBreak` |

Everything else drops nothing:

- `PlaneEntity.crash()` no longer drops. This is the path for ground and wall impacts, plane-vs-plane rams,
  strike flights hitting their target, the autopilot's own crash exits, and any aircraft at 0 HP touching the
  ground.
- An aircraft brought to 0 HP on the ground by anything other than a player's hit is removed without a drop.
  Before, it dropped its item.
- In the air, anything other than a player's hit leaves it at 0 HP. It falls, `crash()` explodes it, and nothing
  drops. The quadcopter goes through its own `dying` and `crash` states, which never dropped.
- `/kill`, the void, missiles and `MissileEntity` (invulnerable) never dropped anything, and still do not.

This also ends the free-plane source recorded in `design/reports/strike-tool-type.md`, open question 1: a
strike plane that crashed on its target dropped its item. It no longer does, whether the flight came from
`/autopilot strike` or from the strike tool.

A player's arrow, trident, fireball, TNT or shooter-upgrade fire counts as destruction, because the direct entity
is the projectile or the explosion. A sweep of a player's sword that reaches the aircraft is a `player_attack`
from the player, so it counts as breaking. Fabric fake players (for example a machine that punches) are
`Player`s and count as breaking too.

### Changes from the old behaviour, apart from the drops

- Old: a player's hit that brought an airborne aircraft to 0 HP let it fall and explode, and the crash dropped
  the item. New: the aircraft is removed at once and drops its item, with no fall and no explosion. This matters
  for the airship and the hovering helicopters and quadcopter, which are rarely on the ground. Punching one of
  them used to blow it up.
- A player's hit on an aircraft that is already at 0 HP, and falling after being shot down, does not salvage
  it. Health must go from above 0 to 0 or below on that hit.
- Quadcopter: the old code dropped the item for any damage whose direct entity was a player, on the ground only,
  including `/damage ... by <player>` with any damage type. A hovering quadcopter punched to 0 HP fell and
  crashed with no item. Now the damage type must be a player attack, and a hovering quadcopter punched to 0 HP
  drops its item. The feedback line is now `lost: broken by a player` instead of `lost: broken on the ground`.

### Inventory and upgrade contents

An aircraft item has always carried the full entity save (`getItemStack()` writes `addAdditionalSaveData`).
That includes upgrades and their containers: the furnace engine's fuel, chest and supply crate contents, the
shooter's ammo, the jukebox record, the cargo plane's large upgrades. `Upgrade.onRemoved()`, which spills a
container, runs only when an upgrade is removed with the wrench. It never runs on destruction.

- On a player break, nothing changed. There is one item, with the contents inside it. Verified: the dropped
  plane item holds `upgrades: {"simpleplanes:furnace_engine": {item: [{count: 64, id: "minecraft:coal"}]}}`.
- On destruction, the contents used to come back inside the dropped item. Now they are lost with the aircraft.
  No coal item appeared in any destruction case below. There is no duplication either way. See the first open
  question.
- A quadcopter carrying a load releases it while dying, as before (the load is an entity, not an item).

### Not changed

- Creative player's hit: removed, no item (both airframes check `getAbilities().instabuild` before anything
  else).
- Folding upgrade: `getDismountLocationForPassenger` still puts the item into the dismounting survival player's
  inventory and removes the aircraft.
- Parachute: `ParachuteEntity` is invulnerable. It still packs back into the parachute item on landing, and a
  storage crate still turns into a barrel or spills its items. It is not an aircraft, and no damage destroys it.
- `MissileEntity`: invulnerable, never drops.
- Aircraft standing in lava take no damage on 26.3 (tested: health 10 after 20 s in a lava pool, `Fire: 0`).
  That was already so and is not touched. `/damage ... minecraft:lava` and the fire types are covered below.

## Test tooling added

Two subcommands of `/aircraft` (permission 2, console), in `commands/AircraftCommand.java`:

| Command | What it does |
|---|---|
| `aircraft punch <id> [creative]` | A fake player (`[AircraftTest]`) in survival or creative stands 1.5 blocks south of the aircraft, looks at its centre and calls vanilla `Player#attack`. This builds the same `player_attack` damage source a left click does. It prints `Aircraft #id punched (survival): health N, alive/removed`. Bare hands at an uncharged attack take 1 HP per hit, and the aircraft's 10-tick damage timeout applies. |
| `aircraft fold <id>` | Fits a folding upgrade if missing, then calls `getDismountLocationForPassenger` for a survival fake player with an empty inventory. Fabric's `FakePlayer` refuses `startRiding`, so this is the hook `LivingEntity#dismountVehicle` runs. It prints what the player got. |

## How it was tested

Dedicated server from `26.3/tools/testserver/make-server.sh <dir> 25718`, superflat (surface y -60), no player
online, `MC_JVM_OPTS=-Xmx1536M`, `spawn_mobs false`, `forceload add -32 -32 400 32` plus forceloads around the
missile and strike sites. After each case the count comes from:

```
execute positioned X Y Z if entity @e[type=item,distance=..16,nbt={Item:{components:{"simpleplanes:entity_tag":{}}}}]
execute positioned X Y Z if entity @e[type=item,distance=..16]
```

The first count is aircraft items (every aircraft item carries `simpleplanes:entity_tag`). The second is all
items. Other items were always dirt from a crash or explosion crater, listed by
`execute ... as @e[type=item] run data get entity @s Item`. For cases where the aircraft moves (the dives) the
count is global, with `@e[type=item,nbt=...]` over all loaded chunks. `kill @e[type=item]` ran before every
case. `/aircraft spawn` gives planes a furnace engine with 64 coal. `data merge entity ... {health:2}` shortens
the punching.

Example sequence (survival break):

```
aircraft spawn plane 30 -60 0                  -> Aircraft #23 plane spawned
data merge entity @e[tag=aircraft-test,limit=1,sort=nearest,x=30,y=-60,z=0] {health:2}
aircraft punch 23                              -> health 1, alive
aircraft punch 23                              -> health 0, removed
execute positioned 30 -60 0 if entity @e[type=item,distance=..16,nbt={...}]   -> Test passed. Count: 1
```

Other commands used: `damage <aircraft> 100 minecraft:mob_attack by <NoAI zombie>`,
`execute at <aircraft> run summon tnt ~ ~0.5 ~ {fuse:0}`, `aircraft launch <id> 3 -70` (a dive from y -20),
`damage <aircraft> 100 minecraft:generic` at y -45, `fill ... minecraft:lava`,
`damage ... minecraft:lava|in_fire|on_fire`, `kill <aircraft>`, `summon arrow ... {Motion:[0,0,3d],damage:20d}`,
`missile silo place 40 -61 440 1 true` + `missile launch 40 -61 440 X -60 Z`, `autopilot strike 40 -60 600 150 0`,
`crane land <id>`, `gamerule minecraft:entity_drops false`.

## Results

"Before" is the unchanged `a8d4872` jar on the same server with the same commands, for the destruction cases it
could run. It had no `punch` or `fold`. Aircraft items / all items.

| # | Case | Airframes | Before | After |
|---|---|---|---|---|
| 1 | Survival punch at 2 HP, until removed (2 hits) | plane, large, cargo, helicopter, fighter, airliner, airship, mini_helicopter, quadcopter | n/a | **1 / 1** each (9 of 9) |
| 2 | Survival punch from full health (10 hits) | plane; quadcopter landed with `crane land` | n/a | **1 / 1**, 1 / 1 |
| 3 | Survival punch, plane 15 b up at 1 HP | plane | n/a | **1 / 1**, the item holds 64 coal in its engine |
| 4 | Contents of the dropped item | plane | | `entity_tag` has `furnace_engine` with 64 coal |
| 5 | Creative punch (1 hit) | plane, quadcopter, airliner | | **0 / 0** each, aircraft removed |
| 6 | Survival punch, `entity_drops false` | plane | | **0 / 0** |
| 7 | `mob_attack` 100 by a zombie, on the ground | plane, large, cargo, helicopter, fighter, airliner, mini_helicopter, quadcopter | plane: 1 / 1 | **0 / 0** each |
| 7a | same | airship (floats, so it falls and crashes) | | **0** / 5 (dirt) |
| 8 | TNT (`fuse:0`) at the aircraft on the ground | plane, airliner, helicopter, quadcopter | plane: 1 / 8 | **0** / 4 to 8 (dirt) |
| 9 | Dive into the ground at 3 b/t, pitch -70 (global count) | plane, fighter, airliner, large | fighter 1, airliner 1, large 1 | **0** each, all four destroyed |
| 10 | Lethal `generic` damage 15 b up: falls and crashes | plane, helicopter, mini_helicopter, airship, quadcopter, fighter | helicopter: 1 / 7; quadcopter: 0 / 0 | **0** each (dirt only), global 0 |
| 11 | Shot down (generic 100) in the air, then punched while falling at 0 HP | helicopter | | **0** / 4 (dirt): no salvage |
| 12 | Tier 1 missile from a silo 100 b away onto the parked aircraft | plane, quadcopter | | **0** / 1 (dirt), 0 / 0 |
| 13 | `/autopilot strike 40 -60 600 150 0` (strike flight crashes on its target) | plane | 1 / 8 | **0** / 6 (dirt), global 0 |
| 14 | `/damage 100 minecraft:lava`, `in_fire`, `on_fire` | plane, helicopter, quadcopter, airliner | | **0 / 0** each |
| 15 | Standing in a lava pool for 20 s | plane, helicopter, quadcopter, airliner | | undamaged, 0 / 0 (see "Not changed") |
| 16 | `/kill` | plane, airliner, quadcopter, airship | | **0 / 0** each |
| 17 | Spawned below the world (y -150) | plane | | removed, **0 / 0** |
| 18 | `/damage 100 minecraft:player_attack by <zombie>` | plane | | **0 / 0** |
| 19 | `/damage 100 minecraft:player_attack` with no attacker | plane | | **0 / 0** |
| 20 | Ownerless arrows (20 damage) into a plane at 1 HP | plane | | **0 / 0**, plane destroyed |
| 21 | Folding dismount, survival player | plane, helicopter, airliner | | aircraft removed, **player got 1 item** each, 0 items on the ground |

Global aircraft-item count after the in-air, lava, `/kill`, void, arrow, strike and dive series: 0.

Not run headless: a real client's left click, a player's own arrow or trident, air-defence interception of a
flying aircraft, and the strike tool item. A left click and `aircraft punch` build the same damage source. A
player's projectile has the projectile as its direct entity, which `isPlayerBreak` rejects, and the
`player_attack by zombie` and ownerless-arrow cases exercise the same branch. Air defence and the strike tool end
in missile explosions and `crash()`, which cases 12 and 13 cover.

## Open questions

1. **Cargo on destruction.** The aircraft's contents (chest, supply crate, fuel, ammo, records, cargo upgrades)
   used to survive a crash inside the dropped item. Now they are lost with it. A vanilla chest boat spills its
   contents when destroyed. Should aircraft spill their upgrade containers (`Upgrade.onRemoved()`) on
   destruction? That would be a separate, small change in `crash()` and the ground-kill branch.
2. **Breaking in the air.** A player's killing hit now removes an airborne aircraft on the spot, with its item,
   instead of letting it fall and explode. This is what makes the airship and hovering helicopters breakable by
   hand. Should it be ground-only instead, which means no item for an airship at all?
3. **Automated breakers.** Fabric fake players (machines from other mods) count as players breaking the
   aircraft. Should they count as destruction instead?
