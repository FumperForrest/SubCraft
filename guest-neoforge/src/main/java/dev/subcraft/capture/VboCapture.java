package dev.subcraft.capture;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.subcraft.SubCraft;
import dev.subcraft.mixin.client.TextureManagerAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws that skip MultiBufferSource: mods that upload their own vertex buffers (Immersive
 * Vehicles' models, anything using Tesselator/BufferUploader) and draw them straight to the GPU.
 * Every upload keeps a CPU copy of the mesh (persistent buffers always; Minecraft's immediate-mode
 * buffers only while a capture runs). While DynamicCapture runs, each draw is replayed into the
 * capture buffer of the RenderType that is set up at the time (or, without one, a stand-in for the
 * bound texture), transformed from the draw's model-view into camera-relative world space, and the
 * GPU draw is skipped: Minecraft's window is hidden, the host draws it.
 */
public final class VboCapture {
	private record Mesh(VertexFormat format, VertexFormat.Mode mode, int vertexCount, byte[] bytes) {
	}

	private static final long MAX_STORED_BYTES = 512L << 20;
	private static final Map<VertexBuffer, Mesh> meshes = new WeakHashMap<>();
	private static final Set<String> warned = new HashSet<>();
	private static long storedBytes;
	private static MultiBufferSource target;
	private static Matrix4f baseInverse;
	private static RenderType current;
	private static long replayed, replayedVertices, missing;
	public static int debugDraws;
	public static String debugPass = "world";

	private VboCapture() {
	}

	/** DynamicCapture is about to run renderers into {@code source}. Render thread. */
	private static String pass = "";

	static void begin(MultiBufferSource source, String passName) {
		pass = passName;
		target = source;
		// Renderers combine their PoseStack with the model-view stack; undoing the stack's part
		// leaves the PoseStack (camera-relative, no camera rotation), like captured buffers.
		baseInverse = new Matrix4f(RenderSystem.getModelViewMatrix()).invert();
		current = null;
	}

	static void end() {
		target = null;
		current = null;
	}

	public static boolean active() {
		return target != null;
	}

	/** RenderStateShard.setupRenderState (mixin): remember which RenderType the next raw draw uses. */
	public static void stateSetUp(RenderStateShard shard) {
		if (target != null && shard instanceof RenderType type) {
			current = type;
		}
	}

	public static void stateCleared(RenderStateShard shard) {
		if (target != null && shard == current) {
			current = null;
		}
	}

	/** VertexBuffer.upload (mixin): keep a CPU copy of the mesh. */
	public static void uploaded(VertexBuffer buffer, MeshData mesh) {
		MeshData.DrawState state = mesh.drawState();
		boolean immediate = state.format().getImmediateDrawVertexBuffer() == buffer;
		if (immediate && target == null) {
			return; // GUI and other immediate draws outside the capture: nothing to keep
		}
		ByteBuffer src = mesh.vertexBuffer();
		Mesh old = meshes.remove(buffer);
		if (old != null) {
			storedBytes -= old.bytes().length;
		}
		if (storedBytes + src.remaining() > MAX_STORED_BYTES) {
			warnOnce("memory", "vertex buffer copies over " + (MAX_STORED_BYTES >> 20) + " MB: newer uploads aren't captured");
			return;
		}
		byte[] copy = new byte[src.remaining()];
		src.duplicate().get(copy);
		meshes.put(buffer, new Mesh(state.format(), state.mode(), state.vertexCount(), copy));
		storedBytes += copy.length;
	}

	public static void closed(VertexBuffer buffer) {
		Mesh old = meshes.remove(buffer);
		if (old != null) {
			storedBytes -= old.bytes().length;
		}
	}

	/**
	 * The model-view a plain draw() really uses: the bound shader's ModelViewMat uniform (mods set
	 * it directly, e.g. Immersive Vehicles per part), else RenderSystem's.
	 */
	public static Matrix4f boundModelView() {
		var shader = RenderSystem.getShader();
		if (target != null && shader != null && shader.MODEL_VIEW_MATRIX != null) {
			var values = shader.MODEL_VIEW_MATRIX.getFloatBuffer();
			if (values != null && values.capacity() >= 16) {
				return new Matrix4f().set(values.duplicate().position(0));
			}
		}
		return RenderSystem.getModelViewMatrix();
	}

	/** VertexBuffer.draw/drawWithShader (mixin). True: captured, skip the GPU draw. */
	public static boolean drawn(VertexBuffer buffer, Matrix4f modelView) {
		if (target == null) {
			return false;
		}
		Mesh mesh = meshes.get(buffer);
		if (mesh == null) {
			missing++;
			return false;
		}
		boolean triangles = mesh.mode() == VertexFormat.Mode.TRIANGLES;
		if (!triangles && mesh.mode() != VertexFormat.Mode.QUADS) {
			return true; // lines, strips, fans: not geometry the host draws
		}
		RenderType type = current != null ? current : standIn();
		if (type == null) {
			missing++;
			return true;
		}
		if (!(target.getBuffer(type) instanceof CaptureBuffer out)) {
			return true;
		}
		out.triangles(triangles);
		Matrix4f world = new Matrix4f(baseInverse).mul(modelView);
		if (debugDraws > 0 && pass.equals(debugPass)) {
			debugDraws--;
			SubCraft.LOG.info("SubCraft raw draw debug ({}): type {} vertices {} | modelView {} | base^-1 {}", pass, type, mesh.vertexCount(),
				modelView.toString().replace('\n', ' '), baseInverse.toString().replace('\n', ' '));
		}
		replay(mesh, world, out);
		out.triangles(false);
		replayed++;
		replayedVertices += mesh.vertexCount();
		return true;
	}

	private static void replay(Mesh mesh, Matrix4f world, CaptureBuffer out) {
		VertexFormat f = mesh.format();
		if (!f.contains(VertexFormatElement.POSITION)) {
			return;
		}
		ByteBuffer b = ByteBuffer.wrap(mesh.bytes()).order(ByteOrder.nativeOrder());
		int stride = f.getVertexSize();
		int pos = f.getOffset(VertexFormatElement.POSITION);
		int uv = f.contains(VertexFormatElement.UV0) ? f.getOffset(VertexFormatElement.UV0) : -1;
		int col = f.contains(VertexFormatElement.COLOR) ? f.getOffset(VertexFormatElement.COLOR) : -1;
		int nrm = f.contains(VertexFormatElement.NORMAL) ? f.getOffset(VertexFormatElement.NORMAL) : -1;
		Matrix3f normals = new Matrix3f(world).invert().transpose();
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		int count = Math.min(mesh.vertexCount(), mesh.bytes().length / stride);
		for (int i = 0; i < count; i++) {
			int at = i * stride;
			world.transformPosition(b.getFloat(at + pos), b.getFloat(at + pos + 4), b.getFloat(at + pos + 8), p);
			out.addVertex(p.x, p.y, p.z);
			if (uv >= 0) {
				out.setUv(b.getFloat(at + uv), b.getFloat(at + uv + 4));
			}
			if (col >= 0) {
				out.setColor(b.get(at + col) & 0xFF, b.get(at + col + 1) & 0xFF, b.get(at + col + 2) & 0xFF, b.get(at + col + 3) & 0xFF);
			}
			if (nrm >= 0) {
				normals.transform(b.get(at + nrm) / 127f, b.get(at + nrm + 1) / 127f, b.get(at + nrm + 2) / 127f, n);
				out.setNormal(n.x, n.y, n.z);
			}
		}
	}

	/** No RenderType set up: an entity-like stand-in for the bound texture, so it is classified and sent. */
	private static RenderType standIn() {
		int gl = RenderSystem.getShaderTexture(0);
		ResourceLocation rl = textureFor(gl);
		return rl != null ? RenderType.entityCutoutNoCull(rl) : null;
	}

	private static final Map<Integer, ResourceLocation> byGlId = new java.util.HashMap<>();

	private static ResourceLocation textureFor(int gl) {
		if (gl <= 0) {
			return null;
		}
		ResourceLocation rl = byGlId.get(gl);
		if (rl != null) {
			return rl;
		}
		for (Map.Entry<ResourceLocation, AbstractTexture> e : ((TextureManagerAccessor) Minecraft.getInstance().getTextureManager()).subcraft$byPath().entrySet()) {
			if (e.getValue().getId() == gl) {
				byGlId.put(gl, e.getKey());
				return e.getKey();
			}
		}
		warnOnce("texture " + gl, "a raw draw's texture (GL " + gl + ") isn't a registered texture: skipped");
		return null;
	}

	private static void warnOnce(String key, String message) {
		if (warned.add(key)) {
			SubCraft.LOG.warn("SubCraft: {}", message);
		}
	}

	public static String stats() {
		return String.format("raw draws: %d replayed (%d vertices), %d without a mesh, %d meshes kept (%d MB)", replayed, replayedVertices, missing,
			meshes.size(), storedBytes >> 20);
	}
}
