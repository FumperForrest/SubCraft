package dev.subcraft.combat;

import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.SubLink;
import dev.subcraft.world.SubWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Keeps one {@link CreatureProxy} per creature in the host's table (server thread): spawns new
 * ones, moves them every tick, removes the ones the host dropped. The table is also kept as a
 * lookup for the client side (box sizes), hits and the hurt source.
 */
public final class Proxies {
	private static volatile Map<Integer, LinkView.Creature> table = new HashMap<>();
	private static final Map<Integer, CreatureProxy> spawned = new HashMap<>();
	private static final List<LinkView.Creature> scratch = new ArrayList<>();
	static int hits;

	private Proxies() {
	}

	public static LinkView.Creature lookup(int id) {
		return table.get(id);
	}

	/** The proxy of a host creature (server thread), or null. */
	public static CreatureProxy proxy(int id) {
		return spawned.get(id);
	}

	public static String stats() {
		return "creatures " + table.size() + ", proxies " + spawned.size() + ", hits sent " + hits;
	}

	public static void serverTick(ServerLevel level) {
		LinkView view = SubLink.view();
		if (!SubWorld.is(level) || view == null || !SubLink.active()) {
			clear();
			return;
		}
		if (!view.readCreatures(scratch)) {
			return; // the host was writing: next tick
		}
		Map<Integer, LinkView.Creature> next = new HashMap<>();
		for (LinkView.Creature c : scratch) {
			next.put(c.id(), c);
		}
		table = next;
		for (Iterator<Map.Entry<Integer, CreatureProxy>> it = spawned.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			if (!next.containsKey(e.getKey()) || e.getValue().isRemoved()) {
				e.getValue().discard();
				it.remove();
			}
		}
		for (LinkView.Creature c : scratch) {
			if (!level.isLoaded(BlockPos.containing(c.x(), c.y(), c.z()))) {
				continue;
			}
			CreatureProxy p = spawned.get(c.id());
			if (p == null) {
				p = SubEntities.CREATURE_PROXY.get().create(level);
				if (p == null) {
					continue;
				}
				p.setHostId(c.id());
				p.sizeTo(c.width(), c.height());
				p.moveTo(c.x(), c.y(), c.z(), c.yaw(), 0);
				level.addFreshEntity(p);
				spawned.put(c.id(), p);
			} else {
				p.sizeTo(c.width(), c.height());
				p.moveTo(c.x(), c.y(), c.z(), c.yaw(), 0);
				p.setYHeadRot(c.yaw());
			}
		}
	}

	/** Link lost or world closed: no proxies without a host. */
	public static void clear() {
		if (spawned.isEmpty() && table.isEmpty()) {
			return;
		}
		for (Entity e : spawned.values()) {
			e.discard();
		}
		spawned.clear();
		table = new HashMap<>();
		SubCraft.LOG.info("SubCraft: creature proxies cleared");
	}
}
