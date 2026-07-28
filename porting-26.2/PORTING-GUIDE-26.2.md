# Porting Guide — LuckyTNTLib + LuckyTNTMod → Minecraft 26.2 (Fabric)

> **Audience:** coding agents doing the 1.21 → 26.2 port.
> **Read section 0 before touching a single file.** Then always run the web-recheck prompt in §8 before implementing anything version-specific.
> Last verified against live Fabric docs & Minecraft Wiki: **July 2026**.

---

## 0. STOP — read this first (the facts that break naive porting)

1. **The target is `26.2`, NOT "1.26.2".** Minecraft Java changed its version scheme in 2026 to `year.drop.hotfix`. The real sequence is:
   `1.21 → 1.21.1 → 1.21.2/1.21.3 → 1.21.4 → 1.21.5 → 1.21.6/1.21.7/1.21.8 → 1.21.9/1.21.10 → 1.21.11` (last "1.x")
   `→ 26.1 (24 Mar 2026) → 26.1.1 → 26.1.2 → 26.2 (16 Jun 2026)`.
   Whenever a task says "1.26.2", it means **26.2**.

2. **Yarn and Intermediary mappings are DISCONTINUED after 1.21.11.**
   `26.1` is the **first unobfuscated** Minecraft release — the game ships with Mojang's own names at runtime. From 26.1 onward you **must** use **Mojang official mappings**.
   - ❌ DO NOT write yarn-mapped code for 26.x (`Identifier`, `MinecraftClient`, `World`, `Item.Settings`, `class_1234`, `EntityRendererFactory`, …).
   - ❌ DO NOT follow pre-2026 yarn tutorials, old MCP/Yarn wikis, or cached blog posts as if they were current.
   - ✅ Use Mojang names (`ResourceLocation`, `Minecraft`, `Level`, `Item.Properties`, `Creeper`, …).
   Yarn stays available only for **historical** (≤1.21.11) versions.

3. **Java 21 → Java 25.** Java 21 holds through 1.21.11; **Java 25 is required from 26.1**. Bump `sourceCompatibility`/`targetCompatibility` and Gradle JVM to 25, and set mixin `compatibilityLevel` accordingly.

4. **Do NOT jump straight from 1.21 to 26.2.** The correct path is staged (see §1). Each 1.21.x step carries its own hard breaks (render-state, NBT, HUD, networking). Skipping steps means debugging ~8 versions of breakage at once with no compiler to guide you.

5. **Always re-verify against the live web.** These APIs moved fast in 2025–2026 and your training data is stale. Before implementing any version-specific change, run the recheck prompt in §8. Trust official Fabric blog posts (`fabricmc.net`), Fabric docs (`docs.fabricmc.net`), and the Minecraft Wiki over anything else.

---

## 1. The mandatory staged porting path

Do these **in order**. Get a green build at each stage before advancing.

| Stage | From → To | Mappings | Java | Headline work |
|---|---|---|---|---|
| A | 1.21 → **1.21.11** | Yarn (still alive) | 21 | Absorb all 1.21.x API breaks step-by-step (render-state, model/pick, NBT-Optional, HUD/RenderPipeline/ReadView, `getEntityWorld`, networking). |
| B | 1.21.11 (Yarn) → 1.21.11 (**Mojang mappings**) | **Yarn → Mojang** | 21 | Run the mappings migration. Mixins must be reviewed by hand. |
| C | 1.21.11 → **26.1** | Mojang | **25** | Build-script overhaul (unobfuscated Loom), Fabric API renames. |
| D | 26.1 → **26.2** | Mojang | 25 | Blaze3D/OpenGL→Vulkan-safe rendering, ID-holder split, GUI relocation. |

> **Why not skip A and migrate mappings on 1.21?** Because the mappings migration tool maps *names*, not *API shapes*. All the semantic breaks (method signatures, removed methods, render-state) must be fixed while you still have Yarn's parameter names and Javadocs to read. Migrate mappings only once the code already compiles on 1.21.11.

**Library first, mod second.** `LuckyTNTMod` depends on `LuckyTNTLib`. Port and publish `LuckyTNTLib` for each target before porting the mod against it. The mod's `build.gradle` pulls the lib from JitPack (`com.github.SlimingHD:Fabric-LuckyTNTLib`).

---

## 2. Toolchain matrix (verified)

| MC | Java | Fabric Loom | Fabric Loader | Mappings |
|---|---|---|---|---|
| 1.21 / 1.21.1 | 21 | 1.7 | ~0.15.x | Yarn 1.21+build.x |
| 1.21.2 / 1.21.3 | 21 | 1.8 | 0.16.x | Yarn |
| 1.21.4 | 21 | 1.9 | 0.16.9 | Yarn |
| 1.21.5 | 21 | 1.10 | 0.16.10 | Yarn |
| 1.21.6–1.21.8 | 21 | 1.10 | 0.16.14 | Yarn |
| 1.21.9 / 1.21.10 | 21 | 1.11+ | 0.17.2+ | Yarn |
| 1.21.11 | 21 | 1.14+ | 0.18.1 | **Yarn (last)** |
| **26.1** | **25** | **1.15**, Gradle 9.4.0 | 0.18.4 | **Mojang** |
| **26.2** | **25** | **1.17**, Gradle 9.5.1 | 0.19.3 | **Mojang** |

IntelliJ **2025.3+** is required for mixins on 26.1+. Enum Extensions on 26.2 needs Loader ≥ 0.19.0.

### The 26.1 build-script overhaul (unobfuscated Loom)
When you reach stage C, `build.gradle` / `gradle.properties` change structurally:
1. `./gradlew wrapper --gradle-version latest`
2. Bump `minecraft_version`, `loader_version`, Loom, `fabric_version` in `gradle.properties`.
3. Loom plugin id: `id "fabric-loom"` → `id "net.fabricmc.fabric-loom"`.
4. **Delete the `mappings "net.fabricmc:yarn:…"` line entirely.**
5. Drop the `mod` prefix on configs: `modImplementation`→`implementation`, `modCompileOnly`→`compileOnly`, `modApi`→`api`.
6. `remapJar` → `jar` in build tasks (there is no remap step anymore).
7. Access wideners / class tweakers: change the header namespace from `named` to `official`.
8. Java compatibility → 25.
9. Nothing built for ≤1.21.11 works on 26.1, **even as compile-only** — every dependency must be 26.1+ (including LuckyTNTLib itself).

Tool: **mcsrc.dev** — Fabric's online decompiled-source viewer with mixin/AccessWidener generators. Use it to confirm exact 26.x method signatures.

---

## 3. Per-version breaking-change hit-list (grep targets)

### 1.21.2 / 1.21.3 — registry, entity render-state, results
- **`Item.Settings` / `Block.Settings` now need `.registryKey(RegistryKey<…>)`** or you get `NullPointerException: Item id not set` / `Block id not set`. Highest-frequency break in this codebase.
- Registry lookup renames: `getEntry`→`getOptional`, `entryOf`→`getOrThrow`, `getOrThrow`→`getValueOrThrow`, `getOrEmpty`→`getOptionalValue`.
- **`EntityType.Builder#build()` now requires a `RegistryKey<EntityType<?>>`.**
- **`EntityType#create(...)` now requires a `SpawnReason`** — e.g. `create(world, SpawnReason.SPAWN_ITEM_USE)`.
- Attributes lose their prefixes: `GENERIC_ATTACK_KNOCKBACK` → `ATTACK_KNOCKBACK`, etc.
- Action results unified into a single `ActionResult` (no more `TypedActionResult`; use `ActionResult.SUCCESS`, `withNewHandStack()`).
- **Entity render-state refactor (the flagship break):** `EntityRenderer<S extends EntityRenderState>`. Rewrite every renderer to:
  - `S createRenderState()`
  - `void updateRenderState(Entity, S, float tickDelta)` — copy entity fields into the state
  - `void render(S, MatrixStack, VertexConsumerProvider, int light)` — render **only** from the state (no entity access, no `getTexture()`).

### 1.21.4 — models, pick, colors
- Block entities render their block model automatically; `getRenderType()==ENTITYBLOCK_ANIMATED` override is gone.
- `fabric-rendering-v0` module removed.
- `BlockPickInteractionAware` → `PlayerPickItemEvents#BLOCK`/`#ENTITY`.
- `ItemColors` removed → item model definition JSON in `assets/<ns>/items/`.
- `FabricModelPredicateProviderRegistry`, `BuiltinItemRenderer(Registry)` removed.

### 1.21.5 — NBT Optionals, blocks, spawns
- `NbtCompound` getters return `Optional`; switch to `get*(key, default)` / `*OrEmpty`.
- `AbstractBlock#onStateReplaced` signature changed (now receives the *old* state); `DataPool`→`Pool`.
- `BiomeModificationContext#addSpawn` gained a `weight` parameter.
- Dynamic-registry datapack files need namespaced dirs: `data/<ns>/<registry>/…`.
- `SpecialBlockRendererRegistry` added.

### 1.21.6 / 1.21.7 / 1.21.8 — RenderPipeline, ReadView/WriteView
- **RenderSystem/RenderPipeline migration:** rendering split into extract + render phases; many `RenderSystem` methods removed → combine `RenderPipeline` + `RenderLayer`.
- Fabric Rendering: Material API removed; `BlockRenderLayerMap` moved to `net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap` (`fabric-blockrenderlayer-v1` merged into `fabric-rendering-v1`).
- **BlockEntity/world serialization → codec-based `ReadView`/`WriteView`** instead of raw `NbtCompound`. Rewrites every `readNbt`/`writeNbt`.
- `FabricTrackedDataRegistry` for conflict-free tracked-data handlers.

### 1.21.9 / 1.21.10 — the "touches everything" renames
- **`Entity#getWorld` → `Entity#getEntityWorld`.** Grep the whole codebase.
- `OrderedRenderCommandQueue`: world rendering reworked to a submit-to-queue model.
- `MinecraftClient.IS_SYSTEM_MAC` → `SystemKeycodes.IS_MAC_OS`.
- `KeyBinding.Category.create(Identifier)` replaces string categories.
- `ResourceManagerHelper` → `ResourceLoader.get()`.

### 1.21.11 — networking, last Yarn build
- Large-packet splitter: `PayloadTypeRegistry.playS2C().registerLarge(ID, CODEC, DATA_SIZE)`.
- Recipe Synchronization API for server→client recipe sync.
- World Render Events reintroduced (extraction separated from rendering).
- **This is the last version with Yarn.** Freeze here, get green, then migrate mappings.

### HUD API evolution (spans versions — the mod uses a HUD overlay)
- `HudRenderCallback` → `HudLayerRegistrationCallback` (1.21.5, deprecated) → **`HudElementRegistry`** (1.21.6). On **26.1 `HudRenderCallback` is removed** — use `HudElementRegistry`.

### 26.1 — Fabric API renames & removals
- Removed modules: `fabric-convention-tags-v1`, `fabric-loot-api-v2`.
- `ItemGroupEvents` → `CreativeModeTabEvents` (Fabric ships an IntelliJ migration map).
- `ColorProviderRegistry` → `BlockColorRegistry`; `FluidRenderHandler` → `FluidModel`.
- Render layers auto-assigned from sprite properties — **manual render-layer registration no longer needed**.
- Recipe serializers use `MapCodec` + `StreamCodec` (no inner serializer classes).
- New `ItemStackTemplate` immutable class; new precise interaction events (`BlockEvents#USE_ITEM_ON`, `ItemEvents#USE_ON`, …).
- Villager `TradeOfferHelper` replaced by a data-driven system.
- `DimensionEvents.MODIFY_ATTRIBUTES`.

### 26.2 — graphics backend + IDs + GUI
- **Vulkan backend added; raw OpenGL calls must move to the Blaze3D API** or they break (OpenGL slated for removal).
- Reversed depth buffer; Order-Independent Transparency (new shader uniforms/defines).
- ID storage split: `BlockIds` / `BlockItemIds` / `ItemIds`; `valueLookupBuilder` removed.
- GUI/HUD relocation, e.g. `Minecraft.getInstance().setScreen(...)` → `Minecraft.getInstance().gui.setScreen(...)`.
- New entity attributes (`air_drag_modifier`, `bounciness`, `friction_modifier`, `below_name_distance`, `name_tag_distance`); entity predicates restructured.
- Beds/signs/hanging-signs use block models not entity models.
- Protocol 776, data version 4903, datapack format 107.1, resource pack format 88.0.

---

## 4. THIS codebase's specific danger zones

Layout: **LuckyTNTLib** (51 Java files) is the dependency; **LuckyTNTMod** (303 Java files, ~250 of them `tnteffects/*` = mostly pure explosion logic that rarely breaks). Sort effort by the table below.

| Area | Files | Break risk | What changes |
|---|---|---|---|
| **Entity renderers** | lib `LTNTRenderer`, `LDynamiteRenderer`, `LTNTMinecartRenderer`; mod `BombRenderer`, `AngryMinerRenderer`, `BouncingTNTRenderer` | 🔴🔴 | Full rewrite to `EntityRenderer<S extends EntityRenderState>` (1.21.2). Remove `getTexture()`. Then RenderPipeline (1.21.6) and Blaze3D/Vulkan-safety (26.2). These renderers currently use `render(entity, yaw, delta, MatrixStack, VertexConsumerProvider, light)` + `getTexture()` — both gone. |
| **Registration** | lib `RegistryHelper` (618 lines), `ItemRegistry`, `NetworkRegistry`; mod's 17 `registry/*` classes | 🔴 | `new Identifier()`→`Identifier.of()` (already needed at 1.21), then Mojang `ResourceLocation.fromNamespaceAndPath()`. Add `.registryKey(...)` to every `Item.Settings`/`Block.Settings`. `EntityType.Builder#build()` + `RegistryKey`. `EntityType#create` + `SpawnReason`. `Registries`/`Registry` lookup renames. |
| **Mixins** | lib `EntityMixin`, `FireBlockMixin`; mod `AbstractMinecartEntityMixin`, `CameraMixin`, `GameRendererMixin`, `InGameHudMixin`, `LivingEntityMixin`, `HungerManagerMixin`, `FireBlockMixin` | 🔴 | INVOKE `target=` descriptors reference exact method signatures that change and are **not** auto-migrated. Re-verify every `@Inject`/`@Redirect` target against 26.2 source (mcsrc.dev). Known fragile targets: `moveOnRail`, `renderWorld`/`loadProjectionMatrix`, `getShapeProperty`. `initDataTracker()` → `initDataTracker(DataTracker.Builder)` (1.20.5); `dataTracker.startTracking`→`builder.add`. `readNbt`/`writeNbt` → ReadView/WriteView. |
| **Entities** | lib `PrimedLTNT`, `LExplosiveProjectile`, `LTNTMinecart`, `LivingPrimedLTNT`, `LuckyTNTMinecart` | 🟠 | `initDataTracker(DataTracker.Builder)`; `getWorld`→`getEntityWorld`; NBT read/write → ReadView/WriteView; `EntityType.create` + SpawnReason. |
| **Items** | lib `LDynamiteItem`, `LTNTMinecartItem`, `LuckyDynamiteItem`, `TNTConfigItem` | 🟠 | `use()` returns `ActionResult` not `TypedActionResult` (1.21.2); `appendTooltip(stack, TooltipContext, TooltipType, list, …)` signature changed and `TooltipContext` moved package; `Item.Settings`→`Item.Properties` (26.1). |
| **Explosion engine** | lib `ImprovedExplosion` (607 lines), `ExplosionHelper` | 🟠 | Reaches into vanilla `Explosion` internals and world block/entity access; audit against 26.2 `Explosion`/`Level` APIs and `getEntityWorld`. |
| **Config GUI** | lib `ConfigScreen`, `ConfigScreenListScreen`, widgets; mod `ConfigScreen`, `ConfigScreen2` | 🟠 | Screen/widget constructor and `render()` signatures shift with the rendering rework; `setScreen` relocation (26.2). |
| **HUD overlay** | mod `client/overlay/OverlayTick`, `InGameHudMixin` | 🟠 | `HudRenderCallback` removed (26.1) → `HudElementRegistry`. |
| **Worldgen data** | `src/generated/data/luckytntmod/worldgen/*` | 🟢 | Datapack dir-layout tweak (1.21.5 namespaced dirs); regenerate via datagen. |
| **Networking** | lib `network/*`, `ClientNetworkRegistry`; mod `NetworkRegistry` | 🟢 | Already on modern `CustomPayload` + `PacketCodec` in the 1.21 build. Low risk; just re-namespace to Mojang and re-verify `CustomPayload.Id`. |
| **`tnteffects/*`** | ~250 mod files | 🟢 | Pure explosion logic on top of lib abstractions. Recompiles once the lib API is stable; touch only where they hit `getWorld`, block/entity APIs, or `DamageSource`. |

**License note:** LuckyTNTLib is **CC0-1.0** (public domain) — free to copy/fork/modify with no attribution constraints. LuckyTNTMod's own `fabric.mod.json` says "All Rights Reserved", so keep mod changes within this repo/branch.

---

## 5. Mapping name cheat-sheet (Yarn → Mojang, for stage B onward)

| Yarn | Mojang |
|---|---|
| `Identifier` | `ResourceLocation` |
| `MinecraftClient` | `Minecraft` |
| `World` / `ServerWorld` | `Level` / `ServerLevel` |
| `WorldAccess` / `BlockView` | `LevelAccessor` / `BlockGetter` |
| `Item.Settings` | `Item.Properties` |
| `Block.Settings` / `AbstractBlock.Settings` | `BlockBehaviour.Properties` |
| `CreeperEntity`, `TntEntity`, `LivingEntity` | `Creeper`, `PrimedTnt`, `LivingEntity` |
| `Text` | `Component` |
| `NbtCompound` | `CompoundTag` |
| `PlayerEntity` / `ServerPlayerEntity` | `Player` / `ServerPlayer` |
| `Hand` / `ItemStack` | `InteractionHand` / `ItemStack` |
| `Vec3d` / `BlockPos` | `Vec3` / `BlockPos` |
| `EntityRendererFactory.Context` | `EntityRendererProvider.Context` |
| `MatrixStack` | `PoseStack` |
| `VertexConsumerProvider` | `MultiBufferSource` |

Mojang mappings **lack parameter names and Javadocs**. Read the Yarn source for intent *before* stage B, keep notes, then migrate. Migration tools: Loom `migrateMappings` task (no Kotlin support) or the **Ravel** IntelliJ plugin (Kotlin + Mixin friendly — what Fabric API itself used). **Neither handles Mixins reliably — review those by hand.**

---

## 6. Rules for agents working on this port

- **DO** verify every version-specific API against the live web (§8) before writing it. Prefer `fabricmc.net`, `docs.fabricmc.net`, `minecraft.wiki`, `mcsrc.dev`.
- **DO** work one stage at a time (§1) and get a green build (`./gradlew build`) before advancing.
- **DO** port `LuckyTNTLib` before `LuckyTNTMod`.
- **DON'T** use Yarn names or Intermediary (`class_XXXX`) for any 26.x work.
- **DON'T** trust pre-2026 tutorials, StackOverflow, or your own training memory for signatures — they predate the 26.x rewrites.
- **DON'T** skip intermediate 1.21.x versions to "save time" — the breaks compound.
- **DON'T** hand-migrate mappings before the code compiles on 1.21.11.
- **DON'T** invent method names. If unsure of a 26.2 signature, look it up on mcsrc.dev or grep the decompiled source; state the source in your change.
- **DON'T** make raw OpenGL/`GL11` calls for 26.2 — use Blaze3D.
- When a change is ambiguous or architectural (rendering rewrite, mapping strategy), **stop and ask** rather than guess.

---

## 7. Definition of done (per stage)

- Stage A: compiles & runs on 1.21.11 with Yarn; all TNT/dynamite render, throw, and explode in-game.
- Stage B: compiles on 1.21.11 with Mojang mappings; mixins verified by hand; parity with A.
- Stage C: compiles & runs on 26.1 with Java 25 and the new Loom build; Fabric API renames resolved.
- Stage D: compiles & runs on 26.2; no raw-GL warnings; renderers correct under Vulkan and OpenGL; ID-holder split applied.

---

## 8. Web-recheck prompt (paste into any agent before it implements a version-specific change)

```
You are porting a Fabric mod across Minecraft versions in the 1.21 → 26.2 range.
Your training data is STALE for this range — do not rely on memory. Before you
write or change any version-specific code you MUST verify it against LIVE 2025–2026
sources.

Context you must hold:
- Minecraft uses year.drop versioning now: after 1.21.11 comes 26.1 then 26.2.
  "1.26.2" is not real — it means 26.2.
- Yarn/Intermediary mappings are DEAD after 1.21.11. 26.1+ is unobfuscated and uses
  Mojang official mappings. Never emit yarn names (Identifier, MinecraftClient,
  World, Item.Settings, class_XXXX) for 26.x code — use Mojang names
  (ResourceLocation, Minecraft, Level, Item.Properties, ...).
- Java 25 is required from 26.1 (was 21).

For the specific API you are about to touch, do this:
1. WebSearch for the exact Fabric blog post / Fabric doc for the target version
   (site:fabricmc.net or site:docs.fabricmc.net) and read the relevant section.
2. Cross-check the exact class/method SIGNATURE on mcsrc.dev (decompiled 26.x source)
   or the Minecraft Wiki version page (minecraft.wiki/w/Java_Edition_26.2).
3. Confirm whether the symbol was renamed, moved package, changed signature, or
   removed between your source version and the target version.
4. Only then write the code, and cite the source URL you verified against in your
   summary. If you cannot find a live source, say so and STOP — do not guess a
   signature.

Authoritative sources, in priority order:
- https://fabricmc.net/  (per-version "Fabric for Minecraft X" blog posts)
- https://docs.fabricmc.net/develop/porting/  and  /develop/porting/mappings/
- https://mcsrc.dev/  (decompiled source + mixin/AccessWidener generator)
- https://minecraft.wiki/w/Java_Edition_26.2  (and 26.1)
```

---

## 9. Sources (verified July 2026)

- Version numbering: https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system
- Fabric per-version blogs: https://fabricmc.net/2024/05/31/121.html · /2024/10/14/1212.html · /2024/12/02/1214.html · /2025/03/24/1215.html · /2025/06/15/1216.html · /2025/09/23/1219.html · /2025/12/05/12111.html · /2026/03/14/261.html · /2026/06/15/262.html
- Unobfuscation announcement: https://fabricmc.net/2025/10/31/obfuscation.html
- Porting to 26.1: https://docs.fabricmc.net/develop/porting/
- Yarn → Mojang mappings migration: https://docs.fabricmc.net/develop/porting/mappings/
- Block Entity Renderers: https://docs.fabricmc.net/develop/blocks/block-entity-renderer
- Damage Types: https://docs.fabricmc.net/develop/entities/damage-types
- MC Wiki 26.1 / 26.2: https://minecraft.wiki/w/Java_Edition_26.1 · https://minecraft.wiki/w/Java_Edition_26.2
- Java version table: https://modready.gg/guides/minecraft-java-version-requirements

### Known gaps to re-verify during the port
- Exact 26.1.1 / 26.1.2 hotfix dev-diffs (assume they inherit 26.1's toolchain).
- Damage-source changes 1.21.2→26.2 (data-driven model is stable since 1.19.4; no 26.x note found — verify if explosion damage code misbehaves).
- Low-level `BufferBuilder`/`VertexConsumer`/`RenderType` signature diffs for Blaze3D — pull from mcsrc.dev if doing custom immediate-mode rendering.
- The full Fabric 26.1 rename catalog / IntelliJ migration map — consult before any mass find/replace.
