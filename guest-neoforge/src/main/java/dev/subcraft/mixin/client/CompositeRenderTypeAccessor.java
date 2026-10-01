package dev.subcraft.mixin.client;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** RenderType's render-state shards, for classifying any (modded) RenderType by state, never by name. */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface CompositeRenderTypeAccessor {
	@Invoker("state")
	RenderType.CompositeState subcraft$state();
}
