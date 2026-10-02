namespace SubCraft.Link
{
	/// <summary>
	/// Mirror of protocol/subcraft_protocol.h. Keep the two in sync; ProtoLayoutTests checks every
	/// value here against protocol/layout.json.
	/// </summary>
	public static class Proto
	{
		public const uint Magic = 0x43425553; // "SUBC"
		public const uint Version = 14;
		public const string LinkFileName = "link.bin";
		public const ulong HeartbeatTimeoutNs = 2_000_000_000UL;

		// ---- region offsets ----
		public const long OffHeader = 0x0;
		public const long OffHostState = 0x100;
		public const long OffMcState = 0x200;
		public const long OffOverlayCtl = 0x300;
		public const long OffOverlaySlotHdr = 0x340;
		public const long OffCommandBox = 0x400;
		public const long OffInputRing = 0x1000;
		public const long OffCreatureTable = 0x12000;
		public const long OffEventRing = 0x17000;
		public const long OffCollisionRing = 0x20000;
		public const long CollisionRingBytes = 32L << 20;
		public const long OffOverlayPixels = OffCollisionRing + CollisionRingBytes;
		public const int MaxOverlayW = 3840;
		public const int MaxOverlayH = 2160;
		public const long OverlaySlotBytes = (long)MaxOverlayW * MaxOverlayH * 4;
		public const int OverlaySlots = 3;
		public const long OffRenderRing = OffOverlayPixels + OverlaySlotBytes * OverlaySlots;
		public const long RenderRingBytes = 64L << 20;
		public const long MappingBytes = OffRenderRing + RenderRingBytes;

		// ---- Header ----
		public const long HMagic = 0x00, HVersion = 0x04, HHostPid = 0x08, HMcPid = 0x0C, HHostHeartbeatNs = 0x10, HMcHeartbeatNs = 0x18;
		public const int HeaderBytes = 0x20;

		// ---- HostState (relative to OffHostState) ----
		public const long HsSeq = 0x00, HsFlags = 0x04, HsWorldId = 0x08, HsCollisionEpoch = 0x0C, HsPosX = 0x10, HsPosY = 0x18, HsPosZ = 0x20,
			HsYaw = 0x28, HsPitch = 0x2C, HsTeleportSeq = 0x30, HsViewportW = 0x34, HsViewportH = 0x38, HsDayFraction = 0x3C,
			HsOxygen = 0x40, HsOxygenCapacity = 0x44;
		public const int HostStateBytes = 0x60;
		public const uint HostInGame = 1, HostMenuOpen = 1 << 1, HostLoading = 1 << 2, HostUnderwater = 1 << 3, HostInside = 1 << 4;

		// ---- McState (relative to OffMcState) ----
		public const long MsSeq = 0x00, MsFlags = 0x04, MsX = 0x08, MsY = 0x10, MsZ = 0x18, MsYaw = 0x20, MsPitch = 0x24, MsEyeHeight = 0x28,
			MsSensitivity = 0x2C, MsTeleportAck = 0x30, MsGuiScale = 0x34, MsFrameCounter = 0x38, MsFov = 0x40, MsBobPhase = 0x44,
			MsBobAmount = 0x48, MsEyeX = 0x50, MsEyeY = 0x58, MsEyeZ = 0x60, MsTickNs = 0x68, MsPrevX = 0x70, MsPrevY = 0x78, MsPrevZ = 0x80,
			MsCurX = 0x88, MsCurY = 0x90, MsCurZ = 0x98, MsTickEyeO = 0xA0, MsTickEye = 0xA4, MsWalkDistO = 0xA8, MsWalkDist = 0xAC,
			MsBobO = 0xB0, MsBob = 0xB4, MsTickMs = 0xB8, MsCameraMode = 0xC0, MsCameraDistance = 0xC4, MsHealth = 0xC8, MsMaxHealth = 0xCC,
			MsFood = 0xD0, MsSaturation = 0xD4, MsAir = 0xD8, MsMaxAir = 0xDC;
		public const int McStateBytes = 0xE0;
		public const uint McInWorld = 1, McScreenOpen = 1 << 1, McOnGround = 1 << 2, McSneaking = 1 << 3, McSprinting = 1 << 4, McDead = 1 << 5,
			McSwimming = 1 << 6, McFlying = 1 << 7, McInWater = 1 << 8, McEyeInWater = 1 << 9;

		// ---- Overlay ----
		public const long OcState = 0x00, OcFramesPublished = 0x08;
		public const int OverlayDirty = 1 << 2;
		public const long SlotHdrBytes = 0x40, ShWidth = 0x00, ShHeight = 0x04, ShFlags = 0x08, ShFrameId = 0x10;

		// ---- Command box (relative to OffCommandBox, v12) ----
		public const long CbSeq = 0x00, CbAck = 0x04, CbStatus = 0x08, CbTextLen = 0x0C, CbText = 0x10, CbReply = 0x400;
		public const int CommandTextBytes = 1008, CommandReplyBytes = 1024;
		public const int CmdOk = 0, CmdFailed = 1, CmdUnknown = 2;

		// ---- Input ring (relative to OffInputRing) ----
		public const int InputRingEntries = 4096;
		public const long IrHead = 0x00, IrTail = 0x40, IrData = 0x80;
		public const int InputEventBytes = 16;
		public const ushort InKey = 1, InMouseButton = 2, InScroll = 3, InCursor = 4, InText = 5, InReleaseAll = 6, InHurt = 7, InOpenMenu = 8,
			InLook = 9;
		public const ushort HurtMelee = 0, HurtProjectile = 1, HurtOther = 2;
		public const int HurtGrab = 1;

		// ---- Creature table ----
		public const int MaxCreatures = 256;
		public const long CtSeq = 0x00, CtCount = 0x04, CtRecords = 0x40;
		public const int CreatureRecordBytes = 64;
		public const long CrecId = 0, CrecFlags = 4, CrecX = 8, CrecY = 12, CrecZ = 16, CrecYaw = 20, CrecWidth = 24, CrecHeight = 28,
			CrecHealthFrac = 32, CrecName = 40;
		public const int CreatureNameBytes = 24;

		// ---- Event ring (relative to OffEventRing) ----
		public const int EventRingEntries = 512;
		public const long ErHead = 0x00, ErTail = 0x40, ErData = 0x80;
		public const int EventBytes = 32;
		public const uint EvHitCreature = 1, EvPlayerDied = 2, EvExplosion = 3, EvDebugResult = 4;

		// ---- Collision ring (relative to OffCollisionRing) ----
		public const long CrHead = 0x00, CrTail = 0x40, CrData = 0x80;
		public const long CrDataBytes = CollisionRingBytes - CrData;
		public const uint ColPad = 0, ColClear = 1, ColRegion = 2, ColTris = 3, ColDry = 4;
		public const int ColDryHeaderBytes = 8, DryBoxBytes = 32;
		public const int ColTriBytes = 40;
		public const uint TriStructure = 1, TriTerrain = 2;
		public const int TriMaterialShift = 8;
		public const int ColRegionBytes = 32, ColBlockBytes = 80, ColBlockBits = 16;
		public const byte MatUnknown = 0, MatRock = 1, MatSand = 2, MatCoral = 3, MatMetal = 4, MatGlass = 5, MatOrganic = 6, MatIce = 7, MatPrecursor = 8;

		// ---- Render ring (relative to OffRenderRing) ----
		public const long RrHead = 0x00, RrTail = 0x40, RrData = 0x80;
		public const long RrDataBytes = RenderRingBytes - RrData;
		public const uint DumpMagic = 0x4D444353; // "SCDM"
		public const uint RenPad = 0, RenAtlas = 1, RenSection = 2, RenClearAll = 3, RenTexture = 4, RenScene = 6, RenAtlasRegion = 7, RenLights = 8;
		public const int RenVertexBytes = 32, RenBatchBytes = 16;
		public const int RenMatOpaque = 0, RenMatCutout = 1, RenMatTranslucent = 2, RenMatEmissive = 3, RenMatAdditive = 4;
		public const uint VertexEmitter = 1 << 3;
		public const int LightSteady = 0, LightFlame = 1, LightLava = 2;
	}
}
