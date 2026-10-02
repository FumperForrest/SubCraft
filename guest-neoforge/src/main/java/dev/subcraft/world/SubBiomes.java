package dev.subcraft.world;

import java.util.Locale;

/**
 * Subnautica biome names (LargeWorld.GetBiome: "safeShallows", "kelpForest_Cave", "Precursor_...")
 * -> SubCraft's Minecraft biomes (data/subcraft/worldgen/biome, written by tools/gen_biomes.py).
 * Pure Java, unit-tested.
 */
public final class SubBiomes {
	public static final String FALLBACK = "ocean";

	private SubBiomes() {
	}

	/** The SubCraft biome path (e.g. "kelp_forest") for a host biome name. */
	public static String of(String hostName) {
		if (hostName == null || hostName.isEmpty()) {
			return FALLBACK;
		}
		String n = hostName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		// Most specific first: precursor bases sit inside other regions' names.
		if (n.contains("precursor") || n.contains("prison") || n.contains("disease")) return "precursor";
		if (n.contains("lava") || n.startsWith("ilz") || n.startsWith("alz")) return "lava_zone";
		if (n.contains("lostriver") || n.contains("ghosttree") || n.contains("bonesfield") || n.contains("skeletoncave") || n.contains("treecove")) return "lost_river";
		if (n.contains("jellyshroom")) return "jellyshroom_caves";
		if (n.contains("safeshallows")) return "safe_shallows";
		if (n.contains("kelpforest")) return "kelp_forest";
		if (n.contains("grassyplateaus")) return "grassy_plateaus";
		if (n.contains("mushroomforest")) return "mushroom_forest";
		if (n.contains("sparsereef")) return "sparse_reef";
		if (n.contains("grandreef")) return "grand_reef";
		if (n.contains("bloodkelp")) return "blood_kelp";
		if (n.contains("underwaterislands")) return "underwater_islands";
		if (n.contains("floatingisland")) return "floating_island";
		if (n.contains("mountain")) return "mountains";
		if (n.contains("dunes")) return "dunes";
		if (n.contains("crashzone")) return "crash_zone";
		if (n.contains("crashedship") || n.contains("aurora") || n.contains("generatorroom")) return "aurora";
		if (n.contains("koosh") || n.contains("bulb")) return "bulb_zone";
		if (n.contains("seatreader")) return "sea_treader_path";
		if (n.contains("void") || n.contains("crateredge")) return "crater_edge";
		return FALLBACK;
	}
}
