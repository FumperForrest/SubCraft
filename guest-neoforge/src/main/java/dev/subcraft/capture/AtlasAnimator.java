package dev.subcraft.capture;

import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Animated block sprites (lava, fire, magma, sea lantern, modded): a few times a second the block
 * atlas is read back and every animated sprite whose pixels changed goes to the host as
 * kRenAtlasRegion, which re-bakes just the cells that use it. Render thread.
 */
public final class AtlasAnimator {
	private static final long INTERVAL_NS = 250_000_000L;

	private record Region(int x, int y, int w, int h) {
	}

	private static List<Region> regions;
	private static byte[][] last;
	private static long nextNs;
	private static long sent;

	private AtlasAnimator() {
	}

	/** The atlas was (re)sent whole: start over. */
	public static void reset() {
		regions = null;
		last = null;
	}

	public static void frame(LinkView view, TextureGrabber.Pixels sentAtlas) {
		long now = System.nanoTime();
		if (now < nextNs) {
			return;
		}
		nextNs = now + INTERVAL_NS;
		if (regions == null) {
			TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
			regions = new ArrayList<>();
			for (TextureAtlasSprite s : atlas.getTextures().values()) {
				if (s.contents().getUniqueFrames().count() > 1) {
					regions.add(new Region(s.getX(), s.getY(), s.contents().width(), s.contents().height()));
				}
			}
			last = new byte[regions.size()][];
			for (int i = 0; i < regions.size(); i++) {
				last[i] = copy(sentAtlas, regions.get(i));
			}
			SubCraft.LOG.info("SubCraft: {} animated block sprites", regions.size());
			return;
		}
		if (regions.isEmpty()) {
			return;
		}
		TextureGrabber.Pixels now2 = TextureGrabber.grab(InventoryMenu.BLOCK_ATLAS);
		if (now2.width() != sentAtlas.width() || now2.height() != sentAtlas.height()) {
			return;
		}
		for (int i = 0; i < regions.size(); i++) {
			Region r = regions.get(i);
			byte[] px = copy(now2, r);
			if (Arrays.equals(px, last[i])) {
				continue;
			}
			ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(r.x()).putInt(r.y()).putInt(r.w()).putInt(r.h()).flip();
			if (!view.tryWriteRender(Proto.REN_ATLAS_REGION, hdr, ByteBuffer.wrap(px))) {
				return;
			}
			last[i] = px;
			sent++;
		}
	}

	private static byte[] copy(TextureGrabber.Pixels p, Region r) {
		byte[] out = new byte[r.w() * r.h() * 4];
		ByteBuffer src = p.rgba();
		for (int y = 0; y < r.h(); y++) {
			src.get((y + r.y()) * p.width() * 4 + r.x() * 4, out, y * r.w() * 4, r.w() * 4);
		}
		return out;
	}

	public static long sent() {
		return sent;
	}
}
