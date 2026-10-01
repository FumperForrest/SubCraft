package dev.subcraft.capture;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Reads a texture's level 0 back from the GPU by its resource location (any texture Minecraft has
 * registered: atlases, entity textures, modded ones). Render thread only. Rows come out in GL
 * order, i.e. row 0 = v 0 = the top of Minecraft's image.
 */
public final class TextureGrabber {
	public record Pixels(int width, int height, ByteBuffer rgba) {
		public int argbAt(int x, int y) {
			int o = (y * this.width + x) * 4;
			return (this.rgba.get(o) & 0xFF) << 16 | (this.rgba.get(o + 1) & 0xFF) << 8 | (this.rgba.get(o + 2) & 0xFF) | (this.rgba.get(o + 3) & 0xFF) << 24;
		}
	}

	private TextureGrabber() {
	}

	public static Pixels grab(ResourceLocation location) {
		RenderSystem.assertOnRenderThread();
		int id = Minecraft.getInstance().getTextureManager().getTexture(location).getId();
		GlStateManager._bindTexture(id);
		int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
		int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
		ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
		GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
		GlStateManager._bindTexture(0);
		return new Pixels(w, h, pixels);
	}
}
