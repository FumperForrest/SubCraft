using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Player
{
	/// <summary>
	/// While a Minecraft screen is open (inventory, chat, a chest), it owns the mouse like
	/// Subnautica's PDA does: this handler sits on top of Subnautica's input stack (so the diver's
	/// look and controls stop), the cursor is free, and its position goes to Minecraft.
	/// </summary>
	public sealed class McScreenInput : IInputHandler
	{
		private static readonly McScreenInput Instance = new McScreenInput();
		private static bool pushed;
		private static Vector3 lastMouse = new Vector3(-1, -1, 0);

		public static bool Active => pushed;

		public static void Frame(LinkDriver driver)
		{
			bool want = PlayerPuppet.Active && driver.HaveMc && driver.Mc.Has(Proto.McScreenOpen) && !HostState.MenuOpen();
			if (want && !pushed && InputHandlerStack.main != null)
			{
				pushed = true;
				InputHandlerStack.main.Push(Instance);
			}
			if (pushed)
			{
				var m = Input.mousePosition;
				if (m != lastMouse)
				{
					lastMouse = m;
					// Unity: pixels from the bottom left; overlay: pixels from the top left.
					driver.Link.View.PushInput(Proto.InCursor, 0, Mathf.RoundToInt(m.x), Mathf.RoundToInt(Screen.height - m.y));
				}
			}
		}

		public bool HandleInput()
		{
			var driver = LinkDriver.Instance;
			bool keep = driver != null && PlayerPuppet.Active && driver.HaveMc && driver.Mc.Has(Proto.McScreenOpen);
			if (!keep)
			{
				pushed = false;
				lastMouse = new Vector3(-1, -1, 0);
				return false; // pops us; Subnautica's handler below takes the cursor back
			}
			UWE.Utils.lockCursor = false;
			return true;
		}

		public bool HandleLateInput() => pushed;

		public void OnFocusChanged(InputFocusMode mode)
		{
			if (mode == InputFocusMode.Add || mode == InputFocusMode.Restore)
			{
				UWE.Utils.lockCursor = false;
			}
		}
	}
}
