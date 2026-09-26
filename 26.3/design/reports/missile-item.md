# Missile items: work report

Branch `claude/missile-item-26.3`, from `26.3-beta` at `a7bf658` (5.4.0-beta.2). The full description is
`MISSILES.md` §1d; the test results are §6c.

## What was done

- **Four items**, `simpleplanes:missile_t1` to `missile_t4` (`MissileItem`, holding its `MissileTier`). Stack 16;
  tier 3 uncommon, tier 4 rare. Tooltip: silo it fits, warhead power and fire, range, usage. In the planes tab
  right after the silo item.
- **Loading.** Use on any part of a silo of the same tier, strike or AD. The silo blocks' `useItemOn`
  (`AirDefenceSilo.use`) returns `PASS` for missile items as it already did for the silo item, so vanilla calls
  `MissileItem#useOn`. Refusals: damaged, busy (opening / launching / closing), wrong tier (names the tier needed),
  already loaded. `stack.consume` leaves creative stacks alone. An empty main hand with a missile in the off hand
  also passes, so the off hand loads instead of the main hand toggling.
- **Unloading.** Sneak + right-click with both hands empty puts the missile into the main hand. This replaces the
  mode toggle for that one gesture only (a sneaking empty-handed click used to toggle like any empty-handed click).
  Reason: upgrading is refused while loaded, and survival players had no way to empty a silo short of breaking it.
- **Drops.** `SiloStructure.noteBreak` also records the loaded tier; `dropItems` pops the missile item next to the
  silo items. Survival player breaks only, as for the silo items.
- **Recipes** (one missile each): T1 2 iron, redstone, gunpowder, rocket; T2 4 iron, redstone, TNT, rocket; T3 4
  iron, comparator, 2 TNT, fire charge, rocket; T4 a T3 missile, 4 TNT, netherite ingot, 2 iron, rocket. The
  reasoning is in `MISSILES.md` §1d.
- **Icons**: original 16x16 pixel art, `item/generated`, one per tier: tier N has N red bands as on the 3D model,
  and the size grows with the tier; T4 has the booster and interstage.
- **Test aids**: `/missile item use` takes `[item] [flags]` (`sneak`, `creative`, `offhand`; `minecraft:air` for an
  empty hand) and tries main then off hand like a client; `/missile item break [creative]` reports missile drops;
  `/missile item recipe all` checks all five recipes.
- `/missile silo load` / `unload` unchanged.

`LaunchSiloBlockEntity`, `MissileEntity`, `MissileTier` and the silo blocks are not touched. No mixins, no access
widener entries.

## Files

- new: `missile/MissileItem.java`; `items/`, `models/item/`, `textures/item/` `missile_t1..4`;
  `data/simpleplanes/recipe/missile_t1..4.json`
- changed: `missile/Missiles.java` (registration, creative tab), `airdefence/AirDefenceSilo.java` (routing),
  `missile/SiloStructure.java` (drop), `missile/MissileCommand.java` (test aids), `lang/en_us.json`, `MISSILES.md`

## Tests

Headless server on port 25712, fake player through vanilla `ServerPlayerGameMode#useItemOn` / `destroyBlock`. All
checks in `MISSILES.md` §6c passed: T1–T4 loads on master and casings, the four refusals, sneak and off-hand
loading, creative not consuming, sneak-unload, upgrade after unload, toggle unchanged, two strike launches and
three AD engagements from item-loaded silos, survival break drops (T1, T3, T4 loaded; T2 empty), creative break
drops nothing, 0 blocks changed around a full item-built silo cycle on grass, all recipes resolve after a restart.

## Not verified

- Icons, tooltips and names on a real client.
- Adventure mode (by code: `mayUseItemAt` refuses, as for the silo item).
