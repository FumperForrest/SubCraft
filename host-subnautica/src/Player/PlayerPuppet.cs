using HarmonyLib;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Player
{
	/// <summary>
	/// Minecraft owns the player's movement: while linked, Subnautica's own motor is skipped and the
	/// player is placed where Minecraft's player is. The player object stays in the world, so
	/// triggers, creature AI, oxygen zones and the camera keep working.
	///
	/// Handshake: the host bumps TeleportSeq with its own feet position; until Minecraft acks it
	/// (it has teleported and found ground) Subnautica keeps the player where it is.
	/// </summary>
	public static class PlayerPuppet
	{
		/// <summary>True while Minecraft drives the player.</summary>
		public static bool Active { get; private set; }

		/// <summary>The host player's feet in Minecraft coordinates (what a teleport sends).</summary>
		public static Vector3 HostFeetMc { get; private set; }

		private static bool savedKinematic;
		private static Vector3 lastSetPos;
		private static bool haveLastSet;
		private static float lastLog;

		/// <summary>Minecraft's standing eye height (blocks).</summary>
		public const float StandingEye = 1.62f;

		public static void OnTeleportRequested()
		{
			haveLastSet = false;
		}

		public static void Frame(LinkDriver driver)
		{
			var player = global::Player.main;
			if (player == null)
			{
				Release("no player");
				return;
			}
			var controller = player.playerController;
			var cam = MainCamera.camera;
			// The view is what has to match: "feet" are the camera minus Minecraft's standing eye
			// height, both ways (Subnautica's collider shrinks while swimming, Minecraft's doesn't).
			Vector3 camU = cam != null ? cam.transform.position : player.transform.position + Vector3.up * StandingEye;
			Vector3 feetU = camU - Vector3.up * StandingEye;
			HostFeetMc = new Vector3(feetU.x, feetU.y, -feetU.z);

			bool want = driver.McLinked && driver.HaveMc && HostState.InGame() && driver.Mc.Has(Proto.McInWorld)
				&& driver.Mc.TeleportAck == driver.TeleportSeq && !player.cinematicModeActive && !player.IsPiloting();
			if (!want)
			{
				Release(null);
				return;
			}

			// Something on the host side moved the player (warp, respawn, a harness teleport): the host
			// decides, Minecraft follows.
			if (Active && haveLastSet && (player.transform.position - lastSetPos).sqrMagnitude > 4f)
			{
				driver.RequestTeleport($"host moved the player {(player.transform.position - lastSetPos).magnitude:F1} m");
				Release(null);
				return;
			}

			if (!Active)
			{
				// Minecraft must start from where the host has the player. If they disagree (the host
				// moved the player while a teleport was pending, or during a menu), teleport again.
				Vector3 mcFeet = new Vector3((float)driver.Mc.X, (float)driver.Mc.Y, (float)driver.Mc.Z);
				if ((mcFeet - HostFeetMc).sqrMagnitude > 4f)
				{
					driver.RequestTeleport($"Minecraft is {(mcFeet - HostFeetMc).magnitude:F1} m from the host player");
					return;
				}
				Active = true;
				savedKinematic = controller.useRigidbody.isKinematic;
				var survival = player.GetComponent<Survival>();
				savedFreezeStats = survival != null && survival.freezeStats;
				Plugin.Log.LogInfo("SubCraft: Minecraft now drives the player");
			}
			controller.useRigidbody.isKinematic = true;
			controller.useRigidbody.velocity = Vector3.zero;
			// Hunger is Minecraft's (MISSION.md section 2): Subnautica's food and water stand still.
			// Every frame: Player.UnfreezeStats (beds, benches) clears the flag.
			var stats = player.GetComponent<Survival>();
			if (stats != null)
			{
				stats.freezeStats = true;
			}

			// Put Subnautica's camera on Minecraft's eye (feet + eye height: sneaking and swimming
			// lower it). The camera hangs off the player, so moving the player by the difference does it.
			Vector3 mcFeetU = new Vector3((float)driver.Mc.X, (float)driver.Mc.Y, (float)-driver.Mc.Z);
			float eye = driver.Mc.EyeHeight > 0.1f ? driver.Mc.EyeHeight : StandingEye;
			Vector3 target = player.transform.position + (mcFeetU + Vector3.up * eye - camU);
			player.transform.position = target;
			lastSetPos = target;
			haveLastSet = true;
			RescueFromTerrain(player, mcFeetU);

			if (Plugin.Diagnostics.Value && Time.unscaledTime - lastLog > 5f)
			{
				lastLog = Time.unscaledTime;
				Plugin.Log.LogInfo($"SubCraft puppet: mc feet {mcFeetU} eye {eye:F2} camera {camU} motor {player.motorMode} underwater {player.IsUnderwater()}");
			}
		}

		private static float lastRescueCheck;
		private static bool savedFreezeStats;

		/// <summary>
		/// If Minecraft's feet ended up inside Subnautica's terrain (a ray going up hits the terrain's
		/// surface from underneath), lift the player onto that surface. The "host moved the player"
		/// path then teleports Minecraft there too.
		/// </summary>
		private static void RescueFromTerrain(global::Player player, Vector3 feet)
		{
			if (Time.unscaledTime - lastRescueCheck < 1f)
			{
				return;
			}
			lastRescueCheck = Time.unscaledTime;
			bool backfaces = Physics.queriesHitBackfaces;
			Physics.queriesHitBackfaces = true;
			try
			{
				var origin = feet + Vector3.up * 0.1f;
				if (Physics.Raycast(origin, Vector3.up, out var hit, 40f, 1 << LayerID.TerrainCollider, QueryTriggerInteraction.Ignore)
					&& Vector3.Dot(hit.normal, Vector3.up) > 0.2f)
				{
					// The face we hit points up, toward its outside: we are underneath it, in the rock.
					var safe = hit.point + Vector3.up * (StandingEye + 0.2f);
					Plugin.Log.LogWarning($"SubCraft: Minecraft's player is inside terrain at {feet}; lifting to {hit.point}");
					player.SetPosition(safe);
					haveLastSet = true; // the next frame sees the jump and teleports Minecraft
				}
			}
			finally
			{
				Physics.queriesHitBackfaces = backfaces;
			}
		}

		/// <summary>Gives control back to Subnautica (link lost, menu, piloting...).</summary>
		public static void Release(string why)
		{
			if (!Active)
			{
				return;
			}
			Active = false;
			haveLastSet = false;
			var player = global::Player.main;
			if (player != null)
			{
				player.playerController.useRigidbody.isKinematic = savedKinematic;
				var survival = player.GetComponent<Survival>();
				if (survival != null)
				{
					survival.freezeStats = savedFreezeStats;
				}
			}
			Plugin.Log.LogInfo($"SubCraft: Subnautica drives the player again{(why != null ? " (" + why + ")" : "")}");
		}
	}

	/// <summary>While Minecraft drives the player, Subnautica's motors don't move it.</summary>
	[HarmonyPatch(typeof(PlayerController), nameof(PlayerController.UpdateController))]
	internal static class PlayerControllerUpdatePatch
	{
		private static bool Prefix(PlayerController __instance)
		{
			if (!PlayerPuppet.Active)
			{
				return true;
			}
			__instance.velocity = Vector3.zero;
			return false;
		}
	}
}
