"""Stand-in for the Minecraft mod, for testing the Subnautica plugin without Minecraft.

Maps the link file the host created, heartbeats, acknowledges the host's teleports, and plays a
very small Minecraft: while the host forwards W (GLFW 87) the "player" walks forward along the
host's look at 4.3 blocks/s; Space (32) rises at 2 blocks/s. Publishes a test-pattern overlay
(a hotbar-like strip and a crosshair) so the host's HUD path can be checked in screenshots.

    python3 tools/fake_minecraft.py [seconds]
"""

import math
import mmap
import os
import struct
import sys
import time

from fake_host import (OFF_MC, OFF_HOST, OFF_IN, OFF_OVL, OFF_OVL_HDR, OFF_PIX, OFF_EVENTS, SLOT, SIZE, MAGIC, VERSION,
                       INPUT_ENTRIES, OVERLAY_DIRTY, OVERLAY_FRONT_SHIFT, mono_ns, link_path)

GLFW_W, GLFW_SPACE = 87, 32


def overlay_pattern(w, h):
    px = bytearray(w * h * 4)
    # Bottom-up rows: row 0 is the bottom of the screen.
    bar_w, bar_h = w // 3, max(8, h // 20)
    x0 = (w - bar_w) // 2
    for y in range(h // 40, h // 40 + bar_h):
        for x in range(x0, x0 + bar_w):
            o = (y * w + x) * 4
            edge = (x - x0) % (bar_w // 9) < 2
            px[o:o + 4] = bytes((200, 200, 200, 255) if edge else (60, 60, 60, 180))
    cx, cy = w // 2, h // 2
    for d in range(-8, 9):
        for (x, y) in ((cx + d, cy), (cx, cy + d)):
            o = (y * w + x) * 4
            px[o:o + 4] = bytes((255, 255, 255, 255))
    return bytes(px)


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 60.0
    path = link_path()
    while not os.path.exists(path) or os.path.getsize(path) < SIZE:
        print(f"waiting for the host to create {path}")
        time.sleep(1)
    f = open(path, "r+b")
    m = mmap.mmap(f.fileno(), SIZE)
    magic, version, host_pid = struct.unpack_from("<III", m, 0)
    if magic != MAGIC or version != VERSION:
        sys.exit(f"protocol mismatch: magic {magic:08x} version {version}")
    struct.pack_into("<I", m, 0xC, os.getpid())
    print(f"fake minecraft: pid {os.getpid()}, host pid {host_pid}")

    pos = [0.0, 0.0, 0.0]
    ack = 0
    seq = 0
    frame = 0
    in_tail = struct.unpack_from("<Q", m, OFF_IN + 0x40)[0]
    held = set()
    # v26: the free slot is neither the middle nor the host's front (bits 4-5 of the state word).
    ovl = struct.unpack_from("<I", m, OFF_OVL)[0]
    back = 3 - (ovl & 3) - ((ovl >> OVERLAY_FRONT_SHIFT) & 3)
    ovl_w = ovl_h = 0
    pattern = b""
    start = last = time.time()
    last_print = 0.0
    keys_seen = 0
    while time.time() - start < seconds:
        now = time.time()
        dt = now - last
        last = now
        struct.pack_into("<Q", m, 0x18, mono_ns())
        # Host state (seqlock).
        hs = None
        for _ in range(50):
            s1 = struct.unpack_from("<I", m, OFF_HOST)[0]
            if s1 & 1:
                continue
            hs = struct.unpack_from("<IIIdddffIIIf", m, OFF_HOST + 4)
            if struct.unpack_from("<I", m, OFF_HOST)[0] == s1:
                break
            hs = None
        # Input ring.
        head = struct.unpack_from("<Q", m, OFF_IN)[0]
        while in_tail < head:
            typ, code, a, b, c = struct.unpack_from("<HHiii", m, OFF_IN + 0x80 + (in_tail % INPUT_ENTRIES) * 16)
            in_tail += 1
            keys_seen += 1
            if typ == 1:
                (held.add if a else held.discard)(code)
            elif typ == 6:
                held.clear()
        struct.pack_into("<Q", m, OFF_IN + 0x40, in_tail)
        if hs:
            flags, _world, _epoch, hx, hy, hz, yaw, pitch, tseq, vw, vh, _day = hs
            if tseq != ack and flags & 1 and not flags & 4:
                pos = [hx, hy, hz]
                ack = tseq
                print(f"  teleport #{tseq} to {hx:.2f} {hy:.2f} {hz:.2f}")
            if GLFW_W in held:
                pos[0] += -math.sin(math.radians(yaw)) * 4.3 * dt
                pos[2] += math.cos(math.radians(yaw)) * 4.3 * dt
            if GLFW_SPACE in held:
                pos[1] += 2.0 * dt
            if vw and vh and (vw, vh) != (ovl_w, ovl_h):
                ovl_w, ovl_h = min(vw, 3840), min(vh, 2160)
                pattern = overlay_pattern(ovl_w, ovl_h)
            # McState (seqlock): flags inWorld, feet, look, eye height, ack, frame.
            seq += 1
            frame += 1
            struct.pack_into("<I", m, OFF_MC, seq * 2 - 1)
            struct.pack_into("<IdddffffII", m, OFF_MC + 4, 1 | 4, pos[0], pos[1], pos[2], yaw, pitch, 1.62, 0.5, ack, 2)
            struct.pack_into("<Q", m, OFF_MC + 0x38, frame)
            struct.pack_into("<ffIfII", m, OFF_MC + 0xC8, 20.0, 20.0, 20, 5.0, 300, 300)
            struct.pack_into("<I", m, OFF_MC, seq * 2)
            # Overlay: write the back slot, swap it in.
            if pattern and frame % 10 == 0:
                m[OFF_PIX + back * SLOT:OFF_PIX + back * SLOT + len(pattern)] = pattern
                hdr = OFF_OVL_HDR + back * 0x40
                struct.pack_into("<IIIIQ", m, hdr, ovl_w, ovl_h, 1, 0, frame)
                old = struct.unpack_from("<I", m, OFF_OVL)[0]  # not atomic: see fake_host.poll_overlay
                struct.pack_into("<I", m, OFF_OVL, back | OVERLAY_DIRTY | (old & (3 << OVERLAY_FRONT_SHIFT)))
                back = old & 3
            if now - last_print > 2:
                last_print = now
                print(f"  pos ({pos[0]:.2f}, {pos[1]:.2f}, {pos[2]:.2f}) look ({yaw:.1f}, {pitch:.1f}) held {sorted(held)} "
                      f"host flags {flags:03b} teleport {tseq}/{ack} input events {keys_seen}")
        time.sleep(1 / 60)


if __name__ == "__main__":
    main()
