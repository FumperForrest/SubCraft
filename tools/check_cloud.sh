#!/bin/sh
# Every check that runs without the games (cloud sessions, CI, or before a push anywhere).
# Usage: tools/check_cloud.sh [layout|python|guest|host|compile]...   (no argument: all of them)
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
want=${*:-layout python guest host compile}
for step in $want; do
	echo "== $step"
	case $step in
		layout) "$root/tools/check_layout.sh" ;;
		python) python3 -m unittest discover -s "$root/tools/tests" -t "$root/tools" ;;
		guest) (cd "$root/guest-neoforge" && ./gradlew -q -Dorg.gradle.jvmargs=-Xmx3G build test) ;;
		host) (cd "$root/host-subnautica/tests" && dotnet test --nologo -v q) ;;
		compile) (cd "$root/host-subnautica/compile-check" && dotnet build --nologo -v q) ;;
		*) echo "unknown step $step" >&2; exit 2 ;;
	esac
done
echo "all cloud checks passed: $want"
