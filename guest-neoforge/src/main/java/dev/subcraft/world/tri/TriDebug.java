package dev.subcraft.world.tri;

/** "subcraft tris": what the triangle store knows around a point (dev harness). */
public final class TriDebug {
	private TriDebug() {
	}

	public static String report(double x, double y, double z) {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("at %.2f %.2f %.2f, %d sections known, generation %d%n", x, y, z, TriStore.sectionCount(), TriStore.generation()));
		int sx = (int) Math.floor(x) >> 4, sy = (int) Math.floor(y) >> 4, sz = (int) Math.floor(z) >> 4;
		for (int dy = 1; dy >= -2; dy--) {
			TriStore.Section s = TriStore.section(sx, sy + dy, sz);
			sb.append(String.format("section (%d, %d, %d) y %d..%d: %s%n", sx, sy + dy, sz, (sy + dy) * 16, (sy + dy) * 16 + 15,
				s == null ? "unknown" : s.count() + " triangles"));
		}
		// Nearest triangle below the point within 10 blocks, by vertical distance under (x, z).
		double[] best = {Double.NaN};
		double[] tmp = new double[6];
		TriStore.query(x - 0.5, y - 10, z - 0.5, x + 0.5, y + 2, z + 0.5, (sec, i) -> {
			double[] c = new double[3];
			TriGeometry.closestPointTriangle(x, y, z, sec.v()[i * 9], sec.v()[i * 9 + 1], sec.v()[i * 9 + 2], sec.v()[i * 9 + 3], sec.v()[i * 9 + 4],
				sec.v()[i * 9 + 5], sec.v()[i * 9 + 6], sec.v()[i * 9 + 7], sec.v()[i * 9 + 8], c);
			double d = Math.sqrt((c[0] - x) * (c[0] - x) + (c[1] - y) * (c[1] - y) + (c[2] - z) * (c[2] - z));
			if (Double.isNaN(best[0]) || d < best[0]) {
				best[0] = d;
				tmp[0] = c[0];
				tmp[1] = c[1];
				tmp[2] = c[2];
			}
		});
		sb.append(Double.isNaN(best[0]) ? "no triangle within the column below" : String.format("nearest triangle point %.2f %.2f %.2f (distance %.3f)", tmp[0], tmp[1], tmp[2], best[0]));
		// The collider itself: a 3-block fall from 2 blocks above, standing shape.
		TriCollider c = TriCollider.get();
		double[] m = c.resolve(x, y + 2, z, 0.3, 1.8, 0, false, 0, -3, 0);
		sb.append(String.format("%nfall probe from y %.2f: moved %.3f (stopped at %.3f), touched %s, considered %d triangles", y + 2, m[1], y + 2 + m[1],
			c.touched, c.lastCount()));
		return sb.toString();
	}
}
