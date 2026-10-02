package dev.subcraft.world.ghost;

import static org.junit.jupiter.api.Assertions.*;

import dev.subcraft.world.tri.TriStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoxelizerTest {
	private final List<float[]> tris = new ArrayList<>();
	private final List<Integer> mats = new ArrayList<>();
	private final Map<Long, TriStore.Section> store = new HashMap<>();
	private int material = 1;
	private int structureFlag;

	@BeforeEach
	void reset() {
		this.tris.clear();
		this.mats.clear();
		this.store.clear();
	}

	/** Quad a-b-c-d, counter-clockwise seen from outside (the solid side is behind it). */
	private void quad(double[] a, double[] b, double[] c, double[] d) {
		tri(a, b, c);
		tri(a, c, d);
	}

	private void tri(double[] a, double[] b, double[] c) {
		this.tris.add(new float[] {(float) a[0], (float) a[1], (float) a[2], (float) b[0], (float) b[1], (float) b[2], (float) c[0], (float) c[1], (float) c[2]});
		this.mats.add(this.material << 8 | this.structureFlag);
	}

	/** A floor (solid below) at height y over the given xz box. */
	private void floor(double x0, double z0, double x1, double z1, double y) {
		quad(new double[] {x0, y, z0}, new double[] {x0, y, z1}, new double[] {x1, y, z1}, new double[] {x1, y, z0});
	}

	/** A ceiling (solid above) at height y. */
	private void ceiling(double x0, double z0, double x1, double z1, double y) {
		quad(new double[] {x0, y, z0}, new double[] {x1, y, z0}, new double[] {x1, y, z1}, new double[] {x0, y, z1});
	}

	/** Every triangle into every section its bounds overlap, as the host sends them. */
	private void commit(int... sectionsY) {
		Map<Long, List<Integer>> per = new HashMap<>();
		for (int i = 0; i < this.tris.size(); i++) {
			float[] t = this.tris.get(i);
			int sx0 = (int) Math.floor(Math.min(t[0], Math.min(t[3], t[6]))) >> 4, sx1 = (int) Math.floor(Math.max(t[0], Math.max(t[3], t[6]))) >> 4;
			int sy0 = (int) Math.floor(Math.min(t[1], Math.min(t[4], t[7]))) >> 4, sy1 = (int) Math.floor(Math.max(t[1], Math.max(t[4], t[7]))) >> 4;
			int sz0 = (int) Math.floor(Math.min(t[2], Math.min(t[5], t[8]))) >> 4, sz1 = (int) Math.floor(Math.max(t[2], Math.max(t[5], t[8]))) >> 4;
			for (int x = sx0; x <= sx1; x++)
				for (int y = sy0; y <= sy1; y++)
					for (int z = sz0; z <= sz1; z++)
						per.computeIfAbsent(TriStore.key(x, y, z), k -> new ArrayList<>()).add(i);
		}
		// Known-empty sections the test names explicitly (the host sends count 0 for those).
		for (int sy : sectionsY) {
			per.computeIfAbsent(TriStore.key(0, sy, 0), k -> new ArrayList<>());
		}
		per.forEach((k, list) -> {
			float[] v = new float[list.size() * 9];
			int[] f = new int[list.size()];
			for (int i = 0; i < list.size(); i++) {
				System.arraycopy(this.tris.get(list.get(i)), 0, v, i * 9, 9);
				f[i] = this.mats.get(list.get(i));
			}
			int sx = (int) (k >> 42) << 10 >> 10, sy = (int) (k >> 22 & 0xFFFFF) << 12 >> 12, sz = (int) (k & 0x3FFFFF) << 10 >> 10;
			this.store.put(k, new TriStore.Section(sx, sy, sz, v, f, new float[0]));
		});
	}

	private Voxelizer.Result run(int sy) {
		return Voxelizer.voxelize((x, y, z) -> this.store.get(TriStore.key(x, y, z)), 0, sy, 0);
	}

	private static int index(int x, int y, int z) {
		return ((y & 15) * 16 + (z & 15)) * 16 + (x & 15);
	}

	@Test
	void flatFloorSplitsTheBlockItCuts() {
		floor(-20, -20, 20, 20, 3.5);
		commit();
		Voxelizer.Result r = run(0);
		assertEquals(Voxelizer.FULL, r.kind[index(5, 2, 5)]);
		assertEquals(Voxelizer.PARTIAL, r.kind[index(5, 3, 5)]);
		assertEquals(Voxelizer.EMPTY, r.kind[index(5, 4, 5)]);
		long[] m = r.mask(index(5, 3, 5));
		for (int l = 0; l < 8; l++) {
			assertEquals(l < 4 ? -1L : 0L, m[l], "layer " + l);
		}
		assertEquals(16 * 16 * 3, r.full);
		assertEquals(16 * 16, r.partial);
		assertFalse(r.unresolved);
		assertEquals(1, r.material[index(5, 3, 5)]);
	}

	@Test
	void overhangIsOpenUnderneath() {
		floor(-20, -20, 20, 20, 2);     // seabed
		ceiling(-20, -20, 20, 20, 9);   // underside of an arch
		floor(-20, -20, 20, 20, 12);    // its top
		commit();
		Voxelizer.Result r = run(0);
		assertEquals(Voxelizer.FULL, r.kind[index(0, 1, 0)]);
		assertEquals(Voxelizer.EMPTY, r.kind[index(0, 5, 0)]);
		assertEquals(Voxelizer.FULL, r.kind[index(0, 10, 0)]);
		assertEquals(Voxelizer.EMPTY, r.kind[index(0, 13, 0)]);
	}

	@Test
	void slopeGivesPartialStairs() {
		// Rises 1 block per 2 along x: y = 4 + x / 2.
		quad(new double[] {-1, 3.5, -1}, new double[] {-1, 3.5, 17}, new double[] {17, 12.5, 17}, new double[] {17, 12.5, -1});
		commit();
		Voxelizer.Result r = run(0);
		for (int x = 0; x < 16; x += 3) {
			double top = 4 + (x + 0.5) / 2;
			assertEquals(Voxelizer.FULL, r.kind[index(x, (int) top - 1, 3)], "below at x " + x);
			assertEquals(Voxelizer.EMPTY, r.kind[index(x, (int) top + 1, 3)], "above at x " + x);
		}
	}

	@Test
	void buriedSectionIsSolidFromTheFloorAbove() {
		floor(-20, -20, 20, 20, 20.5); // in section 1
		commit(0);
		Voxelizer.Result r = run(0);
		assertEquals(4096, r.full);
		assertFalse(r.unresolved);
		assertEquals(1, r.material[index(3, 3, 3)]);
	}

	@Test
	void openWaterSectionUnderAnotherIsEmpty() {
		floor(-20, -20, 20, 20, -12); // seabed in section -1
		commit(0, 1);
		assertEquals(0, run(0).full + run(0).partial);
		assertFalse(run(0).unresolved);
		Voxelizer.Result above = run(1);
		assertEquals(0, above.full + above.partial);
	}

	@Test
	void nothingKnownIsUnresolved() {
		commit(0);
		Voxelizer.Result r = run(0);
		assertTrue(r.unresolved);
		assertEquals(0, r.full + r.partial);
	}

	@Test
	void closedBoxIsSolidInsideOnly() {
		double x0 = 4, x1 = 8, y0 = 4, y1 = 6, z0 = 4, z1 = 8;
		floor(x0, z0, x1, z1, y1);
		ceiling(x0, z0, x1, z1, y0);
		commit(1, -1);
		Voxelizer.Result r = run(0);
		assertEquals(Voxelizer.FULL, r.kind[index(5, 4, 5)]);
		assertEquals(Voxelizer.FULL, r.kind[index(5, 5, 5)]);
		assertEquals(Voxelizer.EMPTY, r.kind[index(5, 7, 5)]);
		assertEquals(Voxelizer.EMPTY, r.kind[index(5, 2, 5)]);
		// Outside the box's footprint nothing crosses and the neighbours are known empty: open.
		assertEquals(Voxelizer.EMPTY, r.kind[index(12, 5, 12)]);
		assertEquals(4 * 2 * 4, r.full);
	}

	@Test
	void materialOfTheSurfaceAboveTheBlock() {
		this.material = 2; // sand
		floor(-20, -20, 20, 20, 7.25);
		commit();
		Voxelizer.Result r = run(0);
		assertEquals(2, r.material[index(1, 7, 1)]);
		assertEquals(2, r.material[index(1, 0, 1)]);
	}

	@Test
	void structureTrianglesMarkTheirBlocks() {
		this.material = 4; // metal
		this.structureFlag = 1; // kTriStructure
		floor(-20, -20, 20, 20, 5.5);
		commit();
		Voxelizer.Result r = run(0);
		int m = r.material[index(2, 5, 2)] & 0xFF;
		assertEquals(Voxelizer.STRUCTURE_BIT, m & Voxelizer.STRUCTURE_BIT);
		assertEquals(4, m & ~Voxelizer.STRUCTURE_BIT);
	}

	@Test
	void structureHullIsAShellNotAFill() {
		this.structureFlag = 1;
		// A closed 4x4x4 box (top, bottom, four walls) from (4,4,4) to (8,8,8).
		floor(4, 4, 8, 8, 8);
		ceiling(4, 4, 8, 8, 4);
		quad(new double[] {4, 4, 4}, new double[] {4, 8, 4}, new double[] {8, 8, 4}, new double[] {8, 4, 4});
		quad(new double[] {4, 4, 8}, new double[] {8, 4, 8}, new double[] {8, 8, 8}, new double[] {4, 8, 8});
		quad(new double[] {4, 4, 4}, new double[] {4, 4, 8}, new double[] {4, 8, 8}, new double[] {4, 8, 4});
		quad(new double[] {8, 4, 4}, new double[] {8, 8, 4}, new double[] {8, 8, 8}, new double[] {8, 4, 8});
		commit(1, -1);
		Voxelizer.Result r = run(0);
		assertEquals(Voxelizer.EMPTY, r.kind[index(6, 6, 6)], "inside the hull is open");
		assertEquals(Voxelizer.PARTIAL, r.kind[index(6, 4, 6)], "the floor plate");
		assertEquals(Voxelizer.PARTIAL, r.kind[index(4, 6, 6)], "a wall");
		assertEquals(0, r.full);
	}
}
