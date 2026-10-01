package dev.subcraft.mixin;

import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Ghost-terrain masks become shapes directly from their 8x8x8 bits (the constructor is protected). */
@Mixin(CubeVoxelShape.class)
public interface CubeVoxelShapeInvoker {
	@Invoker("<init>")
	static CubeVoxelShape subcraft$create(DiscreteVoxelShape shape) {
		throw new AssertionError();
	}
}
