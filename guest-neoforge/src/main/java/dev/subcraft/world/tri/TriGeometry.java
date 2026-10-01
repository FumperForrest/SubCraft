package dev.subcraft.world.tri;

/**
 * Closest-point queries between a segment and a triangle (Ericson, Real-Time Collision Detection,
 * 5.1.5 and 5.1.9). Plain doubles, no allocation in the hot path: results go into a scratch array.
 */
public final class TriGeometry {
	private TriGeometry() {
	}

	/**
	 * Closest points between segment p-q and triangle (a, b, c). Writes {sx, sy, sz, tx, ty, tz}
	 * (point on segment, point on triangle) into {@code out} and returns the squared distance.
	 */
	public static double segmentTriangle(double px, double py, double pz, double qx, double qy, double qz, float[] t, int o, double[] out, double[] tmp) {
		double ax = t[o], ay = t[o + 1], az = t[o + 2];
		double bx = t[o + 3], by = t[o + 4], bz = t[o + 5];
		double cx = t[o + 6], cy = t[o + 7], cz = t[o + 8];
		double best = Double.MAX_VALUE;

		// 1) The segment crosses the triangle: distance 0 at the crossing.
		double nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay);
		double ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az);
		double nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
		double dp = (px - ax) * nx + (py - ay) * ny + (pz - az) * nz;
		double dq = (qx - ax) * nx + (qy - ay) * ny + (qz - az) * nz;
		if ((dp <= 0 && dq >= 0 || dp >= 0 && dq <= 0) && dp != dq) {
			double s = dp / (dp - dq);
			double ix = px + (qx - px) * s, iy = py + (qy - py) * s, iz = pz + (qz - pz) * s;
			closestPointTriangle(ix, iy, iz, ax, ay, az, bx, by, bz, cx, cy, cz, tmp);
			if (sq(tmp[0] - ix, tmp[1] - iy, tmp[2] - iz) < 1e-12) {
				set(out, ix, iy, iz, ix, iy, iz);
				return 0;
			}
		}
		// 2) Segment end points against the triangle.
		closestPointTriangle(px, py, pz, ax, ay, az, bx, by, bz, cx, cy, cz, tmp);
		double d = sq(tmp[0] - px, tmp[1] - py, tmp[2] - pz);
		if (d < best) {
			best = d;
			set(out, px, py, pz, tmp[0], tmp[1], tmp[2]);
		}
		closestPointTriangle(qx, qy, qz, ax, ay, az, bx, by, bz, cx, cy, cz, tmp);
		d = sq(tmp[0] - qx, tmp[1] - qy, tmp[2] - qz);
		if (d < best) {
			best = d;
			set(out, qx, qy, qz, tmp[0], tmp[1], tmp[2]);
		}
		// 3) Segment against each edge.
		best = edge(px, py, pz, qx, qy, qz, ax, ay, az, bx, by, bz, best, out, tmp);
		best = edge(px, py, pz, qx, qy, qz, bx, by, bz, cx, cy, cz, best, out, tmp);
		best = edge(px, py, pz, qx, qy, qz, cx, cy, cz, ax, ay, az, best, out, tmp);
		return best;
	}

	private static double edge(double px, double py, double pz, double qx, double qy, double qz, double ex, double ey, double ez, double fx, double fy,
		double fz, double best, double[] out, double[] tmp) {
		double d = closestSegmentSegment(px, py, pz, qx, qy, qz, ex, ey, ez, fx, fy, fz, tmp);
		if (d < best) {
			System.arraycopy(tmp, 0, out, 0, 6);
			return d;
		}
		return best;
	}

	/** Ericson 5.1.5: closest point on triangle abc to p, into out[0..2]. */
	public static void closestPointTriangle(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz, double cx,
		double cy, double cz, double[] out) {
		double abx = bx - ax, aby = by - ay, abz = bz - az;
		double acx = cx - ax, acy = cy - ay, acz = cz - az;
		double apx = px - ax, apy = py - ay, apz = pz - az;
		double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
		if (d1 <= 0 && d2 <= 0) {
			set3(out, ax, ay, az);
			return;
		}
		double bpx = px - bx, bpy = py - by, bpz = pz - bz;
		double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
		if (d3 >= 0 && d4 <= d3) {
			set3(out, bx, by, bz);
			return;
		}
		double vc = d1 * d4 - d3 * d2;
		if (vc <= 0 && d1 >= 0 && d3 <= 0) {
			double v = d1 / (d1 - d3);
			set3(out, ax + v * abx, ay + v * aby, az + v * abz);
			return;
		}
		double cpx = px - cx, cpy = py - cy, cpz = pz - cz;
		double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
		if (d6 >= 0 && d5 <= d6) {
			set3(out, cx, cy, cz);
			return;
		}
		double vb = d5 * d2 - d1 * d6;
		if (vb <= 0 && d2 >= 0 && d6 <= 0) {
			double w = d2 / (d2 - d6);
			set3(out, ax + w * acx, ay + w * acy, az + w * acz);
			return;
		}
		double va = d3 * d6 - d5 * d4;
		if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
			double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
			set3(out, bx + w * (cx - bx), by + w * (cy - by), bz + w * (cz - bz));
			return;
		}
		double denom = 1.0 / (va + vb + vc);
		double v = vb * denom, w = vc * denom;
		set3(out, ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w);
	}

	/** Ericson 5.1.9: closest points between segments p1-q1 and p2-q2, into out[0..5]; returns squared distance. */
	public static double closestSegmentSegment(double p1x, double p1y, double p1z, double q1x, double q1y, double q1z, double p2x, double p2y, double p2z,
		double q2x, double q2y, double q2z, double[] out) {
		double d1x = q1x - p1x, d1y = q1y - p1y, d1z = q1z - p1z;
		double d2x = q2x - p2x, d2y = q2y - p2y, d2z = q2z - p2z;
		double rx = p1x - p2x, ry = p1y - p2y, rz = p1z - p2z;
		double a = d1x * d1x + d1y * d1y + d1z * d1z, e = d2x * d2x + d2y * d2y + d2z * d2z, f = d2x * rx + d2y * ry + d2z * rz;
		double s, t;
		if (a <= 1e-12 && e <= 1e-12) {
			s = t = 0;
		} else if (a <= 1e-12) {
			s = 0;
			t = clamp(f / e);
		} else {
			double c = d1x * rx + d1y * ry + d1z * rz;
			if (e <= 1e-12) {
				t = 0;
				s = clamp(-c / a);
			} else {
				double b = d1x * d2x + d1y * d2y + d1z * d2z;
				double denom = a * e - b * b;
				s = denom != 0 ? clamp((b * f - c * e) / denom) : 0;
				t = (b * s + f) / e;
				if (t < 0) {
					t = 0;
					s = clamp(-c / a);
				} else if (t > 1) {
					t = 1;
					s = clamp((b - c) / a);
				}
			}
		}
		double c1x = p1x + d1x * s, c1y = p1y + d1y * s, c1z = p1z + d1z * s;
		double c2x = p2x + d2x * t, c2y = p2y + d2y * t, c2z = p2z + d2z * t;
		set(out, c1x, c1y, c1z, c2x, c2y, c2z);
		return sq(c1x - c2x, c1y - c2y, c1z - c2z);
	}

	private static double clamp(double v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	private static double sq(double x, double y, double z) {
		return x * x + y * y + z * z;
	}

	private static void set3(double[] o, double x, double y, double z) {
		o[0] = x;
		o[1] = y;
		o[2] = z;
	}

	private static void set(double[] o, double a, double b, double c, double d, double e, double f) {
		o[0] = a;
		o[1] = b;
		o[2] = c;
		o[3] = d;
		o[4] = e;
		o[5] = f;
	}
}
