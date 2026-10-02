package dev.subcraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Proto.java against protocol/layout.json (generated from the C++ header by tools/check_layout.sh). */
class ProtoLayoutTest {
	private static JsonObject layout;

	@BeforeAll
	static void load() throws Exception {
		Path root = Path.of(System.getProperty("subcraft.repoRoot", ".."));
		layout = JsonParser.parseString(Files.readString(root.resolve("protocol/layout.json"))).getAsJsonObject();
	}

	private static long constant(String name) {
		return layout.getAsJsonObject("constants").get(name).getAsLong();
	}

	private static long size(String struct) {
		return layout.getAsJsonObject("structs").getAsJsonObject(struct).get("size").getAsLong();
	}

	private static long field(String struct, String field) {
		var fields = layout.getAsJsonObject("structs").getAsJsonObject(struct).getAsJsonObject("fields");
		if (!fields.has(field)) {
			throw new AssertionError(struct + "." + field + " missing from layout.json");
		}
		return fields.get(field).getAsLong();
	}

	private static void fields(String struct, Map<String, Long> expected) {
		expected.forEach((name, value) -> assertEquals((long) value, field(struct, name), struct + "." + name));
	}

	@Test
	void constants() {
		assertEquals(Proto.VERSION, layout.get("version").getAsInt());
		assertEquals(Proto.MAGIC, (int) constant("kMagic"));
		assertEquals(Proto.VERSION, constant("kVersion"));
		assertEquals(Proto.HEARTBEAT_TIMEOUT_NS, constant("kHeartbeatTimeoutNs"));
		assertEquals(Proto.OFF_HEADER, constant("kOffHeader"));
		assertEquals(Proto.OFF_HOST_STATE, constant("kOffHostState"));
		assertEquals(Proto.OFF_MC_STATE, constant("kOffMcState"));
		assertEquals(Proto.OFF_OVERLAY_CTL, constant("kOffOverlayCtl"));
		assertEquals(Proto.OFF_OVERLAY_SLOT_HDR, constant("kOffOverlaySlotHdr"));
		assertEquals(Proto.OFF_INPUT_RING, constant("kOffInputRing"));
		assertEquals(Proto.OFF_CREATURE_TABLE, constant("kOffCreatureTable"));
		assertEquals(Proto.OFF_EVENT_RING, constant("kOffEventRing"));
		assertEquals(Proto.OFF_COLLISION_RING, constant("kOffCollisionRing"));
		assertEquals(Proto.COLLISION_RING_BYTES, constant("kCollisionRingBytes"));
		assertEquals(Proto.OFF_OVERLAY_PIXELS, constant("kOffOverlayPixels"));
		assertEquals(Proto.MAX_OVERLAY_W, constant("kMaxOverlayW"));
		assertEquals(Proto.MAX_OVERLAY_H, constant("kMaxOverlayH"));
		assertEquals(Proto.OVERLAY_SLOT_BYTES, constant("kOverlaySlotBytes"));
		assertEquals(Proto.OVERLAY_SLOTS, constant("kOverlaySlots"));
		assertEquals(Proto.OFF_RENDER_RING, constant("kOffRenderRing"));
		assertEquals(Proto.RENDER_RING_BYTES, constant("kRenderRingBytes"));
		assertEquals(Proto.MAPPING_BYTES, constant("kMappingBytes"));
		assertEquals(Proto.OVERLAY_DIRTY, constant("kOverlayDirty"));
		assertEquals(Proto.INPUT_RING_ENTRIES, constant("kInputRingEntries"));
		assertEquals(Proto.IR_HEAD, constant("kInputRingHeadOff"));
		assertEquals(Proto.IR_TAIL, constant("kInputRingTailOff"));
		assertEquals(Proto.IR_DATA, constant("kInputRingDataOff"));
		assertEquals(Proto.MAX_CREATURES, constant("kMaxCreatures"));
		assertEquals(Proto.EVENT_RING_ENTRIES, constant("kEventRingEntries"));
		assertEquals(Proto.ER_HEAD, constant("kEventRingHeadOff"));
		assertEquals(Proto.ER_TAIL, constant("kEventRingTailOff"));
		assertEquals(Proto.ER_DATA, constant("kEventRingDataOff"));
		assertEquals(Proto.CR_HEAD, constant("kColRingHeadOff"));
		assertEquals(Proto.CR_TAIL, constant("kColRingTailOff"));
		assertEquals(Proto.CR_DATA, constant("kColRingDataOff"));
		assertEquals(Proto.CR_DATA_BYTES, constant("kColRingDataBytes"));
		assertEquals(Proto.RR_HEAD, constant("kRenRingHeadOff"));
		assertEquals(Proto.RR_TAIL, constant("kRenRingTailOff"));
		assertEquals(Proto.RR_DATA, constant("kRenRingDataOff"));
		assertEquals(Proto.RR_DATA_BYTES, constant("kRenRingDataBytes"));
		assertEquals(Proto.DUMP_MAGIC, (int) constant("kDumpMagic"));
	}

	@Test
	void header() {
		assertEquals(Proto.HEADER_BYTES, size("Header"));
		fields("Header", Map.of("magic", Proto.H_MAGIC, "version", Proto.H_VERSION, "hostPid", Proto.H_HOST_PID, "mcPid", Proto.H_MC_PID,
			"hostHeartbeatNs", Proto.H_HOST_HEARTBEAT_NS, "mcHeartbeatNs", Proto.H_MC_HEARTBEAT_NS));
	}

	@Test
	void hostState() {
		assertEquals(Proto.HOST_STATE_BYTES, size("HostState"));
		fields("HostState", Map.ofEntries(
			Map.entry("seq", Proto.HS_SEQ), Map.entry("flags", Proto.HS_FLAGS), Map.entry("worldId", Proto.HS_WORLD_ID),
			Map.entry("collisionEpoch", Proto.HS_COLLISION_EPOCH), Map.entry("posX", Proto.HS_POS_X), Map.entry("posY", Proto.HS_POS_Y),
			Map.entry("posZ", Proto.HS_POS_Z), Map.entry("yaw", Proto.HS_YAW), Map.entry("pitch", Proto.HS_PITCH),
			Map.entry("teleportSeq", Proto.HS_TELEPORT_SEQ), Map.entry("viewportW", Proto.HS_VIEWPORT_W),
			Map.entry("viewportH", Proto.HS_VIEWPORT_H), Map.entry("dayFraction", Proto.HS_DAY_FRACTION),
			Map.entry("oxygen", Proto.HS_OXYGEN), Map.entry("oxygenCapacity", Proto.HS_OXYGEN_CAPACITY)));
	}

	@Test
	void mcState() {
		assertEquals(Proto.MC_STATE_BYTES, size("McState"));
		fields("McState", Map.ofEntries(
			Map.entry("seq", Proto.MS_SEQ), Map.entry("flags", Proto.MS_FLAGS), Map.entry("x", Proto.MS_X), Map.entry("y", Proto.MS_Y),
			Map.entry("z", Proto.MS_Z), Map.entry("yaw", Proto.MS_YAW), Map.entry("pitch", Proto.MS_PITCH),
			Map.entry("eyeHeight", Proto.MS_EYE_HEIGHT), Map.entry("sensitivity", Proto.MS_SENSITIVITY),
			Map.entry("teleportAck", Proto.MS_TELEPORT_ACK), Map.entry("guiScale", Proto.MS_GUI_SCALE),
			Map.entry("frameCounter", Proto.MS_FRAME_COUNTER), Map.entry("fovDeg", Proto.MS_FOV), Map.entry("bobPhase", Proto.MS_BOB_PHASE), Map.entry("handFovDeg", Proto.MS_HAND_FOV),
			Map.entry("bobAmount", Proto.MS_BOB_AMOUNT), Map.entry("eyeX", Proto.MS_EYE_X), Map.entry("eyeY", Proto.MS_EYE_Y),
			Map.entry("eyeZ", Proto.MS_EYE_Z), Map.entry("tickNs", Proto.MS_TICK_NS), Map.entry("prevX", Proto.MS_PREV_X),
			Map.entry("prevY", Proto.MS_PREV_Y), Map.entry("prevZ", Proto.MS_PREV_Z), Map.entry("curX", Proto.MS_CUR_X),
			Map.entry("curY", Proto.MS_CUR_Y), Map.entry("curZ", Proto.MS_CUR_Z), Map.entry("tickEyeO", Proto.MS_TICK_EYE_O),
			Map.entry("tickEye", Proto.MS_TICK_EYE), Map.entry("walkDistO", Proto.MS_WALK_DIST_O), Map.entry("walkDist", Proto.MS_WALK_DIST),
			Map.entry("bobO", Proto.MS_BOB_O), Map.entry("bob", Proto.MS_BOB), Map.entry("tickMs", Proto.MS_TICK_MS),
			Map.entry("cameraMode", Proto.MS_CAMERA_MODE), Map.entry("cameraDistance", Proto.MS_CAMERA_DISTANCE),
			Map.entry("health", Proto.MS_HEALTH), Map.entry("maxHealth", Proto.MS_MAX_HEALTH), Map.entry("food", Proto.MS_FOOD),
			Map.entry("saturation", Proto.MS_SATURATION), Map.entry("air", Proto.MS_AIR), Map.entry("maxAir", Proto.MS_MAX_AIR)));
	}

	@Test
	void overlay() {
		fields("OverlayCtl", Map.of("state", Proto.OC_STATE, "framesPublished", Proto.OC_FRAMES_PUBLISHED));
		assertEquals(Proto.SLOT_HDR_BYTES, size("OverlaySlotHdr"));
		fields("OverlaySlotHdr", Map.of("width", Proto.SH_WIDTH, "height", Proto.SH_HEIGHT, "flags", Proto.SH_FLAGS, "frameId", Proto.SH_FRAME_ID));
	}

	@Test
	void commandBox() {
		assertEquals(Proto.OFF_COMMAND_BOX, constant("kOffCommandBox"));
		assertEquals(Proto.COMMAND_TEXT_BYTES, constant("kCommandTextBytes"));
		assertEquals(Proto.COMMAND_REPLY_BYTES, constant("kCommandReplyBytes"));
		fields("CommandBox", Map.of("seq", Proto.CB_SEQ, "ack", Proto.CB_ACK, "status", Proto.CB_STATUS, "textLen", Proto.CB_TEXT_LEN,
			"text", Proto.CB_TEXT, "reply", Proto.CB_REPLY));
	}

	@Test
	void rings() {
		assertEquals(Proto.INPUT_EVENT_BYTES, size("InputEvent"));
		fields("InputEvent", Map.of("type", 0L, "code", 2L, "a", 4L, "b", 8L, "c", 12L));
		assertEquals(Proto.EVENT_BYTES, size("McEvent"));
		fields("McEvent", Map.of("type", 0L, "id", 4L, "a", 8L, "b", 12L, "c", 16L, "d", 20L, "flags", 24L, "extra", 28L));
		assertEquals(Proto.CREATURE_RECORD_BYTES, size("CreatureRecord"));
		fields("CreatureRecord", Map.of("id", Proto.CREC_ID, "flags", Proto.CREC_FLAGS, "x", Proto.CREC_X, "y", Proto.CREC_Y, "z", Proto.CREC_Z,
			"yaw", Proto.CREC_YAW, "width", Proto.CREC_WIDTH, "height", Proto.CREC_HEIGHT, "healthFrac", Proto.CREC_HEALTH_FRAC, "name", Proto.CREC_NAME));
		fields("CreatureTable", Map.of("seq", Proto.CT_SEQ, "count", Proto.CT_COUNT, "creatures", Proto.CT_RECORDS));
		assertEquals(Proto.COL_REGION_BYTES, size("ColRegion"));
		assertEquals(Proto.COL_BLOCK_BYTES, size("ColBlock"));
		assertEquals(Proto.COL_TRI_BYTES, size("ColTri"));
		assertEquals(Proto.COL_DRY_HEADER_BYTES, size("ColDryHeader"));
		assertEquals(Proto.DRY_BOX_BYTES, size("DryBox"));
		assertEquals(Proto.COL_BIOMES_BYTES, size("ColBiomes"));
		assertEquals(Proto.REN_COLLIDERS_BYTES, size("RenColliders"));
		assertEquals(Proto.REN_BOX_BYTES, size("RenBox"));
		assertEquals(12L, field("ColBiomes", "nameCount"));
		assertEquals(Proto.BIOME_CELLS, constant("kBiomeCells"));
		assertEquals(24L, field("DryBox", "id"));
		assertEquals(36L, field("ColTri", "flags"));
		assertEquals(Proto.TRI_MATERIAL_SHIFT, constant("kTriMaterialShift"));
		fields("ColBlock", Map.of("x", 0L, "y", 4L, "z", 8L, "material", 12L, "flags", 13L, "bits", (long) Proto.COL_BLOCK_BITS));
		assertEquals(Proto.REN_VERTEX_BYTES, size("RenVertex"));
		assertEquals(Proto.REN_BATCH_BYTES, size("RenBatch"));
		assertEquals(16, size("RenSection"));
		assertEquals(32, size("RenScene"));
		assertEquals(8, size("RenLight"));
	}
}
