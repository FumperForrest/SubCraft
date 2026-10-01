package dev.subcraft.world.ghost;

import dev.subcraft.SubCraft;
import dev.subcraft.world.SubBlocks;
import dev.subcraft.world.SubWorld;
import dev.subcraft.world.TerrainBlock;
import dev.subcraft.world.TerrainMaterial;
import dev.subcraft.world.tri.TriStore;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps the ghost-terrain blocks in step with the host's triangles (MISSION.md 3.1): every section
 * that arrives (kColTris) is voxelized on a worker thread, then written into the world on the
 * server thread, a budget of blocks per tick. Only our own terrain, air and water are ever
 * replaced: never a player's block.
 */
public final class GhostTerrain {
	private static final int MAX_IN_FLIGHT = 2;
	private static final int BLOCK_BUDGET_PER_TICK = 40_000;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	private static final int REACH = 6;

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SubCraft voxelizer");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	// Server thread only.
	private static final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
	private static final Set<Long> dependsOnNeighbours = new HashSet<>();
	private static final ArrayDeque<Voxelizer.Result> ready = new ArrayDeque<>();
	private static final ArrayDeque<Voxelizer.Result> waitingForChunk = new ArrayDeque<>();
	private static Voxelizer.Result applying;
	private static int cursor;
	private static int generation;
	private static long lastChunkRetry;

	// Worker -> server.
	private static final ConcurrentLinkedQueue<Object[]> done = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger inFlight = new AtomicInteger();

	// Stats (dev harness).
	private static int sectionsVoxelized, sectionsApplied;
	private static long blocksSet, voxelNanos;

	private GhostTerrain() {
	}

	/** A section's triangles changed: redo it, and the neighbours that borrowed its columns. */
	public static void sectionChanged(int sx, int sy, int sz) {
		dirty.add(TriStore.key(sx, sy, sz));
		for (int d = -REACH; d <= REACH; d++) {
			long k = TriStore.key(sx, sy + d, sz);
			if (d != 0 && dependsOnNeighbours.contains(k)) {
				dirty.add(k);
			}
		}
	}

	public static void clear() {
		generation++;
		dirty.clear();
		dependsOnNeighbours.clear();
		ready.clear();
		waitingForChunk.clear();
		applying = null;
		MaskStore.clear();
	}

	public static void tick(ServerLevel level) {
		submit();
		Object[] d;
		while ((d = done.poll()) != null) {
			Voxelizer.Result r = (Voxelizer.Result) d[1];
			if ((int) d[0] != generation) {
				continue;
			}
			long k = TriStore.key(r.sx, r.sy, r.sz);
			if (r.usedNeighbours) {
				dependsOnNeighbours.add(k);
			} else {
				dependsOnNeighbours.remove(k);
			}
			ready.add(r);
		}
		if (!waitingForChunk.isEmpty() && level.getGameTime() - lastChunkRetry >= 20) {
			lastChunkRetry = level.getGameTime();
			ready.addAll(waitingForChunk);
			waitingForChunk.clear();
		}
		apply(level);
	}

	private static void submit() {
		Iterator<Long> it = dirty.iterator();
		while (inFlight.get() < MAX_IN_FLIGHT && it.hasNext()) {
			long k = it.next();
			it.remove();
			TriStore.Section s = sectionOf(k);
			if (s == null) {
				continue;
			}
			int gen = generation;
			inFlight.incrementAndGet();
			WORKER.execute(() -> {
				try {
					long t0 = System.nanoTime();
					Voxelizer.Result r = Voxelizer.voxelize(TriStore::section, s.sx(), s.sy(), s.sz());
					voxelNanos += System.nanoTime() - t0;
					sectionsVoxelized++;
					done.add(new Object[] {gen, r});
				} catch (Throwable t) {
					SubCraft.LOG.error("SubCraft: voxelizing section ({}, {}, {}) failed", s.sx(), s.sy(), s.sz(), t);
				} finally {
					inFlight.decrementAndGet();
				}
			});
		}
	}

	private static TriStore.Section sectionOf(long k) {
		int sx = (int) (k >> 42) << 10 >> 10, sy = (int) (k >> 22 & 0xFFFFF) << 12 >> 12, sz = (int) (k & 0x3FFFFF) << 10 >> 10;
		return TriStore.section(sx, sy, sz);
	}

	private static void apply(ServerLevel level) {
		int budget = BLOCK_BUDGET_PER_TICK;
		BlockState terrain = SubBlocks.TERRAIN.get().defaultBlockState();
		Block terrainBlock = terrain.getBlock();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (budget > 0) {
			if (applying == null) {
				applying = ready.poll();
				cursor = 0;
				if (applying == null) {
					return;
				}
				if (!level.hasChunk(applying.sx, applying.sz)) {
					waitingForChunk.add(applying); // never force-load a chunk for this
					applying = null;
					continue;
				}
			}
			Voxelizer.Result r = applying;
			int bx = r.sx << 4, by = r.sy << 4, bz = r.sz << 4;
			for (; cursor < 4096 && budget > 0; cursor++, budget--) {
				int i = cursor;
				pos.set(bx + (i & 15), by + (i >> 8), bz + (i >> 4 & 15));
				if (!level.isInWorldBounds(pos)) {
					continue;
				}
				long key = pos.asLong();
				boolean water = pos.getY() < SubWorld.SEA_LEVEL;
				byte kind = r.kind[i];
				BlockState want;
				if (kind == Voxelizer.EMPTY) {
					want = water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
					MaskStore.remove(key);
				} else {
					want = terrain.setValue(TerrainBlock.WATERLOGGED, water).setValue(TerrainBlock.PARTIAL, kind == Voxelizer.PARTIAL)
						.setValue(TerrainBlock.MATERIAL, TerrainMaterial.of(r.material[i] & 0xFF));
					if (kind == Voxelizer.PARTIAL) {
						MaskStore.put(key, r.masks, i * 8);
					} else {
						MaskStore.remove(key);
					}
				}
				BlockState have = level.getBlockState(pos);
				if (have == want) {
					continue;
				}
				// Open cells only replace our own terrain; solid cells also fill air and water.
				boolean ours = have.is(terrainBlock);
				if (kind == Voxelizer.EMPTY ? ours : ours || have.isAir() || have.is(Blocks.WATER)) {
					level.setBlock(pos, want, FLAGS);
					blocksSet++;
				}
			}
			if (cursor >= 4096) {
				applying = null;
				if (sectionsApplied++ < 5 || sectionsApplied % 200 == 0) {
					SubCraft.LOG.info("SubCraft: ghost terrain for section ({}, {}, {}): {} full, {} partial{} ({})", r.sx, r.sy, r.sz, r.full, r.partial,
						r.unresolved ? ", some columns unknown" : "", stats());
				}
			}
		}
	}

	public static String stats() {
		return String.format("ghost terrain: %d sections voxelized (%.1f ms avg), %d applied, %d blocks set, %d partial shapes, %d dirty, %d ready, %d waiting for chunks",
			sectionsVoxelized, sectionsVoxelized == 0 ? 0 : voxelNanos / 1e6 / sectionsVoxelized, sectionsApplied, blocksSet, MaskStore.size(), dirty.size(),
			ready.size(), waitingForChunk.size());
	}
}
