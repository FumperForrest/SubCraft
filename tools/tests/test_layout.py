"""The Python tools' hand-written protocol values against protocol/layout.json (which
tools/check_layout.sh keeps equal to the C++ header). Run: python3 -m unittest discover -s tools/tests -t tools"""

import json
import os
import struct
import sys
import unittest

TOOLS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, TOOLS)

import capture_dump  # noqa: E402
import fake_host  # noqa: E402

with open(os.path.join(os.path.dirname(TOOLS), "protocol", "layout.json"), encoding="utf-8") as f:
    LAYOUT = json.load(f)
C = LAYOUT["constants"]
E = LAYOUT["enums"]


def size(name):
    return LAYOUT["structs"][name]["size"]


def field(struct_name, name):
    return LAYOUT["structs"][struct_name]["fields"][name]


class FakeHostLayout(unittest.TestCase):
    def test_constants(self):
        h = fake_host
        self.assertEqual(h.MAGIC, C["kMagic"])
        self.assertEqual(h.VERSION, C["kVersion"])
        self.assertEqual(h.OFF_HOST, C["kOffHostState"])
        self.assertEqual(h.OFF_MC, C["kOffMcState"])
        self.assertEqual(h.OFF_OVL, C["kOffOverlayCtl"])
        self.assertEqual(h.OFF_OVL_HDR, C["kOffOverlaySlotHdr"])
        self.assertEqual(h.OFF_CMD, C["kOffCommandBox"])
        self.assertEqual(h.OFF_IN, C["kOffInputRing"])
        self.assertEqual(h.OFF_CREATURES, C["kOffCreatureTable"])
        self.assertEqual(h.OFF_EVENTS, C["kOffEventRing"])
        self.assertEqual(h.OFF_COL, C["kOffCollisionRing"])
        self.assertEqual(h.COL_BYTES, C["kCollisionRingBytes"])
        self.assertEqual(h.OFF_PIX, C["kOffOverlayPixels"])
        self.assertEqual(h.SLOT, C["kOverlaySlotBytes"])
        self.assertEqual(h.OFF_RENDER, C["kOffRenderRing"])
        self.assertEqual(h.RENDER_BYTES, C["kRenderRingBytes"])
        self.assertEqual(h.SIZE, C["kMappingBytes"])
        self.assertEqual(h.COL_DATA, C["kColRingDataBytes"])
        self.assertEqual(h.RENDER_DATA, C["kRenRingDataBytes"])
        self.assertEqual(h.INPUT_ENTRIES, C["kInputRingEntries"])
        self.assertEqual(h.EVENT_ENTRIES, C["kEventRingEntries"])

    def test_enums(self):
        h = fake_host
        self.assertEqual(h.HOST_IN_GAME, E["HostFlags"]["kHostInGame"])
        self.assertEqual(h.IN_KEY, E["InputType"]["kInKey"])
        self.assertEqual(h.IN_MOUSE_BUTTON, E["InputType"]["kInMouseButton"])
        self.assertEqual(h.IN_RELEASE_ALL, E["InputType"]["kInReleaseAll"])
        self.assertEqual(h.COL_CLEAR, E["ColType"]["kColClear"])
        self.assertEqual(h.COL_REGION, E["ColType"]["kColRegion"])
        # one name per McFlags bit, in bit order
        bits = sorted(E["McFlags"].values())
        self.assertEqual(bits, [1 << i for i in range(len(bits))])
        self.assertEqual(len(h.MC_FLAG_NAMES), len(bits))

    def test_struct_formats(self):
        # the struct.pack formats fake_host uses, against the C++ sizes and offsets
        self.assertEqual(struct.calcsize("<HHiii"), size("InputEvent"))
        self.assertEqual(struct.calcsize("<IIffffII"), size("McEvent"))
        self.assertEqual(struct.calcsize("<6iII"), size("ColRegion"))
        self.assertEqual(struct.calcsize("<iiiBBH8Q"), size("ColBlock"))
        # HostState from flags (after seq) to oxygenCapacity
        self.assertEqual(4 + struct.calcsize("<IIIdddffIIIfff"), field("HostState", "oxygenCapacity") + 4)
        self.assertEqual(4 + struct.calcsize("<III"), field("HostState", "posX"))
        # McState "<Idddffff I": flags .. sensitivity, then teleportAck
        self.assertEqual(4 + struct.calcsize("<Idddffff"), field("McState", "teleportAck"))
        self.assertEqual(0x38, field("McState", "frameCounter"))
        self.assertEqual(0xC8, field("McState", "health"))
        self.assertEqual(0x0C, field("CommandBox", "textLen"))
        self.assertEqual(0x10, field("CommandBox", "text"))
        self.assertEqual(0x400, field("CommandBox", "reply"))
        self.assertEqual(0x800, size("CommandBox"))
        self.assertEqual(0x10, field("Header", "hostHeartbeatNs"))
        self.assertEqual(0x18, field("Header", "mcHeartbeatNs"))
        self.assertEqual(0x0C, field("Header", "mcPid"))


class CaptureDumpLayout(unittest.TestCase):
    def test_constants(self):
        r = E["RenType"]
        self.assertEqual(capture_dump.DUMP_MAGIC, C["kDumpMagic"])
        self.assertEqual(capture_dump.REN_ATLAS, r["kRenAtlas"])
        self.assertEqual(capture_dump.REN_SECTION, r["kRenSection"])
        self.assertEqual(capture_dump.REN_TEXTURE, r["kRenTexture"])
        self.assertEqual(capture_dump.REN_SCENE, r["kRenScene"])
        self.assertEqual(capture_dump.REN_LIGHTS, r["kRenLights"])
        mats = sorted(E["RenMaterial"].items(), key=lambda kv: kv[1])
        self.assertEqual([k[4:].lower() for k, _ in mats], capture_dump.MAT_NAMES)

    def test_struct_sizes(self):
        self.assertEqual(struct.calcsize("<5fIII"), size("RenVertex"))
        self.assertEqual(struct.calcsize("<dddII"), size("RenScene"))
        self.assertEqual(struct.calcsize("<BBBBI"), size("RenLight"))
        self.assertEqual(16, size("RenSection"))
        self.assertEqual(16, size("RenBatch"))
        self.assertEqual(16, size("RenTexture"))


if __name__ == "__main__":
    unittest.main()
