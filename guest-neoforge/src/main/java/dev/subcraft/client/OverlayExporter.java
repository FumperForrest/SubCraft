package dev.subcraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Copies Minecraft's main render target (hand + HUD + screens on a transparent background) into
 * the overlay triple buffer. CPU path: glReadPixels straight into the shared mapping (rows
 * bottom-up). A PBO or shared-texture path can replace it if it shows up in frame times.
 */
public final class OverlayExporter {
	private static long nextFrameId = 1;
	private static boolean loggedSize;

	private OverlayExporter() {
	}

	public static void capture(Minecraft minecraft, LinkView view) {
		RenderTarget target = minecraft.getMainRenderTarget();
		int width = target.width;
		int height = target.height;
		if (width <= 0 || height <= 0 || width > Proto.MAX_OVERLAY_W || height > Proto.MAX_OVERLAY_H) {
			return;
		}
		if (!loggedSize) {
			loggedSize = true;
			SubCraft.LOG.info("SubCraft: overlay capture {}x{} (window {}x{})", width, height,
				minecraft.getWindow().getScreenWidth(), minecraft.getWindow().getScreenHeight());
		}
		ByteBuffer dst = view.buffer().slice((int) view.overlayBackSlotOffset(), width * height * 4);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, dst);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
		view.publishOverlay(width, height, true, nextFrameId++);
	}
}
