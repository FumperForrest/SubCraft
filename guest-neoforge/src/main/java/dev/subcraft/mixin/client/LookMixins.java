package dev.subcraft.mixin.client;

import dev.subcraft.client.LookCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** See LookCapture. */
@Mixin(MouseHandler.class)
public abstract class LookMixins {
	@Shadow
	private double accumulatedDX;
	@Shadow
	private double accumulatedDY;
	@Shadow
	private Minecraft minecraft;

	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
	private void subcraft$beforeMovement(CallbackInfo ci) {
		LookCapture.beforeMovement((this.accumulatedDX != 0 || this.accumulatedDY != 0) && this.minecraft.screen == null && this.minecraft.player != null
			&& ((MouseHandler) (Object) this).isMouseGrabbed());
	}

	@Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
	private void subcraft$afterMovement(CallbackInfo ci) {
		LookCapture.afterMovement();
	}
}
