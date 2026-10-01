using System.Collections.Generic;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Render
{
	/// <summary>
	/// Turns captured Minecraft geometry (DumpReader) into Unity GameObjects drawn with Subnautica's
	/// own materials, plus point lights for light-emitting blocks. Minecraft coordinates map to Unity
	/// as (x, y, -z); mirroring z reverses triangle winding, so every triangle's order is flipped.
	/// </summary>
	public static class SceneBuilder
	{
		private static readonly Vector3[] DirNormals =
		{
			Vector3.down, Vector3.up, new Vector3(0, 0, 1), new Vector3(0, 0, -1), Vector3.left, Vector3.right, // MC N=-z -> Unity +z
		};

		/// <summary>
		/// Builds the dump under a new root. <paramref name="offset"/> is added to every Unity position
		/// (Unity metres), so a scene captured anywhere in Minecraft can be shown anywhere in Subnautica.
		/// </summary>
		public static GameObject Build(DumpReader dump, Vector3 offset, string name)
		{
			var root = new GameObject(name);
			var textures = new Dictionary<int, Texture2D>();
			foreach (var t in dump.Textures.Values)
			{
				textures[t.Id] = MakeTexture(t);
			}
			int meshes = 0, tris = 0;
			var renderers = new List<Renderer>();
			foreach (var batch in dump.Batches)
			{
				if (!textures.TryGetValue(batch.Texture, out var tex))
				{
					continue;
				}
				// One mesh per batch, one submesh per (material class, emitter).
				var groups = new Dictionary<int, List<int>>();
				var positions = new List<Vector3>(batch.Vertices.Count);
				var uvs = new List<Vector2>(batch.Vertices.Count);
				var colors = new List<Color32>(batch.Vertices.Count);
				var normals = new List<Vector3>(batch.Vertices.Count);
				// Minecraft's vertex colour (tint x AO) baked into a texture page (AlbedoBake).
				var src = dump.Textures[batch.Texture];
				var bake = AlbedoBake.Bake(batch.Vertices, src.Width, src.Height, src.Rgba);
				var page = MakeTexture(new DumpReader.Texture { Id = batch.Texture, Width = bake.Width, Height = bake.Height, Rgba = bake.Rgba });
				page.name = $"SubCraft page tex{batch.Texture} ({bake.Cells} cells)";
				for (int i = 0; i < batch.Vertices.Count; i++)
				{
					var v = batch.Vertices[i];
					positions.Add(new Vector3(v.X, v.Y, -v.Z) + offset);
					uvs.Add(new Vector2(bake.U[i], bake.V[i]));
					colors.Add(new Color32((byte)v.Color, (byte)(v.Color >> 8), (byte)(v.Color >> 16), (byte)(v.Color >> 24)));
					int dir = v.NormalDir;
					normals.Add(dir >= 0 && dir < 6 ? DirNormals[dir] : Vector3.up);
				}
				for (int t = 0; t + 2 < batch.Vertices.Count; t += 3)
				{
					var v = batch.Vertices[t];
					int key = v.Material * 2 + (v.Emitter ? 1 : 0);
					if (!groups.TryGetValue(key, out var idx))
					{
						groups[key] = idx = new List<int>();
					}
					idx.Add(t);
					idx.Add(t + 2); // flipped winding (z mirror)
					idx.Add(t + 1);
				}
				var mesh = new Mesh { name = $"{name} tex{batch.Texture}" };
				mesh.indexFormat = positions.Count > 65000 ? UnityEngine.Rendering.IndexFormat.UInt32 : UnityEngine.Rendering.IndexFormat.UInt16;
				mesh.SetVertices(positions);
				mesh.SetUVs(0, uvs);
				mesh.SetColors(colors);
				mesh.SetNormals(normals);
				var mats = new List<Material>();
				mesh.subMeshCount = groups.Count;
				int sub = 0;
				foreach (var g in groups)
				{
					mesh.SetTriangles(g.Value, sub++);
					mats.Add(MaterialFactory.Create(g.Key / 2, (g.Key & 1) != 0, page));
					tris += g.Value.Count / 3;
				}
				mesh.RecalculateTangents();
				mesh.RecalculateBounds();
				var go = new GameObject(mesh.name);
				go.transform.SetParent(root.transform, false);
				go.AddComponent<MeshFilter>().sharedMesh = mesh;
				var mr = go.AddComponent<MeshRenderer>();
				mr.sharedMaterials = mats.ToArray();
				mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.On;
				mr.receiveShadows = true;
				renderers.Add(mr);
				meshes++;
			}
			// Subnautica lights its props with an environment sky (ambient + reflections) handed to
			// their renderers by SkyApplier; without one, faces away from the sun go black.
			var sky = root.AddComponent<SkyApplier>();
			sky.renderers = renderers.ToArray();
			sky.anchorSky = Skies.Auto;
			int lights = 0;
			foreach (var l in dump.Lights)
			{
				BlockLights.Add(root.transform, new Vector3(l.X + 0.5f, l.Y + 0.5f, -(l.Z + 0.5f)) + offset, l.Level, l.Rgb, l.Kind);
				lights++;
			}
			Plugin.Log.LogInfo($"SubCraft: built '{name}': {meshes} meshes, {tris} triangles, {lights} lights at offset {offset}");
			return root;
		}

		private static Texture2D MakeTexture(DumpReader.Texture t)
		{
			// Row 0 = Minecraft v 0, and Unity's v 0 is the first row of texture data: no flip.
			// No mip chain: Subnautica's lower quality presets drop the top mip levels of every
			// mipmapped texture (masterTextureLimit), which turned 16x16 sprites into 4x4.
			var tex = new Texture2D(t.Width, t.Height, TextureFormat.RGBA32, false, false)
			{
				name = $"SubCraft texture {t.Id}",
				filterMode = FilterMode.Point,
				wrapMode = TextureWrapMode.Clamp,
				anisoLevel = 0,
			};
			tex.SetPixelData(t.Rgba, 0);
			tex.Apply(false, false);
			return tex;
		}
	}
}
