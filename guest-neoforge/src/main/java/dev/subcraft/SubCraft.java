package dev.subcraft;

import com.mojang.logging.LogUtils;
import dev.subcraft.world.CollisionConsumer;
import dev.subcraft.world.SubBlocks;
import dev.subcraft.world.SubWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(SubCraft.MOD_ID)
public final class SubCraft {
	public static final String MOD_ID = "subcraft";
	public static final Logger LOG = LogUtils.getLogger();

	public SubCraft(IEventBus modBus) {
		SubBlocks.register(modBus);
		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> CollisionConsumer.serverTick(e.getServer().overworld()));
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> configureWorld(e.getServer().overworld()));
	}

	/** The host owns time, weather and (for now) spawning in the SubCraft world. */
	private static void configureWorld(ServerLevel level) {
		if (!SubWorld.is(level)) {
			return;
		}
		var server = level.getServer();
		GameRules rules = level.getGameRules();
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
		// Phase 0a: no mobs until terrain patching exists (unpatched chunks must be inert).
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
		level.setWeatherParameters(1_000_000, 0, false, false);
		LOG.info("SubCraft: SubCraft world configured");
	}
}
