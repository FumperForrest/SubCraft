using System;
using System.Collections.Generic;
using System.IO;
using SubCraft.Link;

namespace SubCraft.Render
{
	/// <summary>
	/// Reads the capture-dump format (protocol header: 16-byte file header, then render-ring
	/// messages) into plain data. No Unity: unit-tested on .NET. The same message decoding serves
	/// the live render ring in Phase 2.
	/// </summary>
	public sealed class DumpReader
	{
		public struct Vertex
		{
			public float X, Y, Z, U, V; // MC coords, absolute (section/scene origin added)
			public uint Color;          // RGBA8, r low byte
			public uint Light;          // block light low byte, sky light next
			public uint Flags;          // bits 0-2 material, bit 3 emitter, bits 4-6 normal (Direction + 1)

			public int Material => (int)(Flags & 7);
			public bool Emitter => (Flags & 8) != 0;
			public int NormalDir => (int)((Flags >> 4) & 7) - 1;
		}

		public sealed class Texture
		{
			public int Id, Width, Height;
			public byte[] Rgba; // row 0 = v 0
		}

		/// <summary>Triangles sharing a texture (0 = block atlas).</summary>
		public sealed class Batch
		{
			public int Texture;
			public List<Vertex> Vertices = new List<Vertex>();
		}

		public sealed class LightSource
		{
			public int X, Y, Z, Level, Kind;
			public uint Rgb; // r low byte
		}

		public readonly Dictionary<int, Texture> Textures = new Dictionary<int, Texture>();
		public readonly List<Batch> Batches = new List<Batch>();
		public readonly List<LightSource> Lights = new List<LightSource>();
		public uint Version;
		public int Sections;

		public static DumpReader Read(string path) => Read(File.ReadAllBytes(path));

		public static DumpReader Read(byte[] d)
		{
			var r = new DumpReader();
			if (BitConverter.ToUInt32(d, 0) != Proto.DumpMagic)
			{
				throw new InvalidDataException("not a capture dump");
			}
			r.Version = BitConverter.ToUInt32(d, 4);
			long count = BitConverter.ToInt64(d, 8);
			int pos = 16;
			for (long m = 0; m < count; m++)
			{
				uint type = BitConverter.ToUInt32(d, pos);
				int n = BitConverter.ToInt32(d, pos + 4);
				int p = pos + 8;
				switch (type)
				{
					case Proto.RenAtlas:
						r.AddTexture(0, BitConverter.ToInt32(d, p), BitConverter.ToInt32(d, p + 4), d, p + 8);
						break;
					case Proto.RenTexture:
						r.AddTexture(BitConverter.ToInt32(d, p), BitConverter.ToInt32(d, p + 4), BitConverter.ToInt32(d, p + 8), d, p + 16);
						break;
					case Proto.RenSection:
					{
						int sx = BitConverter.ToInt32(d, p), sy = BitConverter.ToInt32(d, p + 4), sz = BitConverter.ToInt32(d, p + 8);
						int vc = BitConverter.ToInt32(d, p + 12);
						r.Sections++;
						r.ReadVertices(r.BatchFor(0), d, p + 16, vc, sx * 16, sy * 16, sz * 16);
						break;
					}
					case Proto.RenLights:
					{
						int sx = BitConverter.ToInt32(d, p), sy = BitConverter.ToInt32(d, p + 4), sz = BitConverter.ToInt32(d, p + 8);
						int lc = BitConverter.ToInt32(d, p + 12);
						for (int i = 0; i < lc; i++)
						{
							int o = p + 16 + i * 8;
							uint colour = BitConverter.ToUInt32(d, o + 4);
							r.Lights.Add(new LightSource
							{
								X = sx * 16 + d[o], Y = sy * 16 + d[o + 1], Z = sz * 16 + d[o + 2], Level = d[o + 3],
								Rgb = colour & 0xFFFFFF, Kind = (int)(colour >> 24),
							});
						}
						break;
					}
					case Proto.RenScene:
					{
						double ox = BitConverter.ToDouble(d, p), oy = BitConverter.ToDouble(d, p + 8), oz = BitConverter.ToDouble(d, p + 16);
						int bc = BitConverter.ToInt32(d, p + 24);
						int vbase = p + 32 + bc * Proto.RenBatchBytes;
						for (int i = 0; i < bc; i++)
						{
							int b = p + 32 + i * Proto.RenBatchBytes;
							int tex = BitConverter.ToInt32(d, b), first = BitConverter.ToInt32(d, b + 4), cnt = BitConverter.ToInt32(d, b + 8);
							r.ReadVertices(r.BatchFor(tex), d, vbase + first * Proto.RenVertexBytes, cnt, ox, oy, oz);
						}
						break;
					}
				}
				pos += (int)LinkView.Align8(8 + n);
			}
			return r;
		}

		private void AddTexture(int id, int w, int h, byte[] d, int off)
		{
			var px = new byte[w * h * 4];
			Buffer.BlockCopy(d, off, px, 0, px.Length);
			Textures[id] = new Texture { Id = id, Width = w, Height = h, Rgba = px };
		}

		private Batch BatchFor(int texture)
		{
			foreach (var b in Batches)
			{
				if (b.Texture == texture)
				{
					return b;
				}
			}
			var nb = new Batch { Texture = texture };
			Batches.Add(nb);
			return nb;
		}

		private void ReadVertices(Batch into, byte[] d, int off, int count, double ox, double oy, double oz)
		{
			for (int i = 0; i < count; i++)
			{
				int o = off + i * Proto.RenVertexBytes;
				into.Vertices.Add(new Vertex
				{
					X = (float)(BitConverter.ToSingle(d, o) + ox),
					Y = (float)(BitConverter.ToSingle(d, o + 4) + oy),
					Z = (float)(BitConverter.ToSingle(d, o + 8) + oz),
					U = BitConverter.ToSingle(d, o + 12),
					V = BitConverter.ToSingle(d, o + 16),
					Color = BitConverter.ToUInt32(d, o + 20),
					Light = BitConverter.ToUInt32(d, o + 24),
					Flags = BitConverter.ToUInt32(d, o + 28),
				});
			}
		}
	}
}
