package dev.subcraft.world;

import static dev.subcraft.link.Proto.*;

import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.SubLink;
import dev.subcraft.world.ghost.DryVolumes;
import dev.subcraft.world.ghost.GhostTerrain;
import dev.subcraft.world.tri.TriStore;
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
 * kColTris (Subnautica): triangles go to the player collider ({@link TriStore}) and are voxelized
 * into ghost terrain ({@link GhostTerrain}). kColRegion (fake_host.py): any occupied cell becomes
 * a full terrain block.
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
	private static int regionsApplied;

	private CollisionConsumer() {
	}

	public static void serverTick(ServerLevel level) {
		LinkView view = SubLink.view();
		if (view == null || !SubWorld.is(level)) {
			return;
		}
		// A new host instance announces itself with a kColClear of a new epoch (unique per instance,
		// v26), read here in ring order before any of its data. (Before, this thread also reset on
		// SubLink.generation, which the render thread bumps: if the server drained the new host's
		// first messages before seeing the bump, it wiped them, and the host never re-sends.)
		long faults = view.sinkFaults, corrupt = view.corruptMessages;
		view.drainCollision((type, off, bytes) -> read(view, type, off, bytes), 256);
		if (view.sinkFaults != faults) {
			SubCraft.LOG.error("SubCraft: collision message failed ({} so far)", view.sinkFaults, view.lastSinkFault);
		}
		if (view.corruptMessages != corrupt) {
			SubCraft.LOG.error("SubCraft: collision ring framing broken, pending messages dropped ({} so far)", view.corruptMessages);
		}
		apply(level);
		GhostTerrain.tick(level);
		DryVolumes.tick(level);
		dev.subcraft.world.ghost.BiomePatcher.tick(level);
	}

	private static void read(LinkView v, int type, long off, int bytes) {
		if (type == COL_TRIS) {
			readTris(v, off, bytes);
			return;
		}
		if (type == COL_BIOMES) {
			readBiomes(v, off, bytes);
			return;
		}
		if (type == COL_DRY) {
			int count = v.getInt(off + 4);
			if (count < 0 || COL_DRY_HEADER_BYTES + (long) count * DRY_BOX_BYTES > bytes) {
				SubCraft.LOG.warn("SubCraft: malformed dry volumes");
				return;
			}
			float[] boxes = new float[count * 6];
			for (int i = 0; i < count; i++) {
				for (int k = 0; k < 6; k++) {
					boxes[i * 6 + k] = v.getFloat(off + COL_DRY_HEADER_BYTES + (long) i * DRY_BOX_BYTES + k * 4L);
				}
			}
			DryVolumes.set(boxes);
			return;
		}
		if (type == COL_CLEAR) {
			int epoch = v.getInt(off);
			if (epoch != lastEpoch) {
				lastEpoch = epoch;
				pending.clear();
				current = null;
				TriStore.clear();
				GhostTerrain.clear();
				DryVolumes.clear();
				dev.subcraft.world.ghost.BiomePatcher.clear();
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
		if (maxX < minX || maxY < minY || maxZ < minZ || count < 0 || COL_REGION_BYTES + (long) count * COL_BLOCK_BYTES > bytes) {
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

	/** kColTris: a section's exact triangles, for the player collider and the ghost-terrain blocks. */
	private static void readTris(LinkView v, long off, int bytes) {
		int minX = v.getInt(off), minY = v.getInt(off + 4), minZ = v.getInt(off + 8);
		int count = v.getInt(off + 28);
		if (count < 0 || COL_REGION_BYTES + (long) count * COL_TRI_BYTES > bytes) {
			SubCraft.LOG.warn("SubCraft: malformed triangle region");
			return;
		}
		float[] verts = new float[count * 9];
		int[] flags = new int[count];
		for (int i = 0; i < count; i++) {
			long t = off + COL_REGION_BYTES + (long) i * COL_TRI_BYTES;
			for (int k = 0; k < 9; k++) {
				verts[i * 9 + k] = v.getFloat(t + k * 4L);
			}
			flags[i] = v.getInt(t + 36);
		}
		TriStore.put(minX >> 4, minY >> 4, minZ >> 4, verts, flags);
		GhostTerrain.sectionChanged(minX >> 4, minY >> 4, minZ >> 4);
		triRegions++;
		triTotal += count;
		if (triRegions <= 5 || triRegions % 200 == 0) {
			SubCraft.LOG.info("SubCraft: triangles for section ({}, {}, {}): {} (regions so far {}, triangles {})", minX >> 4, minY >> 4, minZ >> 4, count,
				triRegions, triTotal);
		}
	}

	/** kColBiomes: 64 cells of host biome names -> SubCraft biomes. */
	private static void readBiomes(LinkView v, long off, int bytes) {
		if (bytes < COL_BIOMES_BYTES + BIOME_CELLS) {
			return;
		}
		int sx = v.getInt(off), sy = v.getInt(off + 4), sz = v.getInt(off + 8);
		int nameCount = v.getByte(off + 12);
		String[] names = new String[nameCount];
		long p = off + COL_BIOMES_BYTES + BIOME_CELLS, end = off + bytes;
		for (int i = 0; i < nameCount && p < end; i++) {
			var sb = new java.io.ByteArrayOutputStream();
			int b;
			while (p < end && (b = v.getByte(p++)) != 0) {
				sb.write(b);
			}
			names[i] = dev.subcraft.world.ghost.BiomePatcher.map(sb.toString(java.nio.charset.StandardCharsets.UTF_8));
		}
		String[] cells = new String[BIOME_CELLS];
		for (int i = 0; i < BIOME_CELLS; i++) {
			int idx = v.getByte(off + COL_BIOMES_BYTES + i);
			cells[i] = idx < nameCount ? names[idx] : null;
		}
		dev.subcraft.world.ghost.BiomePatcher.put(sx, sy, sz, cells);
	}

	private static int triRegions;
	private static long triTotal;

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
