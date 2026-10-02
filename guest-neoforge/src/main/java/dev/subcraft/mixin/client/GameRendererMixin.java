package dev.subcraft.mixin.client;

import dev.subcraft.client.SubClient;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** The host draws the first-person hand (DynamicCapture, kRenHand), lit by its own lights: not in the overlay. */
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void subcraft$handIsTheHosts(Camera camera, float partialTick, Matrix4f matrix, CallbackInfo ci) {
		if (SubClient.hostDrawsWorld()) {
			ci.cancel();
		}
	}
}
