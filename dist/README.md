# dist

Compiled build of the Fabric / Minecraft 26.2 port (source in `../26.2/`).

| File | `simpleplanes-26.2-5.3.7.jar` |
|---|---|
| Minecraft | 26.2 |
| Loader | Fabric, loader ≥ 0.19.3 |
| Java | 25 |
| Requires | Fabric API 0.154.2+26.2 or newer |
| sha256 | `91bc9575f491aa4674288ab27fcf106c15b65c07bc538842dff02ffd7bc0da9a` |

Install: drop the jar and Fabric API into the `mods/` folder of a Fabric 26.2 profile
or server.

**Server-side is verified** — a dedicated 26.2 server boots clean with this jar
(`Done (…)!`, zero `/ERROR]` lines). **The client is not verified**: it has never been
run, because the build environment has no display. Client rendering is known to compile
and nothing more, and several visual features were deliberately dropped during the port —
see the "Disabled content" log in `../26.2/PORT-STATUS.md` before reporting a visual bug.

Rebuild with:

```sh
../gradle/install.sh          # vendored Gradle 9.6.1
cd ../26.2
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle build --no-daemon
```
