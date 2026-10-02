using UnityEngine;
using WorldStreaming;

namespace SubCraft.World
{
	/// <summary>
	/// Does Subnautica's voxel terrain touch a box? Answered from the octrees, which are streamed
	/// much further out than the terrain's collision meshes (a 5x5x5 window of 16 m cells around the
	/// camera). Lets the harvester tell Minecraft "nothing here" ahead of a fast player (elytra)
	/// instead of leaving the space unknown, which Minecraft treats as solid.
	/// </summary>
	public static class OctreeProbe
	{
		/// <summary>True: no terrain voxel in the box. False: some. Null: not loaded yet.</summary>
		public static bool? Open(Vector3 minUnity, Vector3 maxUnity)
		{
			var lws = LargeWorldStreamer.main;
			var streamer = lws != null ? lws.streamerV2 : null;
			if (streamer == null)
			{
				return null;
			}
			var detailed = Open(lws, streamer.octreesStreamer, minUnity, maxUnity);
			// Above the sea, terrain means islands, which the coarse octrees (streamed much further
			// out) still show: use them when the detailed ones aren't loaded that far yet.
			if (detailed == null && minUnity.y > Ocean.GetOceanLevel() + 1f)
			{
				return Open(lws, streamer.lowDetailOctreesStreamer, minUnity, maxUnity);
			}
			return detailed;
		}

		private static bool? Open(LargeWorldStreamer lws, BatchOctreesStreamer os, Vector3 minUnity, Vector3 maxUnity)
		{
			if (os == null)
			{
				return null;
			}
			Int3 a = lws.GetBlock(minUnity), b = lws.GetBlock(maxUnity);
			var lo = Int3.Min(a, b);
			var hi = Int3.Max(a, b);
			int size = os.octreeSize;
			var ids = Int3.MinMax(Int3.FloorDiv(lo, size), Int3.FloorDiv(hi, size));
			foreach (Int3 id in ids)
			{
				if (!os.octreeBounds.Contains(id))
				{
					continue; // outside the voxel world (open ocean edge, the sky): no terrain
				}
				var o = os.GetOctree(id);
				if (o == null)
				{
					return null;
				}
				if (o.IsEmpty())
				{
					continue;
				}
				if (AnySolid(o, 0, id * size, size, lo, hi))
				{
					return false;
				}
			}
			return true;
		}

		/// <summary>Any node with a non-empty block type overlapping [lo, hi] (inclusive block coords).</summary>
		private static bool AnySolid(Octree o, int node, Int3 origin, int size, Int3 lo, Int3 hi)
		{
			if (origin.x > hi.x || origin.y > hi.y || origin.z > hi.z || origin.x + size - 1 < lo.x || origin.y + size - 1 < lo.y || origin.z + size - 1 < lo.z)
			{
				return false;
			}
			int child = o.GetFirstChildId(node);
			if (child == 0 || size <= 1)
			{
				return o.GetType(node) != 0;
			}
			int half = size >> 1;
			for (int i = 0; i < 8; i++)
			{
				var co = new Int3(origin.x + ((i & 4) != 0 ? half : 0), origin.y + ((i & 2) != 0 ? half : 0), origin.z + ((i & 1) != 0 ? half : 0));
				if (AnySolid(o, child + i, co, half, lo, hi))
				{
					return true;
				}
			}
			return false;
		}
	}
}
