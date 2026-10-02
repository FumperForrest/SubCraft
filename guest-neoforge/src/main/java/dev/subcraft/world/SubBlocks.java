package dev.subcraft.world;

import dev.subcraft.SubCraft;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SubBlocks {
	private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SubCraft.MOD_ID);

	public static final DeferredBlock<TerrainBlock> TERRAIN = BLOCKS.register("terrain", () -> new TerrainBlock(
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.STONE)
			.strength(-1.0F, 3_600_000.0F)
			.noLootTable()
			.sound(SoundType.STONE)
			.pushReaction(PushReaction.BLOCK)
			.isValidSpawn((state, level, pos, type) -> true)
			// Partial blocks' shapes depend on the position (MaskStore): no per-state shape cache.
			.dynamicShape()
			// The player's capsule follows the exact triangles, which the voxels can overshoot by
			// a sub-voxel: hugging a rock face must not suffocate anyone or black out the view.
			.isSuffocating((state, level, pos) -> false)
			.isViewBlocking((state, level, pos) -> false)
	));

	/** Host-built geometry (wrecks, the lifepod, habitats): like terrain, removed when the host removes it. */
	public static final DeferredBlock<TerrainBlock> STRUCTURE = BLOCKS.register("structure", () -> new TerrainBlock(
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(-1.0F, 3_600_000.0F)
			.noLootTable()
			.sound(SoundType.METAL)
			.pushReaction(PushReaction.BLOCK)
			.isValidSpawn((state, level, pos, type) -> false)
			.dynamicShape()
			.isSuffocating((state, level, pos) -> false)
			.isViewBlocking((state, level, pos) -> false)
	));

	private SubBlocks() {
	}

	public static void register(IEventBus modBus) {
		BLOCKS.register(modBus);
	}
}
