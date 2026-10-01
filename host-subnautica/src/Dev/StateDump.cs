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
				};
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

		internal static JArray Vec(Vector3 v) => new JArray(v.x, v.y, v.z);
	}
}
