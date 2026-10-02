package dev.subcraft.mixin.client;

import dev.subcraft.client.InputBridge;
import dev.subcraft.client.SubClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Immersive Vehicles reads keys and mouse buttons with GLFW.glfwGetKey/glfwGetMouseButton on
 * Minecraft's window, which is hidden and never has the keyboard: its driving, gear and flight
 * controls never saw a key. While linked they read SubCraft's virtual keyboard instead.
 * (@Pseudo: applied only when Immersive Vehicles is installed.)
 */
@Pseudo
@Mixin(targets = "mcinterface1211.InterfaceInput", remap = false)
public abstract class MtsInputMixin {
	@Inject(method = "isKeyPressed(I)Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void subcraft$key(int key, CallbackInfoReturnable<Boolean> cir) {
		if (SubClient.tookOver()) {
			cir.setReturnValue(InputBridge.isKeyDown(key));
		}
	}

	@Inject(method = "isMouseButtonPressed(I)Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void subcraft$button(int button, CallbackInfoReturnable<Boolean> cir) {
		if (SubClient.tookOver()) {
			cir.setReturnValue(InputBridge.isButtonDown(button));
		}
	}
}
