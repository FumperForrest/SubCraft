// Prints protocol/layout.json from subcraft_protocol.h (offsets and sizes as the C++ compiler
// sees them). tools/check_layout.sh compiles this, regenerates the JSON and fails if the
// committed layout.json differs; the Java and C# layout tests read the committed file.
//
//   clang++ -std=c++20 -o /tmp/layout_dump protocol/layout_dump.cpp && /tmp/layout_dump > protocol/layout.json
#include "subcraft_protocol.h"

#include <cstddef>
#include <cstdio>

using namespace subcraft::proto;

static bool firstStruct = true;
static bool firstField = true;

static void beginStruct(const char* name, std::size_t size)
{
	std::printf("%s\n    \"%s\": {\"size\": %zu, \"fields\": {", firstStruct ? "" : ",", name, size);
	firstStruct = false;
	firstField = true;
}

static void field(const char* name, std::size_t offset)
{
	std::printf("%s\"%s\": %zu", firstField ? "" : ", ", name, offset);
	firstField = false;
}

static void endStruct()
{
	std::printf("}}");
}

#define S(T) beginStruct(#T, sizeof(T))
#define F(T, f) field(#f, offsetof(T, f))
#define C(name) std::printf("%s\n    \"%s\": %llu", first ? "" : ",", #name, (unsigned long long)(name)), first = false

int main()
{
	std::printf("{\n  \"version\": %u,\n  \"constants\": {", kVersion);
	bool first = true;
	C(kMagic);
	C(kVersion);
	C(kHeartbeatTimeoutNs);
	C(kOffHeader);
	C(kOffHostState);
	C(kOffMcState);
	C(kOffOverlayCtl);
	C(kOffOverlaySlotHdr);
	C(kOffCommandBox);
	C(kCommandTextBytes);
	C(kCommandReplyBytes);
	C(kOffInputRing);
	C(kOffCreatureTable);
	C(kOffEventRing);
	C(kOffCollisionRing);
	C(kCollisionRingBytes);
	C(kOffOverlayPixels);
	C(kMaxOverlayW);
	C(kMaxOverlayH);
	C(kOverlaySlotBytes);
	C(kOverlaySlots);
	C(kOffRenderRing);
	C(kRenderRingBytes);
	C(kMappingBytes);
	C(kOverlayDirty);
	C(kInputRingEntries);
	C(kInputRingHeadOff);
	C(kInputRingTailOff);
	C(kInputRingDataOff);
	C(kMaxCreatures);
	C(kEventRingEntries);
	C(kEventRingHeadOff);
	C(kEventRingTailOff);
	C(kEventRingDataOff);
	C(kColRingHeadOff);
	C(kColRingTailOff);
	C(kColRingDataOff);
	C(kColRingDataBytes);
	C(kRenRingHeadOff);
	C(kRenRingTailOff);
	C(kRenRingDataOff);
	C(kRenRingDataBytes);
	C(kDumpMagic);
	std::printf("\n  },\n  \"structs\": {");

	S(Header);
	F(Header, magic); F(Header, version); F(Header, hostPid); F(Header, mcPid);
	F(Header, hostHeartbeatNs); F(Header, mcHeartbeatNs);
	endStruct();

	S(HostState);
	F(HostState, seq); F(HostState, flags); F(HostState, worldId); F(HostState, collisionEpoch);
	F(HostState, posX); F(HostState, posY); F(HostState, posZ); F(HostState, yaw); F(HostState, pitch);
	F(HostState, teleportSeq); F(HostState, viewportW); F(HostState, viewportH); F(HostState, dayFraction);
	endStruct();

	S(McState);
	F(McState, seq); F(McState, flags); F(McState, x); F(McState, y); F(McState, z);
	F(McState, yaw); F(McState, pitch); F(McState, eyeHeight); F(McState, sensitivity);
	F(McState, teleportAck); F(McState, guiScale); F(McState, frameCounter); F(McState, fovDeg);
	F(McState, bobPhase); F(McState, bobAmount); F(McState, eyeX); F(McState, eyeY); F(McState, eyeZ);
	F(McState, tickNs); F(McState, prevX); F(McState, prevY); F(McState, prevZ);
	F(McState, curX); F(McState, curY); F(McState, curZ); F(McState, tickEyeO); F(McState, tickEye);
	F(McState, walkDistO); F(McState, walkDist); F(McState, bobO); F(McState, bob); F(McState, tickMs);
	F(McState, cameraMode); F(McState, cameraDistance); F(McState, health); F(McState, maxHealth);
	F(McState, food); F(McState, saturation); F(McState, air); F(McState, maxAir);
	endStruct();

	S(OverlayCtl);
	F(OverlayCtl, state); F(OverlayCtl, framesPublished);
	endStruct();

	S(OverlaySlotHdr);
	F(OverlaySlotHdr, width); F(OverlaySlotHdr, height); F(OverlaySlotHdr, flags); F(OverlaySlotHdr, frameId);
	endStruct();

	S(CommandBox);
	F(CommandBox, seq); F(CommandBox, ack); F(CommandBox, status); F(CommandBox, textLen); F(CommandBox, text); F(CommandBox, reply);
	endStruct();

	S(InputEvent);
	F(InputEvent, type); F(InputEvent, code); F(InputEvent, a); F(InputEvent, b); F(InputEvent, c);
	endStruct();

	S(CreatureRecord);
	F(CreatureRecord, id); F(CreatureRecord, flags); F(CreatureRecord, x); F(CreatureRecord, y); F(CreatureRecord, z);
	F(CreatureRecord, yaw); F(CreatureRecord, width); F(CreatureRecord, height); F(CreatureRecord, healthFrac);
	F(CreatureRecord, name);
	endStruct();

	S(CreatureTable);
	F(CreatureTable, seq); F(CreatureTable, count); F(CreatureTable, creatures);
	endStruct();

	S(McEvent);
	F(McEvent, type); F(McEvent, id); F(McEvent, a); F(McEvent, b); F(McEvent, c); F(McEvent, d);
	F(McEvent, flags); F(McEvent, extra);
	endStruct();

	S(ColMsgHeader);
	F(ColMsgHeader, type); F(ColMsgHeader, payloadBytes);
	endStruct();

	S(ColRegion);
	F(ColRegion, minX); F(ColRegion, minY); F(ColRegion, minZ); F(ColRegion, maxX); F(ColRegion, maxY);
	F(ColRegion, maxZ); F(ColRegion, epoch); F(ColRegion, count);
	endStruct();

	S(ColBlock);
	F(ColBlock, x); F(ColBlock, y); F(ColBlock, z); F(ColBlock, material); F(ColBlock, flags); F(ColBlock, bits);
	endStruct();

	S(RenAtlas);
	F(RenAtlas, width); F(RenAtlas, height);
	endStruct();

	S(RenAtlasRegion);
	F(RenAtlasRegion, x); F(RenAtlasRegion, y); F(RenAtlasRegion, width); F(RenAtlasRegion, height);
	endStruct();

	S(RenTexture);
	F(RenTexture, id); F(RenTexture, width); F(RenTexture, height);
	endStruct();

	S(RenSection);
	F(RenSection, sx); F(RenSection, sy); F(RenSection, sz); F(RenSection, vertexCount);
	endStruct();

	S(RenScene);
	F(RenScene, originX); F(RenScene, originY); F(RenScene, originZ); F(RenScene, batchCount); F(RenScene, vertexCount);
	endStruct();

	S(RenBatch);
	F(RenBatch, texture); F(RenBatch, first); F(RenBatch, count); F(RenBatch, material);
	endStruct();

	S(RenVertex);
	F(RenVertex, x); F(RenVertex, y); F(RenVertex, z); F(RenVertex, u); F(RenVertex, v);
	F(RenVertex, color); F(RenVertex, light); F(RenVertex, flags);
	endStruct();

	S(RenLights);
	F(RenLights, sx); F(RenLights, sy); F(RenLights, sz); F(RenLights, count);
	endStruct();

	S(RenLight);
	F(RenLight, x); F(RenLight, y); F(RenLight, z); F(RenLight, level); F(RenLight, color);
	endStruct();

	std::printf("\n  }\n}\n");
	return 0;
}
