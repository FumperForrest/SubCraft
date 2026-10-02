package dev.subcraft.world.ghost;

import dev.subcraft.world.SubBlocks;
import dev.subcraft.world.tri.TriGeometry;
import dev.subcraft.world.tri.TriStore;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * "subcraft ghostaudit [radius]": ghost terrain blocks around the player that no host triangle
 * comes near. Subnautica draws the terrain, so these are blocks Minecraft collides with (and you
 * can click, and mobs stand on) where Subnautica shows nothing.
 */
public final class GhostAudit {
	private GhostAudit() {
	}

	public static String run(Level level, BlockPos centre, int radius) {
		int terrain = 0, structure = 0, partial = 0, stray = 0, unknown = 0;
		List<String> samples = new ArrayList<>();
		java.util.Map<Integer, Integer> byX = new java.util.TreeMap<>(), byZ = new java.util.TreeMap<>(), byXmod = new java.util.TreeMap<>(), byZmod = new java.util.TreeMap<>();
		java.util.Map<Long, int[]> columns = new java.util.HashMap<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		double[] c = new double[3];
		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					p.set(centre.getX() + x, centre.getY() + y, centre.getZ() + z);
					BlockState s = level.getBlockState(p);
					boolean isTerrain = s.is(SubBlocks.TERRAIN.get());
					if (!isTerrain && !s.is(SubBlocks.STRUCTURE.get())) {
						continue;
					}
					if (isTerrain) terrain++; else structure++;
					if (s.getValue(dev.subcraft.world.TerrainBlock.PARTIAL)) partial++;
					if (!TriStore.isKnown(p.getX(), p.getY(), p.getZ())) {
						unknown++;
						continue;
					}
					// Exposed blocks only: the inside of the rock is far from any surface by design.
					boolean exposed = false;
					for (var d : net.minecraft.core.Direction.values()) {
						BlockState n = level.getBlockState(p.relative(d));
						if (!n.is(SubBlocks.TERRAIN.get()) && !n.is(SubBlocks.STRUCTURE.get())) {
							exposed = true;
							break;
						}
					}
					if (!exposed) {
						continue;
					}
					double cx = p.getX() + 0.5, cy = p.getY() + 0.5, cz = p.getZ() + 0.5;
					double[] best = {Double.MAX_VALUE};
					TriStore.query(cx - 2, cy - 2, cz - 2, cx + 2, cy + 2, cz + 2, (sec, i) -> {
						float[] v = sec.v();
						int o = i * 9;
						TriGeometry.closestPointTriangle(cx, cy, cz, v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], v[o + 5], v[o + 6], v[o + 7], v[o + 8], c);
						double d = Math.sqrt((c[0] - cx) * (c[0] - cx) + (c[1] - cy) * (c[1] - cy) + (c[2] - cz) * (c[2] - cz));
						if (d < best[0]) best[0] = d;
					});
					if (best[0] > 1.5) {
						stray++;
						byX.merge(p.getX(), 1, Integer::sum);
						byZ.merge(p.getZ(), 1, Integer::sum);
						byXmod.merge(Math.floorMod(p.getX(), 32), 1, Integer::sum);
						byZmod.merge(Math.floorMod(p.getZ(), 32), 1, Integer::sum);
						int[] col = columns.computeIfAbsent((long) p.getX() << 32 | (p.getZ() & 0xFFFFFFFFL), k -> new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE, 0});
						col[0] = Math.min(col[0], p.getY());
						col[1] = Math.max(col[1], p.getY());
						col[2]++;
						if (samples.size() < 12) {
							samples.add(String.format("%d %d %d (%s, nearest surface %s)", p.getX(), p.getY(), p.getZ(), s.getValue(dev.subcraft.world.TerrainBlock.MATERIAL),
								best[0] == Double.MAX_VALUE ? "none within 2" : String.format("%.1f", best[0])));
						}
					}
				}
			}
		}
		StringBuilder cols = new StringBuilder();
		columns.entrySet().stream().sorted((a, b) -> b.getValue()[2] - a.getValue()[2]).limit(15).forEach(e -> cols.append(String.format(" (%d,%d) y%d..%d n%d;",
			(int) (e.getKey() >> 32), (int) (long) e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])));
		return String.format("radius %d: %d terrain + %d structure blocks (%d partial), %d in unknown sections, %d exposed with no surface within 1.5 in %d columns%n"
			+ "x mod 32: %s%nz mod 32: %s%ntop columns:%s%nsamples: %s",
			radius, terrain, structure, partial, unknown, stray, columns.size(), byXmod, byZmod, cols, String.join("; ", samples));
	}

	/** "subcraft column x z": every host surface the vertical line through (x+0.5, z+0.5) crosses, all known sections. */
	public static String column(int x, int z) {
		StringBuilder sb = new StringBuilder();
		double px = x + 0.5, pz = z + 0.5;
		int sx = x >> 4, sz = z >> 4;
		for (TriStore.Section sec : TriStore.all()) {
			if (sec.sx() != sx || sec.sz() != sz) continue;
			float[] v = sec.v();
			int[] f = sec.flags();
			for (int t = 0; t < f.length; t++) {
				int o = t * 9;
				float ax = v[o], ay = v[o + 1], az = v[o + 2], bx = v[o + 3], by = v[o + 4], bz = v[o + 5], cx = v[o + 6], cy = v[o + 7], cz = v[o + 8];
				double d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
				double d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz);
				double d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az);
				boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
				if (neg && pos) continue;
				float e1x = bx - ax, e1y = by - ay, e1z = bz - az, e2x = cx - ax, e2y = cy - ay, e2z = cz - az;
				float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
				if (Math.abs(ny) < 1e-9f) continue;
				double y = ay - (nx * (px - ax) + nz * (pz - az)) / ny;
				sb.append(String.format("y %.2f %s flags 0x%x (section %d); ", y, ny > 0 ? "UP" : "down", f[t], sec.sy()));
			}
		}
		return sb.length() == 0 ? "no crossings" : sb.toString();
	}

	/** "subcraft revox x y z": what the voxelizer says now for the block's section vs the world, and the stamps. */
	public static String revox(net.minecraft.server.level.ServerLevel level, int x, int y, int z) {
		int sx = x >> 4, sy = y >> 4, sz = z >> 4;
		TriStore.Section s = TriStore.section(sx, sy, sz);
		Voxelizer.Result r = Voxelizer.voxelize(TriStore::section, sx, sy, sz);
		var chunk = level.getChunkSource().getChunkNow(sx, sz);
		Long saved = chunk == null ? null : ChunkGhostData.of(chunk).stamps.get(sy);
		int i = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15);
		int mismatch = 0, worldSolid = 0, voxSolid = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int j = 0; j < 4096; j++) {
			p.set((sx << 4) + (j & 15), (sy << 4) + (j >> 8), (sz << 4) + (j >> 4 & 15));
			BlockState b = level.getBlockState(p);
			boolean w = b.is(SubBlocks.TERRAIN.get()) || b.is(SubBlocks.STRUCTURE.get());
			boolean v = r.kind[j] != Voxelizer.EMPTY;
			if (w) worldSolid++;
			if (v) voxSolid++;
			if (w != v) mismatch++;
		}
		return String.format("section (%d,%d,%d) %s; voxelizer now: kind %d at the block, %d solid; world %d solid, %d differ; unresolved %b usedNeighbours %b; "
			+ "stamp now %d saved %s", sx, sy, sz, s == null ? "unknown" : s.count() + " triangles", r.kind[i], voxSolid, worldSolid, mismatch, r.unresolved,
			r.usedNeighbours, GhostTerrain.stamp(s), saved);
	}

	/** "subcraft whysolid x y z": for the block's first solid sub-column, where its state came from. */
	public static String whySolid(int x, int y, int z) {
		int sx = x >> 4, sy = y >> 4, sz = z >> 4;
		Voxelizer.Result r = Voxelizer.voxelize(TriStore::section, sx, sy, sz);
		int i = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15);
		long[] m = r.mask(i);
		for (int cz = 0; cz < 8; cz++) {
			for (int cx = 0; cx < 8; cx++) {
				boolean any = false;
				for (int l = 0; l < 8; l++) any |= (m[l] >>> (cz * 8 + cx) & 1) != 0;
				if (!any) continue;
				int col = ((z & 15) * 8 + cz) * Voxelizer.COLS + (x & 15) * 8 + cx;
				StringBuilder sb = new StringBuilder(String.format("sub-column (%d,%d) col %d: ", cx, cz, col));
				for (int d = -6; d <= 6; d++) {
					TriStore.Section s = TriStore.section(sx, sy + d, sz);
					if (s == null) { sb.append(String.format("[sy%+d unknown] ", d)); continue; }
					Voxelizer.Columns c = Voxelizer.columns(s, sx, sz);
					int n = c.count(col);
					if (n == 0) continue;
					sb.append(String.format("[sy%+d: ", d));
					for (int k = c.start[col]; k < c.start[col] + n; k++) sb.append(String.format("%.2f%s ", c.y[k], c.up[k] ? "U" : "d"));
					sb.append("] ");
				}
				return sb.toString();
			}
		}
		return "no solid sub-column at the block";
	}
}
