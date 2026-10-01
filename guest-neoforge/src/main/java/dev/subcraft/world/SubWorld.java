package dev.subcraft.world;

import dev.subcraft.SubCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** Which worlds are SubCraft's. Behaviour changes apply only there. */
public final class SubWorld {
	public static final String DEV_WORLD_NAME = "SubCraft Dev";
	public static final ResourceKey<DimensionType> DIMENSION_TYPE =
		ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.fromNamespaceAndPath(SubCraft.MOD_ID, "subnautica"));
	public static final ResourceKey<WorldPreset> PRESET =
		ResourceKey.create(Registries.WORLD_PRESET, ResourceLocation.fromNamespaceAndPath(SubCraft.MOD_ID, "subnautica"));
	/** Subnautica's sea surface in Minecraft y (Phase 1: verify against the host). Water is below it. */
	public static final int SEA_LEVEL = 0;

	private SubWorld() {
	}

	public static boolean is(Level level) {
		return level != null && level.dimensionTypeRegistration().is(DIMENSION_TYPE);
	}
}
