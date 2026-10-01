package dev.subcraft.world;

import static dev.subcraft.link.Proto.*;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.SoundType;

/** Subnautica's surface material of a ghost-terrain block (ColMaterial): drives its sound type. */
public enum TerrainMaterial implements StringRepresentable {
	ROCK("rock", SoundType.STONE),
	SAND("sand", SoundType.SAND),
	CORAL("coral", SoundType.CORAL_BLOCK),
	METAL("metal", SoundType.METAL),
	GLASS("glass", SoundType.GLASS),
	ORGANIC("organic", SoundType.WET_GRASS),
	ICE("ice", SoundType.GLASS),
	PRECURSOR("precursor", SoundType.DEEPSLATE_TILES);

	private final String name;
	public final SoundType sound;

	TerrainMaterial(String name, SoundType sound) {
		this.name = name;
		this.sound = sound;
	}

	@Override
	public String getSerializedName() {
		return this.name;
	}

	public static TerrainMaterial of(int colMaterial) {
		return switch (colMaterial) {
			case MAT_SAND -> SAND;
			case MAT_CORAL -> CORAL;
			case MAT_METAL -> METAL;
			case MAT_GLASS -> GLASS;
			case MAT_ORGANIC -> ORGANIC;
			case MAT_ICE -> ICE;
			case MAT_PRECURSOR -> PRECURSOR;
			default -> ROCK;
		};
	}
}
