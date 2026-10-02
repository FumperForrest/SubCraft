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
 *
 * Host-built geometry (structure triangles: wrecks, the lifepod, habitats) is hollow: closed hulls
 * without inner faces would fill solid, so structures become a shell, the sub-voxels their
 * triangles pass through.
 */
public final class Voxelizer {
	public static final int RES = 8;               // sub-voxels per block edge
	public static final int COLS = 16 * RES;       // sub-voxel columns per section edge
	public static final byte EMPTY = 0, FULL = 1, PARTIAL = 2;
	/** Set in a block's material byte when its surface is host-built (ColTri kTriStructure). */
	public static final int STRUCTURE_BIT = 0x80;
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
		/** Patch stamp of the triangles this was built from (ChunkGhostData). */
		public long stamp;
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
		boolean[] terrain; // kTriTerrain: the host's terrain surface (else a prop's closed shell)
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
		// The column's known sections, contiguous around this one (an unknown section ends the reach).
		Columns[] stack = new Columns[2 * REACH + 1];
		stack[REACH] = self == null ? null : columns(self, sx, sz);
		int lo = 0, hi = 0;
		for (int d = -1; d >= -REACH; d--) {
			TriStore.Section s = source.section(sx, sy + d, sz);
			if (s == null) {
				break;
			}
			stack[REACH + d] = cached(s, sx, sz);
			lo = d;
		}
		for (int d = 1; d <= REACH; d++) {
			TriStore.Section s = source.section(sx, sy + d, sz);
			if (s == null) {
				break;
			}
			stack[REACH + d] = cached(s, sx, sz);
			hi = d;
		}
		float baseY = sy * 16;
		// Pass 1: every column's crossings in the known range (CSR), once each.
		final int cap = 32;
		float[] ys = new float[COLS * COLS * cap];
		boolean[] ups = new boolean[ys.length];
		byte[] mats = new byte[ys.length];
		boolean[] terrain = new boolean[ys.length];
		int[] count = new int[COLS * COLS];
		int[] endWt = new int[COLS * COLS];     // terrain winding above the column's last crossing
		float[] terrainTop = new float[COLS * COLS]; // the column's highest terrain crossing
		for (int col = 0; col < COLS * COLS; col++) {
			int base = col * cap, n = 0;
			for (int d = lo; d <= hi; d++) {
				Columns c = stack[REACH + d];
				if (c == null) {
					continue;
				}
				float y0 = (sy + d) * 16, y1 = y0 + 16;
				for (int k = c.start[col], e = k + c.count(col); k < e; k++) {
					float y = c.y[k];
					if (y < y0 || y >= y1 || n == cap) {
						continue;
					}
					boolean t = c.terrain[k];
					// The same face twice (overlapping colliders): once. Only right after the same face:
					// an opposite face in between is a seam, not a copy.
					if (n > 0 && y - ys[base + n - 1] < 0.005f && ups[base + n - 1] == c.up[k] && terrain[base + n - 1] == t) {
						continue;
					}
					ys[base + n] = y;
					ups[base + n] = c.up[k];
					mats[base + n] = c.material[k];
					terrain[base + n] = t;
					n++;
					if (d != 0) {
						r.usedNeighbours = true;
					}
				}
			}
			count[col] = n;
			int wt = -1;
			terrainTop[col] = Float.NaN;
			for (int q = 0; q < n; q++) {
				if (terrain[base + q]) {
					if (wt < 0) {
						wt = ups[base + q] ? 1 : 0;
					}
					wt = ups[base + q] ? 0 : 1;
					terrainTop[col] = ys[base + q];
				}
			}
			endWt[col] = Math.max(wt, 0);
		}

		// Pass 2: fill. Winding from below, terrain and props apart. Terrain is one open surface with the
		// solid under it: under its lowest crossing the line is inside when that surface faces up, and
		// it is inside or not (0..1: the cell-edge skirts and seams of Subnautica's meshes would
		// otherwise stack up). Props
		// are closed shells: outside under them, and never solid above the line's highest crossing, so a
		// broken or inside-out prop (double-sided, mirrored) can't fill the water and sky above it.
		// Crossing an up-facing surface leaves a solid, a down-facing one enters it.
		for (int cz = 0; cz < COLS; cz++) {
			for (int cx = 0; cx < COLS; cx++) {
				int col = cz * COLS + cx, base = col * cap, n = count[col];
				if (n == 0) {
					r.usedNeighbours = true;
					r.unresolved = true;
					continue;
				}
				// A column still inside the terrain above its last crossing while most columns around it
				// close: it went through a crack between terrain pieces and missed the top surface. It
				// closes where they do.
				float crackTop = Float.NaN;
				if (endWt[col] > 0) {
					int closed = 0, open = 0;
					float sum = 0;
					for (int dz = -2; dz <= 2; dz++) {
						for (int dx = -2; dx <= 2; dx++) {
							int x = cx + dx, z = cz + dz;
							if ((dx == 0 && dz == 0) || x < 0 || z < 0 || x >= COLS || z >= COLS || count[z * COLS + x] == 0) {
								continue;
							}
							int o = z * COLS + x;
							if (endWt[o] > 0) {
								open++;
							} else if (!Float.isNaN(terrainTop[o])) {
								closed++;
								sum += terrainTop[o];
							}
						}
					}
					if (closed > open) {
						crackTop = Math.max(sum / closed, ys[base + n - 1] + 0.01f);
					}
				}
				int wt = 0, wp = 0;
				for (int q = 0; q < n; q++) {
					if (terrain[base + q]) {
						wt = ups[base + q] ? 1 : 0;
						break;
					}
				}
				int k = 0;
				for (int j = 0; j < COLS; j++) {
					float y = baseY + (j + 0.5f) / RES;
					while (k < n && ys[base + k] <= y) {
						if (terrain[base + k]) {
							wt = ups[base + k] ? 0 : 1; // one surface: never inside twice (skirts, seams)
						} else {
							wp += ups[base + k] ? -1 : 1;
						}
						k++;
					}
					boolean terrainSolid = wt > 0 && !(k == n && !Float.isNaN(crackTop) && y >= crackTop);
					if (terrainSolid || (wp > 0 && k < n)) {
						set(r, cx, j, cz, k < n ? mats[base + k] : mats[base + n - 1]);
					}
				}
			}
		}
		if (self != null) {
			shell(r, self);
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
				if (Math.abs(ny) < 1e-9f || (flags[t] & 1) != 0) {
					continue; // vertical (no vertical line crosses it), or a structure (shell only)
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
							c.terrain[at] = (flags[t] & 2) != 0;
							// Material (bits 8-15, < 128) plus the structure flag (kTriStructure = bit 0).
							c.material[at] = (byte) ((flags[t] >>> 8 & 0x7F) | ((flags[t] & 1) != 0 ? STRUCTURE_BIT : 0));
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
				c.terrain = new boolean[total];
				c.material = new byte[total];
			}
		}
		// Sort each column by y (few crossings per column: insertion sort).
		for (int col = 0; col < COLS * COLS; col++) {
			int s0 = c.start[col], e0 = c.start[col + 1];
			for (int a = s0 + 1; a < e0; a++) {
				float y = c.y[a];
				boolean u = c.up[a];
				boolean tt = c.terrain[a];
				byte m = c.material[a];
				int b = a - 1;
				while (b >= s0 && c.y[b] > y) {
					c.y[b + 1] = c.y[b];
					c.up[b + 1] = c.up[b];
					c.terrain[b + 1] = c.terrain[b];
					c.material[b + 1] = c.material[b];
					b--;
				}
				c.y[b + 1] = y;
				c.up[b + 1] = u;
				c.terrain[b + 1] = tt;
				c.material[b + 1] = m;
			}
		}
		return c;
	}

	/** Marks the sub-voxels each structure triangle passes through (samples at a third of a sub-voxel). */
	private static void shell(Result r, TriStore.Section s) {
		float[] v = s.v();
		int[] flags = s.flags();
		float ox = r.sx * 16, oy = r.sy * 16, oz = r.sz * 16;
		float step = 1f / (RES * 3);
		for (int t = 0; t < flags.length; t++) {
			if ((flags[t] & 1) == 0) {
				continue;
			}
			byte mat = (byte) ((flags[t] >>> 8 & 0x7F) | STRUCTURE_BIT);
			int o = t * 9;
			float ax = v[o], ay = v[o + 1], az = v[o + 2];
			float ux = v[o + 3] - ax, uy = v[o + 4] - ay, uz = v[o + 5] - az;
			float wx = v[o + 6] - ax, wy = v[o + 7] - ay, wz = v[o + 8] - az;
			int nu = Math.max(1, (int) Math.ceil(Math.sqrt(ux * ux + uy * uy + uz * uz) / step));
			int nw = Math.max(1, (int) Math.ceil(Math.sqrt(wx * wx + wy * wy + wz * wz) / step));
			for (int i = 0; i <= nu; i++) {
				float a = (float) i / nu;
				for (int j = 0; j <= nw; j++) {
					float b = (float) j / nw;
					if (a + b > 1f) {
						break;
					}
					int cx = (int) Math.floor((ax + a * ux + b * wx - ox) * RES);
					int cy = (int) Math.floor((ay + a * uy + b * wy - oy) * RES);
					int cz = (int) Math.floor((az + a * uz + b * wz - oz) * RES);
					if (cx >= 0 && cx < COLS && cy >= 0 && cy < COLS && cz >= 0 && cz < COLS) {
						set(r, cx, cy, cz, mat);
					}
				}
			}
		}
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
