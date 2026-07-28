# NOTES-A — NeoForge 1.21.1 → Fabric 26.2: core / registration / containers / recipes / data

Recipe sheet from Agent A's pass on Simple Planes. **Every entry below was verified** against the
decompiled sources at `/opt/mc-src/…` or against a working Fabric 26.2 mod on disk
(`/home/user/Fabric-LuckyTNTMod/{TntLib,tntmod}`). Paths are given per row.

---

## 0. The one that invalidates the brief: `ResourceLocation` is now `Identifier`

`net.minecraft.resources.ResourceLocation` **does not exist in 26.2**. It was renamed to
`net.minecraft.resources.Identifier` in Mojang's own mappings.

| check | result |
|---|---|
| `ls /opt/mc-src/net/minecraft/resources/` | `Identifier.java`, no `ResourceLocation.java` |
| `/opt/mc-src/net/minecraft/resources/Identifier.java:18` | `public final class Identifier implements Comparable<Identifier>` |
| `Fabric-LuckyTNTMod/TntLib/src/main/java/luckytntlib/registry/RegistryHelper.java:37` | `import net.minecraft.resources.Identifier;` |
| `/home/user/desolation/src/main/java/raltsmc/desolation/world/structure/AshTinkerBaseStructure.java:5` | `import net.minecraft.resources.Identifier;` |

`Identifier` is **not** a Yarn name here — Mojang adopted it. `ResourceKey` kept its name.
Static factories are unchanged: `Identifier.fromNamespaceAndPath(ns, path)`, `Identifier.parse(s)`,
`Identifier.withDefaultNamespace(s)`, `Identifier.tryParse(s)`, `Identifier.CODEC`,
`Identifier.STREAM_CODEC` (`/opt/mc-src/net/minecraft/resources/Identifier.java:19-52`).

---

## 1. Registration: `DeferredRegister` → eager `Registry.register`

Pattern that preserves the `Supplier<T>` field shape (contract C1), mirroring
`TntLib/.../registry/RegistryHelper.java:205,210,489`:

```java
private static <T extends Item> Supplier<T> register(String name, Function<Item.Properties, T> factory, Item.Properties props) {
    T value = Registry.register(BuiltInRegistries.ITEM,
        Identifier.fromNamespaceAndPath(MODID, name),
        factory.apply(props.setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MODID, name)))));
    return () -> value;
}
```

`Registry.register` overloads (`/opt/mc-src/net/minecraft/core/Registry.java:106-118`):
`register(Registry<? super T>, String, T)`, `register(Registry<V>, Identifier, T extends V)`,
`register(Registry<V>, ResourceKey<V>, T)`. There is also `registerForHolder(...)` returning
`Holder.Reference<T>` (line 120) when you need a `Holder`.

| registry | `BuiltInRegistries` field | source |
|---|---|---|
| items | `ITEM` (`Registry<Item>`) | BuiltInRegistries.java |
| blocks | `BLOCK` | |
| block entities | `BLOCK_ENTITY_TYPE` | |
| entities | `ENTITY_TYPE` | |
| menus | `MENU` | |
| sounds | `SOUND_EVENT` | |
| creative tabs | `CREATIVE_MODE_TAB` (line 293) — **not** `Registries.CREATIVE_MODE_TAB` for `Registry.register` | |
| data components | `DATA_COMPONENT_TYPE` (line 296) | |
| recipe types / serializers | `RECIPE_TYPE` (203) / `RECIPE_SERIALIZER` (204) | |
| recipe book categories | `RECIPE_BOOK_CATEGORY` (328) | |

### Mandatory `setId(...)` on properties

| class | method | source |
|---|---|---|
| `Item.Properties` | `Item.Properties setId(ResourceKey<Item>)` | `/opt/mc-src/net/minecraft/world/item/Item.java:627` |
| `BlockBehaviour.Properties` | `Properties setId(ResourceKey<Block>)` | `.../block/state/BlockBehaviour.java:1278` |

Missing it → `NullPointerException: Item id not set` at registration.

### `EntityType`

```java
EntityType<T> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, id,
    EntityType.Builder.of(factory, MobCategory.MISC)
        .sized(w, h).clientTrackingRange(5).updateInterval(3)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, id)));   // build() takes a ResourceKey now
```
`/opt/mc-src/net/minecraft/world/entity/EntityType.java:487` (`Builder.of`), `:595` (`build(ResourceKey<EntityType<?>>)`).
The old public 12-arg `new EntityType<>(factory, category, …, FeatureFlags.VANILLA_SET)` constructor
shape from 1.21.1 no longer matches.

`EntityType#create` now needs a spawn reason:
`create(Level, EntitySpawnReason)` (`EntityType.java:300`). Values at
`/opt/mc-src/net/minecraft/world/entity/EntitySpawnReason.java` — `SPAWN_ITEM_USE`, `MOB_SUMMONED`,
`COMMAND`, `TRIGGERED`, `LOAD`, … It returns `@Nullable T`.

### `BlockEntityType`

Constructor lost the datafixer arg: `new BlockEntityType<>(BlockEntitySupplier<T>, Set<Block>)`
— 2 args, no trailing `null` (`/opt/mc-src/net/minecraft/world/level/block/entity/BlockEntityType.java:18`).

### Custom (modded) registry — NeoForge `RegistryBuilder` → Fabric

```java
public static final ResourceKey<Registry<UpgradeType>> KEY =
    ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(MODID, "upgrade_types"));
public static final Registry<UpgradeType> UPGRADE_TYPE =
    FabricRegistryBuilder.create(KEY).attribute(RegistryAttribute.SYNCED).buildAndRegister();
```
Verified with `javap` on
`~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-registry-sync-v0/**.jar`:
`FabricRegistryBuilder.create(ResourceKey<Registry<T>>) → FabricRegistryBuilder<T, MappedRegistry<T>>`,
`.attribute(RegistryAttribute)`, `.buildAndRegister()`. `RegistryAttribute` = `SYNCED|MODDED|OPTIONAL`.
There is **no** `NewRegistryEvent` equivalent — the builder registers immediately.

### Creative tabs

`FabricCreativeModeTab.builder()` → `CreativeModeTab.Builder` (javap on
`fabric-creative-tab-api-v1`; used in `tntmod/src/main/java/luckytnt/registry/LuckyTNTTabs.java:25`).
Vanilla `CreativeModeTab.builder()` now needs `(Row, int column)`
(`/opt/mc-src/net/minecraft/world/item/CreativeModeTab.java:49`) — use the Fabric one instead.
Then `Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, tab)` (LuckyTNTTabs.java:78).

### Data components

`DataComponentType.builder().persistent(codec).networkSynchronized(streamCodec).build()`
(`/opt/mc-src/net/minecraft/core/component/DataComponentType.java:26,53,60`), then
`Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id, type)`.

**Dead end:** NeoForge's `ItemStack#set(Supplier<DataComponentType<T>>, T)` /
`get(Supplier<…>)` overloads do not exist in vanilla — `ItemStack.set/get` take the raw
`DataComponentType`. If some call sites in the codebase write `FOO` and others `FOO.get()`, register
a wrapper that implements *both* `DataComponentType<T>` and `Supplier<DataComponentType<T>>`
(delegating `codec()`, `streamCodec()`, `ignoreSwapAnimation()`); everything then compiles unchanged.

---

## 2. Entrypoint & events

| NeoForge | Fabric 26.2 |
|---|---|
| `@Mod(MODID)` + ctor `(IEventBus, ModContainer)` | `implements ModInitializer` / `onInitialize()` |
| `FMLCommonSetupEvent` + `event.enqueueWork(…)` | just run it at the end of `onInitialize()` — registries are already populated |
| `RegisterCapabilitiesEvent` | **gone**, no replacement; look the target object up directly |
| `@EventBusSubscriber` + `PlayerInteractEvent.RightClickItem` | `UseItemCallback.EVENT.register((Player, Level, InteractionHand) -> InteractionResult)` |

`UseItemCallback` verified via javap on `fabric-events-interaction-v0`:
`InteractionResult interact(Player, Level, InteractionHand)`. Same jar also has
`BlockEvents$UseItemOnCallback`, `ItemEvents$UseCallback`, `AttackEntityCallback`,
`PlayerPickItemEvents`, `UseEntityCallback`, `UseBlockCallback`.

---

## 3. Config

NeoForge `ModConfigSpec` has no Fabric counterpart and no vanilla one. Cheapest port that keeps
`XXX.get()` call sites: a class of `public static final Supplier<Boolean|Integer|Double>` constants
holding the old TOML defaults. Log it as a §9 cut.

---

## 4. Reload listeners (datapack JSON)

**Dead end:** `SimpleJsonResourceReloadListener` still exists but became
`SimpleJsonResourceReloadListener<T> extends SimplePreparableReloadListener<Map<Identifier, T>>`
and is **codec-driven** — the old `super(GSON, "dir")` + `apply(Map<ResourceLocation, JsonElement>, …)`
shape is gone (`/opt/mc-src/net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener.java:23-38`).

If you want to keep raw Gson parsing, extend `SimplePreparableReloadListener<Map<Identifier, JsonElement>>`
and scan yourself:

```java
private static final FileToIdConverter LISTER = FileToIdConverter.json("plane_payload");

protected Map<Identifier, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
    Map<Identifier, JsonElement> out = new HashMap<>();
    for (Map.Entry<Identifier, Resource> e : LISTER.listMatchingResources(manager).entrySet()) {
        try (Reader r = e.getValue().openAsReader()) { out.put(LISTER.fileToId(e.getKey()), StrictJsonParser.parse(r)); }
        catch (Exception ex) { LOGGER.error(…); }
    }
    return out;
}
protected void apply(Map<Identifier, JsonElement> map, ResourceManager manager, ProfilerFiller profiler) { … }
```
`SimplePreparableReloadListener` signatures: `/opt/mc-src/.../SimplePreparableReloadListener.java:22-24`.
`FileToIdConverter.json(prefix)` / `.fileToId(Identifier)` / `.listMatchingResources(ResourceManager)`:
`/opt/mc-src/net/minecraft/resources/FileToIdConverter.java:11,23`.
`StrictJsonParser.parse(Reader)`: `/opt/mc-src/net/minecraft/util/StrictJsonParser.java:16`.

Registration replaces `AddReloadListenerEvent`:
```java
ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(Identifier, PreparableReloadListener);
```
javap on `fabric-resource-loader-v1`:
`net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(PackType)` +
`registerReloadListener(Identifier, PreparableReloadListener)` and
`addListenerOrdering(Identifier, Identifier)`. Ordering anchors live in
`net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys.{BEFORE,AFTER}_VANILLA`.
(`fabric-resource-loader-v0`'s `ResourceManagerHelper` still exists but v1 is the current API.)

Other registry-lookup fixes hit here:
`BuiltInRegistries.X.get(Identifier)` now returns `Optional<Holder.Reference<T>>`
(`/opt/mc-src/net/minecraft/core/Registry.java:133`). For the old nullable value use
**`getValue(Identifier)`** (line 67). `getTag(TagKey)` is gone — the tag lookup is
`registry.get(TagKey) → Optional<HolderSet.Named<T>>` (`HolderLookup.java:121`).

`TagParser.parseTag(String)` → **`TagParser.parseCompoundFully(String)`**
(`/opt/mc-src/net/minecraft/nbt/TagParser.java:60`).

---

## 5. Menus / containers

| NeoForge | Fabric 26.2 |
|---|---|
| `IMenuTypeExtension.create(factory)` (menu with extra spawn data) | `new ExtendedMenuType<T, D>(ExtendedFactory<T,D>, StreamCodec<? super RegistryFriendlyByteBuf, D>)` |
| `player.openMenu(provider, buf -> …)` | `player.openMenu(ExtendedMenuProvider<D>)` — implement `D getScreenOpeningData(ServerPlayer)` |
| client ctor `(int, Inventory, FriendlyByteBuf)` | client ctor `(int, Inventory, D)` |
| plain menu | `new MenuType<>(MenuSupplier<T>, FeatureFlags.VANILLA_SET)` (unchanged) |

Package is `net.fabricmc.fabric.api.menu.v1` (module **`fabric-menu-api-v1`**, *not*
`fabric-screen-handler-api-v1`). javap output:
```
ExtendedMenuType<T extends AbstractContainerMenu, D> extends MenuType<T>
  ExtendedMenuType(ExtendedMenuType$ExtendedFactory<T,D>, StreamCodec<? super RegistryFriendlyByteBuf, D>)
ExtendedMenuType$ExtendedFactory<T,D>: T create(int, Inventory, D)
ExtendedMenuProvider<D> extends MenuProvider: D getScreenOpeningData(ServerPlayer)
```
`ByteBufCodecs.VAR_INT` is `StreamCodec<ByteBuf, Integer>`; `FriendlyByteBuf extends ByteBuf`
(`/opt/mc-src/net/minecraft/network/FriendlyByteBuf.java:71`), so `? super RegistryFriendlyByteBuf`
accepts it and `Foo::new` binds to an `int` ctor by unboxing.

### `ItemStackHandler` / `IItemHandler` / `SlotItemHandler`

All gone. Two workable substitutions:

* `SimpleContainer` (`/opt/mc-src/net/minecraft/world/SimpleContainer.java`) + plain
  `new Slot(Container, index, x, y)` (`/opt/mc-src/net/minecraft/world/inventory/Slot.java:17`).
* A hand-written `implements Container` class keeping the NeoForge method names
  (`getSlots/getStackInSlot/setStackInSlot/insertItem/extractItem/setSize/serializeNBT/deserializeNBT`)
  when you must not touch hundreds of call sites. Because it implements `Container`, vanilla `Slot`
  works over it directly.

`Container` (`/opt/mc-src/net/minecraft/world/Container.java:19`) extends `Clearable, Iterable<ItemStack>,
SlotProvider`; abstract methods are `getContainerSize, isEmpty, getItem, removeItem,
removeItemNoUpdate, setItem, setChanged, stillValid` (+ `clearContent()` from `Clearable`).
Note `startOpen`/`stopOpen` now take `ContainerUser`, not `Player`.

Item persistence helpers: `ContainerHelper.saveAllItems(ValueOutput, NonNullList<ItemStack>[, boolean])`
/ `loadAllItems(ValueInput, NonNullList<ItemStack>)`
(`/opt/mc-src/net/minecraft/world/ContainerHelper.java:21,40`) — they write an `"Items"` list of
`ItemStackWithSlot`.

**Fuel check:** `ItemStack#getBurnTime(RecipeType)` is gone. Burn time is data-driven:
`Level#fuelValues()` → `FuelValues#isFuel(ItemStack)` / `burnDuration(ItemStack)`
(`/opt/mc-src/net/minecraft/world/level/Level.java:1107`,
`/opt/mc-src/net/minecraft/world/level/block/entity/FuelValues.java:26,34`). A `Slot` has no `Level`,
so pass a `Supplier<Level>` into the slot.

---

## 6. Recipes

`RecipeSerializer` is **no longer an interface to implement** — it is a record:
```java
public record RecipeSerializer<T extends Recipe<?>>(MapCodec<T> codec, StreamCodec<RegistryFriendlyByteBuf, T> streamCodec) {}
```
(`/opt/mc-src/net/minecraft/world/item/crafting/RecipeSerializer.java:7`). Delete the serializer
class, keep the two codecs, and register `new RecipeSerializer<>(CODEC, STREAM_CODEC)`.

`RecipeType` has no `RecipeType.simple(Identifier)`; register an anonymous instance:
`Registry.register(BuiltInRegistries.RECIPE_TYPE, id, new RecipeType<MyRecipe>() {})`
(mirrors `/opt/mc-src/net/minecraft/world/item/crafting/RecipeType.java:16`).

`Recipe<T extends RecipeInput>` interface changed (`/opt/mc-src/.../crafting/Recipe.java:18-42`):

| 1.21.1 | 26.2 |
|---|---|
| `assemble(T, HolderLookup.Provider)` | `ItemStack assemble(T input)` |
| `getResultItem(HolderLookup.Provider)` | **removed** |
| `canCraftInDimensions(int,int)` | **removed** |
| — | `boolean showNotification()` **(new, required)** |
| — | `String group()` **(new, required)** |
| — | `PlacementInfo placementInfo()` **(new, required)** — `PlacementInfo.NOT_PLACEABLE` is fine |
| — | `RecipeBookCategory recipeBookCategory()` **(new, required)** — `RecipeBookCategories.CRAFTING_MISC` |
| `getSerializer()` returns `RecipeSerializer<?>` | returns `RecipeSerializer<? extends Recipe<T>>` |

`ItemStack.STRICT_CODEC` **does not exist** in 26.2 — use `ItemStack.CODEC`
(`/opt/mc-src/net/minecraft/world/item/ItemStack.java:122`). `Ingredient.CODEC` and
`Ingredient.CONTENTS_STREAM_CODEC` are unchanged (`.../crafting/Ingredient.java:27,34`).

### Reading recipes from a menu (client-side!)

`Level#getRecipeManager()` is gone. `Level#recipeAccess()` returns `RecipeAccess`
(`/opt/mc-src/net/minecraft/world/level/Level.java:1064`); only the *server* one is a `RecipeManager`.
`getAllRecipesFor(type)` is now `getAllOfType(type)` and lives on Fabric's `FabricRecipeManager`
(server-only). To list a custom recipe type on both sides:

```java
SimplePlanesRecipes.init():  RecipeSynchronization.synchronizeRecipeSerializer(SERIALIZER);
in the menu:                 level.recipeAccess().getSynchronizedRecipes().getAllOfType(TYPE);
```
javap on `fabric-recipe-api-v1`: `RecipeSynchronization.synchronizeRecipeSerializer(RecipeSerializer<?>)`,
`FabricRecipeAccess.getSynchronizedRecipes() → SynchronizedRecipes`,
`SynchronizedRecipes.getAllOfType(RecipeType<T>) → Collection<RecipeHolder<T>>`.

---

## 7. Blocks & block entities

| 1.21.1 | 26.2 | source |
|---|---|---|
| `Block#onRemove(BlockState, Level, BlockPos, BlockState, boolean)` | **removed** → `protected void affectNeighborsAfterRemoval(BlockState, ServerLevel, BlockPos, boolean movedByPiston)` | `BlockBehaviour.java:173`, `ChestBlock.java:256` |
| dropping BE contents in `onRemove` | `BlockEntity#preRemoveSideEffects(BlockPos, BlockState)` — runs **before** the BE is detached (`LevelChunk.java:307-315`) | `BlockEntity.java:235`, `AbstractFurnaceBlockEntity.java:376` |
| `saveAdditional(CompoundTag, HolderLookup.Provider)` | `protected void saveAdditional(ValueOutput)` | `BlockEntity.java:109` |
| `loadAdditional(CompoundTag, HolderLookup.Provider)` | `protected void loadAdditional(ValueInput)` | `BlockEntity.java:97` |
| `Containers.dropItemStack(...)` | unchanged (`Containers.java:32`); `updateNeighboursAfterDestroy(BlockState, Level, BlockPos)` at `:49` | |

`ValueInput` getters (`/opt/mc-src/net/minecraft/world/level/storage/ValueInput.java`):
`getIntOr/getShortOr/getLongOr/getFloatOr/getDoubleOr/getBooleanOr/getByteOr/getStringOr(name, def)`,
`getInt/getString/getLong → Optional`, `child(name) → Optional<ValueInput>`,
`childOrEmpty(name)`, `list(name, codec)`, `read(name, Codec)`.
`ValueOutput` (same dir): `putInt/putString/…`, `child(name) → ValueOutput`,
`list(name, codec)`, `store(name, Codec, T)`, `discard(name)`.

CompoundTag ↔ ValueInput/Output bridges:
```java
TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
… ; CompoundTag tag = out.buildResult();
ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, registries, tag);
```
(`/opt/mc-src/net/minecraft/world/level/storage/TagValueOutput.java:27,152`,
`TagValueInput.java:40`, `/opt/mc-src/net/minecraft/util/ProblemReporter.java:18`).

`Entity#readAdditionalSaveData` / `addAdditionalSaveData` are **`protected abstract`** in 26.2
(`/opt/mc-src/net/minecraft/world/entity/Entity.java:2208-2210`) — they were public in 1.21.1.
Cross-package callers (e.g. an item spawning a configured entity) need a public bridge on your own
entity class. Do **not** access-widen `Entity#readAdditionalSaveData`: your subclass would then be
reducing visibility and javac rejects it. `Entity#load(ValueInput)` is public but resets position
from the tag, so it is not a substitute.

---

## 8. Items

| 1.21.1 | 26.2 | source |
|---|---|---|
| `InteractionResultHolder<ItemStack> use(Level, Player, InteractionHand)` | `InteractionResult use(Level, Player, InteractionHand)` | `Item.java:188` |
| `InteractionResultHolder.sidedSuccess(stack, isClient)` | `level.isClientSide ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER` | `InteractionResult.java:11-16` |
| `InteractionResultHolder.pass/fail(stack)` | `InteractionResult.PASS` / `InteractionResult.FAIL` | |
| `appendHoverText(ItemStack, TooltipContext, List<Component>, TooltipFlag)` | `appendHoverText(ItemStack, Item.TooltipContext, TooltipDisplay, Consumer<Component>, TooltipFlag)` | `Item.java:323`, `net/minecraft/world/item/component/TooltipDisplay.java` |
| `Item#isEnchantable/getEnchantmentValue/supportsEnchantment` | **removed** → `Item.Properties#enchantable(int)` = `DataComponents.ENCHANTABLE` | `Item.java:433`, `DataComponents.java:190` |
| `ItemStack#onCraftedBy(Level, Player, int)` | `ItemStack#onCraftedBy(Player, int)`; `Item#onCraftedBy(ItemStack, Player)` | `ItemStack.java:721`, `Item.java:291` |
| `BlockItem(Block, Item.Properties)` | unchanged, but add `.useBlockDescriptionPrefix()` for the `block.` translation key | `Item.java:637` |

`CompoundTag` getters return `Optional` in 26.2: `getString(name) → Optional<String>`,
`getInt(name) → Optional<Integer>`, `getCompound(name) → Optional<CompoundTag>`; the non-Optional
forms are `getStringOr(name, def)`, `getIntOr(name, def)`, `getCompoundOrEmpty(name)`.
`getAllKeys()` → **`keySet()`**. (`/opt/mc-src/net/minecraft/nbt/CompoundTag.java:193,299,331,351,355`)

`Level#getEntities(null, aabb)` is now ambiguous against the `EntityTypeTest` overload — cast:
`getEntities((Entity) null, aabb)` (`/opt/mc-src/net/minecraft/world/level/EntityGetter.java:19,21,29`).

---

## 9. Data / resource JSON

### Recipes — plain-string ingredients
`{"tag": "c:ingots/iron"}` → `"#c:ingots/iron"`; `{"item": "minecraft:stick"}` → `"minecraft:stick"`;
applies to `key` values, `ingredients` entries and any custom `ingredient` field.
Verified against `tntmod/src/main/resources/data/luckytntmod/recipe/craft_acidic_tnt.json`
(355 already-migrated recipes) and `Ingredient.CODEC = HolderSetCodec.create(Registries.ITEM, …)`.
`result` keeps the `{"id": …, "count": …}` object form.

Convention-tag renames worth knowing (contents of `fabric-convention-tags-v2`'s
`data/c/tags/item/`): `c:slimeballs` → **`c:slime_balls`**. Everything else the mod used exists
unchanged: `c:ingots/{iron,copper}`, `c:storage_blocks/{iron,gold,redstone}`, `c:gems/{lapis,diamond,quartz}`,
`c:rods/blaze`, `c:obsidians/normal`, `c:dusts/redstone`, `c:glass_blocks/colorless`, `c:strings`.

### Item model definitions (1.21.4+)
`assets/<ns>/models/item/foo.json` stays as-is, and a **new** `assets/<ns>/items/foo.json` is required
per registered item:
```json
{ "model": { "type": "minecraft:model", "model": "<ns>:item/foo" } }
```
(mirrors `tntmod/src/main/resources/assets/luckytntmod/items/*.json`).
Item colour providers are gone; tints go in that file, e.g.
```json
"tints": [ { "type": "minecraft:constant", "value": 11702101 } ]
```
Model types are registered at `/opt/mc-src/net/minecraft/client/renderer/item/ItemModels.java:22-30`
(`empty`, `model`, `range_dispatch`, `special`, `composite`, `select`, `condition`);
`tints` field on the `model` type: `CuboidItemModelWrapper.Unbaked` (`:129-135`);
tint sources at `/opt/mc-src/net/minecraft/client/color/item/` (`Constant`, `Dye`, `MapColor`,
`GrassColorSource`, `Potion`, `TeamColor`, `Firework`, `CustomModelDataSource`).

### `pack.mcmeta`
`SharedConstants`: `RESOURCE_PACK_FORMAT_MAJOR = 88`, `MINOR = 0`; `DATA_PACK_FORMAT_MAJOR = 107`,
`MINOR = 1` (`/opt/mc-src/net/minecraft/SharedConstants.java:27-33`).
Above the "last pre-minor" version (64 for resources, 81 for data,
`/opt/mc-src/net/minecraft/server/packs/metadata/pack/PackFormat.java:64-68`), `pack_format` and
`supported_formats` are **rejected**; you must use `min_format`/`max_format`, and a mod's single
pack.mcmeta has to span both pack types:
```json
{ "pack": { "description": "…", "min_format": 88, "max_format": 107 } }
```
(`min_format` uses `BOTTOM_CODEC` → bare int means `.0`; `max_format` uses `TOP_CODEC` → bare int
means `.MAX`, so 107 covers 107.1.)

### Directory layout (unchanged from 1.21.5+, confirmed against tntmod)
`data/<ns>/recipe/`, `data/<ns>/loot_table/blocks/`, `data/<ns>/tags/{block,item}/`,
`data/minecraft/tags/block/mineable/…`, `assets/<ns>/blockstates/`, `assets/<ns>/models/{block,item}/`,
`assets/<ns>/items/`. Block loot table id is `<ns>:blocks/<name>`
(`/opt/mc-src/net/minecraft/world/level/block/state/BlockBehaviour.java:986`).
Blockstate JSON still uses `{"variants": {"": {"model": …}}}`.

---

## 10. Annotations / misc

* `javax.annotation.Nullable` (JSR305) is not on the Fabric classpath — Minecraft itself uses
  `org.jspecify.annotations.Nullable`; use that. `org.jetbrains.annotations` is present but there is
  no reason to depend on it.
* `SoundEvent.createVariableRangeEvent(Identifier)` / `createFixedRangeEvent(Identifier, float)`
  unchanged (`/opt/mc-src/net/minecraft/sounds/SoundEvent.java:38,45`).
* `TagKey.create(Registries.BLOCK, Identifier)` — `BlockTags.create(...)` is private in 26.2
  (`/opt/mc-src/net/minecraft/tags/BlockTags.java:260`).
* `AbstractContainerMenu.stillValid(ContainerLevelAccess, Player, Block)` is still `protected static`
  (`AbstractContainerMenu.java:93`); `DataSlot.standalone()` unchanged.
* Avoid touching `net.minecraft.client.*` from common classes (e.g. resolving an entity in a menu
  constructor): use `playerInventory.player.level().getEntity(id)` — it works on both sides.
