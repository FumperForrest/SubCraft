using System.Collections.Generic;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Render
{
	/// <summary>Marks Unity objects made from Minecraft content: the collision harvester never sends them back.</summary>
	public sealed class McGeometry : MonoBehaviour
	{
	}

	/// <summary>
	/// Phase 2: Minecraft's blocks, live. Drains the render ring (kRenAtlas, kRenSection, kRenLights,
	/// kRenColliders, kRenClearAll) and keeps one GameObject per 16-block section: a mesh drawn with
	/// Subnautica's own MarmosetUBER materials (AlbedoBake'd onto shared pages), point lights for
	/// light-emitting blocks, and box colliders from the blocks' collision shapes, so Subnautica's
	/// creatures, vehicles and physics meet Minecraft's blocks.
	/// </summary>
	public sealed unsafe class LiveWorld : MonoBehaviour
	{
		public static LiveWorld Instance { get; private set; }

		/// <summary>Milliseconds of ring draining and mesh building per frame.</summary>
		public static float FrameBudgetMs = 4f;
		/// <summary>Block lights kept on at once (nearest first; deferred pixel lights cost per light).</summary>
		public static int MaxLights = 24;

		private sealed class Section
		{
			public GameObject Go;
			public MeshFilter Filter;
			public MeshRenderer Renderer;
			public GameObject Colliders;
			public List<Light> Lights = new List<Light>();
			public List<DumpReader.Vertex> Vertices; // kept to rebuild after an atlas change
		}

		private readonly Dictionary<long, Section> sections = new Dictionary<long, Section>();
		private readonly BakeCache bake = new BakeCache();
		private readonly List<Texture2D> pageTextures = new List<Texture2D>();
		private readonly Dictionary<long, Material> materials = new Dictionary<long, Material>();
		private readonly List<DumpReader.Vertex> scratch = new List<DumpReader.Vertex>();
		private GameObject root;
		private byte[] atlas;
		private int atlasW, atlasH;
		private float nextLightPass;
		private bool drainedAll;
		private readonly HashSet<Section> incomplete = new HashSet<Section>();
		private float nextRetry;

		public int SectionCount => sections.Count;
		public int LightCount { get; private set; }
		public int BoxCount { get; private set; }
		public long Messages { get; private set; }
		public int Pages => bake.Pages.Count;
		public int Cells => bake.Cells;

		private void Awake()
		{
			Instance = this;
		}

		private void Update()
		{
			var driver = LinkDriver.Instance;
			if (driver?.Link == null)
			{
				return;
			}
			if (!driver.McLinked)
			{
				if (sections.Count > 0)
				{
					ClearAll("Minecraft link down");
				}
				return;
			}
			// Subnautica's own materials (our templates) exist only in game: until then the messages wait.
			if (!HostState.InGame())
			{
				return;
			}
			if (incomplete.Count > 0 && Time.unscaledTime >= nextRetry)
			{
				nextRetry = Time.unscaledTime + 2f;
				var retry = new List<Section>(incomplete);
				incomplete.Clear();
				foreach (var s in retry)
				{
					if (s.Go != null) Build(s);
				}
			}
			var view = driver.Link.View;
			var sw = System.Diagnostics.Stopwatch.StartNew();
			drainedAll = false;
			while (sw.Elapsed.TotalMilliseconds < FrameBudgetMs)
			{
				if (view.DrainRender((type, off, bytes) => Handle(view, type, off, bytes), 1) == 0)
				{
					drainedAll = true;
					break;
				}
				Messages++;
			}
			UploadPages();
			if (Time.unscaledTime >= nextLightPass)
			{
				nextLightPass = Time.unscaledTime + 0.5f;
				BudgetLights();
			}
		}

		private void Handle(LinkView view, uint type, long off, int bytes)
		{
			byte* p = view.Base + off;
			switch (type)
			{
				case Proto.RenClearAll:
					ClearAll("Minecraft asked");
					break;
				case Proto.RenAtlas:
				{
					int w = *(int*)p, h = *(int*)(p + 4);
					atlas = new byte[w * h * 4];
					System.Runtime.InteropServices.Marshal.Copy((System.IntPtr)(p + 8), atlas, 0, atlas.Length);
					atlasW = w;
					atlasH = h;
					RebakeAll();
					Plugin.Log.LogInfo($"SubCraft: Minecraft block atlas {w}x{h}");
					break;
				}
				case Proto.RenSection:
				{
					int sx = *(int*)p, sy = *(int*)(p + 4), sz = *(int*)(p + 8), count = *(int*)(p + 12);
					var verts = new List<DumpReader.Vertex>(count);
					byte* v = p + 16;
					for (int i = 0; i < count; i++, v += Proto.RenVertexBytes)
					{
						verts.Add(new DumpReader.Vertex
						{
							X = *(float*)v, Y = *(float*)(v + 4), Z = *(float*)(v + 8), U = *(float*)(v + 12), V = *(float*)(v + 16),
							Color = *(uint*)(v + 20), Light = *(uint*)(v + 24), Flags = *(uint*)(v + 28),
						});
					}
					SetMesh(sx, sy, sz, verts);
					break;
				}
				case Proto.RenLights:
				{
					int sx = *(int*)p, sy = *(int*)(p + 4), sz = *(int*)(p + 8), count = *(int*)(p + 12);
					var s = Get(sx, sy, sz, count > 0);
					if (s == null)
					{
						break;
					}
					foreach (var l in s.Lights)
					{
						if (l != null) Destroy(l.gameObject);
					}
					s.Lights.Clear();
					for (int i = 0; i < count; i++)
					{
						byte* l = p + 16 + i * 8;
						uint colour = *(uint*)(l + 4);
						var at = new Vector3(l[0] + 0.5f, l[1] + 0.5f, -(l[2] + 0.5f));
						var light = BlockLights.Add(s.Go.transform, s.Go.transform.position + at, l[3], colour & 0xFFFFFF, (int)(colour >> 24));
						s.Lights.Add(light);
					}
					DropIfEmpty(sx, sy, sz);
					break;
				}
				case Proto.RenColliders:
				{
					int sx = *(int*)p, sy = *(int*)(p + 4), sz = *(int*)(p + 8), count = *(int*)(p + 12);
					var s = Get(sx, sy, sz, count > 0);
					if (s == null)
					{
						break;
					}
					if (s.Colliders != null)
					{
						BoxCount -= s.Colliders.GetComponents<BoxCollider>().Length;
						Destroy(s.Colliders);
						s.Colliders = null;
					}
					if (count > 0)
					{
						s.Colliders = new GameObject("colliders");
						s.Colliders.transform.SetParent(s.Go.transform, false);
						float* b = (float*)(p + 16);
						for (int i = 0; i < count; i++, b += 6)
						{
							var bc = s.Colliders.AddComponent<BoxCollider>();
							// MC -> Unity: z mirrored.
							bc.center = new Vector3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, -(b[2] + b[5]) / 2);
							bc.size = new Vector3(b[3] - b[0], b[4] - b[1], b[5] - b[2]);
						}
						BoxCount += count;
					}
					DropIfEmpty(sx, sy, sz);
					break;
				}
			}
		}

		private static long Key(int sx, int sy, int sz) => ((long)(sx & 0x3FFFFF) << 42) | ((long)(sy & 0xFFFFF) << 22) | (long)(sz & 0x3FFFFF);

		private Section Get(int sx, int sy, int sz, bool create)
		{
			long k = Key(sx, sy, sz);
			if (sections.TryGetValue(k, out var s) || !create)
			{
				return s;
			}
			if (root == null)
			{
				root = new GameObject("SubCraft Minecraft world");
				DontDestroyOnLoad(root);
			}
			s = new Section { Go = new GameObject($"section {sx} {sy} {sz}") };
			s.Go.transform.SetParent(root.transform, false);
			// Section origin in Unity: MC (x, y, z) -> (x, y, -z).
			s.Go.transform.position = new Vector3(sx * 16, sy * 16, -sz * 16);
			s.Go.AddComponent<McGeometry>();
			s.Filter = s.Go.AddComponent<MeshFilter>();
			s.Renderer = s.Go.AddComponent<MeshRenderer>();
			s.Renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.On;
			s.Renderer.receiveShadows = true;
			s.Renderer.enabled = false;
			// Subnautica's ambient and reflections reach props through SkyApplier (Phase 0c finding).
			var sky = s.Go.AddComponent<SkyApplier>();
			sky.renderers = new Renderer[] { s.Renderer };
			sky.anchorSky = Skies.Auto;
			sections[k] = s;
			return s;
		}

		private void DropIfEmpty(int sx, int sy, int sz)
		{
			long k = Key(sx, sy, sz);
			if (sections.TryGetValue(k, out var s) && (s.Vertices == null || s.Vertices.Count == 0) && s.Lights.Count == 0 && s.Colliders == null)
			{
				if (s.Filter.sharedMesh != null) Destroy(s.Filter.sharedMesh);
				Destroy(s.Go);
				sections.Remove(k);
			}
		}

		private void SetMesh(int sx, int sy, int sz, List<DumpReader.Vertex> verts)
		{
			var s = Get(sx, sy, sz, verts.Count > 0);
			if (s == null)
			{
				return;
			}
			s.Vertices = verts;
			Build(s);
			DropIfEmpty(sx, sy, sz);
		}

		private static readonly Vector3[] DirNormals =
		{
			Vector3.down, Vector3.up, new Vector3(0, 0, 1), new Vector3(0, 0, -1), Vector3.left, Vector3.right, // MC N=-z -> Unity +z
		};

		/// <summary>Mesh + materials for a section's vertices (needs the atlas).</summary>
		private void Build(Section s)
		{
			var verts = s.Vertices;
			if (verts == null || verts.Count == 0 || atlas == null)
			{
				if (s.Filter.sharedMesh != null) s.Filter.sharedMesh.Clear();
				s.Renderer.enabled = false;
				return;
			}
			int n = verts.Count;
			var page = new int[n];
			var u = new float[n];
			var v = new float[n];
			bake.Bake(verts, atlasW, atlasH, atlas, page, u, v);
			var positions = new Vector3[n];
			var uvs = new Vector2[n];
			var normals = new Vector3[n];
			for (int i = 0; i < n; i++)
			{
				var x = verts[i];
				positions[i] = new Vector3(x.X, x.Y, -x.Z);
				uvs[i] = new Vector2(u[i], v[i]);
				int dir = x.NormalDir;
				normals[i] = dir >= 0 && dir < 6 ? DirNormals[dir] : Vector3.up;
			}
			// Submesh per (page, material class, emitter); flipped winding (z mirror).
			var groups = new SortedDictionary<long, List<int>>();
			for (int t = 0; t + 2 < n; t += 3)
			{
				var x = verts[t];
				long key = (long)page[t] << 8 | (long)(x.Material * 2 + (x.Emitter ? 1 : 0));
				if (!groups.TryGetValue(key, out var idx))
				{
					groups[key] = idx = new List<int>();
				}
				idx.Add(t);
				idx.Add(t + 2);
				idx.Add(t + 1);
			}
			var mesh = s.Filter.sharedMesh;
			if (mesh == null)
			{
				mesh = new Mesh { name = s.Go.name };
				s.Filter.sharedMesh = mesh;
			}
			mesh.Clear();
			mesh.indexFormat = n > 65000 ? UnityEngine.Rendering.IndexFormat.UInt32 : UnityEngine.Rendering.IndexFormat.UInt16;
			mesh.vertices = positions;
			mesh.uv = uvs;
			mesh.normals = normals;
			mesh.subMeshCount = groups.Count;
			var mats = new Material[groups.Count];
			int sub = 0;
			foreach (var g in groups)
			{
				mesh.SetTriangles(g.Value, sub);
				mats[sub] = MaterialFor((int)(g.Key >> 8), (int)(g.Key & 0xFF));
				if (mats[sub] == null)
				{
					incomplete.Add(s); // no template yet: rebuilt later instead of drawn magenta
				}
				sub++;
			}
			mesh.RecalculateTangents();
			mesh.RecalculateBounds();
			s.Renderer.sharedMaterials = mats;
			s.Renderer.enabled = !incomplete.Contains(s);
		}

		private Material MaterialFor(int page, int classEmitter)
		{
			long key = (long)page << 8 | (long)classEmitter;
			if (materials.TryGetValue(key, out var m) && m != null)
			{
				return m;
			}
			while (pageTextures.Count <= page)
			{
				var tex = new Texture2D(BakeCache.PageSize, BakeCache.PageSize, TextureFormat.RGBA32, false, false)
				{
					name = $"SubCraft page {pageTextures.Count}",
					filterMode = FilterMode.Point,
					wrapMode = TextureWrapMode.Clamp,
					anisoLevel = 0,
				};
				pageTextures.Add(tex);
				bake.Pages[pageTextures.Count - 1].Dirty = true;
			}
			m = MaterialFactory.Create(classEmitter / 2, (classEmitter & 1) != 0, pageTextures[page]);
			if (m != null)
			{
				materials[key] = m;
			}
			return m;
		}

		/// <summary>New cells this frame: upload the pages that changed (no mips, see MakeTexture in 0c).</summary>
		private void UploadPages()
		{
			for (int i = 0; i < bake.Pages.Count && i < pageTextures.Count; i++)
			{
				var p = bake.Pages[i];
				if (!p.Dirty)
				{
					continue;
				}
				p.Dirty = false;
				pageTextures[i].SetPixelData(p.Rgba, 0);
				pageTextures[i].Apply(false, false);
			}
		}

		private void RebakeAll()
		{
			bake.Clear();
			foreach (var s in sections.Values)
			{
				Build(s);
			}
		}

		/// <summary>Only the nearest block lights stay on.</summary>
		private void BudgetLights()
		{
			var cam = MainCamera.camera;
			if (cam == null)
			{
				return;
			}
			var all = new List<Light>();
			foreach (var s in sections.Values)
			{
				all.AddRange(s.Lights);
			}
			LightCount = all.Count;
			if (all.Count <= MaxLights)
			{
				foreach (var l in all) l.enabled = true;
				return;
			}
			var c = cam.transform.position;
			all.Sort((a, b) => (a.transform.position - c).sqrMagnitude.CompareTo((b.transform.position - c).sqrMagnitude));
			for (int i = 0; i < all.Count; i++)
			{
				all[i].enabled = i < MaxLights;
			}
		}

		public void ClearAll(string why)
		{
			foreach (var s in sections.Values)
			{
				if (s.Filter.sharedMesh != null) Destroy(s.Filter.sharedMesh);
				Destroy(s.Go);
			}
			sections.Clear();
			BoxCount = 0;
			LightCount = 0;
			Plugin.Log.LogInfo($"SubCraft: Minecraft world cleared ({why})");
		}

		public bool Drained => drainedAll;
	}
}
