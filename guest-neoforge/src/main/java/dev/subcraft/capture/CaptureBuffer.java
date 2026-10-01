package dev.subcraft.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.subcraft.link.Proto;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.core.Direction;

/**
 * A VertexConsumer that records what Minecraft would have drawn, as RenVertex triangles
 * (protocol: 32 bytes, position relative to an origin, uv, RGBA8 colour, light, flags).
 * Minecraft emits quads; every 4 vertices become 2 triangles.
 */
public final class CaptureBuffer implements VertexConsumer {
	private ByteBuffer out = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
	private final float[][] quad = new float[4][5];
	private final int[] quadColor = new int[4];
	private final int[] quadLight = new int[4];
	private final float[][] quadNormal = new float[4][3];
	private int n = -1; // index of the vertex being built in the current quad
	private int vertices;
	private float ox, oy, oz;
	private int material = Proto.REN_MAT_CUTOUT;
	private boolean emitter;

	/** Origin subtracted from every position (section origin or scene origin). */
	public CaptureBuffer origin(float x, float y, float z) {
		this.ox = x;
		this.oy = y;
		this.oz = z;
		return this;
	}

	public CaptureBuffer material(int material) {
		flushPartial();
		this.material = material;
		return this;
	}

	/** The geometry that follows belongs to a block that emits light itself (it glows). */
	public CaptureBuffer emitter(boolean emitter) {
		flushPartial();
		this.emitter = emitter;
		return this;
	}

	public int vertexCount() {
		flushIfComplete();
		return this.vertices;
	}

	/** The recorded RenVertex bytes (flipped, ready to write). */
	public ByteBuffer bytes() {
		flushIfComplete();
		ByteBuffer b = this.out.duplicate();
		b.flip();
		return b;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		flushIfComplete();
		this.n++;
		float[] v = this.quad[this.n];
		v[0] = x - this.ox;
		v[1] = y - this.oy;
		v[2] = z - this.oz;
		v[3] = 0;
		v[4] = 0;
		this.quadColor[this.n] = 0xFFFFFFFF;
		this.quadLight[this.n] = 0;
		this.quadNormal[this.n][0] = 0;
		this.quadNormal[this.n][1] = 0;
		this.quadNormal[this.n][2] = 0;
		return this;
	}

	@Override
	public VertexConsumer setColor(int r, int g, int b, int a) {
		this.quadColor[this.n] = (r & 0xFF) | (g & 0xFF) << 8 | (b & 0xFF) << 16 | (a & 0xFF) << 24;
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		this.quad[this.n][3] = u;
		this.quad[this.n][4] = v;
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		return this; // overlay (hurt flash): not used by the host
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		// Packed lightmap coordinates: block light u >> 4, sky light v >> 4 (0-15 each).
		this.quadLight[this.n] = ((u >> 4) & 0xF) | ((v >> 4) & 0xF) << 8;
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		this.quadNormal[this.n][0] = x;
		this.quadNormal[this.n][1] = y;
		this.quadNormal[this.n][2] = z;
		return this;
	}

	private void flushIfComplete() {
		if (this.n == 3) {
			emitQuad();
			this.n = -1;
		}
	}

	private void flushPartial() {
		flushIfComplete();
		this.n = -1;
	}

	private void emitQuad() {
		float[] nrm = this.quadNormal[0];
		Direction dir = Direction.getNearest(nrm[0], nrm[1], nrm[2]);
		boolean hasNormal = nrm[0] != 0 || nrm[1] != 0 || nrm[2] != 0;
		int flags = (this.material & 0x7) | (this.emitter ? 1 << 3 : 0) | (hasNormal ? (dir.ordinal() + 1) << 4 : 0);
		ensure(6 * Proto.REN_VERTEX_BYTES);
		for (int i : new int[] {0, 1, 2, 0, 2, 3}) {
			float[] v = this.quad[i];
			this.out.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]).putFloat(v[3]).putFloat(v[4]);
			this.out.putInt(this.quadColor[i]).putInt(this.quadLight[i]).putInt(flags);
		}
		this.vertices += 6;
	}

	private void ensure(int more) {
		if (this.out.remaining() < more) {
			ByteBuffer bigger = ByteBuffer.allocate(Math.max(this.out.capacity() * 2, this.out.position() + more)).order(ByteOrder.LITTLE_ENDIAN);
			this.out.flip();
			bigger.put(this.out);
			this.out = bigger;
		}
	}
}
