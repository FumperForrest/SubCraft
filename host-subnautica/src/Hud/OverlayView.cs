using System;
using SubCraft.Link;
using UnityEngine;
using UnityEngine.UI;

namespace SubCraft.Hud
{
	/// <summary>
	/// Draws Minecraft's GUI (hotbar, hand, screens) over Subnautica on a uGUI canvas of its own,
	/// above Subnautica's HUD. Hidden while a Subnautica menu (PDA, pause) is up: those are
	/// Subnautica's screens. CPU path: the newest overlay frame is uploaded into a texture (both are
	/// bottom-up).
	/// </summary>
	public sealed unsafe class OverlayView : MonoBehaviour
	{
		/// <summary>Above Subnautica's HUD canvases.</summary>
		private const int SortingOrder = 50;

		private Texture2D tex;
		private long lastFrameId;
		private float lastFrameTime;
		private Canvas canvas;
		private RawImage image;

		public static OverlayView Instance { get; private set; }

		private void Awake()
		{
			Instance = this;
			var go = new GameObject("SubCraft Overlay");
			go.transform.SetParent(transform, false);
			canvas = go.AddComponent<Canvas>();
			canvas.renderMode = RenderMode.ScreenSpaceOverlay;
			canvas.sortingOrder = SortingOrder;
			var imgGo = new GameObject("Minecraft GUI");
			imgGo.transform.SetParent(go.transform, false);
			image = imgGo.AddComponent<RawImage>();
			image.raycastTarget = false;
			var rt = image.rectTransform;
			rt.anchorMin = Vector2.zero;
			rt.anchorMax = Vector2.one;
			rt.offsetMin = Vector2.zero;
			rt.offsetMax = Vector2.zero;
			canvas.enabled = false;
		}

		private void Update()
		{
			var driver = LinkDriver.Instance;
			if (driver?.Link == null || !driver.McLinked)
			{
				canvas.enabled = false;
				return;
			}
			var view = driver.Link.View;
			if (view.TryTakeOverlay(out var frame))
			{
				if (tex == null || tex.width != frame.Width || tex.height != frame.Height)
				{
					if (tex != null) Destroy(tex);
					tex = new Texture2D(frame.Width, frame.Height, TextureFormat.RGBA32, false) { filterMode = FilterMode.Point, wrapMode = TextureWrapMode.Clamp };
					image.texture = tex;
				}
				tex.LoadRawTextureData((IntPtr)(view.Base + frame.PixelsOff), frame.Width * frame.Height * 4);
				tex.Apply(false);
				lastFrameId = frame.FrameId;
				lastFrameTime = Time.unscaledTime;
			}
			HideQuickSlots(Player.PlayerPuppet.Active && Plugin.ShowOverlay.Value);
			canvas.enabled = tex != null && Plugin.ShowOverlay.Value && HostState.InGame() && !HostState.MenuOpen()
				&& Time.unscaledTime - lastFrameTime <= 2f;
		}

		private CanvasGroup quickSlots;
		private float nextQuickSlotSearch;

		/// <summary>Minecraft's hotbar replaces Subnautica's quickslot bar (MISSION.md 9.1).</summary>
		private void HideQuickSlots(bool hide)
		{
			if (quickSlots == null)
			{
				if (Time.unscaledTime < nextQuickSlotSearch)
				{
					return;
				}
				nextQuickSlotSearch = Time.unscaledTime + 1f;
				var bar = FindObjectOfType<uGUI_QuickSlots>();
				if (bar == null)
				{
					return;
				}
				quickSlots = bar.GetComponent<CanvasGroup>() ?? bar.gameObject.AddComponent<CanvasGroup>();
			}
			quickSlots.alpha = hide ? 0f : 1f;
		}

		public long LastFrameId => lastFrameId;

		public Texture2D Texture => tex;
	}
}
