package dev.subcraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SubBiomesTest {
	@Test
	void namesSeenInGame() {
		assertEquals("safe_shallows", SubBiomes.of("safeShallows"));
		assertEquals("kelp_forest", SubBiomes.of("kelpForest"));
		assertEquals("grassy_plateaus", SubBiomes.of("grassyPlateaus"));
	}

	@Test
	void variantsAndCaves() {
		assertEquals("kelp_forest", SubBiomes.of("kelpForest_Cave"));
		assertEquals("grand_reef", SubBiomes.of("deepGrandReef"));
		assertEquals("blood_kelp", SubBiomes.of("bloodKelp_Trench"));
		assertEquals("lava_zone", SubBiomes.of("ILZChamber"));
		assertEquals("lost_river", SubBiomes.of("LostRiver_GhostTree"));
		assertEquals("aurora", SubBiomes.of("crashedShip"));
	}

	@Test
	void precursorWinsOverItsSurroundings() {
		assertEquals("precursor", SubBiomes.of("Precursor_LostRiverBase"));
		assertEquals("precursor", SubBiomes.of("Precursor_LavaCastleBase"));
	}

	@Test
	void unknownIsOcean() {
		assertEquals("ocean", SubBiomes.of(null));
		assertEquals("ocean", SubBiomes.of(""));
		assertEquals("ocean", SubBiomes.of("somethingNew"));
	}
}
