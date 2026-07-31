# Porting notes → Minecraft 26.2

Instructions for coding agents doing a 1.21.x → **26.2** port. These documents were
written for the Fabric Lucky TNT Mod port (`unknown-wq/Fabric-LuckyTNTMod`) and are
copied here verbatim — the toolchain facts, the staged porting path and the API
rename maps are what transfer; the file paths, module names (`tntmod/`, `TntLib/`)
and progress checklists are specific to that repository, not to Simple Planes.

Note that Simple Planes is a **NeoForge** mod while those notes were written for
**Fabric**. Everything about Minecraft itself (version scheme, mappings, Java 25,
render-state and Blaze3D rewrites, vanilla API renames) applies either way; the
loader-specific parts (Loom, Fabric API, `fabric.mod.json`, mixins config) do not.

| File | What it is |
|---|---|
| `PORT-ANY-MOD-26.2.md` | **Mod-agnostic playbook** (in Russian): orchestrator loop, agent roles and prompts, environment setup, contracts, acceptance criteria — distilled from all four completed ports. Start here when porting a *new* mod. |
| `PORTING-GUIDE-26.2.md` | **Read first.** Technical reference: why naive porting breaks, the mandatory staged path, per-version breaking changes, and the web-recheck prompt to run before implementing anything version-specific. |
| `PORT-PLAN-26.2.md` | Execution plan: order of work, stage-by-stage done-criteria. |
| `PORT-MOD-26.2.md` | The verified yarn→Mojang / 1.21→26.2 rename map plus per-area porting recipes (registration, entities, renderers, networking, worldgen). |
| `PORT-CHEATSHEET.md` | Verified fixes for the recurring compile errors that survive the mechanical renames. |

The headline facts, so they are not missed:

1. The target is `26.2`, **not** "1.26.2" — Minecraft moved to a `year.drop.hotfix`
   scheme in 2026.
2. **Java 21 → Java 25** from 26.1.
3. 26.1 is the first **unobfuscated** release; Yarn/Intermediary are discontinued
   after 1.21.11.
4. Do **not** jump 1.21.1 → 26.2 directly. Go in stages, green build at each one.
5. Your training data predates these releases — verify every signature against live
   sources before writing it.
