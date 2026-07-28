# NeoForge 1.21.1 → Fabric 26.2 — index and build/runtime findings

Entry point for the Simple Planes port. The detailed, per-area recipe sheets are:

| File | Area |
|---|---|
| `NOTES-A.md` | Entrypoint, registration, containers/menus, recipes, reload listeners, data JSON |
| `NOTES-B.md` | Entities, synched data, `ValueInput`/`ValueOutput`, upgrades, networking |
| `NOTES-C.md` | Client init, renderers + render states, entity models, screens, sounds, mixins |

Everything in those three files was verified against the decompiled 26.2 sources or a working
Fabric 26.2 mod. This file adds what only shows up once you actually build and boot — the
compiler and the dedicated server catch things no amount of source reading does.

## The four facts that shape this kind of port

1. **NeoForge already uses Mojang mappings**, so a NeoForge→Fabric port needs **no mappings
   migration** — unlike a Yarn-based Fabric port, where 26.1 forces one. The staged
   1.21.x → 26.1 → 26.2 path in `PORTING-GUIDE-26.2.md` exists to give a Yarn codebase a
   compiler at each hop; here a single hop straight to 26.2 works, because the compiler is
   usable from the first minute.
2. **`ResourceLocation` does not exist in 26.2 — the class is `net.minecraft.resources.Identifier`.**
   Mojang adopted the name post-unobfuscation. Do not "fix" `Identifier` back to `ResourceLocation`
   because a guide (including our own) calls it a Yarn name. Details in `NOTES-A.md` §0.
3. **The decompiled game is the only ground truth.** `genSources` + grep answered every
   signature question in this port. Training-data memory of 1.21.x APIs is wrong often enough
   to be worthless here.
4. **Compile-green is cheap; boot-green is where the real bugs are.** The port reached a green
   `compileJava` after one round of trivial fixes, then still had five distinct runtime faults
   that no compiler could see (below).

## Build-time findings (the compiler's list, after three agents each self-checked with javac)

56 errors survived the agents' own `javac` checks, all in one agent's files, all mechanical:

| Error | Fix | Ground truth |
|---|---|---|
| `isClientSide has private access in Level` | `level.isClientSide` → `level.isClientSide()` | `Level.java:129` field is private, `:165` accessor |
| `cannot find symbol: Items.WHITE_BANNER` (×16) | `Items.BANNER.pick(DyeColor.WHITE)` — per-colour banner constants are gone, `Items.BANNER` is a `ColorCollection<Item>` | `Items.java:1569` |
| `cannot find symbol: ClickType` | `ClickType` → `ContainerInput` (same constants: PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL) | `ContainerInput.java`, `AbstractContainerMenu.java:318` |
| `no suitable method found for startRiding(X, boolean)` | `startRiding(entity, force, sendEventAndTriggers)` | `Entity.java:2418` |

## Runtime findings (the dedicated server's list — nothing here is visible at compile time)

**1. Recipe results must be `ItemStackTemplate`, not `ItemStack`.**
`ItemStack.CODEC` validates through `Item.CODEC_WITH_BOUND_COMPONENTS`, and during datapack load a
mod item's components are not bound yet, so every recipe whose result is your own item fails with:

```
Couldn't parse data file 'simpleplanes:plane' from 'simpleplanes:recipe/plane.json':
DataResult.Error['Item simpleplanes:plane does not have components yet']
```

Vanilla's own recipes stopped using `ItemStack` for results in 26.x — `ShapedRecipe` holds an
`ItemStackTemplate` (`ShapedRecipe.java:24,41`). Custom recipe serializers must do the same:
`ItemStackTemplate.CODEC` / `ItemStackTemplate.STREAM_CODEC`, and `template.create()` where an
`ItemStack` is needed. Note the failure is **silent at compile time and non-fatal at runtime** —
the recipes just quietly do not exist.

**2. `#minecraft:non_flammable_wood` is an item tag only.** A *block* tag referencing it fails the
whole tag with `missing following references`. `ItemTags.NON_FLAMMABLE_WOOD` exists
(`ItemTags.java:127`); `BlockTags` has no counterpart. Inline the block ids instead.

**3. Mixin tooling resolves without extra dependencies.** `sponge-mixin 0.17.3+mixin.0.8.7` comes
with the loader and initialises MixinExtras 0.5.4 automatically — no `include`/`implementation`
line needed, and `compatibilityLevel: JAVA_25` is accepted.

**4. Loom applies fabric-api's transitive access wideners.** Client code calling package-private
`MenuScreens.register(...)` compiled and validated with no entry of our own.

**5. Two failures that look like mod bugs but are not.** `No key layers in MapLike[{}]` is
`level-type=minecraft:flat` in `server.properties` without generator settings — a vanilla parse of
the flat-world config, nothing to do with the mod. `Failed to initialize server` with
`bind(..) failed with error(-98)` is a previous `runServer` still holding port 25565; kill it
(`pgrep -f "[d]evlaunch"`) rather than debugging the mod.

## Environment recipe (this container, reproducible)

```sh
sudo apt-get update && sudo apt-get install -y openjdk-25-jdk-headless unrar   # update first: a stale index 404s
./gradle/install.sh                                                            # vendored Gradle 9.6.1 → /opt/gradle-9.6.1
cd 26.2 && JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle genSources --no-daemon
```

`genSources` does **not** leave a sources jar: Loom 1.17 writes a hash-addressed cache at
`~/.gradle/caches/fabric-loom/decompile/v1.zip`, where each entry is
`LOOM` + `NAME <internal/class/name>` + `SRC <source>` records with 4-byte big-endian lengths.
Unpack it into a real package tree before grepping (7055 files for 26.2) — that tree is what
every "verify the signature" instruction in these notes depends on.

## Parallel-agent lessons

- Splitting by **file**, not by package, is what kept three agents from colliding: all
  `*Model.java`/`*Renderer.java`/`*Screen.java` went to the client agent wherever they lived,
  including deep inside the gameplay packages.
- Agreeing the shared shapes **before** the agents start (here: registry fields stay
  `Supplier<T>`, so 229 `.get()` call sites never needed touching) removes the largest class of
  cross-agent conflict.
- Every remaining conflict was a *contract* question (does this class still expose that method),
  not an API question — and each was resolved in one message.
- The instructions given to agents will contain mistakes. Telling them "the decompiled source
  outranks this document, and say so when it does" is what surfaced the `Identifier` error
  instead of burying it.
