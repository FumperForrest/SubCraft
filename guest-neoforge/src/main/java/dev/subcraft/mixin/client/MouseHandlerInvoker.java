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

	@Invoker("onMove")
	void subcraft$onMove(long window, double x, double y);
}
