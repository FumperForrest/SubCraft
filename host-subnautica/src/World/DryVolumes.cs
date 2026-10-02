using System.Collections.Generic;
using System.IO;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.World
{
	/// <summary>
	/// Dry interiors near the player (kColDry): Minecraft turns the water inside them into air, so
	/// the player walks and breathes there. Phase 1: the lifepod. Habitats and subs (per-room
	/// volumes) come with Phase 5.
	/// </summary>
	public sealed class DryVolumes
	{
		private const float Range = 64f;
		private const float Quantum = 0.05f;

		private readonly List<Bounds> boxes = new List<Bounds>();
		private readonly List<uint> ids = new List<uint>();
		private readonly List<Collider> colliders = new List<Collider>();
		private string lastKey;
		private float nextCheck;

		public int Count => boxes.Count;

		public void Reset()
		{
			lastKey = null;
		}

		public void Frame(LinkView view, uint epoch, Vector3 playerUnity)
		{
			if (Time.unscaledTime < nextCheck && lastKey != null)
			{
				return;
			}
			nextCheck = Time.unscaledTime + 0.25f;
			boxes.Clear();
			ids.Clear();
			var pod = EscapePod.main;
			if (pod != null && (pod.transform.position - playerUnity).sqrMagnitude < Range * Range)
			{
				Add(pod.gameObject);
			}
			// Only send when the set changes (the lifepod bobs: quantized).
			var sb = new System.Text.StringBuilder();
			for (int i = 0; i < boxes.Count; i++)
			{
				var b = boxes[i];
				sb.Append(ids[i]).Append(':').Append(Q(b.min.x)).Append(',').Append(Q(b.min.y)).Append(',').Append(Q(b.min.z)).Append(',')
					.Append(Q(b.max.x)).Append(',').Append(Q(b.max.y)).Append(',').Append(Q(b.max.z)).Append(';');
			}
			string key = sb.ToString();
			if (key == lastKey)
			{
				return;
			}
			var payload = new byte[Proto.ColDryHeaderBytes + boxes.Count * Proto.DryBoxBytes];
			using (var w = new BinaryWriter(new MemoryStream(payload)))
			{
				w.Write(epoch);
				w.Write((uint)boxes.Count);
				for (int i = 0; i < boxes.Count; i++)
				{
					// Unity -> MC: z mirrored.
					var b = boxes[i];
					w.Write(b.min.x); w.Write(b.min.y); w.Write(-b.max.z);
					w.Write(b.max.x); w.Write(b.max.y); w.Write(-b.min.z);
					w.Write(ids[i]);
					w.Write(0u);
				}
			}
			if (view.TryWriteCollision(Proto.ColDry, payload, payload.Length))
			{
				if (lastKey == null || Plugin.Diagnostics.Value)
				{
					Plugin.Log.LogInfo($"SubCraft: dry volumes {key}");
				}
				lastKey = key;
			}
		}

		private static int Q(float v) => Mathf.RoundToInt(v / Quantum);

		/// <summary>The interior's hull: the union of its solid colliders' bounds.</summary>
		private void Add(GameObject root)
		{
			root.GetComponentsInChildren(false, colliders);
			bool any = false;
			var bounds = new Bounds();
			foreach (var c in colliders)
			{
				if (c.isTrigger || !c.enabled)
				{
					continue;
				}
				if (!any)
				{
					bounds = c.bounds;
					any = true;
				}
				else
				{
					bounds.Encapsulate(c.bounds);
				}
			}
			if (any)
			{
				boxes.Add(bounds);
				ids.Add((uint)root.GetInstanceID());
			}
		}
	}
}
