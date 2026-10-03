package dev.subcraft;

import com.mojang.logging.LogUtils;
import dev.subcraft.world.CollisionConsumer;
import dev.subcraft.world.SubBlocks;
import dev.subcraft.world.SubWorld;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.SubLink;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import dev.subcraft.world.tri.TriStore;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(SubCraft.MOD_ID)
public final class SubCraft {
	public static final String MOD_ID = "subcraft";
	public static final Logger LOG = LogUtils.getLogger();

	public SubCraft(IEventBus modBus) {
		SubBlocks.register(modBus);
		dev.subcraft.combat.SubEntities.register(modBus);
		dev.subcraft.world.ghost.ChunkGhostData.register(modBus);
		NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Load e) -> {
			if (e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk c && !e.getLevel().isClientSide() && c.getLevel() != null && SubWorld.is(c.getLevel())) {
				dev.subcraft.world.ghost.GhostTerrain.chunkLoaded(c);
			}
		});
		NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload e) -> {
			if (e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk c && !e.getLevel().isClientSide() && c.getLevel() != null && SubWorld.is(c.getLevel())) {
				dev.subcraft.world.ghost.GhostTerrain.chunkUnloaded(c);
			}
		});
		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> CollisionConsumer.serverTick(e.getServer().overworld()));
		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> followHostTime(e.getServer().overworld()));
		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> dev.subcraft.combat.Proxies.serverTick(e.getServer().overworld()));
		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> dev.subcraft.combat.MobTable.serverTick(e.getServer().overworld()));
		NeoForge.EVENT_BUS.addListener(dev.subcraft.combat.MobTable::onJoin);
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> configureWorld(e.getServer().overworld()));
		NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post e) -> holdBreath(e.getEntity()));
		NeoForge.EVENT_BUS.addListener(SubCraft::spawnOnlyOnKnownTerrain);
		NeoForge.EVENT_BUS.addListener(SubCraft::logPlayerDamage);
		NeoForge.EVENT_BUS.addListener(SubCraft::freezeOnUnknownTerrain);
	}

	private static final LinkView.HostState breathState = new LinkView.HostState();
	private static final LinkView.HostState timeState = new LinkView.HostState();

	/**
	 * Subnautica's day/night drives Minecraft's time (MISSION.md 3.4). The host sends its cycle with
	 * 0 = midnight, 0.25 = sunrise, 0.5 = noon, 0.75 = sunset; Minecraft's day starts at sunrise
	 * (tick 0), noon 6000, sunset 12000, midnight 18000. The day counter keeps counting forward.
	 */
	private static void followHostTime(ServerLevel level) {
		LinkView view = SubLink.view();
		if (!SubWorld.is(level) || view == null || !SubLink.active()) {
			return;
		}
		view.readHostState(timeState);
		if (!timeState.inGame()) {
			return;
		}
		long now = level.getDayTime();
		long next = dev.subcraft.core.TimeSync.follow(now, timeState.dayFraction);
		if (next != now) {
			level.setDayTime(next);
		}
	}

	/**
	 * Oxygen belongs to the host (MISSION.md section 2): the air bar shows Subnautica's oxygen, and
	 * Minecraft never drowns a player in the SubCraft world while a host is linked (resetting the
	 * air every tick keeps it from ever counting down to drowning damage; running out of oxygen is
	 * Subnautica's suffocation).
	 */
	private static void holdBreath(Player player) {
		LinkView view = SubLink.view();
		if (!(player instanceof ServerPlayer) || !SubWorld.is(player.level()) || view == null || !SubLink.active()) {
			return;
		}
		view.readHostState(breathState);
		int max = player.getMaxAirSupply();
		if (breathState.oxygenCapacity > 0) {
			float f = Math.max(0, Math.min(1, breathState.oxygen / breathState.oxygenCapacity));
			player.setAirSupply(Math.round(f * max));
		} else {
			player.setAirSupply(max);
		}
	}

	/** Every hurt of the player in the SubCraft world, with its source: health loss always needs explaining. */
	private static void logPlayerDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) {
		if (e.getEntity() instanceof ServerPlayer p && SubWorld.is(p.level())) {
			var src = e.getSource();
			LOG.info("SubCraft: player took {} damage ({} by {}), health {}", e.getNewDamage(), src.getMsgId(),
				src.getEntity() != null ? src.getEntity().getType().toShortString() : "-", p.getHealth());
		}
	}

	/**
	 * Unpatched space is inert (MISSION.md 3.1): no mob spawns where the host hasn't described the
	 * terrain yet, or mobs would appear in "open water" that turns out to be rock.
	 */
	private static void spawnOnlyOnKnownTerrain(MobSpawnEvent.SpawnPlacementCheck e) {
		var pos = e.getPos();
		if (SubWorld.is(e.getLevel().getLevel()) && SubLink.active() && !TriStore.isKnown(pos.getX(), pos.getY(), pos.getZ())) {
			e.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
		}
	}

	/** Mobs in space the host hasn't described stand still (no ticking) until it has. */
	private static void freezeOnUnknownTerrain(EntityTickEvent.Pre e) {
		var entity = e.getEntity();
		if (entity instanceof Player || entity instanceof dev.subcraft.combat.CreatureProxy || entity.level().isClientSide()
			|| !SubWorld.is(entity.level()) || !SubLink.active()) {
			return;
		}
		var pos = entity.blockPosition();
		if (!TriStore.isEmpty() && !TriStore.isKnown(pos.getX(), pos.getY(), pos.getZ())) {
			e.setCanceled(true);
		}
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
		// Natural spawning stays off by default (vanilla ocean mobs in Subnautica is a taste call
		// for Sean); /gamerule doMobSpawning true turns it on, and then only on known terrain.
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
		level.setWeatherParameters(1_000_000, 0, false, false);
		LOG.info("SubCraft: SubCraft world configured");
	}
}
