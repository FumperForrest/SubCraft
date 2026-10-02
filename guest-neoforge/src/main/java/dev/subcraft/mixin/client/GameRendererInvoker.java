package dev.subcraft.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
	/** The effective vertical FOV this frame (sprint, fluid and effect modifiers included). */
	@Invoker("getFov")
	double subcraft$getFov(Camera camera, float partialTick, boolean useFovSetting);

	/** The view sway when hurt (applied to the first-person hand). */
	@Invoker("bobHurt")
	void subcraft$bobHurt(com.mojang.blaze3d.vertex.PoseStack pose, float partialTick);

	/** The walking bob (applied to the first-person hand). */
	@Invoker("bobView")
	void subcraft$bobView(com.mojang.blaze3d.vertex.PoseStack pose, float partialTick);
}
