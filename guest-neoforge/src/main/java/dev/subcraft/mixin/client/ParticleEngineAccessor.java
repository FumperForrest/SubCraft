package dev.subcraft.mixin.client;

import java.util.Map;
import java.util.Queue;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Live particles by render type, so the host can draw them (DynamicCapture). */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
	@Accessor("particles")
	Map<ParticleRenderType, Queue<Particle>> subcraft$particles();
}
