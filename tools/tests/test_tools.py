"""No-game tests for the Python tools: one link-path rule, the command box client, capture dumps.
Run: python3 -m unittest discover -s tools/tests -t tools"""

import io
import mmap
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

TOOLS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, TOOLS)

import capture_dump  # noqa: E402
import fake_host  # noqa: E402


class LinkPath(unittest.TestCase):
    """The same file as Platform.cs / Platform.java, whatever the environment says."""

    def path(self, env, nt=False):
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(os, "name", "nt" if nt else "posix"):
            return fake_host.link_path()

    def test_overrides(self):
        self.assertEqual(self.path({"SUBCRAFT_LINK": "/x/l.bin", "SUBCRAFT_DIR": "/d"}), "/x/l.bin")
        self.assertEqual(self.path({"SUBCRAFT_DIR": "/d", "TMPDIR": "/t"}), os.path.join("/d", "link.bin"))

    def test_defaults(self):
        self.assertEqual(self.path({"TMPDIR": "/t"}), os.path.join("/t", "subcraft", "link.bin"))
        self.assertEqual(self.path({}), os.path.join("/tmp", "subcraft", "link.bin"))
        self.assertEqual(self.path({"LOCALAPPDATA": "C:/L"}, nt=True), os.path.join("C:/L", "SubCraft", "link.bin"))


class CommandBox(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.link = os.path.join(self.dir.name, "link.bin")
        self.host = fake_host.Link(self.link)  # creates the sparse file, header, control blocks

    def tearDown(self):
        self.host.m.close()
        self.host.f.close()
        self.dir.cleanup()

    def run_mc_cmd(self, *args):
        env = dict(os.environ, SUBCRAFT_LINK=self.link)
        return subprocess.run([sys.executable, os.path.join(TOOLS, "mc_cmd.py"), *args], env=env,
                              capture_output=True, text=True, timeout=60)

    def test_refuses_while_a_command_is_pending(self):
        struct.pack_into("<II", self.host.m, fake_host.OFF_CMD, 5, 4)  # command 5 not answered yet
        r = self.run_mc_cmd("/time set noon")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("still pending", r.stderr)
        seq, ack = struct.unpack_from("<II", self.host.m, fake_host.OFF_CMD)
        self.assertEqual((seq, ack), (5, 4), "the pending command was left alone")

    def test_refuses_another_protocol(self):
        struct.pack_into("<I", self.host.m, 4, fake_host.VERSION + 1)
        r = self.run_mc_cmd("/time set noon")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("protocol mismatch", r.stderr)


def dump_bytes(messages):
    out = struct.pack("<IIQ", capture_dump.DUMP_MAGIC, fake_host.VERSION, len(messages))
    for typ, payload in messages:
        out += struct.pack("<II", typ, len(payload)) + payload + bytes((-(8 + len(payload))) % 8)
    return out


def vertex(x, y, z, u, v, material=0, normal=2):
    return struct.pack("<5fIII", x, y, z, u, v, 0xFFFFFFFF, 0, material | (normal << 4))


class CaptureDump(unittest.TestCase):
    def write(self, data):
        f = tempfile.NamedTemporaryFile(suffix=".scdump", delete=False)
        f.write(data)
        f.close()
        self.addCleanup(os.unlink, f.name)
        return f.name

    def scene(self, material=0, normal=2):
        atlas = struct.pack("<II", 2, 2) + bytes([200, 100, 50, 255] * 4)
        section = struct.pack("<iiiI", 1, 0, -1, 3) + b"".join(
            vertex(*p, material=material, normal=normal) for p in ((0, 1, 0, 0, 0), (1, 1, 0, 1, 0), (0, 1, 1, 0, 1)))
        lights = struct.pack("<iiiI", 1, 0, -1, 1) + struct.pack("<BBBBI", 2, 3, 4, 15, 0x01FFAA00)
        return dump_bytes([(capture_dump.REN_ATLAS, atlas), (capture_dump.REN_SECTION, section), (capture_dump.REN_LIGHTS, lights)])

    def test_decodes_sections_and_lights_in_world_coordinates(self):
        d = capture_dump.Dump(self.write(self.scene()))
        self.assertEqual(d.sections, 1)
        self.assertEqual(len(d.tris), 1)
        tex, mat, vs, nrm = d.tris[0]
        self.assertEqual((tex, mat, nrm), (0, 0, 2))
        self.assertEqual(vs[0][:3], (16.0, 1.0, -16.0))  # section (1, 0, -1) origin + vertex
        self.assertEqual(d.lights, [(18, 3, -12, 15, 0xFFAA00, 1)])

    def test_preview_renders_and_survives_odd_flags(self):
        out = tempfile.mkdtemp()
        self.addCleanup(lambda: [os.unlink(os.path.join(out, f)) for f in os.listdir(out)] and None)
        for material, normal in ((0, 2), (7, 7)):  # 7/7: unknown material and normal (used to raise IndexError)
            d = capture_dump.Dump(self.write(self.scene(material, normal)))
            with mock.patch("sys.stdout", new=io.StringIO()):
                d.summary()
                capture_dump.render(d, os.path.join(out, "p.png"), [8, 6, -8], [16.5, 1, -15.5], w=48, h=36)
            with open(os.path.join(out, "p.png"), "rb") as f:
                self.assertEqual(f.read(8), b"\x89PNG\r\n\x1a\n")

    def test_help_and_bad_files(self):
        r = subprocess.run([sys.executable, os.path.join(TOOLS, "capture_dump.py"), "--help"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0)
        self.assertIn("Inspect a capture dump", r.stdout)
        with self.assertRaises(SystemExit):
            capture_dump.Dump(self.write(b"\0" * 16))


class OverlayProtocolModel(unittest.TestCase):
    """Every interleaving of the v26 overlay exchange (protocol/subcraft_protocol.h, overlay triple
    buffer): a reader taking frames by compare-and-swap, and a writer that publishes by
    compare-and-swap and reconnects (one read of the state word) before every other frame. The
    writer must never publish a slot that is the middle or the reader's front."""

    def test_no_interleaving_aliases(self):
        D, SHIFT = fake_host.OVERLAY_DIRTY, fake_host.OVERLAY_FRONT_SHIFT

        def mid(w):
            return w & 3

        def front(w):
            return (w >> SHIFT) & 3

        def steps(st):
            w, rpc, rseen, wpc, wseen, back, pubs = st
            out = []
            if rpc == 0:  # reader: read the word
                if w & D:
                    out.append((w, 1, w, wpc, wseen, back, pubs))
            elif w == rseen:  # reader: CAS succeeds
                out.append((front(rseen) | (mid(rseen) << SHIFT), 0, None, wpc, wseen, back, pubs))
            else:  # reader: CAS fails, start over
                out.append((w, 0, None, wpc, wseen, back, pubs))
            if pubs < 5:
                if wpc == 0:  # writer reconnects: one read
                    out.append((w, rpc, rseen, 1, None, 3 - mid(w) - front(w), pubs))
                elif wpc == 1:  # writer publish: read (the slot it rendered into must be free)
                    if back in (mid(w), front(w)):
                        return None
                    out.append((w, rpc, rseen, 2, w, back, pubs))
                elif w == wseen:  # writer publish: CAS succeeds
                    out.append((back | D | (w & (3 << SHIFT)), rpc, rseen, 0 if pubs % 2 else 1, None, mid(w), pubs + 1))
                else:
                    out.append((w, rpc, rseen, 1, None, back, pubs))
            return out

        start = (2 << SHIFT, 0, None, 0, None, None, 0)  # as InitAsHost
        seen, todo = set(), [start]
        while todo:
            st = todo.pop()
            if st in seen:
                continue
            seen.add(st)
            nxt = steps(st)
            self.assertIsNotNone(nxt, f"writer would publish into a slot in use: {st}")
            todo.extend(nxt)
        self.assertGreater(len(seen), 100)


if __name__ == "__main__":
    unittest.main()
