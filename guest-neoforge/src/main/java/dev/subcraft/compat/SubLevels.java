package dev.subcraft.compat;

import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

/**
 * Sable sub-levels (Create: Aeronautics ships, physics-assembled builds): each is a block region in a
 * "plot" far away in the world, drawn by Sable at a pose. Here the plot's sections near the player go
 * to the host as ordinary sections (SectionStreamer streams the keys this class allows) and every
 * frame kRenSubLevel tells the host where to draw them. Reflection only: Sable is optional.
 */
public final class SubLevels {
	private static final double RANGE = 160;
	private static boolean looked, present;
	private static Method getContainer, getAllSubLevels, renderPose, getPlot, getBoundingBox, getUniqueId, isRemoved;
	private static Method position, orientation, rotationPoint, scale;
	private static Method minX, minY, minZ, maxX, maxY, maxZ;
	/** Plot section keys of the sub-levels being streamed. */
	private static final Set<Long> allowed = new HashSet<>();
	private static final Map<Integer, int[]> sent = new HashMap<>(); // id -> section range last sent
	private static final Set<String> warned = new HashSet<>();
	public static int count, total;

	/** A ship as last sent to the host: its pose (MC) and plot sections. */
	public record Ship(double px, double py, double pz, double qx, double qy, double qz, double qw, double rx, double ry, double rz, double sx, double sy,
		double sz, int[] range) {
	}

	/** This frame's ships (DynamicCapture draws their block entities at the same pose). */
	public static volatile List<Ship> ships = List.of();
	private static String nearest = "";

	private SubLevels() {
	}

	public static boolean allows(long sectionKey) {
		return allowed.contains(sectionKey);
	}

	/** Every frame, before SectionStreamer: which plot sections to stream, and the poses. */
	public static void frame(Minecraft minecraft, LinkView view, java.util.function.LongConsumer queue) {
		if (!lookUp() || minecraft.level == null || minecraft.player == null) {
			return;
		}
		float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
		Vec3 eye = minecraft.player.getEyePosition(partial);
		Set<Integer> seen = new HashSet<>();
		Set<Long> nowAllowed = new HashSet<>();
		List<Ship> nowShips = new java.util.ArrayList<>();
		try {
			Object container = getContainer.invoke(null, minecraft.level);
			if (container == null) {
				return;
			}
			List<?> all = (List<?>) getAllSubLevels.invoke(container);
			total = all.size();
			double best = Double.MAX_VALUE;
			for (Object sub : all) {
				if ((boolean) isRemoved.invoke(sub)) {
					continue;
				}
				Object pose = renderPose.invoke(sub, partial);
				Vector3dc pos = (Vector3dc) position.invoke(pose);
				double dist = Math.sqrt(eye.distanceToSqr(pos.x(), pos.y(), pos.z()));
				if (dist < best) {
					best = dist;
					nearest = String.format("%.0f %.0f %.0f (%.0f away)", pos.x(), pos.y(), pos.z(), dist);
				}
				if (dist > RANGE) {
					continue;
				}
				Object box = getBoundingBox.invoke(getPlot.invoke(sub));
				int[] range = {(int) minX.invoke(box) >> 4, (int) minY.invoke(box) >> 4, (int) minZ.invoke(box) >> 4, (int) maxX.invoke(box) >> 4,
					(int) maxY.invoke(box) >> 4, (int) maxZ.invoke(box) >> 4};
				int id = getUniqueId.invoke(sub).hashCode();
				seen.add(id);
				for (int x = range[0]; x <= range[3]; x++) {
					for (int y = range[1]; y <= range[4]; y++) {
						for (int z = range[2]; z <= range[5]; z++) {
							long k = SectionPos.asLong(x, y, z);
							nowAllowed.add(k);
							if (!allowed.contains(k)) {
								queue.accept(k); // new to the stream: capture it
							}
						}
					}
				}
				Quaterniondc q = (Quaterniondc) orientation.invoke(pose);
				Vector3dc pivot = (Vector3dc) rotationPoint.invoke(pose), s = (Vector3dc) scale.invoke(pose);
				ByteBuffer b = ByteBuffer.allocate(Proto.REN_SUB_LEVEL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
				b.putInt(id).putInt(0);
				for (int v : range) {
					b.putInt(v);
				}
				b.putDouble(pos.x()).putDouble(pos.y()).putDouble(pos.z());
				b.putDouble(q.x()).putDouble(q.y()).putDouble(q.z()).putDouble(q.w());
				b.putDouble(pivot.x()).putDouble(pivot.y()).putDouble(pivot.z());
				b.putDouble(s.x()).putDouble(s.y()).putDouble(s.z());
				view.tryWriteRender(Proto.REN_SUB_LEVEL, b.flip(), null);
				nowShips.add(new Ship(pos.x(), pos.y(), pos.z(), q.x(), q.y(), q.z(), q.w(), pivot.x(), pivot.y(), pivot.z(), s.x(), s.y(), s.z(), range));
				sent.put(id, range);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (warned.add(e.toString())) {
				SubCraft.LOG.warn("SubCraft: reading Sable's sub-levels failed: {}", e.toString());
			}
		}
		// Gone (or out of range): tell the host.
		for (var it = sent.keySet().iterator(); it.hasNext();) {
			int id = it.next();
			if (!seen.contains(id)) {
				ByteBuffer b = ByteBuffer.allocate(Proto.REN_SUB_LEVEL_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(1);
				b.position(Proto.REN_SUB_LEVEL_BYTES);
				if (view.tryWriteRender(Proto.REN_SUB_LEVEL, b.flip(), null)) {
					it.remove();
				}
			}
		}
		ships = nowShips;
		allowed.clear();
		allowed.addAll(nowAllowed);
		count = seen.size();
	}

	private static boolean lookUp() {
		if (looked) {
			return present;
		}
		looked = true;
		if (!ModList.get().isLoaded("sable")) {
			return false;
		}
		try {
			Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
			getContainer = containerClass.getMethod("getContainer", net.minecraft.client.multiplayer.ClientLevel.class);
			getAllSubLevels = Class.forName("dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer").getMethod("getAllSubLevels");
			Class<?> subClass = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
			renderPose = subClass.getMethod("renderPose", float.class);
			getPlot = subClass.getMethod("getPlot");
			getUniqueId = subClass.getMethod("getUniqueId");
			isRemoved = subClass.getMethod("isRemoved");
			getBoundingBox = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot").getMethod("getBoundingBox");
			Class<?> poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
			position = poseClass.getMethod("position");
			orientation = poseClass.getMethod("orientation");
			rotationPoint = poseClass.getMethod("rotationPoint");
			scale = poseClass.getMethod("scale");
			Class<?> boxClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
			minX = boxClass.getMethod("minX");
			minY = boxClass.getMethod("minY");
			minZ = boxClass.getMethod("minZ");
			maxX = boxClass.getMethod("maxX");
			maxY = boxClass.getMethod("maxY");
			maxZ = boxClass.getMethod("maxZ");
			present = true;
			SubCraft.LOG.info("SubCraft: Sable found: its sub-levels are drawn by the host");
		} catch (ReflectiveOperationException | LinkageError e) {
			SubCraft.LOG.warn("SubCraft: Sable is installed but its API isn't what SubCraft expects ({}): sub-levels won't show", e.toString());
		}
		return present;
	}

	public static String stats() {
		return "sub-levels: " + total + " (nearest " + nearest + "), drawn: " + count + ", plot sections streamed: " + allowed.size();
	}
}
