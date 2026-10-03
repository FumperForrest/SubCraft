"""Run one command in the running Minecraft through the link's command box (protocol v12+), without
touching anything else in the link (safe while Subnautica or fake_host owns it).

    python3 tools/mc_cmd.py "subcraft tris"
    python3 tools/mc_cmd.py "/time set noon"
    python3 tools/mc_cmd.py --force "..."   # even if an earlier command never finished

The box holds one command at a time: while an earlier one is still pending (Minecraft busy, or a
timed-out mc_cmd), a new one is refused instead of overwriting its text and reading its reply.
"""
import mmap
import os
import struct
import sys
import time

from fake_host import MAGIC, OFF_CMD, SIZE, VERSION, link_path


def main():
    args = sys.argv[1:]
    force = "--force" in args
    text = " ".join(a for a in args if a != "--force")
    path = link_path()
    if not os.path.exists(path) or os.path.getsize(path) < SIZE:
        sys.exit(f"no link file of the expected size at {path} (is Subnautica or fake_host running?)")
    f = open(path, "r+b")
    m = mmap.mmap(f.fileno(), SIZE)
    magic, version = struct.unpack_from("<II", m, 0)
    if magic != MAGIC or version != VERSION:
        sys.exit(f"protocol mismatch in {path}: magic {magic:08x} version {version}, this tool speaks {VERSION}")
    seq, ack = struct.unpack_from("<II", m, OFF_CMD)
    if seq != ack and not force:
        sys.exit(f"command {seq} is still pending (Minecraft has answered up to {ack}); retry, or --force")
    want = (ack + 1) & 0xFFFFFFFF
    data = text.encode("utf-8")[:1008]
    m[OFF_CMD + 0x10:OFF_CMD + 0x10 + len(data)] = data
    struct.pack_into("<I", m, OFF_CMD + 0x0C, len(data))
    struct.pack_into("<I", m, OFF_CMD, want)
    deadline = time.time() + 30
    while time.time() < deadline:
        if struct.unpack_from("<I", m, OFF_CMD + 4)[0] == want:
            status = struct.unpack_from("<i", m, OFF_CMD + 8)[0]
            reply = bytes(m[OFF_CMD + 0x400:OFF_CMD + 0x800]).split(b"\0", 1)[0].decode("utf-8", "replace")
            print(f"status {status}\n{reply}")
            sys.exit(0 if status == 0 else 1)
        time.sleep(0.05)
    sys.exit("timed out")


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    main()
