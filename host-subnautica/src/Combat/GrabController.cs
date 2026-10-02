using System.Collections.Generic;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Combat
{
	/// <summary>
	/// Minecraft tools moving Subnautica's objects (kEvGrab): Create: Aeronautics' physics staff on a
	/// creature, a Seamoth, a loose item. While held, the object's Rigidbody is steered toward the goal
	/// Minecraft sends every tick (velocity set each physics step, so a creature's own swimming can't
	/// fight it); released, it keeps its momentum (a throw). A lock freezes it in place (kinematic)
	/// until locked again.
	/// </summary>
	public sealed class GrabController : MonoBehaviour
	{
		private sealed class Grab
		{
			public Rigidbody Body;
			public Vector3 Goal;
			public float LastSeen;
		}

		private const float Gain = 8f;        // 1/s: closes the gap in about an eighth of a second
		private const float MaxSpeed = 40f;   // m/s
		private const float Timeout = 0.5f;   // no hold event this long: Minecraft let go (unlinked, crashed)

		private static readonly Dictionary<uint, Grab> grabs = new Dictionary<uint, Grab>();
		private static readonly Dictionary<Rigidbody, bool> locked = new Dictionary<Rigidbody, bool>();
		public static int Held => grabs.Count;
		public static int Events;

		public static void Event(in LinkView.McEvent ev)
		{
			Events++;
			var go = CreatureLink.Find(ev.Id);
			var rb = go != null ? go.GetComponent<Rigidbody>() : null;
			switch (ev.Flags)
			{
				case Proto.GrabHold:
					if (rb == null)
					{
						return;
					}
					if (locked.TryGetValue(rb, out bool wasKinematic))
					{
						rb.isKinematic = wasKinematic; // grabbing a locked object frees it
						locked.Remove(rb);
					}
					if (!grabs.TryGetValue(ev.Id, out var g))
					{
						g = new Grab { Body = rb };
						grabs[ev.Id] = g;
						Plugin.Log.LogInfo($"SubCraft: Minecraft grabbed {go.name}");
					}
					g.Goal = new Vector3(ev.A, ev.B, -ev.C);
					g.LastSeen = Time.time;
					break;
				case Proto.GrabRelease:
					grabs.Remove(ev.Id);
					break;
				case Proto.GrabLock:
					grabs.Remove(ev.Id);
					if (rb == null)
					{
						return;
					}
					if (locked.TryGetValue(rb, out bool kin))
					{
						rb.isKinematic = kin;
						locked.Remove(rb);
					}
					else
					{
						locked[rb] = rb.isKinematic;
						rb.velocity = Vector3.zero;
						rb.angularVelocity = Vector3.zero;
						rb.isKinematic = true;
					}
					break;
			}
		}

		private void FixedUpdate()
		{
			if (grabs.Count == 0)
			{
				return;
			}
			List<uint> gone = null;
			foreach (var kv in grabs)
			{
				var g = kv.Value;
				if (g.Body == null || Time.time - g.LastSeen > Timeout)
				{
					(gone ??= new List<uint>()).Add(kv.Key);
					continue;
				}
				if (g.Body.isKinematic)
				{
					g.Body.MovePosition(Vector3.MoveTowards(g.Body.position, g.Goal, MaxSpeed * Time.fixedDeltaTime));
					continue;
				}
				// The object's centre to the goal: velocity proportional to the gap, spin damped.
				Vector3 v = (g.Goal - g.Body.worldCenterOfMass) * Gain;
				g.Body.velocity = Vector3.ClampMagnitude(v, MaxSpeed);
				g.Body.angularVelocity *= 0.9f;
			}
			if (gone != null)
			{
				foreach (var id in gone)
				{
					grabs.Remove(id);
				}
			}
		}

		/// <summary>Link lost: let everything go and unfreeze what was locked.</summary>
		public static void Reset()
		{
			grabs.Clear();
			foreach (var kv in locked)
			{
				if (kv.Key != null)
				{
					kv.Key.isKinematic = kv.Value;
				}
			}
			locked.Clear();
		}
	}
}
