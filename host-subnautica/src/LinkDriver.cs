using System;
using System.Collections.Generic;
using SubCraft.Link;
using SubCraft.Player;
using UnityEngine;

namespace SubCraft
{
	/// <summary>
	/// Per-frame glue on the host side. Update: heartbeat, read Minecraft, forward input, move the
	/// puppet (before the camera's LateUpdate). LateUpdate: publish HostState with this frame's look.
	/// </summary>
	public sealed class LinkDriver : MonoBehaviour
	{
		public static LinkDriver Instance { get; private set; }

		public HostLink Link { get; private set; }
		public bool McLinked { get; private set; }
		public LinkView.McState Mc;
		public bool HaveMc;
		public uint TeleportSeq { get; private set; }
		public World.CollisionHarvester Harvester { get; } = new World.CollisionHarvester();
		public World.DryVolumes Dry { get; } = new World.DryVolumes();

		private LinkView.HostState host;
		private readonly List<LinkView.McEvent> events = new List<LinkView.McEvent>();
		private float lastDiag;
		private bool wasInGame;

		private void Awake()
		{
			Instance = this;
			try
			{
				Link = new HostLink(Platform.LinkFile());
				Plugin.Log.LogInfo($"SubCraft: link file ready at {Link.Path} ({Proto.MappingBytes >> 20} MiB)");
			}
			catch (Exception e)
			{
				Plugin.Log.LogError($"SubCraft: couldn't create the link file: {e}");
			}
		}

		private void OnDestroy()
		{
			Link?.Dispose();
		}

		private void Update()
		{
			if (Link == null)
			{
				return;
			}
			long now = Platform.MonoNanos();
			var view = Link.View;
			view.HostHeartbeat(now);

			bool alive = Link.McAlive(now);
			if (alive != McLinked)
			{
				McLinked = alive;
				Plugin.Log.LogInfo($"SubCraft: Minecraft link {(alive ? "up" : "down")} (mc pid {view.McPid})");
				if (alive)
				{
					Harvester.Reset(view);
					Dry.Reset();
					RequestTeleport("link up");
				}
				else
				{
					PlayerPuppet.Release("Minecraft heartbeat lost");
				}
			}
			HaveMc = McLinked && view.ReadMcState(out Mc);

			events.Clear();
			view.DrainEvents(events);
			foreach (var ev in events)
			{
				if (ev.Type == Proto.EvPlayerDied)
				{
					Plugin.Log.LogInfo("SubCraft: Minecraft player died (host death flow: Phase 5)");
					RequestTeleport("Minecraft respawn");
				}
			}

			bool inGame = HostState.InGame();
			if (inGame && !wasInGame)
			{
				RequestTeleport("entered game");
			}
			wasInGame = inGame;

			// In a vehicle the keys fly the vehicle; Minecraft's player must not walk off on its own.
			InputCapture.Frame(view, McLinked && inGame && !HostState.MenuOpen() && !HostState.Piloting());
			McScreenInput.Frame(this);
			if (McLinked && inGame && global::Player.main != null)
			{
				var velocityMc = Vector3.zero;
				if (HaveMc && Mc.TickMs > 0f)
				{
					float perSecond = 1000f / Mc.TickMs;
					velocityMc = new Vector3((float)(Mc.CurX - Mc.PrevX), (float)(Mc.CurY - Mc.PrevY), (float)(Mc.CurZ - Mc.PrevZ)) * perSecond;
				}
				Harvester.Frame(view, global::Player.main.transform.position, velocityMc);
				Dry.Frame(view, Harvester.Epoch, global::Player.main.transform.position);
			}
			PlayerPuppet.Frame(this);
			Diag();
		}

		private void LateUpdate()
		{
			if (Link == null)
			{
				return;
			}
			Player.DiverHider.LateFrame();
			HostState.Fill(ref host, TeleportSeq, PlayerPuppet.HostFeetMc);
			Link.View.WriteHostState(host);
		}

		/// <summary>The host decides where the player is: Minecraft moves its player to the host's feet.</summary>
		public void RequestTeleport(string why)
		{
			TeleportSeq++;
			PlayerPuppet.OnTeleportRequested();
			Plugin.Log.LogInfo($"SubCraft: teleport #{TeleportSeq} ({why})");
		}

		private void Diag()
		{
			if (!Plugin.Diagnostics.Value || Time.unscaledTime - lastDiag < 5f)
			{
				return;
			}
			lastDiag = Time.unscaledTime;
			long beatAge = (Platform.MonoNanos() - Link.View.McHeartbeat) / 1_000_000;
			Plugin.Log.LogInfo(
				$"SubCraft diag: linked {McLinked} mcBeatAge {beatAge} ms puppet {PlayerPuppet.Active} " +
				(HaveMc ? $"mc frame {Mc.FrameCounter} pos ({Mc.X:F2}, {Mc.Y:F2}, {Mc.Z:F2}) flags {Convert.ToString(Mc.Flags, 2)} ack {Mc.TeleportAck}/{TeleportSeq} " : "no mc state ") +
				$"inGame {HostState.InGame()} menu {HostState.MenuOpen()} loading {HostState.Loading()} fps {1f / Mathf.Max(Time.unscaledDeltaTime, 1e-4f):F0}");
		}
	}
}
