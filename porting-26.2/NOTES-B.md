# NOTES-B — NeoForge 1.21.1 → Fabric 26.2: entities, upgrades, networking

Verified against the decompiled tree at `/opt/mc-src` (Loom's Fabric-patched 26.2 sources) and the
Fabric API jars in `~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/`.
Every row below was compiled successfully with:

```sh
CP=$(find /root/.gradle/caches/modules-2/files-2.1 -name '*.jar' | grep -v sources | tr '\n' ':')\
/root/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar
/usr/lib/jvm/java-25-openjdk-amd64/bin/javac -proc:none --release 25 -cp "$CP" -d /tmp/out $(find src/main/java -name '*.java')
```

> That `javac` invocation is **not** gradle and does not touch the build dir — it is the cheapest way
> to check a pass before handing errors back to the orchestrator.

---

## 0. The rename that touches every file

| 1.21.1 | 26.2 | source |
|---|---|---|
| `net.minecraft.resources.ResourceLocation` | **`net.minecraft.resources.Identifier`** | `/opt/mc-src/net/minecraft/resources/Identifier.java` |
| `ResourceLocation.fromNamespaceAndPath/parse/tryParse` | same names on `Identifier` | ibid. l.40/44/52 |
| `ResourceLocation.STREAM_CODEC` | `Identifier.STREAM_CODEC` (`StreamCodec<ByteBuf, Identifier>`) | ibid. l.20 |
| `FriendlyByteBuf#writeResourceLocation/readResourceLocation` | **`writeIdentifier` / `readIdentifier`** | `/opt/mc-src/net/minecraft/network/FriendlyByteBuf.java:579,583` |
| `net.minecraft.Util` | **`net.minecraft.util.Util`** | `/opt/mc-src/net/minecraft/world/entity/animal/parrot/Parrot.java:31` |
| `net.minecraft.world.entity.npc.Villager` | **`net.minecraft.world.entity.npc.villager.Villager`** | `/opt/mc-src/net/minecraft/world/entity/npc/villager/` |
| `net.minecraft.world.level.GameRules` | **`net.minecraft.world.level.gamerules.GameRules`** | `/opt/mc-src/net/minecraft/world/level/gamerules/GameRules.java` |
| `javax.annotation.Nullable` | not on the classpath → **`org.jspecify.annotations.Nullable`** | jspecify-1.0.0 is a transitive dep |

Dead ends:
* `net.minecraft.client.renderer.MultiBufferSource`, `net.minecraft.client.gui.GuiGraphics`,
  `RenderType.armorCutoutNoCull`, `ItemRenderer.getArmorFoilBuffer` — **none exist in 26.2.**
  Anything in a non-client package that took them has to lose the method.
* `net.minecraft.world.entity.projectile.AbstractArrow` moved to `…projectile.arrow.AbstractArrow`;
  `SmallFireball`/`Fireball` moved to `…projectile.hurtingprojectile.*`.

---

## 1. `Level` / `Entity` member access

| before | after | source |
|---|---|---|
| `level.isClientSide` (field) | `level.isClientSide()` — **the field is private now** | compile error `isClientSide has private access in Level` |
| `level.random` (field) | `level.getRandom()` — the field is protected | idem |
| `entity.getLevel()` / `getWorld()` | `entity.level()` (unchanged from NeoForge) | `Entity.java` |
| `isControlledByLocalInstance()` | **`isLocalInstanceAuthoritative()`** (final) | `/opt/mc-src/.../Entity.java:3568` |
| `absMoveTo(x,y,z,yRot,xRot)` | **`absSnapTo(...)`**; `moveTo` → `snapTo` | `Entity.java:1763,1784` |
| `Entity#lerpTo(...)` | **gone.** Override `getInterpolation()` returning an `InterpolationHandler` | `Entity.java:2554`, pattern from `vehicle/boat/AbstractBoat.java:64,196,228` |
| `getPickedResult(HitResult)` | **`getPickResult()`** returning `@Nullable ItemStack` | `Entity.java:3852` |
| `interact(Player, InteractionHand)` | **`interact(Player, InteractionHand, Vec3 location)`** | `Entity.java:2257` |
| `canBeCollidedWith()` | **`canBeCollidedWith(@Nullable Entity other)`** | `Entity.java:2366` |
| `causeFallDamage(float, float, DamageSource)` | **`causeFallDamage(double fallDistance, float mult, DamageSource)`** | `Entity.java:1579` |
| `Block#fallOn(level, state, pos, entity, float)` | last arg is now **`double`** | `block/Block.java:478` |
| `kill()` | **`kill(ServerLevel)`** | `Entity.java:411` |
| `spawnAtLocation(ItemStack)` | **`spawnAtLocation(ServerLevel, ItemStack)`** (`@Nullable ItemEntity`) | `Entity.java:2212-2231` |
| `state.getFriction(level, pos, entity)` | **`state.getBlock().getFriction()`** (no args) | `block/Block.java:486`, used in `LivingEntity.java:2452` |
| `Vec3` horizontal helper | `getDeltaMovement().horizontalDistanceSqr()` | `world/phys/Vec3.java:192` |
| `level.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)` | **`level.getGameRules().get(GameRules.ENTITY_DROPS)`** | `gamerules/GameRules.java:34,120` |
| `Level#getTimeOfDay(float)` | **gone** (day time became the world-clock system). Use `level.environmentAttributes().getDimensionValue(EnvironmentAttributes.SUN_ANGLE)` → **degrees**, equals old `getTimeOfDay()*360` | `world/attribute/EnvironmentAttributes.java:55`, usage `block/DaylightDetectorBlock.java:56` |
| `player.connection.aboveGroundVehicleTickCount = 0` | field is private → **`player.connection.resetFlyingTicks()`** | `server/network/ServerGamePacketListenerImpl.java:370` |
| `Items.WHITE_BANNER` | **`Items.BANNER.pick(DyeColor.WHITE)`** (`ColorCollection<Item>`) | `world/item/Items.java:1569`, `world/level/block/ColorCollection.java:90` |
| `itemStack.getBurnTime(RecipeType)` (NeoForge) | **`level.fuelValues().burnDuration(stack)`** | `world/level/Level.java:1107`, `block/entity/FuelValues.java:34` |
| `itemStack.hasCraftingRemainingItem()/getCraftingRemainingItem()` | **`stack.getItem().getCraftingRemainder()`** → `@Nullable ItemStackTemplate`, then `.create()` | `world/item/Item.java:284`, `world/item/ItemStackTemplate.java:79` |
| `itemStack.getEnchantmentLevel(holder)` | **`EnchantmentHelper.getItemEnchantmentLevel(holder, stack)`** | `item/enchantment/EnchantmentHelper.java:53` |
| `registryAccess().registry(key)` | **`registryAccess().lookup(key)`** → `Optional<Registry<E>>` | `core/RegistryAccess.java:19` |
| `registry.getHolder(ResourceKey)` | **`registry.get(ResourceKey)`** → `Optional<Holder.Reference<T>>` (from `HolderGetter`) | `core/HolderGetter.java:9` |
| `registry.get(Identifier)` returning `T` | **`registry.getValue(Identifier)`** (`@Nullable T`); `get(Identifier)` now returns `Optional<Holder.Reference<T>>` | `core/Registry.java:67,133` |
| `BlockTags.create(id)` | `create` is **private**; use `TagKey.create(Registries.BLOCK, id)` | `tags/BlockTags.java:260` |
| `EyeOfEnder#signalTo(BlockPos)` | **`signalTo(Vec3)`** | `projectile/EyeOfEnder.java:74` |

### Damage

```java
// 1.21.1
@Override public boolean hurt(DamageSource source, float amount) { ... }
// 26.2 — Entity#hurt is FINAL and only dispatches:
@Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { ... }
//        public boolean hurtClient(DamageSource source)                  // optional
```
`Entity.java:1918-1931`. **`Entity#isInvulnerableTo(DamageSource)` no longer exists** — only
`protected final boolean isInvulnerableToBase(DamageSource)` (`Entity.java:3002`).
`isInvulnerableTo(ServerLevel, DamageSource)` exists **on `LivingEntity` only**
(`LivingEntity.java:3975`). For a non-living entity, write your own helper that ends in
`return isInvulnerableToBase(source);` — do not mark it `@Override`.

### Riding / passengers

| before | after |
|---|---|
| `canBeRiddenUnderFluidType(FluidType, Entity)` (NeoForge) | `boolean dismountsUnderwater()` — default `this.is(EntityTypeTags.DISMOUNTS_UNDERWATER)` (`Entity.java:2664`) |
| `positionRider(Entity, MoveFunction)` | unchanged, `Entity.MoveFunction` still at `Entity.java:4093` |
| `getDismountLocationForPassenger(LivingEntity)` | unchanged (`Entity.java:3598`) |
| `canAddPassenger` / `canRide` / `addPassenger` | unchanged, still `protected` |

---

## 2. Entity NBT: `CompoundTag` → `ValueInput` / `ValueOutput`

```java
// 1.21.1
public void readAdditionalSaveData(CompoundTag tag)
public void addAdditionalSaveData(CompoundTag tag)
// 26.2 — both are PROTECTED and ABSTRACT on Entity (Entity.java:2208/2210)
protected void readAdditionalSaveData(ValueInput input)
protected void addAdditionalSaveData(ValueOutput output)
```
Package: `net.minecraft.world.level.storage.{ValueInput,ValueOutput,TagValueInput,TagValueOutput}`.

**Exact `ValueInput` surface** (`/opt/mc-src/net/minecraft/world/level/storage/ValueInput.java`) — there is
nothing else:

```
<T> Optional<T> read(String, Codec<T>)          Optional<ValueInput> child(String)
ValueInput childOrEmpty(String)                 Optional<ValueInput.ValueInputList> childrenList(String)
ValueInput.ValueInputList childrenListOrEmpty(String)
<T> Optional<TypedInputList<T>> list(String, Codec<T>)   <T> TypedInputList<T> listOrEmpty(String, Codec<T>)
boolean getBooleanOr(String, boolean)   byte getByteOr(String, byte)   int getShortOr(String, short)
Optional<Integer> getInt(String)        int getIntOr(String, int)
long getLongOr(String, long)            Optional<Long> getLong(String)
float getFloatOr(String, float)         double getDoubleOr(String, double)
Optional<String> getString(String)      String getStringOr(String, String)
Optional<int[]> getIntArray(String)
```

**`ValueOutput`**: `store(String, Codec<T>, T)`, `storeNullable`, `putBoolean/Byte/Short/Int/Long/Float/Double/String/IntArray`,
`child(String)`, `childrenList(String)`, `list(String, Codec<T>)`, `discard`, `isEmpty`.

### Dead end that costs the most time
**`ValueInput` cannot enumerate keys.** There is no `getAllKeys()`/`keySet()`. If your old format was a
compound keyed by dynamic ids (e.g. `upgrades: { "mod:armor": {...}, "mod:seats": {...} }`) you have
two choices:

1. **Keep the format** — read the whole subtree back as a tag and enumerate it yourself:
   ```java
   CompoundTag t = input.read("upgrades", CompoundTag.CODEC).orElse(null);
   for (String key : t.keySet()) {
       ValueInput sub = TagValueInput.create(ProblemReporter.DISCARDING, registryAccess(), t.getCompoundOrEmpty(key));
   }
   // writing is fine with the normal API:
   ValueOutput o = output.child("upgrades");
   o.child(idString);   // one child per entry
   ```
   Do this when another file (item tooltips, recipes) still parses the raw `CompoundTag`.
2. Switch to a **list of children** with an explicit `id` field:
   `output.childrenList("x").addChild()` / `for (ValueInput c : input.childrenListOrEmpty("x"))`.
   `childrenList` + `putString("id",…)` + `child("nbt")` produces a `ListTag` of compounds — byte-identical
   to a hand-written `ListTag` of `{id:…, nbt:…}`.

### CompoundTag ↔ ValueInput/Output bridges
```java
TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registryAccess());
addAdditionalSaveData(out);
CompoundTag tag = out.buildResult();                                   // TagValueOutput.java:27,151

ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, registryAccess(), tag); // TagValueInput.java:40
entity.load(in);                                                       // Entity.java:2139 — takes ValueInput now
```
`ProblemReporter.DISCARDING` is at `/opt/mc-src/net/minecraft/util/ProblemReporter.java:18`.

Because `readAdditionalSaveData` is **protected** in 26.2 (it was public in 1.21.1), anything outside the
entity (e.g. an item that stores entity NBT in a data component) needs a public bridge method on the
entity — there is no other way in.

### ItemStack in NBT
`ItemStack.save(...)` / `ItemStack.parseOptional(...)` are gone. Use the codecs:
`ItemStack.CODEC`, `ItemStack.OPTIONAL_CODEC` (`world/item/ItemStack.java:122,123`) with
`output.store(name, ItemStack.CODEC, stack)` / `input.read(name, ItemStack.CODEC)`.
Stream side is unchanged: `ItemStack.OPTIONAL_STREAM_CODEC` (`ItemStack.java:125`).

### Container serialisation
`SimpleContainer` has ready-made helpers (`/opt/mc-src/net/minecraft/world/SimpleContainer.java:198,206`):
```java
container.storeAsItemList(output.list("Items", ItemStack.CODEC));
container.fromItemList(input.listOrEmpty("Items", ItemStack.CODEC));
```

---

## 3. Synched data & spawn

```java
@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
    builder.define(HEALTH, 10);
}
```
Unchanged from NeoForge 1.21.1. Gotcha found the hard way:

| accessor | 1.21.1 type | 26.2 type |
|---|---|---|
| `EntityDataSerializers.QUATERNION` | `EntityDataSerializer<Quaternionf>` | **`EntityDataSerializer<Quaternionfc>`** (`network/codec/ByteBufCodecs.java:191`) |

So the field must be `EntityDataAccessor<Quaternionfc>`; `entityData.get(Q)` returns `Quaternionfc`,
wrap it (`new Quaternionf(entityData.get(Q))`) where you need the mutable class.
Same for the stream codec: `ByteBufCodecs.QUATERNIONF` is `StreamCodec<ByteBuf, Quaternionfc>` —
adapt with `ByteBufCodecs.QUATERNIONF.map(Quaternionf::new, q -> q)`
(`StreamCodec#map` at `network/codec/StreamCodec.java:69`).

`EntityType#create(Level)` → **`create(Level, EntitySpawnReason)`**
(`/opt/mc-src/net/minecraft/world/entity/EntitySpawnReason.java`; values incl. `MOB_SUMMONED`,
`TRIGGERED`, `COMMAND`). It returns `@Nullable T`.

`EntityType#updateInterval()` still exists (`EntityType.java:422`).

---

## 4. Networking: NeoForge payloads → Fabric

Everything NeoForge-side is gone: `IPayloadContext`, `PayloadRegistrar`,
`RegisterPayloadHandlersEvent`, `PacketDistributor`, `ConnectionType`,
`registrar.playToServer/playToClient`.

The payload record itself is **unchanged** — `CustomPacketPayload` + `CustomPacketPayload.Type<T>` +
a `StreamCodec` are vanilla. Only registration, dispatch and the handler signature change.

### Registration (common entrypoint, runs on both sides)
```java
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

PayloadTypeRegistry.serverboundPlay().register(MyC2S.TYPE, MyC2S.STREAM_CODEC);
PayloadTypeRegistry.clientboundPlay().register(MyS2C.TYPE, MyS2C.STREAM_CODEC);
```
Note the names: **`serverboundPlay()` / `clientboundPlay()`**, *not* `playC2S()/playS2C()`.
Also available: `serverboundConfiguration()`, `clientboundConfiguration()`, and
`registerLarge(TYPE, CODEC, int|IntSupplier)` for oversized payloads.
`B` is `RegistryFriendlyByteBuf` for play, so a `StreamCodec<ByteBuf, T>` fits (`? super B`).
(verified with `javap` on `fabric-networking-api-v1-6.3.3+72073ef09e.jar`)

### Receivers
```java
// server (common entrypoint)
ServerPlayNetworking.registerGlobalReceiver(MyC2S.TYPE, (payload, context) -> {
    ServerPlayer player = context.player();      // Context: server(), player(), responseSender()
    ...                                          // already on the main thread — no enqueueWork()
});

// client (client entrypoint ONLY)
ClientPlayNetworking.registerGlobalReceiver(MyS2C.TYPE, (payload, context) -> {
    Minecraft mc = context.client();             // Context: client(), player(), responseSender()
});
```
`context.enqueueWork(...)` has no Fabric equivalent and is not needed — handlers already run on the
game thread.

### Sending
```java
ServerPlayNetworking.send(serverPlayer, payload);   // S2C
ClientPlayNetworking.send(payload);                 // C2S
```

### `PacketDistributor.sendToPlayersTrackingEntity(entity, payload)` replacement
```java
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
for (ServerPlayer p : PlayerLookup.tracking(entity)) ServerPlayNetworking.send(p, payload);
```
`PlayerLookup` also has `all(server)`, `level(serverLevel)`, `tracking(ServerLevel, ChunkPos|BlockPos)`,
`tracking(BlockEntity)`, `around(level, Vec3|Vec3i, radius)`. **`PlayerLookup.tracking` requires a
server-side entity** — guard every call with `!level().isClientSide()`.

### `IEntityWithComplexSpawn` (extra spawn data) — no Fabric equivalent
Replace with your own S2C payload fired from `EntityTrackingEvents.START_TRACKING`:
```java
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;

EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
    if (entity instanceof MyEntity e) ServerPlayNetworking.send(player, MySpawnPacket.create(e));
});
```
(`EntityTrackingEvents.StartTracking#onStartTracking(Entity, ServerPlayer)`; also `STOP_TRACKING`.)
It fires after the vanilla spawn packet, so the client entity already exists — still null-check it.

### Dead end: writing "the rest of the buffer" lazily
The NeoForge trick of `new RegistryFriendlyByteBuf(outgoingBuf, access, ConnectionType.NEOFORGE)` inside
`StreamCodec#encode` and then writing directly into the live outgoing buffer does not port
(`ConnectionType` is NeoForge-only and Fabric's encoder does not hand you the frame). Serialise
eagerly into a `byte[]` instead:
```java
RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), entity.registryAccess());
writeMyStuff(buf);
byte[] data = new byte[buf.readableBytes()];
buf.readBytes(data);
buf.release();
// record component: byte[] data, codec ByteBufCodecs.BYTE_ARRAY (ByteBufCodecs.java:150)
// on the client:
new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), mc.level.registryAccess());
```
`RegistryFriendlyByteBuf` is now a **2-arg** constructor `(ByteBuf, RegistryAccess)`
(`/opt/mc-src/net/minecraft/network/RegistryFriendlyByteBuf.java:10`).

`StreamCodec.composite` exists for 1–12 field pairs (`StreamCodec.java:118…543`).

### Dedicated-server safety (what `runServer` catches)
Put every client-touching receiver body in a **separate class** referenced only from the
client-registration method:
```java
public static void register()       { /* payload types + server receivers only */ }
public static void registerClient() { MyClientNetworking.register(); }   // lazily resolved
```
Same rule for any call into a client class from shared code: keep the reference inside a method that
only runs when `level().isClientSide()`, never in a field type or a method signature — JVM constant-pool
resolution is lazy per call site, but signatures are resolved at class verification.

---

## 5. Capabilities (contract C4) — what to write instead

| NeoForge | replacement | notes |
|---|---|---|
| `ItemStackHandler` / `IItemHandler` | `net.minecraft.world.SimpleContainer` | `getItem/setItem/removeItem/getContainerSize/addListener`; already has `storeAsItemList`/`fromItemList` |
| `SlotItemHandler` | plain `net.minecraft.world.inventory.Slot(Container, idx, x, y)` | `Slot` and `DataSlot` are unchanged |
| `IEnergyStorage` / `EnergyStorage` | plain field/class owned by the upgrade | do **not** pull in Team Reborn Energy |
| `FluidTank` / `FluidStack` | small local class holding `Fluid fluid; int amount;` | do **not** pull in the Transfer API |
| `stack.getCapability(Capabilities.FluidHandler.ITEM)` | no equivalent — vanilla-bucket-only fallback: `item instanceof BucketItem` + match `fluid.getBucket() == item` (`world/level/material/Fluid.java:55`); `BucketItem.content` is **protected**, so you cannot read it without an access widener |
| `entity.getCap(cap)` / `BaseCapability` | delete | nothing to expose to other mods |

## 6. Menus with extra open data

`Player#openMenu(MenuProvider, Consumer<FriendlyByteBuf>)` (NeoForge) does not exist; vanilla only has
`OptionalInt openMenu(@Nullable MenuProvider)` (`entity/player/Player.java:803`).
Fabric supplies the missing half in **`fabric-menu-api-v1`**:

```java
// registration (Agent A side)
new ExtendedMenuType<MyMenu, Integer>(MyMenu::new, ByteBufCodecs.VAR_INT)   // MyMenu(int id, Inventory inv, Integer data)

// opening (entity/upgrade side)
player.openMenu(new ExtendedMenuProvider<Integer>() {
    @Override public Integer getScreenOpeningData(ServerPlayer p) { return entity.getId(); }
    @Override public Component getDisplayName()                   { return entity.getName(); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player pl) { ... }
});
```
`net.minecraft.world.MenuProvider` already `extends FabricMenuProvider` in the patched sources, and
`ExtendedMenuProvider<D> extends MenuProvider`. `MenuType`'s `(MenuSupplier, FeatureFlagSet)`
constructor is **private** in 26.2 — non-extended menus need another route.

## 7. Misc dead ends burned

* `Entity#getWorld` / `getEntityWorld` — yarn advice; NeoForge sources already use `level()`, keep it.
* `state.getFriction(level, pos, entity)` — the 3-arg form was a NeoForge extension; vanilla only has
  `Block#getFriction()`.
* `Level#explode(Entity, double,double,double,float, Level.ExplosionInteraction)` still exists
  (`Level.java:581`) — no change needed.
* `ServerLevel#sendParticles(T, double x,y,z, int count, double dx,dy,dz, double speed)` unchanged
  (`ServerLevel.java:1304`).
* `Level#addAlwaysVisibleParticle(options, boolean overrideLimiter, x,y,z, dx,dy,dz)` unchanged
  (`Level.java:520`); the 7-arg no-boolean form also exists.
* `EntitySelector.pushableBy(entity)`, `Stats.PLAY_RECORD`, `SoundEvents.ENDER_EYE_LAUNCH`,
  `StructureTags.EYE_OF_ENDER_LOCATED`, `ServerLevel#findNearestMapStructure` — all unchanged.
* `ArrowItem#createArrow(Level, ItemStack, LivingEntity, @Nullable ItemStack firedFromWeapon)` and
  `AbstractArrow.pickup` (public field) — unchanged, only the package moved.
