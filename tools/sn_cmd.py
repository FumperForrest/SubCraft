"""Send dev-harness commands to the running Subnautica (SubCraft plugin) and wait for results.

    python3 tools/sn_cmd.py '{"cmd":"dump"}' '{"cmd":"screenshot","name":"noon"}'
    python3 tools/sn_cmd.py --scenario tools/scenarios/phase0b_recon.jsonl

Commands are appended to $TMPDIR/subcraft/cmd.jsonl; results come back in
$TMPDIR/subcraft/out/results.jsonl (see host-subnautica/src/Dev/DevHarness.cs for the list).
Exit code 1 if any command failed or timed out.
"""

import json
import os
import sys
import time

DIR = os.environ.get("SUBCRAFT_DIR") or os.path.join(os.environ.get("TMPDIR", "/tmp"), "subcraft")
CMD = os.path.join(DIR, "cmd.jsonl")
RESULTS = os.path.join(DIR, "out", "results.jsonl")


def result_lines():
    try:
        with open(RESULTS) as f:
            return f.readlines()
    except FileNotFoundError:
        return []


def run(commands, timeout_per=240.0):
    ok = True
    for c in commands:
        seen = len(result_lines())
        with open(CMD, "a") as f:
            f.write(json.dumps(c) + "\n")
        deadline = time.time() + timeout_per + float(c.get("seconds", 0)) + float(c.get("timeout", 0))
        while time.time() < deadline:
            lines = result_lines()
            fresh = [json.loads(l) for l in lines[seen:] if l.strip()]
            done = [r for r in fresh if r.get("cmd") not in ("ready",)]
            if done:
                r = done[0]
                mark = "ok" if r["ok"] else "FAIL"
                print(f"[{mark}] {c['cmd']}: {r.get('msg', '')}"[:2000])
                ok = ok and r["ok"]
                if c["cmd"] == "dump" and r["ok"] and c.get("print", True):
                    print(open(r["msg"]).read())
                break
            time.sleep(0.1)
        else:
            print(f"[TIMEOUT] {c['cmd']}")
            return False
    return ok


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    if args[0] == "--scenario":
        cmds = [json.loads(l) for l in open(args[1]) if l.strip() and not l.lstrip().startswith("//")]
    else:
        cmds = [json.loads(a) for a in args]
    os.makedirs(os.path.join(DIR, "out"), exist_ok=True)
    sys.exit(0 if run(cmds) else 1)


if __name__ == "__main__":
    main()
