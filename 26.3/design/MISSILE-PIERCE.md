# Piercing missile warheads

The owner asked for the piercing blast (`design/PIERCING-BLAST.md`) on strike missiles too, with
the tier 4 missile at the maximum, as the aircraft have it. Each tier gets a piercing warhead next
to its ordinary one. The piercing warhead breaks no blocks and starts no fires. Which warhead a strike
missile carries is chosen at launch. Recipes, items and air-defence interceptors do not change.

## The tier scale

| tier | ordinary warhead | piercing power | radius R | core (certain death) | 50% in the open | piercing minimum range |
|---|---|---|---|---|---|---|
| 1 | 2, blocks | 16 | 32 | 8 | 20 | 32 |
| 2 | 4, blocks | 24 | 48 | 12 | 30 | 48 |
| 3 | 8, blocks, fire | 40 | 80 | 20 | 50 | 80 |
| 4 | 16, blocks, fire | **64** (`Blast.MAX_PIERCE_POWER`) | 128 | 32 | 80 | 128 |

Why these numbers:

- **T4 = 64** is the cap the aircraft have (`Blast.MAX_PIERCE_POWER`), as the owner asked.
- **T1 = 16** makes the smallest piercing warhead reach as far as the strongest ordinary blast: radius
  32, the entity reach of the T4 ordinary warhead (blast 16). A lower T1 (8, radius 16) would kill
  only within 4 blocks for certain. That is less than the ordinary T4 warhead and hardly worth the
  missile. At 16, T1 still clears a courtyard (core 8, half the open ground within 20).
- **T2 and T3** follow a geometric step between them. (64 / 16)^(1/3) ≈ 1.587 gives 25.4 and 40.3;
  rounded to multiples of 8 that is **24 and 40**. Each tier is then about ×1.6 in radius and ×2.5
  in area over the one below: T1→T2 ×1.5, T2→T3 ×1.67, T3→T4 ×1.6.
- A linear scale (16 / 32 / 48 / 64) was rejected. Its steps shrink from ×2 to ×1.33 in radius, so
  T4 would barely beat T3.

The piercing warhead is not clamped by tier anywhere else. Power and radius come from `MissileTier`
(`pierceWarhead`, `pierceRadius()`).

### The piercing minimum range

A piercing launch must be at least `max(minRange, R)` from the silo: 32 / 48 / 80 / 128. The silo is
then never inside its own missile's radius, so the crew is safe. The ordinary warheads keep their
minimum range (24 / 32 / 48 / 64). The owner did not ask for this rule; see the open questions.

## Probability of death per tier

The model is the one in `design/PIERCING-BLAST.md`, unchanged. With `R = 2 × power`, `x = r / R`:

```
x ≤ 0.25                        death is certain (the core)
s = (x − 0.25) / 0.75
f = 1 − 0.5 × (1 − e)           e = exposure, the share of 9 rays from the body to the centre not blocked
u = s / f
P(death) = 1 − (3u² − 2u³)      for u < 1, else 0
```

Armour and enchantments do not count (the damage type bypasses both). The same `x` is the same
probability for every tier; the tiers differ only in distance.

| x | T1 (blocks) | T2 | T3 | T4 | open (e = 1) | half cover (e = 0.5) | full cover (e = 0) |
|---|---|---|---|---|---|---|---|
| 0.25 | 8 | 12 | 20 | 32 | 100% | 100% | 100% |
| 0.3125 | 10 | 15 | 25 | 40 | 98.0% | 96.6% | 92.6% |
| 0.375 | 12 | 18 | 30 | 48 | 92.6% | 87.4% | 74.1% |
| 0.4375 | 14 | 21 | 35 | 56 | 84.4% | 74.1% | 50.0% |
| 0.5 | 16 | 24 | 40 | 64 | 74.1% | 58.3% | 25.9% |
| 0.625 | 20 | 30 | 50 | 80 | 50.0% | 25.9% | 0 |
| 0.75 | 24 | 36 | 60 | 96 | 25.9% | 3.4% | 0 |
| 0.875 | 28 | 42 | 70 | 112 | 7.4% | 0 | 0 |
| 1.0 | 32 | 48 | 80 | 128 | 0 | 0 | 0 |

Anyone who survives is wounded: they take `0.75 × maxHealth × (1 − u)` and are knocked back. At full
health this is never lethal on its own.

## Choosing the warhead

A missile item carries no warhead. The warhead is set on the silo, or given for one launch.

| path | how the warhead is chosen |
|---|---|
| `/missile silo warhead <pos> [blast\|pierce]` | Sets the silo's warhead. The setting is saved with the silo, synced to clients and carried over when the silo is upgraded. It can be changed in any phase and in either mode. Without an argument, the command prints the setting. |
| `/missile launch <silo> <target> [pierce\|blast]` | Without a keyword, the silo's setting is used. With one, that launch only; the setting is untouched. |
| Launch from the map (`AviationMap.requestLaunch`, the 4-argument form) | The silo's setting. |
| `AviationMap.requestLaunch(silo, x, y, z, piercing)` | That launch only, as the command keyword does. The same path is used for a remote launch whose silo chunk has to be loaded first (`RemoteLaunch`). |
| `AviationMap.requestWarhead(silo, piercing)` | Sets the silo's setting from the map, with the permission `/missile silo warhead` needs. |
| Air defence | Interceptors always carry the ordinary warhead, whatever the setting. |

Tooltip and status:

- The missile tooltip shows both warheads.
- Loading a missile into a piercing silo says so.
- `missile silo status` prints `warhead piercing 64.0 (radius 128, entities only)`. In air-defence
  mode it adds "interceptors keep the ordinary blast". While a launch under way differs from the
  setting, it adds "(this launch: …)".
- The missile report shows `blast=64.0,pierce`, `inert(pierce)` or `suppressed(pierce)`.

The detonation goes through `Blast.detonate`, as for aircraft:

- the game rule `simpleplanes:missile_explosions false` makes the warhead inert;
- blast guards are asked exactly as for an aircraft, so a MineColonies colony's guard lets a piercing
  blast through unchanged (no blocks, no fire to take away);
- `/blastguard off` stops the guards being asked.

### Why not a separate item

1. The recipes stay as they are, as the owner asked.
2. The same silo and the same stock of missiles serve both uses. A player choosing between a crater
   and a clean strike does not have to empty the silo and craft another missile.
3. The map can choose the warhead per launch, which it could not do with an item already sitting in
   the silo.
4. It saves four items, four recipes, textures, creative-tab entries and loot/sync paths.

A separate item would be better only if the owner wants piercing missiles to cost more than ordinary
ones. That would be a recipe decision; it is not built here.

## MineColonies world map (API 4)

Everything below is additive; API 3 callers compile and run unchanged.

| call | what it does |
|---|---|
| `AviationMap.API_VERSION` | 4. Read it reflectively and offer the toggle only at `>= 4`. |
| `AviationMap.canChooseWarhead()` | Whether the server takes the two new requests (both payload types can be sent). |
| `AviationSnapshot.Silo#piercing()` | The silo's setting. |
| `AviationSnapshot.Silo#pierceMinRange()`, `#pierceRadius()` | The piercing minimum range and radius for this tier, for drawing and for greying out targets that are too close. |
| `AviationSnapshot.Silo#minRange(boolean piercing)` | The minimum range for either warhead. |
| `AviationMap.requestLaunch(BlockPos silo, int x, int y, int z, boolean piercing)` | Launch with that warhead, this launch only. The reply is the usual `LaunchResult`. |
| `AviationMap.requestWarhead(BlockPos silo, boolean piercing)` | Set the silo's warhead. The reply comes on the action bar, then a fresh snapshot. The silo's chunk must be loaded; if it is not, the map should offer the warhead with the launch instead. |

`AviationPayloads.PROTOCOL` is 4. The server answers only a client with the same protocol. The two new
requests are separate payload types (`aviation_launch_warhead` and `aviation_warhead`), so
`canSend` tells whether the server has them.

## Measurements

Real time, missile rig of `MISSILES.md` §5. The recipe is in `TESTING.md` under "Recipe: piercing
missile warheads".

The targets were rings of 20 AI-less zombies at `x` = 0.15 … 1.05, in full netherite with Protection IV
or Blast Protection IV, alternating. Every missile arrived with a miss of 0.00.

Deaths per ring:

| tier | runs | 0.15 | 0.30 | 0.45 | 0.60 | 0.75 | 0.90 | 1.05 |
|---|---|---|---|---|---|---|---|---|
| T1 (16) | 3 | 60/60 | 59/60 | 51/60 | 28/60 | 17/60 | 2/60 | 0/60 |
| T2 (24) | 3 | 60/60 | 58/60 | 53/60 | 33/60 | 17/60 | 5/60 | 0/60 |
| T3 (40) | 3 | 60/60 | 60/60 | 47/60 | 29/60 | 11/60 | 3/60 | 0/60 |
| T4 (64) | 8 | 160/160 | 160/160 | 130/160 | 93/160 | 37/160 | 8/160 | 0/160 |
| all | 17 | 100% | 99.1% | 82.6% | 53.8% | 24.1% | 5.3% | 0 |
| model (open) | | 100% | 98.7% | 82.5% | 55.0% | 25.9% | 4.9% | 0 |

- **Blocks.** No piercing hit changed any block: 17 ring runs; one hit on a planks hut with a glass
  roof, aimed at the roof; one with the game rule off.
- **Ordinary warheads, unchanged.** Launched with `blast` from a piercing silo:
  - T4: `blast=16.0,blocks,fire`, 1643 blocks changed (earlier measurements: 1609–1681);
  - T1: `blast=2.0,blocks`, 17 changed (earlier: 17).
- **Cost of the T4 blast.** The explode call took 5.9–11.6 ms with 120 zombies in range, and 280 ms
  with 250 MineColonies citizens.
- **MineColonies 0.0.94**, headless colony of 250 citizens, all within 32 blocks of the town hall; a
  piercing T4 onto the town hall:
  - 250 in range, 250 lethal rolls;
  - 224 were downed as CRITICAL INJURY;
  - 26 (10.4%) died outright with a grave, which is MineColonies' own 10% instant-death chance;
  - no colony block changed; the only changed blocks were the 26 graves.
