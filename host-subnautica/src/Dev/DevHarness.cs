using System;
using System.Collections;
using System.IO;
using System.Text;
using Newtonsoft.Json.Linq;
using SubCraft.Link;
using SubCraft.Player;
using UnityEngine;

namespace SubCraft.Dev
{
	/// <summary>
	/// Dev harness (MISSION.md rule 12): executes JSON-lines commands appended to
	/// $TMPDIR/subcraft/cmd.jsonl, one at a time, and writes results, screenshots and state dumps to
	/// $TMPDIR/subcraft/out/. tools/sn_cmd.py drives it; tools/scenarios/*.jsonl are scripts.
	///
	/// Commands: wait {seconds} | screenshot {name} | dump {name} | recon {name} | console {text}
	/// | teleport {x,y,z} (Unity) | look {yaw,pitch} (Unity degrees) | time {value 0..1}
	/// | key {glfw, down} (to Minecraft) | newgame {mode} | load {slot} | save | skipintro
	/// | waitingame {timeout} | quit
	/// </summary>
	public sealed class DevHarness : MonoBehaviour
	{
		private string cmdFile;
		private string outDir;
		private long offset;
		private bool busy;
		private int seq;
		private float lastPoll;

		private void Awake()
		{
			string dir = Platform.SharedDir();
			cmdFile = Path.Combine(dir, "cmd.jsonl");
			outDir = Path.Combine(dir, "out");
			Directory.CreateDirectory(outDir);
			// Commands written before this game started are stale.
			offset = File.Exists(cmdFile) ? new FileInfo(cmdFile).Length : 0;
			Plugin.Log.LogInfo($"SubCraft harness: watching {cmdFile}, output in {outDir}");
			Result("ready", true, "harness started");
		}

		private void Update()
		{
			if (busy || Time.unscaledTime - lastPoll < 0.1f)
			{
				return;
			}
			lastPoll = Time.unscaledTime;
			string line = NextLine();
			if (line == null)
			{
				return;
			}
			JObject cmd;
			try
			{
				cmd = JObject.Parse(line);
			}
			catch (Exception e)
			{
				Result("parse", false, e.Message);
				return;
			}
			StartCoroutine(Run(cmd));
		}

		private string NextLine()
		{
			if (!File.Exists(cmdFile))
			{
				return null;
			}
			long len = new FileInfo(cmdFile).Length;
			if (len < offset)
			{
				offset = 0; // truncated: a new script
			}
			if (len == offset)
			{
				return null;
			}
			using (var fs = new FileStream(cmdFile, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
			{
				fs.Seek(offset, SeekOrigin.Begin);
				var bytes = new byte[Math.Min(len - offset, 65536)];
				int n = fs.Read(bytes, 0, bytes.Length);
				int nl = Array.IndexOf(bytes, (byte)'\n', 0, n);
				if (nl < 0)
				{
					return null; // partial line: wait for the rest
				}
				offset += nl + 1;
				string line = Encoding.UTF8.GetString(bytes, 0, nl).Trim();
				return line.Length == 0 ? NextLine() : line;
			}
		}

		private IEnumerator Run(JObject cmd)
		{
			busy = true;
			string name = (string)cmd["cmd"] ?? "?";
			bool ok = true;
			string msg = "";
			IEnumerator inner = null;
			try
			{
				switch (name)
				{
					case "wait":
						inner = Wait((float?)cmd["seconds"] ?? 1f);
						break;
					case "screenshot":
						string shot = Path.Combine(outDir, ((string)cmd["name"] ?? "shot") + ".png");
						ScreenCapture.CaptureScreenshot(shot);
						msg = shot;
						inner = Wait(0.5f); // written at the end of the frame
						break;
					case "dump":
						msg = Write(((string)cmd["name"] ?? "state") + ".json", StateDump.Capture().ToString());
						break;
					case "recon":
						msg = Write(((string)cmd["name"] ?? "recon") + ".json", RenderRecon.Capture().ToString());
						break;
					case "console":
						ok = DevConsole.SendConsoleCommand((string)cmd["text"]);
						msg = (string)cmd["text"];
						break;
					case "teleport":
						var p = new Vector3((float)cmd["x"], (float)cmd["y"], (float)cmd["z"]);
						global::Player.main.SetPosition(p);
						msg = p.ToString();
						break;
					case "look":
						var camControl = MainCameraControl.main;
						global::Player.main.transform.rotation = Quaternion.Euler(0f, (float?)cmd["yaw"] ?? 0f, 0f);
						camControl.rotationX = 0f;
						camControl.rotationY = -((float?)cmd["pitch"] ?? 0f);
						break;
					case "time":
						DayNightCycle.main.SetDayNightTime((float)cmd["value"]);
						break;
					case "key":
						InputCapture.Inject(LinkDriver.Instance.Link.View, (int)cmd["glfw"], (bool?)cmd["down"] ?? true);
						break;
					case "newgame":
						inner = NewGame((string)cmd["mode"] ?? "creative");
						break;
					case "load":
						inner = Load((string)cmd["slot"] ?? Plugin.DevSlot.Value);
						break;
					case "save":
						string slot = SaveLoadManager.main.GetCurrentSlot();
						if (string.IsNullOrEmpty(Plugin.DevSlot.Value) || slot != Plugin.DevSlot.Value)
						{
							ok = false;
							msg = $"refusing to save slot '{slot}': not the dev slot '{Plugin.DevSlot.Value}'";
						}
						else
						{
							IngameMenu.main.SaveGame();
							msg = slot;
							inner = Wait(3f);
						}
						break;
					case "skipintro":
						if (uGUI.isIntro)
						{
							uGUI.main.intro.Stop(true);
							msg = "intro stopped";
						}
						else
						{
							msg = "no intro showing";
						}
						break;
					case "loaddump":
						msg = LoadDump(cmd);
						break;
					case "unloaddump":
						if (loaded != null) Destroy(loaded);
						loaded = null;
						break;
					case "matset":
						Color? col = cmd["color"] is JArray ca ? new Color((float)ca[0], (float)ca[1], (float)ca[2], ca.Count > 3 ? (float)ca[3] : 1f) : (Color?)null;
						msg = $"{Render.MaterialFactory.Set((string)cmd["keyword"], (bool?)cmd["on"], (string)cmd["property"], (float?)cmd["value"], col, (bool?)cmd["glowOnly"] ?? false)} materials";
						break;
					case "lightset":
						if (cmd["rangePerLevel"] != null) Render.BlockLights.RangePerLevel = (float)cmd["rangePerLevel"];
						if (cmd["intensity"] != null) Render.BlockLights.Intensity = (float)cmd["intensity"];
						if (cmd["lightmapStrength"] != null) Render.MaterialFactory.LightmapStrength = (float)cmd["lightmapStrength"];
						msg = "applies to the next loaddump";
						break;
					case "rendinfo":
						msg = Write("rendinfo.json", RendInfo((string)cmd["match"] ?? "", (int?)cmd["max"] ?? 3).ToString());
						break;
					case "matinfo":
						msg = Write("matinfo.json", Render.MaterialFactory.Describe().ToString());
						break;
					case "equip":
						msg = Equip((string)cmd["tech"], (bool?)cmd["lights"]);
						break;
					case "waitingame":
						inner = WaitInGame((float?)cmd["timeout"] ?? 180f);
						break;
					case "quit":
						Application.Quit();
						break;
					default:
						ok = false;
						msg = "unknown command";
						break;
				}
			}
			catch (Exception e)
			{
				ok = false;
				msg = e.ToString();
			}
			if (inner != null)
			{
				yield return inner;
				if (inner.Current is string error)
				{
					ok = false;
					msg = error;
				}
			}
			Result(name, ok, msg);
			busy = false;
		}

		private GameObject loaded;

		private static readonly string[] SkyVectors = { "_ExposureIBL", "_ExposureLM", "_SkyMin", "_SkyMax", "_SH0", "_SH1", "_SH2", "_SH3", "_SH4", "_BlendWeightIBL", "_Outdoors", "_AffectedByDayNightCycle" };
		private static readonly string[] MatProps = { "_Lightmap", "_LightmapStrength", "_EnableLightmap", "_Illum", "_EnableGlow", "_GlowStrength", "_SpecInt", "_Shininess", "_EnableCutOff", "_Cutoff", "_Color" };

		/// <summary>rendinfo {match, max}: property block + material state of renderers whose path contains match.</summary>
		private static JObject RendInfo(string match, int max)
		{
			var o = new JObject();
			var cam = MainCamera.camera.transform.position;
			int n = 0;
			foreach (var r in FindObjectsOfType<MeshRenderer>())
			{
				string path = r.transform.parent != null ? r.transform.parent.name + "/" + r.name : r.name;
				if (path.IndexOf(match, StringComparison.OrdinalIgnoreCase) < 0 || (r.bounds.center - cam).sqrMagnitude > 120 * 120)
				{
					continue;
				}
				var block = new MaterialPropertyBlock();
				r.GetPropertyBlock(block);
				var pb = new JObject { ["empty"] = block.isEmpty };
				foreach (var name in SkyVectors)
				{
					pb[name] = block.GetVector(name).ToString("F3");
				}
				var tex = block.GetTexture("_SpecCubeIBL");
				pb["_SpecCubeIBL"] = tex != null ? tex.name : "null";
				var mats = new JArray();
				foreach (var m in r.sharedMaterials)
				{
					if (m == null) continue;
					var mo = new JObject { ["name"] = m.name, ["shader"] = m.shader.name, ["keywords"] = string.Join(" ", m.shaderKeywords), ["queue"] = m.renderQueue };
					foreach (var p in MatProps)
					{
						if (!m.HasProperty(p)) continue;
						if (p == "_Lightmap" || p == "_Illum") mo[p] = m.GetTexture(p) != null ? m.GetTexture(p).name : "null";
						else if (p == "_Color") mo[p] = m.GetColor(p).ToString();
						else mo[p] = m.GetFloat(p);
					}
					mats.Add(mo);
				}
				o[$"{path} #{n}"] = new JObject { ["layer"] = r.gameObject.layer, ["lightProbes"] = r.lightProbeUsage.ToString(), ["block"] = pb, ["materials"] = mats };
				if (++n >= max) break;
			}
			return o;
		}

		/// <summary>
		/// loaddump {path, x, y, z, snap}: builds a capture dump in the world. The dump's minimum
		/// corner goes to Unity (x, y, z); with snap the floor is dropped onto whatever is below
		/// (seabed) by a ray cast down from y + 30.
		/// </summary>
		private string LoadDump(JObject cmd)
		{
			string path = (string)cmd["path"] ?? Path.Combine(outDir, "scene.scdump");
			var dump = Render.DumpReader.Read(path);
			float minX = float.MaxValue, minY = float.MaxValue, maxZ = float.MinValue;
			foreach (var b in dump.Batches)
			{
				foreach (var v in b.Vertices)
				{
					minX = Mathf.Min(minX, v.X);
					minY = Mathf.Min(minY, v.Y);
					maxZ = Mathf.Max(maxZ, v.Z); // MC max z = Unity min z
				}
			}
			var at = new Vector3((float)cmd["x"], (float)cmd["y"], (float)cmd["z"]);
			if ((bool?)cmd["snap"] ?? true)
			{
				var hits = Physics.RaycastAll(at + Vector3.up * 30f, Vector3.down, 200f);
				float best = float.MinValue;
				foreach (var h in hits)
				{
					if (h.collider.GetComponentInParent<global::Player>() == null && h.point.y > best)
					{
						best = h.point.y;
					}
				}
				if (best > float.MinValue)
				{
					at.y = best;
				}
			}
			// Unity = (mcX, mcY, -mcZ) + offset; the corner (minX, minY, maxZ) lands on `at`.
			var offset = new Vector3(at.x - minX, at.y - minY, at.z + maxZ);
			if (loaded != null) Destroy(loaded);
			loaded = Render.SceneBuilder.Build(dump, offset, Path.GetFileNameWithoutExtension(path));
			return $"built {Path.GetFileName(path)} at {at} (offset {offset}), {dump.Lights.Count} lights";
		}

		/// <summary>equip {tech, lights}: puts a tool from the inventory in the player's hand (dev console "item" gives one).</summary>
		private static string Equip(string tech, bool? lights)
		{
			if (!System.Enum.TryParse(tech, true, out TechType type))
			{
				throw new System.ArgumentException("unknown TechType " + tech);
			}
			var inv = Inventory.main;
			var items = inv.container.GetItems(type);
			if (items == null || items.Count == 0)
			{
				DevConsole.SendConsoleCommand("item " + tech);
				items = inv.container.GetItems(type);
				if (items == null || items.Count == 0)
				{
					throw new System.InvalidOperationException("couldn't get a " + tech);
				}
			}
			inv.quickSlots.Bind(0, items[0]);
			inv.quickSlots.SelectImmediate(0);
			if (lights.HasValue)
			{
				foreach (var t in global::Player.main.GetComponentsInChildren<ToggleLights>(true))
				{
					t.SetLightsActive(lights.Value);
				}
			}
			return $"holding {type}";
		}

		private static IEnumerator Wait(float seconds)
		{
			float until = Time.unscaledTime + seconds;
			while (Time.unscaledTime < until)
			{
				yield return null;
			}
		}

		private static IEnumerator WaitInGame(float timeout)
		{
			float until = Time.unscaledTime + timeout;
			while (!HostState.InGame())
			{
				if (Time.unscaledTime > until)
				{
					yield return "timed out waiting to be in game";
					yield break;
				}
				// A new game shows the lifepod intro; skip it so scripts reach the game.
				if (uGUI.isIntro)
				{
					uGUI.main.intro.Stop(true);
				}
				yield return null;
			}
		}

		private IEnumerator NewGame(string mode)
		{
			var menu = uGUI_MainMenu.main;
			if (menu == null)
			{
				yield return "not on the main menu";
				yield break;
			}
			string[] before = SaveLoadManager.main.GetActiveSlotNames();
			switch (mode)
			{
				case "survival": menu.OnButtonSurvival(); break;
				case "freedom": menu.OnButtonFreedom(); break;
				default: menu.OnButtonCreative(); break;
			}
			// The new game's slot becomes the dev slot: the only one the harness may save.
			float until = Time.unscaledTime + 30f;
			while (Time.unscaledTime < until)
			{
				string slot = SaveLoadManager.main.GetCurrentSlot();
				// A real slot ("slotNNNN"): new-game setup briefly uses a scratch name ("test").
				if (!string.IsNullOrEmpty(slot) && slot.StartsWith("slot") && Array.IndexOf(before, slot) < 0)
				{
					Plugin.DevSlot.Value = slot;
					Plugin.Log.LogInfo($"SubCraft harness: dev slot is {slot}");
					yield break;
				}
				yield return null;
			}
			yield return "new game slot never appeared";
		}

		private IEnumerator Load(string slot)
		{
			var menu = uGUI_MainMenu.main;
			if (menu == null || string.IsNullOrEmpty(slot))
			{
				yield return menu == null ? "not on the main menu" : "no dev slot configured";
				yield break;
			}
			var info = SaveLoadManager.main.GetGameInfo(slot);
			if (info == null)
			{
				yield return "no such slot " + slot;
				yield break;
			}
			StartCoroutine(menu.LoadGameAsync(slot, info.session, info.changeSet, info.gameMode));
		}

		private string Write(string file, string text)
		{
			string path = Path.Combine(outDir, file);
			File.WriteAllText(path, text);
			return path;
		}

		private void Result(string cmd, bool ok, string msg)
		{
			var o = new JObject { ["seq"] = ++seq, ["cmd"] = cmd, ["ok"] = ok, ["msg"] = msg, ["time"] = Time.unscaledTime };
			File.AppendAllText(Path.Combine(outDir, "results.jsonl"), o.ToString(Newtonsoft.Json.Formatting.None) + "\n");
			Plugin.Log.LogInfo($"SubCraft harness: {cmd} -> {(ok ? "ok" : "FAILED")} {msg}");
		}
	}
}
