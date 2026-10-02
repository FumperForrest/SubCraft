# Compatibility packs

Re-run from Phase 2 on (MISSION.md §3.5). Not run on the 8 GB dev Mac (rule 15): these are for a
bigger machine or a later session.

## Minecraft (NeoForge 1.21.1)

| Mod | Why | Pinned version |
|---|---|---|
| Create | contraptions, Flywheel instancing | — |
| Create: Aeronautics + Sable | moving structures, physics vs ghost terrain | — |
| JEI or EMI | item bridge, recipe GUIs in the overlay | — |
| Jade | HUD overlay from another mod | — |
| One Fabric mod via Sinytra Connector | Connector path | — |
| Aquatic mob mods (Sean picks) | modded ocean spawns | — |

## Subnautica (BepInEx 5)

| Mod | Why | Pinned version |
|---|---|---|
| Nautilus | modded TechTypes, soft dependency | — |
| Most-downloaded creature mod | modded creature proxies | — |
| Most-downloaded vehicle mod | modded vehicles | — |
| Most-downloaded base-piece mod | modded habitats | — |

## Waiting for this machine (from Phase 3)

These Phase 3 items need the mods above and are not built or verified on the dev Mac:

- **Flywheel** (Create's instanced parts): a SubCraft backend, or a Mixin on its instancer, sending
  models once and instance arrays per frame. Today Flywheel-drawn parts are simply missing.
- **Fallback layer** for draws the capture can't classify (custom shaders, raw GL, level-stage
  events): every such RenderType is logged once in Minecraft's log as
  `capturing fallback: <type> failed: ... render type not captured` -> collect those lines.
- **Moving structures' colliders** (contraptions, Aeronautics ships) for Unity.
- Done-when to check: a Create water wheel and gearbox turn, lit by Subnautica; the compat pack
  runs with fallback draws logged.

## Results

### 2026-10-02 (Windows PC): Create 6.0.10 (+ Flywheel 1.0.6), Immersive Vehicles 24.0.0 + MTS Official Pack V29

- **Create kinetics: drawn and turning** (`docs/look/5-create-kinetics.png`): creative motor, shafts,
  cogwheels, large cogwheel, gearbox, lit by Subnautica. How: `FlywheelCompat` switches Flywheel's
  backend to `flywheel:off` while linked (reflection on its config, then `allChanged()`, like its
  own `/flywheel backend` command); Create then draws through its ordinary block entity renderers,
  which the capture already handles. The player's setting comes back on unlink.
- **Immersive Vehicles: vehicles drawn** (`docs/look/5-immersive-vehicles-quad.png`, the red quad):
  it uploads its models into its own `VertexBuffer`s and draws them straight to the GPU, setting
  each part's transform in the shader's ModelViewMat uniform. `VboCapture` (mixins on
  `VertexBuffer.upload/draw/drawWithShader/close` and `RenderStateShard.setupRenderState`) keeps CPU
  copies of uploads and, during the capture, replays each draw into the active RenderType's buffer
  through `inverse(base model-view) x the bound shader's ModelViewMat`, skipping the GPU draw. This
  covers any mod drawing its own vertex buffers or using Tesselator/BufferUploader.
- Immersive Vehicles also needs its `renderLevel` HEAD hook (it grabs the frame's matrices there):
  SubCraft's `LevelRendererMixin` now has priority 1500 so other mods' HEAD hooks run before the skip.
- Its renderer failed every frame before (a crash report per frame: Minecraft fell to 1-13 fps):
  failing renderers are now skipped for 10 s and logged once with the root cause and stack.
- **Known:** a held Immersive Vehicles item shows as a dark shape in first person; its translucent
  pass (`renderLevel` TAIL hook) never runs, so vehicle glass is missing; vehicles don't collide
  with Subnautica's creatures yet (moving colliders).
- Tools: `subcraft field <class> <path>` (read any mod's static state by reflection),
  `subcraft rawdebug <n> world|hand` (log raw draws), `subcraft save`; harness `mouse`.
