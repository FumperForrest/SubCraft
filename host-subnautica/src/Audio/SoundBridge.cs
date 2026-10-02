using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Audio
{
	/// <summary>
	/// The host's half of the sound mix (MISSION.md 3.4): Minecraft's gameplay sounds play through
	/// Subnautica's FMOD, in a channel group of their own under one of its mixer buses, so its
	/// volume sliders and PDA pause apply to them like to its own effects. Subnautica muffles
	/// underwater inside each of its events (parameters such as "depth"), not on the buses (verified:
	/// the SFX chain's filters stay at 22 kHz at 11 m depth), so Minecraft's sounds get a low-pass of
	/// their own that closes while the camera is in open water. Files arrive once as kRenSound (Ogg
	/// Vorbis, decoded by FMOD into samples); plays, updates and stops arrive as events.
	/// </summary>
	public static class SoundBridge
	{
		private static readonly Dictionary<uint, FMOD.Sound> files = new Dictionary<uint, FMOD.Sound>();
		private static readonly Dictionary<uint, FMOD.Channel> channels = new Dictionary<uint, FMOD.Channel>();
		private static readonly List<(LinkView.McEvent ev, float time)> waiting = new List<(LinkView.McEvent, float)>();
		private static FMOD.ChannelGroup group;
		private static FMOD.DSP lowpass;
		private static bool haveGroup;
		private static float cutoff = OpenCutoff;

		/// <summary>Low-pass cutoffs (Hz): dry air, and the camera in open water.</summary>
		private const float OpenCutoff = 22000f, UnderwaterCutoff = 900f;
		private static string groupBus;
		public static int Played, Failed;
		public static int FileCount => files.Count;

		/// <summary>A sound file (kRenSound).</summary>
		public static unsafe void Load(uint id, byte* data, int bytes)
		{
			var copy = new byte[bytes];
			Marshal.Copy((IntPtr)data, copy, 0, bytes);
			var info = new FMOD.CREATESOUNDEXINFO();
			info.cbsize = Marshal.SizeOf(info);
			info.length = (uint)bytes;
			var mode = FMOD.MODE.OPENMEMORY | FMOD.MODE.CREATESAMPLE | FMOD.MODE._3D | FMOD.MODE._3D_LINEARROLLOFF | FMOD.MODE.LOOP_OFF;
			var r = FMODUnity.RuntimeManager.CoreSystem.createSound(copy, mode, ref info, out FMOD.Sound sound);
			if (r != FMOD.RESULT.OK)
			{
				Failed++;
				Plugin.Log.LogWarning($"SubCraft: FMOD couldn't load Minecraft sound {id} ({bytes} bytes): {r}");
				return;
			}
			if (files.TryGetValue(id, out var old))
			{
				old.release();
			}
			files[id] = sound;
		}

		public static void Event(in LinkView.McEvent ev)
		{
			switch (ev.Type)
			{
				case Proto.EvSoundPlay:
					if (!Play(ev))
					{
						waiting.Add((ev, Time.unscaledTime)); // its file is still on the way (other ring)
					}
					break;
				case Proto.EvSoundUpdate:
					if (channels.TryGetValue(ev.Id, out var ch))
					{
						Apply(ch, ev);
					}
					break;
				case Proto.EvSoundStop:
					if (ev.Id == 0)
					{
						StopAll();
					}
					else if (channels.TryGetValue(ev.Id, out var stop))
					{
						stop.stop();
						channels.Remove(ev.Id);
					}
					break;
			}
		}

		/// <summary>Per frame: plays whose file has arrived since; forget finished channels.</summary>
		public static void Frame()
		{
			Muffle();
			for (int i = waiting.Count - 1; i >= 0; i--)
			{
				if (Play(waiting[i].ev) || Time.unscaledTime - waiting[i].time > 1f)
				{
					waiting.RemoveAt(i);
				}
			}
			if (channels.Count > 0 && Time.frameCount % 30 == 0)
			{
				var done = new List<uint>();
				foreach (var kv in channels)
				{
					if (kv.Value.isPlaying(out bool playing) != FMOD.RESULT.OK || !playing)
					{
						done.Add(kv.Key);
					}
				}
				foreach (var id in done)
				{
					channels.Remove(id);
				}
			}
		}

		/// <summary>Minecraft went away or a new one came: its file ids mean nothing any more.</summary>
		public static void Reset()
		{
			StopAll();
			waiting.Clear();
			foreach (var s in files.Values)
			{
				s.release();
			}
			files.Clear();
		}

		public static void StopAll()
		{
			foreach (var ch in channels.Values)
			{
				ch.stop();
			}
			channels.Clear();
		}

		private static bool Play(in LinkView.McEvent ev)
		{
			uint file = ev.Flags & 0xFFFFFF;
			if (!files.TryGetValue(file, out var sound))
			{
				return false;
			}
			if (!Group(out var cg))
			{
				return false;
			}
			if (FMODUnity.RuntimeManager.CoreSystem.playSound(sound, cg, true, out FMOD.Channel ch) != FMOD.RESULT.OK)
			{
				Failed++;
				return true;
			}
			bool relative = (ev.Flags & Proto.SoundRelative) != 0;
			var mode = (relative ? FMOD.MODE._2D : FMOD.MODE._3D | FMOD.MODE._3D_LINEARROLLOFF)
				| ((ev.Flags & Proto.SoundLoop) != 0 ? FMOD.MODE.LOOP_NORMAL : FMOD.MODE.LOOP_OFF);
			ch.setMode(mode);
			Apply(ch, ev);
			ch.setPaused(false);
			channels[ev.Id] = ch;
			Played++;
			return true;
		}

		private static void Apply(FMOD.Channel ch, in LinkView.McEvent ev)
		{
			float range = (ev.Extra >> 16) / 16f;
			float pitch = (ev.Extra & 0xFFFF) / 10000f;
			// Minecraft (OpenAL): linear falloff from full at the source to silence at range.
			ch.set3DMinMaxDistance(0.01f, Mathf.Max(range, 0.02f));
			var pos = new FMOD.VECTOR { x = ev.A, y = ev.B, z = -ev.C };
			var vel = new FMOD.VECTOR();
			ch.set3DAttributes(ref pos, ref vel);
			ch.setVolume(ev.D);
			if (pitch > 0f)
			{
				ch.setPitch(pitch);
			}
		}

		/// <summary>Underwater, the low-pass glides closed (about a quarter second); in air or a dry interior, open.</summary>
		private static void Muffle()
		{
			if (!haveGroup || !lowpass.hasHandle())
			{
				return;
			}
			var cam = MainCamera.camera;
			var player = global::Player.main;
			bool under = cam != null && player != null && !player.IsInside() && Ocean.GetDepthOf(cam.gameObject) > 0f;
			float target = under ? UnderwaterCutoff : OpenCutoff;
			// Glide in log space, like an ear: equal steps per octave.
			float next = Mathf.Exp(Mathf.MoveTowards(Mathf.Log(cutoff), Mathf.Log(target), Time.unscaledDeltaTime * 12f));
			if (Mathf.Abs(next - cutoff) > 0.5f)
			{
				cutoff = next;
				lowpass.setParameterFloat((int)FMOD.DSP_LOWPASS.CUTOFF, cutoff);
			}
		}

		/// <summary>Minecraft's own channel group (with its low-pass) under the configured Subnautica bus.</summary>
		private static bool Group(out FMOD.ChannelGroup cg)
		{
			string path = Plugin.SoundBus.Value;
			if (haveGroup && groupBus == path)
			{
				cg = group;
				return true;
			}
			if (!BusGroup(path, out var parent))
			{
				cg = default;
				return false;
			}
			var core = FMODUnity.RuntimeManager.CoreSystem;
			if (!group.hasHandle())
			{
				core.createChannelGroup("SubCraft Minecraft", out group);
				if (core.createDSPByType(FMOD.DSP_TYPE.LOWPASS, out lowpass) == FMOD.RESULT.OK)
				{
					lowpass.setParameterFloat((int)FMOD.DSP_LOWPASS.CUTOFF, cutoff);
					group.addDSP(0, lowpass);
				}
			}
			parent.addGroup(group);
			haveGroup = true;
			groupBus = path;
			cg = group;
			return true;
		}

		/// <summary>The channel group of the configured Subnautica bus (master if it isn't there).</summary>
		private static bool BusGroup(string path, out FMOD.ChannelGroup cg)
		{
			cg = default;
			var studio = FMODUnity.RuntimeManager.StudioSystem;
			if (!string.IsNullOrEmpty(path) && studio.getBus(path, out FMOD.Studio.Bus bus) == FMOD.RESULT.OK)
			{
				// A bus only has a channel group while something plays on it, unless it is locked.
				bus.lockChannelGroup();
				FMODUnity.RuntimeManager.StudioSystem.flushCommands();
				if (bus.getChannelGroup(out cg) == FMOD.RESULT.OK)
				{
					Plugin.Log.LogInfo($"SubCraft: Minecraft sounds play on {path}");
					return true;
				}
			}
			if (FMODUnity.RuntimeManager.CoreSystem.getMasterChannelGroup(out cg) == FMOD.RESULT.OK)
			{
				Plugin.Log.LogWarning($"SubCraft: bus '{path}' not available; Minecraft sounds play on the master channel group");
				return true;
			}
			return false;
		}

		/// <summary>
		/// Harness: the effect chain from the bus Minecraft's sounds use up to the master, with each
		/// effect's float parameters (to see Subnautica's underwater filter change).
		/// </summary>
		public static string DescribeChain()
		{
			var sb = new System.Text.StringBuilder();
			if (!Group(out var cg))
			{
				return "no channel group";
			}
			for (int depth = 0; depth < 16 && cg.hasHandle(); depth++)
			{
				cg.getName(out string name, 64);
				cg.getNumDSPs(out int n);
				sb.Append(name).Append(':');
				for (int i = 0; i < n; i++)
				{
					if (cg.getDSP(i, out FMOD.DSP dsp) != FMOD.RESULT.OK)
					{
						continue;
					}
					dsp.getType(out FMOD.DSP_TYPE type);
					dsp.getBypass(out bool bypass);
					sb.Append(' ').Append(type).Append(bypass ? "(bypassed)" : "").Append('[');
					dsp.getNumParameters(out int np);
					for (int k = 0; k < Math.Min(np, 6); k++)
					{
						if (dsp.getParameterFloat(k, out float v) == FMOD.RESULT.OK)
						{
							sb.Append(v.ToString("0.##")).Append(k + 1 < Math.Min(np, 6) ? "," : "");
						}
					}
					sb.Append(']');
				}
				sb.Append('\n');
				if (cg.getParentGroup(out var parent) != FMOD.RESULT.OK || !parent.hasHandle())
				{
					break;
				}
				cg = parent;
			}
			return sb.ToString();
		}

		/// <summary>Harness: Subnautica's buses (for picking Sound.Bus).</summary>
		public static string ListBuses()
		{
			var sb = new System.Text.StringBuilder();
			FMODUnity.RuntimeManager.StudioSystem.getBankList(out FMOD.Studio.Bank[] banks);
			var seen = new HashSet<string>();
			foreach (var bank in banks)
			{
				if (bank.getBusList(out FMOD.Studio.Bus[] buses) != FMOD.RESULT.OK)
				{
					continue;
				}
				foreach (var bus in buses)
				{
					if (bus.getPath(out string p) == FMOD.RESULT.OK && seen.Add(p))
					{
						sb.Append(p).Append('\n');
					}
				}
			}
			return sb.ToString();
		}
	}
}
