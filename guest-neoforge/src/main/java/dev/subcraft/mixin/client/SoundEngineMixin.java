package dev.subcraft.mixin.client;

import dev.subcraft.client.SoundBridge;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The host plays Minecraft's sounds while linked (SoundBridge): stops and "is it playing" go there too. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
	private void subcraft$stop(SoundInstance sound, CallbackInfo ci) {
		SoundBridge.stopped(sound);
	}

	@Inject(method = "stopAll", at = @At("HEAD"))
	private void subcraft$stopAll(CallbackInfo ci) {
		SoundBridge.stoppedAll();
	}

	@Inject(method = "isActive", at = @At("HEAD"), cancellable = true)
	private void subcraft$isActive(SoundInstance sound, CallbackInfoReturnable<Boolean> cir) {
		if (SoundBridge.isLive(sound)) {
			cir.setReturnValue(true);
		}
	}
}
