package dev.subcraft.combat;

import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import dev.subcraft.link.SubLink;
import dev.subcraft.world.SubWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Minecraft's mobs in Subnautica's ecosystem (MISSION.md 3.4), the Minecraft half: every server
 * tick the mobs near the player go to the host's mob table (the host gives each an invisible
 * stand-in its creatures can hunt and bite), a creature's bite comes back as kInHurtMob, and
 * hostile mobs in the SubCraft world also go after the creatures' proxies.
 */
public final class MobTable {
	private static final double RANGE = 48.0;
	private static final List<LinkView.Mob> scratch = new ArrayList<>();
	static int bitesTaken;

	private MobTable() {
	}

	public static void serverTick(ServerLevel level) {
		LinkView view = SubLink.view();
		if (!SubWorld.is(level) || view == null || !SubLink.active()) {
			return;
		}
		scratch.clear();
		for (ServerPlayer player : level.players()) {
			AABB around = player.getBoundingBox().inflate(RANGE);
			for (Entity e : level.getEntities((Entity) null, around, e -> e instanceof Mob && e.isAlive() && !(e instanceof CreatureProxy))) {
				if (scratch.size() >= Proto.MAX_MOBS) {
					break;
				}
				LivingEntity mob = (LivingEntity) e;
				int flags = mob instanceof Enemy ? Proto.MOB_HOSTILE : 0;
				scratch.add(new LinkView.Mob(mob.getId(), flags, (float) mob.getX(), (float) mob.getY(), (float) mob.getZ(),
					mob.getBbWidth(), mob.getBbHeight(), mob.getMaxHealth() > 0 ? mob.getHealth() / mob.getMaxHealth() : 1.0F));
			}
		}
		view.writeMobs(scratch);
	}

	/** kInHurtMob (any thread): a host creature bit a Minecraft mob. */
	public static void hurt(net.minecraft.server.MinecraftServer server, int mobId, float amount, int attackerId) {
		if (server == null || amount <= 0) {
			return;
		}
		server.execute(() -> {
			ServerLevel level = server.overworld();
			Entity e = level.getEntity(mobId);
			if (!(e instanceof LivingEntity mob) || !mob.isAlive()) {
				return;
			}
			CreatureProxy proxy = attackerId != 0 ? Proxies.proxy(attackerId) : null;
			var sources = level.damageSources();
			mob.hurt(proxy != null ? sources.mobAttack(proxy) : sources.generic(), amount);
			bitesTaken++;
		});
	}

	/** Hostile mobs in the SubCraft world hunt the creatures too (after players). */
	public static void onJoin(EntityJoinLevelEvent e) {
		if (e.getLevel().isClientSide() || !SubWorld.is(e.getLevel()) || !(e.getEntity() instanceof Mob mob) || !(mob instanceof Enemy)) {
			return;
		}
		mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, CreatureProxy.class, 10, true, false, null));
	}

	public static String stats() {
		return "mobs " + scratch.size() + ", bites taken " + bitesTaken;
	}
}
