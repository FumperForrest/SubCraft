using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Render
{
	/// <summary>
	/// Materials for Minecraft geometry made from the game's own MarmosetUBER materials (MISSION.md
	/// 3.2), so Subnautica's deferred lighting, caustics, flashlight and waterscape fog treat them like
	/// native props. Templates are picked from loaded materials by keyword set, so only shader
	/// variants that exist in the build are used.
	/// </summary>
	public static class MaterialFactory
	{
		public const string Shader = "MarmosetUBER";

		private static readonly Dictionary<string, Material> Templates = new Dictionary<string, Material>();

		/// <summary>Every material made for Minecraft geometry (the harness "matset" command tunes them live).</summary>
		public static readonly List<Material> All = new List<Material>();
		public static float LightmapStrength = 1f;

		/// <summary>matset: a keyword on/off, or a float/colour property, on every SubCraft material (glow ones only with glowOnly).</summary>
		public static int Set(string keyword, bool? on, string property, float? value, Color? color, bool glowOnly)
		{
			int n = 0;
			All.RemoveAll(m => m == null);
			foreach (var m in All)
			{
				if (glowOnly && !m.name.Contains("glow"))
				{
					continue;
				}
				if (keyword != null && on.HasValue)
				{
					if (on.Value) m.EnableKeyword(keyword); else m.DisableKeyword(keyword);
				}
				if (property != null && m.HasProperty(property))
				{
					if (value.HasValue) m.SetFloat(property, value.Value);
					if (color.HasValue) m.SetColor(property, color.Value);
				}
				n++;
			}
			return n;
		}

		/// <summary>Every keyword combination loaded MarmosetUBER materials use, most common first.</summary>
		public static List<KeyValuePair<string, int>> KeywordSets()
		{
			var counts = new Dictionary<string, int>();
			foreach (var m in Resources.FindObjectsOfTypeAll<Material>())
			{
				if (m != null && m.shader != null && m.shader.name == Shader)
				{
					string key = string.Join(" ", m.shaderKeywords.OrderBy(k => k));
					counts[key] = counts.TryGetValue(key, out int n) ? n + 1 : 1;
				}
			}
			return counts.OrderByDescending(kv => kv.Value).ToList();
		}

		/// <summary>A loaded MarmosetUBER material whose keywords include all of <paramref name="must"/> and none of <paramref name="mustNot"/>, fewest extras first.</summary>
		public static Material Template(string[] must, string[] mustNot)
		{
			string key = string.Join(",", must) + "|" + string.Join(",", mustNot);
			if (Templates.TryGetValue(key, out var cached) && cached != null)
			{
				return cached;
			}
			Material best = null;
			int bestExtra = int.MaxValue;
			foreach (var m in Resources.FindObjectsOfTypeAll<Material>())
			{
				if (m == null || m.shader == null || m.shader.name != Shader)
				{
					continue;
				}
				var kw = m.shaderKeywords;
				if (!must.All(k => System.Array.IndexOf(kw, k) >= 0) || mustNot.Any(k => System.Array.IndexOf(kw, k) >= 0))
				{
					continue;
				}
				int extra = kw.Length - must.Length;
				if (extra < bestExtra)
				{
					best = m;
					bestExtra = extra;
				}
			}
			Templates[key] = best;
			return best;
		}

		private static readonly string[] NoMotion = { "UWE_WAVING", "FX_KELP", "UWE_LIGHTMAP", "UWE_VR_FADEOUT", "UWE_INFECTION", "UWE_PLAYERINFECTION" };

		/// <summary>Material for one material class of Minecraft geometry drawn with <paramref name="texture"/>.</summary>
		public static Material Create(int materialClass, bool emitter, Texture2D texture)
		{
			var must = new List<string> { "_ZWRITE_ON", "MARMO_SPECMAP" };
			var mustNot = new List<string>(NoMotion);
			bool clip = materialClass == Proto.RenMatCutout || materialClass == Proto.RenMatOpaque;
			bool blend = materialClass == Proto.RenMatTranslucent || materialClass == Proto.RenMatAdditive || materialClass == Proto.RenMatEmissive;
			if (blend)
			{
				must.Remove("_ZWRITE_ON");
				must.Add("WBOIT");
			}
			else
			{
				mustNot.Add("WBOIT");
			}
			if (clip)
			{
				must.Add("MARMO_ALPHA_CLIP");
			}
			if (emitter)
			{
				must.Add("MARMO_EMISSION");
			}
			var template = Template(must.ToArray(), mustNot.ToArray());
			if (template == null && !emitter)
			{
				// Fall back to the emission variant with a black glow map.
				must.Add("MARMO_EMISSION");
				template = Template(must.ToArray(), mustNot.ToArray());
			}
			if (template == null)
			{
				Plugin.Log.LogWarning($"SubCraft: no MarmosetUBER template with {string.Join(" ", must)}");
				return null;
			}
			var mat = new Material(template) { name = $"SubCraft {materialClass}{(emitter ? " glow" : "")} ({template.name})" };
			mat.mainTexture = texture;
			if (mat.HasProperty("_MainTex")) mat.SetTexture("_MainTex", texture);
			if (mat.HasProperty("_BumpMap")) mat.SetTexture("_BumpMap", null);   // default "bump": flat
			if (mat.HasProperty("_SpecTex")) mat.SetTexture("_SpecTex", Texture2D.blackTexture); // blocks aren't shiny
			if (mat.HasProperty("_Color")) mat.SetColor("_Color", Color.white);
			if (mat.HasProperty("_Illum"))
			{
				mat.SetTexture("_Illum", emitter ? texture : Texture2D.blackTexture);
			}
			if (clip && mat.HasProperty("_Cutoff")) mat.SetFloat("_Cutoff", 0.1f);
			// _MainTex alpha is gloss to MarmosetUBER: Minecraft's alpha is coverage, so no specular.
			if (mat.HasProperty("_SpecInt")) mat.SetFloat("_SpecInt", 0f);
			if (mat.HasProperty("_Shininess")) mat.SetFloat("_Shininess", 2f);
			if (emitter)
			{
				if (mat.HasProperty("_EnableGlow")) mat.SetFloat("_EnableGlow", 1f);
				if (mat.HasProperty("_GlowColor")) mat.SetColor("_GlowColor", Color.white);
				if (mat.HasProperty("_GlowStrength")) mat.SetFloat("_GlowStrength", 1f);
				if (mat.HasProperty("_GlowStrengthNight")) mat.SetFloat("_GlowStrengthNight", 1f);
			}
			All.Add(mat);
			return mat;
		}

		/// <summary>The shader's properties and a template's values (harness "matinfo").</summary>
		public static JObject Describe()
		{
			var o = new JObject();
			o["keywordSets"] = new JArray(KeywordSets().Select(kv => $"{kv.Value} x [{kv.Key}]"));
			var plain = Template(new[] { "_ZWRITE_ON", "MARMO_SPECMAP" }, NoMotion);
			if (plain != null)
			{
				var sh = plain.shader;
				var props = new JArray();
				for (int i = 0; i < sh.GetPropertyCount(); i++)
				{
					string name = sh.GetPropertyName(i);
					var type = sh.GetPropertyType(i);
					string value = type switch
					{
						UnityEngine.Rendering.ShaderPropertyType.Color => plain.GetColor(name).ToString(),
						UnityEngine.Rendering.ShaderPropertyType.Vector => plain.GetVector(name).ToString(),
						UnityEngine.Rendering.ShaderPropertyType.Float => plain.GetFloat(name).ToString(),
						UnityEngine.Rendering.ShaderPropertyType.Range => plain.GetFloat(name).ToString(),
						UnityEngine.Rendering.ShaderPropertyType.Texture => plain.GetTexture(name) != null ? plain.GetTexture(name).name : "null",
						_ => "?",
					};
					props.Add($"{name} ({type}) = {value}  // {sh.GetPropertyDescription(i)}");
				}
				o["template"] = plain.name;
				o["properties"] = props;
			}
			return o;
		}
	}
}
