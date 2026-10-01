#!/bin/sh
# Start / stop the dev Minecraft client (guest-neoforge runClient) without ever leaving a second one
# running: two clients would fight over the link and the world lock, and the Mac only has 8 GB.
#
#   tools/mc_dev.sh start [logfile]   # background; waits until the mod is loaded
#   tools/mc_dev.sh stop
#   tools/mc_dev.sh status
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME=${SUBCRAFT_GRADLE_JDK:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}
match="fml.modFolders=subcraft"

running() { pgrep -f "$match" || true; }

case "${1:-status}" in
start)
	if [ -n "$(running)" ]; then
		echo "dev client already running (pid $(running | tr '\n' ' '))"; exit 1
	fi
	# A fresh file per start: a dying previous Gradle run may still append to its old log.
	log=${2:-${TMPDIR:-/tmp}/subcraft-client-$(date +%Y%m%d-%H%M%S).log}
	(cd "$root/guest-neoforge" && nohup ./gradlew runClient --console=plain > "$log" 2>&1 &)
	echo "starting dev client, log $log"
	i=0
	while [ $i -lt 120 ]; do
		if grep -a -q "SubCraft client ready" "$log" 2>/dev/null; then echo "client up (pid $(running | tr '\n' ' '))"; exit 0; fi
		if grep -a -q -E "BUILD FAILED|: error:" "$log" 2>/dev/null; then grep -a -E ": error:|FAILED" "$log"; exit 1; fi
		sleep 2; i=$((i + 1))
	done
	echo "timed out waiting for the client"; exit 1 ;;
stop)
	pkill -f "$match" || true
	pkill -f "GradleWrapperMain runClient" || true
	# Minecraft saves the world on SIGTERM; give it time, then insist.
	i=0
	while [ -n "$(running)" ] && [ $i -lt 20 ]; do sleep 1; i=$((i + 1)); done
	if [ -n "$(running)" ]; then echo "force-killing $(running | tr '\n' ' ')"; pkill -9 -f "$match" || true; sleep 1; fi
	[ -z "$(running)" ] && echo "stopped" || { echo "still running: $(running)"; exit 1; } ;;
status)
	pids=$(running)
	if [ -n "$pids" ]; then ps -o pid,rss,etime -p $(echo $pids | tr ' ' ','); else echo "not running"; fi ;;
esac
