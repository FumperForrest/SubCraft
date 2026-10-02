# SubCraft — Mission Directive (v3: one game)

> Play Subnautica as a Minecraft player, with as many Minecraft and Subnautica mods as possible,
> and make it feel as if Subnautica and Minecraft were always meant to be one game: one world,
> one light, one sound, one physics, one economy.

You are building SubCraft. Read this whole file before writing code. When a decision here turns
out wrong, record it in DEVLOG, take the most reasonable path, and flag it at the top of TESTING.md
(stop and wait only for a decision that is expensive to undo).

---

## 1. What you are building

SubCraft follows the architecture of **SkyCraft** (https://github.com/chasmlol/SkyCraft, MIT):
a hidden Minecraft runs beside the host game, and the two talk through one shared-memory block
with a fixed binary protocol. **Minecraft owns the player** (physics, inventory, combat math,
blocks, Minecraft mobs). **Subnautica owns the world** (terrain, creatures, AI, light, sound,
saves) and **draws every pixel of the 3D scene**. Neither game re-implements the other's
mechanics: they only translate.

SkyCraft is a reference implementation, not code to ship unchanged. SubCraft goes further in
three directions:

1. **Compatibility:** Minecraft 1.21.1 NeoForge (+ Sinytra Connector for Fabric mods); the world
   is real Minecraft blocks so other mods see it; rendering is captured at the lowest common draw
   level so modded content appears without per-mod work.
2. **Unity draws Minecraft:** every Minecraft draw in the world pass is captured as geometry and
   drawn by Unity with Subnautica's own lighting, shadows, caustics and fog. Minecraft's GPU
   draws only the GUI.
3. **One game:** shared light, sound, time, HUD, collision both ways, and an item bridge.

## 2. Fixed decisions (do not relitigate without asking Sean)

| Topic | Decision |
|---|---|
| Host game | **Subnautica 1** (Unity 2019.4, Mono, BepInEx 5 + HarmonyX). |
| Platform | **macOS on Apple Silicon**, Steam copy, Subnautica x86_64 under Rosetta. Keep Windows working: platform code only behind a `Platform` layer on each side. |
| Minecraft | **1.21.1 + NeoForge**, Java 21, Mojang mappings in production. Support **Sinytra Connector**. |
| Guest code | New NeoForge mod in this repo, ported from SkyCraft's Fabric mod where useful. |
| Units | **1 block = 1 Unity meter.** `mc = (u.x, u.y, -u.z)`. Yaw/pitch mapping derived and unit-tested. |
| Vertical range | SubCraft dimension `min_y -2032`, `height 2288`; verify Minecraft accepts it. |
| Rendering | **Unity renders all 3D content** from captured Minecraft geometry (§3.2). Minecraft renders only GUI/HUD as a pixel overlay, plus a **fallback layer** for draws that can't be captured. |
| Authority | Minecraft: player physics, health, inventory, placed blocks, hunger, Minecraft mobs. Subnautica: terrain, creatures, oxygen (default), vehicles, bases, light, sound, time, saves, story. |
| Oxygen | Default **Subnautica authoritative**, mirrored into Minecraft's air; Minecraft drowning suppressed. Config `breath = subnautica | minecraft`. |
| Damage scaling | Subnautica → MC **÷ 5**; MC → Subnautica **× 5** default. Config. |
| Shared memory | **File-backed memory map** both sides; default `$TMPDIR/subcraft/link.bin` (macOS), `%LOCALAPPDATA%\SubCraft\link.bin` (Windows). |
| Clock | **Monotonic nanoseconds** across processes. |
| Input codes | **GLFW key codes**. |
| Dev machine | **Sean's Mac has 8 GB RAM.** First goal: a complete, working build that Sean tests later — not a polished one. Dev profile: Minecraft heap **`-Xmx2G`**, NeoForge + SubCraft only (no other mods), Subnautica on lowest settings at a low window resolution. Prefer one-game tests (§8); run both games together only when nothing else can verify the change. A config flag `lowMemory` (default on) reduces capture radius, texture sizes and light counts. |
| Working mode | **Autonomous.** Sean is mostly away. Build through the phases without waiting for him (§4 rule 9). |

## 3. Architecture

### 3.1 The world is real Minecraft blocks ("ghost terrain")

So every Minecraft mod that reasons about blocks — mob spawning and pathfinding, Create
contraptions, Sable/Aeronautics physics, fluids, explosions, raycasts — sees the seabed.

- `subcraft:terrain`: invisible, unbreakable by default, waterloggable, no item. States `full` and
  `partial`; `partial` collision comes from that position's **8×8×8 occupancy mask** (NeoForge
  chunk data attachment; mask format = SkyCraft `ColBlock`). Shapes cached per distinct mask.
  **Sound type** comes from Subnautica's surface material (sand, rock, coral, metal…) so footsteps
  and digging sound right.
- `subcraft:structure`: same, for host-built geometry (habitats, wrecks), removable when
  Subnautica removes it.
- **Real water** below Subnautica's ocean level (verify, ≈ y 0); **dry volumes** (lifepod,
  habitats, Cyclops, Aurora, Precursor dry areas) are air.
- **Biomes:** one Minecraft biome per Subnautica biome, with standard ocean tags so modded aquatic
  spawns work.
- **Filling:** chunk generator makes water-only chunks; host collision **patches** them as
  Subnautica streams terrain (per-chunk data-version stamp, idempotent). Later, **bake mode**
  patches the whole map ahead of time.
- **Ghost terrain blocks light** (opaque to sky light), so deep caves are dark in Minecraft's
  logic too and mob spawning follows Subnautica's geography.
- **Unpatched chunks are inert:** until a chunk has been patched with Subnautica collision, no mob
  spawning, no entity ticking, no fluid ticking in it (otherwise mobs spawn in "open water" that
  later turns out to be rock). Keep Minecraft's simulation distance inside the patched radius.
- v1 trade-off: player blocks may not overlap a `partial` terrain cell.

### 3.2 Unity draws Minecraft (geometry capture)

Minecraft keeps doing all the work of *deciding what to draw* — chunk meshing with any modded
block models, entity models and animation, block-entity renderers, particles, item rendering — but
instead of issuing GPU draws for the world pass, the guest **captures the vertex data** and Unity
draws it as part of Subnautica's scene. Result: Subnautica's sun, shadows, flashlight, vehicle and
base lights, caustics, underwater colour absorption and fog light Minecraft content exactly like
native Subnautica geometry.

**Capture points (generic first, adapters only where needed):**

| Source | Capture | Sent as |
|---|---|---|
| Chunk sections (vanilla + modded block models, connected textures, fluids) | Results of section compilation (`SectionRenderDispatcher` / `SectionCompiler`: `MeshData` per `RenderType`) | Static mesh per section per material class; re-sent on recompile |
| Everything immediate: entities, block entities, items, particles, hand, mod overlays drawn in level-render events | The lowest common draw call (`BufferUploader.drawWithShader` / `RenderType.draw` / `VertexBuffer.drawWithShader`; verify in 1.21.1). **Capture instead of draw.** Record the active model-view matrix, textures and render-state shards | Per-frame dynamic batches (world-space vertices) |
| **Flywheel** (Create's instanced parts) | Implement a **SubCraft Flywheel backend** if Flywheel's backend API allows third-party backends (verify); else Mixin into its instancer/upload layer | Models once; **per-frame instance arrays** (transforms, light, colour) → Unity GPU instancing |
| Textures (block atlas, entity textures, modded atlases, animated sprites) | On bind/upload, by GL texture id + content hash | Upload once; partial updates for animation |

**Material mapping:** classify every draw by its render-state shards (blend mode, alpha cutout,
cull, texture, lightmap, emissive `eyes`-style types), never by RenderType name lists, so modded
RenderTypes map automatically to: opaque, cutout, translucent, emissive, additive.

**Lighting rules:**
- Mixin `getShade(Direction, boolean)` (client level) to return 1.0 in the SubCraft dimension so
  Minecraft's fake face shading is never baked in. Keep Minecraft's **ambient occlusion**.
- Vertex lightmap: **sky light ignored** (Subnautica's sun decides); **block light becomes an
  emission term**, so glowstone and torch flames glow.
- Every light-emitting block (any mod: `BlockState.getLightEmission`) becomes a real **Unity point
  light** (pooled, distance-culled, flicker for flames), so Minecraft torches light Subnautica's
  terrain, creatures and bases.
- Unity materials: **first determine Subnautica's render path and terrain/base shaders at runtime**
  (log `Camera.actualRenderingPath`, material shaders on terrain, base pieces, creatures). Prefer
  driving the game's own shader (likely MarmosetUBER) with Minecraft's atlas, point-filtered, so
  caustics, fog and lighting are identical to native content. Only if that fails, write a matching
  shader that reads the same global fog/caustic properties.
- Shadows: Minecraft geometry casts and receives Subnautica's shadows.
- Stretch: LabPBR resource-pack normal/specular maps for wet, glossy underwater blocks.

**Fallback layer (nothing ever disappears):** draws that can't be classified (custom shaders,
raw GL) are rendered by Minecraft into an offscreen colour + depth layer from the host camera and
composited by Unity **with depth write, before Subnautica's fog**. They keep Minecraft's lighting.
Log each fallback-rendered draw source so it can be promoted to captured later.

**Minecraft's GPU work** shrinks to GUI + fallback layer, which helps on a Mac running both games.

### 3.3 Physics both ways

- Subnautica → Minecraft: ghost terrain (§3.1).
- Minecraft → Subnautica: each section's block **collision shapes** (any mod's `VoxelShape`s,
  not render meshes) become Unity colliders on the section GameObject, so creatures path around
  builds, the Seamoth bumps into them, the Prawn suit stands on them. Moving Minecraft structures
  (Create contraptions, Aeronautics ships) send collision boxes with their per-frame transform.

### 3.4 One game: integration checklist

- **Light:** §3.2. Subnautica's day/night drives Minecraft's `dayTime`; Minecraft weather off.
- **Sound (Sean, 2026-10-02): a mix.** Subnautica keeps its ambience, underwater music, creature
  and vehicle sounds. Minecraft brings over its *important* sounds: the player (steps, hurt, eat),
  blocks (place, break, dig), items, mobs, combat, doors and chests. They are captured
  (`SoundEngine` play/stop, with position, volume, pitch, category) and played by **Subnautica's
  audio** at the right 3D position with Subnautica's **underwater muffling**. Minecraft's own audio
  output muted; Minecraft music, ambient and weather sounds off.
- **Character (Sean, 2026-10-02): Minecraft's character fully replaces Subnautica's**, as in
  SkyCraft. No Subnautica first-person animations (diver body, arms, swim/step/impact camera bob,
  PDA arm pose) whenever linked, vehicles included. Minecraft's camera system exactly: first
  person with Minecraft's FOV, view bobbing and hand (hand FOV and pose as Minecraft draws them);
  F5 third person behind/in front at the distance Minecraft computes against its own blocks, with
  the Minecraft player model drawn by Unity.
- **HUD:** default: Minecraft hotbar + Subnautica's survival dials, with Minecraft health/hunger/air
  shown *in Subnautica's dials* (health dial = MC health, food dial = MC hunger, O2 = oxygen).
  Minecraft hearts/hunger bars hidden. Config to use Minecraft's HUD instead. Depth meter and
  compass stay.
- **Creatures and mobs:** Subnautica creatures are hittable proxies in Minecraft; Minecraft mobs
  (vanilla and modded) are lit, fogged and heard like Subnautica creatures. Minecraft mobs hitting
  proxies damage Subnautica creatures through the same path as the player. The reverse: every
  Minecraft mob gets an invisible Unity stand-in that Subnautica's creature AI can see and target
  (verify how creatures choose targets, e.g. `EcoTarget`), so a reaper hunts a zombie and its bite
  arrives in Minecraft as damage.
- **Item bridge (later phase):** every Subnautica `TechType` (including Nautilus-added ones) gets a
  generated Minecraft item with its Subnautica icon. Picking up Subnautica resources gives those
  items; Minecraft recipes can use them; breaking Subnautica outcrops with Minecraft tools drops
  their Subnautica loot. Direction Minecraft → Subnautica fabricator: open question.
- **One save, one launch, one death:** Subnautica save slot ↔ Minecraft world snapshot; Minecraft
  starts invisibly with Subnautica and quits with it; loading screens synced; death and respawn
  run once, through Subnautica's flow.

### 3.5 Compatibility rules

**Guest:** NeoForge events/APIs over Mixins; Mixins only `@Inject` / MixinExtras
(`@WrapOperation`, `@ModifyReturnValue`) — no `@Overwrite`, no `@Redirect`. Never special-case
vanilla content (use shapes, bounding boxes, tags, render-state shards, light emission). Change
behaviour only in the SubCraft dimension. Sodium/Embeddium/Iris are **not needed** (Minecraft
doesn't draw the world); detect them and warn if their chunk path bypasses §3.2 capture.

**Host:** generic game types (`Creature`, `LiveMixin`, `Vehicle`, `SubRoot`, `Base`, colliders
by layer), never TechType whitelists. Smallest Harmony patches; postfix over prefix; no
transpilers unless unavoidable. Soft-depend on Nautilus.

**Compat packs** (`compat/`, re-run every phase from Phase 2): Minecraft — Create,
Create: Aeronautics + Sable, JEI or EMI, Jade, one Fabric mod via Connector, aquatic mob mods
Sean picks. Subnautica — Nautilus plus content mods Sean picks (at least one modded creature, one
modded vehicle, one modded base piece).

## 4. Ground rules

1. **Never re-implement a game mechanic.** Translate; don't simulate.
2. **One protocol source of truth:** `protocol/subcraft_protocol.h` (start from SkyCraft v10),
   mirrored in `Proto.java` and `Proto.cs`, layout-tested on all sides against
   `protocol/layout.json`. Any change bumps `kVersion`.
3. **Memory ordering:** arm64 JVM ↔ x86_64 Mono under Rosetta. All seqlock/ring fields via
   acquire/release (Java `VarHandle` + fences; C# `Volatile` / `Interlocked`).
4. **Verify every game API by decompiling** (`ilspycmd` → `.decompiled/`, git-ignored; Minecraft
   sources via the NeoForge dev environment). Names in this file are leads, not facts.
5. **Never commit game files.** Reference Subnautica via `SUBNAUTICA_DIR` / untracked `GamePath.props`.
6. Keep SkyCraft's MIT notice for derived code; credit SkyCraft in the README.
7. **Fail safe:** heartbeat lost > 2 s → Subnautica gives control back, Minecraft pauses.
8. **Log generously** behind a diagnostics flag.
9. **One phase at a time, without blocking on Sean.** Finish a phase when its automated tests pass
   and your own in-game verification (rule 12) looks right. Then append that phase's human test
   to **`docs/TESTING.md`** (exact steps, expected results, screenshots to compare, log lines to
   send back) and **continue to the next phase.** Stop and wait only when (a) you need a decision
   only Sean can make and no default in §9 covers it, or (b) you are stuck after several genuinely
   different attempts. Never mark a phase done on untested code: anything you could not verify is
   listed as unverified in DEVLOG and TESTING.md.
10. **Visual checkpoints include screenshots:** before/after comparisons Sean can judge ("does it
    look like it belongs?") are part of done-when for every rendering phase.
11. Small commits; a `docs/DEVLOG.md` entry per session.
12. **Verify your own work in-game before asking Sean.** Build a dev harness early (Phase 0b):
    - the host plugin watches a command file (`$TMPDIR/subcraft/cmd.jsonl`) and executes debug
      commands: teleport, set time of day, toggle flashlight, spawn creature, look at, wait,
      **screenshot** (`ScreenCapture`) and **state dump** (JSON), writing results to
      `$TMPDIR/subcraft/out/`;
    - the guest accepts the same through the link (run a Minecraft command, give item, place
      block);
    - launch the game yourself (`open steam://run/264710`; Steam launch options already route
      through BepInEx) and drive scripted scenarios (`tools/scenarios/*.jsonl`).
    Look at your own screenshots and fix what's obviously wrong. Sean's tests (TESTING.md) are for feel,
    taste and things you can't judge, not for catching crashes.
13. **Never touch Sean's real saves.** Use a dedicated dev save slot and a dedicated Minecraft
    world; back up Subnautica's save folder before the first run of any save-related code.
14. **Pin versions** in `versions.md` (NeoForge, Flywheel, Create, Sable, Aeronautics, Connector,
    BepInEx, Nautilus, Subnautica build) and change them deliberately, with a DEVLOG note.
15. **Memory budget (8 GB Mac):** Minecraft heap 2 GB in the dev profile; measure both processes
    whenever both run and record it in DEVLOG. If a two-game run swaps heavily, say so in DEVLOG
    and fall back to one-game verification rather than fighting it. Don't run the full compat
    packs on this machine; list them in TESTING.md for a bigger machine or a later session.

## 5. Repo layout

```
subcraft/
  MISSION.md  CLAUDE.md  README.md  THIRD-PARTY-NOTICES.md
  protocol/          subcraft_protocol.h, layout.json
  host-subnautica/   BepInEx plugin (C#, net472) + tests
    src/Link/ src/Player/ src/World/ src/Render/ src/Audio/ src/Hud/ src/Combat/ src/Items/
  host-native/       Unity native plugin for shared textures (fallback layer; only if needed)
  guest-neoforge/    NeoForge 1.21.1 mod (Java 21, ModDevGradle) + tests
    link/ world/ capture/ flywheel/ audio/ combat/ items/
  compat/            mod lists + results per phase
  tools/  fake_host.py  fake_minecraft.py  dump_link.py  capture_dump.py
  docs/   DESIGN.md  DEVLOG.md
```

## 6. Read these SkyCraft files first (clone at `../SkyCraft`)

1. `docs/DESIGN.md` (some details stale; code wins). 2. `protocol/skycraft_protocol.h` —
especially the render ring (`RenSection`, `RenVertex`, `RenScene`, `RenBatch`, `RenLights`).
3. `fabric/.../link/SkyLink.java`, `Proto.java`. 4. `fabric/.../client/render/WorldExporter.java`,
`AvatarExporter.java`, `BlockLightColors.java` — SkyCraft's (vanilla-only) version of §3.2.
5. `fabric/.../client/SkyClient.java`, `InputBridge.java`, `combat/*`. 6. `skse/src/WorldRender.cpp`,
`BlockLights.cpp`, `Game.cpp`, `Input.cpp`, `Overlay.cpp`, `Combat.cpp`. 7. `tools/fake_skyrim.py`.

## 7. Phases

### Phase 0a — Guest skeleton + portable link (no Subnautica)
- `guest-neoforge` skeleton; port `SkyLink`/`Proto` with a `Platform` layer (file-backed map via
  `FileChannel.map(..., Arena)`, monotonic ns via FFM `clock_gettime`, `tryLock` single instance,
  `ProcessHandle` pid). Protocol **v11** (monotonic ns, GLFW codes).
- Port `McState` publishing (with tick interpolation), input injection, CPU GUI overlay export.
- **Test first:** hidden Minecraft on macOS keeps ticking and rendering the GUI offscreen? If App
  Nap stalls it: 1×1 borderless window, then disable App Nap via FFM. Record in DEVLOG.
- `tools/fake_host.py` (port of `fake_skyrim.py`).
- **Done when:** `fake_host.py` + hidden Minecraft runs 60 s on Sean's Mac; heartbeats; player
  walks on a fake floor (temporary collision injection OK); overlay PNG shows the hotbar.

### Phase 0b — Subnautica link
- Scaffold `host-subnautica` (net472; BepInEx, 0Harmony, UnityEngine*, publicized
  `Assembly-CSharp`, `Microsoft.NETFramework.ReferenceAssemblies`); post-build copy to
  `<game>/BepInEx/plugins/SubCraft/`.
- `Proto.cs` + layout test; host creates the mapping. Per-frame state both ways.
- Player puppet (decompile `Player`, `PlayerController`, `UnderwaterMotor`, `GroundMotor`);
  teleport handshake.
- **Render-path reconnaissance:** log Subnautica's rendering path, camera image effects, and the
  shaders/keywords on terrain, base pieces, creatures and the player's tools; write findings to
  `docs/DESIGN.md`. This decides §3.2's material strategy.
- `tools/fake_minecraft.py`.
- Dev harness (ground rule 12): command file, screenshots, state dumps, scripted scenarios.
- **Done when:** W moves the Subnautica player through Minecraft's physics; closing Minecraft
  returns control within 2 s; render-path findings written up.

### Phase 0c — Look spike (gate for the whole project)

The project's promise is that Minecraft blocks look native in Subnautica. Prove it before
building the rest.
- Guest: in `fake_host.py`'s world, build a small test scene (cobblestone, glass, oak leaves,
  torch, glowstone, a chest) and dump its captured section meshes + atlas to disk
  (`capture_dump.py` format).
- Host: load the dump from disk (no live link needed) and place it on the seafloor near the
  lifepod, using the material strategy from the render-path reconnaissance. Torch/glowstone →
  point lights.
- Use the dev harness to screenshot it at noon, at dusk, at night with the flashlight, from inside
  the water and from above the surface, near and far (fog).
- **Gate (self-judged for now):** compare your screenshots with native Subnautica objects in the
  same shots. Iterate until lighting, fog and caustics on the blocks match their surroundings to
  your eye, then save the set to `docs/look/` and put "review the look" at the top of TESTING.md.
  Sean reviews it later; continue to Phase 1.

### Phase 1 — The world as blocks; walk and swim
- Host collision harvest → masks → collision ring. Guest ghost terrain, attachments, chunk
  generator, patching, dry volumes (v12), biomes, terrain sound types.
- Oxygen bridge; freeze Subnautica food/water. Camera, input routing (Unity KeyCode → GLFW table),
  GUI overlay on a top uGUI canvas.
- **Done when:** swim lifepod → Kelp Forest on Minecraft physics; dry in the lifepod; air tracks
  oxygen; footsteps on sand sound like sand; a `/summon`ed cod swims without passing through terrain.

### Phase 2 — Unity draws Minecraft (chunks + lights + colliders)
- Capture chunk-section meshes and textures; Unity builds section GameObjects with the material
  strategy from Phase 0b; shade disabled, AO kept, block light → emission.
- Light-emitting blocks → pooled Unity point lights. Block collision shapes → Unity colliders.
- **Done when (screenshots):** a cobblestone hut with torches on the seafloor at noon, at night,
  and under the Seamoth's headlights looks native: caustics on its roof, fog swallowing it at
  distance, its torches lighting the sand; a peeper bounces off its wall; the Seamoth collides.

### Phase 3 — Everything dynamic
- Generic immediate-draw capture: entities, block entities, items, particles, hand, mod overlays.
  Render-state classification; per-frame batches; texture streaming incl. animated sprites.
- Flywheel backend (or Mixin fallback) → Unity instancing.
- Fallback layer for unclassifiable draws (CPU readback first; `host-native` shared textures only
  if needed for performance).
- Moving Minecraft structures' colliders.
- **Done when (screenshots):** a zombie, a dropped item, torch particles and Sean's first-person
  arm are all darker at depth and lit by the flashlight; a Create water wheel and gearbox turn,
  lit by Subnautica; the compat pack runs with fallback draws logged.

### Phase 4 — Combat, sound, time, HUD
- Creature proxies; MC hits → `LiveMixin.TakeDamage` (verify) + knockback; creature hits →
  cancelled, sent as `kInHurt`; grabs hand control to Subnautica; Minecraft mobs can damage
  creatures.
- Sound capture → Subnautica audio with underwater muffling; Minecraft audio muted; the mix in 3.4.
- Character replacement (3.4): Minecraft camera (FOV, bobbing, F5 third person), diver gone
  everywhere, no Subnautica first-person animations.
- Day/night sync. Unified HUD (Minecraft stats in Subnautica's dials) + config.
- **Done when:** a stalker fight with sword and bow sounds and looks native; a zombie's groan is
  muffled underwater and clear in the lifepod; the health dial drops when a sand shark bites.

### Phase 5 — Habitats, vehicles, saves, launch, bake
- Piloting/ladders/hatches; moving Cyclops (dry volume + structure blocks move with it);
  habitat build/deconstruct → structure blocks + dry volumes.
- Save slot ↔ world snapshot; invisible auto-launch via Prism; synced loading; single death flow.
- Bake mode.
- **Done when:** an Aeronautics airship flies over the islands colliding with Subnautica terrain;
  the Cyclops carries Sean and his Minecraft chest; loading an older save rewinds his builds.

### Phase 6 — Item bridge
- Generated Minecraft items per `TechType` (incl. modded) with Subnautica icons; pickups;
  outcrop breaking with Minecraft tools; recipe hooks. Ask Sean about the fabricator direction.
- **Done when:** Sean mines a limestone outcrop with a pickaxe, gets titanium as a Minecraft item,
  and uses it in a Minecraft recipe.

## 8. Testing
- **No game:** layout, coordinate mapping, GLFW table, ring wrap-around, seqlock torn reads,
  mask → VoxelShape, patch idempotency, render-state classification table, capture → world-space
  transform round trips.
- **One game:** `fake_host.py` ↔ Minecraft (dump captured geometry to OBJ/PNG with
  `capture_dump.py`); Subnautica ↔ `fake_minecraft.py` (feed recorded captures into Unity).
- **Both games:** your own scripted runs when memory allows; Sean later, from TESTING.md. Compat packs on a bigger machine.

## 9. Open questions — use these defaults; Sean may override later
1. **HUD:** unified (Minecraft hotbar + Subnautica dials showing Minecraft stats); keep Subnautica's
   depth meter, compass and beacon pings. Config switch for Minecraft's HUD.
2. **PDA/fabricators/inventory:** `Tab` opens Subnautica's PDA (databank, map, scanner log);
   fabricators open through Subnautica's own UI via `G` and use the item bridge once it exists.
   Subnautica's own inventory screen stays hidden.
3. **Item bridge direction:** Subnautica → Minecraft first; Minecraft → fabricator later.
4. **Compat packs:** Create, Create: Aeronautics + Sable, JEI, Jade, one Fabric mod via Connector;
   Subnautica: Nautilus + the most-downloaded creature, vehicle and base-piece mods compatible with
   current BepInEx. Listed in `compat/`, run on a bigger machine later.
5. **Multiplayer:** dropped for now. Don't port SkyCraft's e4mc/Discord code; don't break the
   architecture for it either.

## 10. Start here (first session)
1. Read §6. 2. Create the skeleton (§5), `CLAUDE.md`, `.gitignore`, notices.
3. Get an empty `guest-neoforge` mod building and running in a Prism 1.21.1 NeoForge instance.
4. Do Phase 0a, then keep going per rule 9.

Phase order: 0a → 0b → **0c (look spike)** → 1 → 2 → 3 → 4 → 5 → 6. At the end of every
session: DEVLOG entry, TESTING.md up to date, everything committed and pushed.
