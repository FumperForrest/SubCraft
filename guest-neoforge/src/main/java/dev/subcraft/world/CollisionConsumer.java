package dev.subcraft.world;

import static dev.subcraft.link.Proto.*;

import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.SubLink;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Applies the host's collision ring to the SubCraft world as ghost terrain blocks. Runs on the
 * server thread; spreads large regions over several ticks.
 *
 * Phase 0a: any occupied cell becomes a full terrain block (the "temporary collision injection"
 * MISSION.md allows). Phase 1 replaces this with per-chunk masks, patch stamps and dry volumes.
 */
public final class CollisionConsumer {
	private static final int BLOCK_BUDGET_PER_TICK = 20_000;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/** One region waiting to be applied: box + the solid cells in it. */
	private record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long[] solid) {
	}

	private static final ArrayDeque<Region> pending = new ArrayDeque<>();
	private static Region current;
	private static long cursor;
	private static int lastEpoch = -1;
	private static int seenGeneration;
	private static int regionsApplied;

	private CollisionConsumer() {
	}

	public static void serverTick(ServerLevel level) {
		LinkView view = SubLink.view();
		if (view == null || !SubWorld.is(level)) {
			return;
		}
		if (SubLink.generation() != seenGeneration) {
			// A new host instance: its epochs start over and its old regions are stale.
			seenGeneration = SubLink.generation();
			lastEpoch = -1;
			pending.clear();
			current = null;
		}
		view.drainCollision((type, off, bytes) -> read(view, type, off, bytes), 256);
		apply(level);
	}

	private static void read(LinkView v, int type, long off, int bytes) {
		if (type == COL_CLEAR) {
			int epoch = v.getInt(off);
			if (epoch != lastEpoch) {
				lastEpoch = epoch;
				pending.clear();
				current = null;
				SubCraft.LOG.info("SubCraft: collision epoch {}", epoch);
			}
			return;
		}
		if (type != COL_REGION || bytes < COL_REGION_BYTES) {
			return;
		}
		int minX = v.getInt(off), minY = v.getInt(off + 4), minZ = v.getInt(off + 8);
		int maxX = v.getInt(off + 12), maxY = v.getInt(off + 16), maxZ = v.getInt(off + 20);
		int count = v.getInt(off + 28);
		if (maxX < minX || maxY < minY || maxZ < minZ || COL_REGION_BYTES + (long) count * COL_BLOCK_BYTES > bytes) {
			SubCraft.LOG.warn("SubCraft: malformed collision region");
			return;
		}
		long[] solid = new long[count];
		int n = 0;
		for (int i = 0; i < count; i++) {
			long b = off + COL_REGION_BYTES + (long) i * COL_BLOCK_BYTES;
			boolean any = false;
			for (int k = 0; k < 8 && !any; k++) {
				any = v.getLong(b + COL_BLOCK_BITS + k * 8L) != 0;
			}
			if (any) {
				solid[n++] = BlockPos.asLong(v.getInt(b), v.getInt(b + 4), v.getInt(b + 8));
			}
		}
		java.util.Arrays.sort(solid, 0, n);
		pending.add(new Region(minX, minY, minZ, maxX, maxY, maxZ, java.util.Arrays.copyOf(solid, n)));
	}

	private static void apply(ServerLevel level) {
		int budget = BLOCK_BUDGET_PER_TICK;
		BlockState terrain = SubBlocks.TERRAIN.get().defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (budget > 0) {
			if (current == null) {
				current = pending.poll();
				cursor = 0;
				if (current == null) {
					return;
				}
			}
			Region r = current;
			long sx = r.maxX - r.minX + 1L, sy = r.maxY - r.minY + 1L, sz = r.maxZ - r.minZ + 1L;
			long total = sx * sy * sz;
			for (; cursor < total && budget > 0; cursor++, budget--) {
				int x = (int) (r.minX + cursor % sx);
				int z = (int) (r.minZ + (cursor / sx) % sz);
				int y = (int) (r.minY + cursor / (sx * sz));
				pos.set(x, y, z);
				if (!level.isInWorldBounds(pos)) {
					continue;
				}
				boolean water = y < SubWorld.SEA_LEVEL;
				boolean solid = java.util.Arrays.binarySearch(r.solid, pos.asLong()) >= 0;
				BlockState want = solid ? terrain.setValue(TerrainBlock.WATERLOGGED, water)
					: water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
				BlockState have = level.getBlockState(pos);
				// Open cells only replace our own terrain: never a player's block.
				if (have != want && (solid ? have.isAir() || have.is(Blocks.WATER) || have.is(terrain.getBlock()) : have.is(terrain.getBlock()))) {
					level.setBlock(pos, want, FLAGS);
				}
			}
			if (cursor >= total) {
				current = null;
				if (regionsApplied++ < 10) {
					SubCraft.LOG.info("SubCraft: applied collision region [{} {} {}]..[{} {} {}] with {} solid blocks", r.minX, r.minY, r.minZ, r.maxX, r.maxY, r.maxZ, r.solid.length);
				}
			}
		}
	}
}
