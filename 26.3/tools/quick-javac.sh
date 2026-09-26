#!/usr/bin/env bash
# Compiles src/main/java with plain javac, without Gradle and without the build lock.
#   tools/quick-javac.sh [out-dir]
# A fast syntax/type check only; the real build stays mc-build.sh.
set -uo pipefail

MOD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRANCH="$(git -C "$MOD" branch --show-current 2>/dev/null | tr / -)"
OUT="${1:-/tmp/quick-javac-${BRANCH:-detached}}"
JAVAC=/usr/lib/jvm/java-25-openjdk-amd64/bin/javac
# Prefer this project's Loom-processed jar (Fabric API's transitive access wideners and injected
# interfaces applied); it exists after one mc-build.sh run. The raw deobf jar lacks both.
MC="$(ls -t "$MOD"/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.3/minecraft-merged-*-26.3.jar 2>/dev/null | grep -v sources | head -1)"
if [ -z "$MC" ]; then
	MC=~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar
	echo "quick-javac: no processed jar in .gradle/loom-cache yet (run mc-build.sh <dir> compileJava once); using the raw jar" >&2
fi
M2=~/.gradle/caches/modules-2/files-2.1
FAPI_VERSION="$(sed -n 's/^fabric_version=//p' "$MOD/gradle.properties")"
LOADER_VERSION="$(sed -n 's/^loader_version=//p' "$MOD/gradle.properties")"

for p in "$JAVAC" "$MC"; do
	[ -e "$p" ] || { echo "missing: $p" >&2; exit 2; }
done

CP="$MC"
add() { [ -n "$1" ] && CP="$CP:$1"; }

# Fabric API modules at the exact versions the aggregate pom pins (the cache also holds 26.2 ones).
for pom in "$M2/net.fabricmc.fabric-api/fabric-api/$FAPI_VERSION"/*/*.pom \
           "$M2/net.fabricmc.fabric-api/fabric-api-deprecated/$FAPI_VERSION"/*/*.pom; do
	[ -f "$pom" ] || continue
	while read -r art ver; do
		for j in "$M2/net.fabricmc.fabric-api/$art/$ver"/*/"$art-$ver.jar"; do
			[ -f "$j" ] && add "$j"
		done
	done < <(grep -A2 '<artifactId>' "$pom" | sed -n 's:.*<artifactId>\(.*\)</artifactId>.*:\1:p;s:.*<version>\(.*\)</version>.*:\1:p' | paste - - | grep -v '^fabric-api\s')
done
for j in "$M2/net.fabricmc/fabric-loader/$LOADER_VERSION"/*/fabric-loader-"$LOADER_VERSION".jar; do
	[ -f "$j" ] && add "$j"
done

# Everything else: newest version first, sources jars excluded.
for g in net.fabricmc/sponge-mixin org.joml org.jspecify com.google.guava com.google.code.gson org.slf4j \
         org.apache.logging.log4j io.netty com.mojang it.unimi.dsi org.apache.commons commons-io org.jetbrains; do
	[ -d "$M2/$g" ] || continue
	while read -r j; do add "$j"; done < <(find "$M2/$g" -name '*.jar' ! -name '*-sources.jar' | sort -V -r)
done

mkdir -p "$OUT"
LIST="$(mktemp)"
find "$MOD/src/main/java" -name '*.java' > "$LIST"
LOG="$(mktemp)"
"$JAVAC" -proc:none -nowarn -encoding UTF-8 --release 25 -Xmaxerrs 5000 -d "$OUT" -cp "$CP" @"$LIST" > "$LOG" 2>&1
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
	grep -v '^Note:' "$LOG" | head -40
	echo "quick-javac: FAILED ($(grep -c ': error:' "$LOG") errors), classes in $OUT"
else
	echo "quick-javac: ok, $(wc -l < "$LIST") files -> $OUT"
fi
rm -f "$LIST" "$LOG"
exit "$STATUS"
