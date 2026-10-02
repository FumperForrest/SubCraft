package dev.subcraft.combat;

import dev.subcraft.SubCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SubEntities {
	private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, SubCraft.MOD_ID);

	public static final DeferredHolder<EntityType<?>, EntityType<CreatureProxy>> CREATURE_PROXY = ENTITIES.register("creature_proxy",
		() -> EntityType.Builder.<CreatureProxy>of(CreatureProxy::new, MobCategory.MISC)
			.sized(0.6F, 0.6F)
			.noSave()
			.noSummon()
			.fireImmune()
			.clientTrackingRange(4)
			.updateInterval(1)
			.build("creature_proxy"));

	private SubEntities() {
	}

	public static void register(IEventBus modBus) {
		ENTITIES.register(modBus);
		modBus.addListener((EntityAttributeCreationEvent e) -> e.put(CREATURE_PROXY.get(), LivingEntity.createLivingAttributes().build()));
	}
}
