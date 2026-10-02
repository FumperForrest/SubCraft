using System.Collections.Generic;
using UnityEngine;

namespace SubCraft.Player
{
	/// <summary>
	/// The Minecraft character replaces Subnautica's diver: while Minecraft drives, the diver's body,
	/// arms, held tools (they hang off its hand bones) and first-person scuba mask don't render.
	/// The PDA stays (Tab still opens it). Lights on tools keep working: only renderers are off.
	/// </summary>
	public static class DiverHider
	{
		private static readonly List<Renderer> hidden = new List<Renderer>();
		private static readonly List<Renderer> scratch = new List<Renderer>();

		public static void LateFrame()
		{
			var player = global::Player.main;
			var driver = LinkDriver.Instance;
			bool linked = driver != null && driver.McLinked && driver.HaveMc && driver.Mc.Has(Link.Proto.McInWorld);
			bool hide = linked && Plugin.HideDiver.Value && player != null;
			if (!hide)
			{
				Restore();
				return;
			}
			Hide(player.transform.Find("body"));
			Hide(player.transform.Find("camPivot/camRoot/camOffset/pdaCamPivot/SpawnPlayerMask"));
			Hide(player.transform.Find("camPivot/camRoot/player_head"));
		}

		private static void Hide(Transform root)
		{
			if (root == null)
			{
				return;
			}
			root.GetComponentsInChildren(true, scratch);
			foreach (var r in scratch)
			{
				if (r.forceRenderingOff || r.GetComponentInParent<PDA>() != null)
				{
					continue;
				}
				r.forceRenderingOff = true;
				hidden.Add(r);
			}
		}

		public static void Restore()
		{
			if (hidden.Count == 0)
			{
				return;
			}
			foreach (var r in hidden)
			{
				if (r != null)
				{
					r.forceRenderingOff = false;
				}
			}
			hidden.Clear();
		}
	}
}
