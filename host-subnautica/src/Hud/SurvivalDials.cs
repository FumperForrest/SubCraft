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

		private static float lastHealth = -1f;

		/// <summary>
		/// Subnautica's player health mirrors Minecraft's (x5), so everything Subnautica hangs off it
		/// (the dial's low-health pulse, its punch when hurt, low-health effects) follows Minecraft.
		/// Never below 1: a Minecraft death respawns at once and must not look like Subnautica's.
		/// </summary>
		public static void Frame()
		{
			var player = global::Player.main;
			if (!Active || player == null || player.liveMixin == null)
			{
				lastHealth = -1f;
				return;
			}
			var live = player.liveMixin;
			float health = Mathf.Max(1f, Health * live.maxHealth / 100f);
			if (lastHealth >= 0f && health < lastHealth - 0.01f && live.onHealDamage != null)
			{
				live.onHealDamage.Trigger(lastHealth - health); // the dial's punch
			}
			live.health = health;
			live.tempDamage = 0f;
			lastHealth = health;
		}
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
		/// <summary>
		/// The dials sit on one backplate (BarsPanel/BackgroundQuad for four, BackgroundDouble for
		/// two), so the water slot's empty ring stays: a choice for Sean (hide, or show another stat).
		/// </summary>
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
