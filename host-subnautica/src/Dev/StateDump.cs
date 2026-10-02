using Newtonsoft.Json.Linq;
using SubCraft.Player;
using UnityEngine;

namespace SubCraft.Dev
{
	/// <summary>JSON snapshot of what the harness needs to check a scenario.</summary>
	public static class StateDump
	{
		public static JObject Capture()
		{
			var o = new JObject
			{
				["time"] = Time.unscaledTime,
				["inGame"] = HostState.InGame(),
				["menuOpen"] = HostState.MenuOpen(),
				["loading"] = HostState.Loading(),
				["screen"] = $"{Screen.width}x{Screen.height}",
				["fps"] = 1f / Mathf.Max(Time.unscaledDeltaTime, 1e-4f),
				["oceanLevel"] = Ocean.GetOceanLevel(),
			};
			var driver = LinkDriver.Instance;
			if (driver != null)
			{
				o["link"] = new JObject
				{
					["mcLinked"] = driver.McLinked,
					["teleportSeq"] = driver.TeleportSeq,
					["puppet"] = PlayerPuppet.Active,
					["collision"] = new JObject
					{
						["sectionsSent"] = driver.Harvester.SectionsSent,
						["trianglesSent"] = driver.Harvester.TrianglesSent,
						["bytesSent"] = driver.Harvester.BytesSent,
						["lastColliders"] = driver.Harvester.Collisions,
						["notReadySkips"] = driver.Harvester.NotReady,
						["provisionalOpen"] = driver.Harvester.ProvisionalSent,
						["terrainMeshesCaptured"] = World.TerrainMeshCapture.Captured,
						["terrainMeshesHeld"] = World.TerrainMeshCapture.Count,
					},
					["mcWorld"] = Render.LiveWorld.Instance == null ? null : new JObject
					{
						["sections"] = Render.LiveWorld.Instance.SectionCount,
						["lights"] = Render.LiveWorld.Instance.LightCount,
						["boxes"] = Render.LiveWorld.Instance.BoxCount,
						["messages"] = Render.LiveWorld.Instance.Messages,
						["pages"] = Render.LiveWorld.Instance.Pages,
						["cells"] = Render.LiveWorld.Instance.Cells,
					},
					["mcFeetToSurface"] = driver.HaveMc ? (JToken)SurfaceGap(driver.Mc.X, driver.Mc.Y, driver.Mc.Z) : null,
					["mc"] = driver.HaveMc ? new JObject
					{
						["pos"] = new JArray(driver.Mc.X, driver.Mc.Y, driver.Mc.Z),
						["look"] = new JArray(driver.Mc.Yaw, driver.Mc.Pitch),
						["eyeHeight"] = driver.Mc.EyeHeight,
						["flags"] = driver.Mc.Flags,
						["teleportAck"] = driver.Mc.TeleportAck,
						["frame"] = driver.Mc.FrameCounter,
						["health"] = driver.Mc.Health,
						["air"] = driver.Mc.Air,
					} : null,
				};
			}
			var player = global::Player.main;
			if (player != null)
			{
				var pos = player.transform.position;
				var col = player.playerController.activeController?.GetCollider();
				o["player"] = new JObject
				{
					["pos"] = Vec(pos),
					["feetMc"] = Vec(PlayerPuppet.HostFeetMc),
					["collider"] = col != null ? new JObject { ["min"] = Vec(col.bounds.min), ["max"] = Vec(col.bounds.max), ["type"] = col.GetType().Name } : null,
					["motorMode"] = player.motorMode.ToString(),
					["underwater"] = player.IsUnderwater(),
					["inside"] = player.IsInside(),
					["depth"] = Ocean.GetDepthOf(player.gameObject),
					["biome"] = player.GetBiomeString(),
					["kinematic"] = player.playerController.useRigidbody.isKinematic,
					["oxygen"] = player.GetOxygenAvailable(),
					["cursor"] = $"{Cursor.lockState} visible {Cursor.visible} mcScreen {SubCraft.Player.McScreenInput.Active}",
					["oxygenCapacity"] = player.GetOxygenCapacity(),
				};
			}
			if (EscapePod.main != null)
			{
				o["lifepod"] = new JObject { ["pos"] = Vec(EscapePod.main.transform.position), ["dryVolumes"] = LinkDriver.Instance != null ? LinkDriver.Instance.Dry.Count : 0 };
			}
			var cam = MainCamera.camera;
			if (cam != null)
			{
				o["camera"] = new JObject
				{
					["pos"] = Vec(cam.transform.position),
					["forward"] = Vec(cam.transform.forward),
					["fov"] = cam.fieldOfView,
				};
			}
			if (DayNightCycle.main != null)
			{
				o["dayScalar"] = DayNightCycle.main.GetDayScalar();
			}
			return o;
		}

		/// <summary>Distance from Minecraft's feet down to Subnautica's collision surface (negative: inside it).</summary>
		private static float SurfaceGap(double x, double y, double z)
		{
			var from = new Vector3((float)x, (float)y + 1f, (float)-z);
			var hits = Physics.RaycastAll(from, Vector3.down, 30f, ~0, QueryTriggerInteraction.Ignore);
			float best = float.NaN;
			foreach (var h in hits)
			{
				if (h.collider.GetComponentInParent<global::Player>() != null || h.collider.GetComponentInParent<Creature>() != null)
				{
					continue;
				}
				float gap = h.distance - 1f;
				if (float.IsNaN(best) || gap < best)
				{
					best = gap;
				}
			}
			return best;
		}

		internal static JArray Vec(Vector3 v) => new JArray(v.x, v.y, v.z);
	}
}
