package dev.subcraft.mixin.client;

import java.util.Optional;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderStateShard.EmptyTextureStateShard.class)
public interface TextureStateShardInvoker {
	/** The texture this shard binds, if it is a single texture. */
	@Invoker("cutoutTexture")
	Optional<ResourceLocation> subcraft$texture();
}
