package dev.subcraft.world.ghost;

import dev.subcraft.SubCraft;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Per-chunk ghost-terrain data saved with the chunk (MISSION.md 3.1): the 8x8x8 masks of its
 * partial blocks, so they keep their shape after a restart before the host streams the area
 * again, and a patch stamp per section (hash of the triangles it was built from), so the same
 * triangles arriving again don't redo the work.
 */
public final class ChunkGhostData {
	/** Bump when the voxelizer's output changes: old stamps then no longer match. */
	public static final int VOXEL_VERSION = 7;

	/** Key: x & 15 | (z & 15) << 4 | y << 8 (block y, signed). */
	final Map<Integer, long[]> masks = new HashMap<>();
	/** Section y -> stamp. */
	final Map<Integer, Long> stamps = new HashMap<>();

	public static int key(int x, int y, int z) {
		return x & 15 | (z & 15) << 4 | y << 8;
	}

	public static int keyY(int key) {
		return key >> 8;
	}

	private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, SubCraft.MOD_ID);

	public static final java.util.function.Supplier<AttachmentType<ChunkGhostData>> TYPE = TYPES.register("ghost_terrain",
		() -> AttachmentType.builder(ChunkGhostData::new).serialize(new Serializer()).build());

	public static void register(IEventBus modBus) {
		TYPES.register(modBus);
	}

	public static ChunkGhostData of(LevelChunk chunk) {
		return chunk.getData(TYPE);
	}

	private static final class Serializer implements IAttachmentSerializer<CompoundTag, ChunkGhostData> {
		@Override
		public ChunkGhostData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
			ChunkGhostData d = new ChunkGhostData();
			if (tag.getInt("version") != VOXEL_VERSION) {
				return d; // voxelizer changed: rebuild from the host
			}
			long[] m = tag.getLongArray("masks");
			for (int i = 0; i + 9 <= m.length; i += 9) {
				long[] bits = new long[8];
				System.arraycopy(m, i + 1, bits, 0, 8);
				d.masks.put((int) m[i], bits);
			}
			long[] s = tag.getLongArray("stamps");
			for (int i = 0; i + 2 <= s.length; i += 2) {
				d.stamps.put((int) s[i], s[i + 1]);
			}
			return d;
		}

		@Override
		public CompoundTag write(ChunkGhostData d, HolderLookup.Provider provider) {
			if (d.masks.isEmpty() && d.stamps.isEmpty()) {
				return null;
			}
			long[] m = new long[d.masks.size() * 9];
			int i = 0;
			for (var e : d.masks.entrySet()) {
				m[i] = e.getKey();
				System.arraycopy(e.getValue(), 0, m, i + 1, 8);
				i += 9;
			}
			long[] s = new long[d.stamps.size() * 2];
			i = 0;
			for (var e : d.stamps.entrySet()) {
				s[i++] = e.getKey();
				s[i++] = e.getValue();
			}
			CompoundTag tag = new CompoundTag();
			tag.putInt("version", VOXEL_VERSION);
			tag.put("masks", new LongArrayTag(m));
			tag.put("stamps", new LongArrayTag(s));
			return tag;
		}
	}
}
