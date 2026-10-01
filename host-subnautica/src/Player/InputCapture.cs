using System.Collections.Generic;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Player
{
	/// <summary>
	/// Reads the real keyboard and mouse through Unity's input and forwards them to Minecraft as
	/// GLFW events. Subnautica keeps Tab (PDA) and Esc (pause menu) and the mouse look; everything
	/// else also goes to Minecraft while in game. Phase 1 adds full routing (Minecraft screens get
	/// the cursor, Subnautica stops acting on routed keys).
	/// </summary>
	public static class InputCapture
	{
		private static readonly int[] Keys = BuildKeys();
		private static readonly HashSet<int> HostOnly = new HashSet<int> { (int)KeyCode.Tab, (int)KeyCode.Escape };
		private static readonly HashSet<int> HeldKeys = new HashSet<int>();
		private static readonly bool[] HeldButtons = new bool[5];
		private static bool routing;

		private static int[] BuildKeys()
		{
			var list = new List<int>(KeyMap.MappedUnityKeys);
			return list.ToArray();
		}

		public static void Frame(LinkView view, bool toMinecraft)
		{
			if (!toMinecraft)
			{
				if (routing)
				{
					routing = false;
					HeldKeys.Clear();
					for (int i = 0; i < HeldButtons.Length; i++) HeldButtons[i] = false;
					view.PushInput(Proto.InReleaseAll, 0);
				}
				return;
			}
			routing = true;
			int mods = KeyMap.GlfwMods(
				Input.GetKey(KeyCode.LeftShift) || Input.GetKey(KeyCode.RightShift),
				Input.GetKey(KeyCode.LeftControl) || Input.GetKey(KeyCode.RightControl),
				Input.GetKey(KeyCode.LeftAlt) || Input.GetKey(KeyCode.RightAlt),
				Input.GetKey(KeyCode.LeftCommand) || Input.GetKey(KeyCode.RightCommand));
			foreach (int unity in Keys)
			{
				if (HostOnly.Contains(unity))
				{
					continue;
				}
				bool down = Input.GetKey((KeyCode)unity);
				bool held = HeldKeys.Contains(unity);
				if (down == held)
				{
					continue;
				}
				if (down) HeldKeys.Add(unity); else HeldKeys.Remove(unity);
				view.PushInput(Proto.InKey, (ushort)KeyMap.ToGlfwKey(unity), down ? 1 : 0, mods, 0);
			}
			for (int button = 0; button < HeldButtons.Length; button++)
			{
				bool down = Input.GetMouseButton(button);
				if (down != HeldButtons[button])
				{
					HeldButtons[button] = down;
					view.PushInput(Proto.InMouseButton, (ushort)button, down ? 1 : 0, mods, 0);
				}
			}
			float wheel = Input.mouseScrollDelta.y;
			if (wheel != 0f)
			{
				view.PushInput(Proto.InScroll, 0, Mathf.RoundToInt(wheel * 120f));
			}
		}

		/// <summary>Harness: inject a key as if typed (GLFW code), bypassing the real keyboard.</summary>
		public static void Inject(LinkView view, int glfwKey, bool down)
		{
			view.PushInput(Proto.InKey, (ushort)glfwKey, down ? 1 : 0, 0, 0);
		}
	}
}
