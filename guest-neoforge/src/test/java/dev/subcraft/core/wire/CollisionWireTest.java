package dev.subcraft.core.wire;

import static dev.subcraft.link.Proto.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Parses protocol/fixtures/*.bin, written by the host's CollisionWire (C#, CollisionWireTests):
 * the two languages agree on the bytes, not just on the offsets.
 */
class CollisionWireTest {
	private static ByteBuffer fixture(String name) throws Exception {
		Path root = Path.of(System.getProperty("subcraft.repoRoot", ".."));
		return ByteBuffer.wrap(Files.readAllBytes(root.resolve("protocol/fixtures").resolve(name))).order(ByteOrder.LITTLE_ENDIAN);
	}

	@Test
	void trianglesFromTheHost() throws Exception {
		ByteBuffer b = fixture("col_tris.bin");
		CollisionWire.Tris t = CollisionWire.tris(b, 0, b.capacity());
		assertNotNull(t);
		assertEquals(2, t.sx());
		assertEquals(-1, t.sy());
		assertEquals(-3, t.sz());
		assertEquals(0x12345679, t.epoch());
		assertEquals(2, t.count());
		assertArrayEquals(new float[] {32.5F, -12F, -48.25F, 33.5F, -12F, -48.25F, 32.5F, -11F, -47.25F,
			40F, -16F, -40F, 41F, -15.5F, -40F, 40F, -15.5F, -39F}, t.verts());
		assertEquals(TRI_TERRAIN | MAT_SAND << TRI_MATERIAL_SHIFT, t.flags()[0]);
		assertEquals(TRI_STRUCTURE | MAT_METAL << TRI_MATERIAL_SHIFT, t.flags()[1]);
	}

	@Test
	void biomesFromTheHost() throws Exception {
		ByteBuffer b = fixture("col_biomes.bin");
		CollisionWire.Biomes bio = CollisionWire.biomes(b, 0, b.capacity());
		assertNotNull(bio);
		assertEquals(2, bio.sx());
		assertEquals(-1, bio.sy());
		assertEquals(-3, bio.sz());
		String[] names = {"kelpForest", "safeShallows", "grassyPlateaus_Cave", null};
		for (int i = 0; i < BIOME_CELLS; i++) {
			assertEquals(names[i % 4], bio.cells()[i], "cell " + i);
		}
	}

	@Test
	void countsThatDontFitAreRefused() throws Exception {
		ByteBuffer b = fixture("col_tris.bin");
		assertNull(CollisionWire.tris(b, 0, b.capacity() - 1), "one byte short of the second triangle");
		assertNull(CollisionWire.tris(b, 0, 16), "shorter than the box");
		ByteBuffer neg = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).putInt(28, -1);
		assertNull(CollisionWire.tris(neg, 0, 64));
		ByteBuffer dry = ByteBuffer.allocate(8 + 32).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 2);
		assertNull(CollisionWire.dry(dry, 0, 40), "two boxes claimed, one present");
		dry.putInt(4, 1).putFloat(8 + 12, 3.5F);
		assertEquals(3.5F, CollisionWire.dry(dry, 0, 40).boxes()[3]);
	}

	@Test
	void biomeNamesCutOffByThePayloadAreUnknown() throws Exception {
		ByteBuffer b = fixture("col_biomes.bin");
		// Cut inside the second name: the first survives, the others read as unknown.
		int cut = COL_BIOMES_BYTES + BIOME_CELLS + "kelpForest".length() + 1 + 3;
		CollisionWire.Biomes bio = CollisionWire.biomes(b, 0, cut);
		assertEquals("kelpForest", bio.cells()[0]);
		assertEquals("saf", bio.cells()[1]);
		assertNull(bio.cells()[2]);
	}
}
