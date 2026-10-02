package dev.subcraft.link;

/**
 * Mirror of protocol/subcraft_protocol.h. Keep the two in sync; ProtoLayoutTest checks every
 * value here against protocol/layout.json.
 */
public final class Proto {
	private Proto() {
	}

	public static final int MAGIC = 0x43425553; // "SUBC"
	public static final int VERSION = 24;
	public static final String LINK_FILE_NAME = "link.bin";
	public static final long HEARTBEAT_TIMEOUT_NS = 2_000_000_000L;

	// ---- region offsets ----
	public static final long OFF_HEADER = 0x0;
	public static final long OFF_HOST_STATE = 0x100;
	public static final long OFF_MC_STATE = 0x200;
	public static final long OFF_OVERLAY_CTL = 0x300;
	public static final long OFF_OVERLAY_SLOT_HDR = 0x340;
	public static final long OFF_COMMAND_BOX = 0x400;
	public static final long OFF_INPUT_RING = 0x1000;
	public static final long OFF_CREATURE_TABLE = 0x12000;
	public static final long OFF_MOB_TABLE = 0x16100;
	public static final long OFF_EVENT_RING = 0x17000;
	public static final long OFF_COLLISION_RING = 0x20000;
	public static final long COLLISION_RING_BYTES = 32L << 20;
	public static final long OFF_OVERLAY_PIXELS = OFF_COLLISION_RING + COLLISION_RING_BYTES;
	public static final int MAX_OVERLAY_W = 3840;
	public static final int MAX_OVERLAY_H = 2160;
	public static final long OVERLAY_SLOT_BYTES = (long) MAX_OVERLAY_W * MAX_OVERLAY_H * 4;
	public static final int OVERLAY_SLOTS = 3;
	public static final long OFF_RENDER_RING = OFF_OVERLAY_PIXELS + OVERLAY_SLOT_BYTES * OVERLAY_SLOTS;
	public static final long RENDER_RING_BYTES = 64L << 20;
	public static final long MAPPING_BYTES = OFF_RENDER_RING + RENDER_RING_BYTES;

	// ---- Header ----
	public static final long H_MAGIC = 0x00;
	public static final long H_VERSION = 0x04;
	public static final long H_HOST_PID = 0x08;
	public static final long H_MC_PID = 0x0C;
	public static final long H_HOST_HEARTBEAT_NS = 0x10;
	public static final long H_MC_HEARTBEAT_NS = 0x18;
	public static final int HEADER_BYTES = 0x20;

	// ---- HostState (relative to OFF_HOST_STATE) ----
	public static final long HS_SEQ = 0x00;
	public static final long HS_FLAGS = 0x04;
	public static final long HS_WORLD_ID = 0x08;
	public static final long HS_COLLISION_EPOCH = 0x0C;
	public static final long HS_POS_X = 0x10;
	public static final long HS_POS_Y = 0x18;
	public static final long HS_POS_Z = 0x20;
	public static final long HS_YAW = 0x28;
	public static final long HS_PITCH = 0x2C;
	public static final long HS_TELEPORT_SEQ = 0x30;
	public static final long HS_VIEWPORT_W = 0x34;
	public static final long HS_VIEWPORT_H = 0x38;
	public static final long HS_DAY_FRACTION = 0x3C;
	public static final long HS_OXYGEN = 0x40;
	public static final long HS_OXYGEN_CAPACITY = 0x44;
	public static final int HOST_STATE_BYTES = 0x60;

	public static final int HOST_IN_GAME = 1;
	public static final int HOST_MENU_OPEN = 1 << 1;
	public static final int HOST_LOADING = 1 << 2;
	public static final int HOST_UNDERWATER = 1 << 3;
	public static final int HOST_INSIDE = 1 << 4;
	public static final int HOST_UNIFIED_HUD = 1 << 5;

	// ---- McState (relative to OFF_MC_STATE) ----
	public static final long MS_SEQ = 0x00;
	public static final long MS_FLAGS = 0x04;
	public static final long MS_X = 0x08;
	public static final long MS_Y = 0x10;
	public static final long MS_Z = 0x18;
	public static final long MS_YAW = 0x20;
	public static final long MS_PITCH = 0x24;
	public static final long MS_EYE_HEIGHT = 0x28;
	public static final long MS_SENSITIVITY = 0x2C;
	public static final long MS_TELEPORT_ACK = 0x30;
	public static final long MS_GUI_SCALE = 0x34;
	public static final long MS_FRAME_COUNTER = 0x38;
	public static final long MS_FOV = 0x40;
	public static final long MS_BOB_PHASE = 0x44;
	public static final long MS_BOB_AMOUNT = 0x48;
	public static final long MS_HAND_FOV = 0x4C;
	public static final long MS_HURT_TILT = 0xE0;
	public static final long MS_HURT_DIR = 0xE4;
	public static final long MS_DEATH_ROLL = 0xE8;
	public static final long MS_EYE_X = 0x50;
	public static final long MS_EYE_Y = 0x58;
	public static final long MS_EYE_Z = 0x60;
	public static final long MS_TICK_NS = 0x68;
	public static final long MS_PREV_X = 0x70;
	public static final long MS_PREV_Y = 0x78;
	public static final long MS_PREV_Z = 0x80;
	public static final long MS_CUR_X = 0x88;
	public static final long MS_CUR_Y = 0x90;
	public static final long MS_CUR_Z = 0x98;
	public static final long MS_TICK_EYE_O = 0xA0;
	public static final long MS_TICK_EYE = 0xA4;
	public static final long MS_WALK_DIST_O = 0xA8;
	public static final long MS_WALK_DIST = 0xAC;
	public static final long MS_BOB_O = 0xB0;
	public static final long MS_BOB = 0xB4;
	public static final long MS_TICK_MS = 0xB8;
	public static final long MS_CAMERA_MODE = 0xC0;
	public static final long MS_CAMERA_DISTANCE = 0xC4;
	public static final long MS_HEALTH = 0xC8;
	public static final long MS_MAX_HEALTH = 0xCC;
	public static final long MS_FOOD = 0xD0;
	public static final long MS_SATURATION = 0xD4;
	public static final long MS_AIR = 0xD8;
	public static final long MS_MAX_AIR = 0xDC;
	public static final int MC_STATE_BYTES = 0xF0;

	public static final int MC_IN_WORLD = 1;
	public static final int MC_SCREEN_OPEN = 1 << 1;
	public static final int MC_ON_GROUND = 1 << 2;
	public static final int MC_SNEAKING = 1 << 3;
	public static final int MC_SPRINTING = 1 << 4;
	public static final int MC_DEAD = 1 << 5;
	public static final int MC_SWIMMING = 1 << 6;
	public static final int MC_FLYING = 1 << 7;
	public static final int MC_IN_WATER = 1 << 8;
	public static final int MC_EYE_IN_WATER = 1 << 9;
	public static final int MC_LOOK_CAPTURED = 1 << 10;

	// ---- Overlay ----
	public static final long OC_STATE = 0x00;
	public static final long OC_FRAMES_PUBLISHED = 0x08;
	public static final int OVERLAY_DIRTY = 1 << 2;
	public static final long SLOT_HDR_BYTES = 0x40;
	public static final long SH_WIDTH = 0x00;
	public static final long SH_HEIGHT = 0x04;
	public static final long SH_FLAGS = 0x08;
	public static final long SH_FRAME_ID = 0x10;

	// ---- Command box (relative to OFF_COMMAND_BOX, v12) ----
	public static final long CB_SEQ = 0x00, CB_ACK = 0x04, CB_STATUS = 0x08, CB_TEXT_LEN = 0x0C, CB_TEXT = 0x10, CB_REPLY = 0x400;
	public static final int COMMAND_TEXT_BYTES = 1008, COMMAND_REPLY_BYTES = 1024;
	public static final int CMD_OK = 0, CMD_FAILED = 1, CMD_UNKNOWN = 2;

	// ---- Input ring (relative to OFF_INPUT_RING) ----
	public static final int INPUT_RING_ENTRIES = 4096;
	public static final long IR_HEAD = 0x00;
	public static final long IR_TAIL = 0x40;
	public static final long IR_DATA = 0x80;
	public static final int INPUT_EVENT_BYTES = 16;
	public static final int IN_KEY = 1;
	public static final int IN_MOUSE_BUTTON = 2;
	public static final int IN_SCROLL = 3;
	public static final int IN_CURSOR = 4;
	public static final int IN_TEXT = 5;
	public static final int IN_RELEASE_ALL = 6;
	public static final int IN_HURT = 7;
	public static final int IN_OPEN_MENU = 8;
	public static final int IN_LOOK = 9;
	public static final int IN_HURT_MOB = 10;
	public static final int HURT_MELEE = 0;
	public static final int HURT_PROJECTILE = 1;
	public static final int HURT_OTHER = 2;
	public static final int HURT_GRAB = 1;

	// ---- Creature table (relative to OFF_CREATURE_TABLE) ----
	public static final int MAX_CREATURES = 256;
	public static final long CT_SEQ = 0x00;
	public static final long CT_COUNT = 0x04;
	public static final long CT_RECORDS = 0x40;
	public static final int CREATURE_RECORD_BYTES = 64;
	public static final long CREC_ID = 0, CREC_FLAGS = 4, CREC_X = 8, CREC_Y = 12, CREC_Z = 16, CREC_YAW = 20, CREC_WIDTH = 24, CREC_HEIGHT = 28,
		CREC_HEALTH_FRAC = 32, CREC_NAME = 40;
	public static final int CREATURE_NAME_BYTES = 24;
	public static final int CREATURE_HOSTILE = 1;
	// ---- Mob table (relative to OFF_MOB_TABLE, v22) ----
	public static final int MAX_MOBS = 96;
	public static final long MT_SEQ = 0x00;
	public static final long MT_COUNT = 0x04;
	public static final long MT_RECORDS = 0x40;
	public static final int MOB_RECORD_BYTES = 32;
	public static final long MREC_ID = 0, MREC_FLAGS = 4, MREC_X = 8, MREC_Y = 12, MREC_Z = 16, MREC_WIDTH = 20, MREC_HEIGHT = 24, MREC_HEALTH_FRAC = 28;
	public static final int MOB_HOSTILE = 1;
	public static final int CREATURE_DEAD = 1 << 1;
	public static final int CREATURE_INVULNERABLE = 1 << 2;

	// ---- Event ring (relative to OFF_EVENT_RING) ----
	public static final int EVENT_RING_ENTRIES = 512;
	public static final long ER_HEAD = 0x00;
	public static final long ER_TAIL = 0x40;
	public static final long ER_DATA = 0x80;
	public static final int EVENT_BYTES = 32;
	public static final int EV_HIT_CREATURE = 1;
	public static final int EV_PLAYER_DIED = 2;
	public static final int EV_EXPLOSION = 3;
	public static final int EV_DEBUG_RESULT = 4;
	public static final int EV_SOUND_PLAY = 5;
	public static final int EV_SOUND_UPDATE = 6;
	public static final int EV_SOUND_STOP = 7;
	public static final int SOUND_RELATIVE = 1 << 24;
	public static final int SOUND_LOOP = 1 << 25;
	public static final int SOUND_UI = 1 << 26;

	// ---- Collision ring (relative to OFF_COLLISION_RING) ----
	public static final long CR_HEAD = 0x00;
	public static final long CR_TAIL = 0x40;
	public static final long CR_DATA = 0x80;
	public static final long CR_DATA_BYTES = COLLISION_RING_BYTES - CR_DATA;
	public static final int COL_PAD = 0;
	public static final int COL_CLEAR = 1;
	public static final int COL_REGION = 2;
	public static final int COL_TRIS = 3;
	public static final int COL_DRY = 4;
	public static final int COL_BIOMES = 5;
	public static final int COL_BIOMES_BYTES = 16;
	public static final int BIOME_CELLS = 64;
	public static final int BIOME_UNKNOWN = 255;
	public static final int COL_DRY_HEADER_BYTES = 8;
	public static final int DRY_BOX_BYTES = 32;
	public static final int COL_TRI_BYTES = 40;
	public static final int TRI_STRUCTURE = 1, TRI_TERRAIN = 2, TRI_MATERIAL_SHIFT = 8;
	public static final int COL_REGION_BYTES = 32;
	public static final int COL_BLOCK_BYTES = 80;
	public static final int COL_BLOCK_BITS = 16; // offset of bits[] in ColBlock
	public static final int MAT_UNKNOWN = 0, MAT_ROCK = 1, MAT_SAND = 2, MAT_CORAL = 3, MAT_METAL = 4, MAT_GLASS = 5, MAT_ORGANIC = 6,
		MAT_ICE = 7, MAT_PRECURSOR = 8;
	public static final int COL_BLOCK_STRUCTURE = 1;

	// ---- Render ring (relative to OFF_RENDER_RING) ----
	public static final long RR_HEAD = 0x00;
	public static final long RR_TAIL = 0x40;
	public static final long RR_DATA = 0x80;
	public static final long RR_DATA_BYTES = RENDER_RING_BYTES - RR_DATA;
	public static final int DUMP_MAGIC = 0x4D444353; // "SCDM"
	public static final int REN_PAD = 0;
	public static final int REN_ATLAS = 1;
	public static final int REN_SECTION = 2;
	public static final int REN_CLEAR_ALL = 3;
	public static final int REN_TEXTURE = 4;
	public static final int REN_SCENE = 6;
	public static final int REN_ATLAS_REGION = 7;
	public static final int REN_LIGHTS = 8;
	public static final int REN_COLLIDERS = 9;
	public static final int REN_SOUND = 11;
	public static final int REN_SOUND_BYTES = 16;
	public static final int REN_HAND = 10;
	public static final int REN_COLLIDERS_BYTES = 16;
	public static final int REN_BOX_BYTES = 24;
	/** RenBatch.material bit (v17): draw both faces (the RenderType doesn't cull). */
	public static final int REN_DOUBLE_SIDED = 0x100;
	public static final int REN_MAT_OPAQUE = 0, REN_MAT_CUTOUT = 1, REN_MAT_TRANSLUCENT = 2, REN_MAT_EMISSIVE = 3, REN_MAT_ADDITIVE = 4;
	public static final int REN_VERTEX_BYTES = 32;
	public static final int REN_BATCH_BYTES = 16;
	public static final int LIGHT_STEADY = 0, LIGHT_FLAME = 1, LIGHT_LAVA = 2;
}
