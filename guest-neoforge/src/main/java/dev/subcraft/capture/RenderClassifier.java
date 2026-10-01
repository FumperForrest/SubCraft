package dev.subcraft.capture;

import dev.subcraft.link.Proto;
import dev.subcraft.mixin.client.CompositeRenderTypeAccessor;
import dev.subcraft.mixin.client.CompositeStateAccessor;
import dev.subcraft.mixin.client.TextureStateShardInvoker;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Maps any RenderType (vanilla or modded) to a host material class by its render-state shards:
 * transparency and lightmap, never by RenderType name (MISSION.md 3.2).
 * <ul>
 * <li>no blending -> cutout (alpha-tested; an opaque texture just never discards)</li>
 * <li>additive / lightning / glint blending -> additive</li>
 * <li>any other blending without the lightmap -> emissive; with it -> translucent</li>
 * </ul>
 */
public final class RenderClassifier {
	public record Info(int material, Optional<ResourceLocation> texture, boolean backfaceCulling) {
	}

	private static final Map<RenderType, Info> CACHE = new ConcurrentHashMap<>();
	private static final Info UNKNOWN = new Info(Proto.REN_MAT_CUTOUT, Optional.empty(), true);

	private RenderClassifier() {
	}

	public static Info classify(RenderType type) {
		return CACHE.computeIfAbsent(type, RenderClassifier::compute);
	}

	private static Info compute(RenderType type) {
		if (!(type instanceof CompositeRenderTypeAccessor composite)) {
			return UNKNOWN; // not a composite type: Phase 3's fallback layer handles exotic draws
		}
		CompositeStateAccessor state = (CompositeStateAccessor) (Object) composite.subcraft$state();
		RenderStateShard.TransparencyStateShard transparency = state.subcraft$transparencyState();
		boolean lightmap = state.subcraft$lightmapState() != RenderStateShard.NO_LIGHTMAP;
		int material;
		if (transparency == RenderStateShard.NO_TRANSPARENCY) {
			material = Proto.REN_MAT_CUTOUT;
		} else if (transparency == RenderStateShard.ADDITIVE_TRANSPARENCY || transparency == RenderStateShard.LIGHTNING_TRANSPARENCY
			|| transparency == RenderStateShard.GLINT_TRANSPARENCY) {
			material = Proto.REN_MAT_ADDITIVE;
		} else {
			material = lightmap ? Proto.REN_MAT_TRANSLUCENT : Proto.REN_MAT_EMISSIVE;
		}
		Optional<ResourceLocation> texture = ((TextureStateShardInvoker) state.subcraft$textureState()).subcraft$texture();
		boolean cull = state.subcraft$cullState() != RenderStateShard.NO_CULL;
		return new Info(material, texture, cull);
	}
}
