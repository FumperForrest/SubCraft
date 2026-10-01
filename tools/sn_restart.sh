#!/bin/sh
# Rebuild + redeploy the plugin, restart Subnautica, load the dev slot, run an optional scenario.
#   tools/sn_restart.sh [scenario.jsonl]
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
"$root/tools/sn_dev.sh" stop >/dev/null || true
(cd "$root/host-subnautica" && dotnet build SubCraft.Host.csproj 2>&1 | grep -E " error |deployed" || true)
dotnet build-server shutdown >/dev/null 2>&1 || true
"$root/tools/sn_dev.sh" start
i=0
until python3 "$root/tools/sn_cmd.py" '{"cmd":"load"}' >/dev/null 2>&1; do
	i=$((i + 1)); [ $i -gt 60 ] && { echo "main menu never accepted load"; exit 1; }
	python3 "$root/tools/sn_cmd.py" '{"cmd":"wait","seconds":3}' >/dev/null
done
python3 "$root/tools/sn_cmd.py" '{"cmd":"waitingame","timeout":300}'
[ $# -gt 0 ] && python3 "$root/tools/sn_cmd.py" --scenario "$1"
exit 0
