package dev.subcraft.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.subcraft.client.SubClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** Host state and input arrive before this frame's client ticks run (no extra frame of latency). */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void subcraft$beginFrame(boolean renderLevel, CallbackInfo ci) {
		SubClient.beginFrame();
	}

	@Inject(method = "runTick", at = @At("TAIL"))
	private void subcraft$paceFrame(boolean renderLevel, CallbackInfo ci) {
		SubClient.paceFrame();
	}

	/** The window is hidden while linked and the host has the real focus: focus follows the link. */
	@ModifyReturnValue(method = "isWindowActive", at = @At("RETURN"))
	private boolean subcraft$windowActive(boolean original) {
		return SubClient.tookOver() ? SubClient.linked() : original;
	}
}
