using SubCraft.Link;
using UnityEngine;

namespace SubCraft
{
	/// <summary>What the host tells Minecraft each frame: game state flags, the player's feet, the look, viewport, time.</summary>
	public static class HostState
	{
		public static bool Loading() => WaitScreen.IsWaiting || uGUI.isIntro;

		public static bool InGame() => global::Player.main != null && uGUI.isMainLevel && !Loading();

		/// <summary>Piloting a vehicle or a chair, or seated: Subnautica's locked modes own the player.</summary>
		public static bool Piloting() => global::Player.main != null && global::Player.main.mode != global::Player.Mode.Normal;

		public static bool MenuOpen()
		{
			var player = global::Player.main;
			if (player == null)
			{
				return true;
			}
			if (IngameMenu.main != null && IngameMenu.main.selected)
			{
				return true;
			}
			var pda = player.GetPDA();
			return (pda != null && pda.isOpen) || player.cinematicModeActive || (DevConsole.instance != null && DevConsole.instance.state);
		}

		public static void Fill(ref LinkView.HostState s, uint teleportSeq, Vector3 feetMc)
		{
			uint flags = 0;
			if (InGame())
			{
				flags |= Proto.HostInGame;
			}
			if (MenuOpen())
			{
				flags |= Proto.HostMenuOpen;
			}
			if (Loading())
			{
				flags |= Proto.HostLoading;
			}
			if (Plugin.UnifiedHud.Value)
			{
				flags |= Proto.HostUnifiedHud;
			}
			s.Flags = flags;
			s.WorldId = 1;
			s.X = feetMc.x;
			s.Y = feetMc.y;
			s.Z = feetMc.z;
			var cam = MainCamera.camera;
			if (cam != null)
			{
				// The look, not where the camera points: in Minecraft's front view it faces the player.
				Vector3 f = Player.CharacterCamera.HaveEye ? Player.CharacterCamera.LookForward : cam.transform.forward;
				Link.Coords.LookToMc(f.x, f.y, f.z, out s.Yaw, out s.Pitch);
			}
			s.TeleportSeq = teleportSeq;
			s.ViewportW = (uint)Screen.width;
			s.ViewportH = (uint)Screen.height;
			// GetDayNightCycleTime, not GetDayScalar: Subnautica's sun is up 0.125..0.875 of the raw
			// scalar; the cycle time puts sunrise at 0.25 and sunset at 0.75, like Minecraft's day.
			s.DayFraction = DayNightCycle.main != null ? DayNightCycle.main.GetDayNightCycleTime() : 0.5f;
			var player = global::Player.main;
			if (player != null)
			{
				if (player.IsUnderwater())
				{
					flags |= Proto.HostUnderwater;
				}
				if (player.IsInside())
				{
					flags |= Proto.HostInside;
				}
				s.Flags = flags;
				s.Oxygen = player.GetOxygenAvailable();
				s.OxygenCapacity = player.GetOxygenCapacity();
			}
		}
	}
}
