# Porting guide — **LuckyTNTMod** (`tntmod/`) → Minecraft 26.2

> **Audience:** coding agents porting the *mod* (`tntmod/`, 303 Java files).
> **Status of the library:** ✅ **DONE.** `TntLib/` (LuckyTNTLib) is already fully
> ported to 26.2, builds green, and boots on a dedicated server. You are porting
> the **mod against the already-ported library** — do not re-port the library.
> Read section 0 before touching a file, then use the verified rename map in §4.

---

## 0. STOP — the facts that matter

1. **Target is `26.2`** (year.drop scheme; "1.26.2" is not a thing — it means 26.2).
   Write `26.2` everywhere, never `1.26.2`.
2. **Yarn/Intermediary are dead.** 26.1+ is unobfuscated; use **Mojang official
   mappings**. Never emit yarn names (`Identifier`→stays `Identifier` but the *package*
   changes, `MinecraftClient`, `World`, `Item.Settings`, `class_XXXX`, …).
3. **Java 21 → Java 25.** Mixin `compatibilityLevel` must be `JAVA_25`.
4. **The library is ported and is your single best reference.** Every API pattern
   the mod needs (registration, entities, renderers, mixins, networking, explosions,
   config GUI) already exists, correct and compiling, under `TntLib/src/main/java/luckytntlib/`.
   When unsure how to do something in the mod, **find the equivalent in the ported lib first.**
5. **Verify against live source, not memory.** Your training data predates the 26.x
   rewrites. Grep the decompiled MC source and the ported lib before writing a signature.

---

## 1. Toolchain (identical to the ported library)

| | value |
|---|---|
| Minecraft | `26.2` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.154.2+26.2` |
| Loom | `1.17.13` (`id 'net.fabricmc.fabric-loom'`) |
| Gradle | **9.6.1** (Java 25 needs Gradle 9.x) |
| Java | **25** (`options.release = 25`, `sourceCompatibility/targetCompatibility = VERSION_25`) |
| Mappings | **Mojang official** — NO `mappings "net.fabricmc:yarn:…"` line |

**Gradle in this environment:** the wrapper cannot download its distribution (egress
policy blocks GitHub release assets → HTTP 403). Use the vendored distribution:
```sh
./gradle-dist/install.sh                 # unpacks to /opt/gradle-9.6.1
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
/opt/gradle-9.6.1/bin/gradle build --no-daemon      # NOT ./gradlew
```
(Java 25: `sudo apt-get install -y openjdk-25-jdk-headless`, then
`sudo update-java-alternatives -s java-1.25.0-openjdk-amd64`.)

### `tntmod/build.gradle` — the structural rewrite (mirror `TntLib/build.gradle`)
1. Plugin id `id "fabric-loom"` → `id "net.fabricmc.fabric-loom" version "${loom_version}"`.
2. **Delete** the `mappings "net.fabricmc:yarn:…"` line.
3. Drop `mod` config prefixes: `modImplementation`→`implementation`, `modApi`→`api`, `modCompileOnly`→`compileOnly`.
4. `it.options.release = 21` → `25`; Java compat → `VERSION_25`; add `it.options.encoding = "UTF-8"`.
5. **Consume the local library, not JitPack.** The current dep is
   `modImplementation "com.github.SlimingHD:Fabric-LuckyTNTLib:1.21-0.100.6.1"` — that
   1.21 artifact will NOT resolve on 26.2. Replace it with the ported lib. Two options:
   - **`includeBuild` (recommended):** in `tntmod/settings.gradle` add
     `includeBuild("../TntLib")`, and depend on `implementation "luckytntlib:fabric-luckytntlib-26.2:0.100.6.1"`
     (group/name from `TntLib/gradle.properties`). Loom will build+include the lib.
   - **flatDir jar:** point at the prebuilt jar in the repo-root `dist/`
     (`fabric-luckytntlib-26.2-0.100.6.1.jar`). Fine for a quick compile; `includeBuild`
     is better for iterating on both at once.
6. `gradle.properties`: bump `minecraft_version=26.2`, `loader_version=0.19.3`,
   `fabric_version=0.154.2+26.2`, add `loom_version=1.17.13`; rename
   `archives_base_name=fabric-luckytntmod-26.2`. Keep `mod_version`.
7. `fabric.mod.json`: `depends` → `fabricloader >=0.19.3`, `minecraft ">=26.2 <26.3"`,
   `java ">=25"`, and add a hard dep on `luckytntlib`. Bump `luckytntlib.mixins.json`-style
   mixin config `compatibilityLevel` to `JAVA_25`.

---

## 2. References, in priority order

1. **`TntLib/src/main/java/luckytntlib/`** — the ported library. Same idioms, same
   mappings, same problems already solved. **Start here.**
2. **Decompiled MC 26.2 source** — regenerate once with
   `/opt/gradle-9.6.1/bin/gradle genSources` (in `TntLib/` or `tntmod/`); it lands under
   `<project>/.gradle/loom-cache/minecraftMaven/.../minecraft-merged-*-26.2-sources.jar`.
   Unzip it and grep for exact signatures. This is ground truth.
3. **`desolation/`** (sibling repo) — an independent, working 26.2 Fabric mod. Good for
   GeckoLib/registration/networking usage patterns.
4. Fabric docs/blog (`fabricmc.net`, `docs.fabricmc.net`) for API-level guidance only.

**Rule:** never invent a signature. If you can't confirm it in (1)–(2), stop and say so.

---

## 3. The library API the mod consumes (already 26.2 — match these exactly)

The mod is built on the lib's abstractions. Their **signatures changed** in the port —
update every call site in the mod accordingly:

| `luckytntlib` symbol | 26.2 signature (as ported) |
|---|---|
| `IExplosiveEntity#getLevel()` | returns `net.minecraft.world.level.Level` (was `World`) |
| `IExplosiveEntity#getPos()` | returns `net.minecraft.world.phys.Vec3` (was `Vec3d`) |
| `IExplosiveEntity#getPersistentData()` / `setPersistentData(..)` | `net.minecraft.nbt.CompoundTag` (was `NbtCompound`) |
| `IExplosiveEntity#owner()` / `getEffect()` / `getTNTFuse()` / `x()/y()/z()` / `destroy()` | unchanged names |
| `PrimedTNTEffect` | `getBlock():Block`, `getBlockState(IExplosiveEntity):BlockState`, `getItem():Item`, `getItemStack():ItemStack`, `getSize/getDefaultFuse(IExplosiveEntity)`, `serverExplosion/explosionTick/spawnParticles/baseTick(IExplosiveEntity)`, `toBlockPos(Vec3)` — all present; block/item/nbt types are now the Mojang ones |
| `ImprovedExplosion` | now **`implements Explosion`** (interface); constructors take `Level`, `Vec3`, `Entity`; `doBlockExplosion(...)`, `doEntityExplosion(...)`, `doOldBlockExplosion(...)` unchanged in shape |
| `ExplosionHelper` | static helpers take `Level`, `Vec3` now |
| `TNTXStrengthEffect.Builder` | unchanged fluent API (`fuse/strength/xzStrength/...build()/buildTNT/buildDynamite`) |
| `RegistryHelper` | same method names (`registerTNTBlock`, `registerTNTEntity`, `registerDynamiteItem`, `registerTNTMinecart(Item)`, `registerExplosiveProjectile`, `registerLivingTNT*`, `sendS2CPacket(ServerPlayer,CustomPacketPayload)`, `sendC2SPacket(CustomPacketPayload)`, `registerConfigScreenFactory(Component,..)`); `configScreens` is now `List<Pair<Component,ConfigScreenFactory>>` |
| `LTNTBlock` / `LivingLTNTBlock` / `LuckyTNTBlock` | constructors take `BlockBehaviour.Properties`; `explode(Level, boolean, int,int,int, LivingEntity)` |
| `LDynamiteItem` / `LTNTMinecartItem` / `LuckyDynamiteItem` | constructors take `Item.Properties`; `LTNTMinecartItem#createMinecart(Level, double,double,double, LivingEntity)` |
| `PrimedLTNT` / `LivingPrimedLTNT` / `LExplosiveProjectile` / `LTNTMinecart` | ctors take `(EntityType<…>, Level, …)`; `LExplosiveProjectile implements ItemSupplier` (`getItem():ItemStack`); `LTNTMinecart#getDisplayBlockState()` |
| `LuckyTNTEntityExtension` / `EntityMixin` | additional persistent data is `CompoundTag`, stored in a plain save-backed field |
| `LTNTDataSerializers` | new: custom synced `CompoundTag` serializer, registered via `FabricEntityDataRegistry`; **only relevant if the mod adds its own synced `CompoundTag` tracked data** |

The **217 `tnteffects/*`** files are mostly pure logic on top of these abstractions — once
the types above are updated, most recompile with only type-name/import changes.

---

## 4. Verified Yarn → 26.2 rename map (same as the library used)

**Surprises (do not assume the classic Mojang name):**
- `Identifier` keeps its name, **moves package**: `net.minecraft.resources.Identifier`
  (NOT `ResourceLocation`). Build ids with `Identifier.fromNamespaceAndPath(ns, path)`
  (`Identifier.of` does **not** exist in 26.2).
- Registry-key holders are swapped: yarn `RegistryKeys` → `net.minecraft.core.registries.Registries`;
  yarn `Registries` (frozen) → `net.minecraft.core.registries.BuiltInRegistries`.
- Vanilla entity type constants live in **`EntityTypes`** (plural).

**Core / util**

| Yarn | 26.2 |
|---|---|
| `util.Identifier` | `resources.Identifier` |
| `util.math.BlockPos` | `core.BlockPos` |
| `util.math.Vec3d` | `world.phys.Vec3` |
| `util.math.Box` | `world.phys.AABB` |
| `util.math.Direction` | `core.Direction` |
| `util.math.MathHelper` | `util.Mth` |
| `util.math.random.Random` | `util.RandomSource` |
| `util.math.RotationAxis` | `com.mojang.math.Axis` (`POSITIVE_Y`→`YP`) |
| `util.hit.BlockHitResult` / `EntityHitResult` | `world.phys.*` |
| `util.Hand` | `world.InteractionHand` |
| `util.ActionResult` / `TypedActionResult` / `ItemActionResult` | `world.InteractionResult` (`use()` now returns `InteractionResult`) |
| `text.Text` / `MutableText` | `network.chat.Component` / `MutableComponent` (`Text.literal`→`Component.literal`) |
| `screen.ScreenTexts` | `network.chat.CommonComponents` |

**World / level**

| Yarn | 26.2 |
|---|---|
| `world.World` | `world.level.Level` |
| `world.WorldAccess` / `BlockView` | `world.level.LevelAccessor` / `BlockGetter` |
| `server.world.ServerWorld` | `server.level.ServerLevel` |
| `world.explosion.Explosion` | `world.level.Explosion` (**now an interface**) |
| `world.explosion.ExplosionBehavior` / `EntityExplosionBehavior` | `world.level.ExplosionDamageCalculator` / `EntityBasedExplosionDamageCalculator` |
| `fluid.FluidState` | `world.level.material.FluidState` |
| `world.event.GameEvent` | `world.level.gameevent.GameEvent` (constants are `Holder<GameEvent>`) |

**Blocks**

| Yarn | 26.2 |
|---|---|
| `block.Block/Blocks/BlockState` | `world.level.block.*` / `…block.state.BlockState` |
| `block.AbstractBlock` / `AbstractBlock.Settings` | `world.level.block.state.BlockBehaviour` / `BlockBehaviour.Properties` (`.create()`→`.of()`, needs `.setId(ResourceKey<Block>)`) |
| `block.AbstractFireBlock` / `AbstractRailBlock` | `BaseFireBlock` / `BaseRailBlock` |
| `block.MapColor` | `world.level.material.MapColor` (`RED`→`FIRE`) |
| `block.entity.BlockEntity(Type)` | `world.level.block.entity.*` |
| `block.enums.RailShape` | `world.level.block.state.properties.RailShape` (`isAscending`→`isSlope`) |
| `state.property.Properties` | `world.level.block.state.properties.BlockStateProperties` |
| `sound.BlockSoundGroup` | `world.level.block.SoundType` |
| `block.dispenser.DispenserBehavior` / `FallibleItemDispenserBehavior` / `ItemDispenserBehavior` | `core.dispenser.DispenseItemBehavior` / `OptionalDispenseItemBehavior` / `DefaultDispenseItemBehavior` (impl `execute(BlockSource,ItemStack)`) |
| `util.math.BlockPointer` | `core.dispenser.BlockSource` (record: `level()/pos()/state()/center()`) |
| `DispenserBlock.getOutputLocation` | `getDispensePosition` |

**Entities**

| Yarn | 26.2 |
|---|---|
| `entity.Entity/LivingEntity/EntityType` | `world.entity.*` |
| `entity.TntEntity` | `world.entity.item.PrimedTnt` |
| `entity.MovementType` | `world.entity.MoverType` |
| `entity.SpawnGroup` | `world.entity.MobCategory` |
| `entity.mob.PathAwareEntity` | `world.entity.PathfinderMob` |
| `entity.player.PlayerEntity` / `server.network.ServerPlayerEntity` | `world.entity.player.Player` / `server.level.ServerPlayer` |
| `entity.projectile.PersistentProjectileEntity` / `ProjectileEntity` | `world.entity.projectile.arrow.AbstractArrow` / `projectile.Projectile` |
| `entity.vehicle.AbstractMinecartEntity` / `MinecartEntity` | `world.entity.vehicle.minecart.AbstractMinecart` / `Minecart` |
| `entity.FlyingItemEntity` | `world.entity.projectile.ItemSupplier` (`getStack()`→`getItem():ItemStack`) |
| `entity.damage.DamageSource/DamageTypes` | `world.damagesource.*` (`DamageTypes.OUT_OF_WORLD`→`FELL_OUT_OF_WORLD`; `source.isOf`→`source.is`) |
| `entity.data.DataTracker` / `TrackedData` / `TrackedDataHandlerRegistry` | `network.syncher.SynchedEntityData` / `EntityDataAccessor` / `EntityDataSerializers` |
| **data-tracker define** | `SynchedEntityData.defineId(Class, EntityDataSerializers.X)`; override `defineSynchedData(SynchedEntityData.Builder b)` with `b.define(ACCESSOR, default)` |
| **entity NBT** | codec-based `addAdditionalSaveData(ValueOutput)` / `readAdditionalSaveData(ValueInput)`; `output.store(name, CODEC, v)`, `input.read(name, CODEC)`, `input.getIntOr/getShortOr(...)`, `output.putInt/putShort(...)` |
| common method renames | `getWorld()`→`level()`, `setVelocity`→`setDeltaMovement`, `getVelocity`→`getDeltaMovement`, `isOnGround`→`onGround`, `hasNoGravity`→`isNoGravity`, `getSoundCategory`→`getSoundSource`, `damage(src,amt)`→`hurtServer(ServerLevel,src,amt)`, `getYaw/setYaw`→`getYRot/setYRot`, `spawnEntity`→`addFreshEntity`, `getEntityById`→`level().getEntity(int)`, `getName()`→`getHoverName()` |

**Items**

| Yarn | 26.2 |
|---|---|
| `item.Item/Items/ItemStack/BlockItem/MinecartItem` | `world.item.*` |
| `Item.Settings` | `Item.Properties` (`.maxCount`→`.stacksTo`, needs `.setId(ResourceKey<Item>)`) |
| `item.ItemUsageContext` / `AutomaticItemPlacementContext` | `world.item.context.UseOnContext` / `DirectionalPlaceContext` |
| `item.ItemGroups` | `world.item.CreativeModeTabs` |
| `component.DataComponentTypes` | `core.component.DataComponents` |
| `use()` | returns `InteractionResult`; `useOnBlock`→`useOn(UseOnContext)`; `usageTick`→`onUseTick`; `getStackInHand`→`getItemInHand` |
| tooltip | `appendTooltip(...)` → `appendHoverText(ItemStack, Item.TooltipContext, TooltipDisplay, java.util.function.Consumer<Component>, TooltipFlag)` |
| durability | `stack.damage(...)`→`stack.hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer<Item>)`; `decrement`→`shrink` |

**Rendering (client)**

| Yarn | 26.2 |
|---|---|
| `client.MinecraftClient` | `client.Minecraft` (`setScreen(x)` → `minecraft.gui.setScreen(x)`) |
| `client.util.math.MatrixStack` | `com.mojang.blaze3d.vertex.PoseStack` |
| `render.VertexConsumerProvider` | `client.renderer.MultiBufferSource` |
| `render.entity.EntityRenderer` / `EntityRendererFactory` | `client.renderer.entity.EntityRenderer` / `EntityRendererProvider` |
| `render.entity.TntMinecartEntityRenderer` / `MinecartEntityRenderer` / `FlyingItemEntityRenderer` | `client.renderer.entity.TntMinecartRenderer` / `MinecartRenderer` / `ThrownItemRenderer` |
| `render.model.json.ModelTransformationMode` | `world.item.ItemDisplayContext` |
| `client.font.TextRenderer` | `client.gui.Font` |
| `client.gui.DrawContext` | `client.gui.GuiGraphics` |
| `client.gui.screen.Screen` | `client.gui.screens.Screen` |
| GUI widgets `ButtonWidget`/`SliderWidget`/`TextWidget` | `client.gui.components.Button`/`AbstractSliderButton`/`StringWidget` |
| layout widgets `GridWidget`/`DirectionalLayoutWidget`/`ThreePartsLayoutWidget`/`Positioner` | `client.gui.layouts.GridLayout`/`LinearLayout`/`HeaderAndFooterLayout`/`LayoutSettings` |
| **Entity renderers** | rewritten to the **render-state** model: `EntityRenderer<T, S extends EntityRenderState>` with `createRenderState()`, `extractRenderState(T,S,float)`, and (this 26.2 snapshot) a **`submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)`** method — NOT `render(...)`/`MultiBufferSource`. `getTexture(entity)` is gone. **Copy the shape from the ported lib renderers** `LTNTRenderer`, `LTNTMinecartRenderer`, `LDynamiteRenderer`, and from vanilla `TntRenderer`/`TntMinecartRenderer`/`ThrownItemRenderer`. |

**Networking / registry / Fabric**

| Yarn | 26.2 |
|---|---|
| `network.PacketByteBuf` / `RegistryByteBuf` | `network.FriendlyByteBuf` / `RegistryFriendlyByteBuf` |
| `network.codec.PacketCodec` | `network.codec.StreamCodec` (`StreamCodec.ofMember(...)`) |
| `network.packet.CustomPayload` / `CustomPayload.Id` | `network.protocol.common.custom.CustomPacketPayload` / `CustomPacketPayload.Type`, method `type()` |
| Fabric `PayloadTypeRegistry.playC2S()/playS2C()` | `serverboundPlay()` / `clientboundPlay()` |
| `registry.Registry` | `core.Registry` |
| `EntityType.Builder.create(f, grp)` | `EntityType.Builder.of(f, MobCategory)`; `.maxTrackingRange`→`.clientTrackingRange`, `.makeFireImmune`→`.fireImmune`, `.dimensions`→`.sized`, `.build(String)`→`.build(ResourceKey<EntityType<?>>)` |
| `EntityType#create(world)` | `create(Level, EntitySpawnReason)` |
| `FabricModelPredicate*` / `ItemColors` / `((FireBlock)Blocks.FIRE).registerFlammableBlock` | model-def JSON / `BlockColorRegistry` / `FlammableBlockRegistry.getDefaultInstance().add(block,burn,spread)` (`FireBlock.setFlammable` is private) |
| `ItemGroupEvents` | `net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(ResourceKey<CreativeModeTab>).register(out -> out.accept(item))` |
| `HudRenderCallback` (removed 26.1) | `net.fabricmc.fabric.api.client.rendering.v1.HudElementRegistry` |
| `sound.SoundCategory` | `sounds.SoundSource` |
| `EnchantmentHelper`/`Enchantments` | `world.item.enchantment.*` |
| `particle.ParticleEffect` | `core.particles.ParticleOptions` |

---

## 5. Mod-specific danger zones (sorted by risk)

Layout: `luckytnt` package. 303 files; **217 are `tnteffects/*`** (mostly recompile once
the lib types above are updated — touch only where they hit `getWorld`, block/entity/damage
APIs, or particles).

| Area | Files | Risk | Notes |
|---|---|---|---|
| **Mixins** | `mixin/`: `LivingEntityMixin`, `AbstractMinecartEntityMixin`, `HungerManagerMixin`, `FireBlockMixin` (common) + `GameRendererMixin`, `CameraMixin`, `InGameHudMixin` (client) | 🔴🔴 | `@Inject`/`@Redirect`/`@ModifyVariable` targets reference **exact method names + descriptors** that changed and are NOT auto-migrated. **Re-verify every target against the decompiled 26.2 source.** Two traps proven during the lib port: (a) you **cannot `@Inject` at HEAD of an abstract method** — `Entity.readAdditionalSaveData`/`addAdditionalSaveData` are abstract, so target the concrete callers `load(ValueInput)` / `saveWithoutId(ValueOutput)` instead; (b) `FireBlock` fire-spread is now `checkBurnOut(Level,BlockPos,int,RandomSource,int)` with helpers `getBurnOdds`/`getStateWithAge(LevelReader,…)`. `initDataTracker`→`defineSynchedData`. Set `compatibilityLevel: JAVA_25`. |
| **Entity renderers** | `client/renderer/*` (`BombRenderer`, `AngryMinerRenderer`, `BouncingTNTRenderer`, …), `client/model/*` | 🔴 | Full rewrite to the render-state / `submit(...)` model (see §4). Copy the ported lib renderers. |
| **Registration** | `registry/*` (~17 classes), `block/*`, `item/*`, `entity/*` | 🔴 | `.setId(ResourceKey)` on every `Item.Properties`/`BlockBehaviour.Properties`; `EntityType.Builder.of/build(ResourceKey)`; `EntityType#create(Level, EntitySpawnReason)`; `Registry.register(BuiltInRegistries.*, Identifier.fromNamespaceAndPath(..), obj)`; `FlammableBlockRegistry`; `CreativeModeTabEvents`. Mirror `luckytntlib.registry.RegistryHelper` exactly. |
| **HUD overlay** | `client/overlay/*`, `mixin/InGameHudMixin` | 🟠 | `HudRenderCallback` removed → `HudElementRegistry`; `DrawContext`→`GuiGraphics`. |
| **Items** | `item/*` | 🟠 | `use()`→`InteractionResult`, `appendHoverText`, `Item.Properties`, `hurtAndBreak`. |
| **Config GUI** | `config/*`, `client/gui/*` | 🟠 | Screen/widget/layout signature shifts (see §4); `minecraft.gui.setScreen`. Mirror the lib's `ConfigScreen`. |
| **Worldgen / features** | `feature/*`, `src/generated/data/**` (or `src/main/generated`) | 🟠 | Datapack dir layout (`data/<ns>/<registry>/…`), codec-based feature configs. **Recipe/data JSON:** crafting ingredients are now plain id strings (`"minecraft:iron_ingot"`), not `{"item": …}`. |
| **Commands / events / network** | `commands/*`, `event/*`, `network/*` | 🟢 | Networking already modern; re-map to `CustomPacketPayload`+`StreamCodec` and `serverboundPlay/clientboundPlay`. Commands: Brigadier is stable; check `ServerCommandSource`→`CommandSourceStack`. |
| **`tnteffects/*`** | 217 files | 🟢 | Recompile against the updated lib types; touch only the ones that call vanilla `getWorld`/block/entity/damage/particle APIs directly. |

---

## 6. Workflow & definition of done

1. Port `tntmod/build.gradle` + `gradle.properties` + `settings.gradle` + `fabric.mod.json`
   (§1). Point the lib dependency at the local `TntLib` (`includeBuild`) or the `dist/` jar.
2. `compileJava` first — fix by compiler error, leaning on the ported lib for every pattern.
   Expect the bulk to be mechanical renames from §4; the hard files are the 7 mixins and
   the renderers.
3. Then `build` (applies mixins at package time, processes data/resources).
4. **Smoke-test on a dedicated server** — this catches runtime-only failures the compiler
   can't (mixin targets that don't resolve, `FabricEntityDataRegistry` vs vanilla serializer
   registration, bad data JSON). It worked for the library:
   ```sh
   cd tntmod && mkdir -p run && echo "eula=true" > run/eula.txt
   JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle runServer --no-daemon
   # success = log reaches: Done (N.NNNs)! For help, type "help"   with no /ERROR] lines
   ```
   Client-only content (renderers, GUI, HUD) won't exercise on a server; validate those in
   a real client if a display is available.

**Done when:** `tntmod` compiles & the server boots to `Done (…)!` with zero errors, all TNT
types register, mixins apply, and (client) TNT/dynamite render, throw, and explode.

---

## 7. Rules for agents

- **DO** find the equivalent in the ported `TntLib/` before writing any mod code.
- **DO** verify every version-specific signature against the decompiled 26.2 source.
- **DO** re-verify every mixin `@Inject`/`@Redirect` target by hand against 26.2.
- **DON'T** emit yarn names or `class_XXXX` for 26.x.
- **DON'T** trust pre-2026 tutorials or training memory for signatures — they predate the rewrites.
- **DON'T** re-port the library — it is finished; only adapt the mod's call sites to it.
- **DON'T** invent a method name. If unsure, grep the reference or stop and ask.
- **DON'T** `@Inject` at HEAD of an abstract method — target its concrete caller.

---

## 8. Token economy — MANDATORY for every agent

1. **Environment first, no downloads.** GitHub release assets are blocked by egress
   policy (HTTP 403). NEVER run `./gradlew`, never try to download Gradle/Loom
   distributions — it will fail and waste a whole attempt. Install the vendored
   distribution once: `./gradle-dist/install.sh` → use `/opt/gradle-9.6.1/bin/gradle`
   (see §1 for Java 25 install). Verify with `gradle --version` before anything else.
2. **Mechanical renames are done by a script, not by hand.** Before any hand edits,
   run the ready-made `port-rename.sh` (repo root) ONCE. If it needs extending, how to
   write it: take every row of the §4 tables and turn it into a `perl -pi -e 's/…/…/g'`
   (or `sed -E`) rule over all `tntmod/src/**/*.java` **excluding `*/mixin/*`**
   (mixin targets must be reworked by hand). Rules in three groups, applied in order:
   (a) fully-qualified import paths (`net.minecraft.util.math.Vec3d` →
   `net.minecraft.world.phys.Vec3`), (b) bare class names with `\b` word boundaries
   (`Vec3d`→`Vec3`, `World`→`Level`, `PlayerEntity`→`Player`, …) — replace the longer
   names before their substrings, and swap the `Registries`/`RegistryKeys` pair through
   a temp placeholder so they don't overwrite each other, (c) common method renames
   (`.getWorld()`→`.level()`, `.setVelocity(`→`.setDeltaMovement(`, …).
   It is a FIRST PASS: it doesn't need to be perfect — the compiler catches leftovers.
   Sanity-check with `git diff --stat` and commit it separately before hand fixes.
3. **Work error-driven, never file-driven.** Do not read files "for context".
   Loop: `/opt/gradle-9.6.1/bin/gradle compileJava --no-daemon 2>&1 | tee /tmp/errors.txt`
   → take the first ~30 errors → open ONLY the failing lines (Read with offset/limit)
   → fix → recompile. Never re-read this guide's tables — grep this file instead.
4. **Decompiled sources: unpack once.** The setup agent runs `genSources` once and
   unzips the sources jar to a fixed path (e.g. `/opt/mc-src/`), records the path in
   `PORT-STATUS.md`. All other agents only `grep -rn` that dir — never re-generate.
5. **One smoke test.** Only the final agent runs `runServer` (§6); nobody else boots
   the server.

## 9. Rule: too hard? Disable it, keep the code

If a specific entity/item/effect has complex logic that resists porting (roughly two
honest attempts failed, or it needs an API with no equivalent found in the lib or
decompiled source): **do not block the build and do not delete the code.**

- Preferred: comment out its **registration line(s)** so the content simply doesn't
  exist in game, and stub/comment the broken method bodies so the class still compiles.
- Or comment out the whole broken block, keeping the original source in place:
  ```java
  // TODO(port-26.2): DISABLED — needs manual port (reason: <one line>)
  /* … original code untouched … */
  ```
- Every cut MUST be logged in `PORT-STATUS.md` under "Disabled content" (file, what,
  why). The goal: build green, server boots, original code preserved for a human.

## 10. Orchestrator plan — fully autonomous loop

The orchestrator NEVER asks the user anything and does not stop until done. It does
minimal work itself; agents do the porting.

**Step 0 (orchestrator itself):** install toolchain per §8.1/§1; create
`PORT-STATUS.md` (checklist of §5 areas + "Disabled content" section); run
`port-rename.sh` per §8.2; commit + push.

**Phases** (each agent gets: its role below, its exact file list, and the order to
read §0–§5 + §8–§9 of this file and `PORT-STATUS.md` first):

- **Agent A — setup/core:** `tntmod` build files per §1; `genSources` + unpack to
  `/opt/mc-src/`; fix `registry/*`, `block/*`, `item/*`, `entity/*` until they compile.
  Everything else depends on this — A runs alone, first.
- **Agent B — mixins:** the 7 mixins only (§5, top row). Verifies every target against
  `/opt/mc-src/`. Highest-risk work.
- **Agent C — client:** renderers (render-state/`submit` model — copy the ported lib
  renderers), HUD overlay, config GUI.
- **Agent D — sweeper/finisher:** remaining compile errors (`tnteffects/*`, `feature/*`,
  data JSON), full `build`, then the single `runServer` smoke test (§6).

B and C run in parallel after A (disjoint files), but must NOT run Gradle
concurrently in the same checkout — B/C fix by reading errors A/D produced, or
compile strictly one at a time.

**The loop:** after D, if `build` or `runServer` still fails → collect the error list
→ spawn a fresh sweeper agent with that list (apply §9 to anything that keeps
resisting) → repeat until the server logs `Done (…)!` with no errors. After every
phase: update `PORT-STATUS.md`, commit, push. Done = server boots green, everything
pushed, `PORT-STATUS.md` lists all disabled content.
