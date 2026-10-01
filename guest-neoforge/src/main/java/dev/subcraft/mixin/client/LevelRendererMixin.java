package dev.subcraft.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.subcraft.client.SubClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While linked, the host draws the world: Minecraft's own world pass is skipped and the frame is
 * cleared to transparent, so the main target holds only what is drawn on top (hand, GUI).
 * Phase 2 replaces the skip with geometry capture.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
	private void subcraft$skipWorld(
		DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
		Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci
	) {
		if (SubClient.hostDrawsWorld()) {
			RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
			RenderSystem.clear(16640, Minecraft.ON_OSX); // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
			ci.cancel();
		}
	}
}
