# PORT-STATUS — Simple Planes → Fabric / Minecraft 26.3-pre-1

`26.3/` is a copy of `26.2/` converted to **Minecraft 26.3-pre-1 / Fabric loader 0.19.5 /
Fabric API 0.159.1+26.3 / Loom 1.17.19 / Java 25**. `26.2/` is untouched and remains the 26.2
build. The heavy lifting — NeoForge → Fabric, and the 1.21.1 → 26.2 render-state and
`ValueInput`/`ValueOutput` rewrites — was done in the 26.2 port and carries over unchanged; this
document covers only what 26.2 → 26.3 broke.

## Ground truth

**`/opt/mc-src-26.3` is snapshot-9, not snapshot-10.** It reports `WORLD_VERSION = 5011`;
snapshot-10 is 5015. The two differ in ways that matter to this mod — see `TESTING.md` §2 for how
to generate a real snapshot-10 source tree with `genSources`, and for the `javap` fallback. Every
signature below was checked against snapshot-10, not against that tree.

## What broke, and how it was fixed

33 compile errors, in seven groups.

### Entity interpolation — `InterpolationHandler` became an interface

`new InterpolationHandler(this, 10)` no longer compiles and `Entity#getInterpolation()` is now
`final`, so it cannot be overridden either. 26.3 supplies the handler through a new
`protected InterpolationHandler createInterpolationHandler()` hook, and ships
`LinearInterpolationHandler` / `SteppedInterpolationHandler` as the concrete implementations.

`PlaneEntity` now overrides `createInterpolationHandler()` and returns
`LinearInterpolationHandler.create(this, 10)` — the same class `AbstractBoat` and
`AbstractMinecart` use. Behaviour is unchanged: `create` hands back `InterpolationHandler.NO_OP`
on the logical server, where nothing interpolates anyway, and the two call sites in `tickLerp`
go through `getInterpolation()` instead of the removed field.

### Entity invulnerability was split in two

`Entity#setInvulnerable(boolean)` is gone. The flag it set is now `permanentlyInvulnerable`, with
`setPermanentlyInvulnerable(boolean)`; the separate countdown is `invulnerableTime`, private, with
`setInvulnerableTime(int)` and `isTemporarilyInvulnerable()`. `isInvulnerable()` is the OR of the
two. `PlaneEntity#dropItem` wants the permanent flag.

`GunshipSortie` read `Entity#invulnerableTime` directly, and that field is private now with no
getter. It is the wrong field regardless: the timer the gunship's rate-of-fire rule is written
about is the **LivingEntity damage cooldown**, which 26.3 separated out as
`LivingEntity#damageCooldownTime` — public, set to 20 on every hit, with the same `> 10` rule that
makes a second hit free (`LivingEntity` lines 1212/1222). Switched to that. No access widener
entry was needed.

### `Level#fuelValues()` is gone — fuel is a data component now

`FuelValues` no longer exists. An item is fuel iff it carries `DataComponents.COOKING_FUEL`, and
its burn time is a `NumberProvider` resolved against a `LootContext`
(`AbstractFurnaceBlockEntity#getBurnDuration` is the vanilla call).

New helper `misc/FuelValues.java` mirrors it for the furnace engine and the fuel slot. The context
vanilla passes is a block-entity one and the only thing the stock cooking providers read out of it
is `BLOCK_STATE` — `minecraft:block/fast_cooking` matches a smoker or blast furnace and halves the
burn time. A plane's furnace engine is an entity with no block state, so the context is built over
`LootContextParamSets.EMPTY`: `MatchBlock#test` reads the parameter with `getOptionalParameter`,
gets null, evaluates false, and the ordinary burn time is selected. That is the 26.2 number —
`FuelValues` had no fast variant either. **Verified on the rig: coal burns for 1600 ticks.**

### `LivingEntity#drop` and `Inventory#placeItemBackInInventory` take a `Prediction`

Both gained a trailing `net.minecraft.util.Prediction` argument (`PREDICTED` / `SERVER_ONLY`).
All four call sites in this mod are server-side container and dismount code, so they pass
`SERVER_ONLY`, matching `AbstractContainerMenu#removed`.

### `PoseStack.mulPose(Quaternionfc)` → `rotate(Quaternionfc)`

`mulPose` survives only for `Matrix4fc` and `Transformation`. **This is one of the places
snapshot-9 and snapshot-10 disagree** — snapshot-9 still has the quaternion overload, so the
mislabelled source tree gives the wrong answer here. snapshot-10 also adds
`rotate(Axis, float)` / `rotateDegrees(Axis, float)`, which are not needed: the existing
`Axis.YP.rotationDegrees(...)` calls still return a `Quaternionf`.

### `OrderedSubmitNodeCollector#submitModel` gained a tint argument

26.2: `(model, state, pose, renderType, light, overlay, outlineColor, uvMapping)`.
26.3: `(model, state, pose, renderType, light, overlay, tintedColor, uvMapping, outlineColor)`.

Every call in this mod passed `null` for `uvMapping`, so all nine of them drop the trailing
argument and use the seven-argument default overload, which fills `tintedColor = -1` and
`uvMapping = null` — exactly what the 26.2 calls asked for.

`RenderTypes.entityTranslucentCullItemTarget(Identifier)` was removed; the armour window uses
`entityTranslucentCull(Identifier)`.

### Input moved from GLFW to SDL

`org.lwjgl.glfw` is off the client classpath entirely, and `InputConstants.Type.KEYSYM` is now
`Type.KEYBOARD`. The nine key bindings take their numbers from `InputConstants.KEY_*`, which has a
constant for every key this mod binds.

## What did NOT break

Contrary to expectation, none of these needed any work:

* **`GuiGraphics`.** It was deleted in **26.2**, not 26.3, and the 26.2 port already moved all four
  screens plus `ClientUtil` and `ModBusClientEventHandler` onto `GuiGraphicsExtractor` and
  `extractRenderState`. Nothing in `client/gui/` changed in this port.
* `SurfaceRules`, `ChunkStatus.NOISE`/`SURFACE`/`CARVERS`, `ServerLevel#getStructureManager`,
  `LevelChunk#replaceWithPacketData`, `CommandSourceStack`'s removed constructor,
  `MapColor#calculateRGBColor` — all really are changed in snapshot-10, and this mod names none
  of them.
* `ResourceKey#location()` and `ChunkPos#x` — the mod uses neither.
* The one mixin (`mixin/CameraMixin.java`) still applies as written: `Camera#alignWithEntity(float)`
  exists, and the `setPosition(DDD)V` call it injects after is still the first of that descriptor in
  that method (the other `setPosition` in there is the minecart branch's `setPosition(Vec3)`
  overload, a different descriptor, so `ordinal = 0` is unambiguous). **No new mixin was added.**
* All four `simpleplanes.accesswidener` entries still resolve — Loom's `validateAccessWidener` task
  passes.

`pack.mcmeta` had its `max_format` raised from 107 to 120 (snapshot-10 is resource-pack 96 /
data-pack 117), and `fabric.mod.json` now requires `minecraft >=26.3-alpha.0 <26.4` and
`fabricloader >=0.19.5`.

## 26.3-snapshot-10 → 26.3-pre-1

The pre-release moved the module from snapshot-10 to `26.3-pre-1` (world version 5017, resource
pack 97 / data pack 119, still inside `pack.mcmeta`'s 88–120 window) and with it to Fabric loader
**0.19.5** and Fabric API **0.159.1+26.3** — the first API build compiled against the reorganised
loot number providers below, which is why the previous 0.158.3 will not do.

**One compile error, in one file.** `net.minecraft.world.level.storage.loot.providers.number` is
split into an `ints` and a `floats` half. The sealed `ResolvableNumber` is gone: the int half is
`…providers.number.ints.ResolvableInt`, the float half `…providers.number.floats.ResolvableFloat`,
and the static readers lost the type from their names — `getIntFromItem`/`getFloatFromItem` are
both just `getFromItem` on their own interface. `NumberProvider` likewise became
`ints.ContextIntProvider` / `floats.ContextFloatProvider`, and `CookingFuel#burnTime()` now returns
`ResolvableInt`.

`misc/FuelValues#burnDuration` is the only place this mod touches any of it, and the fix is the
rename: `ResolvableNumber.getIntFromItem(...)` → `ResolvableInt.getFromItem(...)`. The arguments,
the empty-parameter-set loot context and the resolution path are unchanged, and coal still reads
1600 ticks on the rig.

The mod's own two block loot tables are plain `"rolls": 1` pools and parse unchanged; `/loot spawn`
drops both. Nothing else in the mod names a number provider.

Everything else compiled untouched: the camera mixin's target `Camera#alignWithEntity(float)` and
its `setPosition(DDD)V` call site both survive, and all four access widener entries still resolve.

Re-run headless on a `26.3-pre-1` dedicated server: clean boot, `simpleplanes 5.3.10` in the mod
list, all five entity types summoned and ticked, both blocks placed, both block loot tables spawned,
every upgrade type attached to one airframe and ticked, `/reload` clean, an `autopilot strike` on
target, an `autopilot inbound` sortie flown to a landing, taxi and parking, and airfield saved data
plus aircraft upgrade data read back across two restarts. No `ERROR`, no `WARN` and no exception
from this mod anywhere in the log.

## Verified on the rig

See `TESTING.md` for the rig itself. All of the following ran headless on a dedicated server with
this jar:

* Clean boot, no `ERROR`/`FATAL` lines, mod listed as `simpleplanes 5.3.10`.
* Two airfields surveyed (183×25 each, 2000 blocks apart), four parking stands each.
* Airfield-to-airfield sorties in both directions, at 2.60 and at 1.20 blocks/tick — park, taxi,
  line up, depart, cruise, plan the arrival at range, land, vacate, taxi in, park on a stand.
* Two aircraft ordered out of one field seconds apart: the second holds on the stand for the
  runway, replans its arrival because the runway is busy, flies an extended final, and parks on a
  **different** stand. Both survive at full health.
* `autopilot strike` — 300 blocks out, 5 blocks off the aim point.
* `gunship launch` — 64 rounds, 21 hits, 5 kills, landed. This is the `damageCooldownTime` path.
* Furnace engine burn time (`misc/FuelValues`) — coal reads 1600 ticks, the vanilla number.
* Airfields and their stands survive a `save-all` / restart round trip.

## Known, pre-existing, out of scope

**An autopilot aircraft can be lost mid-air on the return leg of a long sortie.** The trace shows
it flying level and on heading (`og=false`, `agl=48.8`) and then simply ceasing to exist —
`Plane #N lost at …, in descent` — which is the entity being removed with its chunk. Force-loading
the region makes the identical flight complete.

**This is not a port regression.** The same scenario was run against a 26.2 server with the 26.2
jar, same pinned seed, same commands: it fails there too, and the two transcripts are identical
line for line apart from the coordinates of the loss. The rolling chunk ticket
(`PlaneAutopilot#keepChunksLoaded`, radius 4, renewed every 5 ticks, `TicketType.ENDER_PEARL` at 40
ticks) is not always keeping up on the arc onto final. `TicketType.ENDER_PEARL` is unchanged in
26.3 (`register("ender_pearl", 40L, 14)`).

`Plane #N could not line up cleanly, departing anyway` on a departure from the far threshold is
likewise present on both builds.

## Not verified

* **Anything client-side at runtime.** No client was launched. The GUI, the renderers and the
  camera mixin compile, and the mixin's target was read out of the snapshot-10 sources, but no
  screen has been drawn and no plane has been ridden.
* The client-authoritative movement path, for the reason `TESTING.md` §3 gives: an autopilot
  aircraft has no rider, so the server simulates it fully and the ridden branch is never entered.
* Helicopters, the parachute, and the upgrade set beyond the furnace engine.
* Worldgen. The rig is superflat; no noise world was generated.
