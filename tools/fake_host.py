"""Stand-in for the Subnautica plugin, for testing the Minecraft mod without Subnautica.

Ported from SkyCraft's tools/fake_skyrim.py (MIT). Creates the shared link file, streams a flat
floor plus a step of collision, holds W and then Space, prints what Minecraft reports, checks the
player actually moved, and saves the newest overlay frame to a PNG.

    python3 tools/fake_host.py [seconds] [out.png] [--scene out.scdump]

With --scene, after the checks it builds the Phase 0c test scene with Minecraft commands (through
the command box) and asks Minecraft to dump it in the capture-dump format.

Python 3.9+, standard library only. Exit code 0 when every check passed.
"""

import mmap
import os
import struct
import sys
import time
import zlib

# ---- protocol (protocol/subcraft_protocol.h); tools/tests/test_layout.py checks these against layout.json ----
MAGIC = 0x43425553
VERSION = 26
OFF_HOST = 0x100
OFF_MC = 0x200
OFF_OVL = 0x300
OFF_OVL_HDR = 0x340
OFF_CMD = 0x400
OFF_IN = 0x1000
OFF_CREATURES = 0x12000
OFF_EVENTS = 0x17000
OFF_COL = 0x20000
COL_BYTES = 32 << 20
OFF_PIX = OFF_COL + COL_BYTES
SLOT = 3840 * 2160 * 4
OFF_RENDER = OFF_PIX + SLOT * 3
RENDER_BYTES = 64 << 20
SIZE = OFF_RENDER + RENDER_BYTES
COL_DATA = COL_BYTES - 0x80
RENDER_DATA = RENDER_BYTES - 0x80
INPUT_ENTRIES = 4096
EVENT_ENTRIES = 512
OVERLAY_DIRTY, OVERLAY_FRONT_SHIFT = 4, 4

HOST_IN_GAME = 1
IN_KEY, IN_MOUSE_BUTTON, IN_RELEASE_ALL = 1, 2, 6
GLFW_KEY_W, GLFW_KEY_SPACE = 87, 32
COL_CLEAR, COL_REGION = 1, 2
MC_FLAG_NAMES = ["inWorld", "screen", "onGround", "sneak", "sprint", "dead", "swim", "fly", "inWater", "eyeInWater",
                 "lookCaptured"]

W, H = 960, 540
FLOOR_Y = 64   # the player stands on y = 64 (floor blocks at y = 63), above the sea (y 0)
SPAWN = (0.5, FLOOR_Y + 0.0, 0.5)


def mono_ns():
    """The cross-process clock: CLOCK_UPTIME_RAW on macOS (== HotSpot's nanoTime there)."""
    if sys.platform == "darwin":
        return time.clock_gettime_ns(time.CLOCK_UPTIME_RAW)
    return time.perf_counter_ns()  # Windows: QueryPerformanceCounter in ns


def link_path():
    override = os.environ.get("SUBCRAFT_LINK")
    if override:
        return override
    if os.name == "nt":
        return os.path.join(os.environ["LOCALAPPDATA"], "SubCraft", "link.bin")
    return os.path.join(os.environ.get("TMPDIR", "/tmp"), "subcraft", "link.bin")


class Link:
    def __init__(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.f = open(path, "a+b")
        self.f.truncate(SIZE)  # sparse: only touched pages take disk/memory
        self.m = mmap.mmap(self.f.fileno(), SIZE)
        # Zero every control block; leave pixel/ring payloads alone.
        for off, n in ((0, 0x1000), (OFF_IN, 0x80), (OFF_CREATURES, 0x40), (OFF_EVENTS, 0x80), (OFF_COL, 0x80), (OFF_RENDER, 0x80)):
            self.m[off:off + n] = bytes(n)
        self.front = 2
        struct.pack_into("<I", self.m, OFF_OVL, self.front << OVERLAY_FRONT_SHIFT)  # middle 0, clean (v26)
        struct.pack_into("<IIII", self.m, 0, MAGIC, VERSION, os.getpid(), 0)
        self.host_seq = 0
        self.col_head = 0
        self.in_head = 0
        self.overlay = None
        self.events = []
        self.render_msgs = {}

    # -- host -> MC --
    def heartbeat(self):
        struct.pack_into("<Q", self.m, 0x10, mono_ns())

    def write_host_state(self, flags, pos, yaw, pitch, teleport_seq, day=0.5):
        self.host_seq += 1
        struct.pack_into("<I", self.m, OFF_HOST, self.host_seq * 2 - 1)  # odd: writing
        struct.pack_into("<IIIdddffIIIfff", self.m, OFF_HOST + 4, flags, 1, 1, pos[0], pos[1], pos[2], yaw, pitch, teleport_seq, W, H, day, 45.0, 45.0)
        struct.pack_into("<I", self.m, OFF_HOST, self.host_seq * 2)

    def send_input(self, typ, code, a=0, b=0, c=0):
        e = OFF_IN + 0x80 + (self.in_head % INPUT_ENTRIES) * 16
        struct.pack_into("<HHiii", self.m, e, typ, code, a, b, c)
        self.in_head += 1
        struct.pack_into("<Q", self.m, OFF_IN, self.in_head)

    def send_collision(self, typ, payload):
        msg = (8 + len(payload) + 7) & ~7
        tail = struct.unpack_from("<Q", self.m, OFF_COL + 0x40)[0]
        pos = self.col_head % COL_DATA
        pad = COL_DATA - pos if pos + msg > COL_DATA else 0
        if COL_DATA - (self.col_head - tail) < msg + pad:
            raise RuntimeError("collision ring full")
        if pad:
            struct.pack_into("<II", self.m, OFF_COL + 0x80 + pos, 0, 0)
            self.col_head += pad
            pos = 0
        at = OFF_COL + 0x80 + pos
        struct.pack_into("<II", self.m, at, typ, len(payload))
        self.m[at + 8:at + 8 + len(payload)] = payload
        self.col_head += msg
        struct.pack_into("<Q", self.m, OFF_COL, self.col_head)

    def send_region(self, box, solid, epoch=1):
        """box = (minX, minY, minZ, maxX, maxY, maxZ); solid = iterable of full blocks (x, y, z)."""
        blocks = [struct.pack("<iiiBBH8Q", x, y, z, 1, 0, 0, *([0xFFFFFFFFFFFFFFFF] * 8)) for (x, y, z) in solid]
        self.send_collision(COL_REGION, struct.pack("<6iII", *box, epoch, len(blocks)) + b"".join(blocks))

    def command(self, text, timeout=30.0):
        """Runs one command in Minecraft through the command box; returns (status, reply)."""
        seq, ack = struct.unpack_from("<II", self.m, OFF_CMD)
        data = text.encode("utf-8")[:1008]
        self.m[OFF_CMD + 0x10:OFF_CMD + 0x10 + len(data)] = data
        struct.pack_into("<I", self.m, OFF_CMD + 0x0C, len(data))
        struct.pack_into("<I", self.m, OFF_CMD, ack + 1)
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.heartbeat()
            self.host_state()
            if struct.unpack_from("<I", self.m, OFF_CMD + 4)[0] == ack + 1:
                status = struct.unpack_from("<i", self.m, OFF_CMD + 8)[0]
                reply = bytes(self.m[OFF_CMD + 0x400:OFF_CMD + 0x800]).split(b"\0", 1)[0].decode("utf-8", "replace")
                return status, reply
            time.sleep(1 / 60)
        return -1, "timed out"

    def host_state(self):
        self.write_host_state(HOST_IN_GAME, SPAWN, 0.0, 10.0, 1)

    # -- MC -> host --
    def mc_heartbeat(self):
        return struct.unpack_from("<Q", self.m, 0x18)[0]

    def mc_pid(self):
        return struct.unpack_from("<I", self.m, 0xC)[0]

    def read_mc_state(self):
        for _ in range(100):
            s1 = struct.unpack_from("<I", self.m, OFF_MC)[0]
            if s1 & 1:
                continue
            flags, x, y, z, yaw, pitch, eye, _sens, ack = struct.unpack_from("<Idddffff I".replace(" ", ""), self.m, OFF_MC + 4)
            frame = struct.unpack_from("<Q", self.m, OFF_MC + 0x38)[0]
            health, max_health, food, _sat, air, max_air = struct.unpack_from("<ffIfII", self.m, OFF_MC + 0xC8)
            if struct.unpack_from("<I", self.m, OFF_MC)[0] == s1:
                return dict(flags=flags, x=x, y=y, z=z, yaw=yaw, pitch=pitch, eye=eye, ack=ack, frame=frame,
                            health=health, max_health=max_health, food=food, air=air, max_air=max_air)
        return None

    def poll_overlay(self):
        state = struct.unpack_from("<I", self.m, OFF_OVL)[0]
        if not state & OVERLAY_DIRTY:
            return
        # The C# host does this as one compare-and-swap (v26: our front goes into bits 4-5). Python
        # can't CAS on a mapping: if Minecraft publishes between this read and write, its frame is
        # lost and the slots can alias until the next restart. Acceptable for a test tool only.
        taken = state & 3
        struct.pack_into("<I", self.m, OFF_OVL, self.front | (taken << OVERLAY_FRONT_SHIFT))
        self.front = taken
        hdr = OFF_OVL_HDR + self.front * 0x40
        w, h, flags = struct.unpack_from("<III", self.m, hdr)
        frame_id = struct.unpack_from("<Q", self.m, hdr + 0x10)[0]
        start = OFF_PIX + self.front * SLOT
        self.overlay = (w, h, flags, frame_id, bytes(self.m[start:start + w * h * 4]))

    def drain_events(self):
        head, tail = struct.unpack_from("<Q", self.m, OFF_EVENTS)[0], struct.unpack_from("<Q", self.m, OFF_EVENTS + 0x40)[0]
        while tail < head:
            ev = struct.unpack_from("<IIffffII", self.m, OFF_EVENTS + 0x80 + (tail % EVENT_ENTRIES) * 32)
            if ev[0] in (5, 6, 7):  # kEvSoundPlay/Update/Stop: the fake host has no audio
                self.sound_events = getattr(self, "sound_events", 0) + 1
            else:
                print(f"  event from Minecraft: {ev}")
            self.events.append(ev)
            tail += 1
        struct.pack_into("<Q", self.m, OFF_EVENTS + 0x40, tail)

    def drain_render(self):
        head, tail = struct.unpack_from("<Q", self.m, OFF_RENDER)[0], struct.unpack_from("<Q", self.m, OFF_RENDER + 0x40)[0]
        while tail < head:
            pos = tail % RENDER_DATA
            typ, n = struct.unpack_from("<II", self.m, OFF_RENDER + 0x80 + pos)
            if typ == 0:
                tail += RENDER_DATA - pos
                continue
            self.render_msgs[typ] = self.render_msgs.get(typ, 0) + 1
            tail += (8 + n + 7) & ~7
        struct.pack_into("<Q", self.m, OFF_RENDER + 0x40, tail)


def write_png(path, w, h, rgba, bottom_up):
    rows = [rgba[r * w * 4:(r + 1) * w * 4] for r in range(h)]
    if bottom_up:
        rows.reverse()
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def flag_names(flags):
    return ",".join(n for i, n in enumerate(MC_FLAG_NAMES) if flags & (1 << i)) or "-"


def rss_mb(pid):
    try:
        if os.name == "nt":
            out = os.popen(f'tasklist /FI "PID eq {pid}" /FO CSV /NH').read().strip().split('","')
            return int(out[-1].strip('"').replace(" K", "").replace(",", "").replace(".", "")) // 1024 if len(out) > 4 else None
        out = os.popen(f"ps -o rss= -p {pid}").read().strip()
        return int(out) // 1024 if out else None
    except (OSError, ValueError):
        return None


# Phase 0c test scene, on the fake floor (ghost terrain at y 63): a small cobblestone hut with
# glass, leaves, a grass block, torches, glowstone and a chest.
SCENE = [
    "/fill 1 64 2 7 70 9 minecraft:air",
    "/fill 2 64 3 6 64 7 minecraft:cobblestone",
    "/fill 2 65 3 2 67 7 minecraft:cobblestone",
    "/fill 3 65 3 6 66 3 minecraft:cobblestone",
    "/setblock 3 65 7 minecraft:glass",
    "/setblock 4 65 7 minecraft:glass",
    "/fill 5 65 7 6 66 7 minecraft:oak_leaves[persistent=true]",
    "/setblock 6 65 5 minecraft:grass_block",
    "/setblock 4 65 5 minecraft:chest[facing=east]",
    "/setblock 6 67 3 minecraft:glowstone",
    "/setblock 3 66 5 minecraft:wall_torch[facing=east]",
    "/setblock 5 65 4 minecraft:torch",
]


def main():
    args = [a for a in sys.argv[1:]]
    scene_out = None
    if "--scene" in args:
        i = args.index("--scene")
        scene_out = os.path.abspath(args[i + 1])
        del args[i:i + 2]
    seconds = float(args[0]) if len(args) > 0 else 60.0
    out_png = args[1] if len(args) > 1 else os.path.join("tools", "out", "overlay.png")
    path = link_path()
    link = Link(path)
    print(f"fake host: pid {os.getpid()}, link {path} ({SIZE >> 20} MiB, protocol v{VERSION})")

    # The floor: a 32x32 slab at y 63 with a one-block step at z 6..8 (walk into it, then jump it).
    floor = [(x, FLOOR_Y - 1, z) for x in range(-16, 16) for z in range(-16, 16)]
    step = [(x, FLOOR_Y, z) for x in range(-16, 16) for z in range(6, 9)]
    link.send_collision(COL_CLEAR, struct.pack("<I", 1))
    link.send_region((-16, FLOOR_Y - 2, -16, 15, FLOOR_Y + 3, 15), floor + step)
    print(f"  sent collision: {len(floor)} floor + {len(step)} step blocks")

    teleport_seq = 1
    start = time.time()
    linked_at = released_at = None
    phase = "wait"
    last_print = 0.0
    start_pos = None
    max_y = -1e9
    max_z = -1e9
    clock_deltas = []
    peak_rss = 0
    frame_dt = 1.0 / 60.0
    try:
        while time.time() - start < seconds:
            t = time.time() - start
            link.heartbeat()
            link.write_host_state(HOST_IN_GAME, SPAWN, 0.0, 10.0, teleport_seq)
            link.poll_overlay()
            link.drain_events()
            link.drain_render()
            st = link.read_mc_state()
            mc_beat = link.mc_heartbeat()
            # Only a live Minecraft counts: it has put its pid in the header and beats within 2 s.
            if mc_beat and link.mc_pid() and abs(mono_ns() - mc_beat) < 2_000_000_000:
                clock_deltas.append(mono_ns() - mc_beat)
                if linked_at is None:
                    linked_at = t
                    print(f"  [{t:5.1f}s] Minecraft linked (pid {link.mc_pid()})")
            if linked_at is not None and st and st["flags"] & 1 and st["ack"] == teleport_seq and released_at is None:
                released_at = t
                start_pos = (st["x"], st["y"], st["z"])
                print(f"  [{t:5.1f}s] player released at {start_pos[0]:.2f} {start_pos[1]:.2f} {start_pos[2]:.2f}")
            if released_at is not None:
                since = t - released_at
                if phase == "wait" and since > 1.0:
                    phase = "walk"
                    link.send_input(IN_KEY, GLFW_KEY_W, 1)
                    print(f"  [{t:5.1f}s] holding W")
                elif phase == "walk" and since > 3.0:
                    phase = "jump"
                    link.send_input(IN_KEY, GLFW_KEY_SPACE, 1)
                    print(f"  [{t:5.1f}s] holding W + Space")
                elif phase == "jump" and since > 5.0:
                    phase = "done"
                    link.send_input(IN_KEY, GLFW_KEY_W, 0)
                    link.send_input(IN_KEY, GLFW_KEY_SPACE, 0)
                    print(f"  [{t:5.1f}s] released keys")
                if st:
                    max_y = max(max_y, st["y"])
                    max_z = max(max_z, st["z"])
            if st and t - last_print > 2.0:
                last_print = t
                pid = link.mc_pid()
                rss = rss_mb(pid) if pid else None
                peak_rss = max(peak_rss, rss or 0)
                print(f"  [{t:5.1f}s] mc frame {st['frame']} pos ({st['x']:.2f}, {st['y']:.2f}, {st['z']:.2f}) "
                      f"look ({st['yaw']:.1f}, {st['pitch']:.1f}) [{flag_names(st['flags'])}] "
                      f"hp {st['health']:.0f}/{st['max_health']:.0f} food {st['food']} air {st['air']}/{st['max_air']}"
                      + (f" rss {rss} MB" if rss else ""))
            time.sleep(frame_dt)
    except KeyboardInterrupt:
        pass

    print("summary:")
    ok = True

    def check(cond, what):
        nonlocal ok
        ok = ok and cond
        print(f"  [{'ok' if cond else 'FAIL'}] {what}")

    check(linked_at is not None, "Minecraft linked and heartbeats")
    if clock_deltas:
        d = sorted(clock_deltas)
        med = d[len(d) // 2] / 1e6
        check(-100 < med < 2000, f"clocks agree: median (host now - mc heartbeat) = {med:.1f} ms")
    check(released_at is not None, "player teleported onto the floor and released")
    if start_pos:
        check(max_z - start_pos[2] > 2.0, f"W moved the player forward (+z {max_z - start_pos[2]:.2f} blocks)")
        check(max_y - FLOOR_Y > 0.9, f"jumped onto the step (max y {max_y:.2f}, floor {FLOOR_Y})")
    if link.overlay:
        w, h, flags, frame_id, px = link.overlay
        os.makedirs(os.path.dirname(out_png) or ".", exist_ok=True)
        write_png(out_png, w, h, px, flags & 1)
        samples = len(px) // (4 * 97)
        opaque = sum(1 for i in range(3, len(px), 4 * 97) if px[i] > 0)
        check(0 < opaque < samples // 2, f"overlay {w}x{h} frame {frame_id}: GUI on a transparent background "
              f"({opaque} of {samples} sampled pixels visible) -> {out_png}")
    else:
        check(False, "received an overlay frame")
    if scene_out and linked_at is not None:
        for c in SCENE:
            st, reply = link.command(c)
            if st != 0:
                print(f"  command {c!r}: status {st} {reply}")
        st, reply = link.command(f"subcraft dump {scene_out} 6 4 66 5", timeout=60)
        check(st == 0, f"scene dump: {reply}")
    if peak_rss:
        print(f"  Minecraft peak RSS seen: {peak_rss} MB")
    if link.render_msgs:
        print(f"  render messages: {link.render_msgs}")
        print(f"  sound events: {getattr(link, 'sound_events', 0)}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
