using HarmonyLib;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Hud
{
	/// <summary>
	/// Unified HUD (MISSION.md 3.4): while a Minecraft player is linked, Subnautica's health dial
	/// shows Minecraft's health and the food dial Minecraft's hunger, on Subnautica's 0-100 scale.
	/// Minecraft has no thirst and Subnautica's water stands still while linked, so the water dial
	/// is hidden. Oxygen stays Subnautica's own dial (the host owns oxygen).
	/// </summary>
	public static class SurvivalDials
	{
		public static bool Active
		{
			get
			{
				var d = LinkDriver.Instance;
				return Plugin.UnifiedHud.Value && d != null && d.McLinked && d.HaveMc && d.Mc.Has(Proto.McInWorld);
			}
		}

		public static float Health
		{
			get
			{
				var mc = LinkDriver.Instance.Mc;
				return mc.MaxHealth > 0f ? 100f * Mathf.Clamp01(mc.Health / mc.MaxHealth) : 100f;
			}
		}

		public static float Food => Mathf.Clamp(LinkDriver.Instance.Mc.Food, 0, 20) * 5f;
	}

	[HarmonyPatch(typeof(uGUI_HealthBar), "SetValue")]
	internal static class HealthDialPatch
	{
		private static void Prefix(ref float has, ref float capacity)
		{
			if (SurvivalDials.Active)
			{
				has = SurvivalDials.Health;
				capacity = 100f;
			}
		}
	}

	[HarmonyPatch(typeof(uGUI_FoodBar), "SetValue")]
	internal static class FoodDialPatch
	{
		private static void Prefix(ref float has, ref float capacity)
		{
			if (SurvivalDials.Active)
			{
				has = SurvivalDials.Food;
				capacity = 100f;
			}
		}
	}

	/// <summary>Fades the water dial out (its GameObject stays active, so this keeps running).</summary>
	[HarmonyPatch(typeof(uGUI_WaterBar), "LateUpdate")]
	internal static class WaterDialPatch
	{
		private static void Postfix(uGUI_WaterBar __instance)
		{
			var group = __instance.GetComponent<CanvasGroup>();
			bool hide = SurvivalDials.Active;
			if (group == null)
			{
				if (!hide)
				{
					return;
				}
				group = __instance.gameObject.AddComponent<CanvasGroup>();
			}
			group.alpha = hide ? 0f : 1f;
		}
	}
}
