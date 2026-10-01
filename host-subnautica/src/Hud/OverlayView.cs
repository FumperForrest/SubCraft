using System;
using SubCraft.Link;
using UnityEngine;

namespace SubCraft.Hud
{
	/// <summary>
	/// Draws Minecraft's GUI (hotbar, hand, screens) over Subnautica. CPU path: the newest overlay
	/// frame is uploaded into a texture (both are bottom-up) and drawn on top in OnGUI.
	/// Phase 1 moves it to a top uGUI canvas.
	/// </summary>
	public sealed unsafe class OverlayView : MonoBehaviour
	{
		private Texture2D tex;
		private long lastFrameId;
		private float lastFrameTime;

		private void Update()
		{
			var driver = LinkDriver.Instance;
			if (driver?.Link == null || !driver.McLinked)
			{
				return;
			}
			var view = driver.Link.View;
			if (!view.TryTakeOverlay(out var frame))
			{
				return;
			}
			if (tex == null || tex.width != frame.Width || tex.height != frame.Height)
			{
				if (tex != null) Destroy(tex);
				tex = new Texture2D(frame.Width, frame.Height, TextureFormat.RGBA32, false) { filterMode = FilterMode.Point, wrapMode = TextureWrapMode.Clamp };
			}
			tex.LoadRawTextureData((IntPtr)(view.Base + frame.PixelsOff), frame.Width * frame.Height * 4);
			tex.Apply(false);
			lastFrameId = frame.FrameId;
			lastFrameTime = Time.unscaledTime;
		}

		private void OnGUI()
		{
			var driver = LinkDriver.Instance;
			if (tex == null || driver == null || !driver.McLinked || !Plugin.ShowOverlay.Value || !HostState.InGame()
				|| Time.unscaledTime - lastFrameTime > 2f)
			{
				return;
			}
			if (Event.current.type != EventType.Repaint)
			{
				return;
			}
			GUI.depth = -1000;
			// Texture rows are bottom-up like Unity's: draw as is.
			GUI.DrawTexture(new Rect(0, 0, Screen.width, Screen.height), tex, ScaleMode.StretchToFill, true);
		}

		public long LastFrameId => lastFrameId;
	}
}
