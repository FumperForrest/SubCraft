using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Render
{
	/// <summary>
	/// Light-emitting Minecraft blocks become real Unity point lights, so a torch lights Subnautica's
	/// sand, creatures and bases (MISSION.md 3.2). Flames flicker; lava glows slowly.
	/// </summary>
	public static class BlockLights
	{
		/// <summary>Range per Minecraft light level (level 15 reaches ~15 blocks in Minecraft).</summary>
		public static float RangePerLevel = 0.9f;
		public static float Intensity = 1.6f;

		public static Light Add(Transform parent, Vector3 position, int level, uint rgb, int kind)
		{
			var go = new GameObject($"SubCraft light {level}");
			go.transform.SetParent(parent, false);
			go.transform.position = position;
			var light = go.AddComponent<Light>();
			light.type = LightType.Point;
			light.color = new Color32((byte)rgb, (byte)(rgb >> 8), (byte)(rgb >> 16), 255);
			light.range = Mathf.Max(2f, level * RangePerLevel);
			light.intensity = Intensity * level / 15f;
			light.shadows = LightShadows.None;
			light.renderMode = LightRenderMode.ForcePixel;
			if (kind == Proto.LightFlame || kind == Proto.LightLava)
			{
				var f = go.AddComponent<Flicker>();
				f.baseIntensity = light.intensity;
				f.speed = kind == Proto.LightFlame ? 9f : 1.2f;
				f.amount = kind == Proto.LightFlame ? 0.18f : 0.1f;
			}
			return light;
		}

		public sealed class Flicker : MonoBehaviour
		{
			public float baseIntensity, speed, amount;
			private Light light;
			private float seed;

			private void Awake()
			{
				light = GetComponent<Light>();
				seed = Random.value * 100f;
			}

			private void Update()
			{
				light.intensity = baseIntensity * (1f + amount * (Mathf.PerlinNoise(seed, Time.time * speed) * 2f - 1f));
			}
		}
	}
}
