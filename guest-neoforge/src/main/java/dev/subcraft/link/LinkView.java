package dev.subcraft.link;

import static dev.subcraft.link.Proto.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Typed access to one mapping of the shared block, with the protocol's memory ordering: every seq,
 * head and tail goes through acquire/release VarHandles; payload fields are plain accesses fenced
 * by those. Holds no connection state, so tests can put one over any buffer.
 */
public final class LinkView {
	private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

	private final ByteBuffer buf;
	// Writer-private overlay slot. The host starts with front = 2 and the middle at 0.
	private int overlayBack = 1;

	public LinkView(ByteBuffer buffer) {
		if (buffer.capacity() < MAPPING_BYTES) {
			throw new IllegalArgumentException("mapping is " + buffer.capacity() + " bytes, need " + MAPPING_BYTES);
		}
		this.buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
	}

	public ByteBuffer buffer() {
		return this.buf;
	}

	// ---- primitive access ----

	public int getByte(long off) {
		return this.buf.get((int) off) & 0xFF;
	}

	public int getInt(long off) {
		return this.buf.getInt((int) off);
	}

	public long getLong(long off) {
		return this.buf.getLong((int) off);
	}

	public float getFloat(long off) {
		return this.buf.getFloat((int) off);
	}

	public double getDouble(long off) {
		return this.buf.getDouble((int) off);
	}

	public void putInt(long off, int v) {
		this.buf.putInt((int) off, v);
	}

	public void putLong(long off, long v) {
		this.buf.putLong((int) off, v);
	}

	public void putFloat(long off, float v) {
		this.buf.putFloat((int) off, v);
	}

	public void putDouble(long off, double v) {
		this.buf.putDouble((int) off, v);
	}

	public int getIntAcquire(long off) {
		return (int) INT.getAcquire(this.buf, (int) off);
	}

	public void setIntRelease(long off, int v) {
		INT.setRelease(this.buf, (int) off, v);
	}

	public long getLongAcquire(long off) {
		return (long) LONG.getAcquire(this.buf, (int) off);
	}

	public void setLongRelease(long off, long v) {
		LONG.setRelease(this.buf, (int) off, v);
	}

	// ---- header ----

	public int magic() {
		return getInt(OFF_HEADER + H_MAGIC);
	}

	public int version() {
		return getInt(OFF_HEADER + H_VERSION);
	}

	public int hostPid() {
		return getIntAcquire(OFF_HEADER + H_HOST_PID);
	}

	public long hostHeartbeat() {
		return getLongAcquire(OFF_HEADER + H_HOST_HEARTBEAT_NS);
	}

	public void setMcPid(int pid) {
		setIntRelease(OFF_HEADER + H_MC_PID, pid);
	}

	public void mcHeartbeat(long nowNs) {
		setLongRelease(OFF_HEADER + H_MC_HEARTBEAT_NS, nowNs);
	}

	// ---- HostState (seqlock read) ----

	/** Plain snapshot of HostState. */
	public static final class HostState {
		public int seq;
		public int flags;
		public int worldId;
		public int collisionEpoch;
		public double x, y, z;
		public float yaw, pitch;
		public int teleportSeq;
		public int viewportW, viewportH;
		public float dayFraction;
		public float oxygen, oxygenCapacity;

		public boolean underwater() {
			return (this.flags & HOST_UNDERWATER) != 0;
		}

		public boolean inside() {
			return (this.flags & HOST_INSIDE) != 0;
		}

		public boolean inGame() {
			return (this.flags & HOST_IN_GAME) != 0;
		}

		public boolean menuOpen() {
			return (this.flags & HOST_MENU_OPEN) != 0;
		}

		public boolean loading() {
			return (this.flags & HOST_LOADING) != 0;
		}
	}

	/** Seqlock read into {@code out}. False on a torn read (out may be partly written; keep the old copy). */
	public boolean readHostState(HostState out) {
		long b = OFF_HOST_STATE;
		for (int attempt = 0; attempt < 1000; attempt++) {
			int seq1 = getIntAcquire(b + HS_SEQ);
			if ((seq1 & 1) != 0) {
				if (attempt > 100) {
					Thread.yield();
				} else {
					Thread.onSpinWait();
				}
				continue;
			}
			int flags = getInt(b + HS_FLAGS);
			int worldId = getInt(b + HS_WORLD_ID);
			int epoch = getInt(b + HS_COLLISION_EPOCH);
			double x = getDouble(b + HS_POS_X);
			double y = getDouble(b + HS_POS_Y);
			double z = getDouble(b + HS_POS_Z);
			float yaw = getFloat(b + HS_YAW);
			float pitch = getFloat(b + HS_PITCH);
			int teleportSeq = getInt(b + HS_TELEPORT_SEQ);
			int vw = getInt(b + HS_VIEWPORT_W);
			int vh = getInt(b + HS_VIEWPORT_H);
			float day = getFloat(b + HS_DAY_FRACTION);
			float oxygen = getFloat(b + HS_OXYGEN);
			float oxygenCapacity = getFloat(b + HS_OXYGEN_CAPACITY);
			VarHandle.loadLoadFence();
			if (getIntAcquire(b + HS_SEQ) == seq1) {
				out.seq = seq1;
				out.flags = flags;
				out.worldId = worldId;
				out.collisionEpoch = epoch;
				out.x = x;
				out.y = y;
				out.z = z;
				out.yaw = yaw;
				out.pitch = pitch;
				out.teleportSeq = teleportSeq;
				out.viewportW = vw;
				out.viewportH = vh;
				out.dayFraction = day;
				out.oxygen = oxygen;
				out.oxygenCapacity = oxygenCapacity;
				return true;
			}
		}
		return false;
	}

	/** Raw HostState sequence number; advances by 2 per host frame. */
	public int hostStateSeq() {
		return getIntAcquire(OFF_HOST_STATE + HS_SEQ);
	}

	// ---- McState (seqlock write) ----

	public static final class McState {
		public int flags;
		public double x, y, z;
		public float yaw, pitch;
		public float eyeHeight;
		public float sensitivity;
		public int teleportAck;
		public int guiScale;
		public long frameCounter;
		public float fov;
		public float bobPhase, bobAmount, handFov, hurtTilt, hurtDir, deathRoll;
		public double eyeX, eyeY, eyeZ;
		public long tickNs;
		public double prevX, prevY, prevZ;
		public double curX, curY, curZ;
		public float tickEyeO, tickEye;
		public float walkDistO, walkDist;
		public float bobO, bob;
		public float tickMs = 50.0F;
		public int cameraMode;
		public float cameraDistance;
		public float health, maxHealth;
		public int food;
		public float saturation;
		public int air, maxAir;
	}

	/** Single writer (the render thread). */
	public void writeMcState(McState st) {
		long b = OFF_MC_STATE;
		int seq = getInt(b + MS_SEQ);
		setIntRelease(b + MS_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		putInt(b + MS_FLAGS, st.flags);
		putDouble(b + MS_X, st.x);
		putDouble(b + MS_Y, st.y);
		putDouble(b + MS_Z, st.z);
		putFloat(b + MS_YAW, st.yaw);
		putFloat(b + MS_PITCH, st.pitch);
		putFloat(b + MS_EYE_HEIGHT, st.eyeHeight);
		putFloat(b + MS_SENSITIVITY, st.sensitivity);
		putInt(b + MS_TELEPORT_ACK, st.teleportAck);
		putInt(b + MS_GUI_SCALE, st.guiScale);
		putLong(b + MS_FRAME_COUNTER, st.frameCounter);
		putFloat(b + MS_FOV, st.fov);
		putFloat(b + MS_BOB_PHASE, st.bobPhase);
		putFloat(b + MS_BOB_AMOUNT, st.bobAmount);
		putFloat(b + MS_HAND_FOV, st.handFov);
		putFloat(b + MS_HURT_TILT, st.hurtTilt);
		putFloat(b + MS_HURT_DIR, st.hurtDir);
		putFloat(b + MS_DEATH_ROLL, st.deathRoll);
		putDouble(b + MS_EYE_X, st.eyeX);
		putDouble(b + MS_EYE_Y, st.eyeY);
		putDouble(b + MS_EYE_Z, st.eyeZ);
		putLong(b + MS_TICK_NS, st.tickNs);
		putDouble(b + MS_PREV_X, st.prevX);
		putDouble(b + MS_PREV_Y, st.prevY);
		putDouble(b + MS_PREV_Z, st.prevZ);
		putDouble(b + MS_CUR_X, st.curX);
		putDouble(b + MS_CUR_Y, st.curY);
		putDouble(b + MS_CUR_Z, st.curZ);
		putFloat(b + MS_TICK_EYE_O, st.tickEyeO);
		putFloat(b + MS_TICK_EYE, st.tickEye);
		putFloat(b + MS_WALK_DIST_O, st.walkDistO);
		putFloat(b + MS_WALK_DIST, st.walkDist);
		putFloat(b + MS_BOB_O, st.bobO);
		putFloat(b + MS_BOB, st.bob);
		putFloat(b + MS_TICK_MS, st.tickMs);
		putInt(b + MS_CAMERA_MODE, st.cameraMode);
		putFloat(b + MS_CAMERA_DISTANCE, st.cameraDistance);
		putFloat(b + MS_HEALTH, st.health);
		putFloat(b + MS_MAX_HEALTH, st.maxHealth);
		putInt(b + MS_FOOD, st.food);
		putFloat(b + MS_SATURATION, st.saturation);
		putInt(b + MS_AIR, st.air);
		putInt(b + MS_MAX_AIR, st.maxAir);
		setIntRelease(b + MS_SEQ, seq + 2);
	}

	// ---- command box (v12) ----

	/** A command the host is waiting on, or null. */
	public record Command(int seq, String text) {
	}

	public Command pendingCommand() {
		long b = OFF_COMMAND_BOX;
		int seq = getIntAcquire(b + CB_SEQ);
		if (seq == getInt(b + CB_ACK)) {
			return null;
		}
		int len = Math.min(Math.max(getInt(b + CB_TEXT_LEN), 0), COMMAND_TEXT_BYTES);
		byte[] text = new byte[len];
		this.buf.get((int) (b + CB_TEXT), text);
		return new Command(seq, new String(text, StandardCharsets.UTF_8));
	}

	/** Answers command {@code seq}: reply (truncated to fit), status, then ack. */
	public void completeCommand(int seq, int status, String reply) {
		long b = OFF_COMMAND_BOX;
		byte[] r = reply.getBytes(StandardCharsets.UTF_8);
		int n = Math.min(r.length, COMMAND_REPLY_BYTES - 1);
		this.buf.put((int) (b + CB_REPLY), r, 0, n);
		this.buf.put((int) (b + CB_REPLY + n), (byte) 0);
		putInt(b + CB_STATUS, status);
		setIntRelease(b + CB_ACK, seq);
	}

	// ---- input ring (consume) ----

	public interface InputSink {
		void accept(int type, int code, int a, int b, int c);
	}

	/** Drains every pending input event. Single consumer. Returns the number delivered. */
	public int drainInput(InputSink sink) {
		long base = OFF_INPUT_RING;
		long head = getLongAcquire(base + IR_HEAD);
		long tail = getLong(base + IR_TAIL);
		if (head - tail > INPUT_RING_ENTRIES) {
			tail = head - INPUT_RING_ENTRIES; // producer lapped us; drop the oldest
		}
		int n = 0;
		while (tail < head) {
			long e = base + IR_DATA + (tail & (INPUT_RING_ENTRIES - 1)) * (long) INPUT_EVENT_BYTES;
			int type = Short.toUnsignedInt(this.buf.getShort((int) e));
			int code = Short.toUnsignedInt(this.buf.getShort((int) e + 2));
			int a = getInt(e + 4);
			int b = getInt(e + 8);
			int c = getInt(e + 12);
			tail++;
			n++;
			sink.accept(type, code, a, b, c);
		}
		setLongRelease(base + IR_TAIL, tail);
		return n;
	}

	// ---- mob table (seqlock write, v22) ----

	public record Mob(int id, int flags, float x, float y, float z, float width, float height, float healthFrac) {
	}

	public void writeMobs(java.util.List<Mob> mobs) {
		long b = OFF_MOB_TABLE;
		int seq = getInt(b + MT_SEQ);
		setIntRelease(b + MT_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		int count = Math.min(mobs.size(), MAX_MOBS);
		for (int i = 0; i < count; i++) {
			Mob m = mobs.get(i);
			long r = b + MT_RECORDS + (long) i * MOB_RECORD_BYTES;
			putInt(r + MREC_ID, m.id());
			putInt(r + MREC_FLAGS, m.flags());
			putFloat(r + MREC_X, m.x());
			putFloat(r + MREC_Y, m.y());
			putFloat(r + MREC_Z, m.z());
			putFloat(r + MREC_WIDTH, m.width());
			putFloat(r + MREC_HEIGHT, m.height());
			putFloat(r + MREC_HEALTH_FRAC, m.healthFrac());
		}
		putInt(b + MT_COUNT, count);
		setIntRelease(b + MT_SEQ, seq + 2);
	}

	// ---- creature table (seqlock read) ----

	public record Creature(int id, int flags, float x, float y, float z, float yaw, float width, float height, float healthFrac, String name) {
	}

	public boolean readCreatures(java.util.List<Creature> out) {
		out.clear();
		long b = OFF_CREATURE_TABLE;
		for (int attempt = 0; attempt < 16; attempt++) {
			int seq1 = getIntAcquire(b + CT_SEQ);
			if ((seq1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int count = Math.min(getInt(b + CT_COUNT), MAX_CREATURES);
			for (int i = 0; i < count; i++) {
				long r = b + CT_RECORDS + (long) i * CREATURE_RECORD_BYTES;
				out.add(new Creature(getInt(r + CREC_ID), getInt(r + CREC_FLAGS), getFloat(r + CREC_X), getFloat(r + CREC_Y), getFloat(r + CREC_Z),
					getFloat(r + CREC_YAW), getFloat(r + CREC_WIDTH), getFloat(r + CREC_HEIGHT), getFloat(r + CREC_HEALTH_FRAC),
					readCString(r + CREC_NAME, CREATURE_NAME_BYTES)));
			}
			VarHandle.loadLoadFence();
			if (getIntAcquire(b + CT_SEQ) == seq1) {
				return true;
			}
			out.clear();
		}
		return false;
	}

	private String readCString(long off, int max) {
		byte[] bytes = new byte[max];
		int n = 0;
		while (n < max) {
			byte v = this.buf.get((int) off + n);
			if (v == 0) {
				break;
			}
			bytes[n++] = v;
		}
		return new String(bytes, 0, n, StandardCharsets.UTF_8);
	}

	// ---- event ring (produce) ----

	/** Queues an event for the host. Returns false (dropped) if the host is a full ring behind. */
	public synchronized boolean pushEvent(int type, int id, float a, float b, float c, float d, int flags, int extra) {
		long base = OFF_EVENT_RING;
		long head = getLong(base + ER_HEAD);
		long tail = getLongAcquire(base + ER_TAIL);
		if (head - tail >= EVENT_RING_ENTRIES) {
			return false;
		}
		long e = base + ER_DATA + (head & (EVENT_RING_ENTRIES - 1)) * (long) EVENT_BYTES;
		putInt(e, type);
		putInt(e + 4, id);
		putFloat(e + 8, a);
		putFloat(e + 12, b);
		putFloat(e + 16, c);
		putFloat(e + 20, d);
		putInt(e + 24, flags);
		putInt(e + 28, extra);
		setLongRelease(base + ER_HEAD, head + 1);
		return true;
	}

	// ---- collision ring (consume) ----

	public interface ColSink {
		/** {@code payloadOff} is the absolute offset of the payload in this view's buffer. */
		void accept(int type, long payloadOff, int payloadBytes);
	}

	/**
	 * Delivers every complete message in the collision ring, then frees the space. A message never
	 * wraps: the producer pads to the ring start instead. Returns the number delivered.
	 *
	 * <p>The producer is another process, so nothing it wrote is trusted: a message whose length
	 * doesn't fit between the tail and the head or the ring end, or a head more than a ring ahead,
	 * drops everything pending ({@link #corruptMessages} counts it). Each message is consumed
	 * before the sink sees it, and a sink that throws only loses that message
	 * ({@link #sinkFaults}, {@link #lastSinkFault}).
	 */
	public int drainCollision(ColSink sink, int maxMessages) {
		return drainBytes(OFF_COLLISION_RING, CR_HEAD, CR_TAIL, CR_DATA, CR_DATA_BYTES, COL_PAD, sink, maxMessages);
	}

	/** Collision messages dropped because their framing was impossible (see {@link #drainCollision}). */
	public long corruptMessages;
	/** Messages whose sink threw. */
	public long sinkFaults;
	public RuntimeException lastSinkFault;

	int drainBytes(long base, long headOff, long tailOff, long dataOff, long dataBytes, int padType, ColSink sink, int maxMessages) {
		long head = getLongAcquire(base + headOff);
		long tail = getLong(base + tailOff);
		int n = 0;
		if (head - tail > dataBytes || head < tail) {
			this.corruptMessages++;
			tail = head;
		}
		while (tail < head && n < maxMessages) {
			long pos = tail % dataBytes;
			long at = base + dataOff + pos;
			int type = getInt(at);
			int payload = getInt(at + 4);
			if (type == padType) {
				tail += dataBytes - pos;
				if (tail > head) {
					this.corruptMessages++;
					tail = head;
				}
				continue;
			}
			long msg = align8(8L + payload);
			if (payload < 0 || pos + msg > dataBytes || tail + msg > head) {
				this.corruptMessages++;
				tail = head;
				break;
			}
			tail += msg;
			n++;
			try {
				sink.accept(type, at + 8, payload);
			} catch (RuntimeException e) {
				this.sinkFaults++;
				this.lastSinkFault = e;
			}
		}
		setLongRelease(base + tailOff, tail);
		return n;
	}

	// ---- render ring (produce) ----

	/** Bytes the render ring can take right now (ignoring wrap padding). */
	public long renderFree() {
		long base = OFF_RENDER_RING;
		return RR_DATA_BYTES - (getLong(base + RR_HEAD) - getLongAcquire(base + RR_TAIL));
	}

	/**
	 * Writes one render message ({@code header} then {@code body}) if it fits now. Single producer.
	 * The caller retries (or drops per-frame data) when this returns false.
	 */
	public synchronized boolean tryWriteRender(int type, ByteBuffer header, ByteBuffer body) {
		int payload = header.remaining() + (body != null ? body.remaining() : 0);
		long msgBytes = align8(8L + payload);
		if (msgBytes > RR_DATA_BYTES / 2) {
			throw new IllegalArgumentException("render message too large: " + msgBytes);
		}
		long base = OFF_RENDER_RING;
		long head = getLong(base + RR_HEAD);
		long tail = getLongAcquire(base + RR_TAIL);
		long pos = head % RR_DATA_BYTES;
		long pad = pos + msgBytes > RR_DATA_BYTES ? RR_DATA_BYTES - pos : 0;
		if (RR_DATA_BYTES - (head - tail) < msgBytes + pad) {
			return false;
		}
		if (pad > 0) {
			putInt(base + RR_DATA + pos, REN_PAD);
			putInt(base + RR_DATA + pos + 4, 0);
			head += pad;
			pos = 0;
		}
		int at = (int) (base + RR_DATA + pos);
		putInt(at, type);
		putInt(at + 4, payload);
		this.buf.put(at + 8, header, header.position(), header.remaining());
		if (body != null && body.remaining() > 0) {
			this.buf.put(at + 8 + header.remaining(), body, body.position(), body.remaining());
		}
		setLongRelease(base + RR_HEAD, head + msgBytes);
		return true;
	}

	// ---- overlay (publish) ----

	public void resetOverlayWriter() {
		this.overlayBack = 1;
	}

	/** Absolute byte offset of the writer's back slot pixels. */
	public long overlayBackSlotOffset() {
		return OFF_OVERLAY_PIXELS + this.overlayBack * OVERLAY_SLOT_BYTES;
	}

	/** Publishes the frame just written into the back slot and takes the old middle as the new back. */
	public void publishOverlay(int width, int height, boolean bottomUp, long frameId) {
		long hdr = OFF_OVERLAY_SLOT_HDR + this.overlayBack * SLOT_HDR_BYTES;
		putInt(hdr + SH_WIDTH, width);
		putInt(hdr + SH_HEIGHT, height);
		putInt(hdr + SH_FLAGS, bottomUp ? 1 : 0);
		putLong(hdr + SH_FRAME_ID, frameId);
		int old = (int) INT.getAndSet(this.buf, (int) (OFF_OVERLAY_CTL + OC_STATE), this.overlayBack | OVERLAY_DIRTY);
		this.overlayBack = old & 3;
		LONG.getAndAdd(this.buf, (int) (OFF_OVERLAY_CTL + OC_FRAMES_PUBLISHED), 1L);
	}

	public static long align8(long n) {
		return (n + 7) & ~7L;
	}
}
