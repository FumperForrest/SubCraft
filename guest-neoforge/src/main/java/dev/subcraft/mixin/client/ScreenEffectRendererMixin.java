package dev.subcraft.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.subcraft.client.SubClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's full-screen effects (underwater tint, the block texture when the eye is inside a
 * block, the fire overlay) blend with the default function, whose alpha factors are (ONE, ZERO):
 * they replace the overlay's alpha everywhere (underwater: 0.1, so the hand all but vanished).
 * The host draws the world and its own underwater look, so they are skipped while it does.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	@Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
	private static void subcraft$noWater(Minecraft minecraft, PoseStack pose, CallbackInfo ci) {
		if (SubClient.hostDrawsWorld()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
	private static void subcraft$noInBlock(TextureAtlasSprite sprite, PoseStack pose, CallbackInfo ci) {
		if (SubClient.hostDrawsWorld()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
	private static void subcraft$noFire(Minecraft minecraft, PoseStack pose, CallbackInfo ci) {
		if (SubClient.hostDrawsWorld()) {
			ci.cancel();
		}
	}
}
