using HarmonyLib;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Player
{
	/// <summary>
	/// Minecraft's camera, exactly (MISSION.md 3.4, "Character"): while Minecraft drives the player,
	/// Subnautica's own camera animation (swim, step and impact bob) is gone, the view uses
	/// Minecraft's FOV and view bobbing, and F5 puts the camera behind or in front of the player at
	/// the distance Minecraft computed against its own blocks (Unity draws the player model from the
	/// captured scene).
	///
	/// Subnautica's MainCameraControl still turns the camera with the mouse (the host owns the look).
	/// Each camera update: the prefix puts the Camera back on its unmodified local pose, the original
	/// runs, the postfix records that first-person eye pose (the look and the puppet use it) and then
	/// moves the Camera where Minecraft's camera is.
	/// </summary>
	public static class CharacterCamera
	{
		/// <summary>
		/// The first-person eye of the latest camera update, before any Minecraft offset. Kept relative
		/// to the player, so it follows when Subnautica moves the player between camera updates (hatches,
		/// leaving a vehicle, respawn): a world-space copy sent Minecraft back to the old spot.
		/// </summary>
		public static Vector3 EyePosition
		{
			get
			{
				var player = global::Player.main;
				return player != null ? player.transform.TransformPoint(eyeLocal) : eyeLocal;
			}
		}

		private static Vector3 eyeLocal;
		public static Vector3 LookForward { get; private set; }
		public static bool HaveEye { get; private set; }

		/// <summary>Minecraft camera modes (CameraType ordinals).</summary>
		public const uint FirstPerson = 0, ThirdPersonBack = 1, ThirdPersonFront = 2;

		private static Transform cameraTf;
		private static Vector3 baseLocalPos;
		private static Quaternion baseLocalRot;
		private static bool overridden;
		private static float lastFov = -1f;

		internal static void BeforeCameraUpdate()
		{
			// Undo last frame's offset so Subnautica's camera code starts from its own pose.
			if (overridden && cameraTf != null)
			{
				cameraTf.localPosition = baseLocalPos;
				cameraTf.localRotation = baseLocalRot;
			}
			overridden = false;
		}

		internal static void AfterCameraUpdate(MainCameraControl control)
		{
			var cam = MainCamera.camera;
			if (cam == null)
			{
				HaveEye = false;
				return;
			}
			var driver = LinkDriver.Instance;
			bool active = PlayerPuppet.Active && driver != null && driver.HaveMc;
			if (active)
			{
				// No Subnautica camera animation: MainCameraControl puts its swim/step/impact bob on
				// its own transform's local position.
				control.transform.localPosition = Vector3.zero;
			}
			if (cameraTf != cam.transform)
			{
				cameraTf = cam.transform;
				baseLocalPos = cameraTf.localPosition;
				baseLocalRot = cameraTf.localRotation;
			}
			var player = global::Player.main;
			eyeLocal = player != null ? player.transform.InverseTransformPoint(cameraTf.position) : cameraTf.position;
			LookForward = cameraTf.forward;
			HaveEye = true;
			if (!active)
			{
				RestoreFov();
				return;
			}

			var mc = driver.Mc;
			Vector3 f = LookForward;
			Vector3 pos = EyePosition;
			Quaternion rot = cameraTf.rotation;
			if (mc.CameraMode == ThirdPersonBack)
			{
				pos = EyePosition - f * mc.CameraDistance;
			}
			else if (mc.CameraMode == ThirdPersonFront)
			{
				// Minecraft's mirrored camera: yaw + 180, pitch negated, i.e. the opposite direction.
				pos = EyePosition + f * mc.CameraDistance;
				rot = Quaternion.LookRotation(-f, rot * Vector3.up);
			}
			// View bobbing: Minecraft moves the world by B in view space; the same picture comes from
			// moving the camera by S B^-1 S in its local space (S mirrors z, GL view -> Unity local):
			// rotation pitch about +x then roll -r about +z, position that rotation applied to (-tx, -ty, 0).
			SubCraft.Link.Coords.BobView(mc.BobPhase, mc.BobAmount, out float tx, out float ty, out float roll, out float pitch);
			Quaternion bobRot = Quaternion.AngleAxis(pitch, Vector3.right) * Quaternion.AngleAxis(-roll, Vector3.forward);
			pos += rot * (bobRot * new Vector3(-tx, -ty, 0f));
			rot *= bobRot;
			// Hurt tilt and death roll (GameRenderer.bobHurt, applied before the bobbing): H =
			// Rz(death) Ry(-dir) Rz(tilt) Ry(dir) in view space -> S H^-1 S, a pure rotation.
			if (mc.HurtTilt != 0f || mc.DeathRoll != 0f)
			{
				rot *= Quaternion.AngleAxis(mc.HurtDir, Vector3.up) * Quaternion.AngleAxis(-mc.HurtTilt, Vector3.forward)
					* Quaternion.AngleAxis(-mc.HurtDir, Vector3.up) * Quaternion.AngleAxis(-mc.DeathRoll, Vector3.forward);
			}
			cameraTf.SetPositionAndRotation(pos, rot);
			overridden = true;

			var hand = cameraTf.Find("SubCraft hand");
			if (hand != null)
			{
				float k = HandScale(mc.Fov, mc.HandFov);
				hand.localScale = new Vector3(k, k, 1f);
			}

			var pda = global::Player.main.GetPDA();
			if (pda == null || !pda.isInUse)
			{
				ApplyFov(mc.Fov); // with the PDA open, Subnautica's PDA zoom has the FOV
			}
		}

		/// <summary>Minecraft owns the FOV now (PDACameraFOVControl stands aside, see below).</summary>
		public static bool OwnsFov => lastFov > 0f;

		private static void ApplyFov(float fov)
		{
			if (fov < 1f || fov > 179f || SNCameraRoot.main == null)
			{
				return;
			}
			lastFov = fov;
			SNCameraRoot.main.SetFov(fov);
		}

		private static void RestoreFov()
		{
			if (lastFov < 0f || SNCameraRoot.main == null)
			{
				return;
			}
			lastFov = -1f;
			SNCameraRoot.main.SyncFieldOfView();
		}

		/// <summary>
		/// Scale for the first-person hand: Minecraft projects it with its own FOV (70 plus fluid
		/// effects), Unity with the world's; scaling view-space x and y by the tangent ratio makes the
		/// projections equal.
		/// </summary>
		public static float HandScale(float worldFov, float handFov)
		{
			if (worldFov < 1f || handFov < 1f)
			{
				return 1f;
			}
			return Mathf.Tan(worldFov * 0.5f * Mathf.Deg2Rad) / Mathf.Tan(handFov * 0.5f * Mathf.Deg2Rad);
		}
	}

	/// <summary>
	/// Subnautica eases the FOV toward its own setting every frame (and to 60 with the PDA open).
	/// While Minecraft drives the camera that fought Minecraft's FOV and made it stutter whenever
	/// Minecraft's changed (sprinting, flying, water); with the PDA open Subnautica keeps its zoom.
	/// </summary>
	[HarmonyPatch(typeof(PDACameraFOVControl), "Update")]
	internal static class PdaFovPatch
	{
		private static bool Prefix()
		{
			var pda = global::Player.main != null ? global::Player.main.GetPDA() : null;
			return !(CharacterCamera.OwnsFov && PlayerPuppet.Active && (pda == null || !pda.isInUse));
		}
	}

	[HarmonyPatch(typeof(MainCameraControl), "OnUpdate")]
	internal static class CameraUpdatePatch
	{
		private static void Prefix() => CharacterCamera.BeforeCameraUpdate();

		private static void Postfix(MainCameraControl __instance) => CharacterCamera.AfterCameraUpdate(__instance);
	}
}
