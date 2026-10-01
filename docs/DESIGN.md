# SubCraft — design notes

`MISSION.md` is the plan. This file records what turned out to be true while building it: verified
API facts, layout decisions and findings. Code wins over this file when they disagree.

## Link

- One file, `$TMPDIR/subcraft/link.bin` (macOS) / `%LOCALAPPDATA%\SubCraft\link.bin` (Windows),
  191 MiB, sparse. The host creates and sizes it, zeroes the control blocks and writes
  magic/version/pid; Minecraft maps it when it appears. A host restart is detected by its pid
  changing in the header (Minecraft then re-teleports and drops collision state).
- Layout: `protocol/subcraft_protocol.h` (v11). Rings carry total-bytes head/tail counters;
  messages are 8-byte aligned and never wrap (a pad message skips to the ring start).
- Clock: monotonic ns. macOS: HotSpot `System.nanoTime()` = `mach_absolute_time` in ns =
  `CLOCK_UPTIME_RAW` (measured against Python, 0.1 ms median difference).
- Java side (Java 21, no FFM): `MappedByteBuffer` + `byteBufferViewVarHandle` acquire/release.

## Guest frame order (Minecraft 1.21.1)

| When | Hook | Work |
|---|---|---|
| `Minecraft.runTick` head | mixin | poll link, read `HostState`, input ring -> GLFW handlers, teleport, host look |
| each client tick end | `ClientTickEvent.Post` | death -> event + respawn, hold until ground, publish physics tick |
| server tick end | `ServerTickEvent.Post` | collision ring -> ghost terrain blocks |
| after `GameRenderer.render` | `RenderFrameEvent.Post` | `McState`, overlay readback + publish |
| `Minecraft.runTick` tail | mixin | pace to one frame per host frame |

## Verified 1.21.1 facts

- `LevelRenderer.renderLevel` clears with the fog colour; skipping it and clearing to (0,0,0,0)
  leaves hand + GUI on transparent. `Gui.renderVignette` blends alpha with (ONE, ZERO) and must be
  skipped too. `GameRenderer.renderLevel` still runs, so picking (`pick`) and the hand work.
- `ReceivingLevelScreen` waits on `LevelRenderer.isSectionCompiled(playerPos)`.
- `WorldOpenFlows.openWorldCheckWorldStemCompatibility` -> `askForBackup` fires for datapack
  dimension types (lifecycle experimental).
- Retina: GLFW window size is in points; the framebuffer is 2x. Window size = viewport / content scale.
- `KeyboardHandler.keyPress(window, key, scancode, action, mods)` is public; `MouseHandler.onPress`,
  `onScroll`, `onMove` are private (invokers).
- The dimension type `min_y -2032, height 2288` is accepted.

## Host (Subnautica, BepInEx 5)

- Plugin `host-subnautica` (net472, `SubCraft.dll` in `BepInEx/plugins/SubCraft`). The Unity-free
  part (`src/Link`: Proto, Platform, LinkView, HostLink, Coords, KeyMap) also builds into a
  net10.0 xunit project, so it is tested on the Mac without Mono.
- Link: `MemoryMappedFile` over the sparse file, raw pointer; seq/head/tail via `Volatile` and
  `Interlocked` (overlay exchange). Clock: `clock_gettime_nsec_np(CLOCK_UPTIME_RAW)` from
  libSystem. Measured under Rosetta: Minecraft's heartbeat age as seen by the host is 1-22 ms
  (one frame), so the x86_64 Mono and arm64 JVM clocks agree.
- Frame: `LinkDriver.Update` heartbeats, reads `McState`, forwards input, places the puppet
  (before the camera's LateUpdate); `LateUpdate` writes `HostState` with the camera's look.

### Player puppet (verified in game)

- Subnautica's motor is skipped with a Harmony prefix on `PlayerController.UpdateController`
  while Minecraft drives; the rigidbody is kinematic and restored on release.
- **Anchor is the eye, not the collider:** Subnautica's collider shrinks while swimming (camera
  0.56 m above its bottom when diving, 1.56 m when walking), Minecraft's player doesn't. The
  puppet puts Subnautica's camera at Minecraft's eye (feet + eye height), and "host feet" sent in
  teleports are camera minus 1.62, so teleports round-trip without drift.
- Teleport handshake: the host bumps `teleportSeq` on link up, entering the game, a Minecraft
  death, and whenever it moves the player itself (warp, harness teleport: > 2 m jump while
  puppeted). Before taking over, if Minecraft is > 2 m from the host player, it teleports again
  instead of yanking the player (found when a harness teleport landed during a pending one).
- Minecraft holds its player until the destination chunk is loaded on the client and there is
  ground or water there (an unloaded chunk reads as air: the first two-game run fell to y -800 and
  dragged the Subnautica player with it).
- Released to Subnautica when: link lost (2 s), not in game, piloting, cinematic mode. Measured
  hard-kill of Minecraft -> control back after 2.09 s (2.0 s timeout + a frame + polling).
- Subnautica's loading screens stall its main thread (and heartbeat) for 2-6 s: Minecraft pauses
  and resumes, as the fail-safe intends.

### Input (Phase 0b level)

- Unity legacy `Input` polled each frame for every mapped KeyCode -> GLFW events (table in
  `KeyMap.cs`, tested). Tab (PDA) and Esc (pause) stay Subnautica's; mouse look stays
  Subnautica's. Not yet: suppressing Subnautica's own reaction to routed keys (number keys pick
  both hotbars), Minecraft screens getting the cursor. Phase 1.

### Dev harness

- `$TMPDIR/subcraft/cmd.jsonl` (JSON lines) -> `out/results.jsonl`, `out/*.png`, `out/*.json`.
  `tools/sn_cmd.py` sends and waits; `tools/scenarios/*.jsonl` are scripts.
- New game -> its slot becomes `DevSlot` in `BepInEx/config/dev.subcraft.host.cfg`; `save` refuses
  any other slot. Dev slot: `slot0002`. Sean's `slot0000`/`slot0001` verified byte-identical to the
  backup after every session so far.
- Dev window size goes through Unity's screen prefs (a `steam://run` URL with arguments makes
  Steam ask for confirmation); `tools/sn_dev.sh stop` restores Sean's originals from the exported
  plist (`tools/sn_prefs.py`).

## Subnautica render path (Phase 0b recon, `docs/recon/*.json`)

| | Finding |
|---|---|
| Pipeline | Built-in, **deferred shading**, HDR, linear colour space, OpenGLCore 4.1 (over Metal), no MSAA |
| Main camera components | `WaterscapeVolumeOnCamera` (underwater fog/absorption), `WaterSurfaceOnCamera`, `WBOIT` (weighted blended OIT), `LensWaterController`, PostProcessing stack, `ColorCorrection`, `UwePostProcessingManager`, many screen FX (disabled) |
| Command buffers | `BeforeForwardAlpha` (unnamed), `AfterForwardAlpha` "Builder Obstacles" |
| Unity fog | **off**: fog is Subnautica's own (`WaterscapeVolume`), so built-in fog keywords won't do it |
| Lights | one directional `SunAndCaustics` (ForcePixel, shadows none at this quality) |
| Quality (dev machine) | "Low": shadows disabled, pixelLightCount 0 (deferred lights unaffected) |
| Props, wrecks, lifepod, creatures, player tools | **`MarmosetUBER`** (`_MainTex`, `_BumpMap`, `_SpecTex`, `_Illum`); keywords `_ZWRITE_ON MARMO_SPECMAP`, `+MARMO_EMISSION`, `+MARMO_ALPHA_CLIP` (cutout), `+UWE_WAVING`, `+FX_KELP`; transparent variants add `WBOIT` at queue 3101; lifepod adds `UWE_LIGHTMAP` |
| Terrain | `UWE/Terrain/Triplanar`, `Triplanar with Capping`, `UWE/SIG`, `UWE/SIG Terrain Grass` (queue 1000-2450, no shadows) |
| Particles | `UWE/Particles/UBER` with `FX_UNDERWATER`, `FX_ADDFOG`, `WBOIT` |

**Material strategy for Phase 0c (decision):** draw captured Minecraft geometry with the game's
own `MarmosetUBER` shader, taking an existing MarmosetUBER material as the template (so every
property the deferred and waterscape passes need is set) and swapping in the Minecraft atlas
(point filtered) as `_MainTex`:
opaque -> base keywords; cutout -> `MARMO_ALPHA_CLIP`; block light -> `MARMO_EMISSION` with an
emission map/vertex colour; translucent -> the `WBOIT` variant at queue 3101. Because MarmosetUBER
renders in the deferred G-buffer, Subnautica's sun, caustics, flashlight and waterscape fog light
it like native props. Unknowns for 0c: how MarmosetUBER takes vertex colour (Minecraft's tint and
AO live in vertex colours) — likely needs `_Color` per batch or a baked tint texture; normals
(blocks have flat faces, `_BumpMap` can be a flat normal map).

## Phase 0c: how Minecraft geometry is drawn (verified in game, `docs/look/`)

- **Capture (guest):** `SceneDump` runs Minecraft's own renderers into `CaptureBuffer`
  (a `VertexConsumer`): block models through `BlockRenderDispatcher.renderBatched` per chunk
  RenderType (tint, AO, connected/modded models), non-water fluids through `renderLiquid`, block
  entities through `BlockEntityRenderDispatcher.render` (needs `prepare()`: the skipped world pass
  normally does it). Material class from render-state shards (`RenderClassifier`). Face shade off
  (`getShade` = 1 in the SubCraft world). Textures read back from the GPU by resource location.
  Light-emitting blocks: a `RenLight` (emission level, colour = brightest third of their own
  sprite, flame/lava kind) and the emitter vertex flag (bit 3).
- **Draw (host):** one mesh per texture, submesh per (material class, emitter), z mirrored and
  winding flipped. Materials cloned from loaded `MarmosetUBER` materials chosen by keyword set
  (`MaterialFactory`): plain `_ZWRITE_ON MARMO_SPECMAP`, cutout `+MARMO_ALPHA_CLIP`, glow
  `+MARMO_EMISSION` with `_Illum` = the texture and `_EnableGlow`. `_MainTex` alpha is *gloss* to
  MarmosetUBER, so `_SpecInt` 0 and `_SpecTex` black.
- **Three things that had to be found:**
  1. **Ambient comes from `SkyApplier`**, not Unity: Subnautica pushes per-renderer SH ambient,
     exposures and the biome's reflection cube through a MaterialPropertyBlock. Every scene root
     gets a `SkyApplier` (anchor Auto) listing its renderers.
  2. **No mipmaps on Minecraft textures:** the "Low" preset's texture limit drops the top mip
     levels of every mipmapped texture, so 16x16 sprites showed 4x4 texels (Sean spotted it).
  3. **Vertex colours have no compiled shader path:** `MARMO_VERTEX_COLOR` / `_EnableVertexColor`
     exist but the variant isn't in the build (toggling changed nothing), and `UWE_LIGHTMAP` is
     baked *light* scaled by `_ExposureLM` (0.05 outdoors), not an albedo multiplier. So
     `AlbedoBake` multiplies tint x AO into the texture: each distinct (sprite area, four corner
     colours, 6-bit) gets a pre-coloured copy on a per-mesh page, sampled bilinearly in quad space
     (any UV rotation). Plain faces share one copy. The test scene: a handful of cells.
- **Lights:** `BlockLights`: Unity point lights (range 0.9 x level, intensity 1.6 x level/15,
  per-pixel, flames flicker). Torch light reads correctly on the wall and sand at dusk/night.
- **SkyCraft comparison (Sean asked):** SkyCraft compiles its own HLSL at runtime in its SKSE
  plugin and draws blocks in its own D3D11 pass (`texture * vertexColour * Lighting()`, lighting
  re-derived from Skyrim's depth and shadow maps). Unity can't compile shaders at runtime; a custom
  shader would need an AssetBundle from the Unity 2019.4 editor (several GB). Driving
  MarmosetUBER gets caustics, waterscape fog, flashlight and IBL exactly like native props, so the
  bake stays unless it fails at world scale (Phase 2): then a custom shader is the fallback (ask
  Sean before installing the editor).

## Phase 1: exact collision (verified in game)

**Two layers, one source.** Subnautica's collision triangles around the player go to Minecraft
(`kColTris`, protocol v13). The **player** moves against them with a capsule collide-and-slide
(`TriCollider`); the **ghost-terrain blocks** (8x8x8 masks, next step) are voxelized from the same
triangles for mobs, pathfinding and other mods, and give players no collision where triangles are
known. Minecraft still computes every velocity; only "what stops me" changes.

**Host harvest (`World/CollisionHarvester`)**
- Sections: 16-block Minecraft sections around the player (5x5x5), nearest first, a few per frame,
  re-sent only when the section's collider signature changes (id, transform, mesh id/size).
- Colliders: `OverlapBox` on the layers the player's collider layer collides with (Unity layer
  matrix); skipped: triggers, `Creature`, `Pickupable`, `Vehicle`, the player, non-kinematic bodies.
- Terrain is `ChunkCollider(Clone)` `MeshCollider`s on layer `TerrainCollider` (30). Their meshes
  read as **0 vertices**: `ClipmapCell.FinalizeCollidersIfNecessary` cooks them into PhysX and
  calls `sharedMesh.Clear()`. `TerrainMeshCapture` (Harmony prefix, read-only) copies vertices and
  indices first, keyed by mesh (pooled meshes are re-finalized and overwrite their copy).
  Read with `GetTriangles(list, submesh, applyBaseVertex: true)` per submesh (it *replaces* the list).
- **Readiness:** terrain collision only exists in the collision clipmap level's window: 5x5x5 cells
  of 16 blocks around the camera (`ClipmapLevel.cellSize` 16, `arraySize` 5,5,5). The land origin
  is a multiple of 16 away from Unity's, so each Minecraft section is exactly one cell. A section is
  sent once that cell `IsLoaded()` (inside the window and built); `LargeWorldStreamer.
  IsRangeActiveAndBuilt` is unusable per section (pads the range into cells outside the window and
  waits for object cells down to "very far").
- Box/sphere/capsule colliders are tessellated (lat-long 10x8); unreadable meshes fall back to their
  bounds (warned once per mesh).

**Guest (`world/tri`)**
- `TriStore`: immutable per-section snapshots in a concurrent map; client and integrated server
  read the same store.
- `TriCollider`: capsule (radius = half the player's width, height = its pose height; a sphere when
  swimming-shaped), 0.1-block sub-steps (< radius: no tunnelling, up to 48 per tick), 4
  depenetration passes per sub-step along the closest-point direction. Ground (normal within ~50
  degrees of up, `WALKABLE_NY` 0.64) is resolved by lifting straight up, so gravity doesn't slide
  you down slopes; steeper faces push along the normal, so motion along them survives. On ground,
  a blocked move retries one step height up and settles vertically (rounded feet would otherwise
  roll back off a ledge).
- `EntityMixin`: `@ModifyReturnValue` on `Entity.collide` (after vanilla collided with real
  blocks) for `Player`s in the SubCraft world, on client and server; `move` HEAD/TAIL keep the
  velocity along a touched surface (vanilla zeroes the whole axis). A move into an unknown section
  is refused (unknown = solid).
- `TerrainBlock.getCollisionShape`: empty for players where `TriStore.isKnown`.

**Measured:** harvested copy vs Unity raycast 0.0000 m; resting on a slope 6 cm above the point
under the feet (capsule geometry); walking 12 m uphill within 2-11 cm of the surface.

**Debugging aids:** harness `colprobe {x,y,z,radius}` (colliders, layers, filter verdicts, clipmap
cells), `harvestprobe {sx,sy,sz}` (one section's harvest step by step), `tricheck {x,z,fromY}`
(physics hit vs our copy); Minecraft `subcraft tris` via `tools/mc_cmd.py` (sections known around
the player, nearest triangle, a fall probe through the collider); state dump `link.collision` and
`link.mcFeetToSurface`.

## Open

- AlbedoBake memory at world scale (Phase 2): measure cells per section; animated sprites (water,
  lava, fire) need the bake redone per frame or a separate path.
- Translucent geometry (stained glass, water from mods): the WBOIT variants, untested so far.
- Whether the WaterscapeVolume pass needs anything from our renderers beyond depth.
- Collision: ghost-terrain voxelization (shell + solid fill from triangle orientation), moving
  structures (Cyclops/Seamoth as kinematic triangle sets), harvest churn while terrain settles,
  material per triangle (footstep sounds) from Subnautica's surface types.
