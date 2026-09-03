# Simple Planes for Fabric — Plane & Helicopter Mod for Minecraft 26.2

**Simple Planes on Fabric for Minecraft 26.2** — an unofficial port of the popular
[Simple Planes](https://www.curseforge.com/minecraft/mc-mods/simple-planes) mod, which adds
craftable, upgradeable **planes and helicopters** to Minecraft. Upstream Simple Planes is
NeoForge-only and stops at Minecraft 1.21.1; this repository ports it to the **Fabric** loader and
to **Minecraft 26.2** (Java 25).

![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-brightgreen "Supported Minecraft version: 26.2")
![Mod loader: Fabric](https://img.shields.io/badge/Loader-Fabric%20%E2%89%A50.19.3-blue "Mod loader: Fabric 0.19.3 or newer")
![Java 25](https://img.shields.io/badge/Java-25-orange "Requires Java 25")
![Tested](https://img.shields.io/badge/Status-tested-success "The port has been tested in game")
![License LGPL-3.0-or-later](https://img.shields.io/badge/License-LGPL--3.0--or--later-lightgrey "License: LGPL-3.0-or-later")

> **Download:** a compiled, ready-to-install jar is committed in
> **[`dist/simpleplanes-26.2-5.3.7.jar`](https://github.com/unknown-wq/simple-planes/raw/26.2/dist/simpleplanes-26.2-5.3.7.jar)** —
> nothing to build, no toolchain needed. Drop it in `mods/` together with Fabric API.

> **Commands:** a cheat sheet for every autopilot command and tool is in
> **[`COMMANDS.md`](COMMANDS.md)**.

---

## Download Simple Planes for Minecraft 26.2 (Fabric)

`dist/` holds the **already compiled build** of the port, not just its source code. The source it
was built from is in `26.2/`; you only need that if you want to compile it yourself.

| | |
|---|---|
| **Download** | [`dist/simpleplanes-26.2-5.3.7.jar`](https://github.com/unknown-wq/simple-planes/raw/26.2/dist/simpleplanes-26.2-5.3.7.jar) (compiled, ready to use) |
| **Minecraft version** | 26.2 |
| **Mod loader** | Fabric, loader 0.19.3 or newer |
| **Java version** | 25 |
| **Required dependency** | Fabric API 0.154.2+26.2 or newer |
| **Mod version** | 5.3.7 |
| **Side** | client + server (`environment: "*"`) |
| **Status** | tested — client and server |
| **License** | LGPL-3.0-or-later |

Build details and the exact sha256 checksum: [`dist/README.md`](dist/README.md).

### How to install

1. Install the **Fabric loader** (0.19.3+) for Minecraft **26.2**, and make sure you are running
   **Java 25**.
2. Download **[Fabric API](https://modrinth.com/mod/fabric-api)** for 26.2 (0.154.2+26.2 or newer)
   — Simple Planes will not load without it.
3. Download **[`simpleplanes-26.2-5.3.7.jar`](https://github.com/unknown-wq/simple-planes/raw/26.2/dist/simpleplanes-26.2-5.3.7.jar)**
   from `dist/`.
4. Put both jars into the `mods/` folder of your Fabric 26.2 profile — or of your Fabric server.
5. Launch. Craft a **Plane Workbench** to start building aircraft.

## Status

**The port is tested and playable.** It has been checked on Minecraft 26.2 with Fabric — the mod
loads on both the client and a dedicated server, aircraft can be built and flown, and the port is
in a state where it is meant to be used, not just compiled.

If something does not work on your setup, **[open an issue](https://github.com/unknown-wq/simple-planes/issues/new)**
— see [Issues](#issues) below for what to include. Bug reports are the way this port gets better;
please do not send port-specific problems to the upstream authors.

## Features

Everything below comes from the original mod; the port keeps the gameplay intact.

- **Aircraft:** Plane, Large Plane, Cargo Plane, Helicopter, plus a deployable Parachute.
- **Engines:** Furnace Engine (coal), Electric Engine (Forge Energy–style charging), Liquid Engine
  (fuel from a bucket — lava and other configured liquid fuels).
- **Upgrades:** Plane Armor, Seats, Shooter, Rocket Booster, Solar Panel, Propeller, Folding
  Upgrade, Floaty Bedding, Quick Fix Kit, Chest storage, Payload, Supply Crate, Banner, Jukebox.
- **Blocks:** Plane Workbench, Charging Station.
- **Wrench** for reconfiguring an assembled aircraft.
- **Languages:** English, Russian, Ukrainian, Italian, Japanese, Simplified Chinese.

## Known limitations

Some features were deliberately dropped to get the port onto Fabric and 26.2. These are **known
cuts, not bugs** — check this list before reporting one:

- All mod compat (`compat/**`): **JEI, Iron Chests, Quark, MrCrayfish's Gun Mod** integration is gone.
- The config is **not editable** — NeoForge's `ModConfigSpec` was replaced by static defaults.
- **NeoForge capabilities do not exist on Fabric:** other mods can no longer pipe items, energy or
  fluid into or out of a plane. The Liquid Engine takes **vanilla buckets only**.
- Not rendered: camera roll, rotated riding players, banner on the tail, blocks inside the cargo
  bay, item tinting by build material, and the fuel / energy HUD gauges.

The complete per-file list is the **Disabled content** log in
[`26.2/PORT-STATUS.md`](26.2/PORT-STATUS.md).

Anything *not* on that list that misbehaves is a bug —
[report it](https://github.com/unknown-wq/simple-planes/issues/new).

## What this port changes technically

The conversion moves along two axes at once:

- **Mod loader:** NeoForge 21.1.61 → **Fabric** — entrypoints, registry calls, networking payloads,
  capabilities and event subscriptions all rewritten.
- **Vanilla API:** Minecraft 1.21.1 / Java 21 → **Minecraft 26.2 / Java 25** — render-state
  renderers, `ValueInput`/`ValueOutput` NBT, `defineSynchedData(Builder)`, `EntitySpawnReason`,
  Blaze3D/Vulkan rendering, item model definitions.

Mappings are Mojang official on both sides: 26.1 was the first unobfuscated release, so there is no
Yarn/Intermediary step.

## Repository layout

```
/
├── 1.21.1/            # upstream sources, unmodified — NeoForge 21.1.x / Minecraft 1.21.1
├── 26.2/              # source code of the Fabric / Minecraft 26.2 port (+ PORT-STATUS.md)
├── dist/              # COMPILED, ready-to-install jar for Minecraft 26.2
└── gradle/            # vendored Gradle 9.6.1 distribution (offline install)
```

## Building from source

You do not need this to play — [`dist/`](dist/README.md) already contains the compiled jar.

There is no `gradlew`. Minecraft 26.2 needs Java 25, which needs Gradle 9.x, and the wrapper cannot
download its own distribution in this build environment — so Gradle 9.6.1 is vendored in
[`gradle/`](gradle/README.md):

```sh
./gradle/install.sh          # unpacks to /opt/gradle-9.6.1 (needs unrar)
cd 26.2
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle build --no-daemon
```

The `1.21.1/` sources still target Java 21 / NeoForge and build fine with Gradle 8.x if you have it
locally; the vendored Gradle 9.6.1 is what the 26.2 port needs.

## FAQ

**Is there a Fabric version of Simple Planes?**
Not officially — the original mod is NeoForge-only. This repository is an unofficial Fabric port,
and the compiled jar for Minecraft 26.2 is in [`dist/`](dist/README.md).

**Does Simple Planes work on Minecraft 26.2?**
The upstream releases do not. This port does: it targets Minecraft 26.2 on Fabric with Java 25, and
it has been tested there.

**Do I need to compile anything?**
No. `dist/simpleplanes-26.2-5.3.7.jar` is a finished build — drop it into `mods/`.

**Do I need Fabric API?**
Yes, 0.154.2+26.2 or newer. The mod will not load without it.

**Does it work on a server?**
Yes — the jar is for both sides, and a dedicated Fabric 26.2 server runs it.

**Is it compatible with JEI / Iron Chests / Quark / MrCrayfish's Gun Mod?**
No. All compat modules were removed during the port.

**Which Minecraft versions are supported?**
Only 26.2 (`>=26.2 <26.3`). For 1.21.1 and older, use the
[upstream NeoForge releases](https://www.curseforge.com/minecraft/mc-mods/simple-planes).

**Something is broken / crashes — what do I do?**
[Open an issue](https://github.com/unknown-wq/simple-planes/issues/new) with your logs. Check
[Known limitations](#known-limitations) first in case it is an intentional cut.

**Is this the same as the SimplePlanes game?**
No. This is a Minecraft mod, unrelated to the standalone airplane-builder game of a similar name.

## Issues

Found a problem? **[Open an issue](https://github.com/unknown-wq/simple-planes/issues/new)** — that
is the right place for anything wrong with this port, from a crash to a plane that flies oddly.

Please include:

- the jar version (`simpleplanes-26.2-5.3.7.jar`) and your Minecraft version;
- your Fabric loader and Fabric API versions;
- the **full** log (`logs/latest.log`; the server log too, if it happened in multiplayer);
- what you did, what you expected, what happened — and a screenshot or clip if it is visual.

Check [Known limitations](#known-limitations) first: the removed compat modules and the few
unrendered visuals are intentional, so those are not worth an issue.

Bugs that also happen on **NeoForge 1.21.1** belong
[upstream](https://github.com/przemykomo/simple-planes/issues) instead — they are in the original
mod, not in this port.

## Credits

All gameplay, models, textures and original code are the work of the upstream authors — this
repository only ports them to Fabric and Minecraft 26.2.

- Original mod by **przemykomo / Przemyk** (and adoxentor), with contributions from many others —
  see the git history and the upstream contributor list.
- **Original repository:** <https://github.com/przemykomo/simple-planes>
- **CurseForge:** <https://www.curseforge.com/minecraft/mc-mods/simple-planes>
- **Modrinth:** <https://modrinth.com/mod/simple-planes>

Licensed under **LGPL-3.0-or-later**, same as upstream (`1.21.1/LICENSE`, `26.2/LICENSE`).

This port is **not** affiliated with or endorsed by the upstream authors. Please do not send them
port-specific bug reports.

---

<sub>Keywords: Simple Planes Fabric, Simple Planes 26.2, Minecraft 26.2 plane mod, Minecraft 26.2
helicopter mod, Fabric plane mod, Fabric aircraft mod, simpleplanes-26.2-5.3.7.jar, Simple Planes
Fabric download, NeoForge to Fabric port, Minecraft 26.2 mods, Minecraft 26.2 Java 25 mod.</sub>
