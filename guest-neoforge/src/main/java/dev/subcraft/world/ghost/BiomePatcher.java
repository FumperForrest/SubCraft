package dev.subcraft.world.ghost;

import dev.subcraft.SubCraft;
import dev.subcraft.world.SubBiomes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * kColBiomes -> Minecraft biomes in the chunk (MISSION.md 3.1: one Minecraft biome per Subnautica
 * biome), written like /fillbiome does and re-sent to the client. Server thread. Sections whose
 * chunk isn't loaded wait; a chunk is rewritten at most once per tick with all its sections.
 */
public final class BiomePatcher {
	/** Pending cells per chunk: section y -> 64 biome paths (null = keep). */
	private static final Map<Long, Map<Integer, String[]>> pending = new HashMap<>();
	private static final Map<String, Holder<Biome>> holders = new HashMap<>();
	private static int sectionsApplied;
	private static final int CHUNKS_PER_TICK = 8;

	private BiomePatcher() {
	}

	public static void put(int sx, int sy, int sz, String[] cells) {
		pending.computeIfAbsent(ChunkPos.asLong(sx, sz), k -> new HashMap<>()).put(sy, cells);
	}

	public static void clear() {
		pending.clear();
	}

	public static int applied() {
		return sectionsApplied;
	}

	public static void tick(ServerLevel level) {
		if (pending.isEmpty()) {
			return;
		}
		List<ChunkAccess> changed = new ArrayList<>();
		var it = pending.entrySet().iterator();
		while (it.hasNext() && changed.size() < CHUNKS_PER_TICK) {
			var e = it.next();
			ChunkPos cp = new ChunkPos(e.getKey());
			LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
			if (chunk == null) {
				continue; // never force-load: retried when loaded
			}
			it.remove();
			Map<Integer, String[]> sections = e.getValue();
			boolean[] differs = {false};
			chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> {
				Holder<Biome> old = chunk.getNoiseBiome(qx, qy, qz);
				String[] cells = sections.get(qy >> 2);
				if (cells == null) {
					return old;
				}
				String path = cells[((qy & 3) * 4 + (qz & 3)) * 4 + (qx & 3)];
				if (path == null) {
					return old;
				}
				Holder<Biome> want = holder(level, path);
				if (want == null || want == old) {
					return old;
				}
				differs[0] = true;
				return want;
			}, level.getChunkSource().randomState().sampler());
			sectionsApplied += sections.size();
			if (differs[0]) {
				chunk.setUnsaved(true);
				changed.add(chunk);
			}
		}
		if (!changed.isEmpty()) {
			level.getChunkSource().chunkMap.resendBiomesForChunks(changed);
		}
	}

	private static Holder<Biome> holder(ServerLevel level, String path) {
		return holders.computeIfAbsent(path, p -> {
			var key = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(SubCraft.MOD_ID, p));
			var h = level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(key).orElse(null);
			if (h == null) {
				SubCraft.LOG.warn("SubCraft: biome {} missing from the registry", key.location());
			}
			return h;
		});
	}

	/** Host biome names -> SubCraft biome paths, cached (few distinct names). */
	private static final Map<String, String> mapped = new HashMap<>();

	public static String map(String hostName) {
		return mapped.computeIfAbsent(hostName, n -> {
			String p = SubBiomes.of(n);
			SubCraft.LOG.info("SubCraft: host biome '{}' -> subcraft:{}", n, p);
			return p;
		});
	}
}
