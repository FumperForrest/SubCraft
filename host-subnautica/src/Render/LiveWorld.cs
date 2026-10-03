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

		/// <summary>
		/// A moving block region (kRenSubLevel: Sable sub-levels, Aeronautics ships). Its plot's sections
		/// hang under this node at their offset from the pose's rotation point, so mesh, lights and block
		/// colliders all move with it (vehicles and creatures bump into a ship).
		/// </summary>
		private sealed class SubLevelNode
		{
			public GameObject Go;
			public int MinSx, MinSy, MinSz, MaxSx, MaxSy, MaxSz;
			public double PivotX, PivotY, PivotZ;
			public bool Contains(int sx, int sy, int sz) => sx >= MinSx && sx <= MaxSx && sy >= MinSy && sy <= MaxSy && sz >= MinSz && sz <= MaxSz;
		}

		private readonly Dictionary<uint, SubLevelNode> subLevels = new Dictionary<uint, SubLevelNode>();
		public int SubLevelCount => subLevels.Count;

		private void SubLevel(byte* p)
		{
			uint id = *(uint*)p, flags = *(uint*)(p + 4);
			if ((flags & 1) != 0)
			{
				if (subLevels.TryGetValue(id, out var gone))
				{
					// Sections go back to world placement (the streamer removes them as it sees fit).
					foreach (var kv in sections)
					{
						if (kv.Value.Go != null && kv.Value.Go.transform.parent == gone.Go.transform)
						{
							Unparent(kv.Key, kv.Value);
						}
					}
					Destroy(gone.Go);
					subLevels.Remove(id);
				}
				return;
			}
			int* r = (int*)(p + 8);
			double* d = (double*)(p + 32);
			if (!subLevels.TryGetValue(id, out var n))
			{
				if (root == null)
				{
					root = new GameObject("SubCraft Minecraft world");
					DontDestroyOnLoad(root);
				}
				n = new SubLevelNode { Go = new GameObject($"SubCraft sub-level {id:x8}") };
				n.Go.transform.SetParent(root.transform, false);
				subLevels[id] = n;
				Plugin.Log.LogInfo($"SubCraft: Minecraft sub-level {id:x8}, plot sections ({r[0]},{r[1]},{r[2]})..({r[3]},{r[4]},{r[5]})");
			}
			n.MinSx = r[0]; n.MinSy = r[1]; n.MinSz = r[2]; n.MaxSx = r[3]; n.MaxSy = r[4]; n.MaxSz = r[5];
			bool pivotMoved = n.PivotX != d[7] || n.PivotY != d[8] || n.PivotZ != d[9];
			n.PivotX = d[7]; n.PivotY = d[8]; n.PivotZ = d[9];
			// MC -> Unity: z mirrored; a rotation (x, y, z, w) mirrored across z is (-x, -y, z, w).
			var t = n.Go.transform;
			t.position = new Vector3((float)d[0], (float)d[1], (float)-d[2]);
			t.rotation = new Quaternion((float)-d[3], (float)-d[4], (float)d[5], (float)d[6]);
			t.localScale = new Vector3((float)d[10], (float)d[11], (float)d[12]);
			for (int sx = n.MinSx; sx <= n.MaxSx; sx++)
			{
				for (int sy = n.MinSy; sy <= n.MaxSy; sy++)
				{
					for (int sz = n.MinSz; sz <= n.MaxSz; sz++)
					{
						long k = Key(sx, sy, sz);
						if (sections.TryGetValue(k, out var s) && s.Go != null && (pivotMoved || s.Go.transform.parent != t))
						{
							Attach(n, sx, sy, sz, s);
						}
					}
				}
			}
		}

		/// <summary>Section origin relative to the rotation point, in doubles (plots lie millions of blocks out).</summary>
		private static void Attach(SubLevelNode n, int sx, int sy, int sz, Section s)
		{
			s.Go.transform.SetParent(n.Go.transform, false);
			s.Go.transform.localPosition = new Vector3((float)(sx * 16.0 - n.PivotX), (float)(sy * 16.0 - n.PivotY), (float)(-(sz * 16.0) + n.PivotZ));
			s.Go.transform.localRotation = Quaternion.identity;
			s.Go.transform.localScale = Vector3.one;
		}

		private void Unparent(long k, Section s)
		{
			s.Go.transform.SetParent(root.transform, false);
			s.Go.transform.localScale = Vector3.one;
			s.Go.transform.localRotation = Quaternion.identity;
			s.Go.transform.position = new Vector3(KeyX(k) * 16, KeyY(k) * 16, -KeyZ(k) * 16);
		}

		/// <summary>Minecraft textures by host id (0 = the block atlas): pixels, a bake cache and its pages.</summary>
		private sealed class Tex
		{
			public int W, H;
			public byte[] Rgba;
			public Texture2D Direct; // the texture as is, for batches with plain white vertex colours
			public bool DirectDirty;
			public readonly BakeCache Bake = new BakeCache();
			public readonly List<Texture2D> Pages = new List<Texture2D>();
		}

		/// <summary>One draw batch over a vertex list: texture id and RenBatch.material bits.</summary>
		private struct Batch
		{
			public int Texture, First, Count;
			public uint Material;
		}

		private readonly Dictionary<int, Tex> textures = new Dictionary<int, Tex>();
		private readonly Dictionary<long, Material> materials = new Dictionary<long, Material>();
		private GameObject root;
		private Section dynamicScene, hand;
		private byte[] pendingScene, pendingHand;
		private int pendingSceneBytes, pendingHandBytes;
		public int DynamicVertices { get; private set; }
		public int HandVertices { get; private set; }
		private float nextLightPass;
		private bool drainedAll;
		private readonly HashSet<Section> incomplete = new HashSet<Section>();
		private float nextRetry;

		public int SectionCount => sections.Count;
		public int LightCount { get; private set; }
		public int BoxCount { get; private set; }
		public long Messages { get; private set; }
		public int Pages { get { int n = 0; foreach (var t in textures.Values) n += t.Bake.Pages.Count; return n; } }
		public int Cells { get { int n = 0; foreach (var t in textures.Values) n += t.Bake.Cells; return n; } }
		public int Textures => textures.Count;
		public long AnimatedCells { get; private set; }

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
			long faults = view.SinkFaults, corrupt = view.CorruptMessages;
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
			if (view.SinkFaults != faults)
			{
				Plugin.Log.LogError($"SubCraft: render message failed ({view.SinkFaults} so far): {view.LastSinkFault}");
			}
			if (view.CorruptMessages != corrupt)
			{
				Plugin.Log.LogError($"SubCraft: render ring framing broken, pending messages dropped ({view.CorruptMessages} so far)");
			}
			if (pendingScene != null)
			{
				BuildScene(false);
			}
			if (pendingHand != null)
			{
				BuildScene(true);
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
				case Proto.RenSubLevel:
					SubLevel(p);
					break;
				case Proto.RenSound:
					Audio.SoundBridge.Load(*(uint*)p, p + Proto.RenSoundBytes, *(int*)(p + 4));
					break;
				case Proto.RenClearAll:
					ClearAll("Minecraft asked");
					break;
				case Proto.RenAtlas:
				{
					int w = *(int*)p, h = *(int*)(p + 4);
					SetTexture(0, w, h, p + 8);
					RebakeAll();
					Plugin.Log.LogInfo($"SubCraft: Minecraft block atlas {w}x{h}");
					break;
				}
				case Proto.RenAtlasRegion:
				{
					// An animated sprite's new frame: patch the atlas, redo the cells that use it.
					int x = *(int*)p, y = *(int*)(p + 4), w = *(int*)(p + 8), h = *(int*)(p + 12);
					if (!textures.TryGetValue(0, out var atlas) || x < 0 || y < 0 || x + w > atlas.W || y + h > atlas.H)
					{
						break;
					}
					for (int row = 0; row < h; row++)
					{
						System.Runtime.InteropServices.Marshal.Copy((System.IntPtr)(p + 16 + row * w * 4), atlas.Rgba, ((y + row) * atlas.W + x) * 4, w * 4);
					}
					AnimatedCells += atlas.Bake.Refill(x, y, w, h, atlas.W, atlas.H, atlas.Rgba);
					atlas.DirectDirty = true;
					break;
				}
				case Proto.RenTexture:
				{
					int id = *(int*)p, w = *(int*)(p + 4), h = *(int*)(p + 8);
					SetTexture(id, w, h, p + 16);
					Plugin.Log.LogInfo($"SubCraft: Minecraft texture {id} {w}x{h}");
					break;
				}
				case Proto.RenScene:
				case Proto.RenHand:
				{
					// Only the newest frame is drawn: copy it now (the ring space is released after this).
					bool isHand = type == Proto.RenHand;
					ref byte[] buf = ref isHand ? ref pendingHand : ref pendingScene;
					if (buf == null || buf.Length < bytes)
					{
						buf = new byte[Mathf.NextPowerOfTwo(bytes)];
					}
					System.Runtime.InteropServices.Marshal.Copy((System.IntPtr)p, buf, 0, bytes);
					if (isHand) pendingHandBytes = bytes; else pendingSceneBytes = bytes;
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
		private static int KeyX(long k) => (int)(k >> 42) << 10 >> 10;
		private static int KeyY(long k) => (int)(k >> 22 & 0xFFFFF) << 12 >> 12;
		private static int KeyZ(long k) => (int)(k & 0x3FFFFF) << 10 >> 10;

		private Section Get(int sx, int sy, int sz, bool create)
		{
			long k = Key(sx, sy, sz);
			if (sections.TryGetValue(k, out var s) || !create)
			{
				return s;
			}
			s = NewDrawable($"section {sx} {sy} {sz}", false);
			// Section origin in Unity: MC (x, y, z) -> (x, y, -z).
			s.Go.transform.position = new Vector3(sx * 16, sy * 16, -sz * 16);
			sections[k] = s;
			foreach (var n in subLevels.Values)
			{
				if (n.Contains(sx, sy, sz))
				{
					Attach(n, sx, sy, sz, s);
					break;
				}
			}
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

		private void SetTexture(int id, int w, int h, byte* pixels)
		{
			if (!textures.TryGetValue(id, out var t))
			{
				textures[id] = t = new Tex();
			}
			t.W = w;
			t.H = h;
			t.Rgba = new byte[w * h * 4];
			System.Runtime.InteropServices.Marshal.Copy((System.IntPtr)pixels, t.Rgba, 0, t.Rgba.Length);
			if (t.Direct != null) Destroy(t.Direct);
			t.Direct = new Texture2D(w, h, TextureFormat.RGBA32, false, false)
			{
				name = $"SubCraft texture {id}", filterMode = FilterMode.Point, wrapMode = TextureWrapMode.Clamp, anisoLevel = 0,
			};
			t.Direct.SetPixelData(t.Rgba, 0);
			t.Direct.Apply(false, false);
			t.Bake.Clear();
			partialAlpha.Clear();
			foreach (var page in t.Pages) Destroy(page);
			t.Pages.Clear();
			// Materials on this texture's pages are stale.
			var stale = new List<long>();
			foreach (var k in materials.Keys) if ((int)(k >> 40) == id) stale.Add(k);
			foreach (var k in stale) materials.Remove(k);
		}

		/// <summary>Mesh + materials for a section's vertices (needs the atlas).</summary>
		private void Build(Section s)
		{
			var verts = s.Vertices;
			if (verts == null || verts.Count == 0 || !textures.ContainsKey(0))
			{
				if (s.Filter.sharedMesh != null) s.Filter.sharedMesh.Clear();
				s.Renderer.enabled = false;
				return;
			}
			var batches = new List<Batch> { new Batch { Texture = 0, First = 0, Count = verts.Count } };
			bool complete = Fill(s, verts, batches, false);
			if (!complete) incomplete.Add(s);
			s.Renderer.enabled = complete;
		}

		/// <summary>The newest kRenScene (world, relative to its origin) or kRenHand (view space, on the camera).</summary>
		private void BuildScene(bool isHand)
		{
			byte[] d = isHand ? pendingHand : pendingScene;
			if (isHand) pendingHand = null; else pendingScene = null;
			fixed (byte* p = d)
			{
				double ox = *(double*)p, oy = *(double*)(p + 8), oz = *(double*)(p + 16);
				int bc = *(int*)(p + 24), vc = *(int*)(p + 28);
				var batches = new List<Batch>(bc);
				for (int i = 0; i < bc; i++)
				{
					byte* b = p + 32 + i * Proto.RenBatchBytes;
					batches.Add(new Batch { Texture = *(int*)b, First = *(int*)(b + 4), Count = *(int*)(b + 8), Material = *(uint*)(b + 12) });
				}
				var verts = new List<DumpReader.Vertex>(vc);
				byte* v = p + 32 + bc * Proto.RenBatchBytes;
				for (int i = 0; i < vc; i++, v += Proto.RenVertexBytes)
				{
					verts.Add(new DumpReader.Vertex
					{
						X = *(float*)v, Y = *(float*)(v + 4), Z = *(float*)(v + 8), U = *(float*)(v + 12), V = *(float*)(v + 16),
						Color = *(uint*)(v + 20), Light = *(uint*)(v + 24), Flags = *(uint*)(v + 28),
					});
				}
				ref Section target = ref isHand ? ref hand : ref dynamicScene;
				if (target == null || target.Go == null)
				{
					target = NewDrawable(isHand ? "SubCraft hand" : "SubCraft dynamic", true);
				}
				if (isHand)
				{
					var cam = MainCamera.camera;
					if (cam == null)
					{
						return;
					}
					if (target.Go.transform.parent != cam.transform)
					{
						target.Go.transform.SetParent(cam.transform, false);
						target.Go.transform.localPosition = Vector3.zero;
						target.Go.transform.localRotation = Quaternion.identity;
					}
					HandVertices = vc;
				}
				else
				{
					target.Go.transform.position = new Vector3((float)ox, (float)oy, (float)-oz);
					DynamicVertices = vc;
				}
				target.Vertices = verts;
				bool complete = vc > 0 && Fill(target, verts, batches, true);
				target.Renderer.enabled = complete;
			}
		}

		private Section NewDrawable(string name, bool dynamicSky)
		{
			if (root == null)
			{
				root = new GameObject("SubCraft Minecraft world");
				DontDestroyOnLoad(root);
			}
			var s = new Section { Go = new GameObject(name) };
			s.Go.transform.SetParent(root.transform, false);
			s.Go.AddComponent<McGeometry>();
			s.Filter = s.Go.AddComponent<MeshFilter>();
			s.Renderer = s.Go.AddComponent<MeshRenderer>();
			s.Renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.On;
			s.Renderer.receiveShadows = true;
			s.Renderer.enabled = false;
			var sky = s.Go.AddComponent<SkyApplier>();
			sky.renderers = new Renderer[] { s.Renderer };
			sky.anchorSky = Skies.Auto;
			sky.dynamic = dynamicSky; // moving things follow the biome's sky
			return s;
		}

		/// <summary>
		/// Fills a drawable's mesh: per batch, its texture baked with Minecraft's vertex colours
		/// (AlbedoBake) or as is when they're all white; flat normals from the triangles; mirrored z,
		/// flipped winding; back faces added for double-sided batches. False if a material is missing.
		/// </summary>
		private bool Fill(Section s, List<DumpReader.Vertex> verts, List<Batch> batches, bool directWhenWhite)
		{
			int n = verts.Count;
			var positions = new List<Vector3>(n);
			var uvs = new List<Vector2>(n);
			var normals = new List<Vector3>(n);
			var groups = new SortedDictionary<long, List<int>>();
			bool complete = true;
			foreach (var b in batches)
			{
				if (!textures.TryGetValue(b.Texture, out var tex) || b.Count <= 0 || b.First + b.Count > n)
				{
					continue;
				}
				var range = verts.GetRange(b.First, b.Count - b.Count % 3);
				int m = range.Count;
				bool direct = directWhenWhite && AllWhite(range);
				var page = new int[m];
				var u = new float[m];
				var v = new float[m];
				if (direct)
				{
					for (int i = 0; i < m; i++) { page[i] = -1; u[i] = range[i].U; v[i] = range[i].V; }
				}
				else
				{
					tex.Bake.Bake(range, tex.W, tex.H, tex.Rgba, page, u, v);
				}
				bool doubleSided = (b.Material & Proto.RenDoubleSided) != 0;
				// Minecraft draws items (held, dropped) and many entity layers with translucent types
				// whose textures are really cut out: only true partial alpha takes the glass path.
				bool cutoutAfterAll = range.Count > 0 && range[0].Material == Proto.RenMatTranslucent && !HasPartialAlpha(tex, range);
				for (int t = 0; t + 2 < m; t += 3)
				{
					var x = range[t];
					// Unity positions (z mirrored); the mirror flips the winding: a, c, b.
					Vector3 pa = Pos(range[t]), pb = Pos(range[t + 2]), pc = Pos(range[t + 1]);
					var nrm = Vector3.Cross(pb - pa, pc - pa);
					nrm = nrm.sqrMagnitude > 1e-12f ? nrm.normalized : Vector3.up;
					int cls = cutoutAfterAll && x.Material == Proto.RenMatTranslucent ? Proto.RenMatCutout : x.Material;
					long key = (long)b.Texture << 40 | (long)(page[t] + 1) << 16 | (long)(cls * 2 + (x.Emitter ? 1 : 0));
					if (!groups.TryGetValue(key, out var idx))
					{
						groups[key] = idx = new List<int>();
					}
					for (int side = 0; side < (doubleSided ? 2 : 1); side++)
					{
						int at = positions.Count;
						positions.Add(pa); positions.Add(pb); positions.Add(pc);
						uvs.Add(new Vector2(u[t], v[t])); uvs.Add(new Vector2(u[t + 2], v[t + 2])); uvs.Add(new Vector2(u[t + 1], v[t + 1]));
						var nn = side == 0 ? nrm : -nrm;
						normals.Add(nn); normals.Add(nn); normals.Add(nn);
						if (side == 0) { idx.Add(at); idx.Add(at + 1); idx.Add(at + 2); }
						else { idx.Add(at); idx.Add(at + 2); idx.Add(at + 1); }
					}
				}
			}
			var mesh = s.Filter.sharedMesh;
			if (mesh == null)
			{
				mesh = new Mesh { name = s.Go.name };
				mesh.MarkDynamic();
				s.Filter.sharedMesh = mesh;
			}
			mesh.Clear();
			mesh.indexFormat = positions.Count > 65000 ? UnityEngine.Rendering.IndexFormat.UInt32 : UnityEngine.Rendering.IndexFormat.UInt16;
			mesh.SetVertices(positions);
			mesh.SetUVs(0, uvs);
			mesh.SetNormals(normals);
			mesh.subMeshCount = groups.Count;
			var mats = new Material[groups.Count];
			int sub = 0;
			foreach (var g in groups)
			{
				mesh.SetTriangles(g.Value, sub);
				mats[sub] = MaterialFor((int)(g.Key >> 40), (int)((g.Key >> 16) & 0xFFFFFF) - 1, (int)(g.Key & 0xFF));
				complete &= mats[sub] != null;
				sub++;
			}
			mesh.RecalculateTangents();
			mesh.RecalculateBounds();
			s.Renderer.sharedMaterials = mats;
			return complete;
		}

		private readonly Dictionary<long, bool> partialAlpha = new Dictionary<long, bool>();

		/// <summary>Any texel with alpha strictly between ~0 and ~1 under the batch's quads (cached per texture rect).</summary>
		private bool HasPartialAlpha(Tex tex, List<DumpReader.Vertex> verts)
		{
			for (int q = 0; q + 5 < verts.Count; q += 6)
			{
				float u0 = Mathf.Min(Mathf.Min(verts[q].U, verts[q + 1].U), verts[q + 2].U), u1 = Mathf.Max(Mathf.Max(verts[q].U, verts[q + 1].U), verts[q + 2].U);
				float v0 = Mathf.Min(Mathf.Min(verts[q].V, verts[q + 1].V), verts[q + 2].V), v1 = Mathf.Max(Mathf.Max(verts[q].V, verts[q + 1].V), verts[q + 2].V);
				int x0 = Mathf.Clamp((int)(u0 * tex.W), 0, tex.W - 1), x1 = Mathf.Clamp(Mathf.CeilToInt(u1 * tex.W), x0 + 1, tex.W);
				int y0 = Mathf.Clamp((int)(v0 * tex.H), 0, tex.H - 1), y1 = Mathf.Clamp(Mathf.CeilToInt(v1 * tex.H), y0 + 1, tex.H);
				long key = ((long)tex.GetHashCode() << 48) ^ ((long)x0 << 36) ^ ((long)x1 << 24) ^ ((long)y0 << 12) ^ y1;
				if (!partialAlpha.TryGetValue(key, out bool partial))
				{
					partial = false;
					for (int y = y0; y < y1 && !partial; y++)
					{
						for (int x = x0; x < x1; x++)
						{
							byte a = tex.Rgba[(y * tex.W + x) * 4 + 3];
							if (a > 8 && a < 247)
							{
								partial = true;
								break;
							}
						}
					}
					partialAlpha[key] = partial;
				}
				if (partial)
				{
					return true;
				}
			}
			return false;
		}

		private static Vector3 Pos(DumpReader.Vertex v) => new Vector3(v.X, v.Y, -v.Z);

		private static bool AllWhite(List<DumpReader.Vertex> verts)
		{
			foreach (var v in verts)
			{
				if ((v.Color & 0xF0F0F0) != 0xF0F0F0)
				{
					return false;
				}
			}
			return true;
		}

		/// <summary>Material for (texture, bake page or -1 = the texture as is, class*2+emitter).</summary>
		private Material MaterialFor(int texId, int page, int classEmitter)
		{
			long key = (long)texId << 40 | (long)(page + 1) << 16 | (long)classEmitter;
			if (materials.TryGetValue(key, out var m) && m != null)
			{
				return m;
			}
			var tex = textures[texId];
			Texture2D texture;
			if (page < 0)
			{
				texture = tex.Direct;
			}
			else
			{
				while (tex.Pages.Count <= page)
				{
					tex.Pages.Add(new Texture2D(BakeCache.PageSize, BakeCache.PageSize, TextureFormat.RGBA32, false, false)
					{
						name = $"SubCraft tex {texId} page {tex.Pages.Count}", filterMode = FilterMode.Point, wrapMode = TextureWrapMode.Clamp, anisoLevel = 0,
					});
					tex.Bake.Pages[tex.Pages.Count - 1].Dirty = true;
				}
				texture = tex.Pages[page];
			}
			m = MaterialFactory.Create(classEmitter / 2, (classEmitter & 1) != 0, texture);
			if (m != null)
			{
				materials[key] = m;
			}
			return m;
		}

		/// <summary>New cells this frame: upload the pages that changed (no mips, see MakeTexture in 0c).</summary>
		private void UploadPages()
		{
			foreach (var t in textures.Values)
			{
				if (t.DirectDirty && t.Direct != null)
				{
					t.DirectDirty = false;
					t.Direct.SetPixelData(t.Rgba, 0);
					t.Direct.Apply(false, false);
				}
				for (int i = 0; i < t.Bake.Pages.Count && i < t.Pages.Count; i++)
				{
					var p = t.Bake.Pages[i];
					if (!p.Dirty)
					{
						continue;
					}
					p.Dirty = false;
					t.Pages[i].SetPixelData(p.Rgba, 0);
					t.Pages[i].Apply(false, false);
				}
			}
		}

		private void RebakeAll()
		{
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
			foreach (var n in subLevels.Values)
			{
				Destroy(n.Go);
			}
			subLevels.Clear();
			if (dynamicScene != null && dynamicScene.Renderer != null) dynamicScene.Renderer.enabled = false;
			if (hand != null && hand.Renderer != null) hand.Renderer.enabled = false;
			BoxCount = 0;
			LightCount = 0;
			Plugin.Log.LogInfo($"SubCraft: Minecraft world cleared ({why})");
		}

		public bool Drained => drainedAll;
	}
}
