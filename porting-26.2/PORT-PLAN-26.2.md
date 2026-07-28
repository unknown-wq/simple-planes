# Port Plan — LuckyTNTLib + LuckyTNTMod → Minecraft 26.2 (Fabric)

> Companion to **`PORTING-GUIDE-26.2.md`** (the technical reference + web-recheck prompt for agents).
> This file is the **execution plan**: order of work, ownership, and done-criteria.

## Repository layout (this monorepo)

```
/
├── tntmod/                  # LuckyTNTMod — the mod (303 Java files). Depends on the lib.
├── TntLib/                  # LuckyTNTLib — the library, CC0-1.0, cloned from
│                            #   SlimingHD/Fabric-LuckyTNTLib @ branch 1.21 (== 1.21-0.100.6.1)
├── PORTING-GUIDE-26.2.md    # Agent instruction / technical reference (READ FIRST)
└── PORT-PLAN-26.2.md        # This file
```

`tntmod` currently pulls the lib from JitPack (`com.github.SlimingHD:Fabric-LuckyTNTLib:1.21-0.100.6.1`).
During the port, `TntLib/` is the source of truth for the library — build it locally / via `includeBuild` and point the mod at the local version instead of JitPack until each stage is published.

## Non-negotiable facts (full detail in the guide)

- Target is **26.2** (`year.drop` scheme; "1.26.2" == 26.2).
- **Yarn is dead after 1.21.11** → 26.1+ uses **Mojang official mappings**.
- **Java 25** from 26.1 (was 21). New Loom (1.17) + Gradle (9.5.1), build-script overhaul at 26.1.
- Rendering rewritten twice: **EntityRenderState** (1.21.2) and **RenderPipeline/Blaze3D + Vulkan** (1.21.6 / 26.2).

## Strategy — 4 staged hops, library before mod

Never jump 1.21 → 26.2 directly. Each stage ends with a **green `./gradlew build`** and in-game smoke test.

| Stage | Hop | Mappings | Java | Focus |
|---|---|---|---|---|
| **A** | 1.21 → **1.21.11** | Yarn | 21 | Absorb 1.21.x breaks: render-state, models/pick, NBT-Optional, HUD, RenderPipeline, ReadView/WriteView, `getEntityWorld`, networking |
| **B** | 1.21.11 Yarn → **Mojang mappings** | Yarn→Mojang | 21 | Run `migrateMappings`/Ravel; **fix Mixins by hand** |
| **C** | 1.21.11 → **26.1** | Mojang | **25** | Unobfuscated-Loom build overhaul; Fabric API renames |
| **D** | 26.1 → **26.2** | Mojang | 25 | Blaze3D/Vulkan-safe rendering; ID-holder split; GUI relocation |

For **each stage**: port `TntLib/` first → get it green → then port `tntmod/` against it.

## Work breakdown by risk (see guide §4 for file-level detail)

| Priority | Area | Files |
|---|---|---|
| 🔴 | Entity renderers → `EntityRenderState`, then Blaze3D | lib `LTNTRenderer`, `LDynamiteRenderer`, `LTNTMinecartRenderer`; mod `BombRenderer`, `AngryMinerRenderer`, `BouncingTNTRenderer` |
| 🔴 | Registration → `registryKey`, `EntityType.Builder`+key, `SpawnReason`, lookup renames | lib `RegistryHelper` (618 lines), `ItemRegistry`; mod's 17 `registry/*` |
| 🔴 | Mixins → re-verify every INVOKE target against 26.2 source | lib `EntityMixin`, `FireBlockMixin`; mod's 7 mixins |
| 🟠 | Entities → `initDataTracker(Builder)`, `getEntityWorld`, ReadView/WriteView | lib `PrimedLTNT`, `LExplosiveProjectile`, `LTNTMinecart`, … |
| 🟠 | Items → `ActionResult`, `appendTooltip`, `Item.Properties` | lib `LDynamiteItem`, `LTNTMinecartItem`, … |
| 🟠 | Explosion engine → vanilla `Explosion`/`Level` internals | lib `ImprovedExplosion` (607 lines), `ExplosionHelper` |
| 🟠 | Config GUI + HUD overlay → widget/`Screen` signatures, `HudElementRegistry` | lib config screens; mod `ConfigScreen*`, `OverlayTick`, `InGameHudMixin` |
| 🟢 | Networking (already modern `CustomPayload`+`PacketCodec`) | lib `network/*`; mod `NetworkRegistry` |
| 🟢 | `tnteffects/*` (~250 files, pure logic — recompile after lib is stable) | mod `tnteffects/**` |

## Execution options (pick one)

1. **Sequential, compiler-guided** — do stage A end-to-end (lib then mod) with a working Yarn compiler, then B/C/D. Safest.
2. **Parallel agents per area** — fan out agents on independent domains (rendering / registration / mixins) at each stage, following the guide.
3. **Lib-only first** — drive `TntLib/` all the way to 26.2, publish, then port `tntmod/`.

## Done criteria (per stage)

- **A**: `tntmod` + `TntLib` compile & run on **1.21.11** (Yarn); TNT/dynamite render, throw, explode in-game.
- **B**: compile on 1.21.11 with **Mojang mappings**; mixins hand-verified; parity with A.
- **C**: compile & run on **26.1**, Java 25, new Loom build; Fabric API renames resolved.
- **D**: compile & run on **26.2**; renderers correct under both Vulkan and OpenGL; ID-holder split applied; no raw-GL.

## Open questions (confirm before starting)

1. Branch name is `claude/luckytntlib-1.26.2-port-...` — keep as-is (renaming means a new PR)?
2. Sequential A→D, or aggressive straight-to-26.2 (fix by compiler errors — faster, riskier)?
3. Mappings source of truth from 26.1 = **Mojang official** (confirm — there is no viable alternative).
