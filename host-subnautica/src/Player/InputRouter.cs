using HarmonyLib;
using UnityEngine;

namespace SubCraft.Player
{
	/// <summary>
	/// One key, one game. While Minecraft drives the player, Subnautica's own buttons stay silent
	/// except the PDA, the pause menu, UI navigation and the look; a left click on something
	/// Subnautica can interact with (a hatch, a locker, a fabricator) is Subnautica's and is not
	/// sent to Minecraft.
	/// </summary>
	public static class InputRouter
	{
		/// <summary>Does Subnautica get this button right now?</summary>
		public static bool HostGets(GameInput.Button b)
		{
			if (!PlayerPuppet.Active || HostState.MenuOpen())
			{
				return true;
			}
			switch (b)
			{
				case GameInput.Button.UIMenu:
					return !McScreenInput.Active; // Esc belongs to the open Minecraft screen
				case GameInput.Button.PDA:
				case GameInput.Button.Look:
				case GameInput.Button.LookUp:
				case GameInput.Button.LookDown:
				case GameInput.Button.LookLeft:
				case GameInput.Button.LookRight:
					return true;
				case GameInput.Button.LeftHand:
					return HostInteractTarget();
				default:
					// UI navigation (UISubmit .. UIAdjust) only matters with a menu open (handled above).
					return false;
			}
		}

		/// <summary>The crosshair is on something Subnautica can interact with.</summary>
		public static bool HostInteractTarget()
		{
			var player = global::Player.main;
			return player != null && player.guiHand != null && player.guiHand.GetActiveTarget() != null;
		}
	}

	/// <summary>
	/// Patched where the button state comes from (every IGameInput: legacy, Input System, Steam):
	/// interface calls are never inlined, unlike GameInput's tiny static wrappers.
	/// </summary>
	[HarmonyPatch]
	internal static class ButtonStatePatch
	{
		private static System.Collections.Generic.IEnumerable<System.Reflection.MethodBase> TargetMethods()
		{
			foreach (var t in new[] { typeof(GameInputLegacy), typeof(GameInputSystem), typeof(GameInputSteam) })
			{
				var m = AccessTools.Method(t, "GetButtonState", new[] { typeof(GameInput.Button) });
				if (m != null) yield return m;
			}
		}

		private static bool Prefix(GameInput.Button __0, ref GameInput.InputStateFlags __result)
		{
			if (InputRouter.HostGets(__0)) return true;
			__result = GameInput.InputStateFlags.None;
			return false;
		}
	}

	[HarmonyPatch]
	internal static class ButtonHeldTimePatch
	{
		private static System.Collections.Generic.IEnumerable<System.Reflection.MethodBase> TargetMethods()
		{
			foreach (var t in new[] { typeof(GameInputLegacy), typeof(GameInputSystem), typeof(GameInputSteam) })
			{
				var m = AccessTools.Method(t, "GetButtonHeldTime", new[] { typeof(GameInput.Button) });
				if (m != null) yield return m;
			}
		}

		private static bool Prefix(GameInput.Button __0, ref float __result)
		{
			if (InputRouter.HostGets(__0)) return true;
			__result = 0f;
			return false;
		}
	}
}
