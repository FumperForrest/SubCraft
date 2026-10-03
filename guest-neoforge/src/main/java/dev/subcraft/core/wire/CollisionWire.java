package dev.subcraft.core.wire;

import static dev.subcraft.link.Proto.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Collision-ring payloads from the host (protocol/subcraft_protocol.h, collision ring), parsed
 * into plain records. Nothing the host wrote is trusted: every count is checked against the
 * payload's length, and a malformed payload parses to null. The host's writer
 * (host-subnautica/src/Core/Wire/CollisionWire.cs) and this parser are both tested against the
 * shared bytes in protocol/fixtures/.
 *
 * <p>Game-free (dev.subcraft.core, see GameFreeCodeTest).
 */
public final class CollisionWire {
	private CollisionWire() {
	}

	/** kColTris: the section the box covers, and its triangles (9 floats each, MC coords). */
	public record Tris(int sx, int sy, int sz, int epoch, float[] verts, int[] flags) {
		public int count() {
			return this.flags.length;
		}
	}

	/** kColBiomes: the host's biome name per 4x4x4 cell ((y * 4 + z) * 4 + x), null where unknown. */
	public record Biomes(int sx, int sy, int sz, String[] cells) {
	}

	/** kColDry: the dry boxes, 6 floats each (min xyz, max xyz). */
	public record Dry(int epoch, float[] boxes) {
		public int count() {
			return this.boxes.length / 6;
		}
	}

	private static ByteBuffer le(ByteBuffer buf) {
		return buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
	}

	/** kColTris at {@code off} (absolute) of {@code bytes} bytes, or null if malformed. */
	public static Tris tris(ByteBuffer buffer, int off, int bytes) {
		ByteBuffer b = le(buffer);
		if (bytes < COL_REGION_BYTES) {
			return null;
		}
		int count = b.getInt(off + 28);
		if (count < 0 || COL_REGION_BYTES + (long) count * COL_TRI_BYTES > bytes) {
			return null;
		}
		float[] verts = new float[count * 9];
		int[] flags = new int[count];
		for (int i = 0; i < count; i++) {
			int t = off + COL_REGION_BYTES + i * COL_TRI_BYTES;
			for (int k = 0; k < 9; k++) {
				verts[i * 9 + k] = b.getFloat(t + k * 4);
			}
			flags[i] = b.getInt(t + 36);
		}
		return new Tris(b.getInt(off) >> 4, b.getInt(off + 4) >> 4, b.getInt(off + 8) >> 4, b.getInt(off + 24), verts, flags);
	}

	/** kColBiomes, or null if malformed. Names past the payload's end read as unknown. */
	public static Biomes biomes(ByteBuffer buffer, int off, int bytes) {
		ByteBuffer b = le(buffer);
		if (bytes < COL_BIOMES_BYTES + BIOME_CELLS) {
			return null;
		}
		int nameCount = b.get(off + 12) & 0xFF;
		String[] names = new String[nameCount];
		int p = off + COL_BIOMES_BYTES + BIOME_CELLS, end = off + bytes;
		for (int i = 0; i < nameCount && p < end; i++) {
			int start = p;
			while (p < end && b.get(p) != 0) {
				p++;
			}
			byte[] utf8 = new byte[p - start];
			b.get(start, utf8);
			names[i] = new String(utf8, StandardCharsets.UTF_8);
			p++; // the NUL
		}
		String[] cells = new String[BIOME_CELLS];
		for (int i = 0; i < BIOME_CELLS; i++) {
			int idx = b.get(off + COL_BIOMES_BYTES + i) & 0xFF;
			cells[i] = idx < nameCount ? names[idx] : null;
		}
		return new Biomes(b.getInt(off), b.getInt(off + 4), b.getInt(off + 8), cells);
	}

	/** kColDry, or null if malformed. */
	public static Dry dry(ByteBuffer buffer, int off, int bytes) {
		ByteBuffer b = le(buffer);
		if (bytes < COL_DRY_HEADER_BYTES) {
			return null;
		}
		int count = b.getInt(off + 4);
		if (count < 0 || COL_DRY_HEADER_BYTES + (long) count * DRY_BOX_BYTES > bytes) {
			return null;
		}
		float[] boxes = new float[count * 6];
		for (int i = 0; i < count; i++) {
			for (int k = 0; k < 6; k++) {
				boxes[i * 6 + k] = b.getFloat(off + COL_DRY_HEADER_BYTES + i * DRY_BOX_BYTES + k * 4);
			}
		}
		return new Dry(b.getInt(off), boxes);
	}
}
