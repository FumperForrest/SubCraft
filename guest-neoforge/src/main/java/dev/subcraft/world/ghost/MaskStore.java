package dev.subcraft.world.ghost;

import dev.subcraft.mixin.CubeVoxelShapeInvoker;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Collision shapes of partial ghost-terrain blocks, by block position. Shared by the integrated
 * server and the client. A partial block without an entry (e.g. after a restart, before the host
 * streams that area again) collides as a full block.
 */
public final class MaskStore {
	private static final Map<Long, VoxelShape> SHAPES = new ConcurrentHashMap<>();

	private MaskStore() {
	}

	public static VoxelShape get(long pos) {
		return SHAPES.get(pos);
	}

	public static void put(long pos, long[] masks, int offset) {
		SHAPES.put(pos, shape(masks, offset));
	}

	public static void remove(long pos) {
		SHAPES.remove(pos);
	}

	public static void clear() {
		SHAPES.clear();
	}

	public static int size() {
		return SHAPES.size();
	}

	/** bits[y] bit (z * 8 + x), from masks[offset .. offset + 8]. */
	static VoxelShape shape(long[] masks, int offset) {
		BitSetDiscreteVoxelShape d = new BitSetDiscreteVoxelShape(8, 8, 8);
		for (int y = 0; y < 8; y++) {
			long layer = masks[offset + y];
			while (layer != 0) {
				int bit = Long.numberOfTrailingZeros(layer);
				layer &= layer - 1;
				d.fill(bit & 7, y, bit >>> 3);
			}
		}
		return CubeVoxelShapeInvoker.subcraft$create(d);
	}
}
