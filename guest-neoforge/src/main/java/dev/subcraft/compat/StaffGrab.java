package dev.subcraft.compat;

import dev.subcraft.client.InputBridge;
import dev.subcraft.client.SubClient;
import dev.subcraft.combat.CreatureProxy;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import dev.subcraft.link.SubLink;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Create: Aeronautics' physics staff on Subnautica's things (creatures, vehicles, loose items): the
 * staff itself only drags Sable sub-levels, so when its use starts on one of the host's proxies,
 * this holds that object instead: click to pick it up, click again to drop it (like the staff on a
 * ship). Every tick the point the staff holds (the eye plus the look at the grab distance) goes to
 * the host (kEvGrab), which steers the object there; dropping keeps its momentum (a throw), and
 * punching while holding freezes it in place (punch it again to free it), like the staff's lock. Any item whose class is a physics staff counts (no hard dependency).
 */
public final class StaffGrab {
	private static final double REACH = 48;
	private static int grabbed;   // host id, 0 = none
	private static double distance;
	private static boolean wasUse, wasAttack;

	private StaffGrab() {
	}

	public static void tick(Minecraft minecraft) {
		LinkView view = SubLink.view();
		var player = minecraft.player;
		if (view == null || player == null || !SubClient.linked() || minecraft.screen != null) {
			release(view);
			wasUse = wasAttack = false;
			return;
		}
		boolean staff = player.getMainHandItem().getItem().getClass().getSimpleName().contains("PhysicsStaff");
		boolean use = InputBridge.isButtonDown(1), attack = InputBridge.isButtonDown(0);
		boolean useStart = use && !wasUse, attackStart = attack && !wasAttack;
		wasUse = use;
		wasAttack = attack;
		if (!staff) {
			release(view);
			return;
		}
		Vec3 eye = player.getEyePosition(1.0F), look = player.getViewVector(1.0F);
		if (grabbed != 0) {
			if (attackStart) {
				view.pushEvent(Proto.EV_GRAB, grabbed, 0, 0, 0, 0, Proto.GRAB_LOCK, 0);
				grabbed = 0;
				return;
			}
			if (useStart) {
				release(view); // second click: drop
				return;
			}
			Vec3 goal = eye.add(look.scale(distance));
			view.pushEvent(Proto.EV_GRAB, grabbed, (float) goal.x, (float) goal.y, (float) goal.z, 0, Proto.GRAB_HOLD, 0);
			return;
		}
		if (!useStart && !attackStart) {
			return;
		}
		CreatureProxy target = aimed(minecraft, eye, look);
		if (target == null) {
			return;
		}
		if (useStart) {
			grabbed = target.hostId();
			distance = Math.max(2.0, eye.distanceTo(target.getBoundingBox().getCenter()));
			view.pushEvent(Proto.EV_GRAB, grabbed, (float) target.getX(), (float) target.getY(), (float) target.getZ(), 0, Proto.GRAB_HOLD, 0);
		} else {
			view.pushEvent(Proto.EV_GRAB, target.hostId(), 0, 0, 0, 0, Proto.GRAB_LOCK, 0); // unlock a frozen one
		}
	}

	/** The nearest proxy along the look, if no block is in front of it. */
	private static CreatureProxy aimed(Minecraft minecraft, Vec3 eye, Vec3 look) {
		var player = minecraft.player;
		Vec3 end = eye.add(look.scale(REACH));
		var block = minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double blockDist = block.getType() == HitResult.Type.MISS ? REACH : block.getLocation().distanceTo(eye);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(minecraft.level, player, eye, end, player.getBoundingBox().expandTowards(look.scale(REACH)).inflate(1.0),
			e -> e instanceof CreatureProxy, 0.3F);
		if (hit == null || hit.getLocation().distanceTo(eye) > blockDist) {
			return null;
		}
		return (CreatureProxy) hit.getEntity();
	}

	private static void release(LinkView view) {
		if (grabbed != 0 && view != null) {
			view.pushEvent(Proto.EV_GRAB, grabbed, 0, 0, 0, 0, Proto.GRAB_RELEASE, 0);
		}
		grabbed = 0;
	}

	public static String stats() {
		return grabbed != 0 ? "staff holding host object " + grabbed + " at " + String.format("%.1f", distance) : "staff holding nothing";
	}
}
