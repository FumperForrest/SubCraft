using HarmonyLib;
using SubCraft.Link;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace SubCraft.Hud
{
	/// <summary>
	/// "Minecraft Settings" in Subnautica's pause menu: closes it and opens Minecraft's own pause menu
	/// (kInOpenMenu) in the overlay, with Options, Mods (every mod's config screen) and the rest. The
	/// button is a clone of the menu's Help button, so it looks and navigates like Subnautica's own.
	/// </summary>
	[HarmonyPatch(typeof(IngameMenu), "Start")]
	internal static class McSettingsButton
	{
		private const string Name = "ButtonSubCraftMinecraft";

		private static void Postfix(IngameMenu __instance)
		{
			var template = __instance.helpButton;
			if (template == null || template.transform.parent.Find(Name) != null)
			{
				return;
			}
			var go = Object.Instantiate(template.gameObject, template.transform.parent, false);
			go.name = Name;
			go.transform.SetSiblingIndex(template.transform.GetSiblingIndex() + 1);
			foreach (var t in go.GetComponentsInChildren<TranslationLiveUpdate>(true))
			{
				Object.DestroyImmediate(t);
			}
			var label = go.GetComponentInChildren<TextMeshProUGUI>(true);
			if (label != null)
			{
				label.text = "Minecraft Settings";
			}
			var button = go.GetComponent<Button>();
			button.onClick = new Button.ButtonClickedEvent();
			button.onClick.AddListener(Open);
			go.SetActive(true);
			Plugin.Log.LogInfo("SubCraft: \"Minecraft Settings\" added to the pause menu");
		}

		/// <summary>Also the harness command "mcmenu".</summary>
		public static void Open()
		{
			var driver = LinkDriver.Instance;
			if (driver == null || driver.Link == null || !driver.McLinked)
			{
				ErrorMessage.AddWarning("Minecraft isn't linked");
				return;
			}
			if (IngameMenu.main != null)
			{
				IngameMenu.main.Close();
			}
			driver.Link.View.PushInput(Proto.InOpenMenu, 0);
		}
	}
}
