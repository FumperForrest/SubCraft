#!/bin/sh
# Regenerates protocol/layout_dump.cpp (tools/gen_layout_dump.py) and protocol/layout.json from the
# C++ header and fails if either differs from the committed file. Pass --write to update both
# instead (after a deliberate protocol change).
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
out=${TMPDIR:-/tmp}/subcraft_layout_dump
if [ "${1:-}" = "--write" ]; then
	python3 "$root/tools/gen_layout_dump.py"
else
	python3 "$root/tools/gen_layout_dump.py" --check
fi
clang++ -std=c++20 -Wall -Wextra -Werror -o "$out" "$root/protocol/layout_dump.cpp"
if [ "${1:-}" = "--write" ]; then
	"$out" > "$root/protocol/layout.json"
	echo "wrote protocol/layout.json"
else
	"$out" | diff -u "$root/protocol/layout.json" - && echo "layout.json matches subcraft_protocol.h"
fi
