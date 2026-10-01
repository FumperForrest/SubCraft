using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Rendering;

namespace SubCraft.Dev
{
	/// <summary>
	/// Render-path reconnaissance (Phase 0b): how Subnautica draws its world, so Phase 0c can make
	/// Minecraft geometry use the same path. Camera setup, image effects, command buffers, quality
	/// and fog settings, and the shaders/keywords on nearby terrain, base pieces, creatures, the
	/// player's tools and everything else, grouped by category.
	/// </summary>
	public static class RenderRecon
	{
		public static JObject Capture(float radius = 80f)
		{
			var o = new JObject();
			var cam = MainCamera.camera;
			if (cam != null)
			{
				var events = new JObject();
				foreach (CameraEvent ev in System.Enum.GetValues(typeof(CameraEvent)))
				{
					var bufs = cam.GetCommandBuffers(ev);
					if (bufs.Length > 0)
					{
						events[ev.ToString()] = new JArray(bufs.Select(b => b.name));
					}
				}
				o["camera"] = new JObject
				{
					["name"] = cam.name,
					["renderingPath"] = cam.renderingPath.ToString(),
					["actualRenderingPath"] = cam.actualRenderingPath.ToString(),
					["allowHDR"] = cam.allowHDR,
					["allowMSAA"] = cam.allowMSAA,
					["clearFlags"] = cam.clearFlags.ToString(),
					["depthTextureMode"] = cam.depthTextureMode.ToString(),
					["near"] = cam.nearClipPlane,
					["far"] = cam.farClipPlane,
					["fov"] = cam.fieldOfView,
					["cullingMask"] = cam.cullingMask,
					["components"] = new JArray(cam.GetComponents<Component>().Select(c => c.GetType().FullName + (c is Behaviour b ? (b.enabled ? "" : " (disabled)") : ""))),
					["commandBuffers"] = events,
				};
				o["otherCameras"] = new JArray(Camera.allCameras.Where(c => c != cam).Select(c => $"{c.name} depth {c.depth} path {c.actualRenderingPath} mask {c.cullingMask} target {(c.targetTexture != null ? c.targetTexture.name : "screen")}"));
			}
			o["graphics"] = new JObject
			{
				["device"] = SystemInfo.graphicsDeviceType.ToString(),
				["deviceVersion"] = SystemInfo.graphicsDeviceVersion,
				["renderPipeline"] = GraphicsSettings.renderPipelineAsset != null ? GraphicsSettings.renderPipelineAsset.name : "built-in",
				["colorSpace"] = QualitySettings.activeColorSpace.ToString(),
				["quality"] = QualitySettings.names[QualitySettings.GetQualityLevel()],
				["pixelLightCount"] = QualitySettings.pixelLightCount,
				["shadows"] = QualitySettings.shadows.ToString(),
				["shadowDistance"] = QualitySettings.shadowDistance,
				["shadowCascades"] = QualitySettings.shadowCascades,
				["fog"] = RenderSettings.fog,
				["fogMode"] = RenderSettings.fogMode.ToString(),
				["fogColor"] = RenderSettings.fogColor.ToString(),
				["ambientMode"] = RenderSettings.ambientMode.ToString(),
				["sun"] = RenderSettings.sun != null ? RenderSettings.sun.name : null,
			};
			o["graphicsTier"] = new JArray(Graphics.activeTier.ToString());

			var origin = cam != null ? cam.transform.position : Vector3.zero;
			var byCategory = new Dictionary<string, Dictionary<string, JObject>>();
			var lights = new JArray();
			foreach (var r in Object.FindObjectsOfType<Renderer>())
			{
				if (!r.enabled || !r.gameObject.activeInHierarchy || (r.bounds.ClosestPoint(origin) - origin).sqrMagnitude > radius * radius)
				{
					continue;
				}
				string cat = Category(r);
				if (!byCategory.TryGetValue(cat, out var shaders))
				{
					byCategory[cat] = shaders = new Dictionary<string, JObject>();
				}
				foreach (var m in r.sharedMaterials)
				{
					if (m == null || m.shader == null)
					{
						continue;
					}
					string key = m.shader.name + " | " + string.Join(" ", m.shaderKeywords.OrderBy(k => k));
					if (!shaders.TryGetValue(key, out var entry))
					{
						entry = new JObject
						{
							["shader"] = m.shader.name,
							["keywords"] = new JArray(m.shaderKeywords.OrderBy(k => k)),
							["renderQueue"] = m.renderQueue,
							["textures"] = new JArray(m.GetTexturePropertyNames().Where(t => m.GetTexture(t) != null)),
							["examples"] = new JArray(),
							["count"] = 0,
						};
						shaders[key] = entry;
					}
					entry["count"] = (int)entry["count"] + 1;
					var ex = (JArray)entry["examples"];
					if (ex.Count < 4)
					{
						ex.Add($"{Path(r.transform)} [{r.GetType().Name}, material {m.name}, shadows {r.shadowCastingMode}/{r.receiveShadows}]");
					}
				}
			}
			var cats = new JObject();
			foreach (var kv in byCategory)
			{
				cats[kv.Key] = new JArray(kv.Value.Values.OrderByDescending(e => (int)e["count"]));
			}
			o["renderers"] = cats;
			foreach (var l in Object.FindObjectsOfType<Light>())
			{
				if (l.enabled && (l.type == LightType.Directional || (l.transform.position - origin).sqrMagnitude < radius * radius))
				{
					lights.Add($"{Path(l.transform)}: {l.type} {l.color} intensity {l.intensity} range {l.range} shadows {l.shadows} mode {l.renderMode}");
				}
			}
			o["lights"] = lights;
			return o;
		}

		private static string Category(Renderer r)
		{
			if (r.GetComponentInParent<global::Player>() != null) return "player";
			if (r.GetComponentInParent<Creature>() != null) return "creature";
			if (r.GetComponentInParent<Base>() != null || r.GetComponentInParent<BaseCell>() != null) return "base";
			if (r.GetComponentInParent<SubRoot>() != null || r.GetComponentInParent<Vehicle>() != null) return "vehicle";
			if (r.GetComponentInParent<EscapePod>() != null) return "lifepod";
			string path = Path(r.transform).ToLowerInvariant();
			if (path.Contains("chunk") || path.Contains("voxeland") || path.Contains("clipmap") || path.Contains("terrain")) return "terrain";
			if (r is ParticleSystemRenderer) return "particles";
			if (path.Contains("water") || path.Contains("ocean")) return "water";
			return "other";
		}

		private static string Path(Transform t)
		{
			var parts = new List<string>();
			for (int i = 0; t != null && i < 5; t = t.parent, i++)
			{
				parts.Add(t.name);
			}
			parts.Reverse();
			return string.Join("/", parts);
		}
	}
}
