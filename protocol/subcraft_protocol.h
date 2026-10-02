// SubCraft shared-memory protocol (Subnautica BepInEx plugin <-> Minecraft NeoForge mod).
//
// This header is the single source of truth for the byte layout. It is mirrored in
//   guest-neoforge/src/main/java/dev/subcraft/link/Proto.java
//   host-subnautica/src/Link/Proto.cs
// and all three are layout-tested against protocol/layout.json. Change anything here, change it
// everywhere, and bump kVersion.
//
// Derived from SkyCraft's skycraft_protocol.h v11 (MIT, (c) 2026 chasmlol). Differences from it:
//   - the mapping is a file (not a Windows named section), so it works on macOS and Windows;
//   - every timestamp is monotonic nanoseconds (macOS: mach_absolute_time in ns, i.e.
//     CLOCK_UPTIME_RAW; Windows: QueryPerformanceCounter scaled to ns);
//   - key and mouse-button codes are GLFW codes (Minecraft 1.21.1 uses GLFW);
//   - Skyrim-only blocks (water grid, world entities, dig, ragdoll, skills) are gone.
//
// All multi-byte values are little-endian. The host (Subnautica) creates and sizes the file;
// Minecraft opens it. Coordinates are Minecraft space (blocks, Y up, Z south) unless noted;
// Unity space converts as mc = (u.x, u.y, -u.z), 1 block = 1 Unity metre.
//
// Memory ordering: every seq / head / tail field is written with release and read with acquire
// semantics (the two ends are an arm64 JVM and an x86_64 Mono under Rosetta).
#pragma once

#include <cstdint>

namespace subcraft::proto
{
	inline constexpr std::uint32_t kMagic = 0x43425553;  // "SUBC"
	inline constexpr std::uint32_t kVersion = 16;

	// Default file locations: macOS $TMPDIR/subcraft/link.bin, Windows %LOCALAPPDATA%\SubCraft\link.bin.
	// Both sides accept an override (Java -Dsubcraft.link=<path>, host config, env SUBCRAFT_LINK).
	inline constexpr char kLinkFileName[] = "link.bin";

	// Either side treats the other as gone when its heartbeat is older than this.
	inline constexpr std::uint64_t kHeartbeatTimeoutNs = 2'000'000'000ull;

	// ---- region offsets ---------------------------------------------------------------------
	inline constexpr std::uint64_t kOffHeader = 0x0;
	inline constexpr std::uint64_t kOffHostState = 0x100;
	inline constexpr std::uint64_t kOffMcState = 0x200;
	inline constexpr std::uint64_t kOffOverlayCtl = 0x300;
	inline constexpr std::uint64_t kOffOverlaySlotHdr = 0x340;  // 3 x 0x40
	inline constexpr std::uint64_t kOffCommandBox = 0x400;      // host -> MC debug commands (v12), see CommandBox
	inline constexpr std::uint64_t kOffInputRing = 0x1000;
	inline constexpr std::uint64_t kOffCreatureTable = 0x12000;  // host -> MC, see CreatureTable
	inline constexpr std::uint64_t kOffEventRing = 0x17000;      // MC -> host, see McEvent
	inline constexpr std::uint64_t kOffCollisionRing = 0x20000;
	inline constexpr std::uint64_t kCollisionRingBytes = 32ull << 20;
	inline constexpr std::uint64_t kOffOverlayPixels = kOffCollisionRing + kCollisionRingBytes;
	inline constexpr std::uint32_t kMaxOverlayW = 3840;
	inline constexpr std::uint32_t kMaxOverlayH = 2160;
	inline constexpr std::uint64_t kOverlaySlotBytes = std::uint64_t(kMaxOverlayW) * kMaxOverlayH * 4;
	inline constexpr std::uint32_t kOverlaySlots = 3;
	inline constexpr std::uint64_t kOffRenderRing = kOffOverlayPixels + kOverlaySlotBytes * kOverlaySlots;
	inline constexpr std::uint64_t kRenderRingBytes = 64ull << 20;
	inline constexpr std::uint64_t kMappingBytes = kOffRenderRing + kRenderRingBytes;

	// ---- header @0x0 ------------------------------------------------------------------------
	struct Header
	{
		std::uint32_t magic;
		std::uint32_t version;
		std::uint32_t hostPid;
		std::uint32_t mcPid;
		std::uint64_t hostHeartbeatNs;  // monotonic ns at the host's last frame
		std::uint64_t mcHeartbeatNs;    // monotonic ns at Minecraft's last frame
	};
	static_assert(sizeof(Header) == 0x20);

	// ---- host -> MC state @0x100 (seqlock: seq odd while writing) ---------------------------
	enum HostFlags : std::uint32_t
	{
		kHostInGame = 1u << 0,    // a save is loaded and the player exists
		kHostMenuOpen = 1u << 1,  // a host menu (PDA, pause, fabricator) owns input; MC drops held keys
		kHostLoading = 1u << 2,   // loading screen in progress
		kHostUnderwater = 1u << 3,  // the host considers the player underwater (Player.IsUnderwater, v14)
		kHostInside = 1u << 4,      // the player is inside a dry interior (lifepod, base, sub) (v14)
	};

	struct HostState
	{
		std::uint32_t seq;
		std::uint32_t flags;           // HostFlags
		std::uint32_t worldId;         // which host world/save; MC drops cached data when it changes
		std::uint32_t collisionEpoch;  // bumps when MC must drop all collision data
		double        posX, posY, posZ;  // host player feet, MC coords
		float         yaw, pitch;        // authoritative look (MC degrees)
		std::uint32_t teleportSeq;       // MC teleports its player to pos when this changes
		std::uint32_t viewportW, viewportH;
		float         dayFraction;       // host time of day, 0..1 (0 = midnight)
		// v14: breath. The host owns oxygen (MISSION.md section 2); Minecraft mirrors it into its air bar.
		float         oxygen;            // seconds of oxygen left
		float         oxygenCapacity;    // seconds when full (tanks included)
		std::uint8_t  reserved[0x18];
	};
	static_assert(sizeof(HostState) == 0x60);

	// ---- MC -> host state @0x200 (seqlock) --------------------------------------------------
	enum McFlags : std::uint32_t
	{
		kMcInWorld = 1u << 0,
		kMcScreenOpen = 1u << 1,  // an MC GUI screen (inventory, chat, ...) is open
		kMcOnGround = 1u << 2,
		kMcSneaking = 1u << 3,
		kMcSprinting = 1u << 4,
		kMcDead = 1u << 5,
		kMcSwimming = 1u << 6,
		kMcFlying = 1u << 7,
		kMcInWater = 1u << 8,
		kMcEyeInWater = 1u << 9,
	};

	struct McState
	{
		std::uint32_t seq;
		std::uint32_t flags;          // McFlags
		double        x, y, z;        // interpolated feet position (MC coords)
		float         yaw, pitch;     // MC rotation (degrees)
		float         eyeHeight;      // blocks above feet
		float         sensitivity;    // MC mouse sensitivity option (0..1)
		std::uint32_t teleportAck;    // last HostState::teleportSeq applied
		std::uint32_t guiScale;
		std::uint64_t frameCounter;
		float         fovDeg;         // effective vertical FOV (includes sprint / fluid modifiers)
		float         bobPhase;       // MC walk-bob phase; 0 if bobbing is off
		float         bobAmount;      // MC walk-bob amplitude
		std::uint32_t pad4C;
		double        eyeX, eyeY, eyeZ;  // MC camera position (interpolated, includes sneak eye lerp)

		// Raw 20 Hz physics ticks, so the host can interpolate on its own frame clock exactly like
		// Minecraft's renderer does with partial ticks.
		std::int64_t tickNs;               // monotonic ns at the (remainder-corrected) tick
		double       prevX, prevY, prevZ;  // feet at the previous tick
		double       curX, curY, curZ;     // feet at the latest tick
		float        tickEyeO, tickEye;    // Camera's smoothed eye height, previous/latest tick
		float        walkDistO, walkDist;  // walk-bob phase inputs
		float        bobO, bob;            // walk-bob amplitude inputs
		float        tickMs;               // milliseconds per tick (50 unless /tick rate changed)
		std::uint32_t tickPad;

		// 0 first person, 1 third person behind, 2 third person in front.
		std::uint32_t cameraMode;
		float         cameraDistance;

		// Survival stats, mirrored into the host's HUD dials.
		float         health, maxHealth;
		std::uint32_t food;        // 0-20
		float         saturation;
		std::uint32_t air, maxAir;  // ticks
	};
	static_assert(sizeof(McState) == 0xE0);
	static_assert(sizeof(McState) <= 0x100);

	// ---- overlay triple buffer @0x300 --------------------------------------------------------
	// state: bits 0-1 = index of the "middle" slot, bit 2 = middle holds an unread frame.
	// Writer (MC) renders into its private back slot, then xchg(state, back | kDirty) and keeps
	// the returned index as its new back slot. Reader (host) does xchg(state, front) only when
	// the dirty bit is set and keeps the returned index as its new front slot.
	// Initial: state = 0 (middle 0), MC back = 1, host front = 2.
	inline constexpr std::uint32_t kOverlayDirty = 1u << 2;

	struct OverlayCtl
	{
		std::uint32_t state;
		std::uint32_t pad;
		std::uint64_t framesPublished;
	};
	static_assert(sizeof(OverlayCtl) == 0x10);

	struct OverlaySlotHdr
	{
		std::uint32_t width;
		std::uint32_t height;
		std::uint32_t flags;  // bit0: rows are bottom-up
		std::uint32_t pad;
		std::uint64_t frameId;
		std::uint8_t  reserved[0x40 - 0x18];
	};
	static_assert(sizeof(OverlaySlotHdr) == 0x40);

	// ---- command box @0x400 (host -> MC, v12) ---------------------------------------------------
	// One debug command at a time (dev harness, MISSION.md rule 12). The host writes text and
	// textLen, then releases seq = ack + 1. Minecraft runs it ("/..." = a Minecraft command as an
	// operator at the player; "subcraft ..." = SubCraft's own, e.g. "subcraft dump <file> <radius>"),
	// writes status and reply, then releases ack = seq.
	inline constexpr std::uint32_t kCommandTextBytes = 1008;
	inline constexpr std::uint32_t kCommandReplyBytes = 1024;

	struct CommandBox
	{
		std::uint32_t seq;
		std::uint32_t ack;
		std::int32_t  status;   // 0 ok, 1 failed, 2 unknown command
		std::uint32_t textLen;  // bytes of UTF-8 in text
		char          text[kCommandTextBytes];
		char          reply[kCommandReplyBytes];  // UTF-8, NUL-terminated (truncated)
	};
	static_assert(sizeof(CommandBox) == 0x800);
	static_assert(kOffCommandBox + sizeof(CommandBox) <= 0x1000);

	// ---- input ring @0x1000 (host produces, MC consumes) ------------------------------------
	inline constexpr std::uint32_t kInputRingEntries = 4096;  // power of two
	inline constexpr std::uint64_t kInputRingHeadOff = 0x00;  // u64, written by host
	inline constexpr std::uint64_t kInputRingTailOff = 0x40;  // u64, written by MC
	inline constexpr std::uint64_t kInputRingDataOff = 0x80;

	enum InputType : std::uint16_t
	{
		kInKey = 1,          // code = GLFW key, a = 1 press / 0 release / 2 repeat, b = GLFW mods, c = scancode (0 ok)
		kInMouseButton = 2,  // code = GLFW button (0 L, 1 R, 2 M, 3/4 side), a = 1 press / 0 release, b = GLFW mods
		kInScroll = 3,       // a = wheel notches * 120 (positive = up)
		kInCursor = 4,       // a, b = absolute cursor position in overlay pixels
		kInText = 5,         // a = unicode code point
		kInReleaseAll = 6,   // release every held key/button (input focus left MC)
		kInHurt = 7,         // host hurt the player: code = HurtKind, a = host damage * 100, b = attacker id, c = HurtFlags
		kInOpenMenu = 8,     // open Minecraft's pause/options menu
		kInLook = 9,         // relative look: a, b = mouse dx, dy * 1000 (used only when MC owns the look)
	};

	enum HurtKind : std::uint16_t
	{
		kHurtMelee = 0,
		kHurtProjectile = 1,
		kHurtOther = 2,
	};

	enum HurtFlags : std::uint32_t
	{
		kHurtGrab = 1u << 0,  // the attacker grabbed the player (reaper, Cyclops arm...)
	};

	struct InputEvent
	{
		std::uint16_t type;
		std::uint16_t code;
		std::int32_t  a;
		std::int32_t  b;
		std::int32_t  c;
	};
	static_assert(sizeof(InputEvent) == 16);

	// ---- creature table @0x12000 (host -> MC, seqlock) ----------------------------------------
	// Nearby host creatures, mirrored in Minecraft as invisible hittable proxy entities (Phase 4).
	inline constexpr std::uint32_t kMaxCreatures = 256;

	enum CreatureFlags : std::uint32_t
	{
		kCreatureHostile = 1u << 0,
		kCreatureDead = 1u << 1,
		kCreatureInvulnerable = 1u << 2,
	};

	struct CreatureRecord
	{
		std::uint32_t id;          // host instance id, stable while it exists
		std::uint32_t flags;       // CreatureFlags
		float         x, y, z;     // centre of the bottom of its box, MC coords
		float         yaw;         // MC degrees
		float         width;       // blocks
		float         height;      // blocks
		float         healthFrac;  // 0..1
		std::uint32_t pad;
		char          name[24];    // display name, UTF-8, NUL-terminated (truncated)
	};
	static_assert(sizeof(CreatureRecord) == 64);

	struct CreatureTable
	{
		std::uint32_t  seq;
		std::uint32_t  count;
		std::uint8_t   pad[0x40 - 8];
		CreatureRecord creatures[kMaxCreatures];
	};
	static_assert(sizeof(CreatureTable) == 0x40 + 64 * kMaxCreatures);
	static_assert(kOffCreatureTable + sizeof(CreatureTable) <= kOffEventRing);

	// ---- event ring @0x17000 (MC -> host) -----------------------------------------------------
	inline constexpr std::uint32_t kEventRingEntries = 512;  // power of two
	inline constexpr std::uint64_t kEventRingHeadOff = 0x00;  // u64, written by MC
	inline constexpr std::uint64_t kEventRingTailOff = 0x40;  // u64, written by host
	inline constexpr std::uint64_t kEventRingDataOff = 0x80;

	enum McEventType : std::uint32_t
	{
		kEvHitCreature = 1,  // id, a = MC damage (after MC's modifiers), b/c = knockback dir x/z, d = strength
		kEvPlayerDied = 2,   // the Minecraft player died: run the host's death flow
		kEvExplosion = 3,    // a/b/c = centre (MC coords), d = radius (blocks)
		kEvDebugResult = 4,  // reply to a debug command: id = command sequence number, flags = 0 ok / 1 error
	};

	struct McEvent
	{
		std::uint32_t type;
		std::uint32_t id;
		float         a, b, c, d;
		std::uint32_t flags;
		std::uint32_t extra;
	};
	static_assert(sizeof(McEvent) == 32);
	static_assert(kOffEventRing + kEventRingDataOff + sizeof(McEvent) * kEventRingEntries <= kOffCollisionRing);

	// ---- collision ring @0x20000 (host produces, MC consumes) -------------------------------
	// Byte ring. Every message starts 8-byte aligned with {u32 type, u32 payloadBytes}; the next
	// message starts at align8(8 + payloadBytes). A kColPad message means "skip to the start of
	// the ring". head and tail count total bytes ever written / consumed.
	inline constexpr std::uint64_t kColRingHeadOff = 0x00;
	inline constexpr std::uint64_t kColRingTailOff = 0x40;
	inline constexpr std::uint64_t kColRingDataOff = 0x80;
	inline constexpr std::uint64_t kColRingDataBytes = kCollisionRingBytes - kColRingDataOff;

	enum ColType : std::uint32_t
	{
		kColPad = 0,
		kColClear = 1,   // payload: u32 epoch
		kColRegion = 2,  // payload: ColRegion + ColBlock[count]
		kColTris = 3,    // payload: ColRegion (count = triangles) + ColTri[count] (v13): the host's exact
		                 // collision surface in the box. Replaces every triangle sent for the same box
		                 // before (boxes are 16-block sections). count 0 = known to be empty. Minecraft
		                 // moves players against these triangles and voxelizes them into ghost terrain.
		kColDry = 4,     // payload: ColDryHeader + DryBox[count] (v14): every dry volume near the player
		                 // (lifepod, habitats, subs). Replaces the previous set; count 0 = none. Water
		                 // inside them becomes air in Minecraft.
		kColBiomes = 5,  // payload: ColBiomes + u8 cell[64] + names (v15): the host's biome in each 4x4x4
		                 // cell of a 16-block section (Minecraft's biome resolution). cell index =
		                 // (y * 4 + z) * 4 + x in MC axes, value = index into the names that follow
		                 // (nameCount NUL-terminated UTF-8 strings), 255 = unknown.
	};

	struct ColBiomes
	{
		std::int32_t sx, sy, sz;  // section coords
		std::uint8_t nameCount;
		std::uint8_t pad[3];
	};
	static_assert(sizeof(ColBiomes) == 16);
	inline constexpr std::uint32_t kBiomeCells = 64;
	inline constexpr std::uint8_t kBiomeUnknown = 255;

	struct ColMsgHeader
	{
		std::uint32_t type;
		std::uint32_t payloadBytes;
	};
	static_assert(sizeof(ColMsgHeader) == 8);

	// Replaces all host collision inside the inclusive block box [min, max]: blocks listed are
	// solid (fully or partly), every other block in the box is open.
	struct ColRegion
	{
		std::int32_t  minX, minY, minZ;
		std::int32_t  maxX, maxY, maxZ;
		std::uint32_t epoch;
		std::uint32_t count;
	};
	static_assert(sizeof(ColRegion) == 32);

	// Surface material of a collision block (drives Minecraft sound type and later digging).
	enum ColMaterial : std::uint8_t
	{
		kMatUnknown = 0,  // rock
		kMatRock = 1,
		kMatSand = 2,
		kMatCoral = 3,
		kMatMetal = 4,
		kMatGlass = 5,
		kMatOrganic = 6,  // kelp, plants, flesh
		kMatIce = 7,
		kMatPrecursor = 8,
	};

	enum ColTriFlags : std::uint32_t
	{
		kTriStructure = 1u << 0,  // host-built (habitats, wrecks, vehicles), not terrain
		kTriTerrain = 1u << 1,    // the host's terrain (voxel mesh)
	};
	inline constexpr std::uint32_t kTriMaterialShift = 8;  // bits 8-15: ColMaterial

	// One collision triangle in MC coordinates. Winding: counter-clockwise seen from outside
	// (the solid is behind the face), after the Unity -> MC mirror.
	struct ColTri
	{
		float         v[9];
		std::uint32_t flags;  // ColTriFlags | material << kTriMaterialShift
	};
	static_assert(sizeof(ColTri) == 40);

	struct ColDryHeader
	{
		std::uint32_t epoch;
		std::uint32_t count;
	};
	static_assert(sizeof(ColDryHeader) == 8);

	// An axis-aligned dry box in MC coordinates (blocks whose centre is inside are dry).
	struct DryBox
	{
		float         minX, minY, minZ;
		float         maxX, maxY, maxZ;
		std::uint32_t id;     // host instance id of the interior
		std::uint32_t flags;  // reserved
	};
	static_assert(sizeof(DryBox) == 32);

	// One block's worth of host collision as an 8x8x8 occupancy mask.
	// bits[y] bit (z * 8 + x) is sub-voxel (x, y, z), each 1/8 block, in MC axes.
	struct ColBlock
	{
		std::int32_t  x, y, z;
		std::uint8_t  material;  // ColMaterial
		std::uint8_t  flags;     // bit0: structure (host-built: habitats, wrecks), not terrain
		std::uint16_t pad;
		std::uint64_t bits[8];
	};
	static_assert(sizeof(ColBlock) == 80);

	// ---- render ring (MC -> host) -------------------------------------------------------------
	// Byte ring like the collision ring. Minecraft ships the geometry it would have drawn and the
	// textures it samples; the host draws them in its own frame with its own lighting. The same
	// message stream, written to a file, is the capture-dump format (tools/capture_dump.py): a
	// 16-byte file header {u32 magic 'SCDM', u32 kVersion, u64 messageCount} then messages
	// exactly as in the ring, without kRenPad.
	inline constexpr std::uint64_t kRenRingHeadOff = 0x00;
	inline constexpr std::uint64_t kRenRingTailOff = 0x40;
	inline constexpr std::uint64_t kRenRingDataOff = 0x80;
	inline constexpr std::uint64_t kRenRingDataBytes = kRenderRingBytes - kRenRingDataOff;
	inline constexpr std::uint32_t kDumpMagic = 0x4D444353;  // "SCDM"

	enum RenType : std::uint32_t
	{
		kRenPad = 0,
		kRenAtlas = 1,     // RenAtlas + RGBA8 pixels (w * h * 4), top row first
		kRenSection = 2,   // RenSection + RenVertex[vertexCount] (triangle list); 0 vertices = remove
		kRenClearAll = 3,  // drop every section (world change)
		kRenTexture = 4,   // RenTexture + RGBA8 pixels: a non-atlas texture (entity skins, ...)
		kRenScene = 6,     // RenScene + RenBatch[batchCount] + RenVertex[vertexCount]: this frame's
		                   // dynamic draws, relative to RenScene's origin
		kRenAtlasRegion = 7,  // RenAtlasRegion + RGBA8 pixels: an animated sprite's current frame
		kRenLights = 8,       // RenLights + RenLight[count]: a section's light-emitting blocks (sent
		                      // after its kRenSection; 0 = none)
		kRenColliders = 9,    // RenColliders + RenBox[count] (v16): a section's block collision boxes
		                      // (Minecraft VoxelShapes, merged), so the host's creatures and vehicles
		                      // collide with Minecraft blocks; 0 = none. Sent with its kRenSection.
	};

	// Material classes, from render-state shards (never RenderType names).
	enum RenMaterial : std::uint32_t
	{
		kMatOpaque = 0,
		kMatCutout = 1,
		kMatTranslucent = 2,
		kMatEmissive = 3,
		kMatAdditive = 4,
	};

	struct RenAtlas
	{
		std::uint32_t width, height;
	};

	struct RenAtlasRegion
	{
		std::uint32_t x, y, width, height;  // pixels in the atlas (kRenAtlas)
	};

	struct RenTexture
	{
		std::uint32_t id;  // 1+, referenced by RenBatch::texture
		std::uint32_t width, height;
		std::uint32_t pad;
	};

	struct RenSection
	{
		std::int32_t  sx, sy, sz;   // section coords (16-block cubes)
		std::uint32_t vertexCount;  // multiple of 3
	};
	static_assert(sizeof(RenSection) == 16);

	struct RenScene
	{
		double        originX, originY, originZ;  // MC block the positions are relative to
		std::uint32_t batchCount;
		std::uint32_t vertexCount;
	};
	static_assert(sizeof(RenScene) == 32);

	struct RenBatch
	{
		std::uint32_t texture;   // 0: the block/item atlas, else a RenTexture id
		std::uint32_t first;     // first vertex
		std::uint32_t count;     // vertices (multiple of 3)
		std::uint32_t material;  // RenMaterial
	};
	static_assert(sizeof(RenBatch) == 16);

	struct RenVertex
	{
		float         x, y, z;  // MC coords relative to the section (or scene) origin
		float         u, v;     // texture UV
		std::uint32_t color;    // RGBA8 (tint * ambient occlusion; Minecraft's face shading left out)
		std::uint32_t light;    // low byte: block light 0-15, next byte: sky light 0-15
		std::uint32_t flags;    // bits 0-2: RenMaterial; bit 3: emitter (the block emits light itself:
		                        // the host draws it glowing); bits 4-6: face normal as MC Direction
		                        // ordinal + 1 (0 = none)
	};
	static_assert(sizeof(RenVertex) == 32);

	struct RenLights
	{
		std::int32_t  sx, sy, sz;  // section coords, as in RenSection
		std::uint32_t count;
	};
	static_assert(sizeof(RenLights) == 16);

	struct RenColliders
	{
		std::int32_t  sx, sy, sz;  // section coords
		std::uint32_t count;
	};
	static_assert(sizeof(RenColliders) == 16);

	// An axis-aligned box in MC coords relative to the section origin.
	struct RenBox
	{
		float minX, minY, minZ;
		float maxX, maxY, maxZ;
	};
	static_assert(sizeof(RenBox) == 24);

	enum LightKind : std::uint8_t
	{
		kLightSteady = 0,
		kLightFlame = 1,  // torches, fire, campfires, candles: flicker
		kLightLava = 2,   // lava, magma: a slow glow
	};

	struct RenLight
	{
		std::uint8_t  x, y, z;  // block within the section
		std::uint8_t  level;    // Minecraft light emission, 1-15
		std::uint32_t color;    // RGB8 (r low byte); top byte: LightKind
	};
	static_assert(sizeof(RenLight) == 8);
}
