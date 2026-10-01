package dev.subcraft.world.ghost;

import dev.subcraft.world.tri.TriStore;

/**
 * Turns one 16-block section of the host's collision triangles into ghost-terrain blocks: each
 * block is empty, full, or partial with an 8x8x8 occupancy mask (ColBlock layout: bits[y] bit
 * z * 8 + x). Pure Java, no Minecraft classes, so it is unit-tested without the game.
 *
 * Inside/outside comes from the triangles' orientation (counter-clockwise seen from outside, so
 * the normal points out of the solid) along vertical lines through the centres of the 128 x 128
 * sub-voxel columns: a sub-voxel is solid when the nearest crossing above it faces up (we are
 * under a floor), or, with nothing above, the nearest below faces down (we are above a ceiling). Terrain is open at section borders, so
 * this needs no closed mesh. Columns without any crossing in the section take their state from
 * the nearest known section above (its lowest crossing), else below (its highest crossing),
 * else they are open water.
 */
public final class Voxelizer {
	public static final int RES = 8;               // sub-voxels per block edge
	public static final int COLS = 16 * RES;       // sub-voxel columns per section edge
	public static final byte EMPTY = 0, FULL = 1, PARTIAL = 2;
	/** How far (in sections) to look up or down for a column with no crossing in its own section. */
	private static final int REACH = 6;

	/** Where neighbouring sections come from (TriStore in the game, a map in tests). */
	@FunctionalInterface
	public interface Source {
		TriStore.Section section(int sx, int sy, int sz);
	}

	/** The voxelized section. Block index = (y * 16 + z) * 16 + x. */
	public static final class Result {
		public final int sx, sy, sz;
		public final byte[] kind = new byte[4096];
		public final byte[] material = new byte[4096];
		/** 8 longs per block (bits[y]); meaningful only for PARTIAL blocks. */
		public final long[] masks = new long[4096 * 8];
		/** Some columns had no crossing anywhere we could see: a later neighbour may change them. */
		public boolean unresolved;
		/** Some columns took their state from a neighbouring section: redo when one of those changes. */
		public boolean usedNeighbours;
		public int full, partial;

		Result(int sx, int sy, int sz) {
			this.sx = sx;
			this.sy = sy;
			this.sz = sz;
		}

		public long[] mask(int index) {
			long[] m = new long[8];
			System.arraycopy(this.masks, index * 8, m, 0, 8);
			return m;
		}
	}

	/** Per-column crossings of one section, sorted by y. */
	static final class Columns {
		final int[] start = new int[COLS * COLS + 1];
		float[] y;
		boolean[] up;      // outward normal has +y: crossing upward leaves the solid
		byte[] material;

		int count(int col) {
			return this.start[col + 1] - this.start[col];
		}
	}

	private Voxelizer() {
	}

	public static Result voxelize(Source source, int sx, int sy, int sz) {
		Result r = new Result(sx, sy, sz);
		TriStore.Section self = source.section(sx, sy, sz);
		Columns cols = self == null ? null : columns(self, sx, sz);
		float baseY = sy * 16;
		for (int cz = 0; cz < COLS; cz++) {
			for (int cx = 0; cx < COLS; cx++) {
				int col = cz * COLS + cx;
				int n = cols == null ? 0 : cols.count(col);
				if (n == 0) {
					int ext = external(source, sx, sy, sz, col);
					r.usedNeighbours = true;
					if (ext == 0) {
						r.unresolved = true;
						continue;
					}
					if (ext > 0) {
						for (int j = 0; j < COLS; j++) {
							set(r, cx, j, cz, (byte) (ext >> 8));
						}
					}
					continue;
				}
				int s = cols.start[col], e = s + n;
				// Sweep upward; k = first crossing above the current sub-voxel centre.
				int k = s;
				for (int j = 0; j < COLS; j++) {
					float y = baseY + (j + 0.5f) / RES;
					while (k < e && cols.y[k] <= y) {
						k++;
					}
					boolean solid;
					byte mat;
					if (k < e) {
						solid = cols.up[k];
						mat = cols.material[k];
					} else {
						solid = !cols.up[e - 1];
						mat = cols.material[e - 1];
					}
					if (solid) {
						set(r, cx, j, cz, mat);
					}
				}
			}
		}
		for (int i = 0; i < 4096; i++) {
			int bits = 0;
			for (int l = 0; l < 8; l++) {
				bits += Long.bitCount(r.masks[i * 8 + l]);
			}
			if (bits == 512) {
				r.kind[i] = FULL;
				r.full++;
			} else if (bits > 0) {
				r.kind[i] = PARTIAL;
				r.partial++;
			}
		}
		return r;
	}

	private static void set(Result r, int cx, int cy, int cz, byte mat) {
		int bx = cx >> 3, by = cy >> 3, bz = cz >> 3;
		int index = (by * 16 + bz) * 16 + bx;
		r.masks[index * 8 + (cy & 7)] |= 1L << ((cz & 7) * 8 + (cx & 7));
		r.material[index] = mat; // sweeping upward: the topmost solid sub-voxel's surface wins
	}

	/**
	 * State of a column with no crossing in its own section, from the nearest section that has one:
	 * 0 unknown, -1 open, else (material << 8) | 1 for solid.
	 */
	private static int external(Source source, int sx, int sy, int sz, int col) {
		for (int d = 1; d <= REACH; d++) {
			TriStore.Section s = source.section(sx, sy + d, sz);
			if (s == null) {
				break;
			}
			Columns c = cached(s, sx, sz);
			int n = c.count(col);
			if (n > 0) {
				int k = c.start[col];
				// Everything below the lowest crossing: inside if that surface faces up.
				return c.up[k] ? (c.material[k] & 0xFF) << 8 | 1 : -1;
			}
		}
		for (int d = 1; d <= REACH; d++) {
			TriStore.Section s = source.section(sx, sy - d, sz);
			if (s == null) {
				break;
			}
			Columns c = cached(s, sx, sz);
			int n = c.count(col);
			if (n > 0) {
				int k = c.start[col] + n - 1;
				return c.up[k] ? -1 : (c.material[k] & 0xFF) << 8 | 1;
			}
		}
		return 0;
	}

	// Column crossings of neighbours are reused while their snapshot is unchanged.
	private static final java.util.Map<TriStore.Section, Columns> CACHE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	private static Columns cached(TriStore.Section s, int sx, int sz) {
		Columns c = CACHE.get(s);
		if (c == null) {
			c = columns(s, sx, sz);
			CACHE.put(s, c);
		}
		return c;
	}

	/** Crossings of every triangle of the section with the vertical lines through its column centres. */
	static Columns columns(TriStore.Section s, int sx, int sz) {
		float[] v = s.v();
		int[] flags = s.flags();
		int n = flags.length;
		float ox = sx * 16, oz = sz * 16;
		// Pass 1 counts, pass 2 fills (columns are a CSR array).
		Columns c = new Columns();
		int[] counts = new int[COLS * COLS];
		for (int pass = 0; pass < 2; pass++) {
			int[] fill = pass == 1 ? new int[COLS * COLS] : null;
			for (int t = 0; t < n; t++) {
				int o = t * 9;
				float ax = v[o], ay = v[o + 1], az = v[o + 2];
				float bx = v[o + 3], by = v[o + 4], bz = v[o + 5];
				float qx = v[o + 6], qy = v[o + 7], qz = v[o + 8];
				// Normal y (right-handed, CCW from outside): (b - a) x (c - a), y component.
				float e1x = bx - ax, e1z = bz - az, e2x = qx - ax, e2z = qz - az;
				float ny = e1z * e2x - e1x * e2z;
				if (Math.abs(ny) < 1e-9f) {
					continue; // vertical: no vertical line crosses it
				}
				float minX = Math.min(ax, Math.min(bx, qx)) - ox, maxX = Math.max(ax, Math.max(bx, qx)) - ox;
				float minZ = Math.min(az, Math.min(bz, qz)) - oz, maxZ = Math.max(az, Math.max(bz, qz)) - oz;
				// Column centres (i + 0.5) / RES inside [min, max].
				int i0 = Math.max(0, (int) Math.ceil(minX * RES - 0.5f)), i1 = Math.min(COLS - 1, (int) Math.floor(maxX * RES - 0.5f));
				int k0 = Math.max(0, (int) Math.ceil(minZ * RES - 0.5f)), k1 = Math.min(COLS - 1, (int) Math.floor(maxZ * RES - 0.5f));
				if (i0 > i1 || k0 > k1) {
					continue;
				}
				// Full normal for the plane equation.
				float e1y = by - ay, e2y = qy - ay;
				float nx = e1y * e2z - e1z * e2y;
				float nz = e1x * e2y - e1y * e2x;
				for (int k = k0; k <= k1; k++) {
					float pz = oz + (k + 0.5f) / RES;
					for (int i = i0; i <= i1; i++) {
						float px = ox + (i + 0.5f) / RES;
						if (!inside2d(px, pz, ax, az, bx, bz, qx, qz)) {
							continue;
						}
						int col = k * COLS + i;
						if (pass == 0) {
							counts[col]++;
						} else {
							float y = ay - (nx * (px - ax) + nz * (pz - az)) / ny;
							int at = c.start[col] + fill[col]++;
							c.y[at] = y;
							c.up[at] = ny > 0;
							c.material[at] = (byte) (flags[t] >>> 8);
						}
					}
				}
			}
			if (pass == 0) {
				for (int col = 0; col < COLS * COLS; col++) {
					c.start[col + 1] = c.start[col] + counts[col];
				}
				int total = c.start[COLS * COLS];
				c.y = new float[total];
				c.up = new boolean[total];
				c.material = new byte[total];
			}
		}
		// Sort each column by y (few crossings per column: insertion sort).
		for (int col = 0; col < COLS * COLS; col++) {
			int s0 = c.start[col], e0 = c.start[col + 1];
			for (int a = s0 + 1; a < e0; a++) {
				float y = c.y[a];
				boolean u = c.up[a];
				byte m = c.material[a];
				int b = a - 1;
				while (b >= s0 && c.y[b] > y) {
					c.y[b + 1] = c.y[b];
					c.up[b + 1] = c.up[b];
					c.material[b + 1] = c.material[b];
					b--;
				}
				c.y[b + 1] = y;
				c.up[b + 1] = u;
				c.material[b + 1] = m;
			}
		}
		return c;
	}

	/** Point in triangle in the xz plane, edges inclusive (a line through a shared edge sees both: harmless, same y). */
	private static boolean inside2d(float px, float pz, float ax, float az, float bx, float bz, float cx, float cz) {
		float d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
		float d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz);
		float d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az);
		boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
		boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
		return !(neg && pos);
	}
}
