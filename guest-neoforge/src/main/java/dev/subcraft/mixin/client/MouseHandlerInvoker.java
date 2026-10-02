package dev.subcraft.mixin.client;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** MouseHandler's GLFW callbacks, so the input bridge can feed host input through them. */
@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {
	@Invoker("onPress")
	void subcraft$onPress(long window, int button, int action, int mods);

	@Invoker("onScroll")
	void subcraft$onScroll(long window, double xOffset, double yOffset);

	@org.spongepowered.asm.mixin.gen.Accessor("accumulatedDX")
	double subcraft$accumulatedDX();

	@org.spongepowered.asm.mixin.gen.Accessor("accumulatedDX")
	void subcraft$setAccumulatedDX(double v);

	@org.spongepowered.asm.mixin.gen.Accessor("accumulatedDY")
	double subcraft$accumulatedDY();

	@org.spongepowered.asm.mixin.gen.Accessor("accumulatedDY")
	void subcraft$setAccumulatedDY(double v);

	@Invoker("onMove")
	void subcraft$onMove(long window, double x, double y);
}
