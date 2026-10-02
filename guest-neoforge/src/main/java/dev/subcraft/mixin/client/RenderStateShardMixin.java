package dev.subcraft.mixin.client;

import dev.subcraft.capture.VboCapture;
import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Which RenderType a raw vertex-buffer draw uses (VboCapture). */
@Mixin(RenderStateShard.class)
public abstract class RenderStateShardMixin {
	@Inject(method = "setupRenderState", at = @At("HEAD"))
	private void subcraft$setup(CallbackInfo ci) {
		VboCapture.stateSetUp((RenderStateShard) (Object) this);
	}

	@Inject(method = "clearRenderState", at = @At("HEAD"))
	private void subcraft$clear(CallbackInfo ci) {
		VboCapture.stateCleared((RenderStateShard) (Object) this);
	}
}
