using System;
using System.Collections.Generic;

namespace SubCraft.Render
{
	/// <summary>
	/// Minecraft multiplies every texel by an interpolated vertex colour (biome tint x ambient
	/// occlusion). No compiled MarmosetUBER variant takes vertex colours, so the multiply is baked
	/// into the texture instead: every distinct (sprite area, four corner colours) gets its own
	/// pre-coloured copy on a texture page, and the quad's UVs move there. Quads with the same sprite
	/// and the same corners (all plain cobblestone faces, say) share one copy.
	///
	/// No Unity types: unit-tested on .NET.
	/// </summary>
	public static class AlbedoBake
	{
		public struct Result
		{
			public int Width, Height;
			public byte[] Rgba;   // row 0 = v 0
			public float[] U, V;  // new UV per vertex
			public int Cells;
		}

		internal struct Key : IEquatable<Key>
		{
			public int X0, Y0, X1, Y1;
			public uint C0, C1, C2, C3;

			public bool Equals(Key o) => X0 == o.X0 && Y0 == o.Y0 && X1 == o.X1 && Y1 == o.Y1 && C0 == o.C0 && C1 == o.C1 && C2 == o.C2 && C3 == o.C3;
			public override bool Equals(object o) => o is Key k && Equals(k);
			public override int GetHashCode() => (((X0 * 31 + Y0) * 31 + X1) * 31 + Y1) ^ (int)(C0 * 7 + C1 * 13 + C2 * 17 + C3 * 19);
		}

		/// <summary>
		/// <paramref name="verts"/>: triangles from Minecraft quads in capture order (q0 q1 q2, q0 q2 q3).
		/// <paramref name="atlas"/>: the source texture (RGBA8, row 0 = v 0).
		/// </summary>
		public static Result Bake(IList<DumpReader.Vertex> verts, int atlasW, int atlasH, byte[] atlas)
		{
			int quads = verts.Count / 6;
			var cells = new Dictionary<Key, int>();
			var keys = new List<Key>();
			var quadCell = new int[quads];
			for (int q = 0; q < quads; q++)
			{
				int b = q * 6;
				DumpReader.Vertex v0 = verts[b], v1 = verts[b + 1], v2 = verts[b + 2], v3 = verts[b + 5];
				float u0 = Math.Min(Math.Min(v0.U, v1.U), Math.Min(v2.U, v3.U)), u1 = Math.Max(Math.Max(v0.U, v1.U), Math.Max(v2.U, v3.U));
				float w0 = Math.Min(Math.Min(v0.V, v1.V), Math.Min(v2.V, v3.V)), w1 = Math.Max(Math.Max(v0.V, v1.V), Math.Max(v2.V, v3.V));
				var key = new Key
				{
					X0 = (int)Math.Floor(u0 * atlasW + 1e-3), X1 = (int)Math.Ceiling(u1 * atlasW - 1e-3),
					Y0 = (int)Math.Floor(w0 * atlasH + 1e-3), Y1 = (int)Math.Ceiling(w1 * atlasH - 1e-3),
					C0 = Quantize(v0.Color), C1 = Quantize(v1.Color), C2 = Quantize(v2.Color), C3 = Quantize(v3.Color),
				};
				if (key.X1 <= key.X0) key.X1 = key.X0 + 1;
				if (key.Y1 <= key.Y0) key.Y1 = key.Y0 + 1;
				if (!cells.TryGetValue(key, out int cell))
				{
					cell = keys.Count;
					cells[key] = cell;
					keys.Add(key);
				}
				quadCell[q] = cell;
			}

			// Shelf-pack the cells (sprites are mostly 16x16) into a power-of-two page.
			var origin = new (int x, int y)[keys.Count];
			int pageW = 64;
			long area = 0;
			foreach (var k in keys) area += (long)(k.X1 - k.X0) * (k.Y1 - k.Y0);
			while ((long)pageW * pageW < area * 2) pageW *= 2;
			int cx = 0, cy = 0, shelf = 0;
			for (int i = 0; i < keys.Count; i++)
			{
				int w = keys[i].X1 - keys[i].X0, h = keys[i].Y1 - keys[i].Y0;
				if (w > pageW) pageW = NextPow2(w);
				if (cx + w > pageW)
				{
					cx = 0;
					cy += shelf;
					shelf = 0;
				}
				origin[i] = (cx, cy);
				cx += w;
				shelf = Math.Max(shelf, h);
			}
			int pageH = NextPow2(Math.Max(1, cy + shelf));

			var page = new byte[pageW * pageH * 4];
			var r = new Result { Width = pageW, Height = pageH, Rgba = page, U = new float[verts.Count], V = new float[verts.Count], Cells = keys.Count };
			for (int i = 0; i < keys.Count; i++)
			{
				FillCell(keys[i], origin[i], verts, Array.IndexOf(quadCell, i), atlasW, atlasH, atlas, page, pageW);
			}
			for (int q = 0; q < quads; q++)
			{
				var k = keys[quadCell[q]];
				var o = origin[quadCell[q]];
				for (int j = 0; j < 6; j++)
				{
					var v = verts[q * 6 + j];
					r.U[q * 6 + j] = (o.x + (v.U * atlasW - k.X0)) / pageW;
					r.V[q * 6 + j] = (o.y + (v.V * atlasH - k.Y0)) / pageH;
				}
			}
			return r;
		}

		/// <summary>The cell a quad (6 vertices from <paramref name="b"/>, capture order) needs.</summary>
		internal static Key KeyFor(IList<DumpReader.Vertex> verts, int b, int atlasW, int atlasH)
		{
			DumpReader.Vertex v0 = verts[b], v1 = verts[b + 1], v2 = verts[b + 2], v3 = verts[b + 5];
			float u0 = Math.Min(Math.Min(v0.U, v1.U), Math.Min(v2.U, v3.U)), u1 = Math.Max(Math.Max(v0.U, v1.U), Math.Max(v2.U, v3.U));
			float w0 = Math.Min(Math.Min(v0.V, v1.V), Math.Min(v2.V, v3.V)), w1 = Math.Max(Math.Max(v0.V, v1.V), Math.Max(v2.V, v3.V));
			var key = new Key
			{
				X0 = (int)Math.Floor(u0 * atlasW + 1e-3), X1 = (int)Math.Ceiling(u1 * atlasW - 1e-3),
				Y0 = (int)Math.Floor(w0 * atlasH + 1e-3), Y1 = (int)Math.Ceiling(w1 * atlasH - 1e-3),
				C0 = Quantize(v0.Color), C1 = Quantize(v1.Color), C2 = Quantize(v2.Color), C3 = Quantize(v3.Color),
			};
			if (key.X1 <= key.X0) key.X1 = key.X0 + 1;
			if (key.Y1 <= key.Y0) key.Y1 = key.Y0 + 1;
			return key;
		}

		/// <summary>Copies the sprite area, multiplying each texel by the quad's colour at that texel.</summary>
		internal static void FillCell(Key k, (int x, int y) o, IList<DumpReader.Vertex> verts, int quad, int atlasW, int atlasH, byte[] atlas, byte[] page, int pageW)
		{
			int b = quad * 6;
			DumpReader.Vertex q0 = verts[b], q1 = verts[b + 1], q3 = verts[b + 5];
			// Texel -> quad parameters (a along q0->q3, c along q0->q1): solve the 2x2 system in UV
			// space (works for any rotation or mirroring of the sprite on the face).
			double ax = q3.U - q0.U, ay = q3.V - q0.V, bx = q1.U - q0.U, by = q1.V - q0.V;
			double det = ax * by - ay * bx;
			// A cell is never bigger than what was allocated for it on the page (BakeCache clamps
			// huge areas, e.g. tiled UVs, to the page size): never write past it into other cells.
			int yEnd = Math.Min(k.Y1, k.Y0 + page.Length / (pageW * 4) - o.y);
			int xEnd = Math.Min(k.X1, k.X0 + pageW - o.x);
			for (int y = k.Y0; y < yEnd; y++)
			{
				for (int x = k.X0; x < xEnd; x++)
				{
					double du = (x + 0.5) / atlasW - q0.U, dv = (y + 0.5) / atlasH - q0.V;
					double s = 0, t = 0;
					if (Math.Abs(det) > 1e-12)
					{
						s = Clamp01((du * by - dv * bx) / det);
						t = Clamp01((ax * dv - ay * du) / det);
					}
					int src = (Math.Min(Math.Max(y, 0), atlasH - 1) * atlasW + Math.Min(Math.Max(x, 0), atlasW - 1)) * 4;
					int dst = ((o.y + y - k.Y0) * pageW + o.x + x - k.X0) * 4;
					for (int ch = 0; ch < 3; ch++)
					{
						int shift = ch * 8;
						// Bilinear over the quad: q0 (0,0), q3 (1,0), q2 (1,1), q1 (0,1).
						double c = (1 - s) * (1 - t) * ((k.C0 >> shift) & 0xFF) + s * (1 - t) * ((k.C3 >> shift) & 0xFF)
							+ s * t * ((k.C2 >> shift) & 0xFF) + (1 - s) * t * ((k.C1 >> shift) & 0xFF);
						page[dst + ch] = (byte)Math.Round(atlas[src + ch] * c / 255.0);
					}
					page[dst + 3] = atlas[src + 3];
				}
			}
		}

		/// <summary>Colours to 6 bits per channel: invisible in the result, many more shared cells.</summary>
		internal static uint Quantize(uint c) => (c & 0x00FCFCFC) | 0x00030303;

		private static double Clamp01(double v) => v < 0 ? 0 : v > 1 ? 1 : v;

		private static int NextPow2(int v)
		{
			int p = 1;
			while (p < v) p <<= 1;
			return p;
		}
	}
}
