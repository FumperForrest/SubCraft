# SubCraft architecture review (2026-10-03)

Written overnight in a cloud session with no games, no GPU and no Steam. Every claim carries
`file:line` evidence (line numbers as of commit `26a936d`, the base of branch
`arch-review-2026-10-03`). The code was read by five parallel reviewers (protocol, guest, host
link/player/combat, host render/world/audio/hud, tools); every finding used below was re-checked
against the source by hand, and the ones I could not confirm are marked **unconfirmed**.

What could be run here: `./gradlew build test` (41 tests), `tools/check_layout.sh`,
`host-subnautica/tests` (42 tests), the Python fakes against each other, and (new tonight) a
compile-only build of the whole Subnautica plugin against the `Subnautica.GameLibs` NuGet package
(section 6.1).

Contents
1. How it works today
2. Where it diverges from MISSION.md / DESIGN.md
3. Problems, ranked
4. Target architecture
5. Migration path
6. What changed tonight / what is only proposed (filled in as the work lands)

---

## 1. How it works today

### 1.1 Processes, threads, ownership

```
 Subnautica (Unity 2019.4 Mono, x86_64 under Rosetta on the Mac)        Minecraft 1.21.1 NeoForge (JVM, arm64 on the Mac)
 ─────────────────────────────────────────────────────────────          ─────────────────────────────────────────────────
 Plugin.Awake: Harmony.PatchAll, one hidden GameObject with             SubCraft (common) + SubCraftClient (client) event wiring
   LinkDriver, GrabController, OverlayView, LiveWorld, DevHarness
                                                                          render thread:
 main thread, Update (LinkDriver, order among the 5 unspecified):          runTick HEAD   SubClient.beginFrame: SubLink.poll, HostState,
   heartbeat, McState read, event ring drain, InputCapture,                              input ring -> GLFW handlers, command box, teleport
   CollisionHarvester + DryVolumes (collision ring), creature table,      client tick    death, hold-until-ground, publish tick (McState)
   mob table, PlayerPuppet, SoundBridge, SurvivalDials                    renderLevel    skipped; DynamicCapture -> render ring
 LiveWorld.Update: render ring drain (4 ms budget) -> GameObjects         RenderFrame    McState, overlay readback, SectionStreamer -> render ring
 OverlayView.Update: overlay triple buffer -> uGUI RawImage               runTick TAIL   pace to the host frame
 LateUpdate: DiverHider, HostState write                                 integrated server thread:
 Harmony prefixes/postfixes: camera, input, damage, HUD, terrain mesh      collision ring -> TriStore / GhostTerrain / Dry / Biomes,
                                                                           time sync, breath, creature proxies, mob table, server.execute tasks
                                                                         worker "SubCraft voxelizer": Voxelizer over TriStore
```

One file-backed mapping (`protocol/subcraft_protocol.h`, v25, 191 MiB):

| Block | Direction | Sync | Producer / consumer |
|---|---|---|---|
| Header (pids, heartbeats) | both | release/acquire | `LinkView.cs:66-73`, `LinkView.java:99-113` |
| HostState @0x100 | host -> MC | seqlock | `LinkDriver.LateUpdate` / `SubClient.beginFrame`, server tick |
| McState @0x200 | MC -> host | seqlock | render thread / `LinkDriver.Update` |
| Overlay ctl + 3 slots | MC -> host | triple buffer (xchg) | `OverlayExporter` / `OverlayView` |
| Command box @0x400 | Python tools -> MC | seq/ack | `mc_cmd.py`, `fake_host.py` / `DebugCommands` |
| Input ring @0x1000 | host -> MC | SPSC record ring | ~10 host call sites / `InputBridge.drain` |
| Creature table @0x12000 | host -> MC | seqlock | `CreatureLink` / `Proxies` (server) |
| Mob table @0x16100 | MC -> host | seqlock | `MobTable` (server) / `MobStandIns` |
| Event ring @0x17000 | MC -> host | MPSC-in-process (synchronized) record ring | render + server threads / `LinkDriver` |
| Collision ring (32 MiB) | host -> MC | SPSC byte ring | `CollisionHarvester`, `DryVolumes` / `CollisionConsumer` (server) |
| Render ring (64 MiB) | MC -> host | SPSC byte ring (synchronized in-process) | ~6 guest producers / `LiveWorld` |

Ownership matches MISSION.md section 2 in spirit: Minecraft computes player physics, damage after
modifiers, health and hunger; Subnautica owns terrain, creatures, oxygen, time, light and sound
mixing. Neither side simulates the other's mechanics (the host's grab controller and knockback
multiplier are tuning, not simulation). The translation rules (z flip, damage x5 / x0.2, breath,
day fraction) live in many places rather than one (section 3.4).

### 1.2 Data flows per feature

| Feature | Host side | Wire | Guest side |
|---|---|---|---|
| Player + camera | `PlayerPuppet`, `CharacterCamera`, `HostState.cs` | HostState (look, teleport), McState (pose, bob, FOV, tick) | `SubClient`, `LookCapture` |
| Input | `InputCapture`, `InputRouter`, `McScreenInput` | input ring | `InputBridge`, `InputConstantsMixin` |
| Collision / world | `CollisionHarvester`, `TerrainMeshCapture`, `OctreeProbe`, `DryVolumes`, `LifepodAnchor` | collision ring (tris, dry, biomes, clear) | `CollisionConsumer`, `TriStore`, `TriCollider`, `GhostTerrain`, `Voxelizer`, `BiomePatcher`, `DryVolumes` |
| Rendering | `LiveWorld`, `BakeCache`, `AlbedoBake`, `MaterialFactory`, `BlockLights` | render ring (atlas, sections, lights, colliders, scenes, hand, textures, sub-levels) | `SectionStreamer`, `SectionCapture`, `DynamicCapture`, `CaptureBuffer`, `VboCapture`, `AtlasAnimator`, `SubLevels` |
| Sound | `Audio/SoundBridge` | render ring (`kRenSound`) + event ring | `SoundBridge`, `SoundEngineMixin` |
| Combat | `CreatureLink`, `MobStandIns`, `GrabController` | creature table, mob table, events, input ring (hurt) | `Proxies`, `CreatureProxy`, `MobTable`, `StaffGrab` |
| HUD | `OverlayView`, `SurvivalDials` | overlay, McState stats | `OverlayExporter`, HUD layer events |
| Dev harness | `DevHarness` (45-case switch), `StateDump` | cmd.jsonl files; command box via Python | `DebugCommands` |

### 1.3 How the code is organised

- **Protocol**: the header is the source of truth; `layout_dump.cpp` (hand-maintained field list)
  emits `layout.json`; `Proto.java`, `Proto.cs` and the Python tools are hand-written mirrors.
  The layout tests check offsets and sizes, **not** enum values, message kinds or flag bits, and
  nothing checks the Python tools (section 3.1).
- **Guest**: one Gradle module, one source set. Every subsystem is a class of statics (~110 mutable
  static fields); `SubClient` is the hub (referenced from 14 files, including mixins and compat).
  Pure logic exists and is tested where it was born pure (`Voxelizer`, `TriCollider`,
  `SubBiomes`); elsewhere it is interleaved with Minecraft calls.
- **Host**: one plugin assembly. `src/Link` is Unity-free and is compiled into the net10.0 test
  project by file path (`tests/SubCraft.Host.Tests.csproj:10-14`) along with three render files.
  Everything else is MonoBehaviours and static classes reached through `LinkDriver.Instance`
  (a service locator used from Player, Hud, Combat, Render and Dev).
- **Tools**: Python 3.9 stdlib fakes that hard-code the layout (`fake_host.py:23-49`), sh/ps1
  twins for game control.
- **Tests**: 41 Java + 42 C# no-game tests; no CI; no test crosses the language boundary.

---

## 2. Where the code diverges from MISSION.md / DESIGN.md

| MISSION / DESIGN says | Code does | Evidence |
|---|---|---|
| "Mirrored in Proto.java and Proto.cs, layout-tested on all sides" (rule 2) | Offsets yes; enums, message kinds, flags and the Python mirror are untested | `layout_dump.cpp` has no enums; `fake_host.py:22` claims check_layout covers Python, it doesn't |
| "Change behaviour only in the SubCraft dimension, and only while linked" (CLAUDE.md, 3.5) | Several hooks run in every world or after unlink: input/look mixins gated by `tookOver` only; `setSectionDirty` and `VertexBuffer.upload` hooks ungated; `EntityMixin` gated by world, not link; capture runs while unlinked | `InputConstantsMixin.java:17,24`, `LocalPlayerTurnMixin.java:16`, `LevelRendererMixin.java:50-53`, `VertexBufferMixin.java:17-20`, `SubClient.java:73` |
| Fail safe: heartbeat lost -> host gives control back, MC pauses (rule 7) | Done; but a Minecraft stall > 2 s (GC, world load) also clears every drawn section on the host, costing a full resend | `LinkDriver.cs:58-75`, `LiveWorld` clear on link down |
| Platform code only behind a `Platform` layer (section 2) | True for the link; the tools' path rules differ between Python, Java and C# (`SUBCRAFT_DIR`, TMPDIR fallback) | `fake_host.py:63-69` vs `Platform.cs:57-77`, `Platform.java:126-146` |
| `versions.md`: Subnautica changeSet 71288 | The plugin only compiles against Subnautica.GameLibs **82304** (`GameInputLegacy/System/Steam`, `GameInput.Button.Look` are missing in 71288) | compile check, section 6.1 |
| "One death" | Interim: Minecraft respawns at once, host teleports | `SubClient.java` handleDeath, `LinkDriver.cs:95-99` (known, Phase 5) |
| DEVLOG "newest first, one entry per session" | Session 10 sits *below* session 9 and its first paragraph is spliced into session 9's text | `docs/DEVLOG.md:139-147` |
| CLAUDE.md machine notes describe the Mac | Sean has been on Windows since 2026-10-02; the Mac section is still first | `CLAUDE.md` |
| `HostState.collisionEpoch` | Never written by the host, never used by the guest | `HostState.cs` Fill, `LinkView.java:183` |

---

## 3. Problems, ranked by severity

Legend: **Cloud** = can be fixed and verified without the games; **Game** = needs an in-game test.

### 3.1 Protocol, seqlocks, rings

**Memory ordering is correct on both sides.** Every seqlock writer does odd-release, fence,
payload, even-release (`LinkView.java:238-284`, `:353-370` with `storeStoreFence`;
`LinkView.cs:103-151` with `Thread.MemoryBarrier`), every reader does acquire, payload, fence,
acquire (`LinkView.java:155-179,382-395`; `LinkView.cs:184-238,260-287`); every ring producer
writes payload then releases head and acquires tail before reuse, every consumer acquires head and
releases tail after a synchronous copy. The overlay exchange is a full-fence xchg on both sides.
The existing torn-read tests use a writer copied into the test rather than the real one
(`LinkViewTest.java:117-131`, `LinkTests.cs:312-326`); x86 CI can't catch arm64 reordering anyway.

The problems are elsewhere:

| # | Severity | Problem | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| P1 | High | **Peer lengths are trusted.** Neither ring drain checks `payloadBytes` against the ring end or `head - tail`; a negative length makes `align8(8+len)` 0 so the same message is delivered over and over; a huge one moves tail past head and breaks the producer's free-space math. The host then reads counts from the payload with raw pointers (`RenSection`, `RenLights`, `RenColliders` counts, `RenSound` length, atlas/texture `w*h`) without checking them against the payload: a drifted or buggy guest crashes Subnautica with an access violation. | `LinkView.cs:400-418`, `LinkView.java:452-468`, `LiveWorld.cs:243,251,275,295,311,335`; guest `CollisionConsumer.java:109-114` lacks a `count < 0` check | Validate in the drains (bad message -> skip to head, count it, log once); decode render payloads in a Unity-free decoder that checks every count | Cloud |
| P2 | High | **No version re-check after a host restart.** Minecraft checks magic/version only when it first maps the file; on a host pid change it carries on. Updating the plugin (new kVersion) and restarting Subnautica with Minecraft still running reads the new layout with the old code. If `kMappingBytes` changed, the host's `SetLength` truncates the file under the JVM mapping (SIGBUS on macOS) or fails outright on Windows (mapped section) and the plugin goes inert. | `SubLink.java:53-61`, `HostLink.cs:25-29` | Re-validate the header on every pid change; unmap and re-map on mismatch | Cloud (logic), Game (restart) |
| P3 | Medium | **Overlay slots alias after a Minecraft reconnect.** The host's front index is reset only in `InitAsHost`; a new Minecraft always starts with back = 1. If the previous Minecraft left its back slot != 1, the new writer's back slot is the host's front or the shared middle: from then on every third frame is written into the slot the host is uploading (HUD tearing until Subnautica restarts). 8 of the 12 possible leftover states alias permanently. | `LinkView.cs:18,64`, `LinkView.java:22,517`, `SubLink.java:57` | Publish the reader's front index in `OverlayCtl.pad`; the writer derives its back slot from (middle, front) with a consistency retry on every (re)connect (protocol v26) | Cloud |
| P4 | Medium | **Enums and message kinds are untested across languages.** Message types, flag bits, materials and sound/grab flags are hand-copied into Java, C# and Python with nothing comparing them. Payload structs are only partly emitted (`RenSubLevel` 8 of 20 fields, `RenBox` 2 of 6): swapping two same-typed fields passes every check. | `layout_dump.cpp`, `ProtoLayoutTest.java`, `LinkTests.cs:34-150` | Emit every enum and every field from the header; tests compare all of them; Python checked too | Cloud |
| P5 | Medium | **`kVersion` is a merge-conflict magnet.** 15 bumps in 3 days (v11 -> v25), each touching header + 2 mirrors + Python + layout.json. Two parallel branches that both change the protocol both pick "v26". | `git log -p protocol/subcraft_protocol.h` | Derive a layout fingerprint (hash of the generated layout) and check that, keep `kVersion` for humans | Cloud |
| P6 | Medium | **Atlas messages over 32 MB throw on the render thread.** `tryWriteRender` throws for messages over half the ring; `SectionStreamer` sends the whole block atlas in one message. A modpack atlas of ~2900² or more (4096² = 64 MB) throws from `RenderFrameEvent` every frame. `kRenSound` and `kRenTexture` can hit the same limit. | `LinkView.java:487-489`, `SectionStreamer.java:81` | Send big textures as a header plus strips (`kRenAtlasRegion` already exists); catch and log oversize on the guest | Cloud (guest), Game (host strip path) |
| P7 | Low | Input ring producer never reads the tail; when Minecraft is a full ring behind the host can rewrite the slot Minecraft is reading. Restart races: `InitAsHost` zeroes head/tail while Minecraft may be mid-write. | `LinkView.cs:303-314`, `:55-69` | Generation number per ring session, see 4.3 | Cloud |
| P8 | Low | Command box writers (`mc_cmd.py`, `fake_host.py`) set `seq = ack + 1` without checking that the previous command finished: a timed-out command's reply is returned as the next command's answer. No magic/version check in `mc_cmd.py`. | `mc_cmd.py:94-98`, `fake_host.py:130-134` | Refuse while `seq != ack`; check the header | Cloud |
| P9 | Low | `magic()`/`version()` read plainly; on arm64 a fresh magic may be seen with a stale version (spurious "mismatch", retried 1 s later). | `LinkView.java:91-97` | Acquire the magic | Cloud |

### 3.2 Threading and lifecycle

| # | Severity | Problem | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| L1 | High | **Subnautica's food and water can stay frozen after Minecraft goes away.** `savedFreezeStats` is captured on every activation, but the puppet sets `freezeStats = true` every frame and `Release(null)` (piloting, cinematics, teleports) keeps it. After any vehicle ride the saved value is `true`, and the final release on unlink writes `true` back. `savedKinematic` follows the same pattern. | `PlayerPuppet.cs:82-85,92-96,180-196` | Capture once per link session (state machine in core, 4.4) | Game (plus a core unit test) |
| L2 | High | **Subnautica's quickslot bar stays hidden after Minecraft dies.** `OverlayView.Update` returns before `HideQuickSlots` when the link is down, leaving alpha 0. | `OverlayView.cs:50-53,69,94` | Restore before the early return | Game |
| L3 | High | **Native memory leak per frame in the guest.** `DynamicCapture.Source` is created two or three times a frame and each allocates a `ByteBufferBuilder` (malloc) that is never closed (~165 MB/hour at 60 fps). The shared buffer is never used (getBuffer/endBatch are overridden). | `DynamicCapture.java:62-63,106,245`; vanilla `MultiBufferSource.java` | One shared builder for all sources | Cloud (compile + reasoning), Game (RSS over time) |
| L4 | High | **`VboCapture` copies every non-immediate VBO upload in every world**, up to 512 MB of heap (a quarter of the 2 GB dev heap), because the upload hook isn't gated. | `VboCapture.java:39,88-107`, `VertexBufferMixin.java:17-20` | Gate on `tookOver && SubWorld`, lower the cap under `lowMemory` | Game |
| L5 | Medium | **Collision generation race.** `SubLink.generation` is bumped on the render thread but acted on by the server thread; if the server drains a new host's first messages before it sees the bump it then wipes them. Every host instance starts at epoch 1, so the new host's `ColClear` matches the old epoch and is ignored. The host never resends, so those sections stay "unknown" = solid. | `SubLink.java:59`, `CollisionConsumer.java:49-59,91-101`, `CollisionHarvester.cs:51-57` | Host epoch seeded from pid/time; consumer resets on the epoch, not on the render-thread generation | Cloud (consumer logic) |
| L6 | Medium | **Nothing listens for world/server unload.** No `ServerStopped`, `LevelEvent.Unload`, logout or resource-reload hook exists. Old levels stay reachable (`GhostTerrain.applyingChunk`, `Proxies.spawned`, `SubClient` player refs); `BiomePatcher.holders` caches registry holders from the old server; atlas, texture-id and VBO caches go stale after F3+T. | grep shows none; `GhostTerrain.java:50`, `BiomePatcher.java:27,38`, `SectionStreamer.java:52` | A `Session` object per (link generation, world) owning every cache; dropped on unload | Cloud (structure), Game (reload) |
| L7 | Medium | Esc key-up is dropped when the Minecraft screen closed on key-down, so `HeldKeys` keeps Escape and every other Esc press is swallowed. `McScreenInput.pushed` can stay true across a scene unload. | `InputCapture.cs:48`, `McScreenInput.cs:45` | Always deliver releases of held keys; reset on `Player.main` change | Game (plus core test once extracted) |
| L8 | Medium | **Creature attacker id mismatch (unconfirmed in game).** The creature table uses `root.GetInstanceID()` (the GameObject), hurt events send `creature.GetInstanceID()` (the `Creature` component): different ids in Unity, so the guest's `Proxies.proxy(attacker)` would return null and bites would become `generic()` damage. DEVLOG session 9 reports "mob by creature_proxy", which contradicts this; check. | `CreatureLink.cs:69` vs `:185`, `MobStandIns.cs:126`, `InputBridge.java:70` | Use `creature.gameObject.GetInstanceID()` in both places | Game |
| L9 | Medium | No exception isolation per subsystem in `LinkDriver.Update`: one subsystem throwing every frame stops everything after it, while the heartbeat (written first) says all is well. One failed Harmony patch makes `PatchAll` throw before the host GameObject exists. | `LinkDriver.cs:49-130`, `Plugin.cs:44-52` | Per-subsystem try/catch with a per-subsystem "faulted" flag; patch per class | Game |
| L10 | Medium | Frame-order assumption: `HostState.Fill` uses the camera's look from `MainCameraControl.OnUpdate`, which must run between `LinkDriver.Update` and `LateUpdate`; nothing enforces it. | `HostState.cs:59-60`, DESIGN "Frame" | Publish the HostState from the camera postfix, or set `DefaultExecutionOrder` | Game |
| L11 | Medium | DevHarness: an exception inside an inner coroutine leaves `busy` true forever; `load` accepts any slot (CLAUDE.md forbids touching slot0000/0001, only `save` is guarded). | `DevHarness.cs:171,175,378-388` | Guard `load` like `save`; `try/finally` around `busy` | Game (small) |
| L12 | Low | `paceFrame` runs uncapped while the host is stalled (spins, multiplies L3). Stale ghost-terrain results can overwrite newer ones; Sable plot sections aren't re-streamed after a host restart; `freezeOnUnknownTerrain` freezes items and projectiles too; `configureWorld` resets `doMobSpawning` on every start. | `SubClient.java:414-435`, `GhostTerrain.java:104-108`, `SubLevels.java:97`, `SubCraft.java:275-296` | Per item | Mostly Cloud after extraction |

### 3.3 Rendering, world, audio, HUD (host)

| # | Severity | Problem | Evidence | Fix | Verify |
|---|---|---|---|---|---|
| R1 | High | **A throwing render handler becomes a poison message.** `DrainRender` releases the tail only after the loop; if `Handle` throws, the same message is re-read every frame and the render ring stalls for good (the guest then fills it). Combined with P1 this is the most likely way for one bad message to end a session. | `LinkView.cs:404-418`, `LiveWorld.cs:211` | Consume the message before dispatch; catch, count and log per type | Cloud |
| R2 | High | **Bake cells larger than a page overflow.** `BakeCache.Allocate` clamps the cell to 1024x1024 but `AlbedoBake.FillCell` writes the full sprite area: neighbouring cells are corrupted, then `IndexOutOfRange` is thrown before `cells[k]` is stored, so the next frame allocates again (and in `BuildScene` the throw skips the rest of `Update`). Any quad spanning > 1024 texels triggers it (tiled UVs, HD packs). | `BakeCache.cs:39-43,66-70`, `AlbedoBake.cs:137-160` | Clamp the fill to the allocated rect | Cloud |
| R3 | High | **Box colliders are tessellated inside-out.** The protocol says ColTri winding is counter-clockwise seen from outside after the mirror; `BoxFaces` produce the opposite (checked by hand and numerically: 0 of 12 box triangles outward, while ellipsoids and terrain are). The voxelizer fills by winding, so BoxCollider props and the bounds fallback for unreadable meshes don't become solid ghost terrain. Negative-scale transforms aren't handled for any shape. | `CollisionHarvester.cs:705-723,550`, `Voxelizer.java:278-310`, header `ColTri` comment | Reverse the face table (and swap on negative determinant), in a tested Unity-free tessellator | Cloud (math), Game (mobs on box props) |
| R4 | High | **Minecraft's own colliders crowd terrain out of the harvest.** LiveWorld puts block BoxColliders on layer Default, inside the player's collision mask; the harvester's 512-entry `OverlapBoxNonAlloc` buffer fills with them before `Wanted` filters them, and saturation (`hits == overlap.Length`) isn't detected. Next to a big build, terrain triangles drop out of a section and the signature flaps. | `LiveWorld.cs:349-358`, `CollisionHarvester.cs:24,323` | Own layer for McGeometry excluded from the query; detect saturation and grow | Game |
| R5 | Medium | **Per-frame garbage on Mono's Boehm GC** (worse under Rosetta): the pending scene buffer is nulled after each build so it is re-allocated every frame; `Fill` allocates lists, a SortedDictionary and per-batch arrays; a capturing lambda per drained message; `RecalculateTangents` every build although `_BumpMap` is null. | `LiveWorld.cs:211,283-290,461-464,546-562,622`, `MaterialFactory.cs:144` | Reuse buffers; mesh arrays via `SetVertexBufferData` | Game (frame time) |
| R6 | Medium | **Never-freed host resources.** `BakeCache` cells/pages, `LiveWorld.textures`, materials (removed from the dictionary but kept by `MaterialFactory.All`, with their textures), `TerrainMeshCapture.ByMesh` (`Forget` has no callers and the patch runs even with no Minecraft), harvester `sent`/`meshCache` (up to 4096 meshes), sound PCM until relink, hand meshes on respawn, SceneBuilder assets on `loaddump`. Sections, lights and colliders persist into the menu and the next save (DontDestroyOnLoad root; only link loss clears them). | `BakeCache.cs:9`, `MaterialFactory.cs:22,162`, `TerrainMeshCapture.cs:53,62-72`, `LiveWorld.cs:183-195,441` | A host `Session` owning these, disposed on link down, scene change and save load | Game |
| R7 | Medium | One missing material hides the **whole** section (`Renderer.enabled = complete`), and `incomplete` rebuilds every incomplete section every 2 s outside the frame budget; `MaterialFactory.Template` re-scans `FindObjectsOfTypeAll` on every miss. `RebakeAll` runs synchronously inside one message (resource reload = multi-second freeze). | `LiveWorld.cs:196-205,252,455-457`, `MaterialFactory.cs:69,93` | Per-submesh fallback; budgeted rebuild queue | Game |
| R8 | Medium | Light positions on rotated/scaled sub-levels add a world-axis offset (`position + at`) instead of `TransformPoint`. Sub-level colliders are static colliders moved every frame (no kinematic Rigidbody). | `LiveWorld.cs:327,85-97`, `BlockLights.cs:20` | `TransformPoint`; kinematic body on the node | Game |
| R9 | Medium | Terrain with an unreadable, uncaptured collision mesh is sent as **open** (the player falls through) instead of "not ready". Biomes for a section are marked sent even when the ring was full. `Harvester.Reset` ignores a failed `ColClear`. | `CollisionHarvester.cs:57,377,432,653-679` | Treat as not ready; check write results | Game |
| R10 | Medium | **No world identity.** `HostState.worldId` is hard-coded 1, so Minecraft can't tell two Subnautica saves apart (Phase 5's "one save" needs it). | `HostState.cs:51` | Hash of the save slot / session id | Game |
| R11 | Low | Hand and dynamic scene stay drawn after link loss when no sections exist (`ClearAll` only runs with sections); the overlay ignores `BottomUp`; signature euler rounding flips near 0/360 (resends); vertex alpha dropped by colour quantization (particles don't fade); lights spawn enabled until the next 0.5 s budget pass; `partialAlpha` key collides for 4096-wide textures. | `LiveWorld.cs:185,639`, `OverlayView.cs:64`, `CollisionHarvester.cs:495-497`, `AlbedoBake.cs:164` | Per item | Mostly Game |

Host-side seams found by the review (pure code currently inside Unity classes): a render-message
decoder (`LiveWorld.cs:239-363,461-484`; `DumpReader` claims to share it but doesn't, `DumpReader.cs:11`),
a mesh builder (`Fill`, `LiveWorld.cs:543-599`: mirror, winding flip, double-sided duplicates, flat
normals, translucent->cutout), section keys (duplicated at `LiveWorld.cs:367-370` and
`CollisionHarvester.cs:69,337`), the light budget, the collision tessellator and payload writers
(`CollisionHarvester.cs:355-433,526-753`, `DryVolumes.cs:46-72`), `ClassifyTerrain` (already pure,
`CollisionHarvester.cs:606-626`), sound `extra` decoding and the muffle glide
(`SoundBridge.cs:166-192`), and the HUD dial mapping (`SurvivalDials.cs:24-52`).

### 3.4 Ownership boundaries and coupling

- **Statics everywhere.** Guest: ~110 mutable static fields; `SubClient` (20+ statics) is reached
  from 14 files including mixins and compat. Host: `LinkDriver.Instance` service locator,
  static state in PlayerPuppet, CharacterCamera, InputCapture, CreatureLink, GrabController,
  MobStandIns, SoundBridge, TerrainMeshCapture. Consequence: nothing can be constructed twice
  (tests), reset is scattered (`LinkDriver.cs:64-75`, plus each class's own idea of "reset"),
  and two features can't be worked on without touching the same hub files.
- **The unit conversion is hand-written ~8 times on the host** (`-z` in `PlayerPuppet.cs:52,100`,
  `CreatureLink.cs:87,159`, `GrabController.cs:56`, `MobStandIns.cs:53`, `StateDump.cs:116`,
  `LiveWorld.cs:323,356`) while `Coords.ToMc/ToUnity` exist and are tested but unused outside
  tests. Damage scaling, heal sign and grab flags are likewise spread over patches.
- **Guards disagree.** Damage goes to Minecraft when `McLinked && HaveMc && McInWorld`, heals when
  `SurvivalDials.Active` (which also needs `UnifiedHud`), kills when `McLinked`
  (`CreatureLink.cs:169,206,224`): with `UnifiedHud=false` damage goes to Minecraft but medkits
  heal Subnautica's unused health.
- **Hub files.** Every feature adds a line to `LinkDriver.Update`, `SubCraft.java`,
  `SubCraftClient.java`, `DevHarness`'s switch (45 cases, 678 lines), both `Proto` files,
  `layout_dump.cpp`, `fake_host.py`, `DEVLOG.md` (top) and `TESTING.md` (top). Those are exactly
  the files two parallel sessions collide on.
- **Commits are features, not steps.** Most of the 40 commits carry a whole phase or a protocol
  version across both games and docs; hard to review, bisect or revert.
- **Naming:** `SubCraft.Player` namespace vs `global::Player`; `SubCraft.HostState` class vs
  `LinkView.HostState` struct.

### 3.5 Mac/Windows parity

| Problem | Evidence | Verify |
|---|---|---|
| Python tools ignore `SUBCRAFT_DIR` (Java and C# honour it); TMPDIR-unset fallback differs (Java `java.io.tmpdir`, others `/tmp`); Python raises without `LOCALAPPDATA` | `fake_host.py:63-69`, `Platform.java:126-146`, `Platform.cs:57-77` | Cloud |
| Host `SetLength` on a mapped file: SIGBUS risk on macOS, exception on Windows (P2) | `HostLink.cs:25-29` | Game |
| `sn_dev.sh start` overwrites Unity prefs with no backup when the plist is missing (the ps1 twin backs up first) | `sn_dev.sh:13-31` vs `sn_dev.ps1:25-33` | Mac |
| `sn_restart.sh/.ps1` report success after a failed build (`|| true`, no pipefail; `ErrorActionPreference Continue`) and always exit 0 | `sn_restart.sh:7,15-17`, `sn_restart.ps1:8,24-26` | Partly cloud |
| `check_layout.ps1` prints no diff; `cl` quoting breaks with a space in TEMP | `check_layout.ps1:14-22` | Windows |
| Windows QPC clock path has no test (the clock test returns early off-Mac); KeyMap lacks Windows/Menu/AltGr keys | `LinkTests.cs:385`, `KeyMap.cs:151-155` | Cloud (scaling math) |
| `mc_dev.ps1` pins a JDK patch path | `mc_dev.ps1:10` | Windows |

### 3.6 Memory on an 8 GB Mac

| Where | Cost | Evidence |
|---|---|---|
| `DynamicCapture.Source` native leak (L3) | ~165 MB/hour, outside the heap | `DynamicCapture.java:63` |
| `VboCapture` heap copies (L4) | up to 512 MB | `VboCapture.java:39` |
| Per-frame `CaptureBuffer`s from 64 KB with doubling copies; scene bodies up to 12.8 MB: humongous G1 allocations every frame | | `CaptureBuffer.java:18,152-158`, `DynamicCapture.java:318` |
| `AtlasAnimator` reads the whole atlas into a new direct buffer 4x/s (4-64 MB each, freed by the Cleaner) | | `AtlasAnimator.java:65`, `TextureGrabber.java:33` |
| `Voxelizer` ~4 MB scratch per call (humongous), 264 KB per result; results kept for chunks that never load | | `Voxelizer.java:107-113`, `GhostTerrain.java:104-108,203-206` |
| `TriStore` (+ cached `Columns` >= 64 KB per section) never evicts: hundreds of MB over a long swim | | `TriStore.java`, `Voxelizer.java:67,251` |
| Host: see 3.3 (BakeCache, textures, TerrainMeshCapture) | | |
| Host: `pendingScene` buffer is nulled after each build, so it is re-allocated (next power of two) every frame | `LiveWorld.cs:283-290,461-462` |

### 3.7 Test gaps

- No CI. Nothing runs automatically; a cloud session can't even compile the host today (fixed
  tonight, 6.1).
- No cross-language test: the Java writer is never run against the C# reader on the same bytes.
- Above the link layer only `Voxelizer`, `TriCollider` and `SubBiomes` (guest) and
  `DumpReader`, `AlbedoBake`, `BakeCache` (host) are tested. Everything else is logic welded to
  game types: the teleport/hold gate, puppet state, input routing, damage translation, collision
  message parsing, render message decoding, scene encoding, section scheduling, time sync, sound
  packing.
- The fakes test each other (6/6 on Linux tonight) but `fake_minecraft` never consumes the
  collision ring, answers the command box or collides with anything (its "jumped onto the step"
  passes because Space raises y without limit, `fake_minecraft.py:103-104`).
- `capture_dump.py` has no fixtures (`*.scdump` is git-ignored) and crashes on several malformed
  inputs (`--help` as a file name, unknown material, normal 7, empty preview).

---

## 4. Target architecture

### 4.1 The case in one paragraph

The current shape (one hub class per side, statics everywhere, protocol hand-mirrored in four
languages, game calls and translation logic in the same methods) was the right way to get through
phases 0-4 in three days with one author. It is the wrong shape for the next stage: more mods on
both sides, a second platform, several people or sessions in parallel, and work done in the cloud
without the games. **I recommend a big refactor, done as a sequence of small steps, not a
rewrite.** The behaviour that took three days of in-game verification to get right (collision,
voxelizer, camera, capture) stays; what changes is where it lives and how it is reached. The key
moves are: (1) generate the protocol instead of mirroring it; (2) split each side into a
game-free core and a thin game edge, so most code builds and is tested anywhere; (3) organise both
sides by feature, with one registry instead of hub files; (4) make the link a session object with
an explicit lifecycle instead of global state; (5) put every check in CI.

### 4.2 Module boundaries

```
                     protocol/  (source of truth + generator)
                     subcraft_protocol.h ──► protogen ──► layout.json, fingerprint
                         │                     ├─► guest  Proto.java        (generated)
                         │                     ├─► host   Proto.cs          (generated)
                         │                     └─► tools  subcraft_proto.py (generated)
                         ▼
 ┌────────────────────── guest-neoforge ──────────────────────┐   ┌──────────────────── host-subnautica ─────────────────────┐
 │ core/  (plain Java 21, no net.minecraft, no NeoForge)       │   │ Core/  (netstandard2.0-safe C#, no UnityEngine, no game)  │
 │   link/      LinkFile, Seqlock, ByteRing, RecordRing,       │   │   Link/      same primitives as the guest, same tests     │
 │              TripleBuffer, CommandBox, Session              │   │   Wire/      typed, bounds-checked message codecs         │
 │   wire/      typed codecs: ColTris, RenSection, RenScene... │   │   Features/  pure logic per feature:                     │
 │   features/  pure logic per feature:                         │   │              puppet state machine, input routing table,  │
 │              teleport gate, collision consumer, voxelizer,   │   │              damage translation, tessellator, mesh       │
 │              tri collider, section scheduler, scene encoder,│   │              builder, bake cache, light budget, dials... │
 │              sound packing, time sync, dry volumes diff...   │   │   Ports/     IClock, ILog, IWorldQuery... (interfaces)   │
 │   ports/     interfaces the edge implements                  │   ├──────────────────────────────────────────────────────────┤
 ├──────────────────────────────────────────────────────────────┤   │ Edge/  (Unity + Subnautica + Harmony, thin)              │
 │ edge/  (Minecraft + NeoForge + mixins, thin)                 │   │   one MonoBehaviour driver, ordered feature list          │
 │   SubCraftMod: builds a Session per (link, world)            │   │   Harmony patches forward to features, no logic           │
 │   mixins forward to features, no logic                       │   │   adapters implement Core ports                           │
 │   adapters implement core ports (WorldBlocks, Renderers...)  │   │ Dev/   harness commands registered per feature            │
 └──────────────────────────────────────────────────────────────┘   └──────────────────────────────────────────────────────────┘
          ▲ unit tests (JUnit), contract tests                               ▲ unit tests (xunit, net10), contract tests
          └──────────── golden byte fixtures in protocol/fixtures/ ─────────────┘   (both sides read and write the same files)

 tools/  sclink.py (generated proto + link primitives) ──► fake_host, fake_minecraft, mc_cmd, capture_dump
 CI      check_layout, protogen --check, gradle test, dotnet test, host compile check (GameLibs), python tests
```

Rules that keep the boundary honest:

- Core never imports game types. Enforced mechanically: on the host, Core is compiled into the
  net10.0 test project, which has no Unity references, so a Unity type in Core is a build error;
  on the guest, a test fails when a file under `dev/subcraft/core/` imports `net.minecraft`,
  `com.mojang` or `net.neoforged` (added tonight, 6.x).
- Edge code is allowed to be untested only because it is thin: a mixin or Harmony patch calls one
  feature method with plain values; an adapter converts a game object to a plain value.
- Each feature is a folder with the same shape on both sides (`features/combat`, `Features/Combat`),
  plus its harness commands and its fixtures. Two people working on combat and rendering touch
  disjoint files.

### 4.3 The link as a session

- `LinkFile` maps the file (Platform layer as today). A `Session` is created when both ends see
  each other (pid + fingerprint match) and owns every cache that belongs to "this host and this
  Minecraft and this world": TriStore, MaskStore, section maps, texture ids, proxies, bake cache,
  sounds. Session end (heartbeat lost, pid change, world unload, save load, resource reload)
  disposes it in one place. Today that state is spread across ~25 static classes with separate
  `Reset` paths, several missing (3.2 L6, 3.3 R6).
- Each ring carries a generation number written by its producer on session start; consumers
  ignore messages of an older generation. This closes the restart races (P7, L5) without timing
  assumptions.
- A dropped or malformed message is never fatal: codecs validate every length, drains consume a
  message before dispatching it, handlers run under a per-feature fault barrier that logs once and
  disables only that feature.

### 4.4 Protocol generation and versioning

- **Source of truth stays `subcraft_protocol.h`** (MISSION rule 2). A stdlib Python generator
  (`tools/protogen.py`) parses the header's restricted C++ subset (constexpr, enums, POD structs
  of fixed-width fields and arrays), computes offsets, and writes `layout.json`, `Proto.java`,
  `Proto.cs` and `subcraft_proto.py`; `layout_dump.cpp` (also generated) compiles the header and
  proves the generator's offsets equal the compiler's. CI fails if any generated file is stale.
  Hand-written mirrors and the hand-maintained field list in `layout_dump.cpp` go away.
- **Fingerprint, not version bumps.** The generator hashes the canonical layout into
  `kLayoutHash`; the host writes it in the header and Minecraft refuses a mismatch (it re-checks on
  every host pid change, P2). `kVersion` stays as a human label. Two branches that change the
  protocol no longer both claim "v26"; they get different hashes, and a merge regenerates one.
- **Feature-scoped message ids.** Each feature owns a block of message kinds (e.g. render 0x0100,
  sound 0x0200, combat 0x0300) so adding a message is a local change.
- **Golden fixtures.** For each message type, `protocol/fixtures/<type>.bin` holds an encoded
  example; Java and C# tests decode it and re-encode it byte-identically. That is the
  cross-language test the project lacks today.
- **Argument for changing a fixed rule (for Sean):** MISSION's "header is the source of truth"
  could become "a schema file is the source of truth and the header is generated". A small
  declarative schema (`protocol/subcraft.schema`, one line per field) is easier to generate four
  languages from than C++, would let the C++ header be just another output, and would remove the
  last hand-written mirror. Not done tonight (the rule is fixed); the header parser above gets 90%
  of the benefit without changing it.

### 4.5 Each game side as layers

Guest (per frame / tick):
`mixin or event` -> `edge adapter` (reads Minecraft into plain values) -> `feature` (core logic,
returns plain decisions/messages) -> `edge adapter` (applies decisions to Minecraft) and
`Session.send(message)` -> generated codec -> ring.

Host: one `HostDriver` MonoBehaviour runs an ordered list of `IFeature { Frame(ctx); LateFrame(ctx);
OnSessionStart/End }` with a fault barrier per feature; Harmony patches call
`Features.X.OnSomething(plain values)`. The frame-order assumption between the camera and
HostState (L10) becomes explicit: the camera feature publishes the look itself.

Platform: `Platform` stays the only OS switch on each side (paths, clock, process). Tools get the
same rules from the generated Python module, so `SUBCRAFT_DIR` and the TMPDIR fallback can't
drift again (3.5).

### 4.6 How the no-game tests grow

1. **Unit** (core): every feature's logic with plain inputs. Today's Voxelizer/TriCollider tests
   are the model; add the puppet state machine, input routing, damage translation, collision
   consumer, section scheduler, scene encoder, mesh builder, tessellator, bake cache edge cases.
2. **Contract**: generated constants checked against `layout.json` on all three languages;
   golden fixtures round-tripped on both sides; the Python module checked too.
3. **Link integration**: the real Java `LinkView` and the real C# `LinkView` against one file in
   one CI job (a tiny Java main writes a scripted session, a dotnet test reads it, and back),
   replacing tests that copy the writer into the test.
4. **Replay**: record the rings for a minute of real play on Sean's machine
   (`tools/record_link.py`), commit the recording, and replay it into the core decoders and
   schedulers in CI. Capture dumps (`*.scdump`) become fixtures for the host mesh builder and
   `capture_dump.py`.
5. **Fakes**: `fake_host` and `fake_minecraft` share `sclink.py`; `fake_minecraft` consumes
   collision and the command box, so the pair is a real end-to-end protocol test in CI.
6. **Compile checks**: the host plugin against `Subnautica.GameLibs` (tonight), the guest as today.
   On a machine with the games, `smoke.py` stays the top of the pyramid.

### 4.7 Parallel work without collisions

- Feature folders + feature-scoped message ids + generated mirrors remove most shared hot files.
- Registries instead of switches: harness commands, link features and HUD layers register
  themselves; adding one doesn't edit `DevHarness`'s 45-case switch or `LinkDriver.Update`.
- Docs: `docs/devlog/YYYY-MM-DD-<topic>.md` (one file per session, an index generated or appended
  at the bottom) instead of everyone prepending to the top of `DEVLOG.md`; same for TESTING items.
- CI on every push, so a cloud session knows it broke nothing it can't run.
- Small commits: one feature step per commit, protocol changes in their own commit.

## 5. Migration path

Each step ships on its own, keeps both games working, and is marked **Cloud** (verifiable with
the cloud checks: Gradle tests, dotnet tests, layout check, host compile check, Python) or
**Game** (needs an in-game test; the test plan is given). Order is by value per risk.
Steps marked ✅ were done tonight (section 6).

| # | Step | Kind |
|---|---|---|
| M1 | CI workflow running every cloud check; host compile check against Subnautica.GameLibs | Cloud ✅ |
| M2 | Layout coverage: every enum, flag and payload field emitted by `layout_dump.cpp` and checked in Java, C# and Python | Cloud ✅ |
| M3 | Ring drains validate peer lengths; consume-before-dispatch so a throwing handler can't wedge the render ring | Cloud ✅ |
| M4 | Host render-message decoder (Unity-free, every count checked); `LiveWorld` calls it | Cloud ✅ (decoder tests) + Game (sections, scenes, lights, colliders still draw: `smoke.py` + look shots) |
| M5 | Minecraft re-validates the header on every host pid change | Cloud ✅ |
| M6 | Overlay slot resync on (re)connect (protocol v26) | Cloud ✅ + Game: restart Minecraft twice with Subnautica running, watch the HUD for tearing |
| M7 | Bake cell overflow clamp | Cloud ✅ |
| M8 | Collision tessellator extracted, box winding fixed | Cloud ✅ + Game: `subcraft whysolid` on a block inside a box-collider prop (crates, lockers in the Aurora / lifepod) is now solid; player still walks on them |
| M9 | Guest collision-message parser extracted (negative counts) | Cloud ✅ |
| M10 | Guest `DynamicCapture` shared byte buffer (native leak) | Cloud ✅ (compile) + Game: Minecraft RSS flat over 30 min linked |
| M11 | Tools: one link-path rule, `mc_cmd` refuses while a command is pending and checks the header, Python tests | Cloud ✅ |
| M12 | Core/edge split, guest: move pure classes into `dev.subcraft.core` with the import guard test | Cloud (started tonight ✅ with the guard) |
| M13 | Core/edge split, host: `src/Core` folder compiled into the tests; move Coords/KeyMap/Link/DumpReader/Bake there | Cloud |
| M14 | `protogen.py`: generate `Proto.java`, `Proto.cs`, `subcraft_proto.py` and `layout_dump.cpp` from the header; layout fingerprint in the header | Cloud |
| M15 | Golden fixtures + Java↔C# link integration job in CI | Cloud |
| M16 | `Session` objects on both sides (one owner per cache, dispose on link down / world unload / save load / resource reload) | Cloud (logic) + Game: link down/up, leave to menu and load another save, F3+T; no stale sections, sounds or terrain |
| M17 | Puppet state machine in host core (fixes L1 frozen food, kinematic) | Game: ride the Seamoth, get out, kill Minecraft; food/water drain again in Subnautica, the diver moves |
| M18 | Small host fixes: quickslot restore on unlink (L2), attacker id (L8), Esc release (L7), `McScreenInput` reset, `load` slot guard (L11), `ColClear` result, biomes resend, unreadable terrain = not ready (R9) | Game: per item, listed in TESTING.md |
| M19 | McGeometry colliders on their own layer excluded from the harvest; saturation check (R4) | Game: build a 10x10x10 cobblestone block next to rock, `colprobe` shows terrain triangles still harvested |
| M20 | Per-feature fault barrier in the host driver; per-class Harmony patching | Game: inject a throwing feature with the harness; the rest keeps running |
| M21 | Feature registry for harness commands (split `DevHarness`), host driver feature list | Cloud (compile) + Game (smoke) |
| M22 | Gate every guest hook on SubCraft world + linked (VBO upload, setSectionDirty, capture while unlinked, input mixins) | Game: play a normal Minecraft world with the mod installed: no captures, normal input |
| M23 | Big textures in strips (atlas > 32 MB) | Cloud (guest) + Game (a 4096² modpack atlas draws) |
| M24 | Memory: pooled CaptureBuffers, atlas readback reuse, ThreadLocal voxelizer scratch, TriStore eviction, host buffer reuse and `SetVertexBufferData` | Game: RSS and frame time over a 10-minute swim, both processes |
| M25 | Ring generations (restart races), world id from the save | Cloud (logic) + Game (restart while streaming) |
| M26 | DEVLOG/TESTING per-session files | Docs |

## 6. What changed tonight, what is only proposed

Filled in as the commits land; the ✅ marks in section 5 are planned for tonight and are
confirmed (or removed) here at the end of the session.
