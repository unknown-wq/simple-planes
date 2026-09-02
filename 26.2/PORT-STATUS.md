# PORT-STATUS — Simple Planes → Fabric / Minecraft 26.2

Live status + **the law** for every agent working in `26.2/`. Read this whole file before
your first edit. Background reference: `../porting-26.2/PORTING-GUIDE-26.2.md` (§3 breaking
changes) and `../porting-26.2/PORT-CHEATSHEET.md` (verified fixes for recurring errors).

## What this port is

`1.21.1/` is **NeoForge 21.1.61 / MC 1.21.1 / Java 21** — 151 java files, 12 609 lines.
`26.2/` is a copy of those sources being converted to **Fabric / MC 26.2 / Java 25**.

Two independent axes of change, both in one hop:

1. **Loader:** NeoForge → Fabric (entrypoints, registration, networking, capabilities, events).
2. **Vanilla API:** 1.21.1 → 26.2 (render-state renderers, `ValueInput`/`ValueOutput` NBT,
   `defineSynchedData(Builder)`, `EntitySpawnReason`, Blaze3D/Vulkan, item model definitions…).

Mappings are **already Mojang official** (NeoForge uses them) — there is no yarn→Mojang
migration in this port. Never write yarn names (`Identifier`, `MinecraftClient`, `World`,
`Item.Settings`, `class_1234`).

## Toolchain (ready — do not reinstall)

- Java 25: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`
- Gradle: `/opt/gradle-9.6.1/bin/gradle` (vendored in `../gradle/`). There is **no** `gradlew`.
- Decompiled MC 26.2 sources: **`/opt/mc-src/`** — ground truth, grep it.
- Reference Fabric 26.2 mods on disk: `/home/user/Fabric-LuckyTNTMod/` (tntmod + TntLib),
  `/home/user/desolation/`, `/home/user/LostCities/`.

## Rules for agents (non-negotiable)

1. **Do NOT run gradle.** The orchestrator compiles centrally and hands you your errors.
   Two gradle runs in this checkout at once will corrupt the build.
2. **Do NOT `git commit` / `git push`.** The orchestrator commits.
3. **Stay inside your file list** (§ Ownership). Need a change in someone else's file →
   report it in your final summary, do not edit it.
4. **Never invent a signature.** `grep -rn '<symbol>' /opt/mc-src/` or copy the pattern from a
   reference mod. If you cannot verify it, stub it under rule 6 and say so.
5. **No yarn names, no `net.neoforged.*` imports left anywhere.**
   **CORRECTION (verified 2026-07-28):** `Identifier` is NOT a yarn name in 26.2 —
   `ResourceLocation` no longer exists anywhere in the game (0 hits in `/opt/mc-src`,
   the class is `net.minecraft.resources.Identifier`, and all three reference Fabric
   26.2 mods import it). Mojang adopted the name post-unobfuscation. Ground truth in
   `/opt/mc-src` always wins over this document. The other yarn names in rule 5
   (`MinecraftClient`, `World`, `Item.Settings`, `class_1234`) remain forbidden.
6. **Rule §9 — "не выходит → забиваем".** If a piece resists ~2 honest attempts: keep the
   original body in a `/* ... */` block, replace with a compiling stub, mark
   `// TODO(port-26.2): DISABLED — <reason>`, and log it under **Disabled content** below.
   **Зелёная сборка важнее полноты фич.** Server-side gameplay > client visuals > compat.
7. Java 25, `release = 25`. Mixin `compatibilityLevel` is already `JAVA_25`.

## Cross-agent contracts (agreed up front — do not deviate)

**C1 — registry fields keep the `Supplier<T>` shape.** All `setup/*` fields are already
typed `Supplier<T>` and are dereferenced with `.get()` at 229 call sites across all three
agents' files. Agent A registers eagerly on Fabric and still exposes `Supplier<T>`:

```java
public static <T extends Item> Supplier<T> register(String name, Supplier<T> factory) {
    T value = Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(MODID, name), factory.get());
    return () -> value;
}
```

So **no other agent may change a `SimplePlanesXxx.FOO.get()` call site** because of
registration. If a vanilla API needs a `Holder<T>` instead, Agent A adds a separate accessor.

**C2 — entrypoints.** `SimplePlanesMod implements ModInitializer` (`onInitialize()`) — Agent A.
`xyz.przemyk.simpleplanes.client.SimplePlanesClient implements ClientModInitializer` — **Agent C
creates it**; it is already declared in `fabric.mod.json`. All client-only registration
(renderers, model layers, screens, key binds, HUD) moves there.

**C3 — networking.** Agent B owns `network/`. Payload registration lives in
`SimplePlanesNetworking` (`PayloadTypeRegistry.playC2S()/playS2C()` + `ServerPlayNetworking` /
`ClientPlayNetworking` receivers). Agent A calls `SimplePlanesNetworking.register()` from the
common entrypoint; Agent C calls `SimplePlanesNetworking.registerClient()` from the client
entrypoint. Keep exactly those two method names.

**C4 — capabilities are gone.** NeoForge `ItemStackHandler`/`IItemHandler` → vanilla
`SimpleContainer`; `IEnergyStorage`/`EnergyStorage` and `FluidStack`/`FluidTank` → plain
fields/small local classes owned by the upgrade that uses them (Agent B), not a capability
system. Do not pull in Team Reborn Energy or the Transfer API unless it is strictly cheaper.

**C5 — NeoForge events → Fabric callbacks.** `@EventBusSubscriber`/`@SubscribeEvent` classes
are deleted; the logic moves into Fabric API callbacks registered from the matching
entrypoint (`ServerTickEvents`, `UseEntityCallback`, `EntityTrackingEvents`, …).

## Ownership (никаких пересечений)

### Agent A — core, registration, data (43 java files + all of `src/main/resources`)
`SimplePlanesMod.java`, `setup/**` (11), `blocks/**`, `items/**`, `container/**` +
`container/slots/**`, `recipes/**`, `datapack/**`, `misc/**`, `compat/**` (**delete
entirely** — JEI / IronChests / Quark / MrCrayfishGun compat is out of scope), and
`src/main/resources/**` (recipes → 26.2 plain-string ingredients, item model definitions,
blockstates, loot tables, tags, lang).

### Agent B — entities, upgrades, networking (40 java files)
`entities/**` (incl. `PlaneEntity`, 1378 lines), `network/**` (15),
`upgrades/**` **except** `*Model.java` / `*Renderer.java` / `*Screen.java`.

### Agent C — client (68 java files)
`client/**`, `mixin/**`, and every `*Model.java` / `*Renderer.java` / `*Screen.java`
anywhere in the tree (including under `upgrades/**`). Creates `client/SimplePlanesClient.java`.

## Build / test commands (orchestrator only)

```sh
cd /home/user/simple-planes/26.2
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle compileJava --no-daemon
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle build --no-daemon
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle runServer --no-daemon
```

**Definition of done:** `build` green and `runServer` boots to `Done (…)!` with zero `/ERROR]`
lines. The client is **not** testable in this container (no display).

## Progress checklist

- [x] Toolchain: JDK 25, Gradle 9.6.1, module `26.2/` with Fabric Loom build files
- [x] `/opt/mc-src` decompiled sources (7055 files, unpacked from Loom's decompile cache)
- [x] Agent A — core/registration/data
- [x] Agent B — entities/upgrades/network
- [x] Agent C — client/renderers/mixins
- [x] `compileJava` green (56 errors in one round, all mechanical)
- [x] `build` green — `simpleplanes-26.2-5.3.7.jar`, access widener validated
- [x] **`runServer` green — boots to `Done (4.387s)!` with ZERO `/ERROR]` lines**

## Result

Dedicated server on Minecraft 26.2 boots clean with the mod loaded (`simpleplanes 5.3.7`),
1607 recipes (1603 vanilla + the 4 plane-workbench recipes), no errors and no mod-related
warnings. Mixins apply; MixinExtras and sponge-mixin come from the loader with no extra
dependency.

**The client is untested** — this container has no display, so nothing visual (renderers,
models, screens, HUD) has ever been executed. Compile-clean is all that is established there.

Three runtime faults were fixed after the first boot; they are documented with their ground
truth in `../porting-26.2/NEOFORGE-TO-FABRIC-26.2.md`:
1. recipe results must be `ItemStackTemplate` — `ItemStack.CODEC` demands bound components,
   which mod items do not have during datapack load (the 4 plane recipes silently vanished);
2. `#minecraft:non_flammable_wood` is an item tag, so a block tag referencing it fails wholesale;
3. two non-mod red herrings: a flat `level-type` without generator settings, and a leftover
   server holding port 25565.

## Disabled content (§9 log — append here, one line per cut)

- `compat/**` — JEI, IronChests, Quark and MrCrayfishGun integration deleted wholesale.
  Reason: out of scope for the port; none of these mods exist for 26.2 in this environment.
- `misc/ChestTypes.java` (Agent A) — replaces the deleted `IronChestsCompat` geometry helpers.
  Only the vanilla 27-slot chest layout survives; all the iron/gold/diamond/copper/silver/crystal/
  obsidian/dirt chest sizes, GUI textures and the `DirtChestSlot` are gone.
- `setup/SimplePlanesConfig` (Agent A) — NeoForge `ModConfigSpec` replaced by a class of static
  `Supplier<T>` constants holding the original defaults. Config is no longer editable; wiring a
  Fabric config library is a follow-up confined to that one file.
- `items/PlaneArmorItem` (Agent A) — `isEnchantable`/`getEnchantmentValue`/`supportsEnchantment`
  no longer exist on `Item` in 26.2. Enchantment value 9 moved to `Item.Properties#enchantable(9)`
  at registration; the "always allow Protection" special case is dropped (data-driven now).
- `client/render/PlaneItemColors` item tint (Agent A resources) — item colour providers are gone in
  26.2. `assets/simpleplanes/items/{plane,large_plane,cargo_plane,helicopter}.json` now use a
  constant tint (`0xB28F55`, the old `DEFAULT_COLOR`) instead of sampling the material block texture.
- `assets/simpleplanes/blockstates/cloud.json` (Agent A) — deleted; no `cloud` block is registered
  and no `block/cloud_*` models exist.
- `blocks/ChargingStationBlockEntity` (Agent A) — charged any `IEnergyStorage` capability holder
  standing on it; now looks up `ElectricEngineUpgrade` on a `PlaneEntity` directly (contract C4).
  **The block is inert as shipped.** Its own buffer was filled through the same capability, and
  nothing on Fabric fills it now, so it holds 0 for ever and charges nothing — while still being
  craftable and listed in the creative tab. Restoring it needs an energy input (a transfer API, or
  an in-world source); whether to keep shipping the block meanwhile is a content decision, not a
  port one. Until then the solar panel is the only way to charge an electric engine.

### Agent C (client) cuts

- `client/render/PlaneItemColors.java` — **file deleted.** Item colour providers
  (`RegisterColorHandlersEvent.Item`) were removed in 1.21.4/26.x; item tints are item-model-JSON
  driven now. The plane item no longer tints itself with its build material. Needs an
  `assets/simpleplanes/items/*.json` tint source if it is ever restored (Agent A).
- `client/ClientEventHandler` — `RenderLivingEvent.Pre/Post` (rotated riding players with the plane),
  `ViewportEvent.ComputeCameraAngles` (rolled the first-person camera) and
  `CalculateDetachedCameraDistanceEvent` (per-plane third-person camera distance) removed. All three
  are NeoForge-only events with **no Fabric equivalent**; re-implementing needs bespoke mixins into
  `LivingEntityRenderer` / `GameRenderer` / `Camera`.
- `client/ClientUtil` — `renderTiledTextureAtlas`, `renderLiquidEngineFluid`, `setColorRGBA` cut.
  They used immediate-mode `Tesselator`/`BufferUploader.drawWithShader` + `RenderSystem.setShaderTexture`
  (all gone: GUI drawing is `GuiGraphicsExtractor` + `RenderPipelines` now) and NeoForge
  `IClientFluidTypeExtensions`/`FluidStack` (removed by contract C4). The liquid engine's fuel gauge
  therefore has no fluid fill.
- `upgrades/banner/BannerModel` — banner-on-the-tail rendering cut. 26.2 renders banners as *block*
  models; `BannerRenderer` no longer exposes a `flag` `ModelPart` or a static `renderPatterns`, and
  `ModelBakery.BANNER_BASE` is gone.
- `client/render/PlaneRenderer` — cargo-plane `largeUpgrades` (chests / supply crates drawn inside
  the cargo bay as block models) not rendered. `BlockRenderDispatcher#renderSingleBlock(..., ModelData, ...)`
  is gone; block models are submitted via `BlockModelResolver`/`BlockStateModelPart`, which needs
  level context a render state does not carry.
- `client/render/ParachuteRenderer` — the barrel block under a supply-crate parachute is not
  rendered (same `renderSingleBlock` removal).
- `client/render/PlaneRenderer#getMaterialTexture` — **simplified.** 1.21.1 read the first quad's
  sprite out of the block's baked inventory model (`ModelResourceLocation` + `getQuads(..., ModelData.EMPTY, ...)`).
  Both APIs are gone; the texture is now derived from the block id
  (`<ns>:textures/block/<path>.png`, falling back to `oak_planks`). Correct for vanilla plank/wool
  style blocks, wrong for blocks whose texture name differs from their id.
- `client/ModBusClientEventHandler` (plane HUD) — `Gui.rightHeight` no longer exists in 26.2, so the
  health rows are drawn at a fixed offset above the hotbar instead of stacking on the vanilla mount
  bar. `EngineUpgrade#renderPowerHUD` (fuel / energy gauge) is not called any more — it took a
  `GuiGraphics`, which no longer exists.
- `client/gui/PlaneInventoryScreen` — `Upgrade#renderScreen` / `#renderScreenBg` overlays (furnace
  burn bar, energy bar, fluid tank) are not drawn: same `GuiGraphics` removal.
- `client/gui/StorageScreen` — Iron Chests layout support removed with `compat/**`; the screen is
  always the vanilla-chest layout sized from `StorageContainer.rowCount`.
- `client/render/UpgradesModels` — `SHULKER_FOLDING` dropped (the folding upgrade's shulker lid).
  `ShulkerModel` moved to `net.minecraft.client.model.monster.shulker` and is now
  `EntityModel<ShulkerRenderState>`, which does not fit the plane render state.
- `mixin/CameraMixin` — kept, but retargeted: `Camera.partialTickTime` no longer exists and the
  `setPosition(DDD)V` call moved from `Camera#setup` into the private `Camera#alignWithEntity(float)`.
  Rewritten as a plain `@Inject(shift = AFTER)` that re-applies the position (no MixinExtras needed).

### Agent B (entities / upgrades / networking) cuts

- `upgrades/Upgrade` — `render(PoseStack, MultiBufferSource, int, float)`, `renderScreen(GuiGraphics, …)`
  and `renderScreenBg(GuiGraphics, …)` **removed**, together with every override in
  `armor`, `banner`, `floating`, `folding`, `jukebox`, `payload`, `seats`, `solarpanel`, `shooter`,
  `storage`, `supplycrate` and the three engines. `MultiBufferSource`, `GuiGraphics`,
  `ItemRenderer.getArmorFoilBuffer` and `RenderType.armorCutoutNoCull` do not exist in 26.2.
  Upgrade world rendering now lives entirely in Agent C's `client/render/UpgradesModels`.
- `upgrades/engines/EngineUpgrade#renderPowerHUD` — **removed** (same `GuiGraphics` removal).
- `upgrades/engines/liquid/LiquidEngineUpgrade` — the NeoForge fluid-handler capability transfer
  (fill/empty **any** modded fluid container placed in the input slot) is replaced by
  **vanilla-bucket-only** transfer: a bucket of a fluid listed in `plane_liquid_fuels` fills the tank
  by 1000 mB, an empty bucket drains 1000 mB. `FluidTank`/`FluidStack` are replaced by the local
  `LiquidEngineUpgrade.PlaneFluidTank` (contract C4).
- `entities/PlaneEntity#getCap` and `upgrades/Upgrade#getCap` — **removed.** NeoForge capabilities
  are gone (C4); other mods can no longer pull items/energy/fluid out of a plane through a capability.
- `entities/PlaneEntity` position interpolation — the hand-rolled `lerpTo`/`lerpX/Y/Z`/`lerpSteps`
  are replaced by vanilla `InterpolationHandler` (10 steps) because `Entity#lerpTo` no longer exists
  in 26.2. The quaternion (`Q`) interpolation is still hand-rolled and unchanged.
- `entities/PlaneEntity#canBeRiddenUnderFluidType` → `Entity#dismountsUnderwater()`. NeoForge's
  per-`FluidType` control is gone; the plane now simply does not eject its rider under water when the
  floaty-bedding upgrade is installed, and does for any other fluid.
- `upgrades/storage/ChestUpgrade#onApply` — no longer resizes the inventory from the chest type
  (`IronChestsCompat.getSize`); fixed 27 slots via `ChestTypes.getSize` (compat was already cut).
