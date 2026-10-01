package dev.subcraft.world.tri;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The host's exact collision surface, as it arrived per 16-block section (kColTris). Read by the
 * player collider on the client and the integrated server (both threads), written by the
 * collision consumer: each section's data is an immutable snapshot swapped in whole.
 */
public final class TriStore {
	/** One section's triangles: 9 floats each in {@code v}, flags per triangle, and the bounds of each. */
	public record Section(int sx, int sy, int sz, float[] v, int[] flags, float[] bounds) {
		public int count() {
			return this.flags.length;
		}
	}

	private static final Map<Long, Section> SECTIONS = new ConcurrentHashMap<>();
	private static volatile int generation;

	private TriStore() {
	}

	public static long key(int sx, int sy, int sz) {
		return ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
	}

	/** Replaces a section's triangles (count 0: known empty). */
	public static void put(int sx, int sy, int sz, float[] v, int[] flags) {
		int n = flags.length;
		float[] bounds = new float[n * 6];
		for (int i = 0; i < n; i++) {
			int o = i * 9;
			float minX = Math.min(v[o], Math.min(v[o + 3], v[o + 6])), maxX = Math.max(v[o], Math.max(v[o + 3], v[o + 6]));
			float minY = Math.min(v[o + 1], Math.min(v[o + 4], v[o + 7])), maxY = Math.max(v[o + 1], Math.max(v[o + 4], v[o + 7]));
			float minZ = Math.min(v[o + 2], Math.min(v[o + 5], v[o + 8])), maxZ = Math.max(v[o + 2], Math.max(v[o + 5], v[o + 8]));
			int b = i * 6;
			bounds[b] = minX;
			bounds[b + 1] = minY;
			bounds[b + 2] = minZ;
			bounds[b + 3] = maxX;
			bounds[b + 4] = maxY;
			bounds[b + 5] = maxZ;
		}
		SECTIONS.put(key(sx, sy, sz), new Section(sx, sy, sz, v, flags, bounds));
		generation++;
	}

	public static void clear() {
		SECTIONS.clear();
		generation++;
	}

	public static boolean isEmpty() {
		return SECTIONS.isEmpty();
	}

	public static int generation() {
		return generation;
	}

	public static int sectionCount() {
		return SECTIONS.size();
	}

	/** True if the host has told us what is in the section containing this block (even "nothing"). */
	public static boolean isKnown(int bx, int by, int bz) {
		return SECTIONS.containsKey(key(bx >> 4, by >> 4, bz >> 4));
	}

	public static Section section(int sx, int sy, int sz) {
		return SECTIONS.get(key(sx, sy, sz));
	}

	public static Iterable<Section> all() {
		return SECTIONS.values();
	}

	/**
	 * Every triangle whose bounds overlap the box, as (section, index) into the visitor. Triangles
	 * spanning several sections are stored in each; callers that care deduplicate (the collider
	 * doesn't need to: pushing out of the same triangle twice is harmless).
	 */
	public static void query(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, TriVisitor visitor) {
		int sx0 = (int) Math.floor(minX) >> 4, sx1 = (int) Math.floor(maxX) >> 4;
		int sy0 = (int) Math.floor(minY) >> 4, sy1 = (int) Math.floor(maxY) >> 4;
		int sz0 = (int) Math.floor(minZ) >> 4, sz1 = (int) Math.floor(maxZ) >> 4;
		for (int sx = sx0; sx <= sx1; sx++) {
			for (int sy = sy0; sy <= sy1; sy++) {
				for (int sz = sz0; sz <= sz1; sz++) {
					Section s = SECTIONS.get(key(sx, sy, sz));
					if (s == null) {
						continue;
					}
					float[] b = s.bounds;
					for (int i = 0, n = s.count(); i < n; i++) {
						int o = i * 6;
						if (b[o] <= maxX && b[o + 3] >= minX && b[o + 1] <= maxY && b[o + 4] >= minY && b[o + 2] <= maxZ && b[o + 5] >= minZ) {
							visitor.visit(s, i);
						}
					}
				}
			}
		}
	}

	@FunctionalInterface
	public interface TriVisitor {
		void visit(Section section, int index);
	}
}
