package dev.subcraft.capture;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import dev.subcraft.link.SubLink;
import dev.subcraft.mixin.client.GameRendererInvoker;
import dev.subcraft.mixin.client.ParticleEngineAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 3: everything that moves, captured each frame instead of drawn (MISSION.md 3.2). Entities
 * (any mod's renderers), block entities, particles and the first-person hand go through
 * Minecraft's own renderers into capture buffers, one per RenderType, and are sent as one
 * kRenScene (world, relative to the camera) and one kRenHand (view space). Textures other than
 * the block atlas are sent once, as kRenTexture, when first used.
 */
public final class DynamicCapture {
	public static double RANGE = 64;
	private static final int FULL_BRIGHT = 0xF000F0; // the host lights everything itself
	private static final int MAX_VERTICES = 400_000;

	private static final Map<ResourceLocation, Integer> textureIds = new HashMap<>();
	private static int seenGeneration = -1;
	private static long scenes, dropped;
	private static int lastVertices, lastBatches;

	private DynamicCapture() {
	}

	/** One RenderType's (or particle sheet's) vertices. */
	private record Batch(int texture, int material, CaptureBuffer buffer) {
	}

	/** Hands Minecraft's renderers our buffers, one per RenderType. */
	private static final class Source extends MultiBufferSource.BufferSource {
		final Map<RenderType, CaptureBuffer> buffers = new LinkedHashMap<>();

		Source() {
			super(new ByteBufferBuilder(256), new java.util.LinkedHashMap<>());
		}

		@Override
		public VertexConsumer getBuffer(RenderType type) {
			return this.buffers.computeIfAbsent(type, t -> new CaptureBuffer().material(RenderClassifier.classify(t).material()));
		}

		@Override
		public void endBatch() {
		}

		@Override
		public void endBatch(RenderType type) {
		}

		@Override
		public void endLastBatch() {
		}
	}

	/** Called where the skipped world pass would have drawn the level. Render thread. */
	public static void frame(Minecraft minecraft, Camera camera, float partial) {
		LinkView view = SubLink.view();
		ClientLevel level = minecraft.level;
		if (view == null || level == null || minecraft.player == null) {
			return;
		}
		// Per-frame draws are worthless once stale: none while the host is loading or in a menu
		// screen outside the game (it doesn't drain then, and they'd queue ahead of the world).
		var host = dev.subcraft.client.SubClient.host();
		if (!host.inGame() || host.loading()) {
			return;
		}
		if (SubLink.generation() != seenGeneration) {
			seenGeneration = SubLink.generation();
			textureIds.clear();
			textureIds.put(InventoryMenu.BLOCK_ATLAS, 0); // sent by SectionStreamer as kRenAtlas
		}
		Vec3 origin = camera.getPosition();
		List<Batch> world = new ArrayList<>();

		// Entities.
		Source entities = new Source();
		var dispatcher = minecraft.getEntityRenderDispatcher();
		PoseStack pose = new PoseStack();
		boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
		for (Entity e : level.entitiesForRendering()) {
			if (e == camera.getEntity() && firstPerson && !camera.isDetached()) {
				continue; // the hand is captured on its own
			}
			if (e.distanceToSqr(origin) > RANGE * RANGE) {
				continue;
			}
			double x = net.minecraft.util.Mth.lerp(partial, e.xOld, e.getX()) - origin.x;
			double y = net.minecraft.util.Mth.lerp(partial, e.yOld, e.getY()) - origin.y;
			double z = net.minecraft.util.Mth.lerp(partial, e.zOld, e.getZ()) - origin.z;
			try {
				dispatcher.render(e, x, y, z, net.minecraft.util.Mth.lerp(partial, e.yRotO, e.getYRot()), partial, pose, entities, FULL_BRIGHT);
			} catch (RuntimeException ex) {
				warnOnce(e.getType().toString(), ex);
			}
		}

		// Block entities (chests, signs, beds, banners...) in the sections around the camera.
		var beDispatcher = minecraft.getBlockEntityRenderDispatcher();
		beDispatcher.prepare(level, camera, minecraft.hitResult);
		int cr = (int) Math.ceil(RANGE / 16);
		int ccx = (int) Math.floor(origin.x) >> 4, ccz = (int) Math.floor(origin.z) >> 4;
		for (int cx = ccx - cr; cx <= ccx + cr; cx++) {
			for (int cz = ccz - cr; cz <= ccz + cr; cz++) {
				var chunk = level.getChunkSource().getChunk(cx, cz, false);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					var p = be.getBlockPos();
					if (p.distToCenterSqr(origin) > RANGE * RANGE) {
						continue;
					}
					pose.pushPose();
					pose.translate(p.getX() - origin.x, p.getY() - origin.y, p.getZ() - origin.z);
					try {
						beDispatcher.render(be, partial, pose, entities);
					} catch (RuntimeException ex) {
						warnOnce(be.getType().toString(), ex);
					}
					pose.popPose();
				}
			}
		}
		for (var e : entities.buffers.entrySet()) {
			RenderClassifier.Info info = RenderClassifier.classify(e.getKey());
			int tex = info.texture().map(rl -> texture(view, rl)).orElse(-1);
			if (tex < 0) {
				// No texture we can read (a non-composite or custom RenderType): the future fallback
				// layer's job; logged once so the compat runs show what's missing.
				warnOnce("fallback: " + e.getKey(), new IllegalStateException("render type not captured (" + e.getValue().vertexCount() + " vertices)"));
			}
			if (tex >= 0) {
				world.add(new Batch(tex, info.material() | (info.backfaceCulling() ? 0 : Proto.REN_DOUBLE_SIDED), e.getValue()));
			}
		}

		// Particles: each draws itself relative to the camera into its sheet's buffer.
		var particles = ((ParticleEngineAccessor) minecraft.particleEngine).subcraft$particles();
		for (Map.Entry<ParticleRenderType, Queue<Particle>> e : particles.entrySet()) {
			ParticleRenderType type = e.getKey();
			ResourceLocation atlas;
			int material;
			if (type == ParticleRenderType.TERRAIN_SHEET) {
				atlas = TextureAtlas.LOCATION_BLOCKS;
				material = Proto.REN_MAT_CUTOUT;
			} else if (type == ParticleRenderType.PARTICLE_SHEET_OPAQUE) {
				atlas = TextureAtlas.LOCATION_PARTICLES;
				material = Proto.REN_MAT_CUTOUT;
			} else if (type == ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT) {
				atlas = TextureAtlas.LOCATION_PARTICLES;
				material = Proto.REN_MAT_TRANSLUCENT;
			} else if (type == ParticleRenderType.PARTICLE_SHEET_LIT) {
				atlas = TextureAtlas.LOCATION_PARTICLES;
				material = Proto.REN_MAT_EMISSIVE;
			} else {
				continue; // CUSTOM, NO_RENDER, modded sheets: the fallback layer's job
			}
			CaptureBuffer buf = new CaptureBuffer().material(material);
			for (Particle p : e.getValue()) {
				// Ambient specks (underwater suspension, spores): the host has its own marine snow.
				if (p instanceof net.minecraft.client.particle.SuspendedParticle) {
					continue;
				}
				if (p.getPos().distanceToSqr(origin) <= RANGE * RANGE) {
					p.render(buf, camera, partial);
				}
			}
			if (buf.vertexCount() > 0) {
				world.add(new Batch(texture(view, atlas), material | Proto.REN_DOUBLE_SIDED, buf));
			}
		}
		send(view, Proto.REN_SCENE, origin, world);

		// The first-person hand and held item, in view space.
		List<Batch> hand = new ArrayList<>();
		if (firstPerson && !camera.isDetached() && !minecraft.options.hideGui && minecraft.gameMode != null
			&& minecraft.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SPECTATOR) {
			Source handSource = new Source();
			PoseStack handPose = new PoseStack();
			GameRendererInvoker gr = (GameRendererInvoker) minecraft.gameRenderer;
			gr.subcraft$bobHurt(handPose, partial);
			if (minecraft.options.bobView().get()) {
				gr.subcraft$bobView(handPose, partial);
			}
			minecraft.gameRenderer.itemInHandRenderer.renderHandsWithItems(partial, handPose, handSource, minecraft.player, FULL_BRIGHT);
			for (var e : handSource.buffers.entrySet()) {
				RenderClassifier.Info info = RenderClassifier.classify(e.getKey());
				int tex = info.texture().map(rl -> texture(view, rl)).orElse(-1);
				if (tex >= 0) {
					hand.add(new Batch(tex, info.material() | (info.backfaceCulling() ? 0 : Proto.REN_DOUBLE_SIDED), e.getValue()));
				}
			}
		}
		send(view, Proto.REN_HAND, Vec3.ZERO, hand);
	}

	/** Texture id for the host; sends the pixels the first time (-1 if it can't be read). */
	private static int texture(LinkView view, ResourceLocation rl) {
		Integer id = textureIds.get(rl);
		if (id != null) {
			return id;
		}
		TextureGrabber.Pixels p;
		try {
			p = TextureGrabber.grab(rl);
		} catch (RuntimeException ex) {
			warnOnce(rl.toString(), ex);
			textureIds.put(rl, -1);
			return -1;
		}
		int newId = textureIds.size();
		ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(newId).putInt(p.width()).putInt(p.height()).putInt(0).flip();
		if (!view.tryWriteRender(Proto.REN_TEXTURE, hdr, p.rgba().duplicate().clear())) {
			return -1; // ring full: try again next frame
		}
		textureIds.put(rl, newId);
		SubCraft.LOG.info("SubCraft: texture {} -> host id {} ({}x{})", rl, newId, p.width(), p.height());
		return newId;
	}

	/** RenScene header + RenBatch[] + RenVertex[] (batches with the same texture and material merged). */
	private static void send(LinkView view, int type, Vec3 origin, List<Batch> batches) {
		Map<Long, List<Batch>> merged = new LinkedHashMap<>();
		int total = 0;
		for (Batch b : batches) {
			int n = b.buffer().vertexCount();
			if (n == 0) {
				continue;
			}
			total += n;
			merged.computeIfAbsent((long) b.texture() << 32 | b.material(), k -> new ArrayList<>()).add(b);
		}
		if (total > MAX_VERTICES) {
			dropped++;
			return;
		}
		ByteBuffer head = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
		head.putDouble(origin.x).putDouble(origin.y).putDouble(origin.z).putInt(merged.size()).putInt(total).flip();
		ByteBuffer body = ByteBuffer.allocate(merged.size() * Proto.REN_BATCH_BYTES + total * Proto.REN_VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		int first = 0;
		for (var e : merged.entrySet()) {
			int count = 0;
			for (Batch b : e.getValue()) {
				count += b.buffer().vertexCount();
			}
			body.putInt((int) (e.getKey() >> 32)).putInt(first).putInt(count).putInt((int) (long) e.getKey());
			first += count;
		}
		for (var e : merged.values()) {
			for (Batch b : e) {
				body.put(b.buffer().bytes());
			}
		}
		if (view.renderFree() < (long) body.position() * 4 + 64) {
			dropped++; // the host is behind: skip this frame's dynamic draws
			return;
		}
		view.tryWriteRender(type, head, body.flip());
		if (type == Proto.REN_SCENE) {
			scenes++;
			lastVertices = total;
			lastBatches = merged.size();
		}
	}

	private static final java.util.Set<String> warned = new java.util.HashSet<>();

	private static void warnOnce(String what, RuntimeException ex) {
		if (warned.add(what)) {
			SubCraft.LOG.warn("SubCraft: capturing {} failed: {}", what, ex.toString());
		}
	}

	public static String stats() {
		return String.format("dynamic: %d scenes sent, %d dropped, last %d vertices in %d batches, %d textures", scenes, dropped, lastVertices, lastBatches,
			textureIds.size());
	}
}
