"""Run one command in the running Minecraft through the link's command box (protocol v12+), without
touching anything else in the link (safe while Subnautica or fake_host owns it).

    python3 tools/mc_cmd.py "subcraft tris"
    python3 tools/mc_cmd.py "/time set noon"
"""
import mmap
import os
import struct
import sys
import time

from fake_host import OFF_CMD, SIZE, link_path


def main():
    text = " ".join(sys.argv[1:])
    f = open(link_path(), "r+b")
    m = mmap.mmap(f.fileno(), SIZE)
    seq, ack = struct.unpack_from("<II", m, OFF_CMD)
    data = text.encode("utf-8")[:1008]
    m[OFF_CMD + 0x10:OFF_CMD + 0x10 + len(data)] = data
    struct.pack_into("<I", m, OFF_CMD + 0x0C, len(data))
    struct.pack_into("<I", m, OFF_CMD, ack + 1)
    deadline = time.time() + 30
    while time.time() < deadline:
        if struct.unpack_from("<I", m, OFF_CMD + 4)[0] == ack + 1:
            status = struct.unpack_from("<i", m, OFF_CMD + 8)[0]
            reply = bytes(m[OFF_CMD + 0x400:OFF_CMD + 0x800]).split(b"\0", 1)[0].decode("utf-8", "replace")
            print(f"status {status}\n{reply}")
            sys.exit(0 if status == 0 else 1)
        time.sleep(0.05)
    sys.exit("timed out")


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    main()
