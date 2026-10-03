# SubCraft dev log

Newest first. One entry per session (MISSION.md rule 11).

## 2026-10-03 — Session 11 (cloud, overnight, no games): architecture review and first steps

Sean asked for a from-scratch look at the architecture and for moves toward it that can be proven
without the games. Branch `arch-review-2026-10-03`; the review is `docs/ARCH-REVIEW.md`.

**Done**
- Review: how the system works (threads, rings, ownership), divergences from MISSION/DESIGN,
  ranked problems with file:line evidence, a target architecture (generated protocol, game-free
  core + thin game edge on both sides, per-feature folders, sessions owning caches, CI) and a
  26-step migration path, each step marked cloud-verifiable or needing an in-game test. Five
  sub-agents read the code in parallel; every finding used was re-checked by hand.
- **Cloud checks:** `tools/check_cloud.sh` and GitHub Actions; the host plugin now compiles in
  the cloud against NuGet's `Subnautica.GameLibs` (`host-subnautica/compile-check`).
- **Protocol coverage:** `layout_dump.cpp` is generated from the header; every enum member and
  field is checked in Java, C# and Python; first cross-language fixtures (`protocol/fixtures`).
- **Bugs fixed (all with tests unless noted):** peer lengths trusted by both byte-ring drains (a
  negative length looped, a bad host render handler wedged the render ring forever); no protocol
  re-check after a host restart; overlay slots aliasing after a Minecraft reconnect (**protocol
  v26**: front slot in the CAS'd state word, checked by an exhaustive interleaving model; a first
  design with a separate word failed that model); bake cells bigger than a page overwriting their
  neighbours; box colliders and x/z capsules tessellated inside out (voxelized as open); a native
  `ByteBufferBuilder` leaked per capture source per frame (compile + reasoning only); a restarted
  host's collision reset racing the server thread (per-instance epochs); Python tools ignoring
  `SUBCRAFT_DIR`; `mc_cmd` returning a previous command's reply.
- First game-free classes: host `src/Core` (RenderWire, CollisionWire, Shapes), guest
  `dev.subcraft.core` (TimeSync, wire.CollisionWire), with `GameFreeCodeTest` guarding them.
- Tests: Java 41 -> 58, C# 42 -> 66, Python 0 -> 13. Fakes 6/6 on v26.

**Found, not fixed (needs the game; ARCH-REVIEW 6.2):** Subnautica's food/water can stay frozen
after unlinking (puppet re-captures `freezeStats` on every activation), the quickslot bar stays
hidden after Minecraft dies, bite attacker ids are the component's not the GameObject's (contradicts
session 9's "mob by creature_proxy": check), Esc release lost, Minecraft's own colliders crowd the
harvester's overlap buffer, per-frame garbage and never-freed resources on the host, `VboCapture`
copying every VBO in any world, the atlas message throwing above 32 MB.

**Unverified:** everything that runs inside either game. TESTING.md has the in-game checks at the
top. `versions.md`: the code needs Subnautica.GameLibs 82304, not 71288 as recorded.

**Notes:** session 10's entry below sits under session 9 and its first paragraph was spliced into
it; left as is. CLAUDE.md gained a "Cloud sessions" section.

## 2026-10-02 — Session 9: Windows bring-up, Seamoth fix

**Done**
- Windows PC set up: Subnautica (Steam) + BepInEx 5.4.23.5 win_x64, JDK 17 for Gradle, .NET 10,
  ilspycmd, LLVM. `./gradlew build test` and the 41 host tests pass; the plugin builds and deploys
  to `Steam\steamapps\common\Subnautica\BepInEx\plugins\SubCraft`.
- PowerShell twins of the shell tools: `tools/mc_dev.ps1`, `sn_dev.ps1` (Unity screen prefs live in
  `HKCU\Software\Unknown Worlds\Subnautica`, backed up to JSON), `sn_restart.ps1`,
  `check_layout.ps1`. `sn_cmd.py` and `fake_host.py` use `%LOCALAPPDATA%\SubCraft` / `tasklist`.
- **Clock bug (Windows only):** Unity's Mono `Stopwatch` is not the raw performance counter: the
  host's "now" was ~36 h behind Java's `nanoTime`, so Minecraft never took the host link.
  `Platform.MonoNanos` now P/Invokes `QueryPerformanceCounter` (overflow-safe scaling, as HotSpot).
- **Seamoth (Sean's report):** boarding a vehicle disables `PlayerController`, whose motor makes
  the player's rigidbody kinematic (`UnderwaterMotor.SetEnabled`); `PlayerPuppet.Release` then
  restored the pre-takeover `isKinematic = false`, so a physics body hung off the moving vehicle:
  camera lag and broken rotation. Release now restores it only while the controller is enabled.
  While piloting (any `Player.Mode` but Normal) keys no longer go to Minecraft, and Minecraft's
  player is teleported along with the vehicle (every 0.5 s when > 2 m off), so leaving the
  vehicle hands control back at once instead of after a long teleport.
- Harness: `pilot` (board the nearest vehicle), `eject`; the dump has `player.mode`.
- **Phase 4 started — time:** the host sends `DayNightCycle.GetDayNightCycleTime()` (0 midnight,
  0.25 sunrise, 0.5 noon, 0.75 sunset; the raw `GetDayScalar` has the sun up 0.125..0.875) and the
  server sets the SubCraft world's day time to match every tick. Verified: noon -> 6035, midnight
  -> 18108.
- **Phase 4 — unified HUD (protocol v18, `kHostUnifiedHud`):** prefixes on
  `uGUI_HealthBar/uGUI_FoodBar.SetValue` show MC health/hunger on the 0-100 scale while a Minecraft
  player is in world; the water dial fades out (CanvasGroup); Minecraft cancels its health, armour,
  food and air layers (`RenderGuiLayerEvent.Pre`). Config `Hud.UnifiedHud`. Subnautica's food and
  water now stay frozen for the whole link (they thawed while piloting). Verified in survival:
  damage 6 -> dial ~87% after regen, hunger dial 19/20, MC bars gone. Open: the water dial's empty
  ring stays; the low-health pulse and damage punch still follow Subnautica's LiveMixin.
- **Sean's new goals (MISSION.md 3.4):** the Minecraft character fully replaces Subnautica's
  (camera, third person, no Subnautica first-person animation); sound is a mix (Subnautica
  ambience and music, Minecraft's gameplay sounds through Subnautica's audio).
- **Character camera (protocol v19, `McState.handFovDeg` in the old pad):** `CharacterCamera`
  wraps `MainCameraControl.OnUpdate` (prefix restores the Camera's own local pose; postfix zeroes
  Subnautica's bob on the control transform, records the first-person eye pose for the look and
  the puppet, then places the Camera). F5: behind `eye - f*d` / in front `eye + f*d` looking back,
  d = Minecraft's collision-reduced distance. View bobbing: Minecraft multiplies its projection by
  B (bobView, view space); the camera gets `S B^-1 S` in local space (unit-tested formula); the
  hand, a child of the camera, then lands exactly where Minecraft draws it, and is scaled by
  `tan(fov/2)/tan(handFov/2)` for Minecraft's hand projection. FOV through `SNCameraRoot.SetFov`
  (restored on unlink). Diver hidden whenever a Minecraft player is in world. Verified: back and
  front cameras 4 m off the eye, player model captured (642 vertices), hand off and back on;
  underwater FOV 60 = 70 x 0.857.
- **Sound mix (protocol v20):** guest `SoundBridge` cancels every `PlaySoundEvent` while linked
  (Minecraft silent), drops MUSIC/AMBIENT/WEATHER/RECORDS and streamed sounds, resolves the rest,
  ships each .ogg once (`kRenSound`, render ring) and sends `kEvSoundPlay/Update/Stop` (event
  ring; file id + flags in `flags`, range/pitch packed in `extra`). Tickable and looping sounds are
  ticked by the bridge; `SoundEngineMixin` (@Inject) forwards stop/stopAll and answers isActive.
  Host `Audio.SoundBridge`: FMOD core sounds from memory (Ogg decoded by FMOD), channels in a
  "SubCraft Minecraft" channel group under `bus:/master/SFX_for_pause/PDA_pause/all/SFX` (volume,
  PDA pause), linear 3D roll-off to Minecraft's range. Finding: Subnautica's underwater muffling is
  inside its events (FMOD parameters like `depth`), not on buses: the SFX chain's LOWPASS/EQ stay at
  22 kHz at 11 m. So our group has its own LOWPASS gliding to 900 Hz while the camera is in open
  water (`Ocean.GetDepthOf(camera) > 0`, not inside). Harness: `fmod` (bus list + counters),
  `fmodchain` (effect chain). Verified: 6 plays, 5 files, 0 FMOD failures, music dropped; cutoff
  22000 in air, 900 at depth, back to 22000.
- **Sean: FOV stutters while flying/moving.** `PDACameraFOVControl.Update` eases the FOV toward
  Subnautica's setting every frame; we only re-applied Minecraft's FOV when it changed, so the two
  alternated whenever Minecraft's FOV moved. Now that script stands aside while Minecraft drives
  (not with the PDA open) and the FOV is set every frame. Verified: steady 66 swimming (70 x 0.857
  x 1.1 flying), 75.9 sprint-swimming, no drift toward 60.
- **Sean: the lifepod glitches.** Two causes. (1) Regression from the camera work: the puppet's
  eye was a cached world position, so when Subnautica moved the player itself (hatch, leaving a
  vehicle, respawn) Minecraft was teleported back to the old spot and yanked the player after it;
  the eye is now kept relative to the player. (2) The bobbing pod's colliders were re-captured on
  every 5 cm / 1 degree of motion and Minecraft re-voxelized them several times a second; while
  linked the pod is held still (`LifepodAnchor`: kinematic). Verified with harness `intopod`:
  inside, walking, stable for 10 s, 2 sections voxelized (first load only).
- **Combat (protocol v21).** Host `Combat.CreatureLink` writes the creatures within 40 m (alive,
  solid-collider bounds, hostile = AggressiveWhenSeeTarget) into the creature table every 0.1 s.
  Guest `combat.Proxies` keeps one `CreatureProxy` per record (invisible LivingEntity, NoopRenderer,
  no physics/gravity/save, box from the table on both sides, synced host id). `hurt()` sends
  `kEvHitCreature` (damage, knockback away from the source, +0.5 when sprinting) instead of losing
  health; only hits with a source entity or explosions count (the proxies were drowning in
  Minecraft's water and that went to the fish). Host: `LiveMixin.TakeDamage(x5, dealer = player)`
  + Rigidbody shove. The other way: a prefix on `LiveMixin.TakeDamage` for the player computes
  Subnautica's damage (`DamageSystem.CalculateDamage`: suits count), cancels it and sends `kInHurt`
  (x0.2, attacker id); Minecraft hurts the server player with `mobAttack(proxy)`, so knockback, hurt
  direction and the hurt sound are Minecraft's. `LiveMixin.Kill` on the player (suffocation, Cyclops,
  console) kills Minecraft's player too (one death). Hurt tilt and death roll: `McState`
  hurtTiltDeg/hurtDirDeg/deathRollDeg, applied as `S H^-1 S` after the bob. Config `Combat.*`.
  Guest logs every damage to the player with its source. Verified: `/damage ... by @p` on a
  RabbitRay's proxy took it 0.70 -> 0.40 (6 x 5 = 30 of 100); a biter's bite arrived as "1.4 damage
  (mob by creature_proxy)" (7 x 0.2); a Minecraft drowned (a zombie converted underwater) fought the
  player. Harness: `creatures`, `hurtplayer`; Minecraft `subcraft creatures`.
- **Mobs in the ecosystem (protocol v22).** Mob table at 0x16100 (96 x 32 bytes, MC -> host,
  seqlock, written every server tick for mobs within 48 blocks). Host `Combat.MobStandIns`: per mob
  an invisible stand-in (BoxCollider, kinematic Rigidbody, LiveMixin with a shared LiveMixinData,
  EcoTarget type Shark like the player); `MeleeAttack.CanDealDamageTo` prefix lets creatures bite
  it; the TakeDamage prefix sends its damage as `kInHurtMob` (x0.2, attacker creature) and keeps its
  health full. Minecraft hurts the mob from the creature's proxy. Hostile mobs in the SubCraft world
  get a `NearestAttackableTargetGoal<CreatureProxy>` (priority 3, after players). Stand-ins are
  skipped by the collision harvester. Verified: two zombies (drowned) and five biters: 4 bites on
  the zombies arrived in Minecraft, the zombies landed 5 hits on creatures.

- **Polish: health.** Subnautica's player LiveMixin.health now mirrors Minecraft's (x5, never
  below 1), so the dial's own low-health pulse and its punch (`onHealDamage`) follow Minecraft;
  `LiveMixin.AddHealth` on the player (first aid kit) goes to Minecraft as a negative `kInHurt`
  (heal). Verified: 40 Subnautica damage -> -8 MC; a 25 heal -> +5 MC.
- **Polish: the water dial's ring** is part of the shared backplate (BarsPanel/BackgroundQuad;
  BackgroundDouble is the two-dial plate), so hiding the dial leaves it: Sean's choice.
- **Fixed in sn_dev.ps1:** `New-Item -Force` on the existing registry key recreated it empty, wiping
  Subnautica's registry settings on every dev start; the backup is retaken if it has no values.

- **Polish: UI sounds** (protocol v23, `kSoundUi`): Minecraft's MASTER-category sounds (menu
  clicks) play on Subnautica's interface bus, never muffled or paused by the PDA. `.gitattributes`
  keeps shell scripts LF in Windows checkouts. `fake_host.py` counts sound events quietly.
- **`tools/smoke.py`**: both-games smoke test (link, F5 behind/front/back, FOV = Minecraft's,
  sound through FMOD, a Minecraft hit on a creature, Subnautica damage on Minecraft's player,
  Seamoth board/eject, lifepod steady). 12/12 on 2026-10-02; Phase 0a `fake_host.py` 6/6.

**Phase 4 result: done** (pending Sean's play-test)

## 2026-10-02 — Session 10: mod compatibility (Sean: "complex entity rendering and full mod compat")

Sean installed Create 6.0.10 and Immersive Vehicles 24.0.0 (+ MTS Official Pack). Details and
results in compat/README.md. In short: Flywheel's backend is off while linked (Create draws
through capturable renderers: kinetics turn in Subnautica); a generic vertex-buffer capture
(`VboCapture`) replays draws that skip MultiBufferSource, through the bound shader's ModelViewMat
(Immersive Vehicles' vehicles drawn); NeoForge render-stage events fire inside the capture;
`LevelRendererMixin` priority 1500 lets other mods' renderLevel HEAD hooks run; failing renderers
back off and log their root cause (Immersive Vehicles' per-frame crash reports had Minecraft at
1-13 fps); `mc_dev.ps1 stop` saves the world first (`subcraft save`): the hard kill had been
losing placed vehicles. New debug tools: `subcraft field`, `subcraft rawdebug`, harness `mouse`.: time, unified HUD, sound mix, character
camera, combat both ways, mobs vs creatures.

**Verified in game (Windows)**: `fake_host.py` six `[ok]`; both games linked (heartbeat 5–8 ms),
W/Space, clicks, block placement, a zombie and the held block drawn; Seamoth boarded: kinematic
stays true, camera on the seat, Minecraft follows; eject -> Minecraft drives again within 0.3 s.

**Not verified:** flying the Seamoth around (no harness key path for vehicle input yet: Sean),
rendering vs the Mac in detail, `check_layout.ps1` (no C++ standard library installed: MSVC or
WinLibs needed).

## 2026-10-02 — Session 8: Phase 3 — everything dynamic (as far as this Mac can test)

**Done**
- Guest `DynamicCapture`: each frame, inside the skipped world pass, entities, block entities and
  particles render through Minecraft's own renderers into capture buffers (one per RenderType;
  particles per sheet, each drawing itself relative to the camera) -> one kRenScene relative to
  the camera. The first-person hand and held item render into view space -> kRenHand (protocol
  v17). Textures (skins, particle atlas) go once as kRenTexture. Minecraft's own hand in the
  overlay is cancelled; entity blob shadows off; ambient suspended particles (underwater specks)
  skipped; no per-frame draws while the host isn't in game. No-cull RenderTypes set RenBatch bit 8.
- `AtlasAnimator`: block atlas read back 4x a second; animated sprites that changed ->
  kRenAtlasRegion; host `BakeCache.Refill` redoes the cells using them (64 animated sprites in
  vanilla; 67-204 cells re-baked in the first seconds).
- Host `LiveWorld` generalized: any texture id with its own bake cache (all-white batches skip the
  bake), flat normals from the triangles (our vertex only knows block-face directions), back faces
  for double-sided batches, the newest scene only, hand mesh parented to Subnautica's camera.

**Found on the way**
- Held and dropped items rendered as dark glass: Minecraft draws items with a translucent
  RenderType, which picked MarmosetUBER's WBOIT glass template. Translucent batches whose texture
  has no partial alpha under their quads are now drawn as cutout.
- Sections arrived ~15 s late after a restart: ~35,000 stale per-frame scenes had queued while
  Subnautica loaded. Fixed on the guest.
- Items float up in water (Minecraft physics): the test sword sat at the surface.
- Sean was building in the dev world meanwhile (door, glowstone, more torches) and asked about
  the black hand: it was the pickaxe, drawn as glass (fixed above).

**Verified in game (docs/look/3-*)**: a zombie, the held block, the bare arm and a door lit by
Subnautica at noon and by the flashlight at night; the dropped sword visible at the surface;
flame and smoke particles; sea lantern glowing; animated cells re-baked live; `fake_host.py` all
[ok].

**Not on this machine (compat/README.md):** Flywheel backend, fallback layer, moving-structure
colliders, the Create water-wheel check. Mod-free dev profile (MISSION.md section 2).

**Open:** smoke particles are opaque dark (alpha fade lost: bake keys drop alpha); the bare arm
is bright at noon close to the camera (judge by eye); hand drawn with Subnautica's FOV, not
Minecraft's hand FOV.

## 2026-10-02 — Session 7: fast-flight freeze fixed; Phase 2 — Unity draws Minecraft's blocks

**Sean's report:** flying fast on an elytra froze him mid-air. Cause, as he suspected: Minecraft
treats unknown space as solid, and Subnautica only builds terrain collision in a 5x5x5 window of
16 m cells around the camera, so at elytra speed the sections ahead stayed unknown.

**Fix (host only):** `OctreeProbe` walks Subnautica's voxel octrees (streamed far beyond the
collision window; whole empty octrees are flagged; ids outside the octree bounds are empty; above
the sea the low-detail octrees answer when the detailed ones aren't loaded). The harvester probes
sections around where the player will be in 1.5 s and sends those with no terrain voxel within 2
blocks and no object collider as known-empty right away. The exact harvest replaces them once
built. Never-sent sections now go before the refresh round robin. Measured at 25 blocks/s: 430-480
blocks in 25-35 s with no stop except flying level into a rising seabed (real collision).

**Phase 2 done**
- Guest `SectionStreamer` + `SectionCapture`: sections Minecraft marks dirty (hooked at
  `LevelRenderer.setSectionDirty`) and sections coming into range (16 x 11 x 16 around the player)
  are captured with Minecraft's own block renderers and streamed: mesh, light emitters, collision
  boxes (new `kRenColliders`, protocol v16). A palette check (`maybeHas`) skips sections holding
  only air, water and ghost terrain; unchanged captures (hash) aren't re-sent; out of range ->
  removed.
- Host `LiveWorld`: one GameObject per section with MarmosetUBER materials on shared `BakeCache`
  pages (a cell is baked once for the whole world; 42 cells for the test hut), SkyApplier, block
  lights (nearest 24 on), BoxColliders tagged `McGeometry` (the harvester skips them).
- Harness `push` (throw/place a rigidbody, heading; reports lights).

**Found on the way**
- Magenta hut after a Subnautica restart: Minecraft re-sent everything at link-up, during
  Subnautica's load, before any MarmosetUBER template existed. LiveWorld now drains only in game,
  sections with a missing material are rebuilt every 2 s, and a missing glow variant falls back
  to the plain one.
- The dark shaded wall: Sean asked whether the black walls
  were night. The shot was at noon; the shaded side of cobblestone is dark, the sky block is
  applied (checked `_SH*`, `_SpecCubeIBL`, `_Outdoors` equal to native kelp next to it), same as
  the approved 0c look.
- Sean was playing the dev game while Claude scripted shots; agreed to hand over (TESTING note).

**Verified in game (docs/look/2-*)**
- A 6x6x6 cobblestone hut with torches beside the lifepod: noon (caustics on the roof), dusk,
  night (torches light the sand), Seamoth headlights light its wall like the rocks behind it,
  swallowed by fog at 41 m.
- Seamoth thrown at 20 m/s: stops at the wall (6.6 m), 17.4 m in open water. Peeper at 15 m/s
  bounces off the wall (-351.5 -> -348.7); 3.5 m further in open water.
- Roof blocks replaced with water: the hole appears, colliders 86 -> 88.
- `fake_host.py` on v16: all six [ok].

**Memory:** swap reached 6.7 of 7 GB with both games (Sean's session plus ours); Minecraft RSS
~1.3 GB peak.

**Open (Phase 2):** block entities (chests, signs) aren't in sections yet (dynamic draws, Phase
3); animated sprites (water, lava, fire) draw their first frame; BakeCache never frees cells;
two of the six test torches were placed in water and popped (Minecraft's own rule).

## 2026-10-01 — Session 6: Phase 1 finished — biomes, structures, saved masks

**Done**
- **Biomes (protocol v15, `kColBiomes`):** with each harvested section the host sends
  `LargeWorld.GetBiome` (2D map plus cave overrides) at the centre of its 64 cells of 4x4x4
  (Minecraft's biome resolution), names inline. The guest maps names to 21 SubCraft biomes
  (`SubBiomes`, tested: `kelpForest_Cave` -> kelp_forest, `Precursor_LavaCastleBase` ->
  precursor, unknown -> ocean). They're written into chunks the way `/fillbiome` does and
  re-sent to the client. The biome data pack comes from `tools/gen_biomes.py`: ocean / deep-ocean
  tags plus `c:is_ocean` and `c:is_aquatic`, so modded aquatic spawns apply. Water colours and
  vanilla fish spawns differ per biome.
- **`subcraft:structure`:** host-built triangles (bases, subs, the lifepod; flag from the host)
  get their own block, voxelized as a **shell**, not a fill. The lifepod's hull is closed with no
  inner faces, so the fill had turned its interior into rock. Unit test: a closed structure box
  is open inside, its plates and walls partial.
- **Saved masks + patch stamps (`ChunkGhostData` attachment):** partial-block masks and a stamp
  per section are saved with the chunk. On chunk load the masks go back into `MaskStore`, so after
  a restart partial blocks keep their shape before the host streams the area again. The stamp is
  an order-independent hash of the section's triangles and those of its column (the host re-sends
  identical triangles in another order). A matching section is not rebuilt.
- **Fix:** the survival inventory crashed Minecraft ("Rendering entity in world", null camera):
  the skipped world pass also calls `EntityRenderDispatcher.prepare`, so we call it ourselves.

**Verified in game**
- Biome at the lifepod `subcraft:safe_shallows`, at (-88, -234) `subcraft:kelp_forest` (host:
  kelpForest).
- Lifepod: the block at the feet is the floor plate (`subcraft:structure`), the block above is
  air, the next is the ceiling.
- Minecraft restarts with Subnautica running: chunks restore their masks (e.g. 1708 in one
  chunk); 51-52 of ~280 sections skipped by stamp. The others are first submitted before their
  column is complete and rebuilt identically (0 blocks changed: idempotent).
- Unknown terrain is inert: a persistent zombie 100 blocks out in never-streamed water stays put
  (same position 8 s apart); one next to the player moves.
- Survival inventory opens (player model shown) and closes with Esc.
- `fake_host.py` one-game run on v15: all six [ok].

**Phase 1 status:** every MISSION.md Phase 1 bullet is in, and every done-when check passed in my
runs (sessions 4-6). What remains for Sean is feel and real-input checks (TESTING.md). Next:
Phase 2 (Unity draws Minecraft's chunks, lights and colliders).

**Known limits:** stamps skip only ~20% after a restart (incomplete columns at first submission).
Dry volumes cover the lifepod only (habitats and subs: Phase 5). Biomes are per 4-block cell, so
narrow cave overrides can be coarse.

## 2026-10-01 — Session 5: Phase 1 — ghost terrain, breath, dry lifepod, one-game input

**Done**
- **Ghost terrain from the triangles** (guest `world/ghost`): each kColTris section is voxelized on
  a worker thread (8x8x8 sub-voxels per block). Inside/outside comes from triangle orientation
  along vertical lines (nearest crossing above faces up = under a floor). Columns with no
  crossing borrow from the nearest known section above/below. Blocks become `subcraft:terrain`
  (full, or partial with a per-position mask shape in `MaskStore`), waterlogged below sea level,
  never replacing a player's block. 4.6-5.9 ms per section; 3261 sections and 580k blocks during a
  200 m swim. 8 new unit tests.
- **Surface materials:** Subnautica's `MaterialDatabase` table ships empty (resources.assets holds
  only the CSV header; Subnautica's own footsteps just play "land" on terrain). The voxel block
  type's name decides instead (Sand02, Coral07, Rock02, `Sand01ToRock02_steep` by slope) -> block
  property `material` -> Minecraft sound type.
- **Protocol v14:** HostState carries oxygen/capacity and underwater/inside flags; `kColDry`
  carries dry boxes (the lifepod's hull; habitats and subs come in Phase 5). The lifepod's
  colliders are now harvested (quantized signature, it bobs).
- **Breath:** Minecraft's air = Subnautica's O2 fraction. Minecraft drowning can't happen (air is
  reset every tick); running out is Subnautica's suffocation. Subnautica's food/water frozen while
  Minecraft drives.
- **Input, one key one game:** `InputRouter` patches every `IGameInput.GetButtonState` (the
  static `GameInput` wrappers are small enough for Mono to inline). While Minecraft drives,
  Subnautica sees only PDA, pause, UI and look, plus left click when the crosshair is on a
  Subnautica interactable (that click isn't sent to Minecraft). `McScreenInput`: an open
  Minecraft screen sits on Subnautica's input stack like the PDA; cursor free, its position and
  typed text go to Minecraft, Esc closes the screen.
- **The diver is gone:** body, arms, held tools and the first-person scuba mask stop rendering
  while Minecraft drives (PDA kept). Subnautica's quickslot bar is hidden. Minecraft's GUI is on its
  own uGUI canvas, hidden while the PDA or pause menu is up.
- **Unknown terrain is inert:** spawn placement fails and non-player entities don't tick in
  sections the host hasn't described. Natural spawning stays off by default (taste call for Sean).

**Found on the way**
- Minecraft's hand vanished underwater: `ScreenEffectRenderer.renderWater` blends with alpha
  factors (ONE, ZERO) and left the whole overlay at alpha 0.1. Full-screen effects are skipped
  while the host draws the world.
- `FindObjectOfType` doesn't see our hidden (`HideAndDontSave`) host object; statics instead.
- `mc_dev.sh start | tail` never returns: the nohup'd Gradle keeps the pipe open (redirect to a
  file instead).

**Verified in game (both games, dev slot)**
- Voxels vs Subnautica: seabed at y -28.32, block -29 partial, -30 full, -28 water. A summoned
  cod and zombie rest on the real surface (cod 0.1 m above it, within one sub-voxel).
- Lifepod: Minecraft's player stands on its floor in air (56 water blocks dried).
- Oxygen (Subnautica Survival): 39/45 s -> air 260/300 ... 0 -> air 0, Minecraft health stays 20;
  Subnautica suffocates and respawns in the lifepod, Minecraft follows with full air.
- Swim test, survival, sprint-swim from the lifepod: Kelp Forest reached after ~30 s, ~180 m in
  50 s, then stopped by a rock face hit nearly head-on (10 degrees off its normal). Landing on Safe
  Shallows seabed: the block under the feet has `material=sand`.
- Minecraft's creative inventory opens over Subnautica (E), closes with Esc through the link.
- Screenshots: no diver arms, tool or mask; Minecraft hand visible underwater.

**Unverified:** real-mouse clicks in Minecraft screens, chat typing, Subnautica ignoring real
number keys and clicks (harness input bypasses Subnautica), footstep sounds by ear. All in
TESTING.md.

**Memory:** both games most of the session, swap 4.4-5.1 GB used of 6; frame rate 21-97 while
streaming terrain. Each Subnautica restart takes ~2 minutes.

**Still open in Phase 1** (MISSION.md bullets not yet done): one Minecraft biome per Subnautica
biome; `subcraft:structure` as its own block (triangles already carry the structure flag); masks
persisted in a chunk attachment (after a restart partial blocks collide as full until re-streamed);
patch stamps per chunk.

## 2026-10-01 — Session 4: Phase 1 begins — exact collision

**Sean's direction:** collision must be "ultra fine": swimming along a terrain face in Subnautica
must be the same movement in Minecraft. Sean approved the Phase 0c look ("everything looks great").

**Decision (extends MISSION.md 3.1, recorded in DESIGN.md):** two collision layers from one source.
The **player** collides with Subnautica's exact collision triangles (capsule collide-and-slide in
Minecraft, as SkyCraft's smooth collider does for Skyrim); the **ghost-terrain blocks** (8x8x8
masks) stay for mobs, pathfinding and other mods, and have no collision for players wherever the
triangles are known (otherwise players would be stair-stepped and the server's movement check
would disagree with the client).

**Done**
- Protocol **v13**: `kColTris` = `ColRegion` + `ColTri[count]` (40 bytes: 9 floats + flags with
  structure/terrain bits and material), one message per 16-block section, count 0 = known empty.
- Host `World/CollisionHarvester`: sections around the player nearest-first (5x5x5), colliders
  from `OverlapBox` on the layers the player's layer collides with, minus triggers, creatures,
  pickupables, vehicles and dynamic rigidbodies; mesh colliders as their triangles, box/sphere/
  capsule tessellated; mirrored into MC space; re-sent when a section's collider signature changes.
- Host `World/TerrainMeshCapture`: Harmony prefix on `ClipmapCell.FinalizeCollidersIfNecessary`
  copies each terrain chunk's collision mesh just before the game clears it.
- Guest `world/tri`: `TriStore` (per-section snapshots, box queries, used by client and server),
  `TriGeometry` (Ericson segment-triangle closest points), `TriCollider` (capsule sized to the
  player's pose; 0.1-block sub-steps; depenetration; step-up with a vertical settle; ~50 degree
  slope limit), `TriDebug` (`subcraft tris`). `EntityMixin`: `@ModifyReturnValue` on
  `Entity.collide` for players in the SubCraft world, and only the into-surface part of the velocity
  is removed after a triangle contact (vanilla zeroes whole axes). Unknown sections are solid.
- Fixes found on the way: the command box ignored commands after a host restart (seq restarts at
  1; "already running" is now per host instance); hold-until-ground also accepts triangles.
- Dev tools: harness `colprobe`, `harvestprobe`, `tricheck`; state dump shows collision stats and
  Minecraft's feet-to-surface gap; `tools/mc_cmd.py` (command box while the real host runs).
- Tests: guest +7 collider tests (floor, terminal velocity, wall slide, 45 degree face glide at
  exactly one radius, ledge step, gentle slope with no creep, out of reach); host 37, all green.

**Verified in game (both games, dev slot):**
- `tricheck`: the harvested copy of a terrain chunk matches Unity's raycast to 0.0000 m (same
  triangle index).
- Landing: the player settles on the seabed 6 cm above the surface point under its feet on a slope
  (the geometric value for a 0.3 m capsule) and stays put.
- Walking W uphill for 6 s across ~12 m of seabed: feet within 2-11 cm of Subnautica's surface;
  0.45-0.65 m only where the ray under the feet' centre finds a dip the 0.6-wide body bridges.

**What it took (each found from measurements):**
1. Terrain collision meshes read as **0 vertices**: Subnautica cooks them into PhysX and clears the
   mesh. -> capture before the clear.
2. First copies didn't match: `Mesh.GetTriangles(List, ...)` *replaces* the list, and the base
   vertex must be applied. -> per-submesh read with `applyBaseVertex: true`.
3. A section was sent "empty" before its terrain streamed in, and the player fell into the rock.
   `LargeWorldStreamer.IsRangeActiveAndBuilt` was then too strict (pads into cells outside the
   window, waits for far object cells). The collision clipmap level is a 5x5x5 window of 16-block
   cells around the camera, aligned with Minecraft sections: a section is sent once its cell is
   loaded; until then Minecraft treats it as solid.
4. Frictionless sliding down slopes under gravity. -> slope limit: ground (<= ~50 degrees) is
   resolved vertically, steeper faces slide.

**Unverified / open**
- Sean's own feel test of swimming along faces (in TESTING.md).
- Ghost-terrain block layer from the triangles (mobs still don't collide with terrain).
- Harvest churn: ~300 section sends in the first minute (terrain chunks finishing, LOD); fine at
  ~0.5 MB/s, to watch.
- The terrain rescue (lift out if inside terrain) fired in the broken runs; untested since the fix.
- Moving structures (Cyclops, Seamoth) are excluded from the harvest for now.

**Memory:** both games ran together most of the session; swap 2-7 GB used of 8; Minecraft heap
~1 GB; frame rate 40-140. Subnautica restarts took ~2 minutes each (build + Steam + load).

**Next (rest of Phase 1):** ghost-terrain masks from the triangles (voxelize: surface shell + solid
fill), oxygen bridge, input routing (keys stop acting in both games), overlay on a uGUI canvas,
camera, and the first step of replacing the diver (hide its first-person arms and tools).

## 2026-10-01 — Session 3: Phase 0c (look spike)

**Sean's direction (recorded):** the Minecraft character must replace Subnautica's diver
completely in the finished game (no diver hands, tools, mask frame or body); today's overlap is a
development stage. Sean verified real keyboard and mouse input from Phase 0b.

**Done**
- Protocol **v12**: `CommandBox` at 0x400: host -> Minecraft debug commands ("/..." as an operator
  at the player, "subcraft dump ..."), reply + status. Mirrored and layout-tested on all sides.
- Guest capture: `RenderClassifier`, `CaptureBuffer`, `TextureGrabber`, `SceneDump`,
  `DebugCommands`, `getShade` = 1 mixin, accessors for RenderType state. `fake_host.py --scene`
  builds the test hut with Minecraft commands and dumps it; `tools/capture_dump.py` decodes, writes
  OBJ/PNG and renders a software preview (checked before touching Unity).
- Host: `DumpReader`, `SceneBuilder`, `MaterialFactory`, `AlbedoBake`, `BlockLights`, harness
  `loaddump`/`matinfo`/`matset`/`rendinfo`/`equip`/`holster`, `tools/sn_restart.sh`,
  `tools/look_shots.py`, scenario `look_place.jsonl`.
- Tests: host 37 (dump reader, albedo bake added), guest unchanged and green.

**Phase 0c result: done (self-judged), Sean to review `docs/look/`.** Iterations, each from
screenshots: (1) MarmosetUBER worked first try for sun, caustics and glow, but colours untinted and
blocks only 4x4 texels; (2) `MARMO_VERTEX_COLOR` toggle changed nothing (variant not compiled);
(3) `SkyApplier` added; tried tint via `UWE_LIGHTMAP` (wrong: it's baked light); (4) Sean pointed
out the 4x4 texels -> no mip chain (Low preset's texture limit); albedo bake for tint x AO ->
green leaves/grass, AO, full-resolution sprites. Final set: noon/dusk/night+flashlight,
near/mid/far, above the surface — blocks take the same absorption, fog, caustics and flashlight as
the rocks around them; torch and glowstone glow and light the wall and sand.

**Unverified / open**
- The standing floor torch disappeared between two scene builds (59 -> 58 blocks); the wall torch
  and glowstone are there. Not chased yet.
- Translucent materials (WBOIT) untested: the scene has no translucent blocks (glass is cutout).
- Shadows: off in the Low preset on this Mac; MarmosetUBER renderers are set to cast/receive.
- Far shot: the hut's shaded side reads a bit darker than rock at the same distance — for Sean.

**Memory:** one game at a time this session (Minecraft only for the dump, then Subnautica only).

**Next:** Phase 1 — the world as blocks: host collision harvest -> masks -> collision ring, ghost
terrain with partial masks, chunk patching, dry volumes, biomes, terrain sound types; oxygen
bridge; camera and input routing; overlay on a uGUI canvas; and the first step of replacing the
diver (hide its first-person arms and tools while Minecraft drives).

## 2026-10-01 — Session 2: Phase 0b (Subnautica link)

**Done**

- Licence: MIT (Sean's decision), `LICENSE` added.
- Saves backed up before anything ran: `~/Development/Modding/SubCraft-save-backups/` (SavedGames
  copy + exported Unity prefs). Sean's `slot0000`/`slot0001` diffed byte-identical afterwards.
- Decompiled Assembly-CSharp(+firstpass) with ilspycmd 11.1 into `.decompiled/` (git-ignored).
- **`host-subnautica`** (net472, BepInEx 5.4.23.5, HarmonyX, publicized Assembly-CSharp via
  BepInEx.AssemblyPublicizer.MSBuild, built with .NET SDK 10 on macOS, post-build deploy):
  - `src/Link`: Proto.cs (mirror of the header), Platform (CLOCK_UPTIME_RAW via libSystem, paths),
    LinkView (unsafe, Volatile/Interlocked), HostLink (creates the sparse 191 MiB file, magic
    written last), Coords (Unity <-> MC, look), KeyMap (Unity KeyCode -> GLFW);
  - `LinkDriver`/`HostState`: heartbeat, McState, events, HostState with camera look, viewport, day;
  - `Player/PlayerPuppet`: Harmony prefix skips `PlayerController.UpdateController` while
    Minecraft drives; camera placed at Minecraft's eye; teleport handshake;
  - `Player/InputCapture`: real keyboard/mouse -> GLFW events (Tab, Esc stay Subnautica's);
  - `Hud/OverlayView`: Minecraft's overlay drawn on top (IMGUI for now);
  - `Dev/DevHarness`, `StateDump`, `RenderRecon`: command-file harness (rule 12).
- **Tests (no game, net10.0 xunit):** layout vs `layout.json`, coordinate and look mapping (round
  trips, Unity euler yaw), key table, input/collision rings, McState seqlock under a writer,
  overlay exchange, HostLink file creation, clock equality with Python's CLOCK_UPTIME_RAW. 34/34.
- **Tools:** `tools/sn_dev.sh` (launch through Steam windowed 960x540, stop + restore prefs),
  `tools/sn_prefs.py`, `tools/sn_cmd.py` (harness client), `tools/fake_minecraft.py` (stand-in
  Minecraft), `tools/scenarios/phase0b.jsonl`, `load_dev_slot.jsonl`.
- **Guest fixes found by the two-game run:** hold the player until the destination chunk is on the
  client and there is ground or water (hold timer now resets per teleport); Minecraft never drowns
  the player in the SubCraft world while linked (host owns oxygen; Phase 1 mirrors it).

**Phase 0b result: done.**
- One game (Subnautica + `fake_minecraft.py`): link up, teleport acked, puppet takes over, injected
  W walks the Subnautica player 14 m; overlay test pattern drawn.
- Both games (Minecraft dev client + Subnautica, dev slot `slot0002`): W injected through the
  harness -> Minecraft's physics moves its player 8 blocks underwater (sinking, in-water flags) ->
  Subnautica's player and camera follow exactly (`docs/screens/0b-before-w.png`, `0b-after-w.png`:
  Minecraft hearts, air, hunger and hotbar over Subnautica's shallows).
- Hard-killing Minecraft returns control to Subnautica after 2.09 s (2.0 s timeout + polling).
- Render-path recon written up in `docs/DESIGN.md` (deferred, HDR, linear, MarmosetUBER
  everywhere, Subnautica's own waterscape fog, WBOIT transparency) with the 0c material strategy.

**Bugs found and fixed on the way:** Steam asks for confirmation when a `steam://run` URL carries
arguments (dev window size moved to Unity prefs); PlistBuddy splits keys on spaces, so the first
prefs restore silently did nothing (rewritten with plistlib, verified); new-game setup briefly uses
a scratch slot named `test`, which the dev-slot detection grabbed; a harness teleport during a
pending teleport got yanked back (re-teleport when Minecraft and host disagree by > 2 m); a
released player in an unloaded chunk fell to y -800 and dragged Subnautica's player with it.

**Decisions**
- Puppet anchors on the eye (camera at Minecraft's eye; host feet = camera - 1.62), not the
  collider, because Subnautica's collider shrinks while swimming.
- Subnautica keeps the mouse look (host owns the look, as SkyCraft); Tab and Esc stay Subnautica's.
- Fail-safe stays at 2 s even though Subnautica's loading stalls its heartbeat 2-6 s: Minecraft
  pausing during loading is harmless and resumes on its own.

**Unverified / not done yet**
- Real keyboard input from a person (all W tests injected through the harness on the same ring
  `InputCapture` writes to). Sean's TESTING step covers it.
- Subnautica still acts on routed keys too (number keys select both hotbars; left click uses
  Subnautica's tool) — Phase 1 input routing.
- Minecraft screens (inventory) can't get the cursor yet — Phase 1.
- Overlay is drawn with IMGUI over everything including Subnautica's PDA — Phase 1 moves it to a
  uGUI canvas below Subnautica's menus.

**Memory (both games):** Minecraft RSS ~0.6-0.9 GB (heap ~0.7 GB used of 2), Subnautica RSS
~0.26 GB at the menu (macOS compresses heavily); swap used 6.7-7.4 GB of 8 GB with Brave and the
Claude app also open. Frame rate dipped to single digits during some seconds; usable for scripted
checks. Two-game runs should stay short on this machine (rule 15).

**Next:** Phase 0c — the look spike: capture a small Minecraft scene (cobblestone, glass, oak
leaves, torch, glowstone, chest) to a dump file, load it in Subnautica with MarmosetUBER
materials and point lights, screenshot noon/dusk/night+flashlight/underwater/above/far.

## 2026-10-01 — Session 1: skeleton and Phase 0a

**Done**

- Repo skeleton (MISSION.md §5): `protocol/`, `guest-neoforge/`, `tools/`, `docs/`, `compat/`,
  `CLAUDE.md`, `README.md`, `THIRD-PARTY-NOTICES.md`, `versions.md`, `.gitignore`.
- Read SkyCraft (`../SkyCraft`, commit `bfcaf17`). It targets Minecraft 26.3/Fabric (SDL input,
  renderpearl GPU API), so its client code ports as ideas; protocol, link and fake host port closely.
- **Protocol v11** (`protocol/subcraft_protocol.h`): SkyCraft v11's layout with a file mapping,
  monotonic-ns timestamps, GLFW key/button codes, a host-neutral `HostState`, survival stats in
  `McState` (0xE0 bytes), a creature table and a generic event ring. Skyrim-only blocks (water grid,
  world entities, dig, ragdoll, skills) dropped. `layout.json` is generated from the header by
  `protocol/layout_dump.cpp` (`tools/check_layout.sh`), and the Java test checks every constant and
  field offset against it.
- **Guest** (NeoForge 21.1.252, ModDevGradle 2.0.148, Gradle 8.11, Java 21 toolchain):
  - link: `Proto`, `Platform` (paths, clock, running lock), `LinkView` (all rings and seqlocks),
    `SubLink` (open/heartbeat/host-restart detection);
  - client: `SubClient` (frame glue, teleport/hold, pacing, fail-safe pause), `InputBridge` (GLFW
    events into KeyboardHandler/MouseHandler), `OverlayExporter` (colour texture -> overlay triple
    buffer), `DevWorld` (opens/creates the dedicated "SubCraft Dev" world);
  - world: `subcraft:terrain` ghost block (invisible, unbreakable, waterloggable, dark to sky light),
    SubCraft dimension type (min_y -2032, height 2288 — Minecraft accepts it) with water below y 0,
    collision ring -> terrain blocks (`CollisionConsumer`, Phase 0a "temporary injection": any
    occupied cell is a full block).
  - mixins (all `@Inject`, MixinExtras or accessors/invokers): runTick head/tail, isWindowActive,
    InputConstants.isKeyDown/grabOrReleaseMouse, LevelRenderer.renderLevel (skip + transparent
    clear) and isSectionCompiled, Gui.renderVignette, WorldOpenFlows backup prompt, invokers for
    MouseHandler and GameRenderer.getFov.
- **Tools:** `tools/fake_host.py` (stand-in host with self-checks), `tools/mc_dev.sh` (start/stop
  exactly one dev client).
- **Tests (no game):** layout (Java vs `layout.json`), input-ring wrap and lapping, collision-ring
  padding, render-ring full/wrap, seqlock under a concurrent writer, overlay triple buffer. 11/11 pass.

**Phase 0a result: done.** `fake_host.py` against the hidden dev client:
linked and heartbeating; clocks agree (median host-now minus MC heartbeat 0.1 ms); player teleported
onto the fake floor and released once ground arrived; W walks it +12.5 blocks on Minecraft physics;
Space jumps it onto the 1-block step (peak y 66.25); the overlay PNG shows hotbar, hearts, hunger,
crosshair and hand on a transparent background. Two host sessions in a row against one Minecraft
pass (re-teleport on new host instance). A 240 s run held ~70 fps hidden the whole time.

**Decisions (flagged where Sean may want a say)**

- *Java 21 has FFM only as a preview*, so the link does not use `FileChannel.map(..., Arena)`:
  it maps with a plain `MappedByteBuffer` and does every seq/head/tail access through
  `MethodHandles.byteBufferViewVarHandle` acquire/release. Monotonic clock = `System.nanoTime()`,
  which on macOS HotSpot is `mach_absolute_time` in ns (CLOCK_UPTIME_RAW) — measured equal to
  Python's `clock_gettime_ns(CLOCK_UPTIME_RAW)` within 0.1 ms. The Mono side must use the same
  clock (Phase 0b: P/Invoke `clock_gettime_nsec_np(CLOCK_UPTIME_RAW)`; verify under Rosetta).
- *World is the overworld slot of a custom preset* (`subcraft:subnautica`), not an extra dimension:
  one dimension type, flat generator with 2032 layers of water. First creation took ~50 s; opening
  it afterwards ~2 s.
- *Host owns the look* (as in SkyCraft): MC's yaw/pitch are set from `HostState` every frame.
- *Death, interim:* while linked, MC reports `kEvPlayerDied` and respawns at once (its death screen
  can't be clicked in a hidden window); the host's death flow replaces this in Phase 5.
- *Fail-safe:* host heartbeat older than 2 s -> Minecraft opens its pause screen (and closes it when
  the host returns). SkyCraft used 8 s because of loading screens; revisit if Subnautica's loading
  stalls its heartbeat (Phase 0b).
- *Vignette off while the host draws the world* (it wrote alpha 1 over the whole overlay).
- `mod_license` in `gradle.properties` is MIT (to match SkyCraft); there is no LICENSE file yet.
  **Sean decides the project licence.**

**Bugs found and fixed on the way:** `pkill -f forgeclientdev` never matched (the dev client is
started through an argfile), so three clients ran at once and fought over the link — now
`tools/mc_dev.sh` matches `fml.modFolders=subcraft`. Accessibility onboarding and the experimental
backup prompt blocked the hidden client. "Loading terrain" never closed because no section is ever
compiled. The vignette made the overlay opaque.

**Unverified**

- App Nap with Minecraft launched by **Prism** (an app bundle) rather than Gradle — the Gradle-launched
  client was never throttled in 4 minutes. Recheck in Phase 5's launch work.
- Overlay readback cost (synchronous `glGetTexImage`): fine at 960x540; measure at full resolution.
- First-person hand looks dull because the skipped world pass leaves its lightmap unset (Phase 3
  captures the hand anyway).

**Memory:** dev client heap peaked ~1.1 GB of 2 GB; RSS read by `ps` 140–930 MB (macOS compresses,
so RSS undercounts). Free disk ~14 GB after Gradle, Java 21, Minecraft and NeoForge caches.

**Next:** Phase 0b — `host-subnautica` scaffold (net472, BepInEx 5, Harmony, publicized
Assembly-CSharp), `Proto.cs` + layout test, `Platform` clock under Rosetta, player puppet,
render-path reconnaissance, `tools/fake_minecraft.py`, dev harness (command file, screenshots,
state dumps). Back up Subnautica saves before running any save-related code.
