package dev.subcraft.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.subcraft.client.SubClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
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
			// The skipped pass also prepares the entity renderer: screens that draw entities (the
			// survival inventory's player model) need its camera.
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level != null) {
				minecraft.getEntityRenderDispatcher().prepare(minecraft.level, camera, minecraft.crosshairPickEntity);
			}
			RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
			RenderSystem.clear(16640, Minecraft.ON_OSX); // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
			ci.cancel();
		}
	}

	/**
	 * "Loading terrain" waits until the player's section is compiled, which never happens while
	 * the world pass is skipped.
	 */
	@ModifyReturnValue(method = "isSectionCompiled", at = @At("RETURN"))
	private boolean subcraft$sectionReady(boolean original, BlockPos pos) {
		return original || SubClient.hostDrawsWorld();
	}
}
