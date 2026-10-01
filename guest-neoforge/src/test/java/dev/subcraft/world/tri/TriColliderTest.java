package dev.subcraft.world.tri;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TriColliderTest {
	private static final double R = 0.3, H = 1.8;
	private final List<float[]> tris = new ArrayList<>();

	@BeforeEach
	void reset() {
		TriStore.clear();
		this.tris.clear();
	}

	private void quad(double[] a, double[] b, double[] c, double[] d) {
		this.tris.add(new float[] {(float) a[0], (float) a[1], (float) a[2], (float) b[0], (float) b[1], (float) b[2], (float) c[0], (float) c[1], (float) c[2]});
		this.tris.add(new float[] {(float) a[0], (float) a[1], (float) a[2], (float) c[0], (float) c[1], (float) c[2], (float) d[0], (float) d[1], (float) d[2]});
	}

	/** Puts every triangle into every section it overlaps (as the host's per-section messages do). */
	private void commit() {
		java.util.Map<Long, List<float[]>> per = new java.util.HashMap<>();
		for (float[] t : this.tris) {
			int sx0 = (int) Math.floor(Math.min(t[0], Math.min(t[3], t[6]))) >> 4, sx1 = (int) Math.floor(Math.max(t[0], Math.max(t[3], t[6]))) >> 4;
			int sy0 = (int) Math.floor(Math.min(t[1], Math.min(t[4], t[7]))) >> 4, sy1 = (int) Math.floor(Math.max(t[1], Math.max(t[4], t[7]))) >> 4;
			int sz0 = (int) Math.floor(Math.min(t[2], Math.min(t[5], t[8]))) >> 4, sz1 = (int) Math.floor(Math.max(t[2], Math.max(t[5], t[8]))) >> 4;
			for (int x = sx0; x <= sx1; x++)
				for (int y = sy0; y <= sy1; y++)
					for (int z = sz0; z <= sz1; z++)
						per.computeIfAbsent(TriStore.key(x, y, z), k -> new ArrayList<>()).add(t);
		}
		per.forEach((k, list) -> {
			float[] v = new float[list.size() * 9];
			for (int i = 0; i < list.size(); i++) System.arraycopy(list.get(i), 0, v, i * 9, 9);
			int sx = (int) (k >> 42), sy = (int) (k >> 22 & 0xFFFFF), sz = (int) (k & 0x3FFFFF);
			// Undo the key's masking for negative coordinates.
			sx = sx << 10 >> 10;
			sy = sy << 12 >> 12;
			sz = sz << 10 >> 10;
			TriStore.put(sx, sy, sz, v, new int[list.size()]);
		});
	}

	private void floor(double y) {
		quad(new double[] {-20, y, -20}, new double[] {-20, y, 20}, new double[] {20, y, 20}, new double[] {20, y, -20});
	}

	@Test
	void landsOnTheFloor() {
		floor(0);
		commit();
		double[] m = TriCollider.get().resolve(0.5, 0.5, 0.5, R, H, 0, false, 0, -1, 0);
		assertEquals(-0.5, m[1], 1e-3);
		assertTrue(TriCollider.get().touched);
	}

	@Test
	void doesNotTunnelAtTerminalVelocity() {
		floor(0);
		commit();
		double[] m = TriCollider.get().resolve(0.5, 3, 0.5, R, H, 0, false, 0, -3.9, 0);
		assertEquals(-3, m[1], 1e-3);
	}

	@Test
	void slidesAlongAWall() {
		// Wall at x = 1 (the solid is beyond it).
		quad(new double[] {1, -5, -20}, new double[] {1, 5, -20}, new double[] {1, 5, 20}, new double[] {1, -5, 20});
		commit();
		double[] m = TriCollider.get().resolve(0, 0, 0, R, H, 0, false, 1, 0, 1);
		assertEquals(1 - R, m[0], 1e-3, "stops one radius from the wall");
		assertEquals(1, m[2], 1e-3, "keeps all the motion along the wall");
	}

	@Test
	void swimsAlongASlopedFaceAtOneRadius() {
		// A 45 degree rock face: the plane y = x (solid below/right). Swimming shape: 0.6 x 0.6.
		quad(new double[] {-10, -10, -20}, new double[] {-10, -10, 20}, new double[] {10, 10, 20}, new double[] {10, 10, -20});
		commit();
		double h = 0.6;
		// Sphere centre one radius above the face, swimming diagonally into it (+x) and along it (+z).
		double cx = -2, cy = -2 + R * Math.sqrt(2);
		double x = cx, y = cy - h / 2, z = 0;
		for (int tick = 0; tick < 20; tick++) {
			double[] m = TriCollider.get().resolve(x, y, z, R, h, 0, false, 0.2, 0, 0.1);
			x += m[0];
			y += m[1];
			z += m[2];
			// Distance from the sphere centre to the plane y = x must stay one radius.
			double centreY = y + h / 2;
			double dist = (centreY - x) / Math.sqrt(2);
			assertEquals(R, dist, 2e-3, "tick " + tick + " stays on the face");
		}
		assertTrue(y > cy - h / 2 + 1.0, "climbed the face");
		assertEquals(2.0, z, 1e-3, "the along-face motion is untouched");
	}

	@Test
	void stepsUpAHalfBlockLedge() {
		floor(0);
		// A box from x = 1 to 3, 0.5 high: top face and the riser at x = 1.
		quad(new double[] {1, 0.5, -5}, new double[] {1, 0.5, 5}, new double[] {3, 0.5, 5}, new double[] {3, 0.5, -5});
		quad(new double[] {1, 0, -5}, new double[] {1, 0.5, -5}, new double[] {1, 0.5, 5}, new double[] {1, 0, 5});
		commit();
		double x = 0.5, y = 0;
		for (int tick = 0; tick < 10; tick++) {
			double[] m = TriCollider.get().resolve(x, y, 0, R, H, 0.6, true, 0.2, -0.08, 0);
			x += m[0];
			y += m[1];
		}
		assertTrue(x > 1.5, "walked onto the ledge, x = " + x);
		assertEquals(0.5, y, 0.01);
	}

	@Test
	void standsStillOnAGentleSlope() {
		// 30 degree slope: y = 0.577 x. Gravity alone must not slide the player downhill.
		quad(new double[] {-10, -5.77, -20}, new double[] {-10, -5.77, 20}, new double[] {10, 5.77, 20}, new double[] {10, 5.77, -20});
		commit();
		double x = 0, y = 0.5, z = 0;
		for (int tick = 0; tick < 40; tick++) {
			double[] m = TriCollider.get().resolve(x, y, z, R, H, 0.6, tick > 0, 0, -0.08, 0);
			x += m[0];
			y += m[1];
			z += m[2];
		}
		assertEquals(0, x, 1e-3, "no sideways creep");
		// Resting: sphere centre r / cos(30) above the slope, feet r below the centre.
		assertEquals(R / Math.cos(Math.toRadians(30)) - R, y, 2e-3);
	}

	@Test
	void ignoresTrianglesOutOfReach() {
		floor(0);
		commit();
		double[] m = TriCollider.get().resolve(0, 10, 0, R, H, 0, false, 0.3, 0, 0);
		assertArrayEquals(new double[] {0.3, 0, 0}, m, 1e-9);
	}
}
