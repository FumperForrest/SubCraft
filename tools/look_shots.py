"""Phase 0c look gate: screenshot the captured Minecraft scene (placed by
tools/scenarios/look_place.jsonl) at noon, dusk, night with the flashlight, near, mid, far and from
above the surface, with native Subnautica objects in the same frames.

    python3 tools/look_shots.py [out_dir]     (Subnautica running, in game, scene placed)
"""

import math
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sn_cmd  # noqa: E402

CENTER = (28.5, -25.6, 175.5)  # the scene placed by look_place.jsonl (Unity coords)


def aim(eye):
    dx, dy, dz = (CENTER[i] - eye[i] for i in range(3))
    yaw = math.degrees(math.atan2(dx, dz)) % 360
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    return yaw, pitch


SHOTS = [
    # name, time of day, eye (Unity), flashlight
    ("near-noon", 0.5, (33.0, -23.0, 182.0), False),
    ("mid-noon", 0.5, (41.0, -21.0, 190.0), False),
    ("near-dusk", 0.85, (33.0, -23.0, 182.0), False),
    ("mid-dusk", 0.85, (41.0, -21.0, 190.0), False),
    ("near-night-flashlight", 0.0, (33.0, -23.0, 182.0), True),
    ("mid-night-flashlight", 0.0, (38.0, -22.0, 186.0), True),
    ("far-noon-fog", 0.5, (55.0, -18.0, 212.0), False),
    ("above-surface-noon", 0.5, (36.0, 2.0, 186.0), False),
]


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join("docs", "look")
    os.makedirs(out, exist_ok=True)
    src = os.path.join(sn_cmd.DIR, "out")
    flashlight = None
    only = sys.argv[2:]  # optional shot names
    for name, t, eye, light in SHOTS:
        if only and name not in only:
            continue
        cmds = [{"cmd": "time", "value": t}]
        if light != flashlight:
            cmds.append({"cmd": "equip", "tech": "Flashlight", "lights": True} if light else {"cmd": "holster"})
            flashlight = light
        yaw, pitch = aim(eye)
        cmds += [{"cmd": "teleport", "x": eye[0], "y": eye[1], "z": eye[2]},
                 {"cmd": "look", "yaw": yaw, "pitch": pitch},
                 {"cmd": "wait", "seconds": 1.5},
                 {"cmd": "look", "yaw": yaw, "pitch": pitch},
                 {"cmd": "screenshot", "name": "look-" + name}]
        ok = sn_cmd.run(cmds)
        shutil.copy(os.path.join(src, f"look-{name}.png"), os.path.join(out, f"0c-{name}.png"))
        print(f"{'ok' if ok else 'FAILED'}: {name}")


if __name__ == "__main__":
    main()
