using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using SubCraft.Link;

namespace SubCraft.Core.Wire
{
	/// <summary>
	/// Collision-ring payloads the host writes (protocol/subcraft_protocol.h, collision ring), as
	/// plain bytes. Minecraft parses the same bytes with dev.subcraft.core.wire.CollisionWire; both
	/// sides are tested against the shared fixtures in protocol/fixtures/.
	///
	/// Unity-free (src/Core): compiled into the no-game tests.
	/// </summary>
	public static class CollisionWire
	{
		/// <summary>kColTris: ColRegion (the 16-block section box, epoch, count) + ColTri[count]; tris holds 9 floats per triangle (MC coords).</summary>
		public static byte[] Tris(int sx, int sy, int sz, uint epoch, IList<float> tris, IList<uint> flags)
		{
			int count = flags.Count;
			if (tris.Count != count * 9)
			{
				throw new ArgumentException($"{tris.Count} floats for {count} triangles");
			}
			var payload = new byte[Proto.ColRegionBytes + count * Proto.ColTriBytes];
			using (var w = new BinaryWriter(new MemoryStream(payload)))
			{
				w.Write(sx * 16); w.Write(sy * 16); w.Write(sz * 16);
				w.Write(sx * 16 + 15); w.Write(sy * 16 + 15); w.Write(sz * 16 + 15);
				w.Write(epoch);
				w.Write(count);
				for (int t = 0; t < count; t++)
				{
					for (int k = 0; k < 9; k++)
					{
						w.Write(tris[t * 9 + k]);
					}
					w.Write(flags[t]);
				}
			}
			return payload;
		}

		/// <summary>
		/// kColBiomes: ColBiomes (section, name count) + 64 cell indices + the names, NUL-terminated
		/// UTF-8. cells[i] indexes names (or kBiomeUnknown); at most 254 names are sent.
		/// </summary>
		public static byte[] Biomes(int sx, int sy, int sz, byte[] cells, IList<string> names)
		{
			if (cells.Length != Proto.BiomeCells)
			{
				throw new ArgumentException($"{cells.Length} biome cells, expected {Proto.BiomeCells}");
			}
			int nameCount = Math.Min(names.Count, 254);
			var ms = new MemoryStream();
			using (var w = new BinaryWriter(ms))
			{
				w.Write(sx); w.Write(sy); w.Write(sz);
				w.Write((byte)nameCount); w.Write((byte)0); w.Write((byte)0); w.Write((byte)0);
				w.Write(cells);
				for (int i = 0; i < nameCount; i++)
				{
					w.Write(Encoding.UTF8.GetBytes(names[i]));
					w.Write((byte)0);
				}
			}
			return ms.ToArray();
		}
	}
}
