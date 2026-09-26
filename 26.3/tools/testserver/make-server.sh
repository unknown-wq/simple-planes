#!/usr/bin/env bash
# Builds a headless 26.3 test server directory from inputs already on this container.
#   make-server.sh <dir> <port>
# Nothing is downloaded. The mod jar is not copied: put build/libs/simpleplanes-*.jar into <dir>/mods/.
set -euo pipefail

if [ "$#" -ne 2 ]; then
	echo "usage: make-server.sh <dir> <port>" >&2
	exit 2
fi
DIR="$1"
PORT="$2"
case "$PORT" in ''|*[!0-9]*) echo "port must be a number: $PORT" >&2; exit 2 ;; esac

SRC=/tmp/mc-server-26.3
FAPI=/home/user/minecolonies-fabric/.cache/fabric-api-0.160.5+26.3.jar

missing=0
for p in "$SRC/fabric-server-launch.jar" "$SRC/libraries" "$SRC/versions" "$SRC/.fabric" "$FAPI"; do
	if [ ! -e "$p" ]; then
		echo "missing input: $p" >&2
		missing=1
	fi
done
[ "$missing" = 0 ] || exit 2

if [ -e "$DIR" ] && [ -n "$(ls -A "$DIR" 2>/dev/null)" ]; then
	echo "refusing: $DIR exists and is not empty" >&2
	exit 1
fi

mkdir -p "$DIR/mods"
DIR="$(cd "$DIR" && pwd)"
cp "$SRC/fabric-server-launch.jar" "$DIR/"
cp -r "$SRC/libraries" "$SRC/versions" "$SRC/.fabric" "$DIR/"
cp "$FAPI" "$DIR/mods/"

echo "eula=true" > "$DIR/eula.txt"
echo "[]" > "$DIR/ops.json"

cat > "$DIR/server.properties" <<PROPS
level-type=minecraft\:flat
generator-settings={"layers"\:[{"block"\:"minecraft\:bedrock","height"\:1},{"block"\:"minecraft\:dirt","height"\:2},{"block"\:"minecraft\:grass_block","height"\:1}],"biome"\:"minecraft\:plains"}
level-name=world
level-seed=aircraft
gamemode=creative
online-mode=false
spawn-monsters=false
spawn-protection=0
max-tick-time=-1
pause-when-empty-seconds=0
view-distance=6
simulation-distance=6
enable-query=false
enable-rcon=false
server-port=$PORT
max-players=2
white-list=false
sync-chunk-writes=false
PROPS

cat > "$DIR/start.sh" <<'SH'
#!/usr/bin/env bash
# Starts the server; blocks until "Done (" (exit 0) or 120 s / death (exit 1).
# MC_JVM_OPTS is passed to java, e.g. MC_JVM_OPTS=-Dsimpleplanes.aircraft.trace=true ./start.sh
RUN="$(cd "$(dirname "$0")" && pwd)"
cd "$RUN"
if [ -f server.pid ] && kill -0 "$(cat server.pid)" 2>/dev/null; then
	echo "already running (pid $(cat server.pid))"; exit 1
fi
[ -p stdin.fifo ] || mkfifo stdin.fifo
nohup bash -c 'exec 3<>"'"$RUN"'/stdin.fifo"; sleep 1000000' >/dev/null 2>&1 &
echo $! > holder.pid
: > console.log
nohup java -Xmx2G $MC_JVM_OPTS -jar fabric-server-launch.jar nogui < stdin.fifo > console.log 2>&1 &
echo $! > server.pid
for i in $(seq 1 120); do
	if grep -q 'Done (' console.log; then grep 'Done (' console.log; exit 0; fi
	kill -0 "$(cat server.pid)" 2>/dev/null || { echo "server died"; tail -40 console.log; exit 1; }
	sleep 1
done
echo "timeout: no 'Done (' after 120 s"
exit 1
SH

cat > "$DIR/cmd.sh" <<'SH'
#!/usr/bin/env bash
# Feeds one console command to the running server.
RUN="$(cd "$(dirname "$0")" && pwd)"
exec 3<>"$RUN/stdin.fifo"; printf '%s\n' "$*" >&3; exec 3>&-
SH

cat > "$DIR/stop.sh" <<'SH'
#!/usr/bin/env bash
# Sends "stop" and waits for the java process to exit.
RUN="$(cd "$(dirname "$0")" && pwd)"
cd "$RUN"
[ -f server.pid ] || { echo "not running"; exit 0; }
PID="$(cat server.pid)"
if kill -0 "$PID" 2>/dev/null; then
	./cmd.sh stop
	for i in $(seq 1 90); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
	kill -0 "$PID" 2>/dev/null && { echo "still running after 90 s, killing"; kill "$PID"; }
fi
[ -f holder.pid ] && kill "$(cat holder.pid)" 2>/dev/null || true
rm -f server.pid holder.pid
echo stopped
SH

chmod +x "$DIR/start.sh" "$DIR/cmd.sh" "$DIR/stop.sh"

echo "mods:  $DIR/mods/"
echo "port:  $PORT"
echo "cmd:   $DIR/cmd.sh \"<console command>\""
