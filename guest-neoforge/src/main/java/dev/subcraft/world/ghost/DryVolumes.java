package dev.subcraft.world.ghost;

import dev.subcraft.SubCraft;
import dev.subcraft.world.SubWorld;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The host's dry interiors (kColDry: lifepod, later habitats and subs). Water whose block centre is
 * inside one becomes air; air we made comes back as water once no volume covers it. Never touches
 * anything but water and the air it made. Server thread, except {@link #isDry} (any thread).
 */
public final class DryVolumes {
	private static volatile float[] boxes = new float[0];   // 6 floats per box
	private static float[] applied = new float[0];
	private static final Set<Long> driedByUs = new HashSet<>();
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private DryVolumes() {
	}

	public static void set(float[] next) {
		boxes = next;
	}

	public static int count() {
		return boxes.length / 6;
	}

	public static void clear() {
		boxes = new float[0];
	}

	public static boolean isDry(int x, int y, int z) {
		float[] b = boxes;
		float cx = x + 0.5f, cy = y + 0.5f, cz = z + 0.5f;
		for (int i = 0; i < b.length; i += 6) {
			if (cx >= b[i] && cx <= b[i + 3] && cy >= b[i + 1] && cy <= b[i + 4] && cz >= b[i + 2] && cz <= b[i + 5]) {
				return true;
			}
		}
		return false;
	}

	/** Brings the world in line with the current volumes when they changed. */
	public static void tick(ServerLevel level) {
		float[] b = boxes;
		if (b == applied) {
			return;
		}
		applied = b;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int dried = 0, wetted = 0;
		// Give back water where no volume is any more.
		for (Iterator<Long> it = driedByUs.iterator(); it.hasNext();) {
			pos.set(it.next());
			if (isDry(pos.getX(), pos.getY(), pos.getZ())) {
				continue;
			}
			it.remove();
			if (level.isLoaded(pos) && level.getBlockState(pos).isAir() && pos.getY() < SubWorld.SEA_LEVEL) {
				level.setBlock(pos, Blocks.WATER.defaultBlockState(), FLAGS);
				wetted++;
			}
		}
		for (int i = 0; i < b.length; i += 6) {
			int x0 = (int) Math.floor(b[i]), y0 = (int) Math.floor(b[i + 1]), z0 = (int) Math.floor(b[i + 2]);
			int x1 = (int) Math.floor(b[i + 3]), y1 = Math.min((int) Math.floor(b[i + 4]), SubWorld.SEA_LEVEL - 1), z1 = (int) Math.floor(b[i + 5]);
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						if (!isDry(x, y, z)) {
							continue;
						}
						pos.set(x, y, z);
						if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.WATER)) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
							driedByUs.add(pos.asLong());
							dried++;
						}
					}
				}
			}
		}
		SubCraft.LOG.info("SubCraft: dry volumes: {} ({} blocks dried, {} wetted)", b.length / 6, dried, wetted);
	}
}
