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
	private boolean terrainFlag = true; // kTriTerrain unless a test builds a prop

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
		this.mats.add(this.material << 8 | this.structureFlag | (this.terrainFlag && this.structureFlag == 0 ? 2 : 0));
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

	/**
	 * Found in game (2026-10-02): a double-sided or mirrored prop on the seabed gives an up-facing
	 * crossing with a down-facing one just above it. Judged by the nearest crossing alone, "a
	 * down-facing surface below and nothing above" filled every section up to the sky.
	 */
	@Test
	void insideOutThinShellDoesNotFillTheWaterAbove() {
		floor(-20, -20, 20, 20, -9.74);   // seabed
		this.terrainFlag = false;
		floor(-20, -20, 20, 20, -4.93);   // the prop: faces up...
		ceiling(-20, -20, 20, 20, -4.81); // ...with a face just above it facing down
		commit(0, 1);
		Voxelizer.Result below = run(-1);
		assertEquals(Voxelizer.FULL, below.kind[index(0, -12, 0)], "inside the seabed");
		assertEquals(Voxelizer.EMPTY, below.kind[index(0, -2, 0)], "water above the prop");
		assertEquals(0, run(0).full + run(0).partial, "open water");
		assertEquals(0, run(1).full + run(1).partial, "sky");
	}

	@Test
	void duplicatePropFacesCountOnce() {
		floor(-20, -20, 20, 20, -24.5); // seabed
		this.terrainFlag = false;
		ceiling(-20, -20, 20, 20, -12); // a prop's bottom...
		ceiling(-20, -20, 20, 20, -12); // ...twice (two overlapping colliders)
		floor(-20, -20, 20, 20, -7);    // its top
		commit(0);
		Voxelizer.Result r = run(-1);
		assertEquals(Voxelizer.FULL, r.kind[index(0, -10, 0)], "inside the prop");
		assertEquals(Voxelizer.EMPTY, r.kind[index(0, -3, 0)], "water above it");
		assertEquals(0, run(0).full + run(0).partial);
	}

	@Test
	void propBottomUnderTheSeabedKeepsTheRockSolid() {
		floor(-20, -20, 20, 20, -9.87); // seabed
		this.terrainFlag = false;
		ceiling(-20, -20, 20, 20, -9.97); // a prop sunk into it
		floor(-20, -20, 20, 20, -9.05);
		commit(0);
		Voxelizer.Result r = run(-1);
		assertEquals(Voxelizer.FULL, r.kind[index(0, -14, 0)], "rock under the seabed");
		assertEquals(Voxelizer.EMPTY, r.kind[index(0, -5, 0)], "water above");
	}

	@Test
	void propRestingOnTheSeabedIsSolid() {
		floor(-20, -20, 20, 20, 2); // seabed
		this.terrainFlag = false;
		double x0 = 4, x1 = 8, z0 = 4, z1 = 8;
		ceiling(x0, z0, x1, z1, 1.5);
		floor(x0, z0, x1, z1, 6);
		commit();
		Voxelizer.Result r = run(0);
		assertEquals(Voxelizer.FULL, r.kind[index(5, 4, 5)], "inside the prop, above the seabed");
		assertEquals(Voxelizer.EMPTY, r.kind[index(5, 8, 5)], "above the prop");
		assertEquals(Voxelizer.EMPTY, r.kind[index(12, 4, 12)], "beside it");
	}

	@Test
	void terrainSeamIsNotAWall() {
		// Overlapping terrain pieces at a seam: up, down, up within 2 cm (found in game).
		floor(-20, -20, 20, 20, -16.38);
		ceiling(-20, -20, 20, 20, -16.36);
		floor(-20, -20, 20, 20, -16.355);
		commit(0);
		Voxelizer.Result r = run(-2);
		assertEquals(Voxelizer.FULL, r.kind[index(0, -20, 0)]);
		assertEquals(0, run(-1).full + run(-1).partial, "water above the seam");
		assertEquals(0, run(0).full + run(0).partial);
	}

	@Test
	void crackInTheTopSurfaceIsNotAPillar() {
		// An arch: underside at -12, top at -6, but the top has a gap 0.2 wide at x 5.0..5.2 (a crack
		// between terrain pieces, found in the kelp forest); seabed at -20.
		floor(-20, -20, 20, 20, -20);
		ceiling(-20, -20, 20, 20, -12);
		floor(-20, -20, 5.0, 20, -6);
		floor(5.2, -20, 20, 20, -6);
		commit(0, 1);
		Voxelizer.Result r = run(-1);
		assertEquals(Voxelizer.FULL, r.kind[index(2, -9, 2)], "inside the arch");
		assertEquals(Voxelizer.EMPTY, r.kind[index(2, -3, 2)], "water above the arch");
		assertNotEquals(Voxelizer.FULL, r.kind[index(5, -3, 2)], "no pillar over the crack");
		assertEquals(0, run(0).full + run(0).partial, "open water above");
	}

	@Test
	void skirtUnderTheTopIsNotASecondInside() {
		// Kelp forest, a terrain cell edge: overhang ceiling, a skirt face just under the top, the top.
		ceiling(-20, -20, 20, 20, -54.94);
		ceiling(-20, -20, 20, 20, -33.12);
		floor(-20, -20, 20, 20, -32.80);
		commit(-1, 0);
		assertEquals(Voxelizer.FULL, run(-3).kind[index(0, -40, 0)], "inside the rock");
		assertEquals(0, run(-2).full + run(-2).partial, "water above the rock");
		assertEquals(0, run(0).full + run(0).partial);
	}

	@Test
	void caveCeilingWithoutItsFloorIsAirUnderneath() {
		ceiling(-20, -20, 20, 20, 5); // cave ceiling, the floor out of reach
		floor(-20, -20, 20, 20, 12);  // the ground on top
		commit();
		Voxelizer.Result r = run(0);
		assertEquals(Voxelizer.EMPTY, r.kind[index(0, 2, 0)]);
		assertEquals(Voxelizer.FULL, r.kind[index(0, 8, 0)]);
		assertEquals(Voxelizer.EMPTY, r.kind[index(0, 14, 0)]);
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
