package dev.subcraft.mixin.client;

import dev.subcraft.client.LookCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The mouse turning the local player (MouseHandler.turnPlayer): see LookCapture. */
@Mixin(Entity.class)
public abstract class LocalPlayerTurnMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void subcraft$turn(double yaw, double pitch, CallbackInfo ci) {
		if ((Object) this == Minecraft.getInstance().player && LookCapture.turn()) {
			ci.cancel();
		}
	}
}
