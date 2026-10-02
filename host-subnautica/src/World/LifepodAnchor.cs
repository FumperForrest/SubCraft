using UnityEngine;

namespace SubCraft.World
{
	/// <summary>
	/// The lifepod floats and bobs on the waves (a non-kinematic Rigidbody; EscapePod.FixedUpdate only
	/// pulls it back toward its anchor). Minecraft collides with a captured copy of its hull, which
	/// went stale with every bob and was re-captured and re-voxelized several times a second: the
	/// player clipped and jittered inside. While a Minecraft player is linked the pod holds still
	/// (kinematic), so the captured hull stays exact; Subnautica gets the bobbing back on unlink.
	/// Moving interiors that Minecraft follows are Phase 5 (the Cyclops).
	/// </summary>
	public static class LifepodAnchor
	{
		private static Rigidbody held;
		private static bool savedKinematic;

		public static void Frame(bool linked)
		{
			var pod = EscapePod.main;
			var rb = pod != null ? pod.rigidbodyComponent : null;
			if (held != null && (held != rb || !linked))
			{
				held.isKinematic = savedKinematic;
				Plugin.Log.LogInfo("SubCraft: lifepod floats again");
				held = null;
			}
			if (!linked || rb == null || held == rb)
			{
				return;
			}
			savedKinematic = rb.isKinematic;
			rb.velocity = Vector3.zero;
			rb.angularVelocity = Vector3.zero;
			rb.isKinematic = true;
			held = rb;
			Plugin.Log.LogInfo($"SubCraft: lifepod held still at {pod.transform.position} while Minecraft is linked");
		}
	}
}
