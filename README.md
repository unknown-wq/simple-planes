# simple-planes

Simple Planes mod for Minecraft.

https://www.curseforge.com/minecraft/mc-mods/simple-planes

## Repository layout

```
/
├── 1.21.1/            # the mod as it exists today — NeoForge 21.1.x / Minecraft 1.21.1
├── gradle/            # vendored Gradle 9.6.1 distribution (offline install, see gradle/README.md)
└── porting-26.2/      # agent instructions for porting to Minecraft 26.2
```

The old Gradle 8.7 wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) has been
removed. Minecraft 26.2 needs Java 25, which needs Gradle 9.x, and the wrapper
cannot download its distribution in the build environment — so Gradle is vendored
in `/gradle` instead:

```sh
./gradle/install.sh                       # unpacks to /opt/gradle-9.6.1
cd 1.21.1
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle build --no-daemon
```

Note that the current `1.21.1/` sources target Java 21 / NeoForge and still build
fine with Gradle 8.x if you have it locally; the vendored Gradle 9.6.1 is what the
26.2 port will need.

# issues

Please add mod version + minecraft version + mod loader version to every issue, and full server + client log + screenshot if applicable.
