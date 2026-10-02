using BepInEx;
using BepInEx.Configuration;
using BepInEx.Logging;
using HarmonyLib;
using UnityEngine;

namespace SubCraft
{
	[BepInPlugin(Guid, "SubCraft", Version)]
	public sealed class Plugin : BaseUnityPlugin
	{
		public const string Guid = "dev.subcraft.host";
		public const string Version = "0.0.1";

		internal static ManualLogSource Log;
		internal static ConfigEntry<bool> Diagnostics;
		internal static ConfigEntry<string> DevSlot;
		internal static ConfigEntry<bool> ShowOverlay;
		internal static ConfigEntry<bool> HideDiver;

		private void Awake()
		{
			Log = Logger;
			Diagnostics = Config.Bind("Debug", "Diagnostics", true, "Log link and puppet details every few seconds.");
			DevSlot = Config.Bind("Debug", "DevSlot", "",
				"The only save slot the dev harness may save (MISSION.md rule 13). Set by the harness's newgame command.");
			ShowOverlay = Config.Bind("Hud", "ShowMinecraftOverlay", true, "Draw Minecraft's GUI (hotbar, screens) on top of Subnautica.");
			HideDiver = Config.Bind("Player", "HideDiver", true, "Hide Subnautica's diver (body, arms, tools, mask) while Minecraft drives the player.");
			new Harmony(Guid).PatchAll(typeof(Plugin).Assembly);
			var host = new GameObject("SubCraft");
			DontDestroyOnLoad(host);
			host.hideFlags = HideFlags.HideAndDontSave;
			host.AddComponent<LinkDriver>();
			host.AddComponent<Hud.OverlayView>();
			host.AddComponent<Dev.DevHarness>();
			Log.LogInfo($"SubCraft {Version} loaded; link file {Link.Platform.LinkFile()}");
		}
	}
}
