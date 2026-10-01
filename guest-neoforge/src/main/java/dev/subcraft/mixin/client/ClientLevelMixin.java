package dev.subcraft.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.subcraft.world.SubWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * No baked face shading in the SubCraft world: the host lights Minecraft's geometry with its own
 * sun and lights, so Minecraft's fixed per-face darkening would be applied twice.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@ModifyReturnValue(method = "getShade(Lnet/minecraft/core/Direction;Z)F", at = @At("RETURN"))
	private float subcraft$noShade(float original, Direction direction, boolean shade) {
		return SubWorld.is((ClientLevel) (Object) this) ? 1.0F : original;
	}

	@ModifyReturnValue(method = "getShade(FFFZ)F", at = @At("RETURN"))
	private float subcraft$noShadeNormal(float original, float x, float y, float z, boolean shade) {
		return SubWorld.is((ClientLevel) (Object) this) ? 1.0F : original;
	}
}
