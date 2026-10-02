package dev.subcraft.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.subcraft.SubCraft;
import dev.subcraft.link.Proto;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Phase 0c: captures the blocks around a point exactly as Minecraft's own renderers build them
 * (block models with tint and ambient occlusion, fluids, block entities) and writes them in the
 * capture-dump format (protocol header: a 16-byte file header, then render-ring messages): the
 * block atlas, other textures, one kRenSection per 16-block section, kRenLights per section,
 * and one kRenScene with the block entities.
 */
public final class SceneDump {
	private SceneDump() {
	}

	public static String run(Minecraft minecraft, Path file, BlockPos center, int radius) throws IOException {
		ClientLevel level = minecraft.level;
		if (level == null) {
			throw new IllegalStateException("no level");
		}
		BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
		RandomSource random = RandomSource.create();
		List<byte[]> messages = new ArrayList<>();
		Map<Long, SectionCapture> sections = new LinkedHashMap<>();
		List<BlockEntity> blockEntities = new ArrayList<>();
		TextureGrabber.Pixels atlas = TextureGrabber.grab(InventoryMenu.BLOCK_ATLAS);

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		PoseStack pose = new PoseStack();
		int blockCount = 0;
		for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
			for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
				for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);
					FluidState fluid = state.getFluidState();
					boolean model = state.getRenderShape() == RenderShape.MODEL;
					boolean light = state.getLightEmission(level, pos) > 0;
					boolean be = state.hasBlockEntity() && level.getBlockEntity(pos) != null;
					// Water is the host's (the sea); only other fluids are captured.
					boolean drawFluid = !fluid.isEmpty() && !fluid.is(FluidTags.WATER);
					if (!model && !light && !be && !drawFluid) {
						continue;
					}
					blockCount++;
					long key = SectionPos.asLong(pos);
					SectionCapture sec = sections.computeIfAbsent(key, k -> new SectionCapture(SectionPos.of(pos)));
					if (model) {
						var bakedModel = blocks.getBlockModel(state);
						ModelData data = level.getModelData(pos);
						for (RenderType layer : bakedModel.getRenderTypes(state, random, data)) {
							CaptureBuffer buf = sec.buffer.material(RenderClassifier.classify(layer).material()).emitter(light);
							pose.pushPose();
							pose.translate(x - sec.originX(), y - sec.originY(), z - sec.originZ());
							random.setSeed(state.getSeed(pos));
							blocks.renderBatched(state, pos, level, pose, buf, true, random, data, layer);
							pose.popPose();
						}
					}
					if (drawFluid) {
						// renderLiquid writes positions relative to the section already.
						sec.buffer.material(RenderClassifier.classify(ItemBlockRenderTypes.getRenderLayer(fluid)).material()).emitter(fluid.createLegacyBlock().getLightEmission() > 0);
						blocks.renderLiquid(pos.immutable(), level, sec.buffer, state, fluid);
					}
					if (light) {
						sec.lights.add(light(level, blocks, atlas, pos.immutable(), state));
					}
					if (be) {
						blockEntities.add(level.getBlockEntity(pos));
					}
				}
			}
		}

		// Block entities through their own renderers, one batch per RenderType.
		Map<ResourceLocation, Integer> textureIds = new LinkedHashMap<>();
		textureIds.put(InventoryMenu.BLOCK_ATLAS, 0);
		Map<RenderType, CaptureBuffer> beBuffers = new LinkedHashMap<>();
		MultiBufferSource source = type -> beBuffers.computeIfAbsent(type,
			t -> new CaptureBuffer().origin(center.getX(), center.getY(), center.getZ()).material(RenderClassifier.classify(t).material()));
		float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
		// Normally done by the world pass, which is skipped while the host draws the world.
		minecraft.getBlockEntityRenderDispatcher().prepare(level, minecraft.gameRenderer.getMainCamera(), minecraft.hitResult);
		for (BlockEntity be : blockEntities) {
			PoseStack bePose = new PoseStack();
			BlockPos p = be.getBlockPos();
			bePose.translate(p.getX(), p.getY(), p.getZ());
			minecraft.getBlockEntityRenderDispatcher().render(be, partial, bePose, source);
		}

		// Messages: textures first, then sections + lights, then the block-entity scene.
		messages.add(atlasMessage(atlas));
		ByteBuffer batches = ByteBuffer.allocate(beBuffers.size() * Proto.REN_BATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		List<ByteBuffer> beVerts = new ArrayList<>();
		int first = 0;
		for (var e : beBuffers.entrySet()) {
			RenderClassifier.Info info = RenderClassifier.classify(e.getKey());
			int tex = 0;
			if (info.texture().isPresent()) {
				ResourceLocation rl = info.texture().get();
				Integer id = textureIds.get(rl);
				if (id == null) {
					id = textureIds.size();
					textureIds.put(rl, id);
					messages.add(textureMessage(id, TextureGrabber.grab(rl)));
				}
				tex = id;
			}
			int count = e.getValue().vertexCount();
			batches.putInt(tex).putInt(first).putInt(count).putInt(info.material());
			beVerts.add(e.getValue().bytes());
			first += count;
		}
		int sectionVerts = 0;
		for (SectionCapture sec : sections.values()) {
			int count = sec.buffer.vertexCount();
			sectionVerts += count;
			ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			hdr.putInt(sec.pos.x()).putInt(sec.pos.y()).putInt(sec.pos.z()).putInt(count);
			messages.add(message(Proto.REN_SECTION, hdr.flip(), sec.buffer.bytes()));
			ByteBuffer lights = ByteBuffer.allocate(16 + sec.lights.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
			lights.putInt(sec.pos.x()).putInt(sec.pos.y()).putInt(sec.pos.z()).putInt(sec.lights.size());
			for (long l : sec.lights) {
				lights.putLong(l);
			}
			messages.add(message(Proto.REN_LIGHTS, lights.flip(), null));
		}
		ByteBuffer scene = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
		scene.putDouble(center.getX()).putDouble(center.getY()).putDouble(center.getZ()).putInt(beBuffers.size()).putInt(first);
		ByteBuffer beBody = ByteBuffer.allocate(batches.position() + first * Proto.REN_VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		beBody.put(batches.flip());
		for (ByteBuffer v : beVerts) {
			beBody.put(v);
		}
		messages.add(message(Proto.REN_SCENE, scene.flip(), beBody.flip()));

		Files.createDirectories(file.toAbsolutePath().getParent());
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			ByteBuffer head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			head.putInt(Proto.DUMP_MAGIC).putInt(Proto.VERSION).putLong(messages.size());
			ch.write(head.flip());
			for (byte[] m : messages) {
				ch.write(ByteBuffer.wrap(m));
			}
		}
		String summary = String.format("dumped %d blocks in %d sections (%d vertices), %d block entities (%d vertices, %d batches), %d textures, %d lights to %s",
			blockCount, sections.size(), sectionVerts, blockEntities.size(), first, beBuffers.size(), textureIds.size(),
			sections.values().stream().mapToInt(s -> s.lights.size()).sum(), file.toAbsolutePath());
		SubCraft.LOG.info("SubCraft: {}", summary);
		return summary;
	}

	private static final class SectionCapture {
		final SectionPos pos;
		final CaptureBuffer buffer;
		final List<Long> lights = new ArrayList<>();

		SectionCapture(SectionPos pos) {
			this.pos = pos;
			this.buffer = new CaptureBuffer();
		}

		int originX() {
			return this.pos.minBlockX();
		}

		int originY() {
			return this.pos.minBlockY();
		}

		int originZ() {
			return this.pos.minBlockZ();
		}
	}

	private static long light(ClientLevel level, BlockRenderDispatcher blocks, TextureGrabber.Pixels atlas, BlockPos pos, BlockState state) {
		return dev.subcraft.capture.SectionCapture.light(level, blocks, atlas, pos, state);
	}

	private static byte[] atlasMessage(TextureGrabber.Pixels p) {
		ByteBuffer hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(p.width()).putInt(p.height());
		return message(Proto.REN_ATLAS, hdr.flip(), p.rgba().duplicate().clear());
	}

	private static byte[] textureMessage(int id, TextureGrabber.Pixels p) {
		ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(p.width()).putInt(p.height()).putInt(0);
		return message(Proto.REN_TEXTURE, hdr.flip(), p.rgba().duplicate().clear());
	}

	/** One render-ring message: {u32 type, u32 payloadBytes}, payload, padded to 8 bytes. */
	static byte[] message(int type, ByteBuffer header, ByteBuffer body) {
		int payload = header.remaining() + (body != null ? body.remaining() : 0);
		int total = (8 + payload + 7) & ~7;
		ByteBuffer m = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
		m.putInt(type).putInt(payload).put(header);
		if (body != null) {
			m.put(body);
		}
		return m.array();
	}
}
