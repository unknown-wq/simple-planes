# Simple Planes for Fabric — Plane & Helicopter Mod for Minecraft 26.2

**Simple Planes on Fabric for Minecraft 26.2** — an unofficial port of the popular
[Simple Planes](https://www.curseforge.com/minecraft/mc-mods/simple-planes) mod, which adds
craftable, upgradeable **planes and helicopters** to Minecraft. Upstream Simple Planes is
NeoForge-only and stops at Minecraft 1.21.1; this repository ports it to the **Fabric** loader and
to **Minecraft 26.2** (Java 25).

![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-brightgreen "Supported Minecraft version: 26.2")
![Mod loader: Fabric](https://img.shields.io/badge/Loader-Fabric%20%E2%89%A50.19.3-blue "Mod loader: Fabric 0.19.3 or newer")
![Java 25](https://img.shields.io/badge/Java-25-orange "Requires Java 25")
![License LGPL-3.0-or-later](https://img.shields.io/badge/License-LGPL--3.0--or--later-lightgrey "License: LGPL-3.0-or-later")

> **Download:** a compiled, ready-to-install jar is committed in
> **[`dist/simpleplanes-26.2-5.3.7.jar`](https://github.com/unknown-wq/simple-planes/raw/master/dist/simpleplanes-26.2-5.3.7.jar)** —
> nothing to build, no toolchain needed. Drop it in `mods/` together with Fabric API.

---

## Download Simple Planes for Minecraft 26.2 (Fabric)

`dist/` holds the **already compiled build** of the port, not just its source code. The source it
was built from is in `26.2/`; you only need that if you want to compile it yourself.

| | |
|---|---|
| **Download** | [`dist/simpleplanes-26.2-5.3.7.jar`](https://github.com/unknown-wq/simple-planes/raw/master/dist/simpleplanes-26.2-5.3.7.jar) (compiled, ready to use) |
| **Minecraft version** | 26.2 |
| **Mod loader** | Fabric, loader 0.19.3 or newer |
| **Java version** | 25 |
| **Required dependency** | Fabric API 0.154.2+26.2 or newer |
| **Mod version** | 5.3.7 |
| **Side** | client + server (`environment: "*"`) |
| **License** | LGPL-3.0-or-later |

Build details and the exact sha256 checksum: [`dist/README.md`](dist/README.md).

### How to install

1. Install the **Fabric loader** (0.19.3+) for Minecraft **26.2**, and make sure you are running
   **Java 25**.
2. Download **[Fabric API](https://modrinth.com/mod/fabric-api)** for 26.2 (0.154.2+26.2 or newer)
   — Simple Planes will not load without it.
3. Download **[`simpleplanes-26.2-5.3.7.jar`](https://github.com/unknown-wq/simple-planes/raw/master/dist/simpleplanes-26.2-5.3.7.jar)**
   from `dist/`.
4. Put both jars into the `mods/` folder of your Fabric 26.2 profile — or of your Fabric server.
5. Launch. Craft a **Plane Workbench** to start building aircraft.

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

## Port status — what works and what was cut

**Server-side is verified:** a dedicated Minecraft 26.2 Fabric server boots clean with this jar
(zero `ERROR` lines). A clean boot exercises loading and registration only — flight, upgrades and
packet handling have not been play-tested.

**The client is not verified.** It has never actually been run, because the build environment has
no display, so client rendering is known to compile and nothing more.

To reach a green build, some features — mostly client visuals and mod compatibility — were
deliberately dropped:

- All mod compat (`compat/**`): **JEI, Iron Chests, Quark, MrCrayfish's Gun Mod** integration is gone.
- The config is **not editable** — NeoForge's `ModConfigSpec` was replaced by static defaults.
- **NeoForge capabilities do not exist on Fabric:** other mods can no longer pipe items, energy or
  fluid into or out of a plane. The Liquid Engine takes **vanilla buckets only**.
- Not rendered: camera roll, rotated riding players, banner on the tail, blocks inside the cargo
  bay, item tinting by build material, and the fuel / energy HUD gauges.

The complete per-file list is the **Disabled content** log in
[`26.2/PORT-STATUS.md`](26.2/PORT-STATUS.md) — read it before reporting a missing visual as a bug.

## What this port changes technically

The conversion moves along two axes at once:

- **Mod loader:** NeoForge 21.1.61 → **Fabric** — entrypoints, registry calls, networking payloads,
  capabilities and event subscriptions all rewritten.
- **Vanilla API:** Minecraft 1.21.1 / Java 21 → **Minecraft 26.2 / Java 25** — render-state
  renderers, `ValueInput`/`ValueOutput` NBT, `defineSynchedData(Builder)`, `EntitySpawnReason`,
  Blaze3D/Vulkan rendering, item model definitions.

Mappings are Mojang official on both sides: 26.1 was the first unobfuscated release, so there is no
Yarn/Intermediary step. General notes on porting any 1.21.x mod to 26.2 live in
[`porting-26.2/`](porting-26.2/README.md).

## Repository layout

```
/
├── 1.21.1/            # upstream sources, unmodified — NeoForge 21.1.x / Minecraft 1.21.1
├── 26.2/              # source code of the Fabric / Minecraft 26.2 port (+ PORT-STATUS.md)
├── dist/              # COMPILED, ready-to-install jar for Minecraft 26.2
├── gradle/            # vendored Gradle 9.6.1 distribution (offline install)
└── porting-26.2/      # general 1.21.x → 26.2 porting notes for coding agents
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
The upstream releases do not. This port does: it targets Minecraft 26.2 on Fabric with Java 25.

**Do I need to compile anything?**
No. `dist/simpleplanes-26.2-5.3.7.jar` is a finished build — drop it into `mods/`.

**Do I need Fabric API?**
Yes, 0.154.2+26.2 or newer. The mod will not load without it.

**Does it work on a server?**
Yes — the jar is for both sides, and a dedicated 26.2 server has been verified to boot with it.
Gameplay has not been play-tested, and the client has never been launched.

**Is it compatible with JEI / Iron Chests / Quark / MrCrayfish's Gun Mod?**
No. All compat modules were removed during the port.

**Which Minecraft versions are supported?**
Only 26.2 (`>=26.2 <26.3`). For 1.21.1 and older, use the
[upstream NeoForge releases](https://www.curseforge.com/minecraft/mc-mods/simple-planes).

**Is this the same as the SimplePlanes game?**
No. This is a Minecraft mod, unrelated to the standalone airplane-builder game of a similar name.

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

## Issues

For bugs **in this port**, open an issue here and include: the jar version, Minecraft version,
Fabric loader and Fabric API versions, plus the full client and server log and a screenshot if
applicable. Check the disabled-content log above first — a missing visual is probably a known cut,
not a bug.

For bugs that also happen on NeoForge 1.21.1, report them
[upstream](https://github.com/przemykomo/simple-planes/issues) instead.

## Русская версия

**Simple Planes для Fabric на Minecraft 26.2** — неофициальный порт мода на **самолёты и
вертолёты** для Minecraft. Оригинальный мод существует только для NeoForge и только до версии
1.21.1; здесь он портирован на загрузчик **Fabric** и на **Minecraft 26.2** (Java 25).

**Скачать готовую сборку:**
[`dist/simpleplanes-26.2-5.3.7.jar`](https://github.com/unknown-wq/simple-planes/raw/master/dist/simpleplanes-26.2-5.3.7.jar)
— это **уже скомпилированный jar**, собирать ничего не нужно. Положите его вместе с
[Fabric API](https://modrinth.com/mod/fabric-api) (0.154.2+26.2 или новее) в папку `mods/` профиля
или сервера Fabric 26.2.

Что нужно знать: сервер запускается чисто и проверен, **клиент ни разу не запускался**, полёты не
тестировались. Совместимость с JEI, Iron Chests, Quark и MrCrayfish's Gun Mod вырезана, конфиг не
редактируется, часть визуальных эффектов не отрисовывается — полный список в
[`26.2/PORT-STATUS.md`](26.2/PORT-STATUS.md). Автор оригинала — **przemykomo / Przemyk**,
исходный репозиторий: <https://github.com/przemykomo/simple-planes>, лицензия LGPL-3.0-or-later.

---

<sub>Keywords: Simple Planes Fabric, Simple Planes 26.2, Minecraft 26.2 plane mod, Minecraft 26.2
helicopter mod, Fabric plane mod, Fabric aircraft mod, simpleplanes-26.2-5.3.7.jar, Simple Planes
Fabric download, NeoForge to Fabric port, Minecraft 26.2 mods, Minecraft 26.2 Java 25 mod,
мод на самолёты Minecraft 26.2, Simple Planes Фабрик скачать.</sub>
