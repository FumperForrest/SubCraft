package dev.subcraft.world.tri;

/**
 * Moves a capsule against the host's exact triangles: Minecraft computes the movement (swimming,
 * walking, gravity, drag), this only decides how far it gets and slides it along what it touches.
 * Derived from SkyCraft's TriCollider idea (MIT, chasmlol), redone for full 3D sliding underwater.
 *
 * Algorithm: sub-steps of at most {@link #SUBSTEP} (less than the radius, so nothing tunnels);
 * after each, the capsule is pushed out of every triangle it overlaps along the contact
 * direction. Pushing out along the contact removes only the motion into a surface, so motion
 * along it survives: swimming diagonally into a rock face glides along the face. On ground, a
 * blocked move retries one step height up (ledges, the lifepod's floor lip) like Minecraft's step.
 *
 * One instance per thread (client and integrated server both move the player).
 */
public final class TriCollider {
	public static final double SUBSTEP = 0.1;
	private static final int MAX_SUBSTEPS = 48;
	private static final int ITERATIONS = 4;
	private static final double SKIN = 1e-4;
	/** Surfaces whose normal is at most ~50 degrees from up are ground: stood on, not slid down. */
	public static final double WALKABLE_NY = 0.64;

	private static final ThreadLocal<TriCollider> PER_THREAD = ThreadLocal.withInitial(TriCollider::new);

	private float[] tris = new float[9 * 256];
	private int count;
	private final double[] closest = new double[6];
	private final double[] tmp = new double[6];
	private double px, py, pz;
	private double radius, height;
	private boolean verticalOnly;

	/** Sum of the contact normals of the last resolve (zero if nothing was touched). */
	public double contactX, contactY, contactZ;
	public boolean touched;

	public static TriCollider get() {
		return PER_THREAD.get();
	}

	/**
	 * Resolves one tick of movement for a capsule standing on (x, y, z) with the given radius and
	 * height. Returns the allowed movement {dx, dy, dz}.
	 */
	public double[] resolve(double x, double y, double z, double radius, double height, double stepHeight, boolean onGround, double dx, double dy,
		double dz) {
		this.radius = radius;
		this.height = height;
		this.touched = false;
		this.contactX = this.contactY = this.contactZ = 0;
		double reach = radius + 0.2;
		gather(Math.min(x, x + dx) - reach, Math.min(y, y + dy) - reach - stepHeight, Math.min(z, z + dz) - reach,
			Math.max(x, x + dx) + reach, Math.max(y, y + dy) + height + reach + stepHeight, Math.max(z, z + dz) + reach);
		if (this.count == 0) {
			return new double[] {dx, dy, dz};
		}
		slide(x, y, z, dx, dy, dz);
		double rx = this.px - x, ry = this.py - y, rz = this.pz - z;
		double wanted = Math.hypot(dx, dz);
		if (stepHeight > 0 && onGround && wanted > 1e-4 && Math.hypot(rx, rz) < wanted * 0.9) {
			// Blocked on the ground: try the same move one step up, then settle back down.
			boolean t = this.touched;
			double cx = this.contactX, cy = this.contactY, cz = this.contactZ;
			slide(x, y, z, 0, stepHeight, 0);
			double sy = this.py;
			slide(this.px, this.py, this.pz, dx, 0, dz);
			this.verticalOnly = true; // settle onto the ledge's edge instead of rolling back off it
			slide(this.px, this.py, this.pz, 0, -(this.py - y) - Math.max(0, -dy), 0);
			this.verticalOnly = false;
			double hx = this.px - x, hz = this.pz - z;
			if (Math.hypot(hx, hz) > Math.hypot(rx, rz) + 1e-3 && this.py - y <= stepHeight + 1e-3 && sy > y) {
				return new double[] {hx, this.py - y, hz};
			}
			this.touched = t;
			this.contactX = cx;
			this.contactY = cy;
			this.contactZ = cz;
		}
		return new double[] {rx, ry, rz};
	}

	/** Moves from (x, y, z) by (dx, dy, dz) in sub-steps, pushing out after each; result in px/py/pz. */
	private void slide(double x, double y, double z, double dx, double dy, double dz) {
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		int steps = Math.min(MAX_SUBSTEPS, Math.max(1, (int) Math.ceil(len / SUBSTEP)));
		this.px = x;
		this.py = y;
		this.pz = z;
		for (int i = 0; i < steps; i++) {
			this.px += dx / steps;
			this.py += dy / steps;
			this.pz += dz / steps;
			depenetrate();
		}
	}

	private void depenetrate() {
		double r = this.radius;
		for (int iter = 0; iter < ITERATIONS; iter++) {
			boolean moved = false;
			for (int i = 0; i < this.count; i++) {
				// The capsule's core segment (a sphere if the shape is shorter than it is wide).
				double lowY, highY, rr = r;
				if (this.height >= 2 * r) {
					lowY = this.py + r;
					highY = this.py + this.height - r;
				} else {
					rr = this.height / 2;
					lowY = highY = this.py + rr;
				}
				double d2 = TriGeometry.segmentTriangle(this.px, lowY, this.pz, this.px, highY, this.pz, this.tris, i * 9, this.closest, this.tmp);
				if (d2 >= rr * rr) {
					continue;
				}
				double nx, ny, nz;
				double dist = Math.sqrt(d2);
				if (dist > 1e-7) {
					nx = (this.closest[0] - this.closest[3]) / dist;
					ny = (this.closest[1] - this.closest[4]) / dist;
					nz = (this.closest[2] - this.closest[5]) / dist;
				} else {
					// The core touches the surface: leave along the face normal.
					double[] n = faceNormal(i);
					nx = n[0];
					ny = n[1];
					nz = n[2];
				}
				double push = rr - dist + SKIN;
				boolean ground = ny >= WALKABLE_NY;
				if (this.verticalOnly || ground) {
					// Ground (and the step-up settle): lift straight up until the contact is one radius
					// away, so gravity into a slope doesn't turn into sliding down it (Minecraft's flat
					// block tops and Subnautica's diver both stand still on a slope).
					if (ny < 0.05) {
						continue; // not something to stand on
					}
					// Lift straight up until the contact is one radius away again.
					this.py += Math.min(push / ny, rr);
				} else {
					this.px += nx * push;
					this.py += ny * push;
					this.pz += nz * push;
				}
				if (ground) {
					this.contactY += 1; // ground stops vertical motion only
				} else {
					this.contactX += nx;
					this.contactY += ny;
					this.contactZ += nz;
				}
				this.touched = true;
				moved = true;
			}
			if (!moved) {
				return;
			}
		}
	}

	private double[] faceNormal(int i) {
		int o = i * 9;
		float[] t = this.tris;
		double ux = t[o + 3] - t[o], uy = t[o + 4] - t[o + 1], uz = t[o + 5] - t[o + 2];
		double vx = t[o + 6] - t[o], vy = t[o + 7] - t[o + 1], vz = t[o + 8] - t[o + 2];
		double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return l < 1e-12 ? new double[] {0, 1, 0} : new double[] {nx / l, ny / l, nz / l};
	}

	private void gather(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		this.count = 0;
		TriStore.query(minX, minY, minZ, maxX, maxY, maxZ, (s, i) -> {
			if ((this.count + 1) * 9 > this.tris.length) {
				this.tris = java.util.Arrays.copyOf(this.tris, this.tris.length * 2);
			}
			System.arraycopy(s.v(), i * 9, this.tris, this.count * 9, 9);
			this.count++;
		});
	}

	/** Test hook: how many triangles the last resolve considered. */
	public int lastCount() {
		return this.count;
	}
}
