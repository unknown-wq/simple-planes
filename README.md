# Simple Planes — Fabric port to Minecraft 26.2

An unofficial **Fabric** port of the [Simple Planes](https://www.curseforge.com/minecraft/mc-mods/simple-planes)
mod (upgradeable planes and helicopters) to **Minecraft 26.2**.

The upstream mod is NeoForge-only and targets Minecraft 1.21.1. This repository keeps those
sources untouched in `1.21.1/` and adds `26.2/`, a conversion of them across two axes at once:

* **Loader:** NeoForge 21.1.61 → Fabric (entrypoints, registration, networking, capabilities, events)
* **Vanilla API:** 1.21.1 / Java 21 → 26.2 / Java 25 (render-state renderers, `ValueInput`/`ValueOutput`
  NBT, `EntitySpawnReason`, Blaze3D/Vulkan, item model definitions, …)

## Credits

All of the gameplay, models, textures and original code are the work of the upstream authors —
this repository only ports them.

* Original mod by **przemykomo / Przemyk** (and adoxentor), with contributions from many others —
  see the git history and the upstream contributor list.
* Original repository: <https://github.com/przemykomo/simple-planes>
* CurseForge: <https://www.curseforge.com/minecraft/mc-mods/simple-planes>
* Modrinth: <https://modrinth.com/mod/simple-planes>

Licensed under **LGPL-3.0-or-later**, same as upstream (`1.21.1/LICENSE`, `26.2/LICENSE`).

This port is **not** affiliated with or endorsed by the upstream authors. Do not report port bugs
to them — see [Issues](#issues).

## Download

A compiled build lives in [`dist/`](dist/README.md):

| | |
|---|---|
| File | `dist/simpleplanes-26.2-5.3.7.jar` |
| Minecraft | 26.2 |
| Loader | Fabric, loader ≥ 0.19.3 |
| Java | 25 |
| Requires | Fabric API 0.154.2+26.2 or newer |

Drop the jar and Fabric API into the `mods/` folder of a Fabric 26.2 client profile or server.

## Port status

**Server-side is verified**: a dedicated 26.2 server boots clean with this jar (zero `ERROR` lines).
A clean boot exercises loading and registration only — flight, upgrades and packet handling have not
been play-tested.

**The client is not verified.** It has never been run — the build environment has no display — so
client rendering is known to compile and nothing more.

Several features were deliberately dropped to get a green build, most of them client visuals and
mod compat. The headline cuts:

* All mod compat (`compat/**`): JEI, IronChests, Quark, MrCrayfishGun.
* Config is not editable — `ModConfigSpec` is replaced by static defaults.
* NeoForge capabilities are gone: other mods can no longer pull items/energy/fluid out of a plane.
  The liquid engine accepts vanilla buckets only.
* Camera roll, rotated riding players, banner-on-the-tail, cargo-bay block rendering, item tinting by
  build material, and the fuel/energy HUD gauges are not rendered.

The full, per-file list is the **Disabled content** log in [`26.2/PORT-STATUS.md`](26.2/PORT-STATUS.md).
Check it before reporting a visual bug.

## Repository layout

```
/
├── 1.21.1/            # upstream sources, unmodified — NeoForge 21.1.x / Minecraft 1.21.1
├── 26.2/              # the Fabric / Minecraft 26.2 port (+ PORT-STATUS.md)
├── dist/              # compiled 26.2 jar
├── gradle/            # vendored Gradle 9.6.1 distribution (offline install)
└── porting-26.2/      # general 1.21.x → 26.2 porting notes for coding agents
```

## Building

There is no `gradlew`. Minecraft 26.2 needs Java 25, which needs Gradle 9.x, and the wrapper cannot
download its distribution in this build environment — so Gradle 9.6.1 is vendored in
[`gradle/`](gradle/README.md) instead:

```sh
./gradle/install.sh          # unpacks to /opt/gradle-9.6.1 (needs unrar)
cd 26.2
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle build --no-daemon
```

The `1.21.1/` sources still target Java 21 / NeoForge and build fine with Gradle 8.x if you have it
locally; the vendored Gradle 9.6.1 is what the 26.2 port needs.

## Issues

For **this port**, open an issue here and include: the jar version, Minecraft version, Fabric loader
and Fabric API versions, plus the full client and server log and a screenshot if applicable. Please
check the disabled-content log above first — a missing visual is probably a known cut, not a bug.

For bugs that also happen on NeoForge 1.21.1, report them
[upstream](https://github.com/przemykomo/simple-planes/issues) instead.
