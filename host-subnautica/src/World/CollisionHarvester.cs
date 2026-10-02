using System;
using System.Collections.Generic;
using System.IO;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.World
{
	/// <summary>
	/// Sends Subnautica's exact collision surface around the player to Minecraft (kColTris), one
	/// 16-block section at a time: the triangles of every collider the player can bump into
	/// (terrain chunk meshes, rocks, wrecks, bases), so Minecraft's player collides with exactly
	/// what Subnautica's would. Sections are re-sent only when their set of colliders changes
	/// (terrain streaming in, a base piece built).
	/// </summary>
	public sealed class CollisionHarvester
	{
		public static int RadiusH = 2;   // sections around the player, horizontally (5 x 5)
		public static int RadiusV = 2;   // and vertically
		public static int SectionsPerFrame = 3;

		private readonly Dictionary<long, long> sent = new Dictionary<long, long>(); // section -> signature
		private readonly Dictionary<int, CachedMesh> meshCache = new Dictionary<int, CachedMesh>();
		private readonly Collider[] overlap = new Collider[512];
		private readonly List<Vector3> scratchVerts = new List<Vector3>();
		private readonly List<int> scratchIdx = new List<int>();
		private readonly List<float> tris = new List<float>(4096);
		private readonly List<uint> triFlags = new List<uint>(512);
		private readonly HashSet<string> unreadableWarned = new HashSet<string>();
		private int cursor;
		private uint epoch = 1;
		private int playerLayerMask;

		public long SectionsSent { get; private set; }
		public long TrianglesSent { get; private set; }
		public long BytesSent { get; private set; }
		public int Collisions { get; private set; }
		public long NotReady { get; private set; }

		private sealed class CachedMesh
		{
			public int VertexCount;
			public Bounds Bounds;
			public Vector3[] Vertices;
			public int[] Indices;
		}

		/// <summary>New Minecraft session: it forgot everything, send it all again.</summary>
		public uint Epoch => epoch;

		public void Reset(LinkView view)
		{
			sent.Clear();
			provisional.Clear();
			epoch++;
			var payload = BitConverter.GetBytes(epoch);
			view.TryWriteCollision(Proto.ColClear, payload, payload.Length);
		}

		/// <summary>Never-sent sections handled per frame before the refresh round robin.</summary>
		public static int NewSectionsPerFrame = 6;
		/// <summary>Octree probes per frame for sections ahead of the player.</summary>
		public static int ProbesPerFrame = 24;

		/// <summary>Sections described as open from the octrees only; the exact harvest replaces them.</summary>
		private readonly HashSet<long> provisional = new HashSet<long>();
		public long ProvisionalSent { get; private set; }

		private static long Key(int sx, int sy, int sz) => ((long)(sx & 0x3FFFFF) << 42) | ((long)(sy & 0xFFFFF) << 22) | (long)(sz & 0x3FFFFF);

		/// <param name="velocityMc">Minecraft's player velocity, blocks per second (MC axes).</param>
		public void Frame(LinkView view, Vector3 playerUnity, Vector3 velocityMc)
		{
			var player = global::Player.main;
			if (player == null)
			{
				return;
			}
			playerLayerMask = PlayerCollisionMask(player);
			// The player's section in Minecraft coordinates (z mirrored).
			int psx = Mathf.FloorToInt(playerUnity.x) >> 4, psy = Mathf.FloorToInt(playerUnity.y) >> 4, psz = Mathf.FloorToInt(-playerUnity.z) >> 4;

			// 1. Sections never sent exactly (the player just moved into range): nearest first.
			int budget = NewSectionsPerFrame;
			foreach (var d in Unique())
			{
				if (budget <= 0)
				{
					break;
				}
				int sx = psx + d.x, sy = psy + d.y, sz = psz + d.z;
				if (sent.ContainsKey(Key(sx, sy, sz)) || !TerrainBuilt(new Vector3(sx * 16 + 8, sy * 16 + 8, -(sz * 16 + 8))))
				{
					continue;
				}
				budget--;
				if (!Harvest(view, sx, sy, sz))
				{
					return; // ring full
				}
			}

			// 2. Ahead of a fast player, beyond what Subnautica has built: open water/air from the octrees.
			Probe(view, playerUnity, velocityMc);

			// 3. Refresh known sections (round robin, nearer more often).
			var order = Order();
			for (int n = 0; n < SectionsPerFrame; n++)
			{
				var d = order[cursor++ % order.Count];
				if (!Harvest(view, psx + d.x, psy + d.y, psz + d.z))
				{
					cursor--; // ring full: retry this section next frame
					return;
				}
			}
		}

		private static List<Vector3Int> unique;

		private static List<Vector3Int> Unique()
		{
			if (unique == null)
			{
				Order();
			}
			return unique;
		}

		private static List<Vector3Int> probeOrder;

		private void Probe(LinkView view, Vector3 playerUnity, Vector3 velocityMc)
		{
			if (probeOrder == null)
			{
				probeOrder = new List<Vector3Int>();
				for (int x = -3; x <= 3; x++)
					for (int y = -2; y <= 2; y++)
						for (int z = -3; z <= 3; z++)
							probeOrder.Add(new Vector3Int(x, y, z));
				probeOrder.Sort((a, b) => a.sqrMagnitude.CompareTo(b.sqrMagnitude));
			}
			// Where the player will be in ~1.5 s (MC axes), clamped to the octrees' detailed range.
			var aheadMc = new Vector3(playerUnity.x, playerUnity.y, -playerUnity.z) + Vector3.ClampMagnitude(velocityMc * 1.5f, 64f);
			int cx = Mathf.FloorToInt(aheadMc.x) >> 4, cy = Mathf.FloorToInt(aheadMc.y) >> 4, cz = Mathf.FloorToInt(aheadMc.z) >> 4;
			int probes = ProbesPerFrame;
			foreach (var d in probeOrder)
			{
				if (probes <= 0)
				{
					return;
				}
				int sx = cx + d.x, sy = cy + d.y, sz = cz + d.z;
				long key = Key(sx, sy, sz);
				if (sent.ContainsKey(key) || provisional.Contains(key))
				{
					continue;
				}
				probes--;
				// The section plus a 2-block margin: no terrain voxel, and no object collider (wrecks,
				// rocks, bases are loaded further out than terrain collision) in the section itself.
				var minU = new Vector3(sx * 16 - 2, sy * 16 - 2, -(sz * 16 + 18));
				var maxU = new Vector3(sx * 16 + 18, sy * 16 + 18, -(sz * 16 - 2));
				if (OctreeProbe.Open(minU, maxU) != true)
				{
					continue;
				}
				var centerU = new Vector3(sx * 16 + 8, sy * 16 + 8, -(sz * 16 + 8));
				int hits = Physics.OverlapBoxNonAlloc(centerU, new Vector3(9, 9, 9), overlap, Quaternion.identity, playerLayerMask, QueryTriggerInteraction.Ignore);
				bool any = false;
				for (int i = 0; i < hits && !any; i++)
				{
					any = Wanted(overlap[i]);
				}
				if (any)
				{
					continue;
				}
				var payload = new byte[Proto.ColRegionBytes];
				using (var w = new BinaryWriter(new MemoryStream(payload)))
				{
					w.Write(sx * 16); w.Write(sy * 16); w.Write(sz * 16);
					w.Write(sx * 16 + 15); w.Write(sy * 16 + 15); w.Write(sz * 16 + 15);
					w.Write(epoch);
					w.Write(0);
				}
				if (!view.TryWriteCollision(Proto.ColTris, payload, payload.Length))
				{
					return;
				}
				provisional.Add(key);
				ProvisionalSent++;
			}
		}

		private static List<Vector3Int> order;

		/// <summary>Section offsets nearest first, so the player's own section refreshes most.</summary>
		private static List<Vector3Int> Order()
		{
			if (order != null)
			{
				return order;
			}
			var list = new List<Vector3Int>();
			for (int x = -RadiusH; x <= RadiusH; x++)
				for (int y = -RadiusV; y <= RadiusV; y++)
					for (int z = -RadiusH; z <= RadiusH; z++)
						list.Add(new Vector3Int(x, y, z));
			list.Sort((a, b) => a.sqrMagnitude.CompareTo(b.sqrMagnitude));
			unique = list;
			// Nearer sections appear more often in the round robin.
			var weighted = new List<Vector3Int>();
			foreach (var v in list)
			{
				int reps = v.sqrMagnitude <= 1 ? 4 : v.sqrMagnitude <= 3 ? 2 : 1;
				for (int i = 0; i < reps; i++) weighted.Add(v);
			}
			order = weighted;
			return order;
		}

		/// <summary>
		/// Dev: raycast the physics surface below a Unity point and compare with our copy of the hit
		/// collider's triangles: the distance from the hit to the nearest of our triangles should be ~0.
		/// </summary>
		public string TriCheck(float x, float z, float fromY)
		{
			if (!Physics.Raycast(new Vector3(x, fromY, z), Vector3.down, out var hit, 100f, 1 << LayerID.TerrainCollider, QueryTriggerInteraction.Ignore))
			{
				return "no terrain below";
			}
			var mc = hit.collider as MeshCollider;
			var sb = new System.Text.StringBuilder($"physics hit {hit.point} normal {hit.normal} on {hit.collider.name} triangleIndex {hit.triangleIndex}\n");
			var streamer = LargeWorldStreamer.main;
			sb.Append($"terrain material '{MaterialDatabase.GetTerrainMaterial(hit.point, hit.normal)}' surface {Utils.GetTerrainSurfaceType(hit.point, hit.normal)} block type {(streamer != null ? streamer.GetBlockType(hit.point - hit.normal * 0.2f) : -1)}\n");
			if (mc == null || mc.sharedMesh == null)
			{
				return sb.Append("not a mesh collider").ToString();
			}
			var data = Mesh(mc);
			if (data == null)
			{
				return sb.Append($"no triangle data (vertexCount {mc.sharedMesh.vertexCount}, captured {TerrainMeshCapture.TryGet(mc.sharedMesh, out _)})").ToString();
			}
			var m = mc.transform.localToWorldMatrix;
			float best = float.MaxValue;
			int bestTri = -1, maxIndex = 0;
			foreach (int i in data.Indices) maxIndex = Mathf.Max(maxIndex, i);
			for (int i = 0; i + 2 < data.Indices.Length; i += 3)
			{
				var a = m.MultiplyPoint3x4(data.Vertices[data.Indices[i]]);
				var b = m.MultiplyPoint3x4(data.Vertices[data.Indices[i + 1]]);
				var c = m.MultiplyPoint3x4(data.Vertices[data.Indices[i + 2]]);
				float d = (ClosestOnTriangle(hit.point, a, b, c) - hit.point).magnitude;
				if (d < best) { best = d; bestTri = i / 3; }
			}
			sb.Append($"our copy: {data.Vertices.Length} vertices, {data.Indices.Length / 3} triangles, max index {maxIndex}, nearest triangle #{bestTri} at {best:F4} m\n");
			sb.Append($"collider transform pos {mc.transform.position} rot {mc.transform.rotation.eulerAngles} scale {mc.transform.lossyScale}, mesh bounds {data.Bounds}");
			return sb.ToString();
		}

		private static Vector3 ClosestOnTriangle(Vector3 p, Vector3 a, Vector3 b, Vector3 c)
		{
			Vector3 ab = b - a, ac = c - a, ap = p - a;
			float d1 = Vector3.Dot(ab, ap), d2 = Vector3.Dot(ac, ap);
			if (d1 <= 0 && d2 <= 0) return a;
			Vector3 bp = p - b;
			float d3 = Vector3.Dot(ab, bp), d4 = Vector3.Dot(ac, bp);
			if (d3 >= 0 && d4 <= d3) return b;
			float vc = d1 * d4 - d3 * d2;
			if (vc <= 0 && d1 >= 0 && d3 <= 0) return a + ab * (d1 / (d1 - d3));
			Vector3 cp = p - c;
			float d5 = Vector3.Dot(ab, cp), d6 = Vector3.Dot(ac, cp);
			if (d6 >= 0 && d5 <= d6) return c;
			float vb = d5 * d2 - d1 * d6;
			if (vb <= 0 && d2 >= 0 && d6 <= 0) return a + ac * (d2 / (d2 - d6));
			float va = d3 * d6 - d5 * d4;
			if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) return b + (c - b) * ((d4 - d3) / ((d4 - d3) + (d5 - d6)));
			float den = 1f / (va + vb + vc);
			return a + ab * (vb * den) + ac * (vc * den);
		}

		/// <summary>Dev: what a section's harvest finds, without sending anything.</summary>
		public string Probe(int sx, int sy, int sz)
		{
			playerLayerMask = PlayerCollisionMask(global::Player.main);
			var mcMin = new Vector3(sx * 16, sy * 16, sz * 16);
			var centerU = new Vector3(mcMin.x + 8, mcMin.y + 8, -(mcMin.z + 8));
			int hits = Physics.OverlapBoxNonAlloc(centerU, new Vector3(9, 9, 9), overlap, Quaternion.identity, playerLayerMask, QueryTriggerInteraction.Ignore);
			tris.Clear();
			triFlags.Clear();
			var sb = new System.Text.StringBuilder();
			sb.Append($"section ({sx},{sy},{sz}) unity centre {centerU} built {TerrainBuilt(centerU)} overlap hits {hits}\n");
			for (int i = 0; i < hits; i++)
			{
				var c = overlap[i];
				int before = triFlags.Count;
				bool wanted = Wanted(c);
				if (wanted)
				{
					AddCollider(c, mcMin - Vector3.one, mcMin + Vector3.one * 17);
				}
				string mesh = c is MeshCollider mc && mc.sharedMesh != null ? $" mesh {mc.sharedMesh.name} verts {mc.sharedMesh.vertexCount} readable {mc.sharedMesh.isReadable} bounds {c.bounds}" : "";
				sb.Append($"  {c.name} [{c.GetType().Name}] wanted {wanted} -> {triFlags.Count - before} tris{mesh}\n");
			}
			return sb.ToString();
		}

		/// <summary>Builds and sends one section if its colliders changed. False if the ring is full.</summary>
		private bool Harvest(LinkView view, int sx, int sy, int sz)
		{
			// Section box in MC coords -> Unity: x same, y same, z mirrored.
			var mcMin = new Vector3(sx * 16, sy * 16, sz * 16);
			var centerU = new Vector3(mcMin.x + 8, mcMin.y + 8, -(mcMin.z + 8));
			// Only describe space Subnautica has finished streaming: an area whose terrain isn't
			// built yet would look empty, and Minecraft's player would fall into it.
			if (!TerrainBuilt(centerU))
			{
				NotReady++;
				return true;
			}
			int hits = Physics.OverlapBoxNonAlloc(centerU, new Vector3(9, 9, 9), overlap, Quaternion.identity, playerLayerMask, QueryTriggerInteraction.Ignore);
			long signature = 17;
			int used = 0;
			for (int i = 0; i < hits; i++)
			{
				var c = overlap[i];
				if (!Wanted(c))
				{
					overlap[i] = null;
					continue;
				}
				used++;
				signature = signature * 31 + Signature(c);
			}
			long key = ((long)(sx & 0x3FFFFF) << 42) | ((long)(sy & 0xFFFFF) << 22) | (long)(sz & 0x3FFFFF);
			if (sent.TryGetValue(key, out long old) && old == signature)
			{
				return true;
			}

			tris.Clear();
			triFlags.Clear();
			var lo = mcMin - Vector3.one;
			var hi = mcMin + Vector3.one * 17;
			for (int i = 0; i < hits; i++)
			{
				if (overlap[i] != null)
				{
					AddCollider(overlap[i], lo, hi);
				}
			}
			int count = triFlags.Count;
			int bytes = Proto.ColRegionBytes + count * Proto.ColTriBytes;
			var payload = new byte[bytes];
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
					w.Write(triFlags[t]);
				}
			}
			if (!view.TryWriteCollision(Proto.ColTris, payload, bytes))
			{
				return false;
			}
			SendBiomes(view, sx, sy, sz);
			sent[key] = signature;
			SectionsSent++;
			TrianglesSent += count;
			BytesSent += bytes;
			Collisions = used;
			return true;
		}

		private readonly List<string> biomeNames = new List<string>();
		private readonly byte[] biomeCells = new byte[Proto.BiomeCells];

		/// <summary>
		/// kColBiomes: Subnautica's biome (the 2D biome map plus cave overrides) at the centre of each
		/// 4x4x4 cell of the section, Minecraft's biome resolution.
		/// </summary>
		private void SendBiomes(LinkView view, int sx, int sy, int sz)
		{
			var world = LargeWorld.main;
			if (world == null)
			{
				return;
			}
			biomeNames.Clear();
			for (int i = 0; i < Proto.BiomeCells; i++)
			{
				int qx = i & 3, qz = (i >> 2) & 3, qy = i >> 4;
				// MC cell centre -> Unity (z mirrored).
				var u = new Vector3(sx * 16 + qx * 4 + 2, sy * 16 + qy * 4 + 2, -(sz * 16 + qz * 4 + 2));
				string name = world.GetBiome(u);
				if (string.IsNullOrEmpty(name))
				{
					biomeCells[i] = Proto.BiomeUnknown;
					continue;
				}
				int idx = biomeNames.IndexOf(name);
				if (idx < 0)
				{
					idx = biomeNames.Count;
					biomeNames.Add(name);
				}
				biomeCells[i] = (byte)Math.Min(idx, 254);
			}
			var ms = new MemoryStream();
			using (var w = new BinaryWriter(ms))
			{
				w.Write(sx); w.Write(sy); w.Write(sz);
				w.Write((byte)Math.Min(biomeNames.Count, 254)); w.Write((byte)0); w.Write((byte)0); w.Write((byte)0);
				w.Write(biomeCells);
				for (int i = 0; i < biomeNames.Count && i < 254; i++)
				{
					w.Write(System.Text.Encoding.UTF8.GetBytes(biomeNames[i]));
					w.Write((byte)0);
				}
			}
			var payload = ms.ToArray();
			view.TryWriteCollision(Proto.ColBiomes, payload, payload.Length);
		}

		/// <summary>
		/// Subnautica's terrain collision for this box exists: every collision-level clipmap cell it
		/// overlaps is inside the level's active window (5x5x5 cells of 16 blocks around the camera:
		/// outside it Subnautica has no terrain collision at all) and loaded. Those cells are aligned
		/// with Minecraft's 16-block sections, so a section is exactly one cell. (LargeWorldStreamer's
		/// own IsRangeActiveAndBuilt pads the range by a few blocks, which reaches into cells outside
		/// the window, and also waits for object cells: neither fits a per-section check.)
		/// </summary>
		public static bool TerrainBuilt(Vector3 centerU) => TerrainBuilt(centerU, 8f);

		public static bool TerrainBuilt(Vector3 centerU, float halfSize)
		{
			var streamer = LargeWorldStreamer.main;
			var cs = streamer != null && streamer.streamerV2 != null ? streamer.streamerV2.clipmapStreamer : null;
			if (cs == null)
			{
				return false;
			}
			var level = cs.levels[cs.collisionLevel];
			var half = new Vector3(halfSize, halfSize, halfSize);
			var lo = Int3.FloorDiv(streamer.GetBlock(centerU - half), level.cellSize);
			var hi = Int3.FloorDiv(streamer.GetBlock(centerU + half - Vector3.one * 0.01f), level.cellSize);
			foreach (Int3 id in Int3.MinMax(lo, hi))
			{
				if (!global::WorldStreaming.ClipmapCell.IsLoaded(level.GetCell(id)))
				{
					return false;
				}
			}
			return true;
		}

		/// <summary>Static things the player collides with. Creatures, items, vehicles and the player are not terrain.</summary>
		public static bool Wanted(Collider c)
		{
			if (c == null || !c.enabled || c.isTrigger || !c.gameObject.activeInHierarchy)
			{
				return false;
			}
			var rb = c.attachedRigidbody;
			// Floating but static enough to stand in: the lifepod bobs on the waves.
			if (rb != null && !rb.isKinematic && rb.GetComponent<EscapePod>() == null)
			{
				return false;
			}
			// Minecraft's own blocks (LiveWorld) must not come back as host terrain.
			if (c.GetComponentInParent<Render.McGeometry>() != null || c.GetComponentInParent<Combat.McMobStandIn>() != null)
			{
				return false;
			}
			return c.GetComponentInParent<global::Player>() == null && c.GetComponentInParent<Creature>() == null
				&& c.GetComponentInParent<Pickupable>() == null && c.GetComponentInParent<Vehicle>() == null;
		}

		private long Signature(Collider c)
		{
			long s = c.GetInstanceID();
			var t = c.transform;
			// Quantized, so a bobbing lifepod isn't re-sent every frame (5 cm, ~1 degree).
			var p = t.position * 20f;
			var r = t.rotation.eulerAngles;
			s = s * 31 + Mathf.RoundToInt(p.x) * 73856093L + Mathf.RoundToInt(p.y) * 19349663L + Mathf.RoundToInt(p.z) * 83492791L;
			s = s * 31 + Mathf.RoundToInt(r.x) * 73856093L + Mathf.RoundToInt(r.y) * 19349663L + Mathf.RoundToInt(r.z) * 83492791L;
			if (c is MeshCollider mc && mc.sharedMesh != null)
			{
				s = s * 31 + mc.sharedMesh.GetInstanceID();
				s = s * 31 + mc.sharedMesh.vertexCount;
				s = s * 31 + mc.sharedMesh.bounds.GetHashCode();
				if (TerrainMeshCapture.TryGet(mc.sharedMesh, out var cap))
				{
					s = s * 31 + cap.Vertices.Length;
				}
			}
			return s;
		}

		public static int PlayerCollisionMask(global::Player player)
		{
			var col = player.playerController.activeController != null ? player.playerController.activeController.GetCollider() : null;
			int layer = col != null ? col.gameObject.layer : player.gameObject.layer;
			int mask = 0;
			for (int l = 0; l < 32; l++)
			{
				if (!Physics.GetIgnoreLayerCollision(layer, l))
				{
					mask |= 1 << l;
				}
			}
			return mask;
		}

		private void AddCollider(Collider c, Vector3 lo, Vector3 hi)
		{
			bool terrain = c.gameObject.layer == LayerID.TerrainCollider;
			bool structure = !terrain && (c.GetComponentInParent<Base>() != null || c.GetComponentInParent<SubRoot>() != null || c.GetComponentInParent<EscapePod>() != null);
			byte objMat = terrain ? Proto.MatRock : SurfaceMaterial(Utils.GetObjectSurfaceType(c.gameObject), structure ? Proto.MatMetal : Proto.MatRock);
			uint flags = (terrain ? Proto.TriTerrain : structure ? Proto.TriStructure : 0u) | ((uint)objMat << Proto.TriMaterialShift);
			var m = c.transform.localToWorldMatrix;
			switch (c)
			{
				case MeshCollider mc:
					var cached = Mesh(mc);
					if (cached != null)
					{
						for (int i = 0; i + 2 < cached.Indices.Length; i += 3)
						{
							var a = m.MultiplyPoint3x4(cached.Vertices[cached.Indices[i]]);
							var b = m.MultiplyPoint3x4(cached.Vertices[cached.Indices[i + 1]]);
							var d = m.MultiplyPoint3x4(cached.Vertices[cached.Indices[i + 2]]);
							uint f = terrain ? Proto.TriTerrain | ((uint)TerrainMaterial(a, b, d) << Proto.TriMaterialShift) : flags;
							Tri(a, b, d, f, lo, hi);
						}
					}
					else
					{
						Box(m, mc.sharedMesh != null ? mc.sharedMesh.bounds.center : Vector3.zero, mc.sharedMesh != null ? mc.sharedMesh.bounds.size : Vector3.one, flags, lo, hi);
					}
					break;
				case BoxCollider bc:
					Box(m, bc.center, bc.size, flags, lo, hi);
					break;
				case SphereCollider sc:
					Ellipsoid(m, sc.center, Vector3.one * sc.radius, 0, -1, flags, lo, hi);
					break;
				case CapsuleCollider cc:
					var axis = cc.direction;
					var half = Mathf.Max(0, cc.height / 2 - cc.radius);
					Ellipsoid(m, cc.center, Vector3.one * cc.radius, half, axis, flags, lo, hi);
					break;
			}
		}

		private readonly Dictionary<int, byte> blockTypeMaterials = new Dictionary<int, byte>();

		/// <summary>
		/// The surface material of a terrain triangle, from the voxel block type under it. Subnautica's
		/// own terrain-material table (MaterialDatabase) ships empty (its footsteps only know "land"),
		/// so the block type's name and render material decide: sand, rock, coral, ice... (logged once).
		/// </summary>
		private byte TerrainMaterial(Vector3 a, Vector3 b, Vector3 c)
		{
			var streamer = LargeWorldStreamer.main;
			var n = Vector3.Cross(b - a, c - a);
			if (streamer == null || n.sqrMagnitude < 1e-12f)
			{
				return Proto.MatRock;
			}
			n.Normalize();
			var centre = (a + b + c) / 3f;
			int type = 0;
			for (int i = 0; i < 3 && type == 0; i++)
			{
				type = streamer.GetBlockType(centre - n * (0.2f + 0.5f * i));
			}
			if (blockTypeMaterials.TryGetValue(type, out byte mat))
			{
				return mat;
			}
			string name = "";
			var types = streamer.streamerV2 != null ? streamer.streamerV2.blockTypes : null;
			if (types != null && type > 0 && type < types.Length && types[type] != null)
			{
				var bt = types[type];
				name = (bt.name ?? "") + " " + (bt.material != null ? bt.material.name : "");
			}
			mat = ClassifyTerrain(name);
			blockTypeMaterials[type] = mat;
			Plugin.Log.LogInfo($"SubCraft: terrain block type {type} '{name.Trim()}' -> material {mat}");
			return mat;
		}

		internal static byte ClassifyTerrain(string name)
		{
			// Blends are "AToB" (e.g. Sand01ToRock02_steep): the steep variant shows B, the flat one A.
			string first = name.Trim().Split(' ')[0];
			int to = first.IndexOf("To", StringComparison.Ordinal);
			if (to > 0)
			{
				bool steep = first.IndexOf("steep", StringComparison.OrdinalIgnoreCase) >= 0;
				name = steep ? first.Substring(to + 2) : first.Substring(0, to);
			}
			string n = name.ToLowerInvariant();
			if (n.Contains("sand") || n.Contains("dune") || n.Contains("silt") || n.Contains("mud") || n.Contains("seabed")) return Proto.MatSand;
			if (n.Contains("coral") || n.Contains("reef")) return Proto.MatCoral;
			if (System.Text.RegularExpressions.Regex.IsMatch(n, @"(^|[^a-z])ice") || n.Contains("snow")) return Proto.MatIce;
			if (n.Contains("crystal") || n.Contains("glass")) return Proto.MatGlass;
			if (n.Contains("precursor") || n.Contains("alien")) return Proto.MatPrecursor;
			if (n.Contains("metal") || n.Contains("wreck") || n.Contains("ship")) return Proto.MatMetal;
			if (n.Contains("grass") || n.Contains("moss") || n.Contains("kelp") || n.Contains("mushroom") || n.Contains("root") || n.Contains("vine")
				|| n.Contains("organic") || n.Contains("lichen")) return Proto.MatOrganic;
			return Proto.MatRock;
		}

		internal static byte SurfaceMaterial(VFXSurfaceTypes type, byte fallback)
		{
			switch (type)
			{
				case VFXSurfaceTypes.sand: return Proto.MatSand;
				case VFXSurfaceTypes.rock: return Proto.MatRock;
				case VFXSurfaceTypes.coral: return Proto.MatCoral;
				case VFXSurfaceTypes.metal:
				case VFXSurfaceTypes.electronic: return Proto.MatMetal;
				case VFXSurfaceTypes.glass: return Proto.MatGlass;
				case VFXSurfaceTypes.organic:
				case VFXSurfaceTypes.vegetation:
				case VFXSurfaceTypes.wood: return Proto.MatOrganic;
				case VFXSurfaceTypes.ionCrystal: return Proto.MatPrecursor;
				default: return fallback;
			}
		}

		private CachedMesh Mesh(MeshCollider mc)
		{
			var mesh = mc.sharedMesh;
			if (mesh == null)
			{
				return null;
			}
			if (mesh.vertexCount == 0 && TerrainMeshCapture.TryGet(mesh, out var captured))
			{
				// Terrain: cooked into physics and cleared by the game; we kept a copy.
				return new CachedMesh { VertexCount = captured.Vertices.Length, Bounds = captured.Bounds, Vertices = captured.Vertices, Indices = captured.Indices };
			}
			if (!mesh.isReadable)
			{
				if (unreadableWarned.Add(mesh.name))
				{
					Plugin.Log.LogWarning($"SubCraft: collision mesh '{mesh.name}' isn't readable; using its bounds");
				}
				return null;
			}
			int id = mesh.GetInstanceID();
			if (meshCache.TryGetValue(id, out var c) && c.VertexCount == mesh.vertexCount && c.Bounds == mesh.bounds)
			{
				return c;
			}
			scratchVerts.Clear();
			scratchIdx.Clear();
			mesh.GetVertices(scratchVerts);
			for (int s = 0; s < mesh.subMeshCount; s++)
			{
				var idx = mesh.GetTriangles(s);
				scratchIdx.AddRange(idx);
			}
			c = new CachedMesh { VertexCount = mesh.vertexCount, Bounds = mesh.bounds, Vertices = scratchVerts.ToArray(), Indices = scratchIdx.ToArray() };
			meshCache[id] = c;
			if (meshCache.Count > 4096)
			{
				meshCache.Clear(); // terrain meshes are pooled and recycled: don't grow forever
			}
			return c;
		}

		/// <summary>Adds a Unity-space triangle if it touches the section box (MC space), mirrored into MC.</summary>
		private void Tri(Vector3 a, Vector3 b, Vector3 c, uint flags, Vector3 lo, Vector3 hi)
		{
			// Unity -> MC: z mirrored; mirroring flips the winding, so swap b and c.
			float ax = a.x, ay = a.y, az = -a.z, bx = c.x, by = c.y, bz = -c.z, cx = b.x, cy = b.y, cz = -b.z;
			if (Mathf.Max(ax, Mathf.Max(bx, cx)) < lo.x || Mathf.Min(ax, Mathf.Min(bx, cx)) > hi.x
				|| Mathf.Max(ay, Mathf.Max(by, cy)) < lo.y || Mathf.Min(ay, Mathf.Min(by, cy)) > hi.y
				|| Mathf.Max(az, Mathf.Max(bz, cz)) < lo.z || Mathf.Min(az, Mathf.Min(bz, cz)) > hi.z)
			{
				return;
			}
			tris.Add(ax); tris.Add(ay); tris.Add(az);
			tris.Add(bx); tris.Add(by); tris.Add(bz);
			tris.Add(cx); tris.Add(cy); tris.Add(cz);
			triFlags.Add(flags);
		}

		private static readonly int[] BoxFaces =
		{
			0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 3, 7, 6, 3, 6, 2, 0, 4, 7, 0, 7, 3, 1, 2, 6, 1, 6, 5,
		};

		private void Box(Matrix4x4 m, Vector3 center, Vector3 size, uint flags, Vector3 lo, Vector3 hi)
		{
			var h = size / 2;
			var p = new Vector3[8];
			for (int i = 0; i < 8; i++)
			{
				var local = center + new Vector3((i & 1) != 0 ^ (i & 2) != 0 ? h.x : -h.x, (i & 4) != 0 ? h.y : -h.y, (i & 2) != 0 ? h.z : -h.z);
				p[i] = m.MultiplyPoint3x4(local);
			}
			for (int i = 0; i < BoxFaces.Length; i += 3)
			{
				Tri(p[BoxFaces[i]], p[BoxFaces[i + 1]], p[BoxFaces[i + 2]], flags, lo, hi);
			}
		}

		/// <summary>Sphere (axis -1) or capsule (cylinder half-length along axis 0/1/2), as a lat-long mesh.</summary>
		private void Ellipsoid(Matrix4x4 m, Vector3 center, Vector3 radius, float halfLength, int axis, uint flags, Vector3 lo, Vector3 hi)
		{
			const int Lon = 10, Lat = 8;
			var pts = new Vector3[(Lat + 1) * Lon];
			for (int la = 0; la <= Lat; la++)
			{
				float theta = Mathf.PI * la / Lat; // 0 = top
				float y = Mathf.Cos(theta), r = Mathf.Sin(theta);
				float shift = halfLength * (la <= Lat / 2 ? 1 : -1);
				for (int lo2 = 0; lo2 < Lon; lo2++)
				{
					float phi = 2 * Mathf.PI * lo2 / Lon;
					var v = new Vector3(r * Mathf.Cos(phi) * radius.x, y * radius.y + shift, r * Mathf.Sin(phi) * radius.z);
					if (axis == 0) v = new Vector3(v.y, v.x, v.z);
					else if (axis == 2) v = new Vector3(v.x, v.z, v.y);
					pts[la * Lon + lo2] = m.MultiplyPoint3x4(center + v);
				}
			}
			for (int la = 0; la < Lat; la++)
			{
				for (int lo2 = 0; lo2 < Lon; lo2++)
				{
					int a = la * Lon + lo2, b = la * Lon + (lo2 + 1) % Lon, c = (la + 1) * Lon + lo2, d = (la + 1) * Lon + (lo2 + 1) % Lon;
					Tri(pts[a], pts[b], pts[d], flags, lo, hi);
					Tri(pts[a], pts[d], pts[c], flags, lo, hi);
				}
			}
		}
	}
}
