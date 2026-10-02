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
		internal static ConfigEntry<bool> UnifiedHud;
		internal static ConfigEntry<string> SoundBus;
		internal static ConfigEntry<string> SoundUiBus;
		internal static ConfigEntry<float> DamageToMinecraft;
		internal static ConfigEntry<float> DamageToSubnautica;

		private void Awake()
		{
			Log = Logger;
			Diagnostics = Config.Bind("Debug", "Diagnostics", true, "Log link and puppet details every few seconds.");
			DevSlot = Config.Bind("Debug", "DevSlot", "",
				"The only save slot the dev harness may save (MISSION.md rule 13). Set by the harness's newgame command.");
			ShowOverlay = Config.Bind("Hud", "ShowMinecraftOverlay", true, "Draw Minecraft's GUI (hotbar, screens) on top of Subnautica.");
			UnifiedHud = Config.Bind("Hud", "UnifiedHud", true,
				"Subnautica's dials show Minecraft's health and hunger (Minecraft's hearts, hunger, armour and air bars hidden). False: Minecraft's own bars.");
			SoundBus = Config.Bind("Sound", "Bus", "bus:/master/SFX_for_pause/PDA_pause/all/SFX",
				"Subnautica FMOD bus Minecraft's sounds play on (so Subnautica's underwater muffling and volume apply). The harness command 'fmod' lists the buses.");
			SoundUiBus = Config.Bind("Sound", "UiBus", "bus:/master/SFX_for_pause/interface_no_pda_pause",
				"Subnautica FMOD bus for Minecraft's interface sounds (menu clicks): not paused by the PDA, never muffled.");
			DamageToMinecraft = Config.Bind("Combat", "DamageToMinecraft", 0.2f,
				"Subnautica damage to the player x this = Minecraft damage (Subnautica health 100, Minecraft 20).");
			DamageToSubnautica = Config.Bind("Combat", "DamageToSubnautica", 5f,
				"Minecraft damage to a creature x this = Subnautica damage.");
			HideDiver = Config.Bind("Player", "HideDiver", true, "Hide Subnautica's diver (body, arms, tools, mask) while Minecraft drives the player.");
			new Harmony(Guid).PatchAll(typeof(Plugin).Assembly);
			var host = new GameObject("SubCraft");
			DontDestroyOnLoad(host);
			host.hideFlags = HideFlags.HideAndDontSave;
			host.AddComponent<LinkDriver>();
			host.AddComponent<Combat.GrabController>();
			host.AddComponent<Hud.OverlayView>();
			host.AddComponent<Render.LiveWorld>();
			host.AddComponent<Dev.DevHarness>();
			Log.LogInfo($"SubCraft {Version} loaded; link file {Link.Platform.LinkFile()}");
		}
	}
}
