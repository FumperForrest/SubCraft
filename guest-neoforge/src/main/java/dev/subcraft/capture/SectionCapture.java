package dev.subcraft.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.subcraft.link.Proto;
import dev.subcraft.world.TerrainBlock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * One 16-block section as the host needs it (Phase 2): the geometry Minecraft's own block renderers
 * build (any mod's models, tint and ambient occlusion; non-water fluids), its light-emitting blocks
 * and its blocks' collision boxes. Water is the host's sea; ghost terrain is the host's own world.
 */
public final class SectionCapture {
	/** Vertices (RenVertex bytes), lights (RenLight as longs), boxes (6 floats each, section-local). */
	public record Result(ByteBuffer vertices, int vertexCount, long[] lights, float[] boxes) {
		public boolean isEmpty() {
			return this.vertexCount == 0 && this.lights.length == 0 && this.boxes.length == 0;
		}

		public long hash() {
			long h = this.vertexCount;
			h = h * 31 + this.vertices.duplicate().hashCode();
			h = h * 31 + java.util.Arrays.hashCode(this.lights);
			return h * 31 + java.util.Arrays.hashCode(this.boxes);
		}
	}

	/** Blocks the host has to know about: anything but air, the host's water and the host's own terrain. */
	public static final Predicate<BlockState> RELEVANT = state -> !state.isAir() && !state.is(Blocks.WATER) && !(state.getBlock() instanceof TerrainBlock);

	private SectionCapture() {
	}

	/** Cheap palette check: can this section contain anything for the host? */
	public static boolean mayHaveContent(LevelChunkSection section) {
		return !section.hasOnlyAir() && section.getStates().maybeHas(RELEVANT);
	}

	public static Result capture(ClientLevel level, SectionPos sp, TextureGrabber.Pixels atlas) {
		Minecraft minecraft = Minecraft.getInstance();
		BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
		RandomSource random = RandomSource.create();
		CaptureBuffer buf = new CaptureBuffer();
		List<Long> lights = new ArrayList<>();
		BoxList boxes = new BoxList();
		PoseStack pose = new PoseStack();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int ox = sp.minBlockX(), oy = sp.minBlockY(), oz = sp.minBlockZ();
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					pos.set(ox + x, oy + y, oz + z);
					BlockState state = level.getBlockState(pos);
					if (!RELEVANT.test(state)) {
						continue;
					}
					FluidState fluid = state.getFluidState();
					boolean light = state.getLightEmission(level, pos) > 0;
					if (state.getRenderShape() == RenderShape.MODEL) {
						var model = blocks.getBlockModel(state);
						ModelData data = level.getModelData(pos);
						for (RenderType layer : model.getRenderTypes(state, random, data)) {
							buf.material(RenderClassifier.classify(layer).material()).emitter(light);
							pose.pushPose();
							pose.translate(x, y, z);
							random.setSeed(state.getSeed(pos));
							blocks.renderBatched(state, pos, level, pose, buf, true, random, data, layer);
							pose.popPose();
						}
					}
					if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) {
						// renderLiquid writes positions relative to the section already.
						buf.material(RenderClassifier.classify(ItemBlockRenderTypes.getRenderLayer(fluid)).material()).emitter(fluid.createLegacyBlock().getLightEmission() > 0);
						blocks.renderLiquid(pos.immutable(), level, buf, state, fluid);
					}
					if (light && atlas != null) {
						lights.add(light(level, blocks, atlas, pos.immutable(), state));
					}
					VoxelShape shape = state.getCollisionShape(level, pos);
					if (!shape.isEmpty()) {
						for (AABB box : shape.toAabbs()) {
							boxes.add(x + (float) box.minX, y + (float) box.minY, z + (float) box.minZ, x + (float) box.maxX, y + (float) box.maxY, z + (float) box.maxZ);
						}
					}
				}
			}
		}
		long[] l = new long[lights.size()];
		for (int i = 0; i < l.length; i++) {
			l[i] = lights.get(i);
		}
		return new Result(buf.bytes(), buf.vertexCount(), l, boxes.toArray());
	}

	/** Boxes with runs of identical boxes along x merged (a wall of full blocks is one box per row). */
	static final class BoxList {
		private float[] b = new float[6 * 64];
		private int n;

		void add(float x0, float y0, float z0, float x1, float y1, float z1) {
			if (this.n > 0) {
				int p = (this.n - 1) * 6;
				// Continues the previous box along x with the same y/z extent.
				if (this.b[p + 3] == x0 && this.b[p + 1] == y0 && this.b[p + 2] == z0 && this.b[p + 4] == y1 && this.b[p + 5] == z1) {
					this.b[p + 3] = x1;
					return;
				}
			}
			if ((this.n + 1) * 6 > this.b.length) {
				this.b = java.util.Arrays.copyOf(this.b, this.b.length * 2);
			}
			int p = this.n++ * 6;
			this.b[p] = x0;
			this.b[p + 1] = y0;
			this.b[p + 2] = z0;
			this.b[p + 3] = x1;
			this.b[p + 4] = y1;
			this.b[p + 5] = z1;
		}

		float[] toArray() {
			return java.util.Arrays.copyOf(this.b, this.n * 6);
		}
	}

	/** RenLight: block in section, emission level, colour from the block's own sprite, kind. */
	public static long light(ClientLevel level, BlockRenderDispatcher blocks, TextureGrabber.Pixels atlas, BlockPos pos, BlockState state) {
		int emission = state.getLightEmission(level, pos);
		TextureAtlasSprite sprite = blocks.getBlockModel(state).getParticleIcon(level.getModelData(pos));
		int rgb = brightColour(atlas, sprite);
		int kind = state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES) || state.is(BlockTags.CANDLES) || state.getBlock() instanceof BaseTorchBlock
			? Proto.LIGHT_FLAME
			: state.getFluidState().is(FluidTags.LAVA) || state.getBlock() instanceof MagmaBlock ? Proto.LIGHT_LAVA : Proto.LIGHT_STEADY;
		long b0 = (pos.getX() & 15) | (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 16 | (long) emission << 24;
		long colour = (rgb & 0xFFFFFFL) | (long) kind << 24;
		return b0 | colour << 32;
	}

	/** Average colour of the brightest third of a sprite's opaque pixels: a flame's colour, not its stick. */
	static int brightColour(TextureGrabber.Pixels atlas, TextureAtlasSprite sprite) {
		int x0 = (int) (sprite.getU0() * atlas.width()), x1 = (int) (sprite.getU1() * atlas.width());
		int y0 = (int) (sprite.getV0() * atlas.height()), y1 = (int) (sprite.getV1() * atlas.height());
		List<int[]> px = new ArrayList<>();
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				int argb = atlas.argbAt(x, y);
				if ((argb >>> 24) > 128) {
					px.add(new int[] {argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF});
				}
			}
		}
		if (px.isEmpty()) {
			return 0xFFFFFF;
		}
		px.sort((a, b) -> Integer.compare(b[0] + b[1] + b[2], a[0] + a[1] + a[2]));
		int n = Math.max(1, px.size() / 3);
		long r = 0, g = 0, b = 0;
		for (int i = 0; i < n; i++) {
			r += px.get(i)[0];
			g += px.get(i)[1];
			b += px.get(i)[2];
		}
		return (int) (r / n) | (int) (g / n) << 8 | (int) (b / n) << 16; // RGB8, r in the low byte
	}
}
