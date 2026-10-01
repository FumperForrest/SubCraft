#!/bin/sh
# Start / stop Subnautica for dev runs (through Steam, so BepInEx loads), windowed at a low
# resolution (8 GB Mac). Sean's display prefs were exported to
# ~/Development/Modding/SubCraft-save-backups/subnautica-prefs-original.plist before the first dev
# run; `stop` restores the screen settings from it so his normal launch is unchanged.
#
#   tools/sn_dev.sh start [width height]   # waits until the SubCraft plugin has loaded
#   tools/sn_dev.sh stop
#   tools/sn_dev.sh status | log
set -eu
SN="$HOME/Library/Application Support/Steam/steamapps/common/Subnautica"
LOG="$SN/BepInEx/LogOutput.log"
PREFS="$HOME/Development/Modding/SubCraft-save-backups/subnautica-prefs-original.plist"
running() { pgrep -f "Subnautica.app/Contents/MacOS" || true; }

restore_prefs() {
	[ -f "$PREFS" ] || return 0
	python3 "$(dirname "$0")/sn_prefs.py" >/dev/null && echo "restored Subnautica screen prefs"
}

case "${1:-status}" in
start)
	if [ -n "$(running)" ]; then echo "Subnautica already running (pid $(running | tr '\n' ' '))"; exit 1; fi
	w=${2:-960}; h=${3:-540}
	before=$(stat -f %m "$LOG" 2>/dev/null || echo 0)
	# Arguments in a steam://run URL make Steam ask for confirmation, so the dev size goes through
	# Unity's screen prefs instead (restored by `stop`).
	defaults write "unity.Unknown Worlds.Subnautica" "Screenmanager Resolution Width" -int "$w"
	defaults write "unity.Unknown Worlds.Subnautica" "Screenmanager Resolution Height" -int "$h"
	defaults write "unity.Unknown Worlds.Subnautica" "Screenmanager Fullscreen mode" -int 3
	defaults write "unity.Unknown Worlds.Subnautica" "Screenmanager Resolution Use Native" -int 0
	open "steam://run/264710"
	echo "launching Subnautica ${w}x${h} windowed through Steam"
	i=0
	while [ $i -lt 180 ]; do
		now=$(stat -f %m "$LOG" 2>/dev/null || echo 0)
		if [ "$now" != "$before" ] && grep -a -q "SubCraft .* loaded" "$LOG" 2>/dev/null; then
			echo "plugin loaded (pid $(running | tr '\n' ' '))"; exit 0
		fi
		sleep 1; i=$((i + 1))
	done
	echo "timed out waiting for the SubCraft plugin; see $LOG"; exit 1 ;;
stop)
	if [ -n "$(running)" ]; then
		osascript -e 'tell application "Subnautica" to quit' >/dev/null 2>&1 || true
		i=0; while [ -n "$(running)" ] && [ $i -lt 20 ]; do sleep 1; i=$((i + 1)); done
		[ -n "$(running)" ] && pkill -f "Subnautica.app/Contents/MacOS" || true
		sleep 1
	fi
	restore_prefs
	[ -z "$(running)" ] && echo "stopped" ;;
status)
	pids=$(running)
	if [ -n "$pids" ]; then ps -o pid,rss,etime -p $(echo $pids | tr ' ' ','); else echo "not running"; fi ;;
log)
	grep -a "SubCraft" "$LOG" | tail -${2:-40} ;;
esac
