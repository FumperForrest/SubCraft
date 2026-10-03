package dev.subcraft.link;

import static dev.subcraft.link.Proto.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Minecraft end of every ring and seqlock, with this test playing the host. */
class LinkViewTest {
	private ByteBuffer mem;
	private LinkView view;

	@BeforeEach
	void setUp() {
		this.mem = ByteBuffer.allocateDirect((int) MAPPING_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		this.view = new LinkView(this.mem);
	}

	@Test
	void inputRingWrapsAndDropsWhenLapped() {
		List<int[]> got = new ArrayList<>();
		long head = 0;
		// Three laps' worth in batches: the consumer keeps up, so nothing is lost across the wrap.
		for (int batch = 0; batch < 3 * INPUT_RING_ENTRIES / 1000; batch++) {
			for (int i = 0; i < 1000; i++, head++) {
				int e = (int) (OFF_INPUT_RING + IR_DATA + (head & (INPUT_RING_ENTRIES - 1)) * INPUT_EVENT_BYTES);
				this.mem.putShort(e, (short) IN_KEY);
				this.mem.putShort(e + 2, (short) 65);
				this.mem.putInt(e + 4, (int) head);
			}
			this.view.setLongRelease(OFF_INPUT_RING + IR_HEAD, head);
			this.view.drainInput((type, code, a, b, c) -> got.add(new int[] {type, code, a}));
		}
		assertEquals(head, got.size());
		for (int i = 0; i < got.size(); i++) {
			assertEquals(i, got.get(i)[2]);
		}
		// Lapped: the host wrote more than a ring's worth while we weren't reading; keep the newest.
		got.clear();
		long start = head;
		head += INPUT_RING_ENTRIES + 10;
		for (long k = start; k < head; k++) {
			int e = (int) (OFF_INPUT_RING + IR_DATA + (k & (INPUT_RING_ENTRIES - 1)) * INPUT_EVENT_BYTES);
			this.mem.putShort(e, (short) IN_KEY);
			this.mem.putInt(e + 4, (int) k);
		}
		this.view.setLongRelease(OFF_INPUT_RING + IR_HEAD, head);
		this.view.drainInput((type, code, a, b, c) -> got.add(new int[] {type, code, a}));
		assertEquals(INPUT_RING_ENTRIES, got.size());
		assertEquals((int) (head - INPUT_RING_ENTRIES), got.get(0)[2]);
	}

	/** Host-side collision ring writer, as fake_host.py and the C# plugin do it. */
	private long colHead;

	private void writeCol(int type, byte[] payload) {
		long msg = LinkView.align8(8L + payload.length);
		long pos = this.colHead % CR_DATA_BYTES;
		if (pos + msg > CR_DATA_BYTES) {
			this.mem.putInt((int) (OFF_COLLISION_RING + CR_DATA + pos), COL_PAD);
			this.colHead += CR_DATA_BYTES - pos;
			pos = 0;
		}
		int at = (int) (OFF_COLLISION_RING + CR_DATA + pos);
		this.mem.putInt(at, type);
		this.mem.putInt(at + 4, payload.length);
		this.mem.put(at + 8, payload);
		this.colHead += msg;
		this.view.setLongRelease(OFF_COLLISION_RING + CR_HEAD, this.colHead);
	}

	@Test
	void collisionRingPadsAtTheEnd() {
		byte[] big = new byte[(int) (CR_DATA_BYTES / 3) - 13]; // odd size: exercises align8
		for (int round = 0; round < 7; round++) {
			big[0] = (byte) round;
			writeCol(COL_REGION, big);
			List<Integer> rounds = new ArrayList<>();
			int n = this.view.drainCollision((type, off, bytes) -> {
				assertEquals(COL_REGION, type);
				assertEquals(big.length, bytes);
				rounds.add((int) this.mem.get((int) off));
			}, 100);
			assertEquals(1, n);
			assertEquals(List.of(round), rounds);
		}
		assertEquals(this.colHead, this.view.getLong(OFF_COLLISION_RING + CR_TAIL));
	}

	/** Writes a raw message header at the current head without any checks (a buggy or drifted host). */
	private void writeRawCol(int type, int payloadBytes, long advance) {
		int at = (int) (OFF_COLLISION_RING + CR_DATA + this.colHead % CR_DATA_BYTES);
		this.mem.putInt(at, type);
		this.mem.putInt(at + 4, payloadBytes);
		this.colHead += advance;
		this.view.setLongRelease(OFF_COLLISION_RING + CR_HEAD, this.colHead);
	}

	@Test
	void collisionDrainSurvivesANegativeLength() {
		writeCol(COL_CLEAR, new byte[4]);
		writeRawCol(COL_REGION, -8, 8); // align8(8 - 8) = 0: used to be delivered forever
		List<Integer> types = new ArrayList<>();
		int n = this.view.drainCollision((type, off, bytes) -> types.add(type), 100);
		assertEquals(1, n);
		assertEquals(List.of(COL_CLEAR), types);
		assertEquals(1, this.view.corruptMessages);
		assertEquals(this.colHead, this.view.getLong(OFF_COLLISION_RING + CR_TAIL), "dropped up to the head");
		// The ring keeps working afterwards.
		writeCol(COL_DRY, new byte[8]);
		types.clear();
		assertEquals(1, this.view.drainCollision((type, off, bytes) -> types.add(type), 100));
		assertEquals(List.of(COL_DRY), types);
	}

	@Test
	void collisionDrainRefusesLengthsPastTheHeadOrTheRingEnd() {
		writeRawCol(COL_TRIS, 1 << 20, 64); // claims 1 MiB, head says 64 bytes
		assertEquals(0, this.view.drainCollision((type, off, bytes) -> fail("delivered"), 100));
		assertEquals(1, this.view.corruptMessages);
		writeRawCol(COL_TRIS, (int) CR_DATA_BYTES, CR_DATA_BYTES / 2); // longer than the ring
		assertEquals(0, this.view.drainCollision((type, off, bytes) -> fail("delivered"), 100));
		assertEquals(2, this.view.corruptMessages);
		// A head more than a ring ahead of the tail (e.g. a stale tail after a host restart).
		this.colHead += 2 * CR_DATA_BYTES;
		this.view.setLongRelease(OFF_COLLISION_RING + CR_HEAD, this.colHead);
		assertEquals(0, this.view.drainCollision((type, off, bytes) -> fail("delivered"), 100));
		assertEquals(3, this.view.corruptMessages);
		assertEquals(this.colHead, this.view.getLong(OFF_COLLISION_RING + CR_TAIL));
	}

	@Test
	void collisionSinkThatThrowsLosesOnlyItsMessage() {
		writeCol(COL_TRIS, new byte[40]);
		writeCol(COL_DRY, new byte[8]);
		List<Integer> types = new ArrayList<>();
		int n = this.view.drainCollision((type, off, bytes) -> {
			if (type == COL_TRIS) {
				throw new IllegalStateException("handler bug");
			}
			types.add(type);
		}, 100);
		assertEquals(2, n);
		assertEquals(List.of(COL_DRY), types);
		assertEquals(1, this.view.sinkFaults);
		assertEquals("handler bug", this.view.lastSinkFault.getMessage());
		assertEquals(this.colHead, this.view.getLong(OFF_COLLISION_RING + CR_TAIL));
	}

	@Test
	void renderRingRefusesWhenFullAndWraps() {
		ByteBuffer header = ByteBuffer.allocate(16);
		ByteBuffer body = ByteBuffer.allocate((int) (RR_DATA_BYTES / 4));
		int written = 0;
		while (this.view.tryWriteRender(REN_SECTION, header.duplicate(), body.duplicate())) {
			written++;
		}
		assertEquals(3, written); // a fourth doesn't fit beside the 8-byte headers
		// Host consumes two; two more fit (one of them after a pad to the ring start).
		long msg = LinkView.align8(8L + 16 + body.capacity());
		this.view.setLongRelease(OFF_RENDER_RING + RR_TAIL, 2 * msg);
		assertTrue(this.view.tryWriteRender(REN_SECTION, header.duplicate(), body.duplicate()));
		assertTrue(this.view.tryWriteRender(REN_SECTION, header.duplicate(), body.duplicate()));
		assertFalse(this.view.tryWriteRender(REN_SECTION, header.duplicate(), body.duplicate()));
	}

	@Test
	void seqlockNeverTearsUnderAConcurrentWriter() throws Exception {
		AtomicBoolean stop = new AtomicBoolean();
		Thread host = new Thread(() -> {
			int seq = 0;
			for (int v = 1; !stop.get(); v++) {
				this.view.setIntRelease(OFF_HOST_STATE + HS_SEQ, ++seq);
				java.lang.invoke.VarHandle.storeStoreFence();
				// Every field carries the same value: a mix of two writes is a torn read.
				this.view.putDouble(OFF_HOST_STATE + HS_POS_X, v);
				this.view.putDouble(OFF_HOST_STATE + HS_POS_Y, v);
				this.view.putDouble(OFF_HOST_STATE + HS_POS_Z, v);
				this.view.putInt(OFF_HOST_STATE + HS_TELEPORT_SEQ, v);
				this.view.setIntRelease(OFF_HOST_STATE + HS_SEQ, ++seq);
				for (int k = 0; k < 200; k++) {
					Thread.onSpinWait(); // a real writer publishes once per frame, not continuously
				}
			}
		});
		host.start();
		LinkView.HostState s = new LinkView.HostState();
		AtomicInteger good = new AtomicInteger();
		long until = System.nanoTime() + 300_000_000L;
		while (System.nanoTime() < until) {
			if (this.view.readHostState(s)) {
				assertEquals(s.x, s.y);
				assertEquals(s.y, s.z);
				assertEquals((int) s.z, s.teleportSeq);
				good.incrementAndGet();
			}
		}
		stop.set(true);
		host.join();
		assertTrue(good.get() > 200, "too few consistent reads: " + good.get()); // the asserts above are the point: no torn read
	}

	/** The host's side of the overlay exchange, as LinkView.cs TryTakeOverlay does it (v26). */
	private static final class OverlayReader {
		final LinkView v;
		int front;

		OverlayReader(LinkView v, int front) {
			this.v = v;
			this.front = front;
		}

		/** The slot taken, or -1 when nothing new was published. */
		int take() {
			while (true) {
				int old = this.v.getIntAcquire(OFF_OVERLAY_CTL + OC_STATE);
				if ((old & OVERLAY_DIRTY) == 0) {
					return -1;
				}
				int taken = old & 3;
				if (this.v.compareAndSetInt(OFF_OVERLAY_CTL + OC_STATE, old, this.front | (taken << OVERLAY_FRONT_SHIFT))) {
					this.front = taken;
					return taken;
				}
			}
		}
	}

	private static int middle(LinkView v) {
		return v.getIntAcquire(OFF_OVERLAY_CTL + OC_STATE) & 3;
	}

	private static void hostInit(LinkView v) {
		v.putInt(OFF_OVERLAY_CTL + OC_STATE, 2 << OVERLAY_FRONT_SHIFT); // as InitAsHost: middle 0, front 2
	}

	@Test
	void overlayTripleBufferHandsOverEveryPublishedSlot() {
		hostInit(this.view);
		assertTrue(this.view.overlayReady());
		assertEquals(1, this.view.overlayBack());
		OverlayReader host = new OverlayReader(this.view, 2);
		for (int frame = 1; frame <= 10; frame++) {
			assertNotEquals(host.front, this.view.overlayBack(), "writer must never own the reader's slot");
			this.view.publishOverlay(4, 4, true, frame);
			assertTrue((this.view.getInt(OFF_OVERLAY_CTL + OC_STATE) & OVERLAY_DIRTY) != 0);
			if (frame % 3 != 0) { // the reader skips some frames
				int slot = host.take();
				assertEquals(frame, this.view.getLong(OFF_OVERLAY_SLOT_HDR + slot * SLOT_HDR_BYTES + SH_FRAME_ID));
			}
		}
		assertEquals(10, this.view.getLong(OFF_OVERLAY_CTL + OC_FRAMES_PUBLISHED));
	}

	@Test
	void writerPublishesNothingUntilTheMappingDescribesThreeSlots() {
		// A zeroed mapping (no host yet): middle 0 = front 0, no slot can be trusted.
		assertFalse(this.view.overlayReady());
		assertThrows(IllegalStateException.class, () -> this.view.publishOverlay(4, 4, true, 1));
		hostInit(this.view);
		assertTrue(this.view.overlayReady());
	}

	/**
	 * A Minecraft connecting to a host that already ran with another Minecraft: whatever slots the
	 * previous writer left behind, the new writer's back slot is free and stays free. Before v26 the
	 * writer assumed slot 1, which aliased the host's front or the middle in 8 of these 12 states.
	 */
	@Test
	void reconnectingWriterFindsTheFreeSlotWhateverTheLeftoverState() {
		for (int front = 0; front < 3; front++) {
			for (int mid = 0; mid < 3; mid++) {
				if (mid == front) {
					continue;
				}
				for (int dirty = 0; dirty <= 1; dirty++) {
					String at = "front " + front + " middle " + mid + " dirty " + dirty;
					this.view.putInt(OFF_OVERLAY_CTL + OC_STATE, mid | (dirty != 0 ? OVERLAY_DIRTY : 0) | front << OVERLAY_FRONT_SHIFT);
					LinkView writer = new LinkView(this.mem); // a fresh Minecraft
					assertTrue(writer.resetOverlayWriter(), at);
					assertEquals(3 - front - mid, writer.overlayBack(), at);
					OverlayReader host = new OverlayReader(writer, front);
					for (int frame = 1; frame <= 30; frame++) {
						writer.publishOverlay(4, 4, true, frame);
						if (frame % 4 != 0) {
							host.take();
						}
						assertEquals(3, host.front + middle(writer) + writer.overlayBack(), at + " frame " + frame);
						assertNotEquals(host.front, writer.overlayBack(), at + " frame " + frame);
						assertNotEquals(middle(writer), writer.overlayBack(), at + " frame " + frame);
					}
				}
			}
		}
	}

	/**
	 * Minecraft reconnects over and over while the host takes frames on another thread. The writer
	 * "renders" (stamps its back slot) before every publish; the host checks that the slot it holds
	 * never changes under it. Any aliasing between the writer's back slot and the host's front would
	 * show up as a changed stamp.
	 */
	@Test
	void reconnectWhileTheHostIsTakingNeverAliases() throws Exception {
		hostInit(this.view);
		OverlayReader host = new OverlayReader(this.view, 2);
		AtomicBoolean stop = new AtomicBoolean();
		AtomicInteger torn = new AtomicInteger();
		AtomicInteger taken = new AtomicInteger();
		Thread reader = new Thread(() -> {
			while (!stop.get()) {
				int slot = host.take();
				if (slot < 0) {
					continue;
				}
				taken.incrementAndGet();
				long id = this.view.getLong(OFF_OVERLAY_SLOT_HDR + slot * SLOT_HDR_BYTES + SH_FRAME_ID);
				for (int k = 0; k < 50; k++) { // "uploading" the frame
					if (id <= 0 || this.view.getLong(OFF_OVERLAY_SLOT_HDR + slot * SLOT_HDR_BYTES + SH_FRAME_ID) != id) {
						torn.incrementAndGet();
						break;
					}
				}
			}
		});
		reader.start();
		long frame = 1;
		try {
			for (int i = 0; i < 30_000; i++) {
				LinkView writer = new LinkView(this.mem); // a reconnect
				assertTrue(writer.resetOverlayWriter());
				for (int f = 0; f < 1 + i % 3; f++, frame++) {
					long hdr = OFF_OVERLAY_SLOT_HDR + writer.overlayBack() * SLOT_HDR_BYTES + SH_FRAME_ID;
					writer.putLong(hdr, -frame); // rendering into the back slot
					writer.publishOverlay(4, 4, true, frame);
				}
			}
		} finally {
			stop.set(true);
			reader.join();
		}
		assertTrue(taken.get() > 0, "the host took frames");
		assertEquals(0, torn.get(), "Minecraft wrote into the slot the host was reading");
	}
}
