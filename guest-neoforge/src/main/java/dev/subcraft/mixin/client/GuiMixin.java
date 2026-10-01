package dev.subcraft.mixin.client;

import dev.subcraft.client.SubClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
	/**
	 * The vignette darkens Minecraft's own world image and writes alpha 1 over the whole screen
	 * (its alpha blend factors are ONE, ZERO), which would make the overlay opaque. The host draws
	 * the world, so there is nothing for it to darken.
	 */
	@Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
	private void subcraft$noVignette(GuiGraphics graphics, Entity entity, CallbackInfo ci) {
		if (SubClient.hostDrawsWorld()) {
			ci.cancel();
		}
	}
}
