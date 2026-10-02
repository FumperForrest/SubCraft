package dev.subcraft.client;

import dev.subcraft.SubCraft;
import dev.subcraft.capture.SectionCapture;
import dev.subcraft.capture.TextureGrabber;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import dev.subcraft.link.SubLink;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Phase 2: streams Minecraft's blocks to the host as they are (MISSION.md 3.2). Every section
 * Minecraft marks dirty (a block changed, a chunk arrived) and every section coming into range is
 * captured with Minecraft's own renderers and sent as kRenSection + kRenLights + kRenColliders.
 * Sections leaving range are removed. Render thread.
 */
public final class SectionStreamer {
	public static int RADIUS_H = 8, RADIUS_V = 5;
	private static final long FRAME_BUDGET_NS = 3_000_000;

	private static final LinkedHashSet<Long> urgent = new LinkedHashSet<>();
	private static final LinkedHashSet<Long> sweep = new LinkedHashSet<>();
	/** Sections the host holds, with the hash of what it got. */
	private static final Map<Long, Long> sent = new HashMap<>();
	private static TextureGrabber.Pixels atlas;
	private static boolean atlasSent;
	private static int seenGeneration = -1;
	private static long center = Long.MIN_VALUE;
	private static List<int[]> offsets;
	private static int captured, removed;

	private SectionStreamer() {
	}

	/** LevelRenderer.setSectionDirty: a block changed or a chunk arrived. */
	public static void markDirty(int sx, int sy, int sz) {
		urgent.add(SectionPos.asLong(sx, sy, sz));
	}

	/** Resource reload: the atlas changed, everything is rebuilt. */
	public static void atlasChanged() {
		atlas = null;
		atlasSent = false;
		sent.clear();
		center = Long.MIN_VALUE;
	}

	public static void frame(Minecraft minecraft) {
		LinkView view = SubLink.view();
		ClientLevel level = minecraft.level;
		if (view == null || level == null || minecraft.player == null || !SubClient.linked() || !SubClient.hostDrawsWorld()) {
			return;
		}
		if (SubLink.generation() != seenGeneration) {
			seenGeneration = SubLink.generation();
			atlasChanged();
			urgent.clear();
			sweep.clear();
			if (!view.tryWriteRender(Proto.REN_CLEAR_ALL, ByteBuffer.allocate(0), null)) {
				seenGeneration = -1; // ring full: try again next frame
				return;
			}
		}
		if (!atlasSent) {
			if (atlas == null) {
				atlas = TextureGrabber.grab(InventoryMenu.BLOCK_ATLAS);
			}
			ByteBuffer hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(atlas.width()).putInt(atlas.height()).flip();
			if (!view.tryWriteRender(Proto.REN_ATLAS, hdr, atlas.rgba().duplicate().clear())) {
				return;
			}
			atlasSent = true;
			dev.subcraft.capture.AtlasAnimator.reset();
			SubCraft.LOG.info("SubCraft: block atlas sent to the host ({}x{})", atlas.width(), atlas.height());
		}
		dev.subcraft.capture.AtlasAnimator.frame(view, atlas);
		SectionPos here = SectionPos.of(minecraft.player.blockPosition());
		if (here.asLong() != center) {
			center = here.asLong();
			recenter(here);
		}
		long deadline = System.nanoTime() + FRAME_BUDGET_NS;
		while (System.nanoTime() < deadline) {
			Long key = next(here);
			if (key == null) {
				break;
			}
			if (!process(view, level, key)) {
				// Ring full: keep it for later.
				urgent.add(key);
				break;
			}
		}
	}

	private static Long next(SectionPos here) {
		LinkedHashSet<Long> q = !urgent.isEmpty() ? urgent : sweep;
		Iterator<Long> it = q.iterator();
		while (it.hasNext()) {
			long k = it.next();
			it.remove();
			if (inRange(here, k)) {
				return k;
			}
		}
		return null;
	}

	private static boolean inRange(SectionPos here, long k) {
		return Math.abs(SectionPos.x(k) - here.x()) <= RADIUS_H && Math.abs(SectionPos.z(k) - here.z()) <= RADIUS_H && Math.abs(SectionPos.y(k) - here.y()) <= RADIUS_V;
	}

	/** New centre: sections in range are swept nearest first; the host drops those out of range. */
	private static void recenter(SectionPos here) {
		if (offsets == null) {
			offsets = new ArrayList<>();
			for (int x = -RADIUS_H; x <= RADIUS_H; x++)
				for (int y = -RADIUS_V; y <= RADIUS_V; y++)
					for (int z = -RADIUS_H; z <= RADIUS_H; z++)
						offsets.add(new int[] {x, y, z});
			offsets.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1] + a[2] * a[2], b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
		}
		sweep.clear();
		for (int[] o : offsets) {
			long k = SectionPos.asLong(here.x() + o[0], here.y() + o[1], here.z() + o[2]);
			if (!sent.containsKey(k)) {
				sweep.add(k);
			}
		}
		LinkView view = SubLink.view();
		for (Iterator<Map.Entry<Long, Long>> it = sent.entrySet().iterator(); it.hasNext();) {
			long k = it.next().getKey();
			if (!inRange(here, k) && view != null && send(view, k, null)) {
				it.remove();
				removed++;
			}
		}
	}

	/** Captures and sends one section; false if the ring had no room. */
	private static boolean process(LinkView view, ClientLevel level, long k) {
		int sx = SectionPos.x(k), sy = SectionPos.y(k), sz = SectionPos.z(k);
		LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
		if (chunk == null || sy < level.getMinSection() || sy >= level.getMaxSection()) {
			return true; // marked dirty again when the chunk arrives
		}
		var section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
		SectionCapture.Result r = SectionCapture.mayHaveContent(section) ? SectionCapture.capture(level, SectionPos.of(sx, sy, sz), atlas) : null;
		if (r != null && r.isEmpty()) {
			r = null;
		}
		Long had = sent.get(k);
		if (r == null) {
			if (had == null) {
				return true;
			}
			if (!send(view, k, null)) {
				return false;
			}
			sent.remove(k);
			return true;
		}
		long hash = r.hash();
		if (had != null && had == hash) {
			return true;
		}
		if (!send(view, k, r)) {
			return false;
		}
		sent.put(k, hash);
		if (captured++ < 5 || captured % 100 == 0) {
			SubCraft.LOG.info("SubCraft: section ({}, {}, {}) to the host: {} vertices, {} lights, {} boxes ({} sections sent so far)", sx, sy, sz, r.vertexCount(),
				r.lights().length, r.boxes().length / 6, captured);
		}
		return true;
	}

	/** kRenSection + kRenLights + kRenColliders for a section, all or nothing (null: remove it). */
	private static boolean send(LinkView view, long k, SectionCapture.Result r) {
		int sx = SectionPos.x(k), sy = SectionPos.y(k), sz = SectionPos.z(k);
		int verts = r != null ? r.vertexCount() : 0;
		long[] lights = r != null ? r.lights() : new long[0];
		float[] boxes = r != null ? r.boxes() : new float[0];
		long need = 3 * 24L + 16 + (long) verts * Proto.REN_VERTEX_BYTES + 16 + lights.length * 8L + 16 + boxes.length * 4L;
		if (view.renderFree() < need * 2) {
			return false;
		}
		ByteBuffer sec = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(verts).flip();
		view.tryWriteRender(Proto.REN_SECTION, sec, r != null ? r.vertices() : null);
		ByteBuffer lb = ByteBuffer.allocate(16 + lights.length * 8).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(lights.length);
		for (long l : lights) {
			lb.putLong(l);
		}
		view.tryWriteRender(Proto.REN_LIGHTS, lb.flip(), null);
		ByteBuffer bb = ByteBuffer.allocate(16 + boxes.length * 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(boxes.length / 6);
		for (float f : boxes) {
			bb.putFloat(f);
		}
		view.tryWriteRender(Proto.REN_COLLIDERS, bb.flip(), null);
		return true;
	}

	public static String stats() {
		return String.format("sections: %d held by the host, %d captures, %d removed, %d urgent, %d sweep", sent.size(), captured, removed, urgent.size(), sweep.size());
	}
}
