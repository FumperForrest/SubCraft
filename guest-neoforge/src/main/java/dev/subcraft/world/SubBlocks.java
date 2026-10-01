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
	));

	private SubBlocks() {
	}

	public static void register(IEventBus modBus) {
		BLOCKS.register(modBus);
	}
}
