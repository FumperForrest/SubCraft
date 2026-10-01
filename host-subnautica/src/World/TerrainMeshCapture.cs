using System.Collections.Generic;
using HarmonyLib;
using Unity.Jobs;
using UnityEngine;

namespace SubCraft.World
{
	/// <summary>
	/// Subnautica cooks each terrain chunk's collision mesh into the physics engine and then clears
	/// the mesh (ClipmapCell.FinalizeCollidersIfNecessary), so its triangles can't be read later.
	/// This keeps a copy, taken just before the clear, keyed by the mesh (meshes are pooled: a reused
	/// mesh is finalized again and overwrites its copy).
	/// </summary>
	public static class TerrainMeshCapture
	{
		public sealed class Data
		{
			public Vector3[] Vertices;
			public int[] Indices;
			public Bounds Bounds;
		}

		private static readonly Dictionary<int, Data> ByMesh = new Dictionary<int, Data>();
		private static readonly List<Vector3> Verts = new List<Vector3>();
		private static readonly List<int> Idx = new List<int>();

		public static int Count => ByMesh.Count;
		public static long Captured { get; private set; }

		public static bool TryGet(Mesh mesh, out Data data) => ByMesh.TryGetValue(mesh.GetInstanceID(), out data);

		internal static void Capture(Mesh mesh)
		{
			if (mesh == null || mesh.vertexCount == 0 || !mesh.isReadable)
			{
				return;
			}
			Verts.Clear();
			Idx.Clear();
			mesh.GetVertices(Verts);
			// GetTriangles(List) replaces the list's contents, so gather submesh by submesh.
			var one = new List<int>();
			for (int s = 0; s < mesh.subMeshCount; s++)
			{
				mesh.GetTriangles(one, s, true);
				Idx.AddRange(one);
			}
			ByMesh[mesh.GetInstanceID()] = new Data { Vertices = Verts.ToArray(), Indices = Idx.ToArray(), Bounds = mesh.bounds };
			Captured++;
		}

		/// <summary>Drops a copy when Subnautica releases the chunk's collision mesh.</summary>
		internal static void Forget(Mesh mesh)
		{
			if (mesh != null)
			{
				ByMesh.Remove(mesh.GetInstanceID());
			}
		}
	}

	[HarmonyPatch(typeof(global::WorldStreaming.ClipmapCell), "FinalizeCollidersIfNecessary")]
	internal static class FinalizeCollidersPatch
	{
		// Prefix only to read the mesh before the game clears it; never skips the original.
		private static void Prefix(global::WorldStreaming.ClipmapChunk chunk)
		{
			if (chunk != null && chunk.collision != null)
			{
				TerrainMeshCapture.Capture(chunk.collision.sharedMesh);
			}
		}
	}
}
