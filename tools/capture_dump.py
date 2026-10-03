"""Inspect a capture dump (protocol/subcraft_protocol.h, "capture-dump format").

    python3 tools/capture_dump.py scene.scdump [--obj out_dir] [--preview out.png] [--eye x y z] [--look x y z]

Prints a summary; --obj writes an OBJ (+ MTL + texture PNGs) per material; --preview renders the
scene with a small software rasterizer (nearest-texel sampling, vertex colour, alpha cutout,
translucency, a fixed sun) so a capture can be checked without either game. Python 3.9 stdlib only.
"""

import math
import os
import struct
import sys
import zlib

DUMP_MAGIC = 0x4D444353
REN_ATLAS, REN_SECTION, REN_TEXTURE, REN_SCENE, REN_LIGHTS = 1, 2, 4, 6, 8
MAT_NAMES = ["opaque", "cutout", "translucent", "emissive", "additive"]
DIRS = [(0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0)]  # MC Direction ordinals


class Texture:
    def __init__(self, w, h, rgba):
        self.w, self.h, self.rgba = w, h, rgba

    def sample(self, u, v):
        x = min(max(int(u * self.w), 0), self.w - 1)
        y = min(max(int(v * self.h), 0), self.h - 1)
        o = (y * self.w + x) * 4
        return self.rgba[o], self.rgba[o + 1], self.rgba[o + 2], self.rgba[o + 3]


class Dump:
    def __init__(self, path):
        with open(path, "rb") as f:
            data = f.read()
        magic, version, count = struct.unpack_from("<IIQ", data, 0)
        if magic != DUMP_MAGIC:
            raise SystemExit(f"not a capture dump: magic {magic:08x}")
        self.version = version
        self.textures = {}  # id -> Texture (0 = block atlas)
        self.tris = []      # (texture id, material, [(x, y, z, u, v, rgba, light)] * 3, normal ordinal) in world MC coords
        self.lights = []    # (x, y, z, level, rgb, kind)
        self.sections = 0
        pos = 16
        for _ in range(count):
            typ, n = struct.unpack_from("<II", data, pos)
            p = pos + 8
            if typ == REN_ATLAS:
                w, h = struct.unpack_from("<II", data, p)
                self.textures[0] = Texture(w, h, data[p + 8:p + 8 + w * h * 4])
            elif typ == REN_TEXTURE:
                tid, w, h, _ = struct.unpack_from("<IIII", data, p)
                self.textures[tid] = Texture(w, h, data[p + 16:p + 16 + w * h * 4])
            elif typ == REN_SECTION:
                sx, sy, sz, vc = struct.unpack_from("<iiiI", data, p)
                self.sections += 1
                self._verts(data, p + 16, vc, (sx * 16, sy * 16, sz * 16), lambda i: 0)
            elif typ == REN_LIGHTS:
                sx, sy, sz, lc = struct.unpack_from("<iiiI", data, p)
                for i in range(lc):
                    x, y, z, level, colour = struct.unpack_from("<BBBBI", data, p + 16 + i * 8)
                    self.lights.append((sx * 16 + x, sy * 16 + y, sz * 16 + z, level, colour & 0xFFFFFF, colour >> 24))
            elif typ == REN_SCENE:
                ox, oy, oz, bc, vc = struct.unpack_from("<dddII", data, p)
                batches = [struct.unpack_from("<IIII", data, p + 32 + i * 16) for i in range(bc)]
                vbase = p + 32 + bc * 16
                for tex, first, cnt, mat in batches:
                    self._verts(data, vbase + first * 32, cnt, (ox, oy, oz), lambda i, t=tex: t)
            pos += (8 + n + 7) & ~7

    def _verts(self, data, off, count, origin, tex_of):
        for t in range(count // 3):
            vs = []
            flags = 0
            for k in range(3):
                x, y, z, u, v, rgba, light, flags = struct.unpack_from("<5fIII", data, off + (t * 3 + k) * 32)
                vs.append((x + origin[0], y + origin[1], z + origin[2], u, v, rgba, light))
            self.tris.append((tex_of(t), flags & 7, vs, (flags >> 4) & 7))

    def summary(self):
        by = {}
        for tex, mat, _, _ in self.tris:
            name = MAT_NAMES[mat] if mat < len(MAT_NAMES) else f"material {mat}"
            by[(tex, name)] = by.get((tex, name), 0) + 1
        print(f"capture dump v{self.version}: {self.sections} sections, {len(self.tris)} triangles, {len(self.lights)} lights")
        for tid, t in sorted(self.textures.items()):
            print(f"  texture {tid}: {t.w}x{t.h}")
        for (tex, mat), n in sorted(by.items()):
            print(f"  texture {tex} {mat}: {n} triangles")
        for l in self.lights:
            kind = ["steady", "flame", "lava"][l[5]] if l[5] < 3 else str(l[5])
            print(f"  light at {l[0]} {l[1]} {l[2]} level {l[3]} colour #{l[4] & 0xFF:02x}{l[4] >> 8 & 0xFF:02x}{l[4] >> 16 & 0xFF:02x} {kind}")
        xs = [v[0] for _, _, vs, _ in self.tris for v in vs]
        ys = [v[1] for _, _, vs, _ in self.tris for v in vs]
        zs = [v[2] for _, _, vs, _ in self.tris for v in vs]
        if xs:
            print(f"  bounds x {min(xs):.1f}..{max(xs):.1f} y {min(ys):.1f}..{max(ys):.1f} z {min(zs):.1f}..{max(zs):.1f}")


def write_png(path, w, h, rgba):
    raw = b"".join(b"\x00" + bytes(rgba[r * w * 4:(r + 1) * w * 4]) for r in range(h))

    def chunk(tag, d):
        return struct.pack(">I", len(d)) + tag + d + struct.pack(">I", zlib.crc32(tag + d) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def write_obj(dump, out):
    os.makedirs(out, exist_ok=True)
    for tid, t in dump.textures.items():
        write_png(os.path.join(out, f"texture{tid}.png"), t.w, t.h, t.rgba)
    with open(os.path.join(out, "scene.mtl"), "w") as m:
        for tid in dump.textures:
            m.write(f"newmtl tex{tid}\nmap_Kd texture{tid}.png\nmap_d texture{tid}.png\n\n")
    with open(os.path.join(out, "scene.obj"), "w") as f:
        f.write("mtllib scene.mtl\n")
        idx = 1
        current = None
        for tex, mat, vs, _ in dump.tris:
            if tex != current:
                f.write(f"usemtl tex{tex}\n")
                current = tex
            for x, y, z, u, v, rgba, _ in vs:
                r, g, b = rgba & 0xFF, rgba >> 8 & 0xFF, rgba >> 16 & 0xFF
                f.write(f"v {x:.4f} {y:.4f} {z:.4f} {r / 255:.3f} {g / 255:.3f} {b / 255:.3f}\nvt {u:.6f} {1 - v:.6f}\n")
            f.write(f"f {idx}/{idx} {idx + 1}/{idx + 1} {idx + 2}/{idx + 2}\n")
            idx += 3
    print(f"wrote {out}/scene.obj with {len(dump.tris)} triangles")


def render(dump, path, eye, target, w=480, h=360, fov=70.0):
    # Camera basis (MC coords, Y up).
    f = [target[i] - eye[i] for i in range(3)]
    fl = math.sqrt(sum(c * c for c in f))
    f = [c / fl for c in f]
    up = (0.0, 1.0, 0.0)
    r = [f[1] * up[2] - f[2] * up[1], f[2] * up[0] - f[0] * up[2], f[0] * up[1] - f[1] * up[0]]
    rl = math.sqrt(sum(c * c for c in r))
    r = [c / rl for c in r]
    u = [r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]]
    scale = (h / 2) / math.tan(math.radians(fov) / 2)
    sun = (0.4, 0.8, 0.3)
    sl = math.sqrt(sum(c * c for c in sun))
    sun = [c / sl for c in sun]
    color = bytearray([(c) for _ in range(w * h) for c in (40, 90, 140, 255)])  # water blue
    depth = [1e30] * (w * h)
    opaque = [t for t in dump.tris if t[1] in (0, 1)]
    blended = [t for t in dump.tris if t[1] not in (0, 1)]
    for pass_tris, blend in ((opaque, False), (blended, True)):
        for tex_id, mat, vs, nrm in pass_tris:
            tex = dump.textures.get(tex_id)
            if tex is None:
                continue
            pts = []
            for x, y, z, uu, vv, rgba, light in vs:
                d = (x - eye[0], y - eye[1], z - eye[2])
                cz = d[0] * f[0] + d[1] * f[1] + d[2] * f[2]
                if cz < 0.05:
                    break
                cx = d[0] * r[0] + d[1] * r[1] + d[2] * r[2]
                cy = d[0] * u[0] + d[1] * u[1] + d[2] * u[2]
                pts.append((w / 2 + cx / cz * scale, h / 2 - cy / cz * scale, cz, uu, vv, rgba, light))
            if len(pts) < 3:
                continue
            n = DIRS[nrm - 1] if 0 < nrm <= len(DIRS) else (0, 1, 0)
            lit = 0.45 + 0.55 * max(0.0, n[0] * sun[0] + n[1] * sun[1] + n[2] * sun[2])
            (x0, y0, *_), (x1, y1, *_), (x2, y2, *_) = pts
            area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if abs(area) < 1e-9:
                continue
            minx, maxx = max(int(min(x0, x1, x2)), 0), min(int(max(x0, x1, x2)) + 1, w - 1)
            miny, maxy = max(int(min(y0, y1, y2)), 0), min(int(max(y0, y1, y2)) + 1, h - 1)
            for py in range(miny, maxy + 1):
                for px in range(minx, maxx + 1):
                    sx, sy = px + 0.5, py + 0.5
                    b0 = ((x1 - sx) * (y2 - sy) - (x2 - sx) * (y1 - sy)) / area
                    b1 = ((x2 - sx) * (y0 - sy) - (x0 - sx) * (y2 - sy)) / area
                    b2 = 1 - b0 - b1
                    if b0 < 0 or b1 < 0 or b2 < 0:
                        continue
                    iz = b0 / pts[0][2] + b1 / pts[1][2] + b2 / pts[2][2]
                    z = 1 / iz
                    i = py * w + px
                    if z >= depth[i]:
                        continue
                    pu = (b0 * pts[0][3] / pts[0][2] + b1 * pts[1][3] / pts[1][2] + b2 * pts[2][3] / pts[2][2]) * z
                    pv = (b0 * pts[0][4] / pts[0][2] + b1 * pts[1][4] / pts[1][2] + b2 * pts[2][4] / pts[2][2]) * z
                    tr, tg, tb, ta = tex.sample(pu, pv)
                    if ta < 26:
                        continue
                    vc = [sum(bk * (p[5] >> s & 0xFF) for bk, p in zip((b0, b1, b2), pts)) / 255 for s in (0, 8, 16)]
                    block = sum(bk * (p[6] & 0xF) for bk, p in zip((b0, b1, b2), pts)) / 15
                    shade = min(1.0, lit + block * 0.6)
                    c = [tr * vc[0] * shade, tg * vc[1] * shade, tb * vc[2] * shade]
                    o = i * 4
                    if blend:
                        a = ta / 255
                        c = [c[k] * a + color[o + k] * (1 - a) for k in range(3)]
                    else:
                        depth[i] = z
                    color[o:o + 3] = bytes(min(255, int(v)) for v in c)
    write_png(path, w, h, color)
    print(f"wrote preview {path} ({w}x{h}) from eye {eye}")


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        sys.exit(0 if args else 2)
    dump = Dump(args[0])
    dump.summary()
    if "--preview" in args and not dump.tris:
        sys.exit("nothing to preview: the dump has no triangles")
    if "--obj" in args:
        write_obj(dump, args[args.index("--obj") + 1])
    if "--preview" in args:
        xs = [v[0] for _, _, vs, _ in dump.tris for v in vs]
        ys = [v[1] for _, _, vs, _ in dump.tris for v in vs]
        zs = [v[2] for _, _, vs, _ in dump.tris for v in vs]
        c = ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, (min(zs) + max(zs)) / 2)
        eye = [float(a) for a in args[args.index("--eye") + 1:args.index("--eye") + 4]] if "--eye" in args else [c[0] + 7, c[1] + 5, c[2] + 9]
        look = [float(a) for a in args[args.index("--look") + 1:args.index("--look") + 4]] if "--look" in args else list(c)
        render(dump, args[args.index("--preview") + 1], eye, look)


if __name__ == "__main__":
    main()
