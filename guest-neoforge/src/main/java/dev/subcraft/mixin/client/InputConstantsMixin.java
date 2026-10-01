package dev.subcraft.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.subcraft.client.InputBridge;
import dev.subcraft.client.SubClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keyboard state comes from the host once it has taken over, and the real cursor is never grabbed. */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void subcraft$isKeyDown(long window, int key, CallbackInfoReturnable<Boolean> cir) {
		if (SubClient.tookOver()) {
			cir.setReturnValue(InputBridge.isKeyDown(key));
		}
	}

	@Inject(method = "grabOrReleaseMouse", at = @At("HEAD"), cancellable = true)
	private static void subcraft$grabOrReleaseMouse(long window, int cursorMode, double x, double y, CallbackInfo ci) {
		if (SubClient.tookOver()) {
			ci.cancel();
		}
	}
}
