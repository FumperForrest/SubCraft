"""Both-games smoke test: Minecraft (tools/mc_dev.*) and Subnautica in the dev game must be running.

    python tools/smoke.py

Checks the link, the puppet, Minecraft's camera (first person, F5 behind and in front), the
sound bridge, combat both ways, the Seamoth (board, eject) and the lifepod, through the dev
harness (sn_cmd.py) and Minecraft's command box (mc_cmd.py). Prints [ok]/[FAIL] lines like
fake_host.py; exit code 1 if anything failed. Moves the player and spawns a Seamoth and a few
fish in the dev game (never saves).
"""

import contextlib
import json
import os
import subprocess
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, TOOLS)
import sn_cmd  # noqa: E402

OUT = os.path.join(sn_cmd.DIR, "out")
results = []


def check(name, ok, detail=""):
    results.append(ok)
    detail = detail.splitlines()[0] if detail else ""
    print(f"  [{'ok' if ok else 'FAIL'}] {name}{(': ' + detail) if detail else ''}")


def sn(*cmds):
    """Runs harness commands quietly; returns the result messages by command name (last wins)."""
    start = len(sn_cmd.result_lines())
    with open(os.devnull, "w") as quiet, contextlib.redirect_stdout(quiet):
        sn_cmd.run([dict(c) for c in cmds])
    msgs = {}
    for line in sn_cmd.result_lines()[start:]:
        r = json.loads(line)
        msgs[r["cmd"]] = r.get("msg", "")
    return msgs


def dump(name):
    sn({"cmd": "dump", "name": name, "print": False})
    with open(os.path.join(OUT, name + ".json")) as f:
        return json.load(f)


def mc(command):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    p = subprocess.run([sys.executable, os.path.join(TOOLS, "mc_cmd.py"), command], capture_output=True, text=True, env=env)
    return p.stdout.strip().splitlines()[-1] if p.stdout.strip() else ""


def main():
    print("smoke test (both games)")
    d = dump("smoke_start")
    check("linked and Minecraft drives the player", d["link"]["mcLinked"] and d["link"]["puppet"])

    # Camera: first person, F5 behind, F5 in front, back to first person (GLFW F5 = 294).
    sn({"cmd": "teleport", "x": -110, "y": -6, "z": -90}, {"cmd": "wait", "seconds": 4})
    fp = dump("smoke_fp")
    cams = []
    for _ in range(3):
        sn({"cmd": "key", "glfw": 294, "down": True}, {"cmd": "key", "glfw": 294, "down": False}, {"cmd": "wait", "seconds": 1.5})
        cams.append(dump("smoke_cam"))
    eye = fp["camera"]["pos"]
    def off(c):
        return sum((a - b) ** 2 for a, b in zip(c["camera"]["pos"], eye)) ** 0.5
    check("F5 behind: camera pulled back", off(cams[0]) > 1.0, f"{off(cams[0]):.1f} m")
    check("F5 in front: camera faces the player", cams[1]["camera"]["forward"][2] * fp["camera"]["forward"][2] < 0 or off(cams[1]) > 1.0)
    check("F5 back to first person: hand drawn", cams[2]["link"]["mcWorld"]["handVertices"] > 0)
    mc_fov = fp["link"]["mc"].get("fov", 0)
    check("FOV is Minecraft's", abs(fp["camera"]["fov"] - mc_fov) < 1.0, f"camera {fp['camera']['fov']:.1f}, Minecraft {mc_fov:.1f}")

    # Sound: a zombie sound and a menu click go through Subnautica's FMOD.
    mc("/playsound minecraft:entity.zombie.ambient hostile @p ~ ~ ~3 1")
    mc("/playsound minecraft:ui.button.click master @p")
    msg = sn({"cmd": "wait", "seconds": 2}, {"cmd": "fmod"}).get("fmod", "")
    stats = msg.splitlines()[-1] if msg else ""
    played = int(stats.split("played ")[1].split()[0]) if "played " in stats else 0
    failed = int(stats.split("failed ")[1].split()[0]) if "failed " in stats else -1
    check("sounds played through FMOD", played >= 2 and failed == 0, stats)

    # Combat: fish nearby, hit one from Minecraft, get hurt by Subnautica.
    sn({"cmd": "console", "text": "spawn peeper 3 3"}, {"cmd": "wait", "seconds": 3})
    before = sn({"cmd": "creatures"}).get("creatures", "")
    check("creatures in the table", before.split()[0].isdigit() and int(before.split()[0]) > 0, before.splitlines()[0] if before else "")
    mc("/damage @e[type=subcraft:creature_proxy,limit=1] 2 minecraft:player_attack by @p")
    after = sn({"cmd": "wait", "seconds": 1}, {"cmd": "creatures"}).get("creatures", "")
    hits = lambda m: int(m.split("hits ")[1].split(",")[0]) if "hits " in m else 0
    check("Minecraft hit reached a creature", hits(after) > hits(before), after.splitlines()[0] if after else "")
    mc("/gamemode survival @p")
    h0 = dump("smoke_h0")["link"]["mc"]["health"]
    sn({"cmd": "hurtplayer", "damage": 10}, {"cmd": "wait", "seconds": 1})
    h1 = dump("smoke_h1")["link"]["mc"]["health"]
    sn({"cmd": "heal", "amount": 50})
    mc("/gamemode creative @p")
    check("Subnautica damage hurts Minecraft's player", h1 < h0, f"{h0:.1f} -> {h1:.1f}")

    # Seamoth: board (camera stays on a kinematic player), eject (Minecraft drives again).
    sn({"cmd": "console", "text": "spawn seamoth"}, {"cmd": "wait", "seconds": 4}, {"cmd": "pilot"}, {"cmd": "wait", "seconds": 3})
    p = dump("smoke_pilot")["player"]
    check("piloting: player kinematic", p["mode"] != "Normal" and p["kinematic"], f"{p['mode']} kinematic {p['kinematic']}")
    for _ in range(5):
        if sn({"cmd": "eject"}, {"cmd": "wait", "seconds": 0.5}).get("eject") == "Normal":
            break
    sn({"cmd": "wait", "seconds": 1})
    e = dump("smoke_eject")
    check("eject: Minecraft drives again", e["player"]["mode"] == "Normal" and e["link"]["puppet"])

    # Lifepod: inside, dry, steady.
    sn({"cmd": "intopod"}, {"cmd": "wait", "seconds": 4})
    a = dump("smoke_pod_a")
    sn({"cmd": "wait", "seconds": 3})
    b = dump("smoke_pod_b")
    drift = sum((x - y) ** 2 for x, y in zip(a["player"]["pos"], b["player"]["pos"])) ** 0.5
    check("lifepod: inside, dry, steady", a["player"]["inside"] and not a["player"]["underwater"] and drift < 0.2, f"drift {drift:.2f} m")

    print(f"summary: {results.count(True)}/{len(results)} ok")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
